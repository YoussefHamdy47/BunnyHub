package org.bunnys.bunnynexus.freebies;

import com.fasterxml.jackson.core.*;
import org.bson.Document;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Strict parser for the public en-US/US promotions response captured on 2026-10-13. */
final class EpicIntake {
    static final URI ENDPOINT = URI.create("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions?locale=en-US&country=US&allowCountries=US");
    private static final JsonFactory JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(16384)
                    .maxNameLength(128).maxNumberLength(20).maxDocumentLength(GamerPowerIntake.MAX_BYTES)
                    .maxTokenCount(100000).build()).build();

    FreebieFeed.Result decode(byte[] bytes, Instant now) {
        if (bytes.length > GamerPowerIntake.MAX_BYTES) return failed();
        try {
            // Validate bounds, duplicate properties and a single JSON root before BSON's convenient tree reader.
            try (var parser = JSON.createParser(bytes)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) return failed();
                parser.skipChildren();
                if (parser.nextToken() != null) return failed();
            }
            Document root = Document.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (root.containsKey("errors") || root.containsKey("extensions")) return failed();
            Document search = doc(doc(root, "data"), "Catalog");
            search = doc(search, "searchStore");
            List<Document> rows = list(search, "elements");
            Document paging = doc(search, "paging");
            if (rows.size() > 500 || number(paging, "total") != rows.size() || number(paging, "count") < rows.size()) return failed();
            List<FreebieFeed.Candidate> candidates = new ArrayList<>();
            Set<String> ids = new HashSet<>(); int rejected = 0;
            for (Document row : rows) {
                try {
                    String identity = identifier(row, "namespace") + ":" + identifier(row, "id");
                    if (!ids.add(identity)) return failed();
                    candidate(row, now, identity).ifPresent(candidates::add);
                } catch (RuntimeException invalid) { rejected++; }
            }
            return new FreebieFeed.Result(rejected == 0 ? FreebieFeed.State.COMPLETE : FreebieFeed.State.PARTIAL,
                    candidates, Optional.empty(), rejected == 0 ? "" : "partial feed: " + rejected + " rejected item(s)");
        } catch (Exception invalid) { return failed(); }
    }

    private Optional<FreebieFeed.Candidate> candidate(Document row, Instant now, String identity) {
        String type = text(row, "offerType", 64);
        if (!type.equals("BASE_GAME")) return Optional.empty(); // Bundles, DLC, editions and add-ons are intentionally excluded.
        if (!(row.get("isCodeRedemptionOnly") instanceof Boolean)) throw new IllegalArgumentException();
        if (!text(row, "status", 32).equals("ACTIVE") || Boolean.TRUE.equals(row.get("isCodeRedemptionOnly"))) return Optional.empty();
        for (String field : List.of("countriesBlacklist", "countriesWhitelist")) {
            if (!row.containsKey(field)) continue;
            List<String> countries = row.getList(field, String.class);
            if (countries == null) throw new IllegalArgumentException();
            if (field.equals("countriesBlacklist") ? countries.contains("US") : !countries.isEmpty() && !countries.contains("US"))
                return Optional.empty();
        }
        if (!row.containsKey("promotions")) throw new IllegalArgumentException();
        if (row.get("promotions") == null) return Optional.empty();
        Document promotions = doc(row, "promotions");
        for (Document group : list(promotions, "upcomingPromotionalOffers"))
            for (Document promotion : list(group, "promotionalOffers")) validatePromotion(promotion);
        List<Document> groups = list(promotions, "promotionalOffers");
        Instant start = null, end = null;
        for (Document group : groups) for (Document promotion : list(group, "promotionalOffers")) {
            validatePromotion(promotion);
            Instant a = Instant.parse(text(promotion, "startDate", 40)), b = Instant.parse(text(promotion, "endDate", 40));
            if (!a.isBefore(b)) throw new IllegalArgumentException();
            Document discount = doc(promotion, "discountSetting");
            if (!text(discount, "discountType", 32).equals("PERCENTAGE") || number(discount, "discountPercentage") != 0) continue;
            if (now.isBefore(a) || !now.isBefore(b)) continue;
            if (start != null) throw new IllegalArgumentException(); // Ambiguous overlapping promotion windows.
            start = a; end = b;
        }
        if (start == null) return Optional.empty();
        Document price = doc(doc(row, "price"), "totalPrice");
        if (number(price, "discountPrice") != 0 || number(price, "originalPrice") <= 0 || number(price, "voucherDiscount") != 0)
            return Optional.empty();
        if (!text(price, "currencyCode", 3).equals("USD")) throw new IllegalArgumentException();
        List<Document> mappings = list(row, "offerMappings");
        List<String> slugs = mappings.stream().filter(m -> "productHome".equals(m.getString("pageType")))
                .map(m -> text(m, "pageSlug", 200)).distinct().toList();
        if (slugs.size() != 1 || !slugs.getFirst().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalArgumentException();
        URI page = URI.create("https://store.epicgames.com/en-US/p/" + slugs.getFirst());
        Optional<URI> image = list(row, "keyImages").stream().filter(i -> "OfferImageWide".equals(i.getString("type")))
                .map(i -> safeImage(i.getString("url"))).flatMap(Optional::stream).findFirst();
        return Optional.of(new FreebieFeed.Candidate(FreebieFeed.Provider.EPIC, identity + ":" + start + ":" + end, FreebieStore.EPIC,
                "PC, Epic Games Store", text(row, "title", 256), text(row, "description", 8000), "",
                Optional.empty(), image, page, page, Optional.of(start), Optional.of(end), "US (en-US); availability elsewhere may differ"));
    }
    private static void validatePromotion(Document promotion) {
        Instant start = Instant.parse(text(promotion, "startDate", 40)), end = Instant.parse(text(promotion, "endDate", 40));
        if (!start.isBefore(end)) throw new IllegalArgumentException();
        Document discount = doc(promotion, "discountSetting");
        text(discount, "discountType", 32);
        long percentage = number(discount, "discountPercentage");
        if (percentage < 0 || percentage > 100) throw new IllegalArgumentException();
    }
    static Optional<URI> safeImage(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 512 && "https".equals(uri.getScheme()) && "cdn1.epicgames.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().matches("/[A-Za-z0-9/_.-]+") && !uri.getRawPath().contains("..") ? Optional.of(uri) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    private static Document doc(Document parent, String name) { return Objects.requireNonNull(parent.get(name, Document.class)); }
    private static List<Document> list(Document parent, String name) { return Objects.requireNonNull(parent.getList(name, Document.class)); }
    private static long number(Document parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
    private static String text(Document parent, String name, int limit) {
        String value = parent.getString(name);
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException();
        return value;
    }
    private static String identifier(Document row, String name) {
        String value = text(row, name, 64);
        if (!value.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException();
        return value;
    }
    private static FreebieFeed.Result failed() {
        return new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.empty(), "invalid Epic promotions envelope");
    }
}

