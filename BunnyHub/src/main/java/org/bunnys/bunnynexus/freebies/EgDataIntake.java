package org.bunnys.bunnynexus.freebies;

import com.fasterxml.jackson.core.*;
import org.bson.Document;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Strict parser for EGData's documented {@code /free-games?country=US&locale=en-US} list (captured 2026-10-24).
 * EGData is attributed enrichment: it never creates a review, approves a game or keeps an Epic offer alive.
 */
final class EgDataIntake {
    static final String HOST = "api.egdata.app";
    static final String ENDPOINT = "https://" + HOST + "/free-games?country=US&locale=en-US";
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ITEMS = 200;
    private static final JsonFactory JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(65536)
                    .maxNameLength(128).maxNumberLength(20).maxDocumentLength(MAX_BYTES).maxTokenCount(400000).build()).build();

    enum Platform { PC, ANDROID, IOS }
    enum State { COMPLETE, PARTIAL, FAILED }

    /** One exact giveaway offer as EGData reports it. */
    record Offer(EpicLinks.Offer identity, Platform platform, String title, Optional<String> slug, Instant startsAt,
                 Instant endsAt, FreebieRegions.Evidence regions) {
        boolean liveAt(Instant now) { return !now.isBefore(startsAt) && now.isBefore(endsAt); }
    }
    record Result(State state, List<Offer> offers, String problem) {
        Result { offers = List.copyOf(offers); }
    }

    Result decode(byte[] bytes, Instant now) {
        if (bytes.length > MAX_BYTES) return failed("response too large");
        List<Document> rows;
        try {
            try (var parser = JSON.createParser(bytes)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) return failed("not a list");
                parser.skipChildren();
                if (parser.nextToken() != null) return failed("trailing content");
            }
            rows = Document.parse("{\"rows\":" + new String(bytes, StandardCharsets.UTF_8) + "}").getList("rows", Document.class);
        } catch (Exception invalid) { return failed("malformed response"); }
        if (rows == null || rows.size() > MAX_ITEMS) return failed("unexpected list size");
        List<Offer> offers = new ArrayList<>();
        int rejected = 0;
        for (Object raw : rows) {
            try { offer((Document) raw, now).ifPresent(offers::add); }
            catch (RuntimeException invalid) { rejected++; }
        }
        // The same offer reported with different windows is a conflict: neither copy is used.
        Map<EpicLinks.Offer, Set<String>> windows = new HashMap<>();
        for (Offer o : offers) windows.computeIfAbsent(o.identity(), k -> new HashSet<>()).add(o.startsAt() + "/" + o.endsAt());
        List<Offer> consistent = new ArrayList<>();
        Set<EpicLinks.Offer> seen = new HashSet<>();
        for (Offer o : offers) {
            if (windows.get(o.identity()).size() > 1) { rejected++; continue; }
            if (seen.add(o.identity())) consistent.add(o);
        }
        return new Result(rejected == 0 ? State.COMPLETE : State.PARTIAL, consistent,
                rejected == 0 ? "" : rejected + " ignored item(s)");
    }

    private static Optional<Offer> offer(Document row, Instant now) {
        if (!"BASE_GAME".equals(row.getString("offerType"))) return Optional.empty();
        var identity = new EpicLinks.Offer(row.getString("namespace"), row.getString("id"));
        Document giveaway = Objects.requireNonNull(row.get("giveaway", Document.class));
        String offerId = giveaway.getString("offerId") != null ? giveaway.getString("offerId") : giveaway.getString("id");
        if (!identity.offerId().equals(offerId)) throw new IllegalArgumentException("giveaway does not match offer");
        Object platformValue = giveaway.get("platform");
        Platform platform = platformValue == null ? Platform.PC : switch (String.valueOf(platformValue)) {
            case "android" -> Platform.ANDROID;
            case "ios" -> Platform.IOS;
            default -> throw new IllegalArgumentException("unknown platform");
        };
        Instant start = Instant.parse(giveaway.getString("startDate")), end = Instant.parse(giveaway.getString("endDate"));
        if (!start.isBefore(end)) throw new IllegalArgumentException("empty window");
        String title = row.getString("title");
        if (title == null || title.isBlank() || title.length() > 256) throw new IllegalArgumentException("title");
        String pageType = platform == Platform.PC ? "productHome" : "offer";
        List<String> slugs = Optional.ofNullable(row.getList("offerMappings", Document.class)).orElse(List.of()).stream()
                .filter(m -> pageType.equals(m.getString("pageType"))).map(m -> m.getString("pageSlug"))
                .filter(s -> s != null && EpicLinks.SLUG.matcher(s).matches()).distinct().toList();
        return Optional.of(new Offer(identity, platform, title, slugs.size() == 1 ? Optional.of(slugs.getFirst()) : Optional.empty(),
                start, end, FreebieRegions.observe("egdata", now, row)));
    }

    private static Result failed(String problem) { return new Result(State.FAILED, List.of(), problem); }
}
