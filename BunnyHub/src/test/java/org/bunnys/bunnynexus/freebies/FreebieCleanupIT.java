package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deleted channel/role cleanup against a real isolated replica set. Discord is mocked. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieCleanupIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    FreebieCleanup cleanup;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final String guild = "100000000000000001", gone = "200000000000000001", kept = "200000000000000002",
            role = "300000000000000001", otherGuild = "100000000000000002";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("cleanup_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db); subscriptions.installIndexes();
        cleanup = new FreebieCleanup(subscriptions, repository.deliveries(), repository.notices());
    }

    void subscribe(String g, String channel, FreebieStore store, String ping) {
        subscriptions.save(new FreebieSubscriptions.Subscription(g, channel, store, Optional.ofNullable(ping)), "1", now);
    }
    FreebieOffer approved(String id) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), now);
        return repository.transition(FreebieOffer.idFor(id), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "owner", now).orElseThrow();
    }
    Document delivery(String id) { return db.getCollection(FreebieRepository.DELIVERIES).find(eq("_id", id)).first(); }

    @Test void deletedChannelLosesSettingsAndOpenAlertsEndCancelledSoTheSummaryCanFinish() {
        subscribe(guild, gone, FreebieStore.STEAM, role); subscribe(guild, gone, FreebieStore.EPIC, null);
        subscribe(guild, kept, FreebieStore.STEAM, null);
        var offer = approved("1");
        repository.planDeliveries(offer, subscriptions.forStore(FreebieStore.STEAM), now);
        // The deleted channel's alert is blocked (as an uncertain send would be); the kept one is in flight.
        db.getCollection(FreebieRepository.DELIVERIES).updateOne(eq("_id", offer.id() + "|" + gone),
                new Document("$set", new Document("state", "BLOCKED").append("blockedReason", "DESTINATION")
                        .append("nextAttemptAt", Date.from(now.plusSeconds(120)))));
        var inFlight = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(offer.id() + "|" + kept, inFlight.id());

        assertEquals(1, cleanup.channelDeleted(guild, gone, Optional.of("old-alerts"), now));
        assertTrue(subscriptions.forGuild(guild).stream().noneMatch(s -> s.channelId().equals(gone)));
        assertEquals(1, subscriptions.forGuild(guild).size());
        var cancelled = delivery(offer.id() + "|" + gone);
        assertEquals("CANCELLED", cancelled.getString("state"));
        assertEquals(FreebieCleanup.CHANNEL_DELETED, cancelled.getString("cancelReason"));
        assertEquals(FreebieCleanup.CHANNEL_DELETED_TEXT, cancelled.getString("failureDetail"));
        assertNotNull(cancelled.getDate("finishedAt"));
        assertEquals("SENDING", delivery(inFlight.id()).getString("state"), "another channel's lease is untouched");
        assertTrue(repository.markSent(inFlight, "m", now));
        assertEquals(new FreebieRepository.Counts(1, 0, 1, 0), repository.counts(offer.id()));
        assertEquals(0, cleanup.channelDeleted(guild, gone, Optional.of("old-alerts"), now), "idempotent");
    }

    @Test void senderLeaseCancelsADeletedChannelOnlyWhileItOwnsTheLease() {
        subscribe(guild, gone, FreebieStore.STEAM, null);
        var offer = approved("1");
        repository.planDeliveries(offer, subscriptions.forStore(FreebieStore.STEAM), now);
        var job = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertFalse(repository.deliveries().cancelDeleted(job, now.plus(Duration.ofMinutes(3))), "expired lease");
        assertTrue(repository.deliveries().cancelDeleted(job, now));
        var row = delivery(job.id());
        assertEquals("CANCELLED", row.getString("state"));
        assertNull(row.getString("leaseToken"));
        assertEquals(0, repository.counts(offer.id()).open());
    }

    @Test void deletedRoleOnlyClearsThePingInThatServer() {
        subscribe(guild, kept, FreebieStore.STEAM, role); subscribe(guild, kept, FreebieStore.EPIC, role);
        subscribe(guild, gone, FreebieStore.GOG, "300000000000000002");
        subscribe(otherGuild, kept, FreebieStore.STEAM, role);
        assertEquals(2, cleanup.roleDeleted(guild, role, Optional.of("Pings"), now));
        var settings = subscriptions.forGuild(guild);
        assertEquals(3, settings.size(), "alerts keep posting");
        assertEquals(2, settings.stream().filter(s -> s.roleId().isEmpty()).count());
        assertEquals(Optional.of(role), subscriptions.forGuild(otherGuild).getFirst().roleId());
    }

    @Test void offlineSweepReconcilesOnlyLoadedGuilds() {
        subscribe(guild, gone, FreebieStore.STEAM, role); subscribe(guild, kept, FreebieStore.STEAM, role);
        String otherChannel = "200000000000000003", deletedOffline = "200000000000000004";
        subscribe(otherGuild, otherChannel, FreebieStore.STEAM, role);
        var offer = approved("1");
        repository.planDeliveries(offer, subscriptions.forStore(FreebieStore.STEAM), now);
        subscriptions.remove(guild, gone, Optional.empty()); // Removed by hand earlier; its alert is still queued.
        subscribe(guild, deletedOffline, FreebieStore.EPIC, null);
        assertEquals(List.of(List.of(guild, gone), List.of(guild, kept), List.of(otherGuild, otherChannel)),
                repository.deliveries().openDestinations(10));

        var client = mock(JDA.class);
        var loaded = mock(Guild.class);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById(Long.parseLong(guild))).thenReturn(loaded);
        when(loaded.getGuildChannelById(kept)).thenReturn(mock(GuildChannel.class));
        // otherGuild is not cached (for example still loading): nothing there may be treated as deleted.
        var result = cleanup.sweep(client, now);
        assertEquals(new FreebieCleanup.Sweep(1, 1, 1), result);
        assertEquals(1, subscriptions.forGuild(guild).size());
        assertTrue(subscriptions.forGuild(guild).getFirst().roleId().isEmpty(), "deleted role cleared");
        assertEquals("CANCELLED", delivery(offer.id() + "|" + gone).getString("state"));
        assertEquals("PENDING", delivery(offer.id() + "|" + kept).getString("state"));
        assertEquals(1, subscriptions.forGuild(otherGuild).size());
        assertEquals(Optional.of(role), subscriptions.forGuild(otherGuild).getFirst().roleId());
        assertEquals(new FreebieCleanup.Sweep(0, 0, 0), cleanup.sweep(client, now), "idempotent");
    }

    org.bson.Document notice(String id) { return db.getCollection(FreebieRepository.NOTICES).find(eq("_id", id)).first(); }

    @Test void deletionsQueueOneOwnerNoticeEachWithoutTouchingTheDailyLimit() {
        subscribe(guild, gone, FreebieStore.STEAM, role); subscribe(guild, gone, FreebieStore.EPIC, role);
        subscribe(guild, kept, FreebieStore.GOG, role);
        db.getCollection(FreebieRepository.NOTICES).insertOne(new Document("_id", guild).append("noticeState", "SENT")
                .append("noticeReconciled", true).append("lastNoticeAt", Date.from(now.minusSeconds(60))));
        cleanup.channelDeleted(guild, gone, Optional.of("epic-games"), now);
        cleanup.channelDeleted(guild, gone, Optional.empty(), now.plusSeconds(5)); // The sweep finding it again.
        cleanup.roleDeleted(guild, role, Optional.of("Pings"), now);
        var channelNotice = notice("change|" + guild + "|CHANNEL_DELETED|" + gone);
        assertEquals("PENDING", channelNotice.getString("noticeState"));
        assertEquals("epic-games", channelNotice.getString("noticeTargetName"));
        assertEquals(List.of("epic", "steam"), channelNotice.getList("noticeStores", String.class));
        var roleNotice = notice("change|" + guild + "|ROLE_DELETED|" + role);
        assertEquals(List.of("gog"), roleNotice.getList("noticeStores", String.class));
        assertEquals(List.of(kept), roleNotice.getList("noticeChannels", String.class));
        assertEquals(3, db.getCollection(FreebieRepository.NOTICES).countDocuments(), "queued once each");

        var store = repository.notices();
        var first = store.claim(now).orElseThrow();
        var second = store.claim(now).orElseThrow();
        assertTrue(store.claim(now).isEmpty());
        for (var claim : List.of(first, second)) {
            assertTrue(claim.change().isPresent());
            assertEquals(guild, claim.guildId());
            assertTrue(store.destination(claim, "DM", "dm", "owner", now));
        }
        assertTrue(store.sent(first, "m1", now));
        assertTrue(store.drop(second, now));
        assertFalse(store.sent(second, "late", now), "a dropped notice cannot be recorded as sent");
        store.recover(now);
        assertEquals(Date.from(now.minusSeconds(60)), notice(guild).getDate("lastNoticeAt"), "daily failure limit untouched");
        assertEquals("SENT", notice(first.id()).getString("noticeState"));
        assertTrue(notice(first.id()).getBoolean("noticeReconciled"));
        assertEquals("DROPPED", notice(second.id()).getString("noticeState"));

        // Both are removed by the retention purge once old enough; the daily-limit document is old too by then.
        var later = now.plus(Duration.ofDays(91));
        assertEquals(3, repository.retention().purge(later, 90, FreebieRetention.Limits.DEFAULT).notices());
    }
}
