package org.bunnys.bunnynexus.alerts.adapters.providers;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerFetcher.*;

class GamerPowerFetcherTest {
    final AtomicLong nanos = new AtomicLong();
    final Budgets budgets = new Budgets(Duration.ofSeconds(5), Duration.ofSeconds(2));
    final Call.Factory factory = mock(Call.Factory.class);
    final Call call = mock(Call.class);
    final AtomicInteger cleanups = new AtomicInteger();
    GamerPowerFetcher fetcher() {
        when(factory.newCall(any())).thenReturn(call);
        return new GamerPowerFetcher(factory, cleanups::incrementAndGet, budgets,
                Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC), nanos::get);
    }
    Response response(int status, String type, String encoding, ResponseBody body) {
        return new Response.Builder().request(new Request.Builder().url(GamerPowerIntake.ENDPOINT.toString()).build())
                .protocol(Protocol.HTTP_1_1).code(status).message("synthetic fixture")
                .header("Content-Type", type).header("Content-Encoding", encoding).body(body).build();
    }
    Response json(int status, String text) {
        return response(status, "application/json", "identity", ResponseBody.create(text, MediaType.get("application/json")));
    }
    @Test void fixedRequestDecodesAndPacesWithoutClockWallTimeDependence() throws Exception {
        try (var f = fetcher()) {
            when(call.execute()).thenReturn(json(200, "[]"));
            assertEquals(State.FETCHED, f.fetch().state()); assertEquals(State.THROTTLED, f.fetch().state());
            var request = org.mockito.ArgumentCaptor.forClass(Request.class); verify(factory).newCall(request.capture());
            assertEquals(GamerPowerIntake.ENDPOINT.toString(), request.getValue().url().toString());
            assertEquals("identity", request.getValue().header("Accept-Encoding"));
            nanos.set(Duration.ofSeconds(2).toNanos()); when(call.execute()).thenReturn(json(200, "[]"));
            assertEquals(State.FETCHED, f.fetch().state());
        }
    }
    @Test void rateAndAuthorizationFailuresPauseWithoutAutomaticRetry() throws Exception {
        for (int status : new int[]{429, 401, 403}) {
            reset(factory, call);
            try (var f = fetcher()) {
                when(call.execute()).thenReturn(json(status, "[]"));
                assertEquals(Failure.HTTP_STATUS, f.fetch().failure()); nanos.addAndGet(Duration.ofDays(30).toNanos());
                assertEquals(State.PAUSED, f.fetch().state()); verify(call, times(1)).execute();
            }
        }
    }
    @Test void failuresBackOffAndSuccessResetsThePenalty() throws Exception {
        try (var f = fetcher()) {
            when(call.execute()).thenThrow(new IOException("private payload"));
            assertEquals(Failure.NETWORK, f.fetch().failure());
            nanos.set(Duration.ofSeconds(3).toNanos()); assertEquals(State.THROTTLED, f.fetch().state());
            nanos.set(Duration.ofSeconds(4).toNanos()); doReturn(json(200, "[]")).when(call).execute();
            assertEquals(State.FETCHED, f.fetch().state());
            nanos.set(Duration.ofSeconds(6).toNanos()); when(call.execute()).thenReturn(json(200, "[]"));
            assertEquals(State.FETCHED, f.fetch().state());
        }
    }
    @Test void redirectsErrorsAndPartialFeedsCannotLookLikeSuccessfulEmptyFeed() throws Exception {
        for (int status : new int[]{301, 302, 307, 404, 500, 503}) {
            reset(factory, call);
            try (var f = fetcher()) {
                when(call.execute()).thenReturn(json(status, "[]")); assertEquals(Failure.HTTP_STATUS, f.fetch().failure());
            }
        }
        try (var f = fetcher()) {
            when(call.execute()).thenReturn(json(200, "[null]"));
            var result = f.fetch(); assertEquals(Failure.INVALID_FEED, result.failure());
            assertEquals(GamerPowerIntake.State.PARTIAL, result.batch().orElseThrow().state());
        }
    }
    @Test void capturedNoResultsEnvelopeIsSuccessfulButArbitrary201IsNot() throws Exception {
        String body;
        try (var input = getClass().getResourceAsStream("/gamerpower/ps4-games-2026-09-22.json")) {
            body = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (var f = fetcher()) {
            when(call.execute()).thenReturn(json(201, body));
            var result = f.fetch();
            assertEquals(State.FETCHED, result.state()); assertEquals(201, result.httpStatus());
            assertEquals(GamerPowerIntake.State.EMPTY, result.batch().orElseThrow().state());
            nanos.set(Duration.ofSeconds(2).toNanos()); when(call.execute()).thenReturn(json(201, "[]"));
            assertEquals(Failure.INVALID_FEED, f.fetch().failure());
        }
    }
    @Test void rejectsEncodingMediaTypeAndDeclaredOrStreamedOversize() throws Exception {
        ResponseBody compressed = ResponseBody.create("[]", MediaType.get("application/json"));
        ResponseBody html = ResponseBody.create("[]", MediaType.get("text/html"));
        ResponseBody oversized = mock(ResponseBody.class);
        when(oversized.contentType()).thenReturn(MediaType.get("application/json"));
        when(oversized.contentLength()).thenReturn((long) GamerPowerIntake.MAX_BYTES + 1);
        // Unknown content length exercises the actual stream cap, not just header validation.
        ResponseBody chunked = new ResponseBody() {
            final Buffer source = new Buffer().write(new byte[GamerPowerIntake.MAX_BYTES + 100]);
            public MediaType contentType() { return MediaType.get("application/json"); }
            public long contentLength() { return -1; }
            public okio.BufferedSource source() { return source; }
        };
        var responses = List.of(response(200, "application/json", "gzip", compressed),
                response(200, "text/html", "identity", html), response(200, "application/json", "identity", oversized),
                response(200, "application/json", "identity", chunked));
        var expected = List.of(Failure.ENCODING, Failure.MEDIA_TYPE, Failure.TOO_LARGE, Failure.TOO_LARGE);
        for (int i = 0; i < responses.size(); i++) try (var f = fetcher()) {
            when(call.execute()).thenReturn(responses.get(i)); assertEquals(expected.get(i), f.fetch().failure());
        }
        verify(oversized, never()).byteStream();
    }
    @Test void lateResponseCannotBeAcceptedAndAllBodiesClose() throws Exception {
        try (var f = fetcher()) {
            var body = spy(ResponseBody.create("[]", MediaType.get("application/json")));
            when(call.execute()).thenAnswer(i -> {
                nanos.set(Duration.ofSeconds(5).toNanos()); return response(200, "application/json", "identity", body);
            });
            assertEquals(Failure.DEADLINE, f.fetch().failure()); verify(body).close();
        }
    }
    @Test void closeCancelsActiveCallAndBusyAdmissionRemainsHeldUntilItReturns() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var f = fetcher(); var executor = Executors.newSingleThreadExecutor()) {
            when(call.execute()).thenAnswer(i -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return json(200, "[]"); });
            var future = executor.submit(f::fetch);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(State.BUSY, f.fetch().state());
                f.close(); verify(call).cancel(); assertEquals(State.CLOSED, f.fetch().state());
            } finally { release.countDown(); }
            assertEquals(State.CLOSED, future.get(5, TimeUnit.SECONDS).state()); verify(call, times(1)).execute();
        }
    }
    @Test void productionClientHasNoRedirectProxyDnsFallbackOrSecondExchange() throws Exception {
        var ip = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        var client = GamerPowerFetcher.client(List.of(ip), budgets);
        assertFalse(client.followRedirects()); assertFalse(client.followSslRedirects()); assertFalse(client.retryOnConnectionFailure());
        assertEquals(Proxy.NO_PROXY, client.proxy()); assertEquals(5000, client.callTimeoutMillis());
        assertEquals(List.of(ip), client.dns().lookup("www.gamerpower.com"));
        assertThrows(UnknownHostException.class, () -> client.dns().lookup("elsewhere.example"));
        var chain = mock(Interceptor.Chain.class);
        var request = new Request.Builder().url(GamerPowerIntake.ENDPOINT.toString()).tag(AtomicBoolean.class, new AtomicBoolean()).build();
        when(chain.request()).thenReturn(request); when(chain.proceed(request)).thenReturn(json(503, "[]"));
        client.networkInterceptors().getFirst().intercept(chain).close();
        assertThrows(IOException.class, () -> client.networkInterceptors().getFirst().intercept(chain));
        verify(chain, times(1)).proceed(request);
    }
    @Test void reservedPrivateAndIpv6PinsFailBeforeAnyNetworkWork() throws Exception {
        for (String address : List.of("0.1.2.3", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.168.1.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "2001:4860:4860::8888")) {
            var ip = InetAddress.getByName(address);
            assertThrows(IllegalArgumentException.class, () -> ProviderAddresses.gamerPower(List.of(ip)), address);
        }
        assertThrows(IllegalArgumentException.class, () -> ProviderAddresses.gamerPower(List.of()));
    }
    @Test void actualHttpCallDeadlineCancelsAStalledResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
             var executor = Executors.newFixedThreadPool(2)) {
            server.setSoTimeout(3000);
            var serving = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n[".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    release.await(3, TimeUnit.SECONDS);
                }
                return null;
            });
            var budget = new Budgets(Duration.ofMillis(200), Duration.ofSeconds(1));
            var client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(budget.timeout()).retryOnConnectionFailure(false).build();
            // Loopback override exists only in the package-private test seam, never the public constructor.
            Call.Factory loopback = request -> client.newCall(request.newBuilder()
                    .url("http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + "/").build());
            try (var f = new GamerPowerFetcher(loopback, () -> client.connectionPool().evictAll(), budget, Clock.systemUTC(), System::nanoTime)) {
                var result = executor.submit(f::fetch).get(3, TimeUnit.SECONDS);
                assertEquals(State.FAILED, result.state()); assertEquals(Failure.DEADLINE, result.failure());
            } finally { release.countDown(); }
            serving.get(3, TimeUnit.SECONDS);
        }
    }
}


