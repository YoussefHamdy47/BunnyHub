package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Entry;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Status;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException.Kind;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.bunnys.bunnynexus.mailbridge.MailFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailBridgeTest {
    static final String CHANNEL = MailBridgeConfig.DEFAULT_CHANNEL, DIAGNOSTIC = "1562067773033807999";

    /** The ledger as MongoMailLedger behaves, in memory; shared between "restarts". */
    static final class MemoryLedger implements MailLedger {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        Instant cutoff;
        @Override public Instant cutoff(Instant firstStart) { if (cutoff == null) cutoff = firstStart; return cutoff; }
        @Override public Optional<Entry> find(String id) { return Optional.ofNullable(entries.get(id)); }
        @Override public void save(Entry entry) { entries.put(entry.id(), entry); }
        @Override public List<Entry> due(Instant now, int limit) {
            return entries.values().stream().filter(e -> (e.status() == Status.PENDING || e.status() == Status.SENDING) && !e.nextAttempt().isAfter(now))
                    .sorted(Comparator.comparing(Entry::received)).limit(limit).toList();
        }
        @Override public Map<Status, Long> counts() {
            Map<Status, Long> counts = new EnumMap<>(Status.class);
            entries.values().forEach(e -> counts.merge(e.status(), 1L, Long::sum));
            return counts;
        }
        Status status(String id) { return entries.get(id).status(); }
    }

    static final class FakeSource implements MailSource {
        final Map<String, DataObject> messages = new LinkedHashMap<>();
        MailSourceException failNext;
        int searches, fetches;
        final List<String> queries = new ArrayList<>();
        @Override public List<String> search(String query, int max) throws MailSourceException {
            searches++;
            queries.add(query);
            if (failNext != null) { var f = failNext; failNext = null; throw f; }
            List<String> ids = new ArrayList<>(messages.keySet());
            Collections.reverse(ids);
            return ids.subList(0, Math.min(max, ids.size()));
        }
        @Override public DataObject fetch(String id) throws MailSourceException {
            fetches++;
            DataObject message = messages.get(id);
            if (message == null) throw new MailSourceException(Kind.PERMANENT, "Gmail rejected the request (HTTP 404)");
            return message;
        }
    }

    static final class FakePoster implements MailPoster {
        final List<Map.Entry<String, MessageCreateData>> posted = new ArrayList<>();
        final Deque<CompletableFuture<String>> outcomes = new ArrayDeque<>();
        Optional<String> problem = Optional.empty();
        Optional<Boolean> history = Optional.of(false);
        @Override public Optional<String> problem(String guildId, String channelId) { return problem; }
        @Override public CompletableFuture<String> post(String channelId, MessageCreateData message, String nonce) {
            assertTrue(nonce.length() <= 25);
            posted.add(Map.entry(channelId, message));
            return outcomes.isEmpty() ? CompletableFuture.completedFuture("9" + posted.size()) : outcomes.poll();
        }
        @Override public Optional<Boolean> alreadyPosted(String channelId, String ref) { return history; }
        long postsTo(String channel) { return posted.stream().filter(p -> p.getKey().equals(channel)).count(); }
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
    final MemoryLedger ledger = new MemoryLedger();
    final FakeSource source = new FakeSource();
    final FakePoster poster = new FakePoster();
    MailBridge bridge = bridge(3, Optional.of(DIAGNOSTIC));

    MailBridge bridge(int attempts, Optional<String> diagnostic) {
        return new MailBridge(config(attempts, diagnostic), source, ledger, poster, clock);
    }

    void arrive(String id, String taskName) { source.messages.put(id, task(id, taskName, clock.instant())); }

    static ErrorResponseException discordRefusal(int code) {
        ErrorResponseException refusal = mock(ErrorResponseException.class);
        when(refusal.getErrorCode()).thenReturn(code);
        return refusal;
    }

    @Test void eachTaskReachesTheChannelOnceWithTheUserMentioned() {
        bridge.poll(); // first start: sets the cutoff
        clock.advance(Duration.ofMinutes(1));
        arrive("a1", "EEE Industrial Training Hunter");
        arrive("a2", "Power Electronics Career Intelligence");
        arrive("a3", "RTL / Digital Verification Career Tracker");
        bridge.poll();
        assertEquals(3, poster.postsTo(CHANNEL));
        assertTrue(poster.posted.get(0).getValue().getEmbeds().getFirst().getTitle().startsWith("EEE"), "oldest first");
        poster.posted.forEach(p -> assertEquals(Set.of(MailBridgeConfig.DEFAULT_MENTION), p.getValue().getMentionedUsers()));
        for (String id : List.of("a1", "a2", "a3")) {
            assertEquals(Status.SENT, ledger.status(id));
            assertTrue(ledger.find(id).orElseThrow().discordMessageId().isPresent());
        }
        bridge.poll();
        clock.advance(Duration.ofHours(2));
        bridge.poll();
        assertEquals(3, poster.posted.size(), "never posted twice");
    }

    @Test void searchesOnlyFromTheCutoffAndIgnoresOlderMail() {
        source.messages.put("old", task("old", "EEE Industrial Training Hunter", clock.instant().minus(Duration.ofDays(2))));
        bridge.poll();
        assertTrue(source.queries.getFirst().endsWith(" after:" + clock.instant().getEpochSecond()), source.queries.getFirst());
        assertEquals(Status.IGNORED, ledger.status("old"), "Gmail may round; the received time is checked too");
        assertTrue(poster.posted.isEmpty());
        clock.advance(Duration.ofDays(30));
        bridge.poll();
        assertTrue(source.queries.getLast().endsWith(" after:" + clock.instant().minus(MailBridge.LOOKBACK).getEpochSecond()),
                "the window never reaches back more than the lookback");
    }

    @Test void unrelatedAndUnclassifiedEmailsAreNeverForwarded() {
        bridge.poll();
        source.messages.put("r1", message("r1", SENDER, "Your receipt", clock.instant(), "Thanks for your payment.", null));
        source.messages.put("u1", message("u1", SENDER, "Your task has finished", clock.instant(), "Secret words", null));
        source.messages.put("b1", message("b1", SENDER, "EEE Industrial Training Hunter + Power Electronics Career Intelligence", clock.instant(), "", null));
        source.messages.put("x1", message("x1", "spam@example.com", "EEE Industrial Training Hunter", clock.instant(), "task", null));
        bridge.poll();
        assertEquals(0, poster.postsTo(CHANNEL));
        assertEquals(Status.IGNORED, ledger.status("r1"));
        assertEquals(Status.IGNORED, ledger.status("x1"));
        assertEquals(Status.UNCLASSIFIED, ledger.status("u1"));
        assertEquals(Status.UNCLASSIFIED, ledger.status("b1"));
        assertEquals(2, poster.postsTo(DIAGNOSTIC), "one content-free notice per unclassified email");
        poster.posted.forEach(p -> assertFalse(p.getValue().getEmbeds().getFirst().getFields().toString().contains("Secret")));
        int fetches = source.fetches;
        bridge.poll();
        assertEquals(fetches, source.fetches, "decided emails aren't fetched again");
    }

    @Test void withoutADiagnosticChannelUnclassifiedEmailsAreOnlyLogged() {
        bridge = bridge(3, Optional.empty());
        bridge.poll();
        source.messages.put("u1", message("u1", SENDER, "Your task has finished", clock.instant(), "", null));
        bridge.poll();
        assertTrue(poster.posted.isEmpty());
        assertEquals(Status.UNCLASSIFIED, ledger.status("u1"));
    }

    @Test void discordRefusalsBackOffAndGiveUpAfterTheLimit() {
        bridge.poll();
        arrive("f1", "EEE Industrial Training Hunter");
        for (int i = 0; i < 3; i++) poster.outcomes.add(CompletableFuture.failedFuture(discordRefusal(50013)));
        bridge.poll();
        Entry first = ledger.find("f1").orElseThrow();
        assertEquals(Status.PENDING, first.status());
        assertEquals(1, first.attempts());
        assertEquals(clock.instant().plus(Duration.ofMinutes(1)), first.nextAttempt());
        bridge.poll();
        assertEquals(1, poster.posted.size(), "not retried before the backoff");
        clock.advance(Duration.ofMinutes(1));
        bridge.poll();
        clock.advance(Duration.ofMinutes(2));
        bridge.poll();
        assertEquals(Status.FAILED, ledger.status("f1"));
        assertEquals(3, poster.posted.size(), "bounded retries");
        clock.advance(Duration.ofHours(5));
        bridge.poll();
        assertEquals(3, poster.posted.size());
    }

    @Test void aRetryAfterARefusalSucceeds() {
        bridge.poll();
        arrive("f2", "EEE Industrial Training Hunter");
        poster.outcomes.add(CompletableFuture.failedFuture(discordRefusal(429)));
        bridge.poll();
        clock.advance(Duration.ofMinutes(1));
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("f2"));
        assertEquals(2, ledger.find("f2").orElseThrow().attempts());
    }

    @Test void anUnconfirmedPostThatLandedIsNotRepeated() {
        bridge.poll();
        arrive("s1", "Power Electronics Career Intelligence");
        poster.outcomes.add(CompletableFuture.failedFuture(new java.io.IOException("connection reset")));
        bridge.poll();
        assertEquals(Status.SENDING, ledger.status("s1"), "outcome unknown");
        // Restart: a new bridge over the same ledger.
        bridge = bridge(3, Optional.of(DIAGNOSTIC));
        poster.history = Optional.of(true);
        clock.advance(Duration.ofMinutes(1));
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("s1"));
        assertEquals(1, poster.posted.size(), "found in the channel, so not posted again");
    }

    @Test void anUnconfirmedPostThatDidntLandIsRetried() {
        bridge.poll();
        arrive("s2", "RTL / Digital Verification Career Tracker");
        poster.outcomes.add(CompletableFuture.failedFuture(new java.io.IOException("connection reset")));
        bridge.poll();
        clock.advance(Duration.ofMinutes(1));
        bridge = bridge(3, Optional.of(DIAGNOSTIC));
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("s2"));
        assertEquals(2, poster.posted.size());
    }

    @Test void aCrashMidPostIsRecoveredOnRestart() {
        bridge.poll();
        arrive("c1", "EEE Industrial Training Hunter");
        // The bot died after saving SENDING and before Discord answered.
        ledger.save(new Entry("c1", Status.SENDING, MailCategory.EEE_TRAINING, clock.instant(), 1,
                clock.instant().plus(Duration.ofMinutes(1)), Optional.empty(), "posting", clock.instant()));
        clock.advance(Duration.ofMinutes(1));
        bridge = bridge(3, Optional.of(DIAGNOSTIC));
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("c1"));
        assertEquals(2, ledger.find("c1").orElseThrow().attempts());
    }

    @Test void aMissingChannelKeepsNotificationsWaitingWithoutUsingAttempts() {
        bridge.poll();
        arrive("w1", "EEE Industrial Training Hunter");
        poster.problem = Optional.of("the bot is not in server 1043515676960247828");
        for (int i = 0; i < 5; i++) { bridge.poll(); clock.advance(Duration.ofMinutes(5)); }
        assertEquals(Status.PENDING, ledger.status("w1"));
        assertEquals(0, ledger.find("w1").orElseThrow().attempts());
        assertTrue(bridge.status().contains("not in server"));
        poster.problem = Optional.empty();
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("w1"));
    }

    @Test void expiredGmailAccessPausesPollingAndWarnsOnce() {
        bridge.poll();
        source.failNext = new MailSourceException(Kind.AUTH, "Google rejected the stored credentials (invalid_grant)");
        bridge.poll();
        assertEquals(1, poster.postsTo(DIAGNOSTIC), "one warning");
        assertTrue(poster.posted.getFirst().getValue().getEmbeds().getFirst().getTitle().contains("Gmail access"));
        int searches = source.searches;
        clock.advance(Duration.ofMinutes(10));
        bridge.poll();
        assertEquals(searches, source.searches, "paused, not hammering Google");
        assertTrue(bridge.status().contains("broken since"));
        clock.advance(MailBridge.AUTH_RETRY);
        source.failNext = new MailSourceException(Kind.AUTH, "Google rejected the stored credentials (invalid_grant)");
        bridge.poll();
        assertEquals(1, poster.postsTo(DIAGNOSTIC), "still just the one warning");
        clock.advance(MailBridge.AUTH_RETRY);
        bridge.poll();
        assertFalse(bridge.status().contains("broken since"), "recovered");
    }

    @Test void transientGmailFailuresRespectRetryAfter() {
        bridge.poll();
        source.failNext = new MailSourceException(Kind.TRANSIENT, "Gmail is busy or unavailable (HTTP 429)",
                Optional.of(clock.instant().plus(Duration.ofMinutes(10))));
        bridge.poll();
        int searches = source.searches;
        clock.advance(Duration.ofMinutes(5));
        bridge.poll();
        assertEquals(searches, source.searches, "waits out Retry-After");
        clock.advance(Duration.ofMinutes(5));
        bridge.poll();
        assertEquals(searches + 1, source.searches);
    }

    @Test void deletedEmailsDontBlockOthers() {
        bridge.poll();
        arrive("d1", "EEE Industrial Training Hunter");
        poster.outcomes.add(CompletableFuture.failedFuture(discordRefusal(500)));
        bridge.poll();
        source.messages.remove("d1");
        arrive("d2", "Power Electronics Career Intelligence");
        clock.advance(Duration.ofMinutes(1));
        bridge.poll();
        assertEquals(Status.FAILED, ledger.status("d1"));
        assertEquals(Status.SENT, ledger.status("d2"));
    }

    @Test void aPostRefusedBeforeSendingIsRetriedNotLeftUncertain() {
        bridge.poll();
        arrive("q1", "EEE Industrial Training Hunter");
        MailPoster throwing = new MailPoster() {
            @Override public Optional<String> problem(String guildId, String channelId) { return Optional.empty(); }
            @Override public CompletableFuture<String> post(String channelId, MessageCreateData message, String nonce) {
                throw new IllegalStateException("missing permission");
            }
            @Override public Optional<Boolean> alreadyPosted(String channelId, String ref) { return Optional.of(false); }
        };
        new MailBridge(config(3, Optional.empty()), source, ledger, throwing, clock).poll();
        assertEquals(Status.PENDING, ledger.status("q1"));
        clock.advance(Duration.ofMinutes(1));
        bridge.poll();
        assertEquals(Status.SENT, ledger.status("q1"));
    }

    @Test void backoffAndNonceShapes() {
        assertEquals(Duration.ofMinutes(1), MailBridge.backoff(1));
        assertEquals(Duration.ofMinutes(8), MailBridge.backoff(4));
        assertEquals(MailBridge.MAX_BACKOFF, MailBridge.backoff(50));
        assertEquals(25, MailBridge.nonce("x".repeat(64)).length());
        assertEquals("mb18f2a", MailBridge.nonce("18f2a"));
    }
}
