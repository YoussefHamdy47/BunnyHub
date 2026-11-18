package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Recoverable notices on the dedicated sender worker, with at most two requests in flight. */
final class FreebieNotifier {
    private final FreebieRepository repository;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final Executor executor;
    private int inFlight;
    private volatile boolean closed;

    FreebieNotifier(FreebieRepository repository, Supplier<JDA> jda, Clock clock, Executor executor) {
        this.repository = repository; this.jda = jda; this.clock = clock; this.executor = executor;
    }
    /** The wait after a tick that found a notice, and the waits after each further tick in a row that found none. */
    static final Duration BUSY_DELAY = Duration.ofSeconds(5);
    static final List<Duration> IDLE_DELAYS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(60));
    private volatile FreebieIdleBackoff pacing;

    /** Starts the self-rescheduling tick; {@code scheduler} must be the sender thread this notifier works on. */
    void start(ScheduledExecutorService scheduler, Duration initialDelay) {
        var loop = new FreebieIdleBackoff(scheduler, "notices", BUSY_DELAY, IDLE_DELAYS, this::tick);
        pacing = loop;
        loop.start(initialDelay);
    }

    /** A notice was queued (a failed alert, a deleted channel or role): one tick at once. Safe from any thread. */
    void wake() {
        var loop = pacing;
        if (loop != null) loop.wake();
    }

    void close() {
        closed = true;
        var loop = pacing;
        if (loop != null) loop.close();
    }

    void deliveryFailed(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure) {
        // markFailed already persisted noticePending atomically with the failure; housekeeping survives a crash here.
        if (!failure.notifyServer() || closed) return;
        repository.notices().recover(clock.instant()); // Turns the failure just saved into a queued notice.
        tick();
    }

    /**
     * Claims up to two due notices, on the sender thread. Interrupted enqueues and reconciliations are replayed by
     * housekeeping ({@link FreebieNoticeStore#recover}), not here.
     *
     * @return false only after a connected tick that found nothing and has nothing in flight
     */
    boolean tick() {
        if (closed) return false;
        var client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return true; // Nothing was read.
        boolean claimedAny = false;
        for (int admitted = 0; admitted < 2 && inFlight < 2; admitted++) {
            var claimed = repository.notices().claim(clock.instant());
            if (claimed.isEmpty()) break;
            claimedAny = true;
            inFlight++;
            start(client, claimed.get());
        }
        return claimedAny || inFlight > 0;
    }

    private void start(JDA client, FreebieNoticeStore.Claim job) {
        try {
            var guild = client.getGuildById(job.guildId());
            if (guild == null && job.change().isPresent() && !client.isUnavailable(Long.parseLong(job.guildId()))) {
                // Connected and the server is gone: the bot was removed, so there is nobody left to tell.
                repository.notices().drop(job, clock.instant());
                inFlight--;
                return;
            }
            var offer = job.change().isPresent() ? null : repository.offer(job.offerId()).orElse(null);
            if (guild == null || (job.change().isEmpty() && offer == null)) throw new IllegalStateException("notice guild or offer unavailable");
            String route = "SYSTEM".equals(job.route()) ? "SYSTEM" : "DM";
            String recipient = job.recipientId() == null ? guild.getOwnerId() : job.recipientId();
            CompletableFuture<? extends MessageChannel> channel;
            if (route.equals("SYSTEM")) {
                var fallback = job.channelId() == null ? guild.getSystemChannel()
                        : guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, job.channelId());
                if (fallback == null || !fallback.canTalk() || fallback.getId().equals(job.failedChannel()))
                    throw new IllegalStateException("no usable notice fallback channel");
                channel = CompletableFuture.completedFuture(fallback);
            } else channel = client.openPrivateChannelById(recipient).timeout(45, TimeUnit.SECONDS).submit();
            channel.thenComposeAsync(destination -> {
                if (closed || !repository.notices().destination(job, route, destination.getId(), recipient, clock.instant()))
                    return CompletableFuture.<Message>failedFuture(new IllegalStateException("notice lease or destination changed"));
                CompletableFuture<Optional<Message>> history = job.verifyFirst()
                        ? destination.getHistory().retrievePast(50).timeout(45, TimeUnit.SECONDS).submit()
                            .thenApply(messages -> messages.stream().filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                                    .filter(m -> FreebieMessages.carriesNoticeReference(m, job.reference())).findFirst())
                        : CompletableFuture.completedFuture(Optional.empty());
                return history.thenComposeAsync(found -> {
                    if (closed || !repository.notices().owns(job, clock.instant()))
                        return CompletableFuture.<Message>failedFuture(new IllegalStateException("notice lease expired"));
                    if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
                    var notice = job.change().isPresent() ? FreebieMessages.changeNotice(guild.getName(), job.change().get())
                            : FreebieMessages.serverNotice(guild.getName(), job.failedChannel(), job.failure(), offer);
                    var embed = new net.dv8tion.jda.api.EmbedBuilder(notice.getEmbeds().getFirst())
                            .setFooter(FreebieMessages.noticeReference(job.reference())).build();
                    return destination.sendMessage(org.bunnys.handler.cards.Card.fromEmbed(embed).message())
                            .setNonce(FreebieMessages.nonce("notice|" + job.reference())).timeout(45, TimeUnit.SECONDS).submit();
                }, executor);
            }, executor).whenCompleteAsync((message, failure) -> finish(job, message, failure), executor);
        } catch (RuntimeException failure) { finish(job, null, failure); }
    }

    private void finish(FreebieNoticeStore.Claim job, Message message, Throwable failure) {
        try {
            if (failure == null) repository.notices().sent(job, message.getId(), clock.instant());
            else {
                if (!"SYSTEM".equals(job.route()) && dmRejected(failure)) repository.notices().fallback(job, clock.instant());
                repository.notices().retry(job, clock.instant());
                BunnyLog.warning("[Freebies] Notice for " + job.guildId() + " remains pending | " + FailureDiagnostics.describe(failure));
            }
        } catch (RuntimeException storage) {
            BunnyLog.error("[Freebies] Notice outcome will recover | " + FailureDiagnostics.describe(storage));
        } finally { inFlight--; }
    }

    static boolean dmRejected(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof ErrorResponseException response && response.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER;
    }
}
