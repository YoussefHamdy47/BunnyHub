package org.bunnys.bunnynexus.deals;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;

/**
 * The deals area's MongoDB state (docs/deals/03-storage.md): the last snapshot ({@code DealState}) and each
 * server's deals board ({@code DealBoards}). Blocking; called from the deals
 * thread, and from the setup form.
 */
public final class DealRepository implements DealRefresh.Store, DealBoard.Store {
    static final String STATE = "DealState", BOARDS = "DealBoards";
    /** A saved deal's link must still point at the store itself. */
    private static final Set<String> STORE_HOSTS = Set.of("store.steampowered.com", "www.gog.com", "www.xbox.com",
            "store.playstation.com", "www.nintendo.com");
    static final int FAILURES_BEFORE_OFF = 3;
    
    private final MongoCollection<Document> state, boards;
    private final MongoDatabase db;

    public DealRepository(MongoDatabase db) {
        this.db = db; state = db.getCollection(STATE); boards = db.getCollection(BOARDS);
    }

    /** Safe on every start. */
    public void installIndexes() {
        boards.createIndex(Indexes.ascending("on"), new IndexOptions().name("deal_boards_on"));
    }

    // ---- Snapshot -------------------------------------------------------------------------------------------------

    /** Each listed game's lowest price seen ({@link DealPrices}). */
    DealPrices.Store prices() { return new DealPrices.Mongo(db); }

    public Optional<DealSnapshot> loadSnapshot() {
        Document doc = state.find(eq("_id", "snapshot")).first();
        if (doc == null || !(doc.get("fetchedAt") instanceof Date fetched)) return Optional.empty();
        return Optional.of(new DealSnapshot(deals(doc), fetched.toInstant(), doc.getInteger("rowsSeen", 0)));
    }

    @Override public void saveSnapshot(DealSnapshot snapshot) {
        state.replaceOne(eq("_id", "snapshot"), new Document("_id", "snapshot").append("fetchedAt", Date.from(snapshot.fetchedAt()))
                .append("rowsSeen", snapshot.rowsSeen()).append("deals", entries(snapshot.deals())), new ReplaceOptions().upsert(true));
    }

    // ---- Boards ---------------------------------------------------------------------------------------------------

    /**
     * One server's board. {@code shownGames}: the top games when the board was last (re)posted, so a game that
     * entered the top since counts as new. {@code contentKey}: what the message shows now, to skip edits that change
     * nothing.
     */
    public record Board(String guildId, String channelId, boolean on, Optional<String> messageId, List<String> shownGames,
                        Optional<String> contentKey, Optional<Instant> postedAt, int failures, Optional<String> offReason,
                        Ping ping, Optional<Instant> lastPingAt, Style style) {
        public Board { Objects.requireNonNull(style); }
        /** A board in the original style: one message kept up to date. */
        public Board(String guildId, String channelId, boolean on, Optional<String> messageId, List<String> shownGames,
                     Optional<String> contentKey, Optional<Instant> postedAt, int failures, Optional<String> offReason,
                     Ping ping, Optional<Instant> lastPingAt) {
            this(guildId, channelId, on, messageId, shownGames, contentKey, postedAt, failures, offReason, ping, lastPingAt, Style.BOARD);
        }
    }

    /**
     * How deals appear in the channel. {@code BOARD} (the original, and the default): one message with the best deals,
     * edited as prices change and reposted when new deals arrive. {@code POSTS}: new deals each get a message of their
     * own, like a feed, never more often than {@link DealBoard#POST_GAP}; nothing is edited.
     */
    public enum Style {
        BOARD("board", "One board, kept up to date"), POSTS("posts", "A new post for new deals");
        public final String id, label;
        Style(String id, String label) { this.id = id; this.label = label; }
        public static Style byId(String id) {
            for (Style s : values()) if (s.id.equals(id)) return s;
            return BOARD;
        }
    }

    /**
     * Who the board pings when new deals arrive: an optional role, only for new deals on {@code stores} (all when
     * empty), at most once every {@code everyHours}.
     */
    public record Ping(Optional<String> roleId, Set<DealStore> stores, int everyHours) {
        public static final List<Integer> CHOICES = List.of(6, 12, 24);
        public static final int DEFAULT_HOURS = 24;
        public static final Ping NONE = new Ping(Optional.empty(), Set.of(), DEFAULT_HOURS);
        public Ping {
            stores = stores.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(stores));
            if (!CHOICES.contains(everyHours)) everyHours = DEFAULT_HOURS;
        }
        /** True when a new deal on {@code store} may ping. */
        public boolean covers(DealStore store) { return stores.isEmpty() || stores.contains(store); }
    }

    @Override public Optional<Board> board(String guildId) {
        return Optional.ofNullable(boards.find(eq("_id", guildId)).first()).map(DealRepository::board);
    }

    /** The servers whose board is on: IDs only, read through the {@code on} index. */
    public Set<String> guildsWithBoardOn() {
        Set<String> ids = new HashSet<>();
        for (Document doc : boards.find(eq("on", true)).projection(new Document("_id", 1))) ids.add(doc.getString("_id"));
        return ids;
    }

    /** One page of boards that are on, after {@code afterId} in server ID order. */
    @Override public List<Board> boardsOn(String afterId, int limit) {
        List<Board> on = new ArrayList<>(Math.min(limit, 100));
        for (Document doc : boards.find(Filters.and(eq("on", true), Filters.gt("_id", afterId))).sort(Sorts.ascending("_id")).limit(limit))
            on.add(board(doc));
        return on;
    }

    /** Turns the board on in {@code channelId}. A new channel starts a new message; returns the board as it was. */
    public Optional<Board> setBoard(String guildId, String channelId, Ping ping, String userId, Instant now) {
        return setBoard(guildId, channelId, ping, Style.BOARD, userId, now);
    }

    /** As above, in {@code style}; a new style starts fresh too (the caller removes an old board message). */
    public Optional<Board> setBoard(String guildId, String channelId, Ping ping, Style style, String userId, Instant now) {
        var before = board(guildId);
        boolean sameChannel = before.map(b -> b.channelId().equals(channelId) && b.style() == style).orElse(false);
        List<Bson> updates = new ArrayList<>(List.of(Updates.set("channelId", channelId), Updates.set("on", true),
                Updates.set("setBy", userId), Updates.set("setAt", Date.from(now)), Updates.set("failures", 0),
                Updates.set("offReason", null), Updates.set("pingRole", ping.roleId().orElse(null)),
                Updates.set("pingStores", ping.stores().stream().map(store -> store.choice).toList()),
                Updates.set("pingEveryHours", ping.everyHours()), Updates.set("style", style.id)));
        // A new channel starts over. A new style in the same channel keeps the games already shown there, so switching
        // to posts does not announce them again as new.
        boolean sameChannelOnly = before.map(b -> b.channelId().equals(channelId)).orElse(false);
        if (!sameChannel) updates.addAll(List.of(Updates.set("messageId", null), Updates.set("contentKey", null), Updates.set("postedAt", null)));
        if (!sameChannelOnly) updates.add(Updates.set("shownGames", List.of()));
        boards.updateOne(eq("_id", guildId), Updates.combine(updates), new UpdateOptions().upsert(true));
        return before;
    }

    /** Turns the board off; the message is forgotten (the caller deletes it). Returns the board as it was. */
    public Optional<Board> turnOff(String guildId, String userId, Instant now) {
        var before = board(guildId);
        boards.updateOne(eq("_id", guildId), Updates.combine(Updates.set("on", false), Updates.set("offReason", "turned off"),
                Updates.set("setBy", userId), Updates.set("setAt", Date.from(now)), Updates.set("messageId", null),
                Updates.set("shownGames", List.of()), Updates.set("contentKey", null)));
        return before;
    }

    /** A new message was posted (first post, or a repost for new games). Conditional on the board still being on there. */
    @Override public void posted(String guildId, String channelId, String messageId, List<String> shownGames, String contentKey,
                                 Instant now, boolean pinged) {
        List<Bson> updates = new ArrayList<>(List.of(Updates.set("messageId", messageId), Updates.set("shownGames", shownGames),
                Updates.set("contentKey", contentKey), Updates.set("postedAt", Date.from(now)), Updates.set("failures", 0)));
        if (pinged) updates.add(Updates.set("lastPingAt", Date.from(now)));
        boards.updateOne(Filters.and(eq("_id", guildId), eq("channelId", channelId), eq("on", true)), Updates.combine(updates));
    }

    /** The message was edited to show {@code contentKey}. */
    @Override public void edited(String guildId, String messageId, String contentKey) {
        boards.updateOne(Filters.and(eq("_id", guildId), eq("messageId", messageId)),
                Updates.combine(Updates.set("contentKey", contentKey), Updates.set("failures", 0)));
    }

    /**
     * A day the board could not be updated. Counted once per day; the third failed day turns it off with
     * {@code reason}. Returns the failure count afterwards.
     */
    @Override public int markFailed(String guildId, String day, String reason) {
        boards.updateOne(Filters.and(eq("_id", guildId), Filters.ne("lastFailedDay", day)),
                Updates.combine(Updates.inc("failures", 1), Updates.set("lastFailedDay", day)));
        boards.updateOne(Filters.and(eq("_id", guildId), Filters.gte("failures", FAILURES_BEFORE_OFF), eq("on", true)),
                Updates.combine(Updates.set("on", false), Updates.set("offReason", reason), Updates.set("messageId", null)));
        return board(guildId).map(Board::failures).orElse(0);
    }

    /** The bot left the server. */
    public void forget(String guildId) { boards.deleteOne(eq("_id", guildId)); }

    private static Board board(Document doc) {
        Date posted = doc.getDate("postedAt"), pinged = doc.getDate("lastPingAt");
        Set<DealStore> stores = EnumSet.noneOf(DealStore.class);
        for (String choice : doc.getList("pingStores", String.class, List.of())) DealStore.byChoice(choice).ifPresent(stores::add);
        var ping = new Ping(Optional.ofNullable(doc.getString("pingRole")), stores, doc.getInteger("pingEveryHours", Ping.DEFAULT_HOURS));
        return new Board(doc.getString("_id"), doc.getString("channelId"), Boolean.TRUE.equals(doc.getBoolean("on")),
                Optional.ofNullable(doc.getString("messageId")), List.copyOf(doc.getList("shownGames", String.class, List.of())),
                Optional.ofNullable(doc.getString("contentKey")), Optional.ofNullable(posted).map(Date::toInstant),
                doc.getInteger("failures", 0), Optional.ofNullable(doc.getString("offReason")), ping, Optional.ofNullable(pinged).map(Date::toInstant),
                Style.byId(Objects.requireNonNullElse(doc.getString("style"), Style.BOARD.id)));
    }

    // ---- Entries ------------------------------------------------------------------------------------------------

    private static List<Document> entries(List<Deal> deals) {
        List<Document> entries = new ArrayList<>(deals.size());
        for (Deal d : deals) {
            entries.add(new Document("id", d.id()).append("store", d.store().id).append("title", d.title()).append("link", d.link().toString())
                    .append("sale", d.saleCents()).append("normal", d.normalCents()).append("off", d.percentOff())
                    .append("rating", d.rating()).append("reviews", d.reviews()).append("endsAt", d.endsAt().map(Date::from).orElse(null)));
        }
        return entries;
    }

    /** Entries that no longer read (unknown store, missing field, a link that is not the store's own) are dropped. */
    private static List<Deal> deals(Document doc) {
        List<Deal> deals = new ArrayList<>();
        for (Document e : doc.getList("deals", Document.class, List.of())) {
            try {
                var store = DealStore.byId(e.getInteger("store"));
                var link = java.net.URI.create(e.getString("link"));
                if (store.isEmpty() || !STORE_HOSTS.contains(link.getHost()) || !"https".equals(link.getScheme())) continue;
                deals.add(new Deal(e.getString("id"), store.get(), e.getString("title"), link, ((Number) e.get("sale")).longValue(),
                        ((Number) e.get("normal")).longValue(), e.getInteger("off"), e.getInteger("rating"), e.getInteger("reviews"),
                        Optional.ofNullable(e.getDate("endsAt")).map(Date::toInstant)));
            } catch (RuntimeException unreadable) { /* skip this entry */ }
        }
        return List.copyOf(deals);
    }
}
