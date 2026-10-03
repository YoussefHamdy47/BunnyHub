package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Recoverable reporting, confined to the dedicated freebie sender executor. */
final class FreebieSummarySender {
    static final Duration LEASE = Duration.ofMinutes(3);
    private final FreebieRepository repository;
    private final FreebieConfig config;
    private final Supplier<JDA> jda;
    private final Executor executor;
    private final Clock clock;
    private final FreebieReviewChannel reviews;
    private int inFlight;

    FreebieSummarySender(FreebieRepository repository, FreebieConfig config, Supplier<JDA> jda,
                         Executor executor, Clock clock, FreebieReviewChannel reviews) {
        this.repository = repository; this.config = config; this.jda = jda;
        this.executor = executor; this.clock = clock; this.reviews = reviews;
    }

    void submit(FreebieOffer offer) {
        try { executor.execute(() -> {
            try { start(offer); }
            catch (RuntimeException failure) { log(offer.id(), failure); }
        }); } catch (RejectedExecutionException shutdown) {
            // No claim was made; housekeeping resumes after restart.
        }
    }

    private void start(FreebieOffer candidate) {
        if (inFlight >= 4 || repository.counts(candidate.id()).open() != 0) return;
        var claimed = repository.summaries().claim(candidate.id(), config.reviewChannelId(), clock.instant(), LEASE);
        if (claimed.isEmpty()) return;
        var job = claimed.get();
        inFlight++;
        try {
            var client = jda.get();
            var channel = client == null ? null : client.getChannelById(GuildMessageChannel.class, job.channelId());
            if (channel == null || !channel.canTalk()) {
                finish(job, null, new IllegalStateException("review channel unavailable"));
                return;
            }
            // Fail closed on a history error: do not blindly resend an uncertain message.
            CompletableFuture<Optional<Message>> existing = job.verifyFirst()
                    ? channel.getHistory().retrievePast(50).timeout(45, TimeUnit.SECONDS).submit()
                        .thenApply(messages -> messages.stream()
                                .filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                                .filter(m -> FreebieMessages.carriesSummaryReference(m, job.offer().id())).findFirst())
                    : CompletableFuture.completedFuture(Optional.empty());
            existing.thenComposeAsync(found -> {
                if (!repository.summaries().owns(job, clock.instant()))
                    return CompletableFuture.<Message>failedFuture(new IllegalStateException("summary lease expired"));
                if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
                var offer = job.offer();
                var counts = repository.counts(offer.id());
                var failures = repository.failures(offer.id(), 40);
                Map<String, String> names = new HashMap<>();
                for (var failure : failures) {
                    var guild = client.getGuildById(failure.guildId());
                    if (guild != null) names.put(failure.guildId(), guild.getName());
                }
                try { reviews.finishReview(offer, counts); }
                catch (RuntimeException failure) { log(offer.id(), failure); }
                if (!repository.summaries().owns(job, clock.instant()))
                    return CompletableFuture.<Message>failedFuture(new IllegalStateException("summary lease expired before send"));
                return channel.sendMessage(FreebieMessages.summary(offer, counts, failures, names))
                        .setNonce(FreebieMessages.nonce("summary|" + offer.id())).timeout(45, TimeUnit.SECONDS).submit();
            }, executor).whenCompleteAsync((message, failure) -> finish(job, message, failure), executor);
        } catch (RuntimeException failure) { finish(job, null, failure); }
    }

    private void finish(FreebieSummaryStore.Claim job, Message message, Throwable failure) {
        try {
            if (failure == null) repository.summaries().sent(job, message.getId(), clock.instant());
            else {
                log(job.offer().id(), failure);
                repository.summaries().retry(job, clock.instant());
            }
        } catch (RuntimeException storage) {
            // A write may itself have succeeded before timing out. Recovery consults persisted state/history.
            log(job.offer().id(), storage);
        } finally { inFlight--; }
    }

    private static void log(String id, Throwable failure) {
        BunnyLog.warning("[Freebies] Summary " + id + " remains recoverable | " + FailureDiagnostics.describe(failure));
    }
}
