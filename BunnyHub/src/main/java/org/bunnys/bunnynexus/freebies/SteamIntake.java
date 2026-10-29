package org.bunnys.bunnynexus.freebies;

import com.fasterxml.jackson.core.*;
import org.bson.Document;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict parsers for Steam's US store, captured on 2026-10-29. Two steps: the store search for items that are free
 * because of a discount ({@code maxprice=free&specials=1}) lists app IDs, then {@code IStoreBrowseService/GetItems}
 * confirms each one. Only a game whose purchase option Steam itself marks {@code is_free_to_keep}, at $0.00 from a
 * real price with a 100% discount and a future {@code free_to_keep_ends}, becomes a candidate. Free weekends, demos,
 * DLC and free-to-play games never do.
 */
final class SteamIntake {
    static final String SEARCH_HOST = "store.steampowered.com", API_HOST = "api.steampowered.com";
    static final URI SEARCH = URI.create("https://store.steampowered.com/search/results/?maxprice=free&specials=1&infinite=1&count=100&cc=us&l=english");
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ITEMS = 100;
    /** Steam's store item type for a game (4 is DLC). */
    static final int GAME = 0;
    private static final Pattern ROW = Pattern.compile("<a\\s[^>]*?data-ds-(appid|packageid|bundleid)=\"([0-9,]{1,200})\"");
    private static final JsonFactory JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_BYTES)
                    .maxNameLength(128).maxNumberLength(20).maxDocumentLength(MAX_BYTES).maxTokenCount(200_000).build()).build();

    /** App IDs from a search page; {@code complete} is false when Steam reported more rows than the page held. */
    record Search(List<Long> appIds, boolean complete) {
        Search { appIds = List.copyOf(appIds); }
    }

    /** Empty when the envelope is not the captured shape. Bundles and multi-app packages are left to their own pages. */
    Optional<Search> search(byte[] bytes) {
        try {
            Document root = parse(bytes);
            if (number(root, "success") != 1) return Optional.empty();
            long total = number(root, "total_count");
            String html = root.getString("results_html");
            if (html == null || total < 0) return Optional.empty();
            var ids = new LinkedHashSet<Long>();
            int rows = 0;
            var matcher = ROW.matcher(html);
            while (matcher.find()) {
                rows++;
                // A package or bundle row may hold several apps; its own app rows are listed separately when free.
                if (!matcher.group(1).equals("appid") || matcher.group(2).contains(",")) continue;
                ids.add(Long.parseLong(matcher.group(2)));
            }
            if (rows > MAX_ITEMS || ids.size() > MAX_ITEMS) return Optional.empty();
            return Optional.of(new Search(new ArrayList<>(ids), rows >= total));
        } catch (RuntimeException | java.io.IOException invalid) { return Optional.empty(); }
    }

    /** The GetItems request for these apps, US store, English. */
    static URI items(List<Long> appIds) {
        var ids = new StringJoiner(",");
        for (long id : appIds) ids.add("{\"appid\":" + id + "}");
        String input = "{\"ids\":[" + ids + "],\"context\":{\"language\":\"english\",\"country_code\":\"US\"},"
                + "\"data_request\":{\"include_basic_info\":true,\"include_assets\":true,\"include_all_purchase_options\":true}}";
        return URI.create("https://" + API_HOST + "/IStoreBrowseService/GetItems/v1/?input_json="
                + java.net.URLEncoder.encode(input, StandardCharsets.UTF_8));
    }

    /**
     * Candidates from a GetItems response for {@code requested}. Items that are not free-to-keep games are skipped; an
     * item Steam did not return, or returned in an unexpected shape, makes the result partial.
     */
    FreebieFeed.Result decode(byte[] bytes, List<Long> requested, boolean searchComplete, Instant now) {
        try {
            Document response = Objects.requireNonNull(parse(bytes).get("response", Document.class));
            List<Document> items = response.containsKey("store_items") ? list(response, "store_items") : List.of();
            if (items.size() > MAX_ITEMS) return failed("too many Steam items");
            Set<Long> wanted = new HashSet<>(requested), seen = new HashSet<>();
            List<FreebieFeed.Candidate> candidates = new ArrayList<>();
            int rejected = 0;
            for (Document item : items) {
                try {
                    long appId = number(item, "appid");
                    if (!wanted.contains(appId) || !seen.add(appId)) return failed("unexpected Steam item");
                    candidate(item, appId, now).ifPresent(candidates::add);
                } catch (RuntimeException invalid) { rejected++; }
            }
            int missing = wanted.size() - seen.size();
            boolean complete = searchComplete && rejected == 0 && missing == 0;
            String problem = complete ? "" : !searchComplete ? "partial feed: more Steam results than one page"
                    : "partial feed: " + (rejected + missing) + " Steam item(s) unreadable";
            return new FreebieFeed.Result(complete ? FreebieFeed.State.COMPLETE : FreebieFeed.State.PARTIAL, candidates, Optional.empty(), problem);
        } catch (RuntimeException | java.io.IOException invalid) { return failed("invalid Steam store response"); }
    }

    private static Optional<FreebieFeed.Candidate> candidate(Document item, long appId, Instant now) {
        if (number(item, "success") != 1) throw new IllegalArgumentException();
        if (number(item, "type") != GAME || !Boolean.TRUE.equals(item.get("visible"))) return Optional.empty();
        Document option = null;
        List<Document> options = item.containsKey("purchase_options") ? list(item, "purchase_options") : List.of();
        for (Document o : options) {
            if (!Boolean.TRUE.equals(o.get("is_free_to_keep"))) continue;
            if (option != null) throw new IllegalArgumentException(); // Two free packages: ambiguous, never guessed.
            option = o;
        }
        if (option == null) return Optional.empty();
        long packageId = number(option, "packageid");
        long ends = number(option, "free_to_keep_ends");
        long original = cents(option, "original_price_in_cents");
        if (cents(option, "final_price_in_cents") != 0 || original <= 0 || number(option, "discount_pct") != 100)
            throw new IllegalArgumentException();
        Instant end = Instant.ofEpochSecond(ends);
        if (!end.isAfter(now) || end.isAfter(now.plusSeconds(90L * 24 * 3600))) throw new IllegalArgumentException();
        String name = text(item, "name", 256);
        Document basic = item.get("basic_info", Document.class);
        String raw = basic == null ? null : basic.getString("short_description");
        String description = raw == null ? "" : raw.strip().length() > 8000 ? raw.strip().substring(0, 8000) : raw.strip();
        URI page = URI.create("https://" + SEARCH_HOST + "/app/" + appId + "/");
        return Optional.of(new FreebieFeed.Candidate(FreebieFeed.Provider.STEAM, appId + ":" + packageId, FreebieStore.STEAM,
                "PC, Steam", name, description, "Add it to your Steam library before the end date to keep it.",
                Optional.of(EpicIntake.usd(original)), header(item, appId), page, page, Optional.empty(), Optional.of(end),
                "US store; availability elsewhere may differ"));
    }

    /** The store header artwork on Steam's CDN, built from the app ID; anything unexpected is dropped. */
    static Optional<URI> header(Document item, long appId) {
        Document assets = item.get("assets", Document.class);
        String file = assets == null ? null : assets.getString("header");
        if (file == null || !file.matches("[A-Za-z0-9_.-]{1,64}\\.(?:jpg|png)") || file.contains(".."))
            return Optional.empty();
        return Optional.of(URI.create("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/" + file));
    }

    private static Document parse(byte[] bytes) throws java.io.IOException {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
        try (var parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException();
            parser.skipChildren();
            if (parser.nextToken() != null) throw new IllegalArgumentException();
        }
        return Document.parse(new String(bytes, StandardCharsets.UTF_8));
    }
    private static List<Document> list(Document parent, String name) { return Objects.requireNonNull(parent.getList(name, Document.class)); }
    private static long number(Document parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
    /** Steam sends prices as decimal strings of cents. */
    private static long cents(Document parent, String name) {
        String value = parent.getString(name);
        if (value == null || !value.matches("[0-9]{1,9}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private static String text(Document parent, String name, int limit) {
        String value = parent.getString(name);
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException();
        return value;
    }
    private static FreebieFeed.Result failed(String problem) {
        return new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.empty(), problem);
    }
}
