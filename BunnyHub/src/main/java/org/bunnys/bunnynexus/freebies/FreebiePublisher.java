package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automatic publishing of announcement pages in allowlisted Announcement Channels (Discord's Crosspost Message
 * endpoint, JDA {@link Message#crosspost()}). Inside the sender's shared background budget, on its thread.
 *
 * <p>Never sends or resends a giveaway message. Before publishing it rechecks the allowlist, the channel type and
 * permissions, that an earlier page of the same announcement is finished, that at least one game on the page is
 * still approved and live, and that the latest edit (which removes ended games) has been applied. An uncertain or
 * "already crossposted" result is reconciled from the original message's CROSSPOSTED flag; IS_CROSSPOST marks a
 * follower's copy and is never treated as published. Publishes are budgeted below Discord's documented ten per hour
 * per channel, counting continuation pages; Discord's own Retry-After waits are handled by JDA's rate limiter and a
 * request that outlasts our timeout is reconciled, not repeated blindly.
 */
final class FreebiePublisher {
    static final int MAX_IN_FLIGHT = 1;
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);
    static final Duration RECHECK = Duration.ofMinutes(30), SHORT = Duration.ofSeconds(30), UNCERTAIN = Duration.ofMinutes(10);
    private final FreebieRepository repository;
    private final FreebieFeatures features;
    private final Executor executor;
    private final Clock clock;
    private int own; // Sender thread only.
    private Instant nextPrepare = Instant.MIN; // Sender thread only.
    private volatile boolean closed;

    FreebiePublisher(FreebieRepository repository, FreebieFeatures features, Executor executor, Clock clock) {
        this.repository = repository; this.features = features; this.executor = executor; this.clock = clock;
    }

    void close() { closed = true; }
    int inFlight() { return own; }
    private FreebiePublicationStore jobs() { return repository.publications(); }

    /**
     * Once per tick: recovers crashed leases, plans jobs for newly sent pages (idempotent insert, then clear the
     * marker), and stops pending publications when the feature or a channel is no longer enabled.
     */
    void prepare() {
        Instant now = clock.instant();
        if (closed || now.isBefore(nextPrepare)) return;
        nextPrepare = now.plusSeconds(5);
        jobs().recover(now);
        if (!features.publishEnabled()) {
            jobs().cancelOpen(Optional.empty(), now);
            repository.announcements().dropPublishPending(Optional.empty());
            return;
        }
        jobs().cancelOpen(Optional.of(features.publishChannels()), now);
        repository.announcements().dropPublishPending(Optional.of(features.publishChannels()));
        for (var page : repository.announcements().publishPending(20)) {
            jobs().plan(page, now);
            repository.announcements().publishPlanned(page.getString("_id"));
        }
    }

    boolean claimOne(JDA client, AtomicInteger shared, AtomicInteger background) {
        if (closed || !features.publishEnabled() || own >= MAX_IN_FLIGHT || shared.get() >= FreebieSender.MAX_IN_FLIGHT
                || background.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
        var claimed = jobs().claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return false;
        own++; shared.incrementAndGet(); background.incrementAndGet();
        Runnable release = () -> { own--; shared.decrementAndGet(); background.decrementAndGet(); };
        boolean handedOff = false;
        try { handedOff = start(client, claimed.get(), release); }
        catch (RuntimeException failure) { log(failure); } // The lease expires and the job is reconciled.
        finally { if (!handedOff) release.run(); }
        return true;
    }

    /** @return true when Discord work is in flight and its completion runs {@code release} */
    boolean start(JDA client, FreebiePublicationStore.Job job, Runnable release) {
        Instant now = clock.instant();
        if (job.attempts() > FreebiePublicationStore.MAX_ATTEMPTS) { jobs().failed(job, "attempts exhausted", now); return false; }
        if (!features.publishes(job.channelId())) { jobs().cancelled(job, "publishing disabled for this channel", now); return false; }
        var page = repository.announcements().get(job.pageId()).orElse(null);
        if (page == null) { jobs().skipped(job, "announcement record missing", now); return false; }
        var members = FreebieAnnouncementSender.members(repository, page, now);
        if (members.stream().noneMatch(m -> m.status() == FreebieAnnouncementCards.Status.LIVE)) {
            jobs().skipped(job, "no game on this page is still approved and live", now);
            return false;
        }
        if (jobs().earlierPageOpen(job)) { jobs().delay(job, "waiting for the previous page", now.plus(SHORT), now); return false; }
        if (!repository.announcements().settled(page)) { jobs().delay(job, "waiting for the latest edit", now.plus(SHORT), now); return false; }
        // The count includes this job itself (PUBLISHING) and every possibly-landed uncertain one.
        if (!job.uncertain() && jobs().usedLastHour(job.channelId(), now) - 1 >= FreebiePublicationStore.HOURLY_LIMIT) {
            jobs().delay(job, "hourly publish limit", jobs().budgetFreesAt(job.channelId(), now), now);
            return false;
        }
        long guildId = Long.parseLong(job.guildId());
        if (client.isUnavailable(guildId)) { jobs().delay(job, "guild unavailable", now.plus(Duration.ofMinutes(2)), now); return false; }
        var guild = client.getGuildById(guildId);
        if (guild == null) { jobs().skipped(job, "bot removed", now); return false; }
        NewsChannel channel = guild.getNewsChannelById(job.channelId());
        if (channel == null) {
            if (guild.getGuildChannelById(job.channelId()) == null) jobs().skipped(job, "channel deleted", now);
            else jobs().blocked(job, "not an Announcement Channel", now.plus(RECHECK), now);
            return false;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)) {
            jobs().blocked(job, "missing View Channel, Send Messages or Read Message History", now.plus(RECHECK), now);
            return false;
        }
        String selfId = client.getSelfUser().getId();
        List<String> references = page.getList("deliveries", String.class, List.of()).stream().map(FreebieMessages::reference).toList();
        CompletableFuture<Result> work;
        try {
            work = channel.retrieveMessageById(job.messageId()).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenComposeAsync(message -> publish(job, message, selfId, references), executor);
        } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        work.whenComplete((result, error) -> dispatch(release, () -> {
            try { outcome(job, result, error); }
            catch (RuntimeException storage) { log(storage); }
            finally { release.run(); }
        }));
        return true;
    }

    enum Result { PUBLISHED, RECONCILED, NOT_OURS, LEASE_LOST }

    private CompletableFuture<Result> publish(FreebiePublicationStore.Job job, Message message, String selfId, List<String> references) {
        if (!message.getAuthor().getId().equals(selfId) || references.stream().noneMatch(r -> FreebieMessages.carriesReference(message, r)))
            return CompletableFuture.completedFuture(Result.NOT_OURS);
        // CROSSPOSTED: this original was already published (by us before a crash, or manually). Never publish twice.
        if (alreadyPublished(message)) return CompletableFuture.completedFuture(Result.RECONCILED);
        Instant now = clock.instant();
        if (closed || !jobs().submitting(job, now)) return CompletableFuture.completedFuture(Result.LEASE_LOST);
        return message.crosspost().timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit().thenApply(published -> Result.PUBLISHED);
    }

    /** The source message's own flag. A follower's copy carries IS_CROSSPOST instead, which is not this. */
    static boolean alreadyPublished(Message message) { return message.getFlags().contains(Message.MessageFlag.CROSSPOSTED); }

    private void outcome(FreebiePublicationStore.Job job, Result result, Throwable error) {
        Instant now = clock.instant();
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause == null) {
            switch (result) {
                case PUBLISHED -> jobs().published(job, false, now);
                case RECONCILED -> jobs().published(job, true, now);
                case NOT_OURS -> jobs().skipped(job, "the saved message is not this announcement", now);
                case LEASE_LOST -> { /* Another attempt owns the job now. */ }
            }
            return;
        }
        if (cause instanceof InsufficientPermissionException) {
            jobs().blocked(job, "missing permission: " + ((InsufficientPermissionException) cause).getPermission().getName(), now.plus(RECHECK), now);
            return;
        }
        if (cause instanceof ErrorResponseException response) {
            ErrorResponse code = response.getErrorResponse();
            if (code == ErrorResponse.ALREADY_CROSSPOSTED) { jobs().published(job, true, now); return; }
            if (code == ErrorResponse.UNKNOWN_MESSAGE) { jobs().skipped(job, "message deleted", now); return; }
            if (code == ErrorResponse.UNKNOWN_CHANNEL) { jobs().skipped(job, "channel deleted", now); return; }
            if (code == ErrorResponse.MISSING_PERMISSIONS || code == ErrorResponse.MISSING_ACCESS) {
                jobs().blocked(job, "Discord refused: missing permission or access", now.plus(RECHECK), now);
                return;
            }
            if (code == ErrorResponse.ANNOUNCEMENT_RATE_LIMIT) {
                jobs().delay(job, "Discord announcement rate limit", now.plus(Duration.ofHours(1)), now);
                return;
            }
            if (!response.isServerError() && response.getErrorCode() > 0 && response.getErrorCode() < 130000) {
                jobs().failed(job, "rejected: " + FailureDiagnostics.describe(cause), now);
                return;
            }
        }
        // Timeouts, 5xx and long rate-limit waits: the request may have landed. Read the flag before trying again.
        jobs().uncertain(job, "outcome unknown: " + cause.getClass().getSimpleName(), now.plus(UNCERTAIN), now);
    }

    private void dispatch(Runnable release, Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); }
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Publishing will recover | " + FailureDiagnostics.describe(failure));
    }
}
