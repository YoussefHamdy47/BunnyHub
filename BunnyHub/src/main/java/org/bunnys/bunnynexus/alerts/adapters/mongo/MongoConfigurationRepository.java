package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoConfigurationSchema.*;

/** Inactive transactional administrator adapter. All writers must use this aggregate/guard protocol. */
public final class MongoConfigurationRepository implements ConfigurationRepository {
    private final MongoAlertDatabase db;
    private final MongoConfigurationProjections projections;
    public MongoConfigurationRepository(MongoAlertDatabase db) {
        this.db = Objects.requireNonNull(db); this.projections = new MongoConfigurationProjections(db);
    }
    @Override public GuildConfiguration load(String guildId) {
        AlertIdentity.snowflake(guildId);
        var document = db.collection(CONFIGURATIONS).find(eq("_id", guildId)).collation(SIMPLE).first();
        return decode(guildId, document);
    }
    @Override public Optional<Receipt> receipt(String guildId, String actionId) {
        AlertIdentity.snowflake(guildId); AlertIdentity.snowflake(actionId);
        return Optional.ofNullable(db.collection(AUDIT).find(and(eq("_id", actionId), eq("guildId", guildId)))
                .collation(SIMPLE).first()).map(ConfigurationDocuments::receipt);
    }
    @Override public Result commit(ConfigurationChange change, ConfigurationAccess.Proof proof, ConfigurationPolicy policy) {
        Objects.requireNonNull(change); Objects.requireNonNull(proof); Objects.requireNonNull(policy);
        AlertDocuments.date(proof.checkedAt()); AlertDocuments.date(proof.validUntil());
        try {
            return db.transaction(session -> commit(session, change, proof, policy));
        } catch (StaleRevision stale) {
            return new Result(Status.REVISION_CONFLICT, stale.revision);
        }
        // Driver errors, including unknown commits and duplicate IDs, are not converted into success.
    }
    private Result commit(ClientSession session, ConfigurationChange change, ConfigurationAccess.Proof proof, ConfigurationPolicy policy) {
        var previous = db.collection(AUDIT).find(session, and(eq("_id", change.actionId()), eq("guildId", change.guildId())))
                .collation(SIMPLE).first();
        if (previous != null) return Result.replay(ConfigurationDocuments.receipt(previous), change);
        // This guard also serializes configuration changes against delivery authorization. New guards are
        // created only inside this transaction; any rejection rolls the insert/touch back.
        var guard = db.collection(GUILDS).findOneAndUpdate(session, eq("_id", change.guildId()),
                new Document("$setOnInsert", new Document("schemaVersion", 1).append("configFormat", 1)
                        .append("revision", 0L).append("enabled", false))
                        .append("$currentDate", new Document("checkedAt", true)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
        AlertDocuments.version(guard);
        if (AlertDocuments.number(guard, "configFormat") != 1) throw new IllegalStateException("Unmanaged configuration guard.");
        var before = decode(change.guildId(), db.collection(CONFIGURATIONS).find(session, eq("_id", change.guildId())).collation(SIMPLE).first());
        if (AlertDocuments.number(guard, "revision") != before.revision()) throw new IllegalStateException("Configuration/guard revision mismatch.");
        if (before.revision() != change.expectedRevision()) throw new StaleRevision(before.revision());
        var now = AlertDocuments.instant(guard, "checkedAt");
        var next = policy.apply(before, change, now);
        proof.requireCurrent(ConfigurationAccess.requirements(change, next), now);
        var nextDocument = ConfigurationDocuments.configuration(next);
        projections.write(session, before, next, change, now);
        if (before.revision() == 0) db.collection(CONFIGURATIONS).insertOne(session, nextDocument);
        else if (db.collection(CONFIGURATIONS).replaceOne(session,
                and(eq("_id", change.guildId()), eq("schemaVersion", 1), eq("revision", before.revision())), nextDocument,
                new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new StaleRevision(before.revision());
        var receipt = new Receipt(change.actionId(), change.actorId(), change.guildId(), change.fingerprint(), next.revision(), now);
        db.collection(AUDIT).insertOne(session, ConfigurationDocuments.receipt(receipt, change));
        var timeFence = new Document("$expr", new Document("$and", List.of(
                new Document("$gte", List.of("$$NOW", AlertDocuments.date(now))),
                new Document("$lt", List.of("$$NOW", AlertDocuments.date(proof.validUntil()))))));
        if (db.collection(GUILDS).updateOne(session, and(eq("_id", change.guildId()), eq("schemaVersion", 1),
                eq("configFormat", 1), eq("revision", before.revision()), timeFence),
                new Document("$set", new Document("revision", next.revision()).append("enabled", next.enabled()).append("deleted", next.deleted())),
                new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new ConfigurationAccess.AccessDenied();
        return new Result(Status.APPLIED, next.revision());
    }
    private static GuildConfiguration decode(String guildId, Document document) {
        var value = document == null ? GuildConfiguration.absent(guildId) : ConfigurationDocuments.configuration(document);
        if (!value.guildId().equals(guildId)) throw new IllegalArgumentException("Foreign configuration record.");
        return value;
    }
    private static final class StaleRevision extends RuntimeException {
        private final long revision;
        private StaleRevision(long revision) { this.revision = revision; }
    }
}
