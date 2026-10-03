package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;

/** Read-only operational snapshot. Bounded detail lists; never exposes stored exception text. */
final class FreebieHealth {
    record Queue(long total, Instant oldest, long leased, long expired) {}
    record Destination(String offerId, String channelId, String state) {}
    record Failure(String guildId, String channelId, String reason) {}
    record Snapshot(Queue deliveries, Queue reviews, Queue summaries, Queue notices,
                    long failedDay, List<Failure> failures, List<Destination> summariesWaiting) {}
    private final MongoCollection<Document> offers, deliveries, notices;

    FreebieHealth(MongoDatabase db) {
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        notices = db.getCollection(FreebieRepository.NOTICES);
    }

    void installIndexes() {
        deliveries.createIndex(Indexes.ascending("state", "finishedAt"), new IndexOptions().name("delivery_finished"));
        deliveries.createIndex(Indexes.ascending("state", "createdAt"), new IndexOptions().name("delivery_age"));
    }

    Snapshot snapshot(Instant now) {
        var pendingSummary = and(in("state", "COMPLETED", "STOPPED"), ne("summaryPosted", true));
        var recentFailure = and(eq("state", "FAILED"), gte("finishedAt", Date.from(now.minus(Duration.ofDays(1)))));
        List<Failure> failures = deliveries.find(recentFailure).sort(Sorts.descending("finishedAt")).limit(5)
                .map(d -> new Failure(d.getString("guildId"), d.getString("channelId"), d.getString("failure")))
                .into(new ArrayList<>());
        List<Destination> summaries = offers.find(pendingSummary).sort(Sorts.ascending("updatedAt", "_id")).limit(5)
                .map(d -> new Destination(d.getString("_id"), d.getString("summaryChannelId") != null
                        ? d.getString("summaryChannelId") : d.getString("reviewChannelId"),
                        d.getDate("summaryLeaseUntil") != null && d.getDate("summaryLeaseUntil").toInstant().isAfter(now)
                                ? "in flight" : d.getDate("summaryNextAttemptAt") != null
                                && d.getDate("summaryNextAttemptAt").toInstant().isAfter(now) ? "retry scheduled" : "awaiting recovery"))
                .into(new ArrayList<>());
        var noticeSources = queue(deliveries, eq("noticePending", true), "$finishedAt", "leaseUntil", now);
        var noticeLeases = queue(notices, eq("noticeState", "PENDING"), "$noticeCreatedAt", "noticeLeaseUntil", now);
        return new Snapshot(queue(deliveries, in("state", "PENDING", "SENDING", "BLOCKED"), "$createdAt", "leaseUntil", now),
                queue(offers, and(eq("state", "PENDING"), eq("reviewMessageId", null)), "$discoveredAt", "reviewPostUntil", now),
                queue(offers, pendingSummary, new Document("$ifNull", List.of("$updatedAt", "$discoveredAt")), "summaryLeaseUntil", now),
                new Queue(noticeSources.total(), noticeSources.oldest(), noticeLeases.leased(), noticeLeases.expired()),
                deliveries.countDocuments(recentFailure), List.copyOf(failures), List.copyOf(summaries));
    }

    private static Queue queue(MongoCollection<Document> collection, Bson filter, Object date, String lease, Instant now) {
        var result = collection.aggregate(List.of(Aggregates.match(filter), new Document("$group", new Document("_id", null)
                .append("total", new Document("$sum", 1)).append("oldest", new Document("$min", date))
                .append("leased", new Document("$sum", new Document("$cond", List.of(
                        new Document("$gt", List.of("$" + lease, Date.from(now))), 1, 0))))
                .append("expired", new Document("$sum", new Document("$cond", List.of(new Document("$and", List.of(
                        new Document("$ne", Arrays.asList(new Document("$ifNull", Arrays.asList("$" + lease, null)), null)),
                        new Document("$lte", List.of("$" + lease, Date.from(now))))), 1, 0))))))).first();
        return result == null ? new Queue(0, null, 0, 0) : new Queue(number(result, "total"),
                result.getDate("oldest") == null ? null : result.getDate("oldest").toInstant(), number(result, "leased"), number(result, "expired"));
    }
    private static long number(Document document, String key) { return ((Number) document.get(key)).longValue(); }

    static String render(Snapshot snapshot, JDA client, FreebieConfig config, Instant now) {
        StringBuilder text = new StringBuilder("\n\n**Delivery health**\n");
        append(text, "Alerts queued", snapshot.deliveries(), now);
        append(text, "Reviews to post", snapshot.reviews(), now);
        append(text, "Summaries pending", snapshot.summaries(), now);
        append(text, "Owner notices pending", snapshot.notices(), now);
        text.append("Failed alerts in the last 24h: **").append(snapshot.failedDay()).append("**\n");
        text.append("\n**Review channel checks**\n");
        for (String channel : new LinkedHashSet<>(List.of(config.reviewChannelId(), config.otherReviewChannelId())))
            text.append("<#").append(channel).append("> — ").append(channelHealth(client, channel)).append('\n');
        if (!snapshot.summariesWaiting().isEmpty()) {
            text.append("\n**Oldest pending summaries** (up to 5)\n");
            for (var summary : snapshot.summariesWaiting()) {
                String channel = summary.channelId() == null ? config.reviewChannelId() : summary.channelId();
                text.append('`').append(FreebieMessages.text(summary.offerId(), 60)).append("` — ").append(summary.state())
                        .append("; <#").append(channel).append(">: ").append(channelHealth(client, channel)).append('\n');
            }
        }
        if (!snapshot.failures().isEmpty()) {
            text.append("\n**Recent failed destinations** (up to 5)\n");
            for (var failure : snapshot.failures()) text.append("<#").append(failure.channelId()).append("> (server `")
                    .append(failure.guildId()).append("`) — ").append(reason(failure.reason())).append('\n');
        }
        text.append("\nSnapshot only; channel checks use the bot's current cache. No messages were sent.");
        return text.toString();
    }

    private static void append(StringBuilder text, String label, Queue queue, Instant now) {
        text.append(label).append(": **").append(queue.total()).append("**");
        if (queue.oldest() != null) text.append(" • oldest ").append(Math.max(0, Duration.between(queue.oldest(), now).toMinutes())).append("m");
        if (queue.leased() != 0) text.append(" • in flight ").append(queue.leased());
        if (queue.expired() != 0) text.append(" • expired leases ").append(queue.expired());
        text.append('\n');
    }

    static String channelHealth(JDA client, String id) {
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return "Discord disconnected; check again after reconnect";
        var channel = client.getChannelById(GuildMessageChannel.class, id);
        if (channel == null) return "not visible; check channel ID and bot access";
        var self = channel.getGuild().getSelfMember();
        List<String> missing = new ArrayList<>();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL)) missing.add("View Channel");
        if (!channel.canTalk()) missing.add("Send Messages");
        if (!self.hasPermission(channel, Permission.MESSAGE_EMBED_LINKS)) missing.add("Embed Links");
        if (!self.hasPermission(channel, Permission.MESSAGE_HISTORY)) missing.add("Read Message History");
        return missing.isEmpty() ? "ready" : "restore " + String.join(", ", missing);
    }

    static String reason(String code) {
        if (code == null) return "unknown failure; inspect logs";
        return switch (code) {
            case "MISSING_PERMISSIONS" -> "restore View Channel, Send Messages and Embed Links";
            case "CHANNEL_MISSING" -> "check the saved channel and bot access";
            case "BOT_REMOVED" -> "bot is no longer in the server";
            case "TRANSIENT" -> "Discord/network retries exhausted";
            case "REJECTED" -> "Discord rejected the alert; inspect logs";
            default -> "unknown failure; inspect logs";
        };
    }
}
