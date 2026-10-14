package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.io.IOException;

/** Fixed public endpoint, bounded body and timeouts; no redirects, proxies or private DNS answers. */
final class EpicFeed implements FreebieFeed {
    private final Clock clock;
    private final OkHttpClient client;
    EpicFeed(Clock clock) {
        this.clock = clock;
        client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .dns(host -> {
                    if (!EpicIntake.ENDPOINT.getHost().equals(host)) throw new UnknownHostException("Unapproved provider host");
                    var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
                    if (addresses.isEmpty()) throw new UnknownHostException("No public provider address");
                    return addresses;
                }).callTimeout(Duration.ofSeconds(20)).connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(20)).writeTimeout(Duration.ofSeconds(20)).build();
    }
    @Override public Provider provider() { return Provider.EPIC; }
    @Override public Result poll() {
        var request = new Request.Builder().url(EpicIntake.ENDPOINT.toString()).header("Accept", "application/json")
                .header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 200) return new Result(State.FAILED, List.of(),
                    GamerPowerFeed.retryAfter(response.header("Retry-After"), clock.instant()), "HTTP " + response.code());
            return new EpicIntake().decode(response.body().byteStream().readNBytes(GamerPowerIntake.MAX_BYTES + 1), clock.instant());
        } catch (IOException failure) {
            return new Result(State.FAILED, List.of(), Optional.empty(), "network: " + failure.getClass().getSimpleName());
        }
    }
    @Override public void close() {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
