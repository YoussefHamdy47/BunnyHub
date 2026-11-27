package org.bunnys.bunnynexus.freebies;

import okhttp3.Dns;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * The HTTP side of a store's own giveaway source: fixed public hosts only, no redirects or proxies, bounded bodies,
 * and "only if changed" reads ({@link ConditionalFetch}). OkHttp asks for gzip and unpacks it, so a listing costs its
 * compressed size, and an unchanged one a 304.
 */
final class StoreFetch {
    private StoreFetch() {}

    static final String USER_AGENT = "BunnyNexus-Freebies/2.0";

    /** {@code calls} is closed by the feed, which cancels its open requests. */
    static ConsoleTips.Http create(Set<String> hosts, int maxBytes, FreebieHttp.Calls calls) {
        var client = FreebieHttp.client(host -> {
            if (!hosts.contains(host)) throw new UnknownHostException("Unapproved provider host");
            var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
            if (addresses.isEmpty()) throw new UnknownHostException("No public provider address");
            return addresses;
        }, Duration.ofSeconds(20));
        var fetch = new ConditionalFetch(client, calls, USER_AGENT, maxBytes);
        return new ConsoleTips.Http() {
            @Override public Optional<byte[]> ifChanged(URI url) throws IOException { return fetch.ifChanged(url); }
            @Override public byte[] get(URI url) throws IOException { return fetch.get(url); }
            @Override public void commit() { fetch.commit(); }
            @Override public void rollback() { fetch.rollback(); }
        };
    }
}
