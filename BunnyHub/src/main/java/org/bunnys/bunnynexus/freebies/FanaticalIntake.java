package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict parsers for Fanatical's public store API, captured on 2026-11-27 (the Vertical Drop Heroes HD giveaway).
 * Fanatical has no giveaway list, so two listings name the games to check: the promotions feed (a free bonus that
 * requires claiming a game names that game) and the site's banners (a "Giveaway" banner links its game). Each named
 * game's own page then decides: only a game Fanatical marks {@code giveaway}, at $0.00 now from a real price, inside
 * its claim window, becomes a candidate. Bonuses that need a purchase never do.
 */
final class FanaticalIntake {
    private FanaticalIntake() {}

    static final String HOST = "www.fanatical.com";
    static final URI PROMOTIONS = URI.create("https://" + HOST + "/api/all-promotions/en");
    static final URI SITE = URI.create("https://" + HOST + "/api/all/en");
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_GAMES = 10;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,119}");
    private static final Pattern GAME_LINK = Pattern.compile("https://www\\.fanatical\\.com/en/game/(" + SLUG.pattern() + ")/?");
    private static final Pattern COVER = Pattern.compile("[A-Za-z0-9-]{1,80}\\.(?:jpe?g|png|webp)");
    private static final Pattern GIVEAWAY = Pattern.compile("(?i)\\bgiveaway\\b");

    static URI product(String slug) { return URI.create("https://" + HOST + "/api/products-group/" + slug + "/en"); }
    static URI page(String slug) { return URI.create("https://" + HOST + "/en/game/" + slug); }

    /** Games named by free bonuses with no spend: the games they require, and free games they give. Empty: not the captured shape. */
    static Optional<Set<String>> promoted(byte[] bytes) {
        try {
            Document root = parse(bytes);
            List<Document> free = root.getList("freeProducts", Document.class);
            if (free == null) return Optional.empty();
            Set<String> slugs = new LinkedHashSet<>();
            for (Document offer : free) {
                if (!Boolean.TRUE.equals(offer.get("public")) || !noSpend(offer.get("min_spend", Document.class))) continue;
                for (Document p : offer.getList("required_products", Document.class, List.of()))
                    if ("game".equals(p.getString("type"))) slug(p.getString("slug")).ifPresent(slugs::add);
                for (Document p : offer.getList("products", Document.class, List.of()))
                    if ("game".equals(p.getString("type")) && cents(p.get("price", Document.class)).orElse(-1L) == 0)
                        slug(p.getString("slug")).ifPresent(slugs::add);
            }
            return Optional.of(slugs);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    /** Games linked by banners named "… Giveaway …". Empty: not the captured shape. */
    static Optional<Set<String>> bannered(byte[] bytes) {
        try {
            List<Document> images = parse(bytes).getList("images", Document.class);
            if (images == null) return Optional.empty();
            Set<String> slugs = new LinkedHashSet<>();
            for (Document banner : images) {
                String name = banner.getString("name"), url = banner.getString("url");
                if (name == null || url == null || !GIVEAWAY.matcher(name).find()) continue;
                var link = GAME_LINK.matcher(url);
                if (link.matches()) slugs.add(link.group(1));
            }
            return Optional.of(slugs);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    /**
     * The game page's verdict: a candidate while it is a live giveaway, empty when it is not one (yet or any more).
     * Throws {@link IllegalArgumentException} when the page is not the captured shape.
     */
    static Optional<FreebieFeed.Candidate> candidate(byte[] bytes, String slug, Instant now) {
        Document p = parse(bytes);
        if (!slug.equals(p.getString("slug"))) throw new IllegalArgumentException("unexpected Fanatical product");
        if (!"game".equals(p.getString("type")) || !Boolean.TRUE.equals(p.get("giveaway"))) return Optional.empty();
        Document window = p.get("availability", Document.class);
        if (window == null) return Optional.empty();
        var from = instant(window.getString("valid_from")); var until = instant(window.getString("valid_until"));
        if (from.isEmpty() || until.isEmpty() || now.isBefore(from.get()) || !now.isBefore(until.get())
                || until.get().isAfter(now.plusSeconds(90L * 24 * 3600))) return Optional.empty();
        long price = cents(p.get("price", Document.class)).orElse(0L);
        if (price <= 0 || cents(p.get("currentPrice", Document.class)).orElse(-1L) != 0) return Optional.empty();
        var store = store(p.get("drm", Document.class));
        if (store.isEmpty()) return Optional.empty();
        String name = p.getString("name");
        if (name == null || name.isBlank() || name.length() > 256) throw new IllegalArgumentException("Fanatical product name");
        boolean newsletter = Boolean.TRUE.equals(p.get("giveaway_newsletter"));
        String redeem = store.get() == FreebieStore.STEAM ? "redeem the key on Steam" : "redeem the key on " + store.get().label();
        String instructions = (newsletter ? "Sign in to Fanatical and subscribe to their newsletter, then unlock the key and "
                : "Sign in to Fanatical, unlock the key and ") + redeem + ". Keys can run out.";
        URI claim = page(slug);
        Optional<String> publisher = p.getList("publishers", Document.class, List.of()).stream()
                .map(d -> d.getString("name")).filter(n -> n != null && !n.isBlank() && n.length() <= 120).findFirst();
        return Optional.of(new FreebieFeed.Candidate(FreebieFeed.Provider.FANATICAL, slug + ":" + from.get().getEpochSecond(), store.get(),
                "PC, " + store.get().label() + " key from Fanatical", name, description(p), instructions,
                Optional.of(EpicIntake.usd(price)), image(p.getString("cover")), claim, claim, from, until, region(p),
                steamPage(p), publisher));
    }

    /** The launcher the key is for; keys for other services (and vouchers) are left out. */
    static Optional<FreebieStore> store(Document drm) {
        if (drm == null) return Optional.empty();
        if (Boolean.TRUE.equals(drm.get("steam"))) return Optional.of(FreebieStore.STEAM);
        if (Boolean.TRUE.equals(drm.get("gog"))) return Optional.of(FreebieStore.GOG);
        if (Boolean.TRUE.equals(drm.get("epicgames"))) return Optional.of(FreebieStore.EPIC);
        if (Boolean.TRUE.equals(drm.get("uplay"))) return Optional.of(FreebieStore.UBISOFT);
        if (Boolean.TRUE.equals(drm.get("drm_free"))) return Optional.of(FreebieStore.DRM_FREE);
        return Optional.empty();
    }

    /** "Not claimable in AR, BR, …" or "Only claimable in …" from the page's region lists; never guessed. */
    static String region(Document p) {
        var excluded = codes(p.getList("regions_excluded", Document.class, List.of()));
        if (!excluded.isEmpty()) return "Not claimable in " + String.join(", ", excluded);
        var included = codes(p.getList("regions_included", Document.class, List.of()));
        if (!included.isEmpty()) return "Only claimable in " + String.join(", ", included);
        return "Not specified by source";
    }

    private static List<String> codes(List<Document> regions) {
        List<String> codes = new ArrayList<>();
        for (Document r : regions) {
            String code = r.getString("code");
            if (code != null && code.matches("[A-Z]{2}") && !codes.contains(code)) codes.add(code);
            if (codes.size() == 60) { codes.add("…"); break; }
        }
        return codes;
    }

    /** Fanatical's short description, else the first part of its long one without markup. */
    private static String description(Document p) {
        String raw = p.getString("sdesc");
        if (raw == null || raw.isBlank()) {
            Object desc = p.get("desc");
            if (desc instanceof Document byLanguage) desc = byLanguage.get("en");
            raw = desc instanceof String s ? s : "";
        }
        String flat = raw.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
        return flat.length() > 2000 ? flat.substring(0, 2000) : flat;
    }

    private static Optional<URI> image(String cover) {
        if (cover == null || !COVER.matcher(cover).matches()) return Optional.empty();
        return Optional.of(URI.create("https://fanatical.imgix.net/product/original/" + cover + "?auto=compress,format&w=800"));
    }

    /** The game's Steam page when Fanatical names its Steam app. */
    private static Optional<URI> steamPage(Document p) {
        Document steam = p.get("steam", Document.class);
        if (steam == null || !"app".equals(steam.getString("type")) || !(steam.get("id") instanceof Number id) || id.longValue() <= 0)
            return Optional.empty();
        return Optional.of(URI.create("https://store.steampowered.com/app/" + id.longValue() + "/"));
    }

    private static boolean noSpend(Document spend) {
        if (spend == null) return true;
        for (Object v : spend.values()) if (!(v instanceof Number n) || n.doubleValue() != 0) return false;
        return true;
    }

    /** US cents from a price object ({@code {"USD": 699, …}}). */
    private static Optional<Long> cents(Document price) {
        if (price == null || !(price.get("USD") instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1_000_000) return Optional.empty();
        return Optional.of(Math.round(n.doubleValue()));
    }

    private static Optional<String> slug(String value) {
        return value != null && SLUG.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    private static Optional<Instant> instant(String value) {
        if (value == null) return Optional.empty();
        try { return Optional.of(Instant.parse(value)); } catch (DateTimeParseException invalid) { return Optional.empty(); }
    }

    static Document parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("response too large");
        String json = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!json.startsWith("{")) throw new IllegalArgumentException("not a JSON object");
        return Document.parse(json);
    }
}
