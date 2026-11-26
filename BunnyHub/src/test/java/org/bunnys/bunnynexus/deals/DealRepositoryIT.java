package org.bunnys.bunnynexus.deals;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bunnys.bunnynexus.freebies.LocalReplicaSet;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DealRepositoryIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    DealRepository repository;
    final Instant now = Instant.parse("2026-11-23T17:00:00Z");
    final String guild = "100000000000000001", channel = "100000000000000002", admin = "100000000000000003";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("deals_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new DealRepository(db);
        repository.installIndexes();
    }

    @Test void indexesInstallTwiceWithTheirNames() {
        repository.installIndexes();
        Set<String> names = new HashSet<>();
        for (String collection : List.of(DealRepository.BOARDS))
            for (Document index : db.getCollection(collection).listIndexes()) names.add(index.getString("name"));
        assertTrue(names.contains("deal_boards_on"), names.toString());
    }

    @Test void theSnapshotRoundTrips() throws Exception {
        var snapshot = DealFixtures.snapshot();
        repository.saveSnapshot(snapshot);
        var loaded = repository.loadSnapshot().orElseThrow();
        assertEquals(snapshot.fetchedAt(), loaded.fetchedAt());
        assertEquals(snapshot.rowsSeen(), loaded.rowsSeen());
        assertEquals(snapshot.deals(), loaded.deals());
        assertTrue(loaded.deals().stream().anyMatch(d -> d.endsAt().isPresent()), "Steam sale ends are kept");
    }

    @Test void aBoardRemembersItsMessageAndWhatItShowed() {
        repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now);
        var fresh = repository.board(guild).orElseThrow();
        assertTrue(fresh.on());
        assertTrue(fresh.messageId().isEmpty());
        repository.posted(guild, channel, "300000000000000001", List.of("1", "2"), "k1", now, false);
        repository.edited(guild, "300000000000000001", "k2");
        var b = repository.board(guild).orElseThrow();
        assertEquals(Optional.of("300000000000000001"), b.messageId());
        assertEquals(List.of("1", "2"), b.shownGames());
        assertEquals(Optional.of("k2"), b.contentKey());
        assertEquals(Optional.of(now), b.postedAt());
        assertEquals(List.of(guild), repository.boardsOn("", 100).stream().map(DealRepository.Board::guildId).toList());
        assertEquals(java.util.Set.of(guild), repository.guildsWithBoardOn());
    }

    @Test void pingSettingsAndTheLastPingAreKept() {
        var ping = new DealRepository.Ping(Optional.of("500000000000000001"), Set.of(DealStore.GOG), 6);
        repository.setBoard(guild, channel, ping, admin, now);
        repository.posted(guild, channel, "300000000000000001", List.of("1"), "k1", now, true);
        var b = repository.board(guild).orElseThrow();
        assertEquals(ping, b.ping());
        assertEquals(Optional.of(now), b.lastPingAt());
        assertTrue(b.ping().covers(DealStore.GOG));
        assertFalse(b.ping().covers(DealStore.STEAM));
        assertTrue(DealRepository.Ping.NONE.covers(DealStore.STEAM), "no launchers means every launcher");
        assertEquals(24, new DealRepository.Ping(Optional.empty(), Set.of(), 5).everyHours(), "an unknown cooldown falls back to a day");
    }

    @Test void theSameChannelKeepsTheMessageAndANewOneStartsOver() {
        repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now);
        repository.posted(guild, channel, "300000000000000001", List.of("1"), "k1", now, false);
        var before = repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now).orElseThrow();
        assertEquals(Optional.of("300000000000000001"), before.messageId());
        assertEquals(Optional.of("300000000000000001"), repository.board(guild).orElseThrow().messageId());
        repository.setBoard(guild, "100000000000000009", DealRepository.Ping.NONE, admin, now);
        var moved = repository.board(guild).orElseThrow();
        assertTrue(moved.messageId().isEmpty());
        assertTrue(moved.shownGames().isEmpty());
        repository.posted(guild, channel, "300000000000000002", List.of("1"), "k1", now, false);
        assertTrue(repository.board(guild).orElseThrow().messageId().isEmpty(), "a late post for the old channel is ignored");
    }

    @Test void aFailedDayCountsOnceAndTheThirdTurnsTheBoardOff() {
        repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now);
        repository.posted(guild, channel, "300000000000000001", List.of("1"), "k1", now, false);
        assertEquals(1, repository.markFailed(guild, "2026-11-23", "I can't post in that channel"));
        assertEquals(1, repository.markFailed(guild, "2026-11-23", "I can't post in that channel"));
        assertEquals(2, repository.markFailed(guild, "2026-11-24", "I can't post in that channel"));
        assertTrue(repository.board(guild).orElseThrow().on());
        assertEquals(3, repository.markFailed(guild, "2026-11-25", "I can't post in that channel"));
        var off = repository.board(guild).orElseThrow();
        assertFalse(off.on());
        assertEquals(Optional.of("I can't post in that channel"), off.offReason());
        assertTrue(off.messageId().isEmpty());
        assertTrue(repository.boardsOn("", 100).isEmpty());
        assertTrue(repository.guildsWithBoardOn().isEmpty());
        repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now);
        var back = repository.board(guild).orElseThrow();
        assertTrue(back.on());
        assertEquals(0, back.failures());
        assertTrue(back.offReason().isEmpty());
    }

    @Test void turningOffForgetsTheMessage() {
        repository.setBoard(guild, channel, DealRepository.Ping.NONE, admin, now);
        repository.posted(guild, channel, "300000000000000001", List.of("1"), "k1", now, false);
        var before = repository.turnOff(guild, admin, now).orElseThrow();
        assertEquals(Optional.of("300000000000000001"), before.messageId());
        var off = repository.board(guild).orElseThrow();
        assertEquals(Optional.of("turned off"), off.offReason());
        assertTrue(off.messageId().isEmpty());
        repository.forget(guild);
        assertTrue(repository.board(guild).isEmpty());
    }

}
