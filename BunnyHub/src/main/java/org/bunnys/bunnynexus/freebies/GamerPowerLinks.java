package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Optional (FREEBIE_RESOLVE_GAMERPOWER_LINKS): follows a GamerPower claim link's redirects, without loading any page,
 * to the official Epic product page it points at. Only HTTPS redirects within www.gamerpower.com and the Epic store
 * hosts are followed, at most three. Any failure leaves the report without a storefront identity, so it can only
 * reach an owner question, never an automatic join. Disabled by default: on 2026-10-24 the redirect endpoint answered
 * HTTP 520 to unauthenticated requests, so its current contract is unverified.
 */
final class GamerPowerLinks implements UnaryOperator<FreebieFeed.Candidate>, AutoCloseable {
    private static final int MAX_HOPS = 3;
    private final OkHttpClient client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .followSslRedirects(false).dns(host -> {
                if (!allowedHost(host)) throw new UnknownHostException("Unapproved redirect host");
                var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
                if (addresses.isEmpty()) throw new UnknownHostException("No public address");
                return addresses;
            }).callTimeout(Duration.ofSeconds(10)).connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(10)).build();

    static boolean allowedHost(String host) {
        return host.equals("www.gamerpower.com") || host.equals("store.epicgames.com") || host.equals("www.epicgames.com");
    }

    /** Only Epic-tagged GamerPower reports are resolved; others are returned unchanged. */
    @Override public FreebieFeed.Candidate apply(FreebieFeed.Candidate c) {
        if (c.provider() != FreebieFeed.Provider.GAMERPOWER || c.store() != FreebieStore.EPIC || c.storePage().isPresent()) return c;
        return resolve(c.claimPage()).map(c::withStorePage).orElse(c);
    }

    Optional<URI> resolve(URI start) {
        URI current = start;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            var request = new Request.Builder().url(current.toString()).header("User-Agent", "BunnyNexus-Freebies/2.0").head().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isRedirect()) return Optional.empty();
                var next = next(current, response.header("Location"));
                if (next.isEmpty()) return Optional.empty();
                if (EpicLinks.productSlug(next.get()).isPresent()) return next;
                current = next.get();
            } catch (IOException | RuntimeException failure) { return Optional.empty(); }
        }
        return Optional.empty();
    }

    /** The validated next hop of a redirect: HTTPS, an allowlisted host, no credentials or explicit port. */
    static Optional<URI> next(URI from, String location) {
        if (location == null || location.length() > 512) return Optional.empty();
        try {
            URI next = from.resolve(location);
            if (!"https".equals(next.getScheme()) || next.getHost() == null || !allowedHost(next.getHost())
                    || next.getRawUserInfo() != null || next.getPort() != -1) return Optional.empty();
            return Optional.of(next);
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    @Override public void close() {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
