package org.bunnys.bunnynexus.deals;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DealBoardTest {
    static final String ROLE = "500000000000000001";

    /** In-memory stand-in with the same rules as DealRepository. */
    static final class Store implements DealBoard.Store {
        final Map<String, DealRepository.Board> boards = new TreeMap<>();
        @Override public Optional<DealRepository.Board> board(String guild) { return Optional.ofNullable(boards.get(guild)); }
        @Override public List<DealRepository.Board> boardsOn(String after, int limit) {
            return boards.values().stream().filter(DealRepository.Board::on).filter(b -> b.guildId().compareTo(after) > 0).limit(limit).toList();
        }
        @Override public void posted(String guild, String channel, String message, List<String> shown, String key, Instant now, boolean pinged) {
            var b = boards.get(guild);
            boards.put(guild, new DealRepository.Board(guild, channel, true, Optional.of(message), shown, Optional.of(key), Optional.of(now), 0,
                    Optional.empty(), b.ping(), pinged ? Optional.of(now) : b.lastPingAt()));
        }
        @Override public void edited(String guild, String message, String key) {
            var b = boards.get(guild);
            boards.put(guild, new DealRepository.Board(guild, b.channelId(), b.on(), b.messageId(), b.shownGames(), Optional.of(key), b.postedAt(),
                    0, b.offReason(), b.ping(), b.lastPingAt()));
        }
        @Override public int markFailed(String guild, String day, String reason) {
            var b = boards.get(guild);
            int failures = b.failures() + 1;
            boards.put(guild, new DealRepository.Board(guild, b.channelId(), failures < 3, b.messageId(), b.shownGames(), b.contentKey(),
                    b.postedAt(), failures, failures < 3 ? Optional.empty() : Optional.of(reason), b.ping(), b.lastPingAt()));
            return failures;
        }
        void add(String guild, DealRepository.Ping ping) {
            boards.put(guild, new DealRepository.Board(guild, "2" + guild, true, Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                    0, Optional.empty(), ping, Optional.empty()));
        }
    }

    static final class Discord implements DealBoard.Discord {
        final List<String> calls = new ArrayList<>();
        final List<String> posts = new ArrayList<>();
        final Set<String> gone = new HashSet<>(), broken = new HashSet<>();
        int next = 1;
        @Override public boolean ready() { return true; }
        @Override public DealBoard.Result post(String guild, String channel, MessageCreateData message) {
            if (broken.contains(guild)) { calls.add("fail " + guild); return DealBoard.Result.failed("I can't post in that channel"); }
            calls.add("post " + guild);
            posts.add(message.toData().toString() + " | mentions " + message.getMentionedRoles());
            return DealBoard.Result.ok("m" + next++);
        }
        @Override public DealBoard.Result edit(String guild, String channel, String message, MessageEditData data) {
            calls.add("edit " + guild);
            return gone.contains(message) ? DealBoard.Result.missing() : DealBoard.Result.edited();
        }
        @Override public void delete(String guild, String channel, String message) { calls.add("delete " + guild + " " + message); }
    }

    final Store store = new Store();
    final Discord discord = new Discord();
    Instant now = Instant.parse("2026-11-23T13:00:00Z");
    DealSnapshot snapshot;

    DealBoard board() { return new DealBoard(store, discord, Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO); }

    @BeforeEach void setUp() throws Exception {
        var fixture = DealFixtures.snapshot();
        snapshot = new DealSnapshot(fixture.deals(), now, fixture.rowsSeen());
        store.add("1", new DealRepository.Ping(Optional.of(ROLE), Set.of(), 24));
        store.add("2", DealRepository.Ping.NONE);
    }

    DealSnapshot at(List<Deal> deals) { return new DealSnapshot(deals, now, snapshot.rowsSeen()); }

    /** The same deals with the first one cheaper: the board changes, but no game is new. */
    DealSnapshot pricesChanged() {
        var deals = new ArrayList<>(snapshot.deals());
        var first = deals.getFirst();
        deals.set(0, new Deal(first.id(), first.store(), first.title(), first.link(), first.saleCents() - 50,
                first.normalCents(), first.percentOff(), first.rating(), first.reviews(), first.endsAt()));
        return at(deals);
    }

    /** A brand-new Steam game at the top. */
    DealSnapshot newGame(String gameId, DealStore launcher) {
        var deals = new ArrayList<>(snapshot.deals());
        var deal = DealFixtures.deal(launcher.choice + ":" + gameId, launcher, 9, 1999);
        deals.addFirst(new Deal(deal.id(), launcher, "New Game " + gameId, deal.link(), 9, 1999, 99, launcher == DealStore.STEAM ? 100 : 50, 10000000, Optional.empty()));
        return at(deals);
    }

    @Test void everyBoardIsReachedPageByPage() {
        for (int i = 100; i < 350; i++) store.add(String.valueOf(i), DealRepository.Ping.NONE);
        board().updateAll(snapshot);
        assertEquals(252, discord.calls.size(), "2 + 250 boards across three pages, each posted once");
        assertEquals(252, new HashSet<>(discord.calls).size());
    }

    @Test void theFirstPostNeverPingsAndAnUnchangedBoardIsLeftAlone() {
        board().updateAll(snapshot);
        assertEquals(List.of("post 1", "post 2"), discord.calls);
        assertTrue(discord.posts.stream().allMatch(p -> p.contains("mentions []")), "no ping on the first post");
        discord.calls.clear();
        board().updateAll(snapshot);
        assertTrue(discord.calls.isEmpty(), "nothing changed: no edit, no unread dot");
    }

    @Test void changedPricesAreEditedSilently() {
        board().updateAll(snapshot);
        discord.calls.clear();
        board().updateAll(pricesChanged());
        assertEquals(List.of("edit 1", "edit 2"), discord.calls);
    }

    @Test void aNewGameRepostsAndPingsWithACooldown() {
        board().updateAll(snapshot);
        discord.calls.clear();
        discord.posts.clear();
        now = now.plus(Duration.ofHours(3));
        board().updateAll(newGame("900001", DealStore.STEAM));
        assertEquals(List.of("delete 1 m1", "post 1", "delete 2 m2", "post 2"), discord.calls, "a new game reposts every board");
        assertTrue(discord.posts.get(0).contains("<@&" + ROLE + "> New deals: **New Game 900001**"));
        assertTrue(discord.posts.get(0).contains("mentions [" + ROLE + "]"));
        assertTrue(discord.posts.get(1).contains("mentions []"), "a board without a role never pings");

        discord.posts.clear();
        now = now.plus(Duration.ofHours(3));
        board().updateAll(newGame("900002", DealStore.STEAM));
        assertEquals(2, discord.posts.size(), "reposted again");
        assertTrue(discord.posts.get(0).contains("mentions []"), "but the 24 h cooldown holds the ping back");

        discord.posts.clear();
        now = now.plus(Duration.ofHours(21)); // 24 h after the ping
        board().updateAll(newGame("900003", DealStore.STEAM));
        assertTrue(discord.posts.get(0).contains("mentions [" + ROLE + "]"), "the cooldown has passed");
    }

    @Test void onlyTheChosenLaunchersPing() {
        store.add("1", new DealRepository.Ping(Optional.of(ROLE), Set.of(DealStore.GOG), 6));
        store.boards.remove("2");
        board().updateAll(snapshot);
        discord.posts.clear();
        board().updateAll(newGame("900001", DealStore.STEAM));
        assertTrue(discord.posts.getFirst().contains("mentions []"), "a Steam deal does not ping a GOG-only role");
        discord.posts.clear();
        board().updateAll(newGame("900002", DealStore.GOG));
        assertTrue(discord.posts.getFirst().contains("mentions [" + ROLE + "]"));
    }

    @Test void aGameBouncingInAndOutDoesNotRepost() {
        board().updateAll(snapshot);
        var withNew = newGame("900001", DealStore.STEAM);
        board().updateAll(withNew);
        discord.calls.clear();
        board().updateAll(snapshot);
        board().updateAll(withNew);
        assertTrue(discord.calls.stream().noneMatch(c -> c.startsWith("post")), "already shown once: edits only " + discord.calls);
    }

    @Test void aDeletedMessageIsPostedAgain() {
        board().updateAll(snapshot);
        discord.gone.add("m1");
        discord.calls.clear();
        board().updateAll(pricesChanged());
        assertEquals(List.of("edit 1", "post 1", "edit 2"), discord.calls);
        assertEquals(Optional.of("m3"), store.boards.get("1").messageId());
    }

    @Test void aBrokenChannelTurnsOffAfterThreeFailures() {
        discord.broken.add("2");
        for (int i = 0; i < 3; i++) board().updateAll(snapshot);
        var off = store.boards.get("2");
        assertFalse(off.on());
        assertEquals(Optional.of("I can't post in that channel"), off.offReason());
        assertTrue(store.boards.get("1").on());
    }

    @Test void staleDealsLeaveBoardsAlone() {
        board().updateAll(new DealSnapshot(snapshot.deals(), now.minus(Duration.ofHours(30)), 60));
        assertTrue(discord.calls.isEmpty());
        assertEquals(DealBoard.Action.NOTHING, board().updateOne("3", snapshot), "no board for that server");
        assertEquals(DealBoard.Action.POSTED, board().updateOne("1", snapshot));
    }
}
