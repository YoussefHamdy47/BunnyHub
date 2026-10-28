package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Enrichment saved on an offer beside the reviewed fields: exact Epic identity, release group, region evidence,
 * separately identified mobile variants and the platforms a secondary source reported. Read-only view.
 */
record FreebieFacts(Optional<EpicLinks.Offer> epicOffer, Optional<String> groupKey, Optional<FreebieRegions.Evidence> regions,
                    List<Variant> variants, Optional<String> reportedPlatforms, boolean official) {
    static final FreebieFacts NONE = new FreebieFacts(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), false);

    /**
     * A separately identified platform offer (Android or iOS) with its own catalog pair, window, page and restrictions.
     * {@code reviewed}: it was present while the giveaway awaited review, so the approval covers it. A variant found
     * after approval is never added to public cards automatically.
     */
    record Variant(EgDataIntake.Platform platform, EpicLinks.Offer offer, String slug, Instant startsAt, Instant endsAt,
                   Optional<FreebieRegions.Evidence> regions, boolean reviewed) {
        URI page() { return EpicLinks.productPage(slug); }
        boolean liveAt(Instant now) { return !now.isBefore(startsAt) && now.isBefore(endsAt); }
        String label() { return platform == EgDataIntake.Platform.ANDROID ? "Android" : "iOS"; }
    }

    /** Variants an owner reviewed that are claimable now. */
    List<Variant> publicVariants(Instant now) { return variants.stream().filter(v -> v.reviewed() && v.liveAt(now)).toList(); }

    static FreebieFacts read(Document d) {
        if (d == null) return NONE;
        Optional<EpicLinks.Offer> epic = Optional.empty();
        Document e = d.get("epicOffer", Document.class);
        if (e != null) {
            try { epic = Optional.of(new EpicLinks.Offer(e.getString("namespace"), e.getString("offerId"))); }
            catch (IllegalArgumentException invalid) { epic = Optional.empty(); }
        }
        Document regions = d.get("regions", Document.class);
        var evidence = Optional.ofNullable(regions).flatMap(r -> FreebieRegions.Evidence.read(r.get("egdata", Document.class)));
        List<Variant> variants = new ArrayList<>();
        Document saved = d.get("variants", Document.class);
        if (saved != null) for (String key : List.of("android", "ios")) {
            Document v = saved.get(key, Document.class);
            if (v == null) continue;
            try {
                variants.add(new Variant(key.equals("android") ? EgDataIntake.Platform.ANDROID : EgDataIntake.Platform.IOS,
                        new EpicLinks.Offer(v.getString("namespace"), v.getString("offerId")), Objects.requireNonNull(v.getString("slug")),
                        v.getDate("startsAt").toInstant(), v.getDate("endsAt").toInstant(),
                        FreebieRegions.Evidence.read(v.get("regions", Document.class)), v.getBoolean("reviewed", false)));
            } catch (RuntimeException invalid) { /* An unreadable variant is ignored, never guessed. */ }
        }
        Document reported = d.get("reported", Document.class);
        var platforms = Optional.ofNullable(reported).map(r -> r.get("gamerpower", Document.class)).map(g -> g.getString("platforms"));
        return new FreebieFacts(epic, Optional.ofNullable(d.getString("groupKey")), evidence, List.copyOf(variants), platforms,
                d.get("official") != null);
    }
}
