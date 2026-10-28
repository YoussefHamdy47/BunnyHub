package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieBroadcastStore.Job;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends owner announcements, one per server, on the freebie sender thread inside the shared background budget, so
 * free-game first posts always go first and Discord rate limits stay with JDA. Same safety as alerts: a fixed nonce
 * and footer reference per server, uncertainty persisted before the request, and a history check (never a blind
 * resend) after an uncertain attempt. Announcements never mention anyone.
 */
final class FreebieBroadcastWorker {
    static final int MAX_IN_FLIGHT = 2;
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);
    private final FreebieRepository repository;
    private final Executor executor;
    private final Clock clock;
    private int own; // Sender thread only.
    private volatile boolean closed;

    FreebieBroadcastWorker(FreebieRepository repository, Executor executor, Clock clock) {
        this.repository = repository; this.executor = executor; this.clock = clock;
    }

    void close() { closed = true; }
    int inFlight() { return own; }
    private FreebieBroadcastStore store() { return repository.broadcasts(); }

    static String reference(String jobId) { return "Bot announcement • ref " + FreebieMessages.nonce("broadcast|" + jobId).substring(0, 12); }
    static String nonce(String jobId) { return FreebieMessages.nonce("broadcast|" + jobId); }

    /** The message exactly as every server receives it (and as the owner previews it). */
    static MessageCreateData message(String title, String body, String reference) {
        MessageEmbed embed = Embeds.of("📢", title).setDescription(body).setFooter(reference).setTimestamp(null).build();
        return new MessageCreateBuilder().setEmbeds(embed).setAllowedMentions(List.of()).mentionRepliedUser(false).build();
    }

    static boolean carries(Message message, String reference) {
        return message.getEmbeds().stream().anyMatch(e -> e.getFooter() != null && reference.equals(e.getFooter().getText()));
    }

    void prepare() { if (!closed && store() != null) store().recover(clock.instant()); }

    boolean claimOne(JDA client, AtomicInteger shared, AtomicInteger background) {
        if (closed || store() == null || own >= MAX_IN_FLIGHT || shared.get() >= FreebieSender.MAX_IN_FLIGHT
                || background.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
        var claimed = store().claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return false;
        own++; shared.incrementAndGet(); background.incrementAndGet();
        Runnable release = () -> { own--; shared.decrementAndGet(); background.decrementAndGet(); };
        boolean handedOff = false;
        try { handedOff = start(client, claimed.get(), release); }
        catch (RuntimeException failure) { log(failure); } // The lease expires and the job is checked again.
        finally { if (!handedOff) release.run(); }
        return true;
    }

    /** @return true when a Discord request is in flight and its completion runs {@code release} */
    boolean start(JDA client, Job job, Runnable release) {
        Instant now = clock.instant();
        var broadcast = store().get(job.broadcastId()).orElse(null);
        if (broadcast == null || !"SENDING".equals(broadcast.state())) { store().cancelled(job, now); return false; }
        if (job.attempts() > FreebieFailure.MAX_ATTEMPTS) { store().failed(job, "attempts exhausted", now); return false; }
        long guildId = Long.parseLong(job.guildId());
        if (client.isUnavailable(guildId)) { store().release(job, now.plus(Duration.ofMinutes(2)), now); return false; }
        var guild = client.getGuildById(guildId);
        if (guild == null) { store().skipped(job, "bot no longer in this server", now); return false; }
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, job.channelId());
        if (channel == null) { store().skipped(job, "channel deleted", now); return false; }
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            store().skipped(job, "missing View Channel, Send Messages or Embed Links", now);
            return false;
        }
        if (job.verifyFirst() && !self.hasPermission(channel, Permission.MESSAGE_HISTORY)) {
            store().skipped(job, "an earlier attempt may have arrived and history cannot be read", now);
            return false;
        }
        String reference = reference(job.id()), selfId = client.getSelfUser().getId();
        CompletableFuture<Optional<Message>> existing;
        try {
            existing = job.verifyFirst()
                    ? channel.getHistory().retrievePast(50).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                        .thenApply(list -> list.stream().filter(m -> m.getAuthor().getId().equals(selfId)).filter(m -> carries(m, reference)).findFirst())
                    : CompletableFuture.completedFuture(Optional.empty());
        } catch (RuntimeException failure) { existing = CompletableFuture.failedFuture(failure); }
        existing.whenComplete((found, error) -> dispatch(release, () -> {
            boolean submitted = false;
            try {
                Instant at = clock.instant();
                if (error != null) store().retry(job, at.plus(Duration.ofMinutes(2)), "history could not be read", at);
                else if (found.isPresent()) store().sent(job, found.get().getId(), at);
                else if (!"SENDING".equals(store().get(job.broadcastId()).map(FreebieBroadcastStore.Broadcast::state).orElse(""))) store().cancelled(job, at);
                else if (repository.sendingPaused()) store().release(job, at.plusSeconds(120), at);
                else if (!closed && store().submitting(job, at)) {
                    var request = channel.sendMessage(message(broadcast.title(), broadcast.body(), reference)).setNonce(nonce(job.id()))
                            .timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit();
                    submitted = true;
                    request.whenComplete((message, sendError) -> dispatch(release, () -> {
                        try { outcome(job, message, sendError); }
                        catch (RuntimeException storage) { log(storage); }
                        finally { release.run(); }
                    }));
                }
            } catch (RuntimeException failure) { log(failure); }
            finally { if (!submitted) release.run(); }
        }));
        return true;
    }

    private void outcome(Job job, Message message, Throwable error) {
        Instant now = clock.instant();
        if (error == null) { store().sent(job, message.getId(), now); return; }
        var failure = FreebieFailure.classify(error);
        if (failure.retryable() || failure == FreebieFailure.TRANSIENT) {
            Duration delay = FreebieFailure.backoff(job.attempts());
            // The request may have arrived: the next attempt checks history first (verifyFirst is already saved).
            if (delay != null) store().retry(job, now.plus(delay), failure.name().toLowerCase(), now);
            else store().failed(job, FailureDiagnostics.describe(error), now);
            return;
        }
        store().failed(job, failure.name().toLowerCase(), now);
    }

    private void dispatch(Runnable release, Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); }
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Announcement delivery will recover | " + FailureDiagnostics.describe(failure));
    }
}
