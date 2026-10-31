package org.bunnys.bunnynexus.freebies;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FreebieHttpTest {
    private static Map<String, OkHttpClient> clients(Clock clock) {
        return Map.of("GamerPower", new GamerPowerFeed(clock).client(), "Epic", new EpicFeed(clock).client(),
                "EGData", new EgDataFeed(clock).client(), "Steam", new SteamFeed(clock).client(),
                "GamerPowerLinks", GamerPowerLinks.CLIENT);
    }

    @Test void everyFeedClientSharesTheBasePoolAndDispatcher() {
        var base = FreebieHttp.base();
        clients(Clock.systemUTC()).forEach((name, client) -> {
            assertSame(base.connectionPool(), client.connectionPool(), name);
            assertSame(base.dispatcher(), client.dispatcher(), name);
        });
    }

    @Test void derivedClientsKeepTheirGuardsRedirectAndProxyPolicies() {
        clients(Clock.systemUTC()).forEach((name, client) -> {
            assertFalse(client.followRedirects(), name);
            assertFalse(client.followSslRedirects(), name);
            assertSame(Proxy.NO_PROXY, client.proxy(), name);
            // Loopback and unapproved hosts are refused by the feed's own resolver, without touching the network.
            for (String host : List.of("localhost", "127.0.0.1", "metadata.google.internal", "example.com"))
                assertThrows(UnknownHostException.class, () -> client.dns().lookup(host), name + " " + host);
        });
    }

    @Test void derivedClientsKeepTheirTimeouts() {
        var clock = Clock.systemUTC();
        for (var client : List.of(new GamerPowerFeed(clock).client(), new EpicFeed(clock).client(),
                new EgDataFeed(clock).client(), new SteamFeed(clock).client()))
            assertEquals(List.of(20_000, 20_000, 20_000, 20_000), List.of(client.callTimeoutMillis(),
                    client.connectTimeoutMillis(), client.readTimeoutMillis(), client.writeTimeoutMillis()));
        var links = GamerPowerLinks.CLIENT;
        assertEquals(5_000, links.callTimeoutMillis());
        assertEquals(5_000, links.readTimeoutMillis());
    }

    @Test void derivedClientDoesNotFollowARedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            // An IP literal skips DNS, so this reaches the local server; the redirect must come back unfollowed.
            var client = FreebieHttp.client(host -> { throw new UnknownHostException(host); }, Duration.ofSeconds(5));
            var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/").build();
            try (var response = client.newCall(request).execute()) {
                assertEquals(302, response.code());
            }
        } finally { server.stop(0); }
    }

    @Test void closingOneFeedCancelsOnlyItsOwnCalls() throws Exception {
        var arrived = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = java.util.concurrent.Executors.newFixedThreadPool(2);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            arrived.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200, 2);
            try (var body = exchange.getResponseBody()) { body.write("ok".getBytes()); }
        });
        server.start();
        var client = FreebieHttp.client(host -> { throw new UnknownHostException(host); }, Duration.ofSeconds(10));
        var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/").build();
        var closed = new FreebieHttp.Calls();
        var other = new FreebieHttp.Calls();
        var closedResult = new AtomicReference<Object>();
        var otherResult = new AtomicReference<Object>();
        Thread first = Thread.ofPlatform().start(() -> closedResult.set(run(closed, client, request)));
        Thread second = Thread.ofPlatform().start(() -> otherResult.set(run(other, client, request)));
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            closed.close();
            first.join(5_000);
            assertInstanceOf(IOException.class, closedResult.get(), "the closed feed's call is cancelled");
            release.countDown();
            second.join(5_000);
            assertEquals(200, otherResult.get(), "the other feed's call on the shared pool completes");
            assertEquals(0, closed.openCalls());
            assertEquals(0, other.openCalls());
            assertThrows(IOException.class, () -> closed.execute(client, request, r -> r.code()), "closed feeds start nothing");
        } finally { release.countDown(); server.stop(0); handlers.shutdownNow(); }
    }

    private static Object run(FreebieHttp.Calls calls, OkHttpClient client, Request request) {
        try { return calls.execute(client, request, response -> response.code()); }
        catch (IOException failure) { return failure; }
    }
}
