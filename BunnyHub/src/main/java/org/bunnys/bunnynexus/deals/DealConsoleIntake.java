package org.bunnys.bunnynexus.deals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the console stores' own responses, captured on 2026-11-27, US store in USD:
 * <ul>
 *   <li>Xbox: the xbox.com store's browse API, console games on sale by popularity. Each game carries its price, list
 *       price, sale end and its players' star rating and rating count.</li>
 *   <li>PlayStation: the PlayStation Store's "All Deals" category, full games by sales. The list has no ratings; the
 *       game's store page gives its star rating, rating count and sale end ({@link #playstationRating}).</li>
 *   <li>Nintendo: the eShop's sales page, whose page data lists the featured deals with their sale end. The eShop has
 *       no player ratings: the game's Steam rating is added when the same game is on Steam.</li>
 * </ul>
 * Same rules as {@link DealIntake}: lenient per row, strict per body. Pure: no I/O.
 */
final class DealConsoleIntake {
    private DealConsoleIntake() {}

    static final int MAX_NINTENDO_BYTES = 6 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern XBOX_ID = Pattern.compile("[0-9A-Z]{12}");
    private static final Pattern PS_ID = DealFeed.PLAYSTATION_ID;
    private static final Pattern NINTENDO_URL_KEY = Pattern.compile("[a-z0-9-]{1,160}");
    private static final Pattern USD = Pattern.compile("\\$([0-9]{1,4}(?:,[0-9]{3})*\\.[0-9]{2})");
    private static final Pattern NEXT_DATA = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern PS_STARS = Pattern.compile("\"starRating\":\\{\"__typename\":\"StarRating\",\"averageRating\":([0-9.]{1,6}),\"totalRatingsCount\":([0-9]{1,12})\\}");
    private static final Pattern PS_END = Pattern.compile("\"endTime\":\"([0-9]{13})\"");
    private static final DateTimeFormatter XBOX_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

    // ---- Xbox -------------------------------------------------------------------------------------------------------

    /** One page of Xbox's browse API: the deals on it and the token for the next page, if any. */
    record XboxPage(List<Deal> deals, Optional<String> next) {}

    static XboxPage xbox(byte[] body) throws DealIntake.Invalid {
        JsonNode root = json(body, DealIntake.MAX_BYTES);
        JsonNode summaries = root.path("productSummaries"), channels = root.path("channels");
        if (!summaries.isArray() || !channels.isObject()) throw new DealIntake.Invalid("invalid Xbox browse response");
        List<Deal> deals = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode p : summaries) {
            try {
                String id = p.path("productId").asText(""), title = p.path("title").asText("").strip();
                if (!XBOX_ID.matcher(id).matches() || title.isEmpty()) { unreadable++; continue; }
                if (!"Game".equals(p.path("productKind").asText())) continue;
                // The cheapest sale a buyer can purchase; trials and subscriber-only prices are other availabilities.
                JsonNode best = null;
                for (JsonNode a : p.path("specificPrices").path("purchaseable")) {
                    boolean buyable = false;
                    for (JsonNode action : a.path("availabilityActions")) buyable |= "Purchase".equals(action.asText());
                    if (!buyable || !"USD".equals(a.path("currency").asText()) || a.path("listPrice").asDouble() >= a.path("msrp").asDouble()) continue;
                    if (best == null || a.path("listPrice").asDouble() < best.path("listPrice").asDouble()) best = a;
                }
                if (best == null) continue;
                long sale = cents(best.path("listPrice").asText()), normal = cents(best.path("msrp").asText());
                if (sale <= 0 || normal <= 0) continue;
                int off = (int) Math.round(100.0 * (normal - sale) / normal);
                int stars = (int) Math.round(p.path("averageRating").asDouble() * 10);
                deals.add(new Deal("xbox:" + id, DealStore.XBOX, title, URI.create("https://www.xbox.com/en-US/games/store/p/" + id),
                        sale, normal, off, stars, p.path("ratingCount").asInt(), xboxEnd(best.path("endDateUtc").asText())));
            } catch (RuntimeException unusable) { unreadable++; }
        }
        if (unreadable * 2 > summaries.size()) throw new DealIntake.Invalid("unexpected Xbox browse shape");
        String next = null;
        for (JsonNode channel : channels) {
            String token = channel.path("encodedCT").asText("");
            if (!token.isEmpty() && token.length() < 8000 && token.matches("[A-Za-z0-9+/=]+")) next = token;
        }
        return new XboxPage(deals, Optional.ofNullable(next));
    }

    /** "12/03/2026 09:59:59" in UTC; the far-future placeholder (year 9998) means no end. */
    static Optional<Instant> xboxEnd(String value) {
        try {
            var end = LocalDateTime.parse(value, XBOX_DATE).toInstant(ZoneOffset.UTC);
            return end.isAfter(Instant.parse("2100-01-01T00:00:00Z")) ? Optional.empty() : Optional.of(end);
        } catch (DateTimeParseException | NullPointerException none) { return Optional.empty(); }
    }

    // ---- PlayStation ------------------------------------------------------------------------------------------------

    /**
     * A full game in PlayStation's deals list, before its rating is known: {@link #withRating} completes it. Prices
     * tied to a PS Plus subscription are left out: everyone else pays more.
     */
    record PlayStationRow(String productId, String title, long saleCents, long normalCents, int percentOff) {
        Deal withRating(PlayStationRating rating) {
            return new Deal("playstation:" + productId, DealStore.PLAYSTATION, title,
                    URI.create("https://store.playstation.com/en-us/product/" + productId), saleCents, normalCents, percentOff,
                    rating.stars(), rating.count(), rating.ends());
        }
    }

    /** Stars in tenths, how many players rated, and the sale end, from a game's store page. */
    record PlayStationRating(int stars, int count, Optional<Instant> ends) {}

    static List<PlayStationRow> playstation(byte[] body) throws DealIntake.Invalid {
        JsonNode grid = json(body, DealIntake.MAX_BYTES).path("data").path("categoryGridRetrieve");
        JsonNode products = grid.path("products");
        if (!products.isArray()) throw new DealIntake.Invalid("invalid PlayStation deals response");
        List<PlayStationRow> rows = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode p : products) {
            try {
                String id = p.path("id").asText(""), title = p.path("name").asText("").strip();
                if (!PS_ID.matcher(id).matches() || title.isEmpty()) { unreadable++; continue; }
                if (!"FULL_GAME".equals(p.path("storeDisplayClassification").asText())) continue;
                JsonNode price = p.path("price");
                if (price.path("isTiedToSubscription").asBoolean(false) || price.path("isFree").asBoolean(false)) continue;
                var sale = usd(price.path("discountedPrice").asText("")); var normal = usd(price.path("basePrice").asText(""));
                if (sale.isEmpty() || normal.isEmpty() || sale.get() <= 0 || sale.get() >= normal.get()) continue;
                rows.add(new PlayStationRow(id, title, sale.get(), normal.get(),
                        (int) Math.round(100.0 * (normal.get() - sale.get()) / normal.get())));
            } catch (RuntimeException unusable) { unreadable++; }
        }
        if (unreadable * 2 > products.size()) throw new DealIntake.Invalid("unexpected PlayStation deals shape");
        return rows;
    }

    /** The star rating on a PlayStation game page (the first one is the game's own); empty when the page has none. */
    static Optional<PlayStationRating> playstationRating(byte[] page) {
        String html = new String(page, StandardCharsets.UTF_8);
        var stars = PS_STARS.matcher(html);
        if (!stars.find()) return Optional.empty();
        double average = Double.parseDouble(stars.group(1));
        long count = Long.parseLong(stars.group(2));
        if (average < 0 || average > 5 || count > Integer.MAX_VALUE) return Optional.empty();
        var end = PS_END.matcher(html);
        Optional<Instant> ends = end.find() ? Optional.of(Instant.ofEpochMilli(Long.parseLong(end.group(1)))) : Optional.empty();
        return Optional.of(new PlayStationRating((int) Math.round(average * 10), (int) count, ends));
    }

    // ---- Nintendo ---------------------------------------------------------------------------------------------------

    /** A Nintendo deal before its Steam rating: {@link #withRating} completes it. */
    record NintendoRow(String nsuid, String title, URI link, long saleCents, long normalCents, int percentOff,
                       Optional<Instant> ends, Optional<String> publisher) {
        Deal withRating(int percent, int reviews) {
            return new Deal("nintendo:" + nsuid, DealStore.NINTENDO, title, link, saleCents, normalCents, percentOff, percent, reviews, ends);
        }
    }

    /** Discounted games (and game bundles) on the eShop sales page; DLC and add-ons are left out. */
    static List<NintendoRow> nintendo(byte[] page) throws DealIntake.Invalid {
        if (page == null || page.length == 0 || page.length > MAX_NINTENDO_BYTES) throw new DealIntake.Invalid("invalid Nintendo page");
        var data = NEXT_DATA.matcher(new String(page, StandardCharsets.UTF_8));
        if (!data.find()) throw new DealIntake.Invalid("Nintendo page without its data");
        JsonNode root;
        try { root = JSON.readTree(data.group(1)); }
        catch (Exception unreadable) { throw new DealIntake.Invalid("invalid Nintendo page data"); }
        Map<String, NintendoRow> rows = new LinkedHashMap<>();
        int products = 0, unreadable = 0;
        Deque<JsonNode> stack = new ArrayDeque<>(List.of(root));
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node.isObject() && node.has("nsuid") && node.has("prices") && node.has("name")) {
                products++;
                try { nintendoRow(node).ifPresent(row -> rows.putIfAbsent(row.nsuid(), row)); }
                catch (RuntimeException unusable) { unreadable++; }
                continue;
            }
            if (node.isContainerNode()) node.forEach(stack::push);
        }
        if (products == 0 || unreadable * 2 > products) throw new DealIntake.Invalid("unexpected Nintendo page shape");
        return List.copyOf(rows.values());
    }

    private static Optional<NintendoRow> nintendoRow(JsonNode p) {
        String nsuid = p.path("nsuid").asText(""), title = p.path("name").asText("").strip(), key = p.path("urlKey").asText("");
        if (!nsuid.matches("[0-9]{14}") || title.isEmpty() || !NINTENDO_URL_KEY.matcher(key).matches()) throw new IllegalArgumentException();
        if (!"GAMES".equals(p.path("topLevelCategory").path("code").asText())) return Optional.empty();
        String dlc = p.path("dlcType").path("code").asText("");
        if (!dlc.isEmpty() && !dlc.equals("ROM_BUNDLE")) return Optional.empty();
        JsonNode prices = p.path("prices");
        if (!prices.path("discounted").asBoolean(false) || !"USD".equals(prices.path("currency").asText())) return Optional.empty();
        long sale = cents(prices.path("finalPrice").asText()), normal = cents(prices.path("regularPrice").asText());
        if (sale <= 0 || sale >= normal) return Optional.empty();
        Optional<Instant> ends;
        try { ends = Optional.of(Instant.parse(p.path("eshopDetails").path("discountPriceEnd").asText())); }
        catch (DateTimeParseException none) { ends = Optional.empty(); }
        String publisher = p.path("softwarePublisher").asText("").strip();
        return Optional.of(new NintendoRow(nsuid, title, URI.create("https://www.nintendo.com/us/store/products/" + key + "/"),
                sale, normal, (int) Math.round(100.0 * (normal - sale) / normal), ends,
                publisher.isEmpty() || publisher.length() > 120 ? Optional.empty() : Optional.of(publisher)));
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** "$1,299.99" → 129999. */
    static Optional<Long> usd(String text) {
        var m = USD.matcher(text.strip());
        return m.matches() ? Optional.of(cents(m.group(1).replace(",", ""))) : Optional.empty();
    }

    /** A decimal amount in dollars ("14.99", "14.990000") → cents; never through a double's rounding. */
    static long cents(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static JsonNode json(byte[] body, int max) throws DealIntake.Invalid {
        if (body == null || body.length == 0) throw new DealIntake.Invalid("empty response");
        if (body.length > max) throw new DealIntake.Invalid("response too large");
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw new DealIntake.Invalid("invalid response");
            return node;
        } catch (java.io.IOException unreadable) { throw new DealIntake.Invalid("invalid response"); }
    }
}
