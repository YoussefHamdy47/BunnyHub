package org.bunnys.bunnynexus.consoles;

import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Xbox Game Pass, read from Microsoft's own public lists (no key): the Game Pass catalog's "Recently added", "Coming
 * to Game Pass" and "Leaving soon" lists give store product IDs, and the Microsoft Store catalog gives each game's
 * name, picture, release date and whether it runs on Xbox consoles. Pure parsing.
 */
public final class GamePass {
    public enum Shelf {
        ADDED("added", "f13cf6b4-57e6-4459-89df-6aec18cf0538", "New on Game Pass"),
        COMING("coming", "095bda36-f5cd-43f2-9ee1-0a72f371fb96", "Coming soon"),
        LEAVING("leaving", "393f05bf-e596-4ef6-9487-6d4fa0eab987", "Leaving soon");

        final String id, sigl, heading;
        Shelf(String id, String sigl, String heading) { this.id = id; this.sigl = sigl; this.heading = heading; }
    }

    /** One game. {@code console}: runs on Xbox consoles, or unknown (a game not yet released lists no packages). */
    public record Game(String id, String title, Optional<String> image, boolean console, Optional<LocalDate> release) {
        public String link() { return "https://www.xbox.com/en-US/games/store/x/" + id; }

        Document toDocument() {
            return new Document("id", id).append("title", title).append("image", image.orElse(null)).append("console", console)
                    .append("release", release.map(LocalDate::toString).orElse(null));
        }

        static Optional<Game> read(Document d) {
            try {
                String id = d.getString("id");
                if (!PRODUCT.matcher(id).matches()) return Optional.empty();
                return Optional.of(new Game(id, d.getString("title"), Optional.ofNullable(d.getString("image")).filter(GamePass::imageHost),
                        d.getBoolean("console", true), Optional.ofNullable(d.getString("release")).map(LocalDate::parse)));
            } catch (RuntimeException unreadable) { return Optional.empty(); }
        }
    }

    static final String SIGLS = "catalog.gamepass.com", CATALOG = "displaycatalog.mp.microsoft.com", IMAGES = "store-images.s-microsoft.com";
    static final Pattern PRODUCT = Pattern.compile("^[0-9A-Z]{12}$");
    static final int MAX_LIST = 400, BATCH = 10;
    private static final List<String> ART = List.of("TitledHeroArt", "SuperHeroArt", "BoxArt", "Poster");

    private GamePass() {}

    static String siglUrl(Shelf list) {
        return "https://" + SIGLS + "/sigls/v2?id=" + list.sigl + "&language=en-us&market=US";
    }

    static String catalogUrl(List<String> ids) {
        return "https://" + CATALOG + "/v7.0/products?bigIds=" + String.join(",", ids) + "&market=US&languages=en-us";
    }

    /** The product IDs of one catalog list, in its order; empty when unreadable. */
    static List<String> ids(byte[] body) {
        List<String> ids = new ArrayList<>();
        try {
            var list = Document.parse("{\"l\":" + new String(body, StandardCharsets.UTF_8) + "}").getList("l", Document.class);
            for (Document entry : list) {
                String id = entry.getString("id");
                if (id != null && PRODUCT.matcher(id).matches() && !ids.contains(id)) ids.add(id);
                if (ids.size() == MAX_LIST) break;
            }
        } catch (RuntimeException unreadable) { return List.of(); }
        return ids;
    }

    /** The games in a store catalog response; products without a title are skipped. */
    static List<Game> games(byte[] body) {
        List<Game> games = new ArrayList<>();
        try {
            for (Document p : Document.parse(new String(body, StandardCharsets.UTF_8)).getList("Products", Document.class, List.of())) {
                String id = p.getString("ProductId");
                var local = p.getList("LocalizedProperties", Document.class, List.of());
                if (id == null || !PRODUCT.matcher(id).matches() || local.isEmpty()) continue;
                String title = local.getFirst().getString("ProductTitle");
                if (title == null || title.isBlank()) continue;
                games.add(new Game(id, title.strip(), image(local.getFirst()), console(p), release(p)));
            }
        } catch (RuntimeException unreadable) { return List.of(); }
        return games;
    }

    private static Optional<String> image(Document local) {
        var images = local.getList("Images", Document.class, List.of());
        for (String purpose : ART)
            for (Document image : images)
                if (purpose.equals(image.getString("ImagePurpose")) && image.getString("Uri") != null) {
                    String uri = image.getString("Uri");
                    String full = uri.startsWith("//") ? "https:" + uri : uri;
                    // A smaller copy: the originals are up to 4K.
                    if (imageHost(full)) return Optional.of(full.contains("?") ? full : full + "?w=800");
                }
        return Optional.empty();
    }

    static boolean imageHost(String url) {
        try {
            var uri = java.net.URI.create(url);
            return "https".equals(uri.getScheme()) && IMAGES.equals(uri.getHost());
        } catch (RuntimeException bad) { return false; }
    }

    /** True when a package runs on Xbox, or when no package is listed yet. */
    private static boolean console(Document product) {
        boolean anyPackage = false;
        for (Document sku : product.getList("DisplaySkuAvailabilities", Document.class, List.of())) {
            var skuDoc = sku.get("Sku", Document.class);
            var props = skuDoc == null ? null : skuDoc.get("Properties", Document.class);
            if (props == null) continue;
            for (Document pkg : props.getList("Packages", Document.class, List.of())) {
                for (Document platform : pkg.getList("PlatformDependencies", Document.class, List.of())) {
                    anyPackage = true;
                    if ("Windows.Xbox".equals(platform.getString("PlatformName"))) return true;
                }
            }
        }
        return !anyPackage;
    }

    /** The original release date; Microsoft's placeholder (year 9998) means unknown. */
    private static Optional<LocalDate> release(Document product) {
        var market = product.getList("MarketProperties", Document.class, List.of());
        if (market.isEmpty() || !(market.getFirst().get("OriginalReleaseDate") instanceof String date)) return Optional.empty();
        try {
            var day = Instant.parse(date.replaceAll("\\.\\d+Z$", "Z")).atZone(ZoneOffset.UTC).toLocalDate();
            return day.getYear() >= 9000 ? Optional.empty() : Optional.of(day);
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
