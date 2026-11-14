package org.bunnys.bunnynexus.freebies;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The idle sender against a real isolated replica set, on a scheduler and clock the test controls. The sender is first
 * left idle until it polls every 30 s; each test then shows that queued work is picked up with no time passing (a
 * wake), that housekeeping recovers expired leases, that the pause works from memory, and how many MongoDB round
 * trips an idle minute makes (every command the bot's client starts is counted).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieSenderWakeIT {
    static final String OWNER = "333644367539470337", GUILD = "100000000000000001", CHANNEL = "200000000000000001",
            REVIEW = "100000000000000099";
    /** Per-game posts (the rollback path): a planned row is due at once, with no drop window to wait for. */
    static final FreebieFeatures PER_GAME = new FreebieFeatures(false, false, Set.of(), false, true, false);
    final Instant start = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieConfig config = new FreebieConfig(REVIEW, REVIEW, Set.of(OWNER), 10, 90, 10);
    final FreebieSubscriptions.Subscription subscription = new FreebieSubscriptions.Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.empty());

    LocalReplicaSet replica;
    /** The bot's own client: every command it starts is one round trip. */
    MongoClient counted;
    final AtomicInteger commands = new AtomicInteger();
    final Map<String, Integer> byCommand = new ConcurrentHashMap<>();

    ManualScheduler scheduler;
    MongoDatabase db;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    FreebieSystem system;
    JDA jda;
    StandardGuildMessageChannel channel;
    MessageHistory history;
    int offers;

    @BeforeAll void startReplica() throws Exception {
        replica = new LocalReplicaSet();
        String hosts = String.join(",", replica.ports.stream().map(p -> "127.0.0.1:" + p).toList());
        counted = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + hosts + "/?replicaSet=" + replica.name))
                .timeout(20, TimeUnit.SECONDS)
                .addCommandListener(new CommandListener() {
                    @Override public void commandStarted(CommandStartedEvent event) {
                        commands.incrementAndGet();
                        byCommand.merge(event.getCommandName(), 1, Integer::sum);
                    }
                }).build());
    }
    @AfterAll void stopReplica() {
        if (counted != null) counted.close();
        if (replica != null) replica.close();
    }

    @BeforeEach @SuppressWarnings("unchecked") // Generic REST action mocks.
    void fresh() {
        scheduler = new ManualScheduler(start);
        db = counted.getDatabase("sender_wake_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db); subscriptions.installIndexes();
        subscriptions.save(subscription, OWNER, start);
        offers = 0;

        jda = mock(JDA.class);
        var guild = mock(Guild.class);
        var member = mock(SelfMember.class);
        channel = mock(StandardGuildMessageChannel.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(jda.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        var self = mock(SelfUser.class);
        when(self.getId()).thenReturn("bot");
        when(jda.getSelfUser()).thenReturn(self);
        when(guild.getIdLong()).thenReturn(Long.parseLong(GUILD));
        when(guild.getSelfMember()).thenReturn(member);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        doReturn(channel).when(guild).getGuildChannelById(CHANNEL);
        when(member.hasPermission(org.mockito.ArgumentMatchers.eq(channel), any(Permission[].class))).thenReturn(true);
        // Every send is accepted at once; history is readable and holds none of our messages.
        var accepted = mock(Message.class);
        when(accepted.getId()).thenReturn("900000000000000001");
        var send = mock(MessageCreateAction.class, RETURNS_SELF);
        when(send.submit()).thenReturn(CompletableFuture.completedFuture(accepted));
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
        history = mock(MessageHistory.class);
        RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(history.retrievePast(50)).thenReturn(past);
        when(channel.getHistory()).thenReturn(history);
    }
    @AfterEach void close() { if (system != null) system.close(); }

    /** A running system whose sender has been idle long enough to poll only every 30 s. */
    FreebieSystem idleSystem(FreebieFeatures features) {
        system = new FreebieSystem(config, features, repository, subscriptions, () -> jda, scheduler.clock(), new GamerPowerFeed(), scheduler);
        system.startSending(Duration.ZERO);
        scheduler.advance(Duration.ofMinutes(3));
        return system;
    }

    Instant now() { return scheduler.now(); }
    MongoCollection<Document> rows() { return db.getCollection(FreebieRepository.DELIVERIES); }
    Document row(String id) { return rows().find(eq("_id", id)).first(); }

    FreebieOffer approved() {
        String id = String.valueOf(++offers);
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Epic Games Store"), now());
        return repository.transition(FreebieOffer.idFor(id), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, OWNER, now()).orElseThrow();
    }

    /** A first post due now, written straight to the database: nothing tells the sender about it. */
    String queuedRow() {
        var offer = approved();
        assertEquals(1, repository.planDeliveries(offer, List.of(subscription), now()));
        String id = offer.id() + "|" + CHANNEL;
        scheduler.runDue();
        assertEquals("PENDING", row(id).getString("state"), "an idle sender does not see the row until its next poll");
        return id;
    }

    /** No time passes: only a wake can have run the tick that sent it. */
    void assertSentWithinOneTick(String id) {
        scheduler.runDue();
        assertEquals("SENT", row(id).getString("state"));
    }

    // ------------------------------------------------------------------ wake call sites

    @Test void fanOutAfterAnApprovalIsSentAtOnce() {
        idleSystem(PER_GAME);
        var offer = approved();
        system.fanOut(offer);
        assertSentWithinOneTick(offer.id() + "|" + CHANNEL);
    }

    @Test void catchUpIsSentAtOnce() {
        idleSystem(PER_GAME);
        var offer = approved();
        repository.setFanoutDone(offer.id(), 0);
        assertEquals(new FreebieSystem.CatchUp(1, 1), system.catchUpChannel(GUILD, CHANNEL, store -> true));
        assertSentWithinOneTick(offer.id() + "|" + CHANNEL);
    }

    @Test void dmConsentWakesTheSender() {
        idleSystem(PER_GAME);
        String waiting = queuedRow();
        assertEquals(FreebieDmService.Subscribed.SUBSCRIBED,
                system.dms().subscribe("400000000000000001", EnumSet.of(FreebieSection.MAJOR_PC), consent -> true));
        assertSentWithinOneTick(waiting);
    }

    @Test void aConfirmedOwnerAnnouncementIsSentAtOnce() {
        idleSystem(PER_GAME);
        var preview = assertInstanceOf(FreebieBroadcasts.Preview.class, system.announce(OWNER, "Maintenance", "Back soon"));
        String button = ((net.dv8tion.jda.api.components.buttons.Button) preview.message().getComponents().getFirst().asActionRow()
                .getComponents().getFirst()).getCustomId();
        String[] parts = button.split(":", 3);
        scheduler.runDue();
        assertEquals(0, db.getCollection(FreebieBroadcastStore.JOBS).countDocuments(), "a preview sends nothing");
        assertTrue(system.confirmAnnouncement(OWNER, parts[2], parts[1]).startsWith("Sending to 1 server"));
        scheduler.runDue();
        assertEquals("SENT", db.getCollection(FreebieBroadcastStore.JOBS).find().first().getString("state"));
    }

    @Test void anOwnerRetryIsSentAtOnce() {
        idleSystem(PER_GAME);
        var offer = approved();
        repository.planDeliveries(offer, List.of(subscription), now());
        var claimed = repository.claim(now(), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.markFailed(claimed, FreebieFailure.REJECTED, "rejected", now()));
        var admin = system.deliveryAdmin();
        assertFalse(admin.preview(OWNER, offer.id(), CHANNEL).rows().isEmpty());
        String token = row(claimed.id()).getString("retryPreviewToken");
        scheduler.runDue();
        assertEquals("FAILED", row(claimed.id()).getString("state"));
        assertTrue(admin.confirm(OWNER, offer.id(), CHANNEL, token).text().startsWith("Retry queued"));
        assertSentWithinOneTick(claimed.id());
    }

    // ------------------------------------------------------------------ pause

    @Test void pauseStopsClaimsAtOnceAndResumeSendsAtOnce() {
        idleSystem(PER_GAME);
        system.setPaused(OWNER, true);
        var offer = approved();
        system.fanOut(offer); // Wakes the sender, which must not claim.
        String id = offer.id() + "|" + CHANNEL;
        scheduler.advance(Duration.ofMinutes(2));
        assertEquals("PENDING", row(id).getString("state"));
        assertNull(row(id).get("lastAttemptAt"), "a paused sender does not even claim");
        system.setPaused(OWNER, false);
        assertSentWithinOneTick(id);
    }

    @Test void aPauseWrittenByAnotherProcessStopsClaimsWithinOneHousekeepingPass() {
        idleSystem(PER_GAME);
        var otherProcess = new FreebieRepository(db);
        otherProcess.setSendingPaused(true, OWNER, now());
        system.housekeeping();
        var offer = approved();
        system.fanOut(offer);
        String id = offer.id() + "|" + CHANNEL;
        scheduler.advance(Duration.ofMinutes(2));
        assertEquals("PENDING", row(id).getString("state"));
        assertNull(row(id).get("lastAttemptAt"), "after housekeeping read the pause, the sender does not claim at all");
        // The other process resumes: housekeeping reads it, and the paused tick (which reads nothing) runs every second.
        otherProcess.setSendingPaused(false, OWNER, now());
        system.housekeeping();
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals("SENT", row(id).getString("state"));
    }

    @Test void aPauseNotYetSeenByThisProcessIsStillCheckedBeforeSubmission() {
        idleSystem(PER_GAME);
        new FreebieRepository(db).setSendingPaused(true, OWNER, now());
        var offer = approved();
        system.fanOut(offer); // No housekeeping yet: this process still believes sending is on and claims the row.
        scheduler.runDue();
        String id = offer.id() + "|" + CHANNEL;
        assertEquals("PENDING", row(id).getString("state"), "released, not sent");
        assertNotNull(row(id).get("lastAttemptAt"));
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    // ------------------------------------------------------------------ lease recovery in housekeeping

    @Test void housekeepingRecoversAnExpiredDeliveryLeaseAndTheResendChecksHistoryFirst() {
        idleSystem(PER_GAME);
        var offer = approved();
        repository.setFanoutDone(offer.id(), 1);
        repository.planDeliveries(offer, List.of(subscription), now());
        var crashed = repository.claim(now(), Duration.ofMinutes(3)).orElseThrow(); // A send that never reported back.
        scheduler.advance(Duration.ofMinutes(4));
        assertEquals("SENDING", row(crashed.id()).getString("state"), "the idle sender's own ticks recover nothing");

        system.housekeeping();
        var recovered = row(crashed.id());
        assertEquals("BLOCKED", recovered.getString("state"));
        assertEquals("UNCERTAIN", recovered.getString("blockedReason"));
        assertTrue(recovered.getBoolean("verifyFirst"));
        verify(channel, never()).sendMessage(any(MessageCreateData.class));

        scheduler.runDue(); // Housekeeping woke the sender: no wait for the next 30 s poll.
        var order = inOrder(history, channel);
        order.verify(history).retrievePast(50);
        order.verify(channel).sendMessage(any(MessageCreateData.class));
        assertEquals("SENT", row(crashed.id()).getString("state"));
    }

    @Test void housekeepingRecoversExpiredLeasesOfEveryOtherQueueAsVerifyFirst() {
        var publishing = new FreebieFeatures(false, true, Set.of(CHANNEL), false, true, false);
        idleSystem(publishing);
        Instant at = now();
        Duration lease = Duration.ofMinutes(3);
        var offer = approved();
        repository.setFanoutDone(offer.id(), 1);
        // Lifecycle: an ended-card edit for a sent alert.
        repository.planDeliveries(offer, List.of(subscription), at);
        assertTrue(repository.markSent(repository.claim(at, lease).orElseThrow(), "900000000000000002", at));
        assertEquals(1, repository.lifecycle().planEdits(offer.id(), false, at).queued());
        var edit = repository.lifecycle().claim(at, lease).orElseThrow();
        // Personal DM.
        assertEquals(1, repository.dmJobs().plan(offer.id(), List.of("400000000000000001"), at));
        var dm = repository.dmJobs().claim(at, lease).orElseThrow();
        // Owner announcement.
        var draft = repository.broadcasts().draft("T", "B", OWNER, FreebieReleases.token(), at, null, false);
        String token = db.getCollection(FreebieBroadcastStore.BROADCASTS).find(eq("_id", draft.id())).first().getString("token");
        assertTrue(repository.broadcasts().confirm(draft.id(), token, OWNER, at, false).isPresent());
        repository.broadcasts().planTargets(draft.id(), Map.of(GUILD, subscription), at);
        repository.broadcasts().planned(draft.id(), 1);
        var announcement = repository.broadcasts().claim(at, lease).orElseThrow();
        // Publication of a sent announcement page.
        assertTrue(repository.publications().plan(new Document("_id", "page-1").append("key", "k").append("page", 1)
                .append("guildId", GUILD).append("channelId", CHANNEL).append("messageId", "900000000000000003"), at));
        var publication = repository.publications().claim(at, lease).orElseThrow();

        scheduler.advance(Duration.ofMinutes(4));
        var lifecycleJobs = db.getCollection(FreebieLifecycleStore.COLLECTION);
        var dmJobs = db.getCollection(FreebieDmJobs.COLLECTION);
        var broadcastJobs = db.getCollection(FreebieBroadcastStore.JOBS);
        var publications = db.getCollection(FreebiePublicationStore.COLLECTION);
        assertEquals("SENDING", lifecycleJobs.find(eq("_id", edit.id())).first().getString("state"));
        assertEquals("SENDING", dmJobs.find(eq("_id", dm.id())).first().getString("state"));
        assertEquals("SENDING", broadcastJobs.find(eq("_id", announcement.id())).first().getString("state"));
        assertEquals("PUBLISHING", publications.find(eq("_id", publication.id())).first().getString("state"),
                "four minutes of idle sender ticks recover nothing");

        system.housekeeping();
        for (var job : List.of(lifecycleJobs.find(eq("_id", edit.id())).first(), dmJobs.find(eq("_id", dm.id())).first(),
                broadcastJobs.find(eq("_id", announcement.id())).first())) {
            assertEquals("PENDING", job.getString("state"), job.getString("_id"));
            assertTrue(job.getBoolean("verifyFirst"), "the next attempt checks history first: " + job.getString("_id"));
            assertNull(job.get("leaseToken"));
        }
        var uncertain = publications.find(eq("_id", publication.id())).first();
        assertEquals("UNCERTAIN", uncertain.getString("state"), "reconciled from the message's flag, never republished blindly");
        assertTrue(uncertain.getBoolean("uncertain"));

        // The woken sender takes the announcement again with no time passing, and reads history before sending.
        scheduler.runDue();
        var order = inOrder(history, channel);
        order.verify(history).retrievePast(50);
        order.verify(channel).sendMessage(any(MessageCreateData.class));
        assertEquals("SENT", broadcastJobs.find(eq("_id", announcement.id())).first().getString("state"));
    }

    // ------------------------------------------------------------------ publishing clean-up

    @Test void disablingPublishingCancelsOpenJobsWithinOneHousekeepingPassNotOnATick() {
        idleSystem(PER_GAME); // Publishing is off.
        assertTrue(repository.publications().plan(new Document("_id", "page-1").append("key", "k").append("page", 1)
                .append("guildId", GUILD).append("channelId", CHANNEL).append("messageId", "900000000000000003"), now()));
        db.getCollection(FreebieAnnouncementStore.COLLECTION).insertOne(new Document("_id", "page-2").append("channelId", CHANNEL)
                .append("state", "SENT").append("publishPending", true));
        var publications = db.getCollection(FreebiePublicationStore.COLLECTION);
        system.catchUpChannel(GUILD, CHANNEL, store -> true); // Nothing to queue, so no wake; then two idle minutes of ticks.
        scheduler.advance(Duration.ofMinutes(2));
        assertEquals("QUEUED", publications.find().first().getString("state"), "left alone by the tick, and never claimed while off");
        system.housekeeping();
        assertEquals("CANCELLED", publications.find().first().getString("state"));
        assertNull(db.getCollection(FreebieAnnouncementStore.COLLECTION).find(eq("_id", "page-2")).first().get("publishPending"));
    }

    // ------------------------------------------------------------------ round trips

    @Test void anIdleMinuteMakesFewRoundTrips() {
        idleSystem(FreebieFeatures.DEFAULT);
        var live = approved();
        repository.setFanoutDone(live.id(), 0);
        // Reach the steady state: both ticks on their 30 s step, and housekeeping's once-per-process work done.
        for (int pass = 0; pass < 6; pass++) { scheduler.advance(Duration.ofSeconds(30)); system.housekeeping(); }

        commands.set(0);
        byCommand.clear();
        scheduler.advance(Duration.ofSeconds(60));
        int polling = commands.get();
        var pollingCommands = new TreeMap<>(byCommand);
        system.housekeeping();
        system.housekeeping();
        int total = commands.get();
        System.out.println("IDLE_ROUND_TRIPS minute: sender+notices=" + polling + " " + pollingCommands + ", housekeeping (2 passes)="
                + (total - polling) + ", total=" + total + " (" + String.format(Locale.ROOT, "%.2f", total / 60.0) + "/s) " + new TreeMap<>(byCommand));
        assertTrue(polling <= 30, "sender and notice ticks made " + polling + " round trips in an idle minute " + pollingCommands);
        assertTrue(total <= 120, "an idle minute made " + total + " round trips (limit: 2 a second)");
    }
}
