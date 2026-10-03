package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Durable per-server notice outbox; the daily timestamp advances only on accepted delivery. */
final class FreebieNoticeStore {
    record Claim(String guildId, String deliveryId, String offerId, String failedChannel, FreebieFailure failure,
                 String token, String route, String channelId, String recipientId, boolean verifyFirst) {}
    private final MongoCollection<Document> notices, deliveries;
    FreebieNoticeStore(MongoCollection<Document> notices, MongoCollection<Document> deliveries) {
        this.notices = notices; this.deliveries = deliveries;
    }
    void installIndexes() {
        notices.createIndex(Indexes.ascending("noticeState", "noticeNextAttemptAt", "noticeLeaseUntil"), new IndexOptions().name("notice_due"));
        deliveries.createIndex(Indexes.ascending("noticePending", "noticeCheckAt", "finishedAt"), new IndexOptions().name("notice_source"));
    }

    /** Each write stands alone: an interrupted enqueue/reconciliation is replayed on the next tick. */
    void recover(Instant now) {
        for (var notice : notices.find(and(eq("noticeState", "SENT"), ne("noticeReconciled", true))).limit(20)) {
            deliveries.updateOne(and(eq("_id", notice.getString("noticeDeliveryId")), eq("state", "FAILED")),
                    combine(set("serverNotified", true), set("noticePending", false)));
            notices.updateOne(and(eq("_id", notice.getString("_id")), eq("noticeState", "SENT"),
                            eq("noticeDeliveryId", notice.getString("noticeDeliveryId"))), set("noticeReconciled", true));
        }
        for (var source : deliveries.find(and(eq("state", "FAILED"), eq("noticePending", true),
                or(eq("noticeCheckAt", null), lte("noticeCheckAt", Date.from(now))))).sort(Sorts.ascending("finishedAt", "_id")).limit(20)) {
            enqueue(source, now);
            deliveries.updateOne(and(eq("_id", source.getString("_id")), eq("noticePending", true)),
                    set("noticeCheckAt", Date.from(now.plusSeconds(120))));
        }
    }

    void enqueue(Document source, Instant now) {
        String guild = source.getString("guildId");
        var current = notices.find(eq("_id", guild)).first();
        if (current != null && ("PENDING".equals(current.getString("noticeState"))
                || ("SENT".equals(current.getString("noticeState")) && !current.getBoolean("noticeReconciled", false)))) return;
        Date last = current == null ? null : current.getDate("lastNoticeAt");
        if (last != null && (!last.toInstant().isBefore(now.minus(Duration.ofDays(1)))
                || !last.before(source.getDate("finishedAt")))) {
            deliveries.updateOne(and(eq("_id", source.getString("_id")), eq("noticePending", true)),
                    combine(set("noticePending", false), set("noticeSuppressed", true)));
            return;
        }
        try {
            notices.updateOne(and(eq("_id", guild), ne("noticeState", "PENDING"), ne("noticeReconciled", false),
                            eq("lastNoticeAt", last)),
                    combine(set("noticeState", "PENDING"), set("noticeDeliveryId", source.getString("_id")),
                            set("noticeOfferId", source.getString("offerId")), set("noticeFailedChannel", source.getString("channelId")),
                            set("noticeFailure", source.getString("failure")), set("noticeCreatedAt", Date.from(now)),
                            set("noticeNextAttemptAt", Date.from(now)), set("noticeReconciled", false),
                            unset("noticeToken"), unset("noticeLeaseUntil"), unset("noticeChannelId"), unset("noticeRecipientId"),
                            unset("noticeRoute"), unset("noticeAttempted"), unset("noticeMessageId")), new UpdateOptions().upsert(true));
        } catch (MongoWriteException race) {
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
        }
    }

    Optional<Claim> claim(Instant now) {
        var doc = notices.findOneAndUpdate(and(eq("noticeState", "PENDING"), lte("noticeNextAttemptAt", Date.from(now)),
                        or(eq("noticeLeaseUntil", null), lte("noticeLeaseUntil", Date.from(now)))),
                combine(set("noticeToken", UUID.randomUUID().toString()), set("noticeLeaseUntil", Date.from(now.plusSeconds(180)))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("noticeNextAttemptAt", "_id")).returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(d -> new Claim(d.getString("_id"), d.getString("noticeDeliveryId"),
                d.getString("noticeOfferId"), d.getString("noticeFailedChannel"), FreebieFailure.valueOf(d.getString("noticeFailure")),
                d.getString("noticeToken"), d.getString("noticeRoute"), d.getString("noticeChannelId"),
                d.getString("noticeRecipientId"), d.getBoolean("noticeAttempted", false)));
    }

    private static Bson owned(Claim c, Instant now) {
        return and(eq("_id", c.guildId()), eq("noticeState", "PENDING"), eq("noticeToken", c.token()),
                eq("noticeDeliveryId", c.deliveryId()), gt("noticeLeaseUntil", Date.from(now)));
    }
    boolean owns(Claim c, Instant now) { return notices.countDocuments(owned(c, now)) == 1; }

    boolean destination(Claim c, String route, String channel, String recipient, Instant now) {
        return notices.updateOne(and(owned(c, now), or(eq("noticeChannelId", null), eq("noticeChannelId", channel))),
                combine(set("noticeRoute", route), set("noticeChannelId", channel), set("noticeRecipientId", recipient),
                        set("noticeAttempted", true))).getMatchedCount() == 1;
    }

    /** Only a definite DM rejection may change destination. Timeouts must verify the original destination. */
    boolean fallback(Claim c, Instant now) {
        return notices.updateOne(owned(c, now), combine(set("noticeRoute", "SYSTEM"), unset("noticeChannelId"),
                unset("noticeRecipientId"), set("noticeAttempted", false))).getModifiedCount() == 1;
    }

    boolean sent(Claim c, String message, Instant now) {
        return notices.updateOne(owned(c, now), combine(set("noticeState", "SENT"), set("noticeMessageId", message),
                set("lastNoticeAt", Date.from(now)), unset("noticeToken"), unset("noticeLeaseUntil"))).getModifiedCount() == 1;
    }
    boolean retry(Claim c, Instant now) {
        return notices.updateOne(owned(c, now), combine(set("noticeNextAttemptAt", Date.from(now.plusSeconds(120))),
                unset("noticeToken"), unset("noticeLeaseUntil"))).getModifiedCount() == 1;
    }
}
