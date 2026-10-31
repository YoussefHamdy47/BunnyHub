package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the GamerPower game feed. Uses normal DNS (the previous fixed IP pins broke whenever the
 * provider's CDN moved) but still refuses private/loopback answers, redirects, proxies and large bodies.
 * Blocking: call from the discovery thread only.
 */
public final class GamerPowerFeed implements FreebieFeed {
    @Override public Provider provider() { return Provider.GAMERPOWER; }
    @Override public Result poll() {
        var result = fetch();
        var batch = result.batch();
        State state = result.complete() ? State.COMPLETE
                : batch.isPresent() && batch.get().state() == GamerPowerIntake.State.PARTIAL ? State.PARTIAL : State.FAILED;
        return new Result(state, batch.map(b -> b.candidates().stream().map(Candidate::from).toList()).orElse(List.of()),
                result.retryAfter(), state == State.PARTIAL ? "partial feed: " + batch.orElseThrow().rejectedItems() + " rejected item(s)" : result.problem());
    }
    public record Fetch(Optional<GamerPowerIntake.Batch> batch, int httpStatus, Optional<Duration> retryAfter, String problem) {
        public boolean complete() {
            return batch.isPresent() && (batch.get().state() == GamerPowerIntake.State.CANDIDATES
                    || batch.get().state() == GamerPowerIntake.State.EMPTY);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final OkHttpClient client;
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    private final GamerPowerIntake intake = new GamerPowerIntake();
    private final Clock clock;

    public GamerPowerFeed() { this(Clock.systemUTC()); }
    GamerPowerFeed(Clock clock) {
        this.clock = clock;
        client = FreebieHttp.client(GamerPowerFeed::publicOnly, TIMEOUT);
    }

    private static List<InetAddress> publicOnly(String host) throws UnknownHostException {
        if (!GamerPowerIntake.ENDPOINT.getHost().equals(host)) throw new UnknownHostException("Unapproved provider host.");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("Provider resolved only to non-public addresses.");
        return addresses;
    }

    static boolean isPublic(InetAddress address) {
        return !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc) // IPv6 unique-local
                || (address.getAddress().length == 4 && (address.getAddress()[0] & 0xff) == 100
                    && (address.getAddress()[1] & 0xc0) == 64)); // carrier-grade NAT
    }

    public Fetch fetch() {
        var request = new Request.Builder().url(GamerPowerIntake.ENDPOINT.toString())
                .header("Accept", "application/json").header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
        try {
            return calls.execute(client, request, response -> {
                int status = response.code();
                if (status == 429 || status == 503) return new Fetch(Optional.empty(), status,
                        retryAfter(response.header("Retry-After"), clock.instant()), "rate limited");
                if (status != 200 && status != 201) return new Fetch(Optional.empty(), status, Optional.empty(), "HTTP " + status);
                ResponseBody body = response.body();
                byte[] bytes = body.byteStream().readNBytes(GamerPowerIntake.MAX_BYTES + 1);
                var batch = intake.decode(status, bytes, clock.instant());
                return new Fetch(Optional.of(batch), status, Optional.empty(),
                        batch.state() == GamerPowerIntake.State.FAILED ? "invalid feed: " + batch.problem() : "");
            });
        } catch (IOException failure) {
            return new Fetch(Optional.empty(), 0, Optional.empty(), "network: " + failure.getClass().getSimpleName());
        }
    }

    static Optional<Duration> retryAfter(String header, Instant now) {
        if (header == null || header.length() > 128) return Optional.empty();
        String value = header.strip();
        try {
            Duration wait = value.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (wait.isNegative() || wait.isZero()) return Optional.empty();
            return Optional.of(wait.compareTo(Duration.ofHours(6)) > 0 ? Duration.ofHours(6) : wait);
        } catch (NumberFormatException | DateTimeParseException invalid) { return Optional.empty(); }
    }

    OkHttpClient client() { return client; }

    @Override public void close() { calls.close(); }
}
