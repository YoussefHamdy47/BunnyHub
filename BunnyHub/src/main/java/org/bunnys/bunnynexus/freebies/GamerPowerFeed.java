package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the GamerPower game feed. Uses normal DNS (the previous fixed IP pins broke whenever the
 * provider's CDN moved) but still refuses private/loopback answers, redirects, proxies and large bodies.
 * Blocking: call from the discovery thread only.
 */
public final class GamerPowerFeed implements AutoCloseable {
    public record Fetch(Optional<GamerPowerIntake.Batch> batch, int httpStatus, Optional<Duration> retryAfter, String problem) {
        public boolean complete() {
            return batch.isPresent() && (batch.get().state() == GamerPowerIntake.State.CANDIDATES
                    || batch.get().state() == GamerPowerIntake.State.EMPTY);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final OkHttpClient client;
    private final GamerPowerIntake intake = new GamerPowerIntake();
    private final Clock clock;

    public GamerPowerFeed() { this(Clock.systemUTC()); }
    GamerPowerFeed(Clock clock) {
        this.clock = clock;
        client = new OkHttpClient.Builder().dns(GamerPowerFeed::publicOnly).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(TIMEOUT).connectTimeout(TIMEOUT).readTimeout(TIMEOUT).writeTimeout(TIMEOUT).build();
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
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status == 429 || status == 503) return new Fetch(Optional.empty(), status, retryAfter(response), "rate limited");
            if (status != 200 && status != 201) return new Fetch(Optional.empty(), status, Optional.empty(), "HTTP " + status);
            ResponseBody body = response.body();
            byte[] bytes = body.byteStream().readNBytes(GamerPowerIntake.MAX_BYTES + 1);
            var batch = intake.decode(status, bytes, clock.instant());
            return new Fetch(Optional.of(batch), status, Optional.empty(),
                    batch.state() == GamerPowerIntake.State.FAILED ? "invalid feed: " + batch.problem() : "");
        } catch (IOException failure) {
            return new Fetch(Optional.empty(), 0, Optional.empty(), "network: " + failure.getClass().getSimpleName());
        }
    }

    private static Optional<Duration> retryAfter(Response response) {
        try {
            String header = response.header("Retry-After");
            if (header == null) return Optional.empty();
            long seconds = Long.parseLong(header.strip());
            return seconds > 0 ? Optional.of(Duration.ofSeconds(Math.min(seconds, 6 * 3600))) : Optional.empty();
        } catch (NumberFormatException ignored) { return Optional.empty(); }
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
