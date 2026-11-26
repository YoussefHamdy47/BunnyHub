package org.bunnys.bunnynexus.ratings;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.bson.Document;
import org.bunnys.bunnynexus.freebies.FreebieHttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

/**
 * Steam user ratings for games, from Steam's own public endpoints (no key): the store search to find a game by title,
 * and the store's item details for its developers, publishers and review summary.
 *
 * <p>A game from another store counts as the same Steam game only when the titles match exactly (ignoring case, ™ and
 * ®, punctuation and "&amp;" for "and") and, when the other store names a publisher, one of Steam's developers or
 * publishers is that company. Without a publisher exactly one Steam game may carry the title. Anything less certain
 * is "no match": an alert shows no rating rather than another game's. Blocking: call from a background thread.
 */
public final class SteamRatings implements AutoCloseable {
    static final String STORE = "store.steampowered.com", API = "api.steampowered.com";
    static final Set<String> HOSTS = Set.of(STORE, API);
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_CANDIDATES = 5;
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    public sealed interface Lookup permits Found, NoMatch, Failed {}
    public record Found(GameRating rating) implements Lookup {}
    public record NoMatch(String why) implements Lookup {}
    public record Failed(String why) implements Lookup {}

    private final OkHttpClient client = FreebieHttp.client(SteamRatings::lookup, TIMEOUT);
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    private final String userAgent;

    public SteamRatings(String userAgent) { this.userAgent = userAgent; }

    static List<InetAddress> lookup(String host) throws UnknownHostException {
        if (!HOSTS.contains(host)) throw new UnknownHostException("Unapproved ratings host");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(org.bunnys.bunnynexus.freebies.GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("No public ratings address");
        return addresses;
    }

    /** The rating of a known Steam app. */
    public Lookup forApp(long appId) {
        try {
            return pickApp(get(itemsUrl(List.of(appId))), appId);
        } catch (IOException failure) { return new Failed("network: " + failure.getClass().getSimpleName()); }
    }

    /** The rating of the Steam game with this title (and this publisher, when known). */
    public Lookup forTitle(String title, Optional<String> publisher) {
        try {
            var ids = exactMatches(get(searchUrl(title)), title);
            if (ids.isEmpty()) return new NoMatch("no Steam game with that exact title");
            return pick(get(itemsUrl(ids)), publisher);
        } catch (IOException failure) { return new Failed("network: " + failure.getClass().getSimpleName()); }
    }

    static String searchUrl(String title) {
        return "https://" + STORE + "/api/storesearch/?term=" + URLEncoder.encode(title, StandardCharsets.UTF_8) + "&l=english&cc=US";
    }

    static String itemsUrl(List<Long> appIds) {
        var ids = new StringJoiner(",");
        for (long id : appIds) ids.add("{\"appid\":" + id + "}");
        String input = "{\"ids\":[" + ids + "],\"context\":{\"language\":\"english\",\"country_code\":\"US\"},"
                + "\"data_request\":{\"include_reviews\":true,\"include_basic_info\":true}}";
        return "https://" + API + "/IStoreBrowseService/GetItems/v1/?input_json=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    private byte[] get(String url) throws IOException {
        var request = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", userAgent).get().build();
        return calls.execute(client, request, response -> {
            if (response.code() != 200) throw new IOException("HTTP " + response.code());
            byte[] body = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) throw new IOException("response too large");
            return body;
        });
    }

    // ------------------------------------------------------------------ pure parsing

    /** Lower case, accents, ™ ® ©, punctuation and "&amp;" (as "and") ignored: "Bread & Fred" equals "Bread and Fred". */
    static String normalize(String title) {
        if (title == null) return "";
        // ™ and ® go before normalizing, which would spell ™ out as "TM".
        String plain = Normalizer.normalize(title.replaceAll("[™®©]", ""), Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("&", " and ");
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Company names compared loosely: "AM Holyday Studios Ltd" is "Holyday Studios". */
    static boolean sameCompany(String a, String b) {
        String x = company(a), y = company(b);
        if (x.length() < 3 || y.length() < 3) return false;
        return x.equals(y) || (" " + x + " ").contains(" " + y + " ") || (" " + y + " ").contains(" " + x + " ");
    }

    private static String company(String name) {
        return normalize(name).replaceAll("\\b(ltd|limited|inc|llc|gmbh|corp|corporation|co|sa|ab|oy|srl|bv|plc|pty)\\b", " ")
                .replaceAll("\\s+", " ").strip();
    }

    /** App IDs in a store search whose title is exactly {@code title}, at most {@link #MAX_CANDIDATES}. */
    static List<Long> exactMatches(byte[] body, String title) {
        String wanted = normalize(title);
        List<Long> ids = new ArrayList<>();
        if (wanted.isEmpty()) return ids;
        try {
            var items = Document.parse(new String(body, StandardCharsets.UTF_8)).getList("items", Document.class, List.of());
            for (Document item : items) {
                if (!"app".equals(item.getString("type")) || !wanted.equals(normalize(item.getString("name")))) continue;
                Object id = item.get("id");
                if (id instanceof Number n && n.longValue() > 0) ids.add(n.longValue());
                if (ids.size() == MAX_CANDIDATES) break;
            }
        } catch (RuntimeException unreadable) { return List.of(); }
        return ids;
    }

    record Item(long appId, boolean game, List<String> companies, Optional<GameRating> rating) {}

    static List<Item> items(byte[] body) {
        List<Item> found = new ArrayList<>();
        try {
            var response = Document.parse(new String(body, StandardCharsets.UTF_8)).get("response", Document.class);
            for (Document item : response.getList("store_items", Document.class, List.of())) {
                if (!(item.get("appid") instanceof Number id)) continue;
                List<String> companies = new ArrayList<>();
                var basic = item.get("basic_info", Document.class);
                if (basic != null) for (String field : List.of("developers", "publishers"))
                    for (Document c : basic.getList(field, Document.class, List.of())) if (c.getString("name") != null) companies.add(c.getString("name"));
                Optional<GameRating> rating = Optional.empty();
                var reviews = item.get("reviews", Document.class);
                var summary = reviews == null ? null : reviews.get("summary_filtered", Document.class);
                if (summary != null && summary.get("review_count") instanceof Number count && summary.get("percent_positive") instanceof Number percent)
                    rating = GameRating.read(new Document("source", "steam").append("appId", id.longValue()).append("percent", percent.intValue())
                            .append("reviews", count.longValue()).append("label", summary.getString("review_score_label")));
                found.add(new Item(id.longValue(), Integer.valueOf(0).equals(item.get("type")), companies, rating));
            }
        } catch (RuntimeException unreadable) { return List.of(); }
        return found;
    }

    static Lookup pick(byte[] body, Optional<String> publisher) {
        var games = items(body).stream().filter(Item::game).toList();
        if (publisher.isPresent()) games = games.stream().filter(i -> i.companies().stream().anyMatch(c -> sameCompany(c, publisher.get()))).toList();
        if (games.isEmpty()) return new NoMatch(publisher.isPresent() ? "no Steam game by that publisher" : "no Steam game");
        if (games.size() > 1) return new NoMatch("several Steam games share that title");
        return games.getFirst().rating().<Lookup>map(Found::new).orElse(new NoMatch("too few Steam reviews"));
    }

    static Lookup pickApp(byte[] body, long appId) {
        return items(body).stream().filter(i -> i.appId() == appId).findFirst()
                .flatMap(Item::rating).<Lookup>map(Found::new).orElse(new NoMatch("too few Steam reviews"));
    }

    @Override public void close() { calls.close(); }
}
