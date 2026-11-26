package org.bunnys.bunnynexus.consoles;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.bunnys.bunnynexus.freebies.FreebieHttp;
import org.bunnys.bunnynexus.freebies.GamerPowerFeed;

import java.io.IOException;
import java.net.InetAddress;
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
    private final String userAgent;

    ConsoleFeeds(String userAgent) { this.userAgent = userAgent; }

    static List<InetAddress> lookup(String host) throws UnknownHostException {
        if (!HOSTS.contains(host)) throw new UnknownHostException("Unapproved console news host");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("No public console news address");
        return addresses;
    }

    @Override public List<PsPlusPost> psPlus() throws IOException { return PsPlusPost.parse(get(PS_FEED)); }

    @Override public List<FreeWeekend> freeWeekends() throws IOException { return FreeWeekend.parse(get(FreeWeekend.FEED)); }

    @Override public List<ConsoleItem> items() throws IOException { return ConsoleItem.parse(get(ConsoleItem.FEED)); }

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

    private byte[] get(String url) throws IOException {
        var request = new Request.Builder().url(url).header("User-Agent", userAgent).get().build();
        return calls.execute(client, request, response -> {
            if (response.code() != 200) throw new IOException("HTTP " + response.code() + " from " + response.request().url().host());
            byte[] body = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) throw new IOException("response too large");
            return body;
        });
    }

    @Override public void close() { calls.close(); }
}
