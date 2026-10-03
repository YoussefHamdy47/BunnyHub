package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieDeliveryStoreIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final String owner = "333644367539470337", guildId = "100000000000000001", channelId = "200000000000000001";
    final FreebieConfig config = new FreebieConfig("100000000000000099", Set.of(owner), 10);
    JDA jda;
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("delivery_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db);
        subscriptions.save(new FreebieSubscriptions.Subscription(guildId, channelId, FreebieStore.EPIC, Optional.empty()), owner, now);
        jda = mock(JDA.class); var guild = mock(Guild.class); var member = mock(SelfMember.class);
        var channel = mock(StandardGuildMessageChannel.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getGuildById(guildId)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(Long.parseLong(guildId));
        when(guild.getSelfMember()).thenReturn(member);
        when(guild.getChannelById(StandardGuildMessageChannel.class, channelId)).thenReturn(channel);
        when(member.hasPermission(org.mockito.ArgumentMatchers.eq(channel), any(Permission[].class))).thenReturn(true);
    }
    FreebieDeliveryAdmin admin(Instant at) { return new FreebieDeliveryAdmin(config, repository, subscriptions, () -> jda, Clock.fixed(at, ZoneOffset.UTC)); }
    FreebieRepository.Claimed claim() {
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Epic Games Store"), now);
        var offer = repository.transition("gamerpower:1", FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, owner, now).orElseThrow();
        repository.planDelivery(offer, new FreebieSubscriptions.Subscription(guildId, channelId, FreebieStore.EPIC, Optional.empty()), now);
        return repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
    }
    Document row(String id) { return db.getCollection(FreebieRepository.DELIVERIES).find(eq("_id", id)).first(); }
    String failed() {
        var c = claim(); assertTrue(repository.markFailed(c, FreebieFailure.MISSING_PERMISSIONS, "PRIVATE_SECRET", now)); return c.id();
    }
    String preview(String id) {
        assertFalse(admin(now).preview(owner, "gamerpower:1", channelId).rows().isEmpty());
        return row(id).getString("retryPreviewToken");
    }
    @Test void expiredLeaseRejectsSuccessEvenBeforeRecoverySweep() {
        var c = claim(); assertFalse(repository.markSent(c, "stale", now.plusSeconds(180)));
        assertFalse(repository.deliveries().block(c, FreebieDeliveryStore.Block.UNCERTAIN, now.plusSeconds(180)));
        assertFalse(repository.deliveries().submitting(c, now.plusSeconds(180)));
    }
    @Test void crashBeforeSubmissionAndAfterAcceptanceBothRequireHistoryAfterRestart() {
        var c = claim();
        var restarted = new FreebieRepository(db);
        restarted.recoverExpiredLeases(now.plusSeconds(180));
        var next = restarted.claim(now.plusSeconds(180), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(next.verifyFirst());
        assertTrue(restarted.deliveries().submitting(next, now.plusSeconds(180)));
        assertTrue(row(c.id()).getBoolean("verifyFirst"));
        // Discord accepts; process dies without writing success.
        restarted = new FreebieRepository(db); restarted.recoverExpiredLeases(now.plusSeconds(360));
        var recovered = restarted.claim(now.plusSeconds(360), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(recovered.verifyFirst()); assertEquals(c.channelId(), recovered.channelId());
        assertEquals(FreebieMessages.nonce(c.id()), FreebieMessages.nonce(recovered.id()));
        assertFalse(restarted.markSent(next, "old", now.plusSeconds(361)));
        assertTrue(restarted.markSent(recovered, "found-in-history", now.plusSeconds(361)));
        assertTrue(restarted.claim(now.plusSeconds(362), Duration.ofMinutes(3)).isEmpty());
    }
    @Test void blockedStatePersistsReasonTimingAndCountsAsOpen() {
        var c = claim(); assertTrue(repository.deliveries().block(c, FreebieDeliveryStore.Block.HISTORY_PERMISSION, now));
        assertEquals("BLOCKED", row(c.id()).getString("state"));
        assertEquals(1, repository.counts(c.offerId()).open());
        assertEquals(1, repository.health().snapshot(now).deliveries().total());
        assertTrue(repository.claim(now.plusSeconds(119), Duration.ofMinutes(3)).isEmpty());
        var next = new FreebieRepository(db).claim(now.plusSeconds(120), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(next.verifyFirst());
        assertFalse(repository.markFailed(c, FreebieFailure.REJECTED, "old", now.plusSeconds(121)));
        assertFalse(repository.deliveries().block(c, FreebieDeliveryStore.Block.HISTORY_FAILED, now.plusSeconds(121)));
    }
    @Test void concurrentDuplicateConfirmationsQueueOnlyOneAndKeepAudit() throws Exception {
        String id = failed(), token = preview(id);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("Retry queued"));
            int wins = 0; for (var result : pool.invokeAll(tasks)) if (result.get()) wins++;
            assertEquals(1, wins);
        }
        assertEquals(1, row(id).getInteger("manualRetries"));
        assertEquals(1, row(id).getList("retryAudit", Document.class).size());
        assertEquals(owner, row(id).getList("retryAudit", Document.class).getFirst().getString("actor"));
        assertEquals(1, row(id).getInteger("attempts"));
    }
    @Test void stoppedBetweenPreviewAndConfirmationCannotBeReopened() {
        String id = failed(), token = preview(id);
        repository.transition("gamerpower:1", FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, owner, now);
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        assertEquals("FAILED", row(id).getString("state"));
    }
    @Test void expiryAndFeedDisappearanceInvalidatePreviews() {
        String id = failed(), token = preview(id);
        assertTrue(admin(now.plus(Duration.ofDays(10))).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        repository.markMissing(Set.of()); repository.markMissing(Set.of());
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        assertEquals("FAILED", row(id).getString("state"));
    }
    @Test void changedSubscriptionOrPermissionInvalidatesConfirmation() {
        String id = failed(), token = preview(id);
        var originalGuild = jda.getGuildById(guildId);
        when(jda.getGuildById(guildId)).thenReturn(null);
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        when(jda.getGuildById(guildId)).thenReturn(originalGuild);
        db.getCollection(FreebieSubscriptions.COLLECTION).deleteMany(new Document());
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        assertEquals("FAILED", row(id).getString("state"));
    }
    @Test void expiredPreviewCannotQueueAndSentOrCancelledNeverReopen() {
        String id = failed(), token = preview(id);
        assertTrue(admin(now.plusSeconds(301)).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("Confirmation expired"));
        for (String state : List.of("SENT", "CANCELLED")) {
            db.getCollection(FreebieRepository.DELIVERIES).updateOne(eq("_id", id), set("state", state));
            assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
            assertEquals(state, row(id).getString("state"));
        }
    }
    @Test void changedRevisionAndWrongActorInvalidateConfirmation() {
        var c = claim(); repository.deliveries().block(c, FreebieDeliveryStore.Block.UNCERTAIN, now);
        String token = preview(c.id());
        assertFalse(repository.deliveries().retry(row(c.id()), "someone-else", token, now));
        var next = repository.claim(now.plusSeconds(120), Duration.ofMinutes(3)).orElseThrow();
        repository.deliveries().block(next, FreebieDeliveryStore.Block.HISTORY_FAILED, now.plusSeconds(120));
        assertFalse(repository.deliveries().retry(row(c.id()), owner, token, now.plusSeconds(121)));
    }
    @Test void retryPreservesUncertaintyAndPostedSummary() {
        var c = claim(); repository.deliveries().block(c, FreebieDeliveryStore.Block.UNCERTAIN, now);
        repository.transition(c.offerId(), FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now);
        db.getCollection(FreebieRepository.OFFERS).updateOne(eq("_id", c.offerId()), combine(set("summaryPosted", true), set("summaryMessageId", "original")));
        String token = preview(c.id());
        assertTrue(admin(now).confirm(owner, c.offerId(), channelId, token).text().startsWith("Retry queued"));
        assertTrue(repository.claim(now, Duration.ofMinutes(3)).orElseThrow().verifyFirst());
        var offer = db.getCollection(FreebieRepository.OFFERS).find(eq("_id", c.offerId())).first();
        assertTrue(offer.getBoolean("summaryPosted")); assertEquals("original", offer.getString("summaryMessageId"));
    }
    @Test void boundedPaginationIncludesEveryOutcomeWithoutLeakingStoredDetails() {
        String id = failed();
        var base = row(id);
        for (int i = 2; i <= 13; i++) {
            var copy = new Document(base); String channel = "2000000000000000" + String.format(Locale.ROOT, "%02d", i);
            copy.put("_id", "gamerpower:1|" + channel); copy.put("channelId", channel);
            copy.put("state", i % 2 == 0 ? "BLOCKED" : "SENT");
            db.getCollection(FreebieRepository.DELIVERIES).insertOne(copy);
        }
        Set<String> seen = new HashSet<>(); String cursor = "";
        while (true) {
            var page = repository.deliveries().page("gamerpower:1", cursor); assertTrue(page.size() <= 6);
            for (var d : page.stream().limit(5).toList()) assertTrue(seen.add(d.getString("_id")));
            var view = admin(now).history(owner, "gamerpower:1", cursor);
            assertFalse(view.text().contains("PRIVATE_SECRET")); assertTrue(view.text().length() <= 4096);
            if (page.size() <= 5) break;
            cursor = page.get(4).getString("_id");
        }
        assertEquals(13, seen.size());
    }
    @Test void automaticSendBudgetIsIndependentOfHistoryChecksAndCanBeRenewedExplicitly() {
        var c = claim();
        for (int i = 0; i < 5; i++) assertTrue(repository.deliveries().submitting(c, now));
        assertFalse(repository.deliveries().submitting(c, now));
        repository.deliveries().block(c, FreebieDeliveryStore.Block.EXHAUSTED, now);
        String token = preview(c.id());
        assertTrue(admin(now).confirm(owner, c.offerId(), channelId, token).text().startsWith("Retry queued"));
        var next = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(next.verifyFirst()); assertTrue(repository.deliveries().submitting(next, now));
        assertEquals(6, row(c.id()).getInteger("sendAttempts"));
    }
    @Test void legacyTransientFailureKeepsUncertaintyWhenManuallyRetried() {
        String id = failed();
        db.getCollection(FreebieRepository.DELIVERIES).updateOne(eq("_id", id), combine(set("failure", "TRANSIENT"), set("verifyFirst", false)));
        String token = preview(id);
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("Retry queued"));
        assertTrue(repository.claim(now, Duration.ofMinutes(3)).orElseThrow().verifyFirst());
        assertTrue(row(id).getList("retryAudit", Document.class).getFirst().getBoolean("uncertain"));
    }
    @Test void permissionLossAndMissingApprovalInvalidateConfirmation() {
        String id = failed(), token = preview(id);
        var guild = jda.getGuildById(guildId); var member = guild.getSelfMember();
        when(member.hasPermission(any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class), any(Permission[].class))).thenReturn(false);
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        when(member.hasPermission(any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class), any(Permission[].class))).thenReturn(true);
        db.getCollection(FreebieRepository.OFFERS).updateOne(eq("_id", "gamerpower:1"), unset("decidedBy"));
        assertTrue(admin(now).confirm(owner, "gamerpower:1", channelId, token).text().startsWith("No retry"));
        assertEquals("FAILED", row(id).getString("state"));
    }
    @Test void retryLimitPreservesAuditWithoutTruncation() {
        String id = failed(), token = preview(id);
        db.getCollection(FreebieRepository.DELIVERIES).updateOne(eq("_id", id), set("manualRetries", 100));
        assertFalse(repository.deliveries().retry(row(id), owner, token, now));
        assertTrue(admin(now).preview(owner, "gamerpower:1", channelId).text().contains("limit"));
        assertEquals("FAILED", row(id).getString("state"));
    }}




