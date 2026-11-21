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
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    /** The last good response, so "Free next week" needs no request of its own. */
    private volatile byte[] lastBody;
    EpicFeed(Clock clock) {
        this.clock = clock;
        client = FreebieHttp.client(host -> {
            if (!EpicIntake.ENDPOINT.getHost().equals(host)) throw new UnknownHostException("Unapproved provider host");
            var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
            if (addresses.isEmpty()) throw new UnknownHostException("No public provider address");
            return addresses;
        }, Duration.ofSeconds(20));
    }
    @Override public Provider provider() { return Provider.EPIC; }
    @Override public Result poll() {
        var request = new Request.Builder().url(EpicIntake.ENDPOINT.toString()).header("Accept", "application/json")
                .header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
        try {
            return calls.execute(client, request, response -> {
                if (response.code() != 200) return new Result(State.FAILED, List.of(),
                        GamerPowerFeed.retryAfter(response.header("Retry-After"), clock.instant()), "HTTP " + response.code());
                byte[] body = response.body().byteStream().readNBytes(GamerPowerIntake.MAX_BYTES + 1);
                lastBody = body;
                return new EpicIntake().decode(body, clock.instant());
            });
        } catch (IOException failure) {
            return new Result(State.FAILED, List.of(), Optional.empty(), "network: " + failure.getClass().getSimpleName());
        }
    }
    OkHttpClient client() { return client; }

    /** Next week's free games from the last poll; empty before the first one. */
    List<FreebieUpcoming> upcoming() { return EpicIntake.upcoming(lastBody, clock.instant()); }

    @Override public void close() { calls.close(); }
}
