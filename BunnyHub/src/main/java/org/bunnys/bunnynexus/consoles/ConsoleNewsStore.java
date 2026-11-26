package org.bunnys.bunnynexus.consoles;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bunnys.bunnynexus.freebies.FreebieStore;

import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * The console news state in MongoDB: {@code ConsoleNewsState} (one document: what was seen) and {@code ConsoleNewsPosts}
 * (each post, the channels it reached and failed tries per channel). Blocking.
 */
final class ConsoleNewsStore implements ConsoleNews.Store {
    static final String STATE = "ConsoleNewsState", POSTS = "ConsoleNewsPosts";
    private final MongoCollection<Document> state, posts;

    ConsoleNewsStore(MongoDatabase db) { state = db.getCollection(STATE); posts = db.getCollection(POSTS); }

    void installIndexes() { posts.createIndex(Indexes.ascending("open"), new IndexOptions().name("console_posts_open")); }

    @Override public ConsoleNews.State state() {
        Document d = state.find(eq("_id", "state")).first();
        if (d == null) return ConsoleNews.State.EMPTY;
        Map<GamePass.Shelf, Map<String, Instant>> seen = new EnumMap<>(GamePass.Shelf.class);
        Map<GamePass.Shelf, List<String>> pending = new EnumMap<>(GamePass.Shelf.class);
        var seenDoc = d.get("seen", Document.class);
        var pendingDoc = d.get("pending", Document.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            seen.put(shelf, sightings(seenDoc == null ? null : seenDoc.get(shelf.id, Document.class)));
            pending.put(shelf, pendingDoc == null ? List.of() : List.copyOf(pendingDoc.getList(shelf.id, String.class, List.of())));
        }
        return new ConsoleNews.State(strings(d, "psSeen"), seen, pending, instant(d, "lastGamePassPost"), d.getBoolean("seeded", false),
                d.getBoolean("migrated", false), strings(d, "weekendsSeen"), sightings(d.get("itemsSeen", Document.class)),
                strings(d, "itemsPending"), instant(d, "lastItemsPost"), d.getBoolean("itemsSeeded", false));
    }

    @Override public void save(ConsoleNews.State s) {
        var seen = new Document();
        var pending = new Document();
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            seen.append(shelf.id, sightings(s.seen().getOrDefault(shelf, Map.of())));
            pending.append(shelf.id, s.pending().getOrDefault(shelf, List.of()));
        }
        state.replaceOne(eq("_id", "state"), new Document("_id", "state").append("psSeen", s.psSeen()).append("seen", seen)
                .append("pending", pending).append("lastGamePassPost", s.lastGamePassPost().map(Date::from).orElse(null))
                .append("seeded", s.seeded()).append("migrated", s.migrated()).append("weekendsSeen", s.weekendsSeen())
                .append("itemsSeen", sightings(s.itemsSeen())).append("itemsPending", s.itemsPending())
                .append("lastItemsPost", s.lastItemsPost().map(Date::from).orElse(null)).append("itemsSeeded", s.itemsSeeded()),
                new ReplaceOptions().upsert(true));
    }

    @Override public void create(ConsoleNews.Post post) {
        var shelves = new Document();
        post.gamePass().forEach((shelf, games) -> shelves.append(shelf.id, games.stream().map(GamePass.Game::toDocument).toList()));
        var doc = new Document("store", post.store().id()).append("createdAt", Date.from(post.createdAt())).append("open", true)
                .append("psPlus", post.psPlus().map(PsPlusPost::toDocument).orElse(null)).append("gamePass", shelves)
                .append("weekend", post.weekend().map(FreeWeekend::toDocument).orElse(null))
                .append("items", post.items().stream().map(ConsoleItem::toDocument).toList())
                .append("delivered", List.of()).append("failures", new Document());
        posts.updateOne(eq("_id", post.id()), new Document("$setOnInsert", doc), new UpdateOptions().upsert(true));
    }

    @Override public List<ConsoleNews.Post> open() {
        List<ConsoleNews.Post> open = new ArrayList<>();
        for (Document d : posts.find(eq("open", true)).sort(new Document("createdAt", 1)).limit(50)) {
            var store = FreebieStore.byId(d.getString("store")).filter(FreebieStore::news);
            if (store.isEmpty() || d.getDate("createdAt") == null) continue;
            Optional<PsPlusPost> ps = Optional.ofNullable(d.get("psPlus", Document.class)).flatMap(PsPlusPost::read);
            Optional<FreeWeekend> weekend = Optional.ofNullable(d.get("weekend", Document.class)).flatMap(FreeWeekend::read);
            List<ConsoleItem> items = d.getList("items", Document.class, List.of()).stream().map(ConsoleItem::fromSaved).flatMap(Optional::stream).toList();
            Map<GamePass.Shelf, List<GamePass.Game>> shelves = new EnumMap<>(GamePass.Shelf.class);
            var gp = d.get("gamePass", Document.class);
            if (gp != null) for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
                var games = gp.getList(shelf.id, Document.class, List.of()).stream().map(GamePass.Game::read).flatMap(Optional::stream).toList();
                if (!games.isEmpty()) shelves.put(shelf, games);
            }
            // A post whose content no longer reads is skipped rather than sent empty.
            if (ps.isEmpty() && weekend.isEmpty() && items.isEmpty() && shelves.isEmpty()) continue;
            Map<String, Integer> failures = new HashMap<>();
            var f = d.get("failures", Document.class);
            if (f != null) f.forEach((channel, n) -> { if (n instanceof Number count) failures.put(channel, count.intValue()); });
            open.add(new ConsoleNews.Post(d.getString("_id"), store.get(), d.getDate("createdAt").toInstant(), ps, shelves, weekend, items,
                    Set.copyOf(d.getList("delivered", String.class, List.of())), failures));
        }
        return open;
    }

    @Override public boolean claim(String postId, String channelId) {
        return posts.updateOne(and(eq("_id", postId), ne("delivered", channelId)), addToSet("delivered", channelId)).getModifiedCount() == 1;
    }

    @Override public void failed(String postId, String channelId) {
        posts.updateOne(eq("_id", postId), combine(pull("delivered", channelId), inc("failures." + channelId, 1)));
    }

    @Override public void close(String postId) { posts.updateOne(eq("_id", postId), set("open", false)); }

    private static List<String> strings(Document d, String field) { return List.copyOf(d.getList(field, String.class, List.of())); }

    private static Optional<Instant> instant(Document d, String field) { return Optional.ofNullable(d.getDate(field)).map(Date::toInstant); }

    private static Map<String, Instant> sightings(Document d) {
        Map<String, Instant> at = new HashMap<>();
        if (d != null) d.forEach((id, when) -> { if (when instanceof Date date) at.put(id, date.toInstant()); });
        return at;
    }

    private static Document sightings(Map<String, Instant> at) {
        var d = new Document();
        at.forEach((id, when) -> d.append(id, Date.from(when)));
        return d;
    }
}
