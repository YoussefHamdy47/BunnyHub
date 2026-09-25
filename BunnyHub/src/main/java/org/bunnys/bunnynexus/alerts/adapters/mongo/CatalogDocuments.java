package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.AlertDocuments.*;

/** Catalog identity remains independent of display text, source and audience origin. */
public final class CatalogDocuments {
    private CatalogDocuments() {}
    public static String offerId(OfferKey key) { return id("offer-v1", key.store().value(), key.campaignKey(), key.market().value()); }
    static String id(String... parts) {
        return StableIdentity.hash(parts);
    }
    public static Document offer(Offer o) {
        var e = o.evidence();
        Document d = base(offerId(o.key())).append("storeId", o.key().store().value()).append("campaignKey", o.key().campaignKey())
                .append("market", o.key().market().value()).append("editionId", o.editionId()).append("kind", o.kind().name())
                .append("verifiedAt", date(o.verifiedAt())).append("contentRevision", o.contentRevision())
                .append("evidence", new Document("market", e.market().name()).append("entitlement", e.entitlement().name())
                        .append("claimConditions", e.claimConditions().name()).append("approvedLink", e.approvedLink().name())
                        .append("sourceId", e.source().value()).append("sourceItemId", e.sourceItemId()).append("identityRuleVersion", e.identityRuleVersion()));
        o.price().ifPresent(p -> d.append("price", new Document("minorUnits", p.minorUnits()).append("currency", p.currency().getCurrencyCode())));
        o.startsAt().ifPresent(t -> d.append("startsAt", date(t))); o.endsAt().ifPresent(t -> d.append("endsAt", date(t)));
        return d;
    }
    public static Offer offer(Document d) {
        version(d); Document e = Objects.requireNonNull(d.get("evidence", Document.class));
        OfferKey key = new OfferKey(new StoreId(string(d, "storeId")), string(d, "campaignKey"), new Market(string(d, "market")));
        if (!offerId(key).equals(string(d, "_id"))) throw new IllegalArgumentException("Canonical offer ID mismatch.");
        Optional<Offer.Money> price = Optional.ofNullable(d.get("price", Document.class)).map(p ->
                new Offer.Money(number(p, "minorUnits"), Currency.getInstance(string(p, "currency"))));
        return new Offer(key, string(d, "editionId"), Offer.Kind.valueOf(string(d, "kind")),
                new Offer.Evidence(Offer.Proof.valueOf(string(e, "market")), Offer.Proof.valueOf(string(e, "entitlement")),
                        Offer.Proof.valueOf(string(e, "claimConditions")), Offer.Proof.valueOf(string(e, "approvedLink")),
                        new SourceId(string(e, "sourceId")), string(e, "sourceItemId"), string(e, "identityRuleVersion")),
                price, time(d, "startsAt"), time(d, "endsAt"), instant(d, "verifiedAt"), number(d, "contentRevision"));
    }
    static Optional<Instant> time(Document d, String field) { return d.containsKey(field) ? Optional.of(instant(d, field)) : Optional.empty(); }
    public record Event(String id, Offer snapshot, Instant observedAt) {
        public Event {
            id = token(id); Objects.requireNonNull(snapshot); Objects.requireNonNull(observedAt);
            if (snapshot.verifiedAt().isAfter(observedAt)) throw new IllegalArgumentException("Future event evidence.");
        }
    }
    public static Document event(Event event) {
        return base(event.id()).append("kind", "FREE_GAME_AVAILABLE").append("payloadVersion", 1)
                .append("offerId", offerId(event.snapshot().key())).append("observedAt", date(event.observedAt()))
                .append("snapshot", offer(event.snapshot()));
    }
    public static Event event(Document d) {
        version(d);
        if (!"FREE_GAME_AVAILABLE".equals(string(d, "kind")) || number(d, "payloadVersion") != 1)
            throw new IllegalArgumentException("Unsupported event envelope.");
        Offer offer = offer(d.get("snapshot", Document.class));
        if (!offerId(offer.key()).equals(string(d, "offerId"))) throw new IllegalArgumentException("Event offer mismatch.");
        return new Event(string(d, "_id"), offer, instant(d, "observedAt"));
    }
}
