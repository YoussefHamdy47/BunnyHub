package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Everything the system posts in, or edits inside, the private review channel from .env. */
final class FreebieReviewChannel {
    private static final Duration PROGRESS_INTERVAL = Duration.ofMinutes(1);

    private final FreebieSummarySender summaries;
    private final Clock clock;
    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final Supplier<JDA> jda;
    private final Map<String, Instant> lastProgress = new ConcurrentHashMap<>();
    /** How long a sending offer's progress edits reuse its audience count; it is only shown, never used to send. */
    static final Duration AUDIENCE_TTL = Duration.ofMinutes(10);
    private record CachedAudience(FreebieSubscriptions.Audience audience, Instant at) {}
    private final Map<String, CachedAudience> progressAudience = new ConcurrentHashMap<>();
    /** The posted ping line of a sending card, so its once-a-minute progress edit needs no read of the message. */
    private final Map<String, java.util.Optional<String>> progressHeader = new ConcurrentHashMap<>();

    FreebieReviewChannel(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions, Supplier<JDA> jda, Executor executor, Clock clock) {
        this.config = config; this.repository = repository; this.subscriptions = subscriptions; this.jda = jda;
        this.clock = clock;
        summaries = new FreebieSummarySender(repository, config, jda, executor, clock, this);
    }

    /** Blocking only on discovery's dedicated worker, with a fenced claim and bounded REST waits. */
    void post(JDA client, FreebieReviewStore.Claim job) {
        var offer = job.offer();
        String channelId = job.channelId() == null ? config.reviewChannelFor(offer.store()) : job.channelId();
        var destination = channel(client, channelId);
        if (job.channelId() == null && destination == null && !channelId.equals(config.reviewChannelId())) {
            channelId = config.reviewChannelId();
            destination = channel(client, channelId);
        }
        if (destination == null) {
            BunnyLog.warning("[Freebies] Review destination unavailable for " + offer.id() + "; claim will recover.");
            return;
        }
        try {
            if (!repository.reviews().destination(job, channelId, clock.instant())) return;
            if (job.verifyFirst()) {
                var messages = destination.getHistory().retrievePast(50).timeout(45, TimeUnit.SECONDS).submit().get(50, TimeUnit.SECONDS);
                var existing = messages.stream().filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                        .filter(m -> FreebieMessages.carriesReviewReference(m, offer.id())).findFirst();
                if (existing.isPresent()) {
                    repository.reviews().sent(job, channelId, existing.get().getId(), clock.instant());
                    return;
                }
            }
            var audience = subscriptions.audience(offer.store());
            if (!repository.reviews().owns(job, clock.instant())) return;
            var message = destination.sendMessage(FreebieMessages.review(offer, facts(offer), groupPending(offer),
                            audience.channels(), audience.guilds(), config.ownerIds(), config.autoSends(offer.store()), clock.instant()))
                    .setNonce(FreebieMessages.nonce("review|" + offer.id())).timeout(45, TimeUnit.SECONDS).submit().get(50, TimeUnit.SECONDS);
            repository.reviews().sent(job, channelId, message.getId(), clock.instant());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            BunnyLog.warning("[Freebies] Review " + offer.id() + " remains recoverable | " + FailureDiagnostics.describe(failure));
        }
    }
    void edit(FreebieOffer offer, String status, List<ActionRow> components) {
        edit(offer, status, components, null);
    }

    /** {@code audience}: a count already read for this edit, or null to read it now. */
    private void edit(FreebieOffer offer, String status, List<ActionRow> components, FreebieSubscriptions.Audience audience) {
        var channel = reviewChannelOf(jda.get(), offer);
        if (channel == null || offer.reviewMessageId().isEmpty()) return;
        boolean progress = audience != null;
        if (audience == null) audience = subscriptions.audience(offer.store());
        var facts = facts(offer);
        var counted = audience;
        String id = offer.reviewMessageId().get();
        java.util.function.Consumer<Throwable> failed =
                error -> BunnyLog.warning("[Freebies] Could not update review " + offer.id() + " | " + FailureDiagnostics.describe(error));
        var known = progress ? progressHeader.get(offer.id()) : null;
        if (known != null) {
            channel.editMessageById(id, FreebieMessages.reviewEdit(offer, facts, counted.channels(), counted.guilds(), status,
                    FreebieMessages.withClear(components), known, clock.instant())).queue(null, failed);
            return;
        }
        // A message keeps the format it was posted in: reviews from before Components V2 are still edited as embeds.
        channel.retrieveMessageById(id).queue(message -> {
            if (progress && message.isUsingComponentsV2()) progressHeader.put(offer.id(), FreebieComponentCards.header(message));
            Instant now = clock.instant();
            var request = message.isUsingComponentsV2()
                    ? channel.editMessageById(id, FreebieMessages.reviewEdit(offer, facts, counted.channels(), counted.guilds(), status,
                            FreebieMessages.withClear(components), FreebieComponentCards.header(message), now))
                    : channel.editMessageEmbedsById(id, FreebieAnnouncementCards.preview(offer, facts, now),
                            FreebieMessages.reviewEmbed(offer, facts, counted.channels(), counted.guilds(), status, now))
                            .setComponents(FreebieMessages.withClear(components));
            request.queue(null, failed);
        }, failed);
    }

    /** Live progress on the review message, at most once a minute per offer. */
    void progress(FreebieOffer offer, FreebieRepository.Counts counts, Instant now) {
        Instant last = lastProgress.get(offer.id());
        if (last != null && Duration.between(last, now).compareTo(PROGRESS_INTERVAL) < 0) return;
        lastProgress.put(offer.id(), now);
        long total = counts.sent() + counts.failed() + counts.cancelled() + counts.open();
        // The in-process pause copy (refreshed by housekeeping) and an audience count reused for AUDIENCE_TTL: this
        // text is informational, while every send still reads the pause flag from the database.
        String paused = repository.sendingPausedCached() ? " **(sending is paused)**" : "";
        if (channelReady(offer)) {
            var cached = progressAudience.get(offer.id());
            if (cached == null || Duration.between(cached.at(), now).compareTo(AUDIENCE_TTL) >= 0) {
                cached = new CachedAudience(subscriptions.audience(offer.store()), now);
                progressAudience.put(offer.id(), cached);
            }
            edit(offer, "Sending… " + counts.sent() + "/" + total + " sent, " + counts.failed() + " failed, "
                    + counts.open() + " left." + paused, FreebieMessages.stopControls(offer), cached.audience());
        }
    }

    private boolean channelReady(FreebieOffer offer) {
        return offer.reviewMessageId().isPresent() && reviewChannelOf(jda.get(), offer) != null;
    }

    /**
     * Posts a held source report with its decision buttons in the review channel of the giveaway's section. Blocking,
     * discovery thread only, bounded wait. True only when Discord accepted it.
     */
    boolean postQuestion(String storeId, FreebieDeliveryAdmin.View view) {
        var store = FreebieStore.byId(storeId).orElse(FreebieStore.OTHER);
        var channel = channel(jda.get(), config.reviewChannelFor(store));
        if (channel == null) channel = channel(jda.get(), config.reviewChannelId());
        if (channel == null) return false;
        try {
            channel.sendMessage("**Possible duplicate held for your decision**\n" + view.text()).setComponents(view.rows())
                    .setAllowedMentions(List.of()).timeout(45, TimeUnit.SECONDS).submit().get(50, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            BunnyLog.warning("[Freebies] Source question will be posted again | " + FailureDiagnostics.describe(failure));
            return false;
        }
    }

    void notifyOwners(String text) {
        var channel = channel(jda.get(), config.reviewChannelId());
        if (channel == null) { BunnyLog.warning("[Freebies] " + text); return; }
        channel.sendMessage(text).setAllowedMentions(List.of()).queue(null,
                error -> BunnyLog.warning("[Freebies] Owner notice failed | " + FailureDiagnostics.describe(error)));
    }

    /** Claim and send only on the dedicated freebie executor. */
    void summarize(FreebieOffer offer) { summaries.submit(offer); }

    void finishReview(FreebieOffer offer, FreebieRepository.Counts counts) {
        lastProgress.remove(offer.id());
        progressAudience.remove(offer.id());
        progressHeader.remove(offer.id());
        edit(offer, (offer.state() == FreebieOffer.State.COMPLETED ? "Done" : "Stopped") + " — sent " + counts.sent()
                + ", failed " + counts.failed() + ", cancelled " + counts.cancelled() + ".", List.of());
    }
    private int groupPending(FreebieOffer offer) {
        return repository.routes() == null ? 0 : repository.routes().groupPending(offer, clock.instant());
    }

    private FreebieFacts facts(FreebieOffer offer) {
        return Objects.requireNonNullElse(repository.facts(offer.id()), FreebieFacts.NONE);
    }

    /** The channel holding this offer's review; offers reviewed before sections existed are in the main one. */
    private GuildMessageChannel reviewChannelOf(JDA client, FreebieOffer offer) {
        return channel(client, offer.reviewChannelId().orElse(config.reviewChannelId()));
    }

    private static GuildMessageChannel channel(JDA client, String channelId) {
        if (client == null) return null;
        var channel = client.getChannelById(GuildMessageChannel.class, channelId);
        return channel != null && channel.canTalk() ? channel : null;
    }
}
