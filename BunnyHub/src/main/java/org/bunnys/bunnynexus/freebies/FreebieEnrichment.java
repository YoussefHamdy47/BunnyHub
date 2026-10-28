package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Optional EGData enrichment on the discovery thread. Attaches region evidence to the exact Epic offer (same
 * namespace and catalog offer ID, overlapping window) and records separately identified Android/iOS offers of the
 * same namespace and official title. Failures leave everything unknown; nothing here creates, approves, revives or
 * ends a giveaway.
 */
final class FreebieEnrichment {
    static final Duration EVERY = Duration.ofMinutes(30), MAX_BACKOFF = Duration.ofHours(4);
    private final MongoCollection<Document> offers;
    private final EgDataFeed feed;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private int failures;
    private volatile Instant lastAttempt, lastOk;
    private volatile String problem = "not checked yet";

    FreebieEnrichment(MongoCollection<Document> offers, EgDataFeed feed, Clock clock) {
        this.offers = offers; this.feed = feed; this.clock = clock;
    }

    void start(ScheduledExecutorService discoveryThread, long initialDelaySeconds) {
        executor = discoveryThread;
        executor.schedule(this::run, initialDelaySeconds, TimeUnit.SECONDS);
    }

    String status() {
        return "Last EGData enrichment: " + (lastAttempt == null ? "not yet" : "<t:" + lastAttempt.getEpochSecond() + ":R>")
                + (problem.isEmpty() ? " ✅" : " ⚠️ " + problem)
                + (lastOk == null ? "" : " · last success <t:" + lastOk.getEpochSecond() + ":R>");
    }

    private void run() {
        Duration next;
        try { next = attempt(); }
        catch (Throwable failure) {
            failures++; problem = "error: " + failure.getClass().getSimpleName();
            BunnyLog.warning("[Freebies] EGData enrichment failed | " + FailureDiagnostics.describe(failure));
            next = backoff();
        }
        try { if (!executor.isShutdown()) executor.schedule(this::run, Math.max(60, next.toSeconds()), TimeUnit.SECONDS); }
        catch (RejectedExecutionException shuttingDown) { /* Shutdown raced the reschedule. */ }
    }

    Duration attempt() {
        lastAttempt = clock.instant();
        var result = feed.poll();
        if (result.state() == EgDataIntake.State.FAILED) { failures++; problem = result.problem(); return backoff(); }
        apply(result.offers(), clock.instant());
        failures = 0; problem = result.problem(); lastOk = clock.instant();
        return EVERY;
    }

    private Duration backoff() {
        Duration wait = EVERY.multipliedBy(1L << Math.min(failures, 3));
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    /** Applies live entries only; upcoming ones never become claims. Returns the number of offers updated. */
    int apply(List<EgDataIntake.Offer> list, Instant now) {
        int changed = 0;
        for (var offer : list) {
            if (!offer.liveAt(now)) continue;
            changed += offer.platform() == EgDataIntake.Platform.PC ? regions(offer, now) : variant(offer, now);
        }
        return changed;
    }

    private static boolean overlaps(Document d, EgDataIntake.Offer offer) {
        var start = FreebieIdentity.start(d);
        var end = FreebieIdentity.comparableEnd(d);
        return start.isPresent() && end.isPresent() && start.get().isBefore(offer.endsAt()) && offer.startsAt().isBefore(end.get());
    }

    private int regions(EgDataIntake.Offer offer, Instant now) {
        int changed = 0;
        for (Document d : offers.find(and(eq("epicOffer.namespace", offer.identity().namespace()),
                eq("epicOffer.offerId", offer.identity().offerId()), in("state", "PENDING", "APPROVED", "COMPLETED"))).limit(10)) {
            if (!overlaps(d, offer)) continue; // A repeat giveaway of the same catalog offer is another occurrence.
            Document saved = Optional.ofNullable(d.get("regions", Document.class)).map(r -> r.get("egdata", Document.class)).orElse(null);
            var before = FreebieRegions.Evidence.read(saved);
            var evidence = offer.regions();
            boolean restrictionsChanged = before.map(b -> !b.blacklist().equals(evidence.blacklist())
                    || !b.whitelist().equals(evidence.whitelist()) || b.malformed() != evidence.malformed()).orElse(true);
            List<Bson> changes = new ArrayList<>(List.of(set("regions.egdata", evidence.toDocument()
                    .append("startsAt", Date.from(offer.startsAt())).append("endsAt", Date.from(offer.endsAt())))));
            if (restrictionsChanged) changes.add(inc(d.getString("state").equals("PENDING") ? "revision" : "cardRevision", 1L));
            if (restrictionsChanged && d.getString("state").equals("PENDING")) changes.add(set("reviewRefresh", true));
            if (offers.updateOne(and(eq("_id", d.getString("_id")), eq("state", d.getString("state"))), combine(changes)).getModifiedCount() == 1
                    && restrictionsChanged) changed++;
        }
        return changed;
    }

    private int variant(EgDataIntake.Offer offer, Instant now) {
        if (offer.slug().isEmpty()) return 0; // Without an official page there is nothing to link.
        String key = offer.platform() == EgDataIntake.Platform.ANDROID ? "android" : "ios";
        int changed = 0;
        for (Document d : offers.find(and(eq("epicOffer.namespace", offer.identity().namespace()),
                in("state", "PENDING", "APPROVED", "COMPLETED"), exists("official"))).limit(10)) {
            String official = d.get("official", Document.class).getString("title");
            // Same namespace alone does not prove the same game: the official title must match exactly (normalized).
            if (!FreebieIdentity.normalized(official).equals(FreebieIdentity.normalized(offer.title())) || !overlaps(d, offer)) continue;
            Document saved = Optional.ofNullable(d.get("variants", Document.class)).map(v -> v.get(key, Document.class)).orElse(null);
            if (saved != null && !offer.identity().offerId().equals(saved.getString("offerId"))) continue; // Conflicting identity: keep the first.
            boolean pending = d.getString("state").equals("PENDING");
            boolean reviewed = saved != null ? saved.getBoolean("reviewed", false) || pending : pending;
            var variant = new Document("namespace", offer.identity().namespace()).append("offerId", offer.identity().offerId())
                    .append("slug", offer.slug().get()).append("startsAt", Date.from(offer.startsAt())).append("endsAt", Date.from(offer.endsAt()))
                    .append("regions", offer.regions().toDocument()).append("provider", "egdata").append("observedAt", Date.from(now))
                    .append("reviewed", reviewed);
            boolean fresh = saved == null || saved.getBoolean("reviewed", false) != reviewed;
            List<Bson> changes = new ArrayList<>(List.of(set("variants." + key, variant)));
            if (fresh && pending) { changes.add(inc("revision", 1L)); changes.add(set("reviewRefresh", true)); }
            if (offers.updateOne(and(eq("_id", d.getString("_id")), eq("state", d.getString("state"))), combine(changes)).getModifiedCount() == 1
                    && fresh) changed++;
        }
        return changed;
    }
}
