package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;

/**
 * Where each server wants free games posted: one document per guild + channel + launcher, carrying the role to
 * ping. A server may use at most {@link #MAX_CHANNELS_PER_GUILD} channels, each with any number of launchers.
 */
public final class FreebieSubscriptions {
    public static final String COLLECTION = "FreebieSubscriptions";
    public static final int MAX_CHANNELS_PER_GUILD = 5;
    /** Every launcher in every allowed channel; bounds per-server reads. */
    public static final int MAX_PER_GUILD = MAX_CHANNELS_PER_GUILD * FreebieStore.values().length;

    public record Subscription(String guildId, String channelId, FreebieStore store, Optional<String> roleId) {}
    public record Audience(int channels, int guilds) {}
    public enum SaveResult { CREATED, UPDATED, LIMIT_REACHED }
    public record ChannelChange(SaveResult result, Set<FreebieStore> added, Set<FreebieStore> removed) {}

    private final MongoCollection<Document> subscriptions;

    public FreebieSubscriptions(MongoDatabase db) { subscriptions = db.getCollection(COLLECTION); }

    public void installIndexes() {
        subscriptions.createIndex(Indexes.ascending("store", "_id"), new IndexOptions().name("subscription_store"));
        subscriptions.createIndex(Indexes.ascending("guildId"), new IndexOptions().name("subscription_guild"));
    }

    public static String id(String guildId, String channelId, FreebieStore store) {
        return guildId + ":" + channelId + ":" + store.id();
    }

    public List<Subscription> forGuild(String guildId) {
        return subscriptions.find(eq("guildId", guildId)).sort(Sorts.ascending("_id")).limit(MAX_PER_GUILD * 2)
                .map(FreebieSubscriptions::read).into(new ArrayList<>());
    }

    /** Streams every channel subscribed to {@code store}, in a stable order. */
    public Iterable<Subscription> forStore(FreebieStore store) {
        return subscriptions.find(eq("store", store.id())).sort(Sorts.ascending("_id")).map(FreebieSubscriptions::read);
    }

    /** Counted in the database: review edits run every minute while sending, and must not stream every row here. */
    public Audience audience(FreebieStore store) {
        Document totals = subscriptions.aggregate(List.of(Aggregates.match(eq("store", store.id())),
                Aggregates.group("$guildId", Accumulators.sum("channels", 1)),
                Aggregates.group(null, Accumulators.sum("channels", "$channels"), Accumulators.sum("guilds", 1)))).first();
        return totals == null ? new Audience(0, 0)
                : new Audience(((Number) totals.get("channels")).intValue(), ((Number) totals.get("guilds")).intValue());
    }

    /** Adds one launcher to a channel, or updates its role. */
    public SaveResult save(Subscription s, String actorId, Instant now) {
        boolean exists = subscriptions.find(eq("_id", id(s.guildId(), s.channelId(), s.store()))).first() != null;
        boolean newChannel = !exists && !channels(s.guildId()).contains(s.channelId());
        if (newChannel && !roomForChannel(s.guildId())) return SaveResult.LIMIT_REACHED;
        upsert(s, actorId, now);
        if (newChannel && !keepWithinLimit(s.guildId(), s.channelId())) return SaveResult.LIMIT_REACHED;
        return exists ? SaveResult.UPDATED : SaveResult.CREATED;
    }

    /**
     * Makes {@code stores} the exact launcher set of one channel, all pinging {@code roleId}: missing ones are
     * added, unticked ones removed, kept ones get the new role. An empty set is refused; use removal instead.
     */
    public ChannelChange setChannelStores(String guildId, String channelId, Set<FreebieStore> stores,
                                          Optional<String> roleId, String actorId, Instant now) {
        if (stores.isEmpty()) throw new IllegalArgumentException("Pick at least one launcher.");
        Set<FreebieStore> before = EnumSet.noneOf(FreebieStore.class);
        for (Subscription s : forGuild(guildId)) if (s.channelId().equals(channelId)) before.add(s.store());
        boolean newChannel = before.isEmpty();
        if (newChannel && !roomForChannel(guildId)) return new ChannelChange(SaveResult.LIMIT_REACHED, Set.of(), Set.of());

        Set<FreebieStore> removed = EnumSet.noneOf(FreebieStore.class);
        for (FreebieStore store : before) if (!stores.contains(store)) removed.add(store);
        if (!removed.isEmpty())
            subscriptions.deleteMany(and(eq("guildId", guildId), eq("channelId", channelId),
                    in("store", removed.stream().map(FreebieStore::id).toList())));
        for (FreebieStore store : stores) upsert(new Subscription(guildId, channelId, store, roleId), actorId, now);
        if (newChannel && !keepWithinLimit(guildId, channelId))
            return new ChannelChange(SaveResult.LIMIT_REACHED, Set.of(), Set.of());

        Set<FreebieStore> added = EnumSet.copyOf(stores);
        added.removeAll(before);
        return new ChannelChange(newChannel ? SaveResult.CREATED : SaveResult.UPDATED, added, removed);
    }

    /**
     * Dashboard save for one section: {@code stores} (only this section's launchers count) all go to one channel
     * pinging {@code roleId}; the section's launchers anywhere else, and the unticked ones, are removed. Other
     * sections are untouched. The channel limit is judged on the channels in use after the change, so moving a
     * section out of a channel it had to itself frees that slot.
     */
    public SaveResult setSection(String guildId, FreebieSection section, String channelId, Set<FreebieStore> stores,
                                 Optional<String> roleId, String actorId, Instant now) {
        List<String> wanted = section.stores().stream().filter(stores::contains).map(FreebieStore::id).toList();
        if (wanted.isEmpty()) throw new IllegalArgumentException("Pick at least one launcher; use clearSection to turn it off.");
        List<String> sectionIds = section.stores().stream().map(FreebieStore::id).toList();
        List<Subscription> current = forGuild(guildId);
        Set<String> after = new HashSet<>(Set.of(channelId));
        for (Subscription s : current) if (s.store().section() != section) after.add(s.channelId());
        if (after.size() > MAX_CHANNELS_PER_GUILD) return SaveResult.LIMIT_REACHED;
        boolean newChannel = current.stream().noneMatch(s -> s.channelId().equals(channelId));

        for (String id : wanted)
            upsert(new Subscription(guildId, channelId, FreebieStore.byId(id).orElseThrow(), roleId), actorId, now);
        subscriptions.deleteMany(and(eq("guildId", guildId), in("store", sectionIds),
                or(ne("channelId", channelId), nin("store", wanted))));
        // Same race rule as save(): if another admin filled the last slot meanwhile, the newcomer backs out.
        if (newChannel && !keepWithinLimit(guildId, channelId)) return SaveResult.LIMIT_REACHED;
        return newChannel ? SaveResult.CREATED : SaveResult.UPDATED;
    }

    /** Turns one section off in every channel of the server. */
    public long clearSection(String guildId, FreebieSection section) {
        return subscriptions.deleteMany(and(eq("guildId", guildId),
                in("store", section.stores().stream().map(FreebieStore::id).toList()))).getDeletedCount();
    }

    /** Removes one launcher from a channel, or every launcher in the channel when {@code store} is empty. */
    public long remove(String guildId, String channelId, Optional<FreebieStore> store) {
        Bson filter = and(eq("guildId", guildId), eq("channelId", channelId));
        if (store.isPresent()) filter = and(filter, eq("store", store.get().id()));
        return subscriptions.deleteMany(filter).getDeletedCount();
    }

    /** The bot was removed from the server: its alert settings are no longer deliverable. */
    public long removeGuild(String guildId) {
        return subscriptions.deleteMany(eq("guildId", guildId)).getDeletedCount();
    }

    private Set<String> channels(String guildId) {
        return subscriptions.distinct("channelId", eq("guildId", guildId), String.class).into(new HashSet<>());
    }

    private boolean roomForChannel(String guildId) {
        return channels(guildId).size() < MAX_CHANNELS_PER_GUILD;
    }

    /**
     * Two admins adding different channels at the same moment can both pass the pre-check. Whoever finds the
     * server over the limit afterwards backs its channel out again, so the limit holds without a lock.
     */
    private boolean keepWithinLimit(String guildId, String channelId) {
        if (channels(guildId).size() <= MAX_CHANNELS_PER_GUILD) return true;
        subscriptions.deleteMany(and(eq("guildId", guildId), eq("channelId", channelId)));
        return false;
    }

    private void upsert(Subscription s, String actorId, Instant now) {
        String id = id(s.guildId(), s.channelId(), s.store());
        subscriptions.replaceOne(eq("_id", id), new Document("_id", id).append("schemaVersion", 1).append("guildId", s.guildId())
                .append("channelId", s.channelId()).append("store", s.store().id()).append("roleId", s.roleId().orElse(null))
                .append("updatedBy", actorId).append("updatedAt", Date.from(now)), new ReplaceOptions().upsert(true));
    }

    private static Subscription read(Document d) {
        return new Subscription(d.getString("guildId"), d.getString("channelId"),
                FreebieStore.byId(d.getString("store")).orElse(FreebieStore.OTHER), Optional.ofNullable(d.getString("roleId")));
    }
}
