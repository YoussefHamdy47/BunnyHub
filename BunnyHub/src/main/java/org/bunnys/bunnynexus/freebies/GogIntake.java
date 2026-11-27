package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict parsers for GOG's store, read from its storefront code on 2026-11-27. GOG's free game sits on its home page
 * as a {@code GIVEAWAY_SECTION}: the home page's section list (about 1 KB) names it, and the section itself carries
 * the game ({@code properties.product.title/slug/coverHorizontal}) and the end ({@code properties.endDate}). GOG's
 * catalog then adds the usual price and art. Claiming needs the home page banner, so that is the claim link.
 */
final class GogIntake {
    private GogIntake() {}

    static final String SECTIONS_HOST = "sections.gog.com", CATALOG_HOST = "catalog.gog.com";
    static final String MARKET = "countryCode=US&locale=en-US&currencyCode=USD";
    static final URI HOME_SECTIONS = URI.create("https://" + SECTIONS_HOST + "/v1/pages/2f?" + MARKET);
    static final URI HOME = URI.create("https://www.gog.com/en/");
    static final String GIVEAWAY_SECTION = "GIVEAWAY_SECTION";
    static final int MAX_BYTES = 1024 * 1024, MAX_SECTIONS = 3;
    private static final Pattern SECTION_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9_]{1,120}");
    private static final Pattern IMAGE = Pattern.compile("https://images(?:-[0-9])?\\.gog(?:-statics)?\\.com/[0-9a-f]{64}(?:_[a-z0-9_]{1,40})?\\.(?:png|jpg|webp)");

    static URI section(String id) { return URI.create("https://" + SECTIONS_HOST + "/v1/pages/2f/sections/" + id + "?" + MARKET); }
    static URI catalog(String title) {
        return URI.create("https://" + CATALOG_HOST + "/v1/catalog?limit=10&query=" + URLEncoder.encode("like:" + title, StandardCharsets.UTF_8)
                + "&productType=in:game,pack&" + MARKET);
    }
    static URI page(String slug) { return URI.create("https://www.gog.com/en/game/" + slug); }

    /** The giveaway sections on GOG's home page; an empty list when there is none. Empty: not the captured shape. */
    static Optional<List<String>> giveaways(byte[] bytes) {
        try {
            List<Document> sections = FanaticalIntake.parse(bytes).getList("sections", Document.class);
            if (sections == null) return Optional.empty();
            List<String> ids = new ArrayList<>();
            for (Document s : sections)
                if (GIVEAWAY_SECTION.equals(s.getString("sectionType"))) {
                    String id = s.getString("sectionId");
                    if (id == null || !SECTION_ID.matcher(id).matches()) return Optional.empty();
                    if (ids.size() < MAX_SECTIONS) ids.add(id);
                }
            return Optional.of(ids);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    record Giveaway(String title, String slug, Optional<URI> cover, Instant ends) {}

    /** The game of a giveaway section; empty once it has ended. Throws {@link IllegalArgumentException} for another shape. */
    static Optional<Giveaway> giveaway(byte[] bytes, Instant now) {
        Document root = FanaticalIntake.parse(bytes);
        Document properties = root.get("properties", Document.class);
        if (properties == null && root.get("section", Document.class) != null)
            properties = root.get("section", Document.class).get("properties", Document.class);
        if (properties == null) throw new IllegalArgumentException("GOG giveaway section without properties");
        Document product = properties.get("product", Document.class);
        if (product == null) throw new IllegalArgumentException("GOG giveaway without a product");
        String title = product.getString("title"), slug = product.getString("slug"), end = properties.getString("endDate");
        if (title == null || title.isBlank() || title.length() > 256 || slug == null || !SLUG.matcher(slug).matches())
            throw new IllegalArgumentException("GOG giveaway product");
        Instant ends = instant(end).orElseThrow(() -> new IllegalArgumentException("GOG giveaway end"));
        if (!now.isBefore(ends)) return Optional.empty();
        if (ends.isAfter(now.plusSeconds(30L * 24 * 3600))) throw new IllegalArgumentException("GOG giveaway end too far");
        return Optional.of(new Giveaway(title.strip(), slug, image(product.getString("coverHorizontal")), ends));
    }

    record Listing(long priceCents, Optional<URI> cover, Optional<String> publisher) {}

    /** The catalog row with exactly this slug, when the search found it. */
    static Optional<Listing> listing(byte[] bytes, String slug) {
        try {
            for (Document p : FanaticalIntake.parse(bytes).getList("products", Document.class, List.of())) {
                if (!slug.equals(p.getString("slug"))) continue;
                Document price = p.get("price", Document.class);
                Document base = price == null ? null : price.get("baseMoney", Document.class);
                String amount = base == null ? null : base.getString("amount");
                long cents = amount != null && amount.matches("[0-9]{1,6}\\.[0-9]{2}") && "USD".equals(base.getString("currency"))
                        ? Long.parseLong(amount.replace(".", "")) : 0;
                Optional<String> publisher = p.getList("publishers", String.class, List.of()).stream()
                        .filter(n -> n != null && !n.isBlank() && n.length() <= 120).findFirst();
                return Optional.of(new Listing(cents, image(p.getString("coverHorizontal")), publisher));
            }
        } catch (RuntimeException invalid) { /* No catalog details: the giveaway still posts without them. */ }
        return Optional.empty();
    }

    static FreebieFeed.Candidate candidate(Giveaway g, Optional<Listing> listing) {
        var worth = listing.map(Listing::priceCents).filter(c -> c > 0).map(EpicIntake::usd);
        var art = listing.flatMap(Listing::cover).or(g::cover);
        return new FreebieFeed.Candidate(FreebieFeed.Provider.GOG, g.slug() + ":" + g.ends().getEpochSecond(), FreebieStore.GOG, "PC, GOG",
                g.title(), "", "Open GOG's home page while signed in, find the giveaway banner and press \"Get it free\". The game is yours to keep.",
                worth, art, HOME, page(g.slug()), Optional.empty(), Optional.of(g.ends()), "Not specified by source",
                Optional.of(page(g.slug())), listing.flatMap(Listing::publisher));
    }

    /** The storefront reads the end with the browser's lenient date parser: an instant, an offset time, or a UTC time. */
    static Optional<Instant> instant(String value) {
        if (value == null || value.length() > 40) return Optional.empty();
        try { return Optional.of(java.time.OffsetDateTime.parse(value).toInstant()); } catch (DateTimeParseException notOffset) { /* next */ }
        try { return Optional.of(java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC)); }
        catch (DateTimeParseException invalid) { return Optional.empty(); }
    }

    private static Optional<URI> image(String value) {
        return value != null && IMAGE.matcher(value).matches() ? Optional.of(URI.create(value)) : Optional.empty();
    }
}
