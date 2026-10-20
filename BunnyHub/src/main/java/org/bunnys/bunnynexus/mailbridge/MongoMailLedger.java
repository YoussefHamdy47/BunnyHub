package org.bunnys.bunnynexus.mailbridge;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.*;

/**
 * {@link MailLedger} in MongoDB. Finished entries expire after {@link #KEEP_FINISHED}, which is far longer than the
 * search window ({@link MailBridge#LOOKBACK}), so an expired entry's message can never be found again.
 */
public final class MongoMailLedger implements MailLedger {
    static final String COLLECTION = "MailBridgeMessages";
    static final String STATE = "MailBridgeState";
    static final Duration KEEP_FINISHED = Duration.ofDays(90);

    private final MongoCollection<Document> entries;
    private final MongoCollection<Document> state;

    public MongoMailLedger(MongoDatabase db) {
        entries = db.getCollection(COLLECTION);
        state = db.getCollection(STATE);
    }

    public void installIndexes() {
        entries.createIndex(Indexes.ascending("status", "nextAttempt"));
        entries.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override public Instant cutoff(Instant firstStart) {
        Document doc = state.findOneAndUpdate(eq("_id", "cutoff"),
                Updates.setOnInsert("at", Date.from(firstStart)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return doc.getDate("at").toInstant();
    }

    @Override public Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.find(eq("_id", id)).first()).map(MongoMailLedger::read);
    }

    @Override public void save(Entry entry) {
        boolean finished = switch (entry.status()) {
            case SENT, IGNORED, UNCLASSIFIED, FAILED -> true;
            case PENDING, SENDING -> false;
        };
        Document doc = new Document("_id", entry.id()).append("status", entry.status().name())
                .append("category", entry.category().name()).append("received", Date.from(entry.received()))
                .append("attempts", entry.attempts()).append("nextAttempt", Date.from(entry.nextAttempt()))
                .append("discordMessageId", entry.discordMessageId().orElse(null)).append("note", entry.note())
                .append("updated", Date.from(entry.updated()))
                .append("expiresAt", finished ? Date.from(entry.updated().plus(KEEP_FINISHED)) : null);
        entries.replaceOne(eq("_id", entry.id()), doc, new ReplaceOptions().upsert(true));
    }

    @Override public List<Entry> due(Instant now, int limit) {
        List<Entry> due = new ArrayList<>();
        entries.find(and(in("status", Status.PENDING.name(), Status.SENDING.name()), lte("nextAttempt", Date.from(now))))
                .sort(Sorts.ascending("received")).limit(limit).forEach(doc -> due.add(read(doc)));
        return due;
    }

    @Override public Map<Status, Long> counts() {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status status : Status.values()) counts.put(status, entries.countDocuments(eq("status", status.name())));
        return counts;
    }

    private static Entry read(Document doc) {
        return new Entry(doc.getString("_id"), Status.valueOf(doc.getString("status")),
                MailCategory.valueOf(doc.getString("category")), doc.getDate("received").toInstant(),
                doc.getInteger("attempts", 0), doc.getDate("nextAttempt").toInstant(),
                Optional.ofNullable(doc.getString("discordMessageId")), Objects.requireNonNullElse(doc.getString("note"), ""),
                doc.getDate("updated").toInstant());
    }
}
