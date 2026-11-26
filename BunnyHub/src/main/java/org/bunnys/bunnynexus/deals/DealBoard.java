package org.bunnys.bunnynexus.deals;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.utils.BunnyLog;

import java.time.*;
import java.util.*;

/**
 * Each server's deals board (docs/deals/05): one message, one block per launcher. After a refresh:
 * <ul>
 *   <li>a game the board never showed before is on it → the old message is deleted and a new one posted (the
 *       channel lights up), naming the new games and pinging the board's role if it covers one of their launchers
 *       and its cooldown has passed;</li>
 *   <li>otherwise, if what it shows changed → the message is edited silently;</li>
 *   <li>otherwise nothing.</li>
 * </ul>
 * The first post never pings. Runs on the deals thread.
 */
final class DealBoard {
    /** Game IDs remembered as already shown, so a game bouncing in and out of a launcher's top 3 never reposts. */
    static final int REMEMBERED = 200;
    /** Boards read from the database at a time, so memory stays flat however many servers have one. */
    static final int PAGE = 100;

    /**
     * What every board shows for one snapshot, built once per refresh instead of once per server: the deals on it,
     * its content key and the silent edit. A post is still built per board (its notice names that board's new games).
     */
    record View(DealSnapshot snapshot, List<Deal> shown, String key, Map<Optional<String>, MessageEditData> edits) {
        static View of(DealSnapshot snapshot) {
            List<Deal> shown = DealCards.boardDeals(snapshot).values().stream().flatMap(List::stream).toList();
            return new View(snapshot, shown, contentKey(shown, snapshot), new java.util.concurrent.ConcurrentHashMap<>());
        }

        /** The silent edit for boards offering this ping role (or none): built once per role per refresh. */
        MessageEditData edit(Optional<String> role) { return edits.computeIfAbsent(role, r -> DealCards.boardEdit(snapshot, r)); }

        /** The content key of a board offering this role: a role added, changed or removed edits the board. */
        String key(Optional<String> role) { return key + role.map(r -> "|role " + r).orElse(""); }
    }

    /** What a board update reads and writes; {@link DealRepository} in production. */
    interface Store {
        Optional<DealRepository.Board> board(String guildId);
        /** Boards that are on with a server ID after {@code afterId} ("" for the start), in ID order, at most {@code limit}. */
        List<DealRepository.Board> boardsOn(String afterId, int limit);
        void posted(String guildId, String channelId, String messageId, List<String> shownGames, String contentKey, Instant now, boolean pinged);
        void edited(String guildId, String messageId, String contentKey);
        int markFailed(String guildId, String day, String reason);
    }

    /** Discord. A failure text is what admins see in setup. */
    interface Discord {
        boolean ready();
        Result post(String guildId, String channelId, MessageCreateData message);
        Result edit(String guildId, String channelId, String messageId, MessageEditData message);
        /** Best effort; a message that is already gone is fine. */
        void delete(String guildId, String channelId, String messageId);
    }

    /** {@code messageId} after a post; {@code gone} when the message to edit no longer exists. */
    record Result(Optional<String> messageId, boolean gone, Optional<String> failure) {
        static Result ok(String messageId) { return new Result(Optional.of(messageId), false, Optional.empty()); }
        static Result edited() { return new Result(Optional.empty(), false, Optional.empty()); }
        static Result missing() { return new Result(Optional.empty(), true, Optional.empty()); }
        static Result failed(String reason) { return new Result(Optional.empty(), false, Optional.of(reason)); }
    }

    enum Action { NOTHING, POSTED, REPOSTED, PINGED, EDITED, FAILED }

    private final Store store;
    private final Discord discord;
    private final Clock clock;
    private final Duration spacing;

    DealBoard(Store store, Discord discord, Clock clock, Duration spacing) {
        this.store = store; this.discord = discord; this.clock = clock; this.spacing = spacing;
    }

    /** Every board, after a refresh. Skipped while Discord is not connected or the deals are stale. */
    void updateAll(DealSnapshot snapshot) {
        if (!discord.ready() || snapshot.stale(clock.instant())) return;
        Map<Action, Integer> counts = new EnumMap<>(Action.class);
        View view = View.of(snapshot);
        String after = "";
        for (List<DealRepository.Board> page; !(page = store.boardsOn(after, PAGE)).isEmpty(); after = page.getLast().guildId()) {
            for (var board : page) {
                Action action = update(board, view);
                counts.merge(action, 1, Integer::sum);
                if (action != Action.NOTHING && !pause()) return;
            }
            if (page.size() < PAGE) break;
        }
        counts.remove(Action.NOTHING);
        if (!counts.isEmpty()) BunnyLog.info("[Deals] Boards updated: " + counts);
    }

    /** One server's board, right after setup changed it. */
    Action updateOne(String guildId, DealSnapshot snapshot) {
        if (!discord.ready() || snapshot.stale(clock.instant())) return Action.NOTHING;
        return store.board(guildId).filter(DealRepository.Board::on).map(board -> update(board, snapshot)).orElse(Action.NOTHING);
    }

    Action update(DealRepository.Board board, DealSnapshot snapshot) { return update(board, View.of(snapshot)); }

    Action update(DealRepository.Board board, View view) {
        Instant now = clock.instant();
        DealSnapshot snapshot = view.snapshot();
        List<Deal> shown = view.shown();
        Optional<String> role = board.ping().roleId();
        String key = view.key(role);
        if (board.messageId().isEmpty()) return post(board, snapshot, DealCards.Notice.NONE, shown, key, now, false);
        List<Deal> fresh = shown.stream().filter(d -> !board.shownGames().contains(d.id())).toList();
        if (!fresh.isEmpty()) {
            boolean ping = mayPing(board, fresh, now);
            discord.delete(board.guildId(), board.channelId(), board.messageId().get());
            var notice = new DealCards.Notice(ping ? board.ping().roleId() : Optional.empty(), fresh);
            return post(board, snapshot, notice, shown, key, now, ping);
        }
        if (board.contentKey().filter(key::equals).isPresent()) return Action.NOTHING;
        var result = discord.edit(board.guildId(), board.channelId(), board.messageId().get(), view.edit(role));
        if (result.gone()) return post(board, snapshot, DealCards.Notice.NONE, shown, key, now, false);
        if (result.failure().isPresent()) return failed(board, result.failure().get(), now);
        store.edited(board.guildId(), board.messageId().get(), key);
        return Action.EDITED;
    }

    /** A role is set, it covers a launcher of one of the new deals, and its cooldown has passed. */
    static boolean mayPing(DealRepository.Board board, List<Deal> fresh, Instant now) {
        var ping = board.ping();
        if (ping.roleId().isEmpty() || fresh.stream().noneMatch(d -> ping.covers(d.store()))) return false;
        return board.lastPingAt().map(at -> !now.isBefore(at.plus(Duration.ofHours(ping.everyHours())))).orElse(true);
    }

    private Action post(DealRepository.Board board, DealSnapshot snapshot, DealCards.Notice notice, List<Deal> shown,
                        String key, Instant now, boolean pinged) {
        var result = discord.post(board.guildId(), board.channelId(), DealCards.board(snapshot, notice, board.ping().roleId()));
        if (result.messageId().isEmpty()) return failed(board, result.failure().orElse("Discord refused the post"), now);
        store.posted(board.guildId(), board.channelId(), result.messageId().get(), remember(board.shownGames(), shown), key, now, pinged);
        return pinged ? Action.PINGED : board.messageId().isEmpty() ? Action.POSTED : Action.REPOSTED;
    }

    /** What is on the board now first, then what was shown before, up to {@link #REMEMBERED}. */
    static List<String> remember(List<String> before, List<Deal> shown) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        shown.forEach(d -> ids.add(d.id()));
        ids.addAll(before);
        return ids.stream().limit(REMEMBERED).toList();
    }

    private Action failed(DealRepository.Board board, String reason, Instant now) {
        store.markFailed(board.guildId(), LocalDate.ofInstant(now, ZoneOffset.UTC).toString(), reason);
        return Action.FAILED;
    }

    /** What the board shows (deals, prices, labels, counts) and the refresh day, so an unchanged board is not edited. */
    static String contentKey(List<Deal> shown, DealSnapshot snapshot) {
        var text = new StringBuilder();
        shown.forEach(d -> text.append(d.id()).append(':').append(d.saleCents()).append(':').append(d.normalCents()).append(':')
                .append(d.endsAt().map(Instant::getEpochSecond).orElse(0L)).append(':').append(DealCards.rating(d)).append(':').append(snapshot.lowest(d)).append(';'));
        for (DealStore store : DealStore.values()) text.append(snapshot.forStore(Optional.of(store)).size()).append(',');
        // The day keeps the board's "updated" time at most a day old even when the deals stay the same.
        return Integer.toHexString(text.toString().hashCode()) + ":" + LocalDate.ofInstant(snapshot.fetchedAt(), ZoneOffset.UTC);
    }

    private boolean pause() {
        if (spacing.isZero()) return true;
        try { Thread.sleep(spacing); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
}
