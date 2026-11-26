package org.bunnys.bunnynexus.consoles;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.bunnys.bunnynexus.freebies.ConditionalFetch;
import org.bunnys.bunnynexus.freebies.FreebieHttp;
import org.bunnys.bunnynexus.freebies.GamerPowerFeed;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.*;

/** The hosts the console news reads, and nothing else. Blocking: call from the console news thread. */
final class ConsoleFeeds implements ConsoleNews.Source, AutoCloseable {
    static final String PS_FEED = "https://" + PsPlusPost.HOST + "/feed/";
    static final Set<String> HOSTS = Set.of(PsPlusPost.HOST, GamePass.SIGLS, GamePass.CATALOG, FreeWeekend.HOST, ConsoleItem.HOST);
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final OkHttpClient client = FreebieHttp.client(ConsoleFeeds::lookup, TIMEOUT);
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    /** Feeds that have not changed answer 304 with no body (the PlayStation Blog feed is 128 KB uncompressed). */
    private final ConditionalFetch fetch;

    ConsoleFeeds(String userAgent) { fetch = new ConditionalFetch(client, calls, userAgent, MAX_BYTES); }

    static List<InetAddress> lookup(String host) throws UnknownHostException {
        if (!HOSTS.contains(host)) throw new UnknownHostException("Unapproved console news host");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("No public console news address");
        return addresses;
    }

    @Override public Optional<List<PsPlusPost>> psPlus() throws IOException {
        return fetch.ifChanged(URI.create(PS_FEED)).map(PsPlusPost::parse);
    }

    @Override public Optional<List<FreeWeekend>> freeWeekends() throws IOException {
        return fetch.ifChanged(URI.create(FreeWeekend.FEED)).map(FreeWeekend::parse);
    }

    /** Always read (5 KB): an item waiting for its daily post is posted from this read. */
    @Override public Optional<List<ConsoleItem>> items() throws IOException {
        return Optional.of(ConsoleItem.parse(fetch.get(URI.create(ConsoleItem.FEED))));
    }

    @Override public void committed() { fetch.commit(); }

    @Override public void rolledBack() { fetch.rollback(); }

    @Override public List<String> shelf(GamePass.Shelf shelf) throws IOException {
        var ids = GamePass.ids(get(GamePass.siglUrl(shelf)));
        if (ids.isEmpty()) throw new IOException("empty Game Pass list " + shelf.id);
        return ids;
    }

    @Override public List<GamePass.Game> games(List<String> ids) throws IOException {
        List<GamePass.Game> games = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += GamePass.BATCH)
            games.addAll(GamePass.games(get(GamePass.catalogUrl(ids.subList(i, Math.min(ids.size(), i + GamePass.BATCH))))));
        return games;
    }

    private byte[] get(String url) throws IOException { return fetch.get(URI.create(url)); }

    @Override public void close() { calls.close(); }
}
