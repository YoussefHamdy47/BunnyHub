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
    void close() { closed = true; }

    void deliveryFailed(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure) {
        // markFailed already persisted noticePending atomically with the failure. The tick also survives a crash here.
        if (failure.notifyServer()) tick();
    }

    void tick() {
        var client = jda.get();
        if (closed || client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        repository.notices().recover(clock.instant());
        for (int admitted = 0; admitted < 2 && inFlight < 2; admitted++) {
            var claimed = repository.notices().claim(clock.instant());
            if (claimed.isEmpty()) break;
            inFlight++;
            start(client, claimed.get());
        }
    }

    private void start(JDA client, FreebieNoticeStore.Claim job) {
        try {
            var guild = client.getGuildById(job.guildId());
            var offer = repository.offer(job.offerId()).orElse(null);
            if (guild == null || offer == null) throw new IllegalStateException("notice guild or offer unavailable");
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
                                    .filter(m -> FreebieMessages.carriesNoticeReference(m, job.deliveryId())).findFirst())
                        : CompletableFuture.completedFuture(Optional.empty());
                return history.thenComposeAsync(found -> {
                    if (closed || !repository.notices().owns(job, clock.instant()))
                        return CompletableFuture.<Message>failedFuture(new IllegalStateException("notice lease expired"));
                    if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
                    var notice = FreebieMessages.serverNotice(guild.getName(), job.failedChannel(), job.failure(), offer);
                    var embed = new net.dv8tion.jda.api.EmbedBuilder(notice.getEmbeds().getFirst())
                            .setFooter(FreebieMessages.noticeReference(job.deliveryId())).build();
                    return destination.sendMessage(new MessageCreateBuilder().setEmbeds(embed).setAllowedMentions(List.of()).build())
                            .setNonce(FreebieMessages.nonce("notice|" + job.deliveryId())).timeout(45, TimeUnit.SECONDS).submit();
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
