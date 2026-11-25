package org.bunnys.bunnynexus.deals;

import org.bson.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the stores' own responses (docs/deals/01-source.md): a Steam search page (app IDs), Steam's item details
 * (price, discount, reviews, sale end) and GOG's catalog. Lenient per row (a row that does not read is skipped),
 * strict per body: a body that is too large, not the expected shape, or mostly unreadable fails that store's part of
 * the refresh, so a format change never empties the list silently. Pure: no I/O.
 */
final class DealIntake {
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ROWS = 100;
    /** A search row for one app; package and bundle rows (several apps) are skipped. */
    private static final Pattern STEAM_ROW = Pattern.compile("<a\\s[^>]*?data-ds-appid=\"([0-9]{1,10})\"");
    /** The discount Steam shows on a search row; the row ends at the next row. */
    private static final Pattern STEAM_DISCOUNT = Pattern.compile("data-discount=\"([0-9]{1,3})\"");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,6}([.][0-9]{1,6})?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");
    private static final Pattern GOG_LINK = Pattern.compile("https://www\\.gog\\.com/[a-z]{2}(-[a-z]{2})?/game/[a-z0-9_]{1,120}");
    /** Steam's item type for a game (4 is DLC, 1 a demo...). */
    private static final int STEAM_GAME = 0;

    private DealIntake() {}

    /** A body that must fail its store's part of the refresh. */
    static final class Invalid extends Exception {
        Invalid(String reason) { super(reason, null, false, false); }
    }

    /**
     * The app IDs on a Steam search page, in page order, without repeats, leaving out rows whose shown discount is
     * below {@code minPercentOff}, so their details are never requested. A row without a shown discount is kept (the
     * item details decide). The details are still what counts: this only saves requests.
     */
    static List<Long> steamSearch(byte[] body, int minPercentOff) throws Invalid {
        Document root = parse(body);
        if (!(root.get("success") instanceof Number success) || success.intValue() != 1 || !(root.get("results_html") instanceof String html))
            throw new Invalid("invalid Steam search response");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        var rows = STEAM_ROW.matcher(html);
        boolean found = rows.find();
        int read = 0;
        while (found && read++ < MAX_ROWS) {
            long id = Long.parseLong(rows.group(1));
            int start = rows.end();
            found = rows.find();
            var discount = STEAM_DISCOUNT.matcher(html).region(start, found ? rows.start() : html.length());
            if (!discount.find() || Integer.parseInt(discount.group(1)) >= minPercentOff) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** Games on sale from a Steam GetItems response. DLC, demos and items without a discount are left out. */
    static List<Deal> steamItems(byte[] body) throws Invalid {
        if (!(parse(body).get("response") instanceof Document response)) throw new Invalid("invalid Steam items response");
        List<Document> items = response.getList("store_items", Document.class, List.of());
        if (items.size() > MAX_ROWS) throw new Invalid("too many Steam items");
        List<Deal> deals = new ArrayList<>();
        int unreadable = 0;
        for (Document item : items) {
            try {
                long appId = number(item, "appid");
                String name = item.getString("name");
                if (appId <= 0 || name == null || name.isBlank()) { unreadable++; continue; }
                if (number(item, "type") != STEAM_GAME || Boolean.FALSE.equals(item.getBoolean("visible"))) continue;
                if (!(item.get("best_purchase_option") instanceof Document option) || option.get("original_price_in_cents") == null) continue;
                Document reviews = item.get("reviews") instanceof Document r && r.get("summary_filtered") instanceof Document f ? f : new Document();
                Optional<Instant> ends = option.getList("active_discounts", Document.class, List.of()).stream()
                        .map(d -> d.get("discount_end_date")).filter(Number.class::isInstance).map(n -> ((Number) n).longValue())
                        .filter(s -> s > 0).min(Long::compare).map(Instant::ofEpochSecond);
                deals.add(new Deal("steam:" + appId, DealStore.STEAM, name.strip(), URI.create("https://store.steampowered.com/app/" + appId + "/"),
                        number(option, "final_price_in_cents"), number(option, "original_price_in_cents"), (int) number(option, "discount_pct"),
                        (int) optionalNumber(reviews, "percent_positive"), (int) optionalNumber(reviews, "review_count"), ends));
            } catch (RuntimeException unusable) { unreadable++; }
        }
        if (unreadable * 2 > items.size()) throw new Invalid("unexpected Steam items shape");
        return deals;
    }

    /** Discounted games and packs from GOG's catalog, US prices in USD. */
    static List<Deal> gog(byte[] body) throws Invalid {
        Document root = parse(body);
        if (!(root.get("products") instanceof List<?> products)) throw new Invalid("invalid GOG catalog response");
        if (products.size() > MAX_ROWS) throw new Invalid("too many GOG products");
        List<Deal> deals = new ArrayList<>();
        int unreadable = 0;
        for (Object row : products) {
            try {
                if (!(row instanceof Document product)) { unreadable++; continue; }
                String id = product.getString("id"), title = product.getString("title"), link = product.getString("storeLink");
                if (id == null || !DIGITS.matcher(id).matches() || title == null || title.isBlank() || link == null || !GOG_LINK.matcher(link).matches()) {
                    unreadable++;
                    continue;
                }
                if (!Set.of("game", "pack").contains(product.getString("productType"))) continue;
                Document price = (Document) product.get("price");
                Document fin = (Document) price.get("finalMoney"), base = (Document) price.get("baseMoney");
                if (!"USD".equals(fin.getString("currency")) || !"USD".equals(base.getString("currency"))) { unreadable++; continue; }
                String sale = fin.getString("amount"), normal = base.getString("amount");
                if (!DECIMAL.matcher(sale).matches() || !DECIMAL.matcher(normal).matches()) { unreadable++; continue; }
                long saleCents = cents(sale), normalCents = cents(normal);
                int off = normalCents <= 0 ? 0 : (int) Math.round(100.0 * (normalCents - saleCents) / normalCents);
                deals.add(new Deal("gog:" + id, DealStore.GOG, title.strip(), URI.create(link), saleCents, normalCents, off,
                        (int) optionalNumber(product, "reviewsRating"), (int) optionalNumber(product, "reviewsCount"), Optional.empty()));
            } catch (RuntimeException unusable) { unreadable++; }
        }
        if (unreadable * 2 > products.size()) throw new Invalid("unexpected GOG catalog shape");
        return deals;
    }

    /** "1.99" → 199. Never through a double. */
    static long cents(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** A number Steam may send as a JSON number or as a string of digits. */
    private static long number(Document doc, String name) {
        Object value = doc.get(name);
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && DIGITS.matcher(s).matches()) return Long.parseLong(s);
        throw new IllegalArgumentException("missing " + name);
    }

    private static long optionalNumber(Document doc, String name) {
        return doc.get(name) == null ? 0 : number(doc, name);
    }

    private static Document parse(byte[] body) throws Invalid {
        if (body == null || body.length == 0) throw new Invalid("empty response");
        if (body.length > MAX_BYTES) throw new Invalid("response too large");
        String json = new String(body, StandardCharsets.UTF_8).strip();
        if (!json.startsWith("{")) throw new Invalid("invalid response");
        try {
            return Document.parse(json);
        } catch (RuntimeException unreadable) {
            throw new Invalid("invalid response");
        }
    }
}
