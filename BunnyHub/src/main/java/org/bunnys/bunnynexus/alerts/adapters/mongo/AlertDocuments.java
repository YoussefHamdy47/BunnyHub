package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Explicit BSON v1 mapping. Dates require millisecond precision; future schemas fail visibly. */
public final class AlertDocuments {
    public static final int VERSION = 1;
    private AlertDocuments() {}
    public static Document delivery(DeliveryJob job) {
        var s = job.snapshot();
        var d = base(s.id()).append("eventId", s.key().eventId());
        destination(d, s.destination());
        d.append("subscriptionId", s.subscriptionId()).append("createdAt", date(s.createdAt()))
                .append("state", s.state().name()).append("dueAt", date(s.dueAt())).append("revision", s.revision())
                .append("generation", s.generation()).append("attemptCount", s.attemptCount()).append("reason", s.reason().name());
        s.lease().ifPresent(l -> lease(d, l));
        // One bounded current-attempt projection; history/outcomes belong to AlertAttempts.
        s.attempt().ifPresent(a -> d.append("attempt", attempt(a)));
        s.messageId().ifPresent(id -> d.append("messageId", id));
        return d;
    }
    public static DeliveryJob delivery(Document d) {
        version(d);
        return DeliveryJob.restore(new DeliveryJob.Snapshot(string(d, "_id"),
                new DeliveryKey(string(d, "eventId"), string(d, "channelId")), destination(d),
                string(d, "subscriptionId"), instant(d, "createdAt"), DeliveryJob.State.valueOf(string(d, "state")),
                instant(d, "dueAt"), number(d, "revision"), number(d, "generation"), integer(d, "attemptCount"),
                lease(d), Optional.ofNullable(d.get("attempt", Document.class)).map(AlertDocuments::attempt),
                DeliveryJob.Reason.valueOf(string(d, "reason")), optionalString(d, "messageId")));
    }
    public static Document attempt(DeliveryJob.Attempt a) {
        return base(a.id()).append("revision", 1L).append("ordinal", a.ordinal()).append("nonce", a.nonce())
                .append("contentRevision", a.contentRevision()).append("subscriptionRevision", a.subscriptionRevision())
                .append("templateVersion", a.templateVersion()).append("payloadHash", a.payloadHash())
                .append("roleIds", a.roleIds().stream().sorted().toList()).append("startedAt", date(a.startedAt()))
                .append("validUntil", date(a.validUntil()));
    }
    public static DeliveryJob.Attempt attempt(Document d) {
        version(d);
        return new DeliveryJob.Attempt(string(d, "_id"), integer(d, "ordinal"), string(d, "nonce"),
                number(d, "contentRevision"), number(d, "subscriptionRevision"), string(d, "templateVersion"),
                string(d, "payloadHash"), roles(d), instant(d, "startedAt"), instant(d, "validUntil"));
    }
    public static Document plan(FanoutPlan p) {
        var key = p.notificationKey().offer();
        var d = base(p.id()).append("eventId", p.eventId()).append("kind", "AUTOMATIC_FREE_GAME")
                .append("storeId", key.store().value()).append("campaignKey", key.campaignKey()).append("market", key.market().value())
                .append("observedAt", date(p.observedAt())).append("state", p.state().name())
                .append("revision", p.revision()).append("generation", p.generation())
                .append("inserted", p.inserted()).append("duplicates", p.duplicates()).append("skipped", p.skipped())
                .append("outboxCounted", p.outboxCounted());
        p.afterSubscriptionId().ifPresent(id -> d.append("afterSubscriptionId", id));
        p.lease().ifPresent(l -> lease(d, l));
        return d;
    }
    public static FanoutPlan plan(Document d) {
        version(d);
        if (!"AUTOMATIC_FREE_GAME".equals(string(d, "kind"))) throw new IllegalArgumentException("Unsupported audience plan kind.");
        return new FanoutPlan(string(d, "_id"), string(d, "eventId"),
                new FreeGameKey(new OfferKey(new StoreId(string(d, "storeId")), string(d, "campaignKey"), new Market(string(d, "market")))),
                instant(d, "observedAt"), FanoutPlan.State.valueOf(string(d, "state")), optionalString(d, "afterSubscriptionId"),
                number(d, "revision"), number(d, "generation"), lease(d), number(d, "inserted"), number(d, "duplicates"), number(d, "skipped"),
                d.getBoolean("outboxCounted", false));
    }
    /** Stored subscription has references, not authoritative copies of guild/destination enabled flags. */
    public static Document subscription(Subscription s) {
        var d = base(s.id()); destination(d, s.destination());
        return d.append("storeId", s.store().value()).append("market", s.market().value()).append("topic", s.topic().name())
                .append("enabled", s.enabled()).append("enabledSince", date(s.enabledSince())).append("revision", s.revision())
                .append("roleIds", s.roleIds().stream().sorted().toList());
    }
    /** Join only verified current guard records. Missing/replaced/cross-guild guards disable the projection. */
    public static Subscription subscription(Document d, Document guild, Document destination) {
        version(d); DestinationRef ref = destination(d);
        boolean guildEnabled = false, destinationEnabled = false;
        if (guild != null) {
            version(guild);
            guildEnabled = ref.guildId().equals(string(guild, "_id")) && enabled(guild);
        }
        if (destination != null) {
            version(destination);
            destinationEnabled = ref.destinationId().equals(string(destination, "_id"))
                    && ref.guildId().equals(string(destination, "guildId"))
                    && ref.channelId().equals(string(destination, "channelId"))
                    && ref.incarnation().equals(string(destination, "incarnation")) && enabled(destination);
        }
        return new Subscription(string(d, "_id"), ref, new StoreId(string(d, "storeId")), new Market(string(d, "market")),
                Topic.valueOf(string(d, "topic")), guildEnabled, destinationEnabled, enabled(d), instant(d, "enabledSince"),
                number(d, "revision"), roles(d));
    }
    static Document base(String id) { return new Document("_id", token(id)).append("schemaVersion", VERSION); }
    static void version(Document d) {
        Objects.requireNonNull(d);
        if (number(d, "schemaVersion") != VERSION) throw new IllegalArgumentException("Unsupported alert schema version.");
    }
    static String string(Document d, String key) {
        Object value = d.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string field: " + key);
        return token(text);
    }
    static long number(Document d, String key) {
        Object value = d.get(key);
        if (value instanceof Long n) return n;
        if (value instanceof Integer n) return n.longValue();
        throw new IllegalArgumentException("Expected integer field: " + key);
    }
    static int integer(Document d, String key) { return Math.toIntExact(number(d, key)); }
    static Instant instant(Document d, String key) {
        if (!(d.get(key) instanceof Date value)) throw new IllegalArgumentException("Expected date field: " + key);
        return value.toInstant();
    }
    static Date date(Instant time) {
        if (time.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("Persisted alert timestamps require millisecond precision.");
        return Date.from(time);
    }
    private static Optional<String> optionalString(Document d, String key) {
        return d.containsKey(key) ? Optional.of(string(d, key)) : Optional.empty();
    }
    private static boolean enabled(Document d) {
        if (!(d.get("enabled") instanceof Boolean value)) throw new IllegalArgumentException("Expected enabled boolean.");
        return value;
    }
    private static Set<String> roles(Document d) {
        if (!(d.get("roleIds") instanceof List<?> values) || values.size() > Subscription.MAX_ROLE_IDS)
            throw new IllegalArgumentException("Invalid bounded roles.");
        Set<String> roles = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String role) || !roles.add(snowflake(role))) throw new IllegalArgumentException("Invalid/duplicate role ID.");
        }
        return Set.copyOf(roles);
    }
    private static void destination(Document d, DestinationRef ref) {
        d.append("guildId", ref.guildId()).append("channelId", ref.channelId())
                .append("destinationId", ref.destinationId()).append("incarnation", ref.incarnation());
    }
    private static DestinationRef destination(Document d) {
        return new DestinationRef(string(d, "guildId"), string(d, "channelId"), string(d, "destinationId"), string(d, "incarnation"));
    }
    private static void lease(Document d, DeliveryJob.Lease lease) {
        d.append("leaseToken", lease.token()).append("leaseUntil", date(lease.until()));
    }
    private static Optional<DeliveryJob.Lease> lease(Document d) {
        if (!d.containsKey("leaseToken") && !d.containsKey("leaseUntil")) return Optional.empty();
        return Optional.of(new DeliveryJob.Lease(string(d, "leaseToken"), number(d, "generation"), instant(d, "leaseUntil")));
    }
}
