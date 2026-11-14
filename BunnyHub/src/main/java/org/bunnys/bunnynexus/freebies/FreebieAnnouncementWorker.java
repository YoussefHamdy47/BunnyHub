package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
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
 * Edits posted announcement pages when something on them changed: a member joined, ended, was withdrawn or was
 * corrected. One leased refresh per page at a time rebuilds the whole message from current state, so concurrent
 * changes to different games cannot overwrite each other. Editing never posts or pings; Discord propagates edits of
 * a published message to following channels. Runs inside the sender's shared background budget.
 */
final class FreebieAnnouncementWorker {
    static final int MAX_IN_FLIGHT = 2;
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 8;
    private final FreebieRepository repository;
    private final FreebieFeatures features;
    private final Executor executor;
    private final Clock clock;
    private static final Duration ORPHAN_SCAN = Duration.ofSeconds(30);
    private int own; // Sender thread only.
    private Instant nextOrphanScan = Instant.MIN; // Sender thread only.
    private volatile boolean closed;

    FreebieAnnouncementWorker(FreebieRepository repository, FreebieFeatures features, Executor executor, Clock clock) {
        this.repository = repository; this.features = features; this.executor = executor; this.clock = clock;
    }

    void close() { closed = true; }
    int inFlight() { return own; }

    boolean claimOne(JDA client, AtomicInteger shared, AtomicInteger background) {
        if (closed || own >= MAX_IN_FLIGHT || shared.get() >= FreebieSender.MAX_IN_FLIGHT
                || background.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
        Instant now = clock.instant();
        var claimed = repository.announcements().claimRefresh(now, LEASE);
        // No edit is due: look, at most every ORPHAN_SCAN, for an uncertain page that no row can recover any more.
        Optional<FreebieAnnouncementStore.Orphan> orphan = Optional.empty();
        if (claimed.isEmpty()) {
            if (now.isBefore(nextOrphanScan)) return false;
            orphan = repository.announcements().claimOrphan(now, LEASE);
            if (orphan == null || orphan.isEmpty()) { nextOrphanScan = now.plus(ORPHAN_SCAN); return false; }
        }
        own++; shared.incrementAndGet(); background.incrementAndGet();
        Runnable release = () -> { own--; shared.decrementAndGet(); background.decrementAndGet(); };
        boolean handedOff = false;
        try { handedOff = claimed.isPresent() ? start(client, claimed.get(), release) : reconcile(client, orphan.get(), release); }
        catch (RuntimeException failure) { log(failure); }
        finally { if (!handedOff) release.run(); }
        return true;
    }

    /**
     * An uncertain page whose rows are all final: history decides. Found: recorded as sent and due for a refresh, so
     * ended or withdrawn games lose their claim. Not found: withdrawn. Unreadable history leaves it uncertain for a
     * later check. Nothing is ever posted here.
     */
    private boolean reconcile(JDA client, FreebieAnnouncementStore.Orphan job, Runnable release) {
        Instant now = clock.instant();
        var store = repository.announcements();
        var page = job.page();
        String id = page.getString("_id"), channelId = page.getString("channelId");
        long guildId = Long.parseLong(page.getString("guildId"));
        if (client.isUnavailable(guildId)) { store.reconcileLater(id, job.token(), now.plus(Duration.ofMinutes(2)), now); return false; }
        var guild = client.getGuildById(guildId);
        var channel = guild == null ? null : guild.getChannelById(StandardGuildMessageChannel.class, channelId);
        // The bot left or the channel is gone: no message can be read or edited any more.
        if (channel == null) { store.cancel(id, job.token(), now); return false; }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
            store.reconcileLater(id, job.token(), now.plus(Duration.ofMinutes(30)), now);
            return false;
        }
        String selfId = client.getSelfUser().getId();
        List<String> references = page.getList("deliveries", String.class, List.of()).stream().map(FreebieMessages::reference).toList();
        CompletableFuture<Optional<Message>> existing;
        try {
            existing = channel.getHistory().retrievePast(50).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenApply(messages -> messages.stream().filter(m -> m.getAuthor().getId().equals(selfId))
                            .filter(m -> references.stream().anyMatch(r -> FreebieMessages.carriesReference(m, r))).findFirst());
        } catch (RuntimeException failure) { existing = CompletableFuture.failedFuture(failure); }
        existing.whenComplete((found, error) -> dispatch(release, () -> {
            try {
                Instant at = clock.instant();
                if (error != null) store.reconcileLater(id, job.token(), at.plus(Duration.ofMinutes(2)), at);
                else if (found.isPresent()) store.reconciled(id, job.token(), found.get().getId(), features.publishes(channelId), at);
                else store.cancel(id, job.token(), at);
            } catch (RuntimeException storage) { log(storage); }
            finally { release.run(); }
        }));
        return true;
    }

    private boolean start(JDA client, FreebieAnnouncementStore.Refresh job, Runnable release) {
        Instant now = clock.instant();
        var store = repository.announcements();
        var page = job.page();
        if (page.getInteger("refreshAttempts", 1) > MAX_ATTEMPTS) { store.refreshed(job, "attempts exhausted", now); return false; }
        long guildId = Long.parseLong(page.getString("guildId"));
        if (client.isUnavailable(guildId)) { store.refreshRetry(job, now.plus(Duration.ofMinutes(2)), "guild unavailable"); return false; }
        var guild = client.getGuildById(guildId);
        if (guild == null) { store.refreshed(job, "bot removed", now); return false; }
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, page.getString("channelId"));
        if (channel == null) { store.refreshed(job, "channel missing", now); return false; }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
            store.refreshRetry(job, now.plus(Duration.ofMinutes(30)), "missing access");
            return false;
        }
        String selfId = client.getSelfUser().getId();
        List<String> references = page.getList("deliveries", String.class, List.of()).stream().map(FreebieMessages::reference).toList();
        CompletableFuture<String> work;
        try {
            work = channel.retrieveMessageById(page.getString("messageId")).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenComposeAsync(message -> edit(job, message, selfId, references), executor);
        } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        work.whenComplete((outcome, error) -> dispatch(release, () -> {
            try { outcome(job, outcome, error); }
            catch (RuntimeException storage) { log(storage); }
            finally { release.run(); }
        }));
        return true;
    }

    private CompletableFuture<String> edit(FreebieAnnouncementStore.Refresh job, Message message, String selfId, List<String> references) {
        if (!message.getAuthor().getId().equals(selfId) || references.stream().noneMatch(r -> FreebieMessages.carriesReference(message, r)))
            return CompletableFuture.completedFuture("not our message");
        Instant now = clock.instant();
        var members = FreebieAnnouncementSender.members(repository, repository.announcements().get(job.page().getString("_id"))
                .orElse(job.page()), now);
        if (members.isEmpty()) return CompletableFuture.completedFuture("no members left");
        if (closed || !repository.announcements().ownsRefresh(job, now)) return CompletableFuture.failedFuture(new CancellationException("lease lost"));
        return message.editMessage(FreebieAnnouncementCards.edit(members, features.checkout(), now, Optional.ofNullable(message.getContentRaw())))
                .timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit().thenApply(edited -> "edited");
    }

    private void outcome(FreebieAnnouncementStore.Refresh job, String outcome, Throwable error) {
        Instant now = clock.instant();
        var store = repository.announcements();
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause == null) { store.refreshed(job, outcome, now); return; }
        if (cause instanceof CancellationException) return;
        var verdict = FreebieLifecycle.classify(cause);
        switch (verdict.outcome()) {
            case SKIP -> store.refreshed(job, verdict.skip().name(), now);
            case FAIL -> store.refreshed(job, "rejected: " + FailureDiagnostics.describe(cause), now);
            case RETRY -> {
                var delay = FreebieLifecycle.retryDelay(job.page().getInteger("refreshAttempts", 1));
                if (delay.isEmpty()) store.refreshed(job, "attempts exhausted", now);
                else store.refreshRetry(job, now.plus(delay.get()), "retrying");
            }
        }
    }

    private void dispatch(Runnable release, Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); }
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Announcement edit will recover | " + FailureDiagnostics.describe(failure));
    }
}
