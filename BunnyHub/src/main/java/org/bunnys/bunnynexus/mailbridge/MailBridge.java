package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Entry;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Status;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Polls Gmail for task notifications and posts them to Discord.
 *
 * <p>Each poll searches a bounded window (never before the first-start cutoff, never more than {@link #LOOKBACK}
 * ago), records every new message in the {@link MailLedger}, then posts whatever is due. An entry is only marked
 * {@code SENT} after Discord returns the message ID. Before a post, the entry is saved as {@code SENDING}; if the
 * answer never comes (timeout or crash), the next attempt first looks for the post in the channel by its ref, so a
 * post that did land is not repeated. That makes delivery at-least-once with duplicates very unlikely, not
 * exactly-once: if the channel history can't be read, the bridge posts again rather than risk losing a notice.
 *
 * <p>Logs carry Gmail message IDs, categories and reasons only, never subjects, bodies, tokens or URLs.
 */
public final class MailBridge {
    static final Duration LOOKBACK = Duration.ofDays(14);
    static final int MAX_NEW_PER_POLL = 50;
    static final int MAX_DUE_PER_POLL = 20;
    static final Duration POST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration AUTH_RETRY = Duration.ofMinutes(30);
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private static volatile MailBridge instance;
    private static volatile String disabledReason = "not started";

    private final MailBridgeConfig config;
    private final MailSource source;
    private final MailLedger ledger;
    private final MailPoster poster;
    private final MailClassifier classifier;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    // Health, read by /mail-bridge status. Guarded by this.
    private Instant lastPoll, lastSuccess, blockedUntil = Instant.EPOCH, authProblemSince;
    private String lastError = "";
    private int transientFailures;
    private boolean authNoticePosted;
    private String lastChannelProblem = "";

    MailBridge(MailBridgeConfig config, MailSource source, MailLedger ledger, MailPoster poster, Clock clock) {
        this.config = config;
        this.source = source;
        this.ledger = ledger;
        this.poster = poster;
        this.classifier = MailClassifier.from(config);
        this.clock = clock;
    }

    public static Optional<MailBridge> current() { return Optional.ofNullable(instance); }
    public static String disabledReason() { return disabledReason; }

    /** Starts polling if configured; otherwise logs why and stays off. Never throws. */
    public static synchronized void start(MailBridgeConfig config, MailSource source, MailLedger ledger, MailPoster poster) {
        if (instance != null) return;
        var bridge = new MailBridge(config, source, ledger, poster, Clock.systemUTC());
        bridge.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mail-bridge");
            thread.setDaemon(true);
            return thread;
        });
        bridge.scheduler.scheduleWithFixedDelay(bridge::pollSafely, 30, config.pollInterval().toSeconds(), TimeUnit.SECONDS);
        instance = bridge;
        BunnyLog.success("[MailBridge] Started. Posting to channel " + config.channelId() + ", checking Gmail every "
                + config.pollInterval().toMinutes() + " min.");
    }

    public static synchronized void disable(String reason) {
        disabledReason = reason;
        BunnyLog.warning("[MailBridge] Disabled: " + reason);
    }

    /** Lets an in-flight poll finish (up to 20 seconds); entries it didn't reach stay due for the next start. */
    public static synchronized void shutdown() {
        MailBridge bridge = instance;
        instance = null;
        disabledReason = "stopped";
        if (bridge == null || bridge.scheduler == null) return;
        bridge.scheduler.shutdown();
        try {
            if (!bridge.scheduler.awaitTermination(20, TimeUnit.SECONDS)) bridge.scheduler.shutdownNow();
        } catch (InterruptedException interrupted) {
            bridge.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public MailBridgeConfig config() { return config; }
    public MailSource source() { return source; }
    public MailLedger ledger() { return ledger; }
    public MailPoster poster() { return poster; }
    public MailClassifier classifier() { return classifier; }

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable unexpected) {
            synchronized (this) { lastError = "unexpected: " + unexpected.getClass().getSimpleName(); }
            BunnyLog.error("[MailBridge] Poll failed | " + FailureDiagnostics.describe(unexpected));
        }
    }

    /** One poll: find new messages, then post what's due. Safe to call repeatedly; does nothing while backing off. */
    synchronized void poll() {
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) return;
        lastPoll = now;
        try {
            Instant cutoff = ledger.cutoff(now);
            Instant from = cutoff.isAfter(now.minus(LOOKBACK)) ? cutoff : now.minus(LOOKBACK);
            List<String> ids = new ArrayList<>(source.search(config.query() + " after:" + from.getEpochSecond(), MAX_NEW_PER_POLL));
            Collections.reverse(ids); // Gmail lists newest first; record oldest first.
            for (String id : ids) if (ledger.find(id).isEmpty()) intake(id, from, now);
            deliverDue(now);
            lastSuccess = now;
            transientFailures = 0;
            if (authProblemSince != null) BunnyLog.success("[MailBridge] Gmail access works again.");
            authProblemSince = null;
            authNoticePosted = false;
            if (lastError.startsWith("Gmail")) lastError = "";
        } catch (MailSourceException failure) {
            sourceFailed(failure, now);
        }
    }

    private void intake(String id, Instant from, Instant now) throws MailSourceException {
        MailMessage mail;
        try {
            mail = MailParser.parse(source.fetch(id));
        } catch (MailSourceException failure) {
            if (failure.kind() != MailSourceException.Kind.PERMANENT) throw failure;
            ledger.save(entry(id, Status.IGNORED, MailCategory.UNKNOWN, now, now, "unreadable: " + failure.getMessage()));
            return;
        } catch (IllegalArgumentException unusable) {
            ledger.save(entry(id, Status.IGNORED, MailCategory.UNKNOWN, now, now, "unparseable message"));
            return;
        }
        if (mail.received().isBefore(from)) {
            ledger.save(entry(id, Status.IGNORED, MailCategory.UNKNOWN, mail.received(), now, "before the cutoff"));
            return;
        }
        var result = classifier.classify(mail);
        switch (result.outcome()) {
            case MATCHED -> {
                ledger.save(entry(id, Status.PENDING, result.category(), mail.received(), now, result.reason()));
                BunnyLog.info("[MailBridge] " + id + " recognised as " + result.category() + " (" + result.reason() + ").");
            }
            case UNRELATED -> ledger.save(entry(id, Status.IGNORED, MailCategory.UNKNOWN, mail.received(), now, result.reason()));
            case UNMATCHED, AMBIGUOUS -> {
                ledger.save(entry(id, Status.UNCLASSIFIED, MailCategory.UNKNOWN, mail.received(), now, result.reason()));
                BunnyLog.warning("[MailBridge] " + id + " looks like a task email but wasn't forwarded: " + result.reason() + ".");
                config.diagnosticChannelId().ifPresent(channel -> postQuietly(channel, MailBridgeCards.unclassified(mail, result.reason()), id));
            }
        }
    }

    private void deliverDue(Instant now) throws MailSourceException {
        List<Entry> due = ledger.due(now, MAX_DUE_PER_POLL);
        if (due.isEmpty()) return;
        Optional<String> problem = poster.problem(config.guildId(), config.channelId());
        if (problem.isPresent()) {
            // Not the email's fault: keep the entries waiting without using up their attempts.
            if (!problem.get().equals(lastChannelProblem))
                BunnyLog.warning("[MailBridge] Can't post yet: " + problem.get() + ". " + due.size() + " notification(s) waiting.");
            lastChannelProblem = problem.get();
            lastError = "Discord: " + problem.get();
            return;
        }
        lastChannelProblem = "";
        for (Entry entry : due) deliver(entry, now);
    }

    private void deliver(Entry entry, Instant now) throws MailSourceException {
        String ref = MailBridgeCards.REF_PREFIX + entry.id();
        if (entry.status() == Status.SENDING && poster.alreadyPosted(config.channelId(), ref).orElse(false)) {
            ledger.save(entry.with(Status.SENT, entry.attempts(), now, entry.discordMessageId(), "found in channel after an unconfirmed post", now));
            return;
        }
        if (entry.attempts() >= config.maxAttempts()) {
            ledger.save(entry.with(Status.FAILED, entry.attempts(), now, Optional.empty(), "gave up after " + entry.attempts() + " attempts", now));
            BunnyLog.error("[MailBridge] " + entry.id() + " was not delivered after " + entry.attempts() + " attempts.");
            return;
        }
        MailMessage mail;
        try {
            mail = MailParser.parse(source.fetch(entry.id()));
        } catch (MailSourceException failure) {
            if (failure.kind() != MailSourceException.Kind.PERMANENT) throw failure;
            ledger.save(entry.with(Status.FAILED, entry.attempts(), now, Optional.empty(), "email no longer readable", now));
            return;
        }
        MessageCreateData message = MailBridgeCards.notification(mail, entry.category(), config.mentionUserId());
        int attempt = entry.attempts() + 1;
        // Saved before posting: if the bot dies mid-post, the restart sees SENDING and checks the channel first.
        ledger.save(entry.with(Status.SENDING, attempt, now.plus(backoff(attempt)), Optional.empty(), "posting", now));
        CompletableFuture<String> posting;
        try {
            posting = poster.post(config.channelId(), message, nonce(entry.id()));
        } catch (RuntimeException rejected) {
            // Refused before anything was sent (for example a permission check), so it's safe to retry later.
            boolean last = attempt >= config.maxAttempts();
            ledger.save(entry.with(last ? Status.FAILED : Status.PENDING, attempt, now.plus(backoff(attempt)), Optional.empty(),
                    "not sent: " + rejected.getClass().getSimpleName(), now));
            lastError = "Post rejected before sending: " + rejected.getClass().getSimpleName();
            return;
        }
        try {
            String messageId = posting.get(POST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ledger.save(entry.with(Status.SENT, attempt, now, Optional.of(messageId), "posted", clock.instant()));
            BunnyLog.success("[MailBridge] Posted " + entry.category() + " notification for " + entry.id() + ".");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof ErrorResponseException discord) {
                boolean last = attempt >= config.maxAttempts();
                ledger.save(entry.with(last ? Status.FAILED : Status.PENDING, attempt, now.plus(backoff(attempt)), Optional.empty(),
                        "Discord refused: " + discord.getErrorCode(), now));
                lastError = "Discord refused a post (error " + discord.getErrorCode() + ")";
                BunnyLog.warning("[MailBridge] Discord refused " + entry.id() + " (error " + discord.getErrorCode() + "), attempt " + attempt + ".");
            } else {
                lastError = "Discord post outcome unknown";
                BunnyLog.warning("[MailBridge] Post for " + entry.id() + " failed without a Discord answer; will check before retrying.");
            }
        } catch (TimeoutException slow) {
            lastError = "Discord post timed out";
            BunnyLog.warning("[MailBridge] Post for " + entry.id() + " timed out; will check the channel before retrying.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void sourceFailed(MailSourceException failure, Instant now) {
        lastError = "Gmail: " + failure.getMessage();
        if (failure.kind() == MailSourceException.Kind.AUTH) {
            if (authProblemSince == null) {
                authProblemSince = now;
                BunnyLog.error("[MailBridge] " + failure.getMessage() + ". Re-authorise Gmail; retrying every "
                        + AUTH_RETRY.toMinutes() + " min until then.");
            }
            blockedUntil = now.plus(AUTH_RETRY);
            if (!authNoticePosted) {
                authNoticePosted = true;
                postQuietly(config.diagnosticChannelId().orElse(config.channelId()),
                        MailBridgeCards.authProblem(config.mentionUserId(), failure.getMessage(), authProblemSince), "auth");
            }
            return;
        }
        transientFailures++;
        Instant backoffUntil = now.plus(backoff(transientFailures));
        blockedUntil = failure.retryAfter().filter(backoffUntil::isBefore).orElse(backoffUntil);
        BunnyLog.warning("[MailBridge] " + failure.getMessage() + "; next try at " + blockedUntil + ".");
    }

    /** One-off notices: a single attempt, logged on failure, never blocking the poll for long. */
    private void postQuietly(String channelId, MessageCreateData message, String what) {
        try {
            if (poster.problem(config.guildId(), channelId).isPresent() && !channelId.equals(config.channelId())) {
                BunnyLog.warning("[MailBridge] Diagnostic channel unavailable; notice for " + what + " only logged.");
                return;
            }
            poster.post(channelId, message, nonce("n" + what)).get(POST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failed) {
            BunnyLog.warning("[MailBridge] Couldn't post the notice for " + what + " | " + FailureDiagnostics.describe(failed));
        }
    }

    /** 1, 2, 4 … minutes, capped at an hour. */
    static Duration backoff(int attempt) {
        long minutes = 1L << Math.min(Math.max(attempt - 1, 0), 6);
        Duration wait = Duration.ofMinutes(minutes);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    /** Discord nonces are at most 25 characters. */
    static String nonce(String id) {
        String value = "mb" + id;
        return value.length() <= 25 ? value : value.substring(0, 25);
    }

    private static Entry entry(String id, Status status, MailCategory category, Instant received, Instant now, String note) {
        return new Entry(id, status, category, received, 0, now, Optional.empty(), note, now);
    }

    // ------------------------------------------------------------------ owner tools

    /** Health summary for {@code /mail-bridge status}. */
    public synchronized String status() {
        StringBuilder text = new StringBuilder();
        text.append("**Channel:** <#").append(config.channelId()).append(">\n");
        text.append("**Checks Gmail:** every ").append(config.pollInterval().toMinutes()).append(" min\n");
        text.append("**Last check:** ").append(when(lastPoll)).append("\n");
        text.append("**Last successful check:** ").append(when(lastSuccess)).append("\n");
        if (authProblemSince != null) text.append("**Gmail access:** broken since ").append(when(authProblemSince)).append("\n");
        if (clock.instant().isBefore(blockedUntil)) text.append("**Paused until:** ").append(when(blockedUntil)).append("\n");
        poster.problem(config.guildId(), config.channelId()).ifPresent(problem -> text.append("**Discord:** ").append(problem).append("\n"));
        if (!lastError.isEmpty()) text.append("**Last problem:** ").append(MailBridgeCards.untrusted(lastError, 300)).append("\n");
        try {
            var counts = ledger.counts();
            text.append("**Emails:** ");
            StringJoiner parts = new StringJoiner(" · ");
            counts.forEach((status, count) -> { if (count > 0) parts.add(status.name().toLowerCase(Locale.ROOT) + " " + count); });
            text.append(parts.length() == 0 ? "none yet" : parts.toString());
        } catch (RuntimeException unreadable) {
            text.append("**Emails:** database unavailable");
        }
        return text.toString();
    }

    /** Asks for a poll as soon as possible, ignoring any backoff. */
    public synchronized void pollNow() {
        blockedUntil = Instant.EPOCH;
        if (scheduler != null) scheduler.execute(this::pollSafely);
    }

    private static String when(Instant instant) {
        return instant == null ? "never" : "<t:" + instant.getEpochSecond() + ":R>";
    }
}
