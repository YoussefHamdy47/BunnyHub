package org.bunnys.bunnynexus.freebies;

import org.bson.Document;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The Microsoft Store's own price for an Xbox game, from its public catalog (no key): confirms that a console giveaway
 * is real (the store price is $0 and the normal price is not) and gives the official title, picture and end date. Only
 * the full game's public store price counts: the catalog also lists $0 entries for Game Pass members, trials and
 * licences, which never make a game free. Pure parsing.
 */
final class XboxStore {
    static final String HOST = "displaycatalog.mp.microsoft.com", IMAGES = "store-images.s-microsoft.com";
    private static final Pattern PRODUCT = Pattern.compile("^[0-9A-Z]{12}$");
    /** xbox.com/…/games/store/<slug>/<ID> and microsoft.com/…/p/<slug>/<ID>. */
    private static final Set<String> STORE_HOSTS = Set.of("www.xbox.com", "xbox.com", "www.microsoft.com", "apps.microsoft.com");

    /** What the store says about one game. {@code ends}: when the current store price ends, when it says. */
    record Price(String productId, String title, Optional<URI> image, double listPrice, double msrp, Optional<Instant> ends) {
        boolean freeToKeep() { return listPrice == 0 && msrp > 0; }
        URI storePage() { return URI.create("https://www.xbox.com/en-US/games/store/x/" + productId); }
    }

    private XboxStore() {}

    /** The product ID in an Xbox or Microsoft Store link. */
    static Optional<String> productId(URI link) {
        if (link == null || !"https".equals(link.getScheme()) || link.getHost() == null || !STORE_HOSTS.contains(link.getHost().toLowerCase(Locale.ROOT)))
            return Optional.empty();
        String[] parts = Objects.requireNonNullElse(link.getPath(), "").split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].toUpperCase(Locale.ROOT);
            if (PRODUCT.matcher(part).matches()) return Optional.of(part);
        }
        return Optional.empty();
    }

    static String url(String productId) {
        return "https://" + HOST + "/v7.0/products?bigIds=" + productId + "&market=US&languages=en-us";
    }

    /** The game's public store price on Xbox, or empty when the response has no such price. */
    static Optional<Price> price(byte[] body, String productId) {
        try {
            for (Document p : Document.parse(new String(body, StandardCharsets.UTF_8)).getList("Products", Document.class, List.of())) {
                if (!productId.equals(p.getString("ProductId"))) continue;
                var local = p.getList("LocalizedProperties", Document.class, List.of());
                String title = local.isEmpty() ? null : local.getFirst().getString("ProductTitle");
                if (title == null || title.isBlank()) return Optional.empty();
                for (Document sku : p.getList("DisplaySkuAvailabilities", Document.class, List.of())) {
                    var skuDoc = sku.get("Sku", Document.class);
                    var props = skuDoc == null ? null : skuDoc.get("Properties", Document.class);
                    if (props != null && Boolean.TRUE.equals(props.getBoolean("IsTrial"))) continue;
                    for (Document a : sku.getList("Availabilities", Document.class, List.of())) {
                        var actions = a.getList("Actions", String.class, List.of());
                        if (!actions.contains("Purchase") || !actions.contains("Browse") || !onXbox(a)) continue;
                        var order = a.get("OrderManagementData", Document.class);
                        var price = order == null ? null : order.get("Price", Document.class);
                        if (price == null || !"USD".equals(price.getString("CurrencyCode"))) continue;
                        if (!(price.get("ListPrice") instanceof Number list) || !(price.get("MSRP") instanceof Number msrp)) continue;
                        return Optional.of(new Price(productId, title.strip(), image(local.getFirst()), list.doubleValue(), msrp.doubleValue(), end(a)));
                    }
                }
            }
        } catch (RuntimeException unreadable) { return Optional.empty(); }
        return Optional.empty();
    }

    private static boolean onXbox(Document availability) {
        var conditions = availability.get("Conditions", Document.class);
        var client = conditions == null ? null : conditions.get("ClientConditions", Document.class);
        if (client == null) return false;
        for (Document platform : client.getList("AllowedPlatforms", Document.class, List.of()))
            if ("Windows.Xbox".equals(platform.getString("PlatformName"))) return true;
        return false;
    }

    private static Optional<Instant> end(Document availability) {
        var conditions = availability.get("Conditions", Document.class);
        if (conditions == null || !(conditions.get("EndDate") instanceof String date)) return Optional.empty();
        try {
            var end = Instant.parse(date.replaceAll("\\.\\d+Z$", "Z"));
            return end.isAfter(Instant.parse("9000-01-01T00:00:00Z")) ? Optional.empty() : Optional.of(end);
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }

    private static Optional<URI> image(Document local) {
        for (String purpose : List.of("BoxArt", "Poster", "TitledHeroArt", "SuperHeroArt"))
            for (Document image : local.getList("Images", Document.class, List.of()))
                if (purpose.equals(image.getString("ImagePurpose")) && image.getString("Uri") instanceof String uri) {
                    try {
                        var full = URI.create((uri.startsWith("//") ? "https:" + uri : uri) + "?w=600");
                        if ("https".equals(full.getScheme()) && IMAGES.equals(full.getHost())) return Optional.of(full);
                    } catch (RuntimeException bad) { /* next */ }
                }
        return Optional.empty();
    }
}
