package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.OwnerReviewRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.net.URI;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.AlertDocuments.*;

/** Bounded durable preview; does not change catalog or existing attempt formats. */
final class OwnerReviewDocuments {
    private OwnerReviewDocuments() {}
    static Document content(AlertDisplayContent c) {
        return new Document("title", c.title()).append("description", c.description()).append("claimUrl", c.claimUrl().toASCIIString())
                .append("sourceName", c.sourceName()).append("attributionUrl", c.attributionUrl().toASCIIString());
    }
    static AlertDisplayContent content(Document d, Offer o) {
        return new AlertDisplayContent(o.key(), o.contentRevision(), text(d, "title"), text(d, "description"),
                URI.create(text(d, "claimUrl")), text(d, "sourceName"), URI.create(text(d, "attributionUrl")));
    }
    private static String text(Document d, String field) {
        if (!(d.get(field) instanceof String value)) throw new IllegalArgumentException("Invalid review content.");
        return value; // AlertDisplayContent applies field-specific bounds, including multiline descriptions.
    }
    static OwnerReviewRepository.Snapshot read(Document d) {
        version(d);
        var offer = CatalogDocuments.offer(d.get("offer", Document.class));
        var content = content(d.get("content", Document.class), offer);
        String hash = string(d, "materialHash");
        if (!hash.equals(PublicationReviewPolicy.materialHash(offer, content)) || !string(d, "_id").equals(CatalogDocuments.offerId(offer.key())))
            throw new IllegalArgumentException("Review material/identity mismatch.");
        long revision = number(d, "revision");
        int maximum = Math.toIntExact(number(d, "maximumAttempts")), used = Math.toIntExact(number(d, "usedAttempts"));
        var state = OwnerReviewRepository.State.valueOf(string(d, "state"));
        var until = CatalogDocuments.time(d, "releaseUntil");
        if (revision < 1 || maximum < 0 || maximum > MongoOwnerReviewRepository.MAX_ATTEMPTS || used < 0 || used > maximum
                || (state == OwnerReviewRepository.State.RELEASED && (until.isEmpty() || maximum == 0)))
            throw new IllegalArgumentException("Invalid owner release.");
        if (state == OwnerReviewRepository.State.RELEASED) {
            var released = instant(d, "releasedAt");
            if (released.isBefore(instant(d, "verifiedAt")) || !released.isBefore(instant(d, "previewUntil"))
                    || !released.isBefore(until.orElseThrow()) || until.orElseThrow().isAfter(released.plus(MongoOwnerReviewRepository.RELEASE_LIFETIME))
                    || offer.endsAt().isEmpty() || until.orElseThrow().isAfter(offer.endsAt().orElseThrow()))
                throw new IllegalArgumentException("Invalid owner release lifetime.");
        }
        return new OwnerReviewRepository.Snapshot(string(d, "_id"), revision, state, offer, content, hash, string(d, "templateVersion"),
                instant(d, "previewUntil"), until, maximum, used);
    }
}
