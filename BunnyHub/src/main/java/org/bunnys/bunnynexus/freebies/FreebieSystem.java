package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.awt.Color;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Free-game alerts: discover on GamerPower → owner reviews in the private channel from .env →
 * one confirmed approval → durable per-channel delivery with retries → summary back to the owner and
 * a notice to any server whose channel could not receive it.
 *
 * <p>Two dedicated threads: discovery/housekeeping and sending. Nothing runs on JDA gateway threads.
 */
public final class FreebieSystem {
    private static volatile FreebieSystem instance;

    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final GamerPowerFeed feed;
    private final ScheduledExecutorService discovery, sending;
    private final FreebieSender sender;
    private int pollFailures;
    private volatile Instant lastPollOk, lastPollAt;
    private volatile String lastPollProblem = "not polled yet";
    private boolean healthAlerted;
    private final Instant startedAt;
    private final Map<String, Instant> lastProgress = new ConcurrentHashMap<>();

    private FreebieSystem(FreebieConfig config, FreebieRepository repository, Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this.config = config; this.repository = repository; this.jda = jda; this.clock = clock; this.feed = feed;
        startedAt = clock.instant();
        discovery = Executors.newSingleThreadScheduledExecutor(daemon("FreebieDiscovery"));
        sending = Executors.newSingleThreadScheduledExecutor(daemon("FreebieSender"));
        sender = new FreebieSender(repository, jda, sending, clock, new FreebieNotifier(repository, jda, clock, sending));
    }

    public static Optional<FreebieSystem> current() { return Optional.ofNullable(instance); }
    public FreebieConfig config() { return config; }
    public FreebieRepository repository() { return repository; }

    /** Starts only when FREEBIE_REVIEW_CHANNEL_ID and FREEBIE_OWNER_IDS are set; otherwise logs why and stays off. */
    public static synchronized void start(BunnyHub client) {
        if (instance != null) return;
        List<String> problems = new ArrayList<>();
        var config = FreebieConfig.fromEnvironment(problems);
        if (config.isEmpty()) {
            BunnyLog.warning("[Freebies] Disabled: " + String.join("; ", problems));
            return;
        }
        var repository = new FreebieRepository(DB.getDatabase());
        repository.installIndexes();
        var system = new FreebieSystem(config.get(), repository, client::getJDA, Clock.systemUTC(), new GamerPowerFeed());
        instance = system;
        system.discovery.scheduleWithFixedDelay(guarded("housekeeping", system::housekeeping), 20, 30, TimeUnit.SECONDS);
        system.discovery.schedule(system::pollAndReschedule, 15, TimeUnit.SECONDS);
        system.sending.scheduleWithFixedDelay(guarded("sender", system.sender::tick), 10, 1, TimeUnit.SECONDS);
        BunnyLog.success("[Freebies] Started. Review channel " + config.get().reviewChannelId() + ", polling every "
                + config.get().pollMinutes() + " min.");
    }

    public static synchronized void shutdown() {
        var system = instance;
        if (system == null) return;
        instance = null;
        system.sender.close();
        system.discovery.shutdownNow();
        system.sending.shutdown();
        try {
            // Let in-flight sends record their outcome; anything left is recovered safely on next start.
            if (!system.sending.awaitTermination(5, TimeUnit.SECONDS)) system.sending.shutdownNow();
        } catch (InterruptedException interrupted) {
            system.sending.shutdownNow(); Thread.currentThread().interrupt();
        }
        system.feed.close();
    }

    // ------------------------------------------------------------------ discovery

    private void pollAndReschedule() {
        Duration next = Duration.ofMinutes(config.pollMinutes());
        try { next = poll(); }
        catch (Throwable failure) {
            pollFailures++;
            BunnyLog.error("[Freebies] Poll failed | " + FailureDiagnostics.describe(failure));
            next = backoff();
        } finally {
            if (!discovery.isShutdown()) discovery.schedule(this::pollAndReschedule, next.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private Duration backoff() {
        long minutes = (long) config.pollMinutes() << Math.min(pollFailures, 4);
        return Duration.ofMinutes(Math.min(minutes, 120));
    }

    private Duration poll() {
        var fetch = feed.fetch();
        Instant now = clock.instant();
        if (fetch.batch().isEmpty() || fetch.batch().get().state() == GamerPowerIntake.State.FAILED) {
            pollFailures++;
            BunnyLog.warning("[Freebies] GamerPower poll failed: " + fetch.problem());
            lastPollAt = now; lastPollProblem = fetch.problem();
            checkDiscoveryHealth(now);
            return fetch.retryAfter().filter(wait -> wait.compareTo(backoff()) > 0).orElse(backoff());
        }
        pollFailures = 0;
        lastPollAt = now; lastPollOk = now; lastPollProblem = "";
        checkDiscoveryHealth(now);
        int fresh = 0;
        Set<String> present = new HashSet<>();
        for (var candidate : fetch.batch().get().candidates()) {
            present.add(FreebieOffer.idFor(candidate.sourceItemId()));
            if (repository.recordSeen(candidate, now)) fresh++;
        }
        // Only a complete, valid feed may be used as evidence that an offer ended.
        if (fetch.complete()) for (var gone : repository.markMissing(present)) ended(gone, "no longer listed as active");
        if (fresh > 0) BunnyLog.info("[Freebies] " + fresh + " new giveaway(s) queued for review.");
        return Duration.ofMinutes(config.pollMinutes());
    }

    // ------------------------------------------------------------------ housekeeping

    private void housekeeping() {
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        Instant now = clock.instant();
        for (int i = 0; i < 5; i++) {
            var offer = repository.claimReviewPost(now);
            if (offer.isEmpty()) break;
            postReview(client, offer.get());
        }
        for (var offer : repository.offersIn(FreebieOffer.State.PENDING, 200))
            if (offer.ended(now)) ended(offer, "ended before review");
        for (var offer : repository.offersIn(FreebieOffer.State.APPROVED, 200)) {
            if (offer.ended(now)) { ended(offer, "the giveaway ended"); continue; }
            if (!offer.fanoutDone()) { fanOut(offer); continue; }
            var counts = repository.counts(offer.id());
            if (counts.open() == 0) {
                lastProgress.remove(offer.id());
                repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now)
                        .ifPresent(this::finish);
            } else progress(offer, counts, now);
        }
        for (var offer : repository.stoppedAwaitingSummary(50))
            if (repository.counts(offer.id()).open() == 0) finish(offer);
    }

    private void postReview(JDA client, FreebieOffer offer) {
        var channel = reviewChannel(client);
        if (channel == null) {
            BunnyLog.error("[Freebies] Review channel " + config.reviewChannelId() + " is missing or not writable; reviews are queued.");
            return;
        }
        var audience = repository.audience(offer.store());
        try {
            var message = channel.sendMessage(FreebieMessages.review(offer, audience.channels(), audience.guilds(), config.ownerIds()))
                    .setNonce(FreebieMessages.nonce("review|" + offer.id())).submit().get(60, TimeUnit.SECONDS);
            repository.setReviewMessage(offer.id(), message.getId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            // Lease expires and it is retried; the fixed nonce stops a quick duplicate.
            BunnyLog.warning("[Freebies] Could not post review for " + offer.id() + " | " + FailureDiagnostics.describe(failure));
        }
    }

    private GuildMessageChannel reviewChannel(JDA client) {
        var channel = client.getChannelById(GuildMessageChannel.class, config.reviewChannelId());
        return channel != null && channel.canTalk() ? channel : null;
    }

    private void fanOut(FreebieOffer offer) {
        int planned = repository.planDeliveries(offer, clock.instant());
        repository.setFanoutDone(offer.id(), planned);
    }

    /** The giveaway ended (or vanished from the feed): close review or stop remaining sends. */
    private void ended(FreebieOffer offer, String why) {
        Instant now = clock.instant();
        if (offer.state() == FreebieOffer.State.PENDING) {
            repository.transition(offer.id(), FreebieOffer.State.PENDING, FreebieOffer.State.EXPIRED, null, now)
                    .ifPresent(expired -> editReview(expired, "Expired — " + why + ". Nothing was sent.", AppDesign.ColorCodes.PASTEL_RED, List.of()));
        } else if (offer.state() == FreebieOffer.State.APPROVED) {
            repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, null, now).ifPresent(stopped -> {
                repository.cancelPending(stopped.id(), why, now);
                editReview(stopped, "Stopped automatically — " + why + ". Summary follows.", AppDesign.ColorCodes.PASTEL_RED, List.of());
            });
        } else if (offer.state() == FreebieOffer.State.COMPLETED) {
            // Only late-subscriber catch-up sends can still be queued; the sender also refuses ended offers.
            repository.cancelPending(offer.id(), why, now);
        }
    }

    /** Live progress on the review message, at most once a minute per offer. */
    private void progress(FreebieOffer offer, FreebieRepository.Counts counts, Instant now) {
        Instant last = lastProgress.get(offer.id());
        if (last != null && Duration.between(last, now).compareTo(Duration.ofMinutes(1)) < 0) return;
        lastProgress.put(offer.id(), now);
        long total = counts.sent() + counts.failed() + counts.cancelled() + counts.open();
        String paused = repository.sendingPaused() ? " **(sending is paused)**" : "";
        editReview(offer, "Sending… " + counts.sent() + "/" + total + " sent, " + counts.failed() + " failed, "
                + counts.open() + " left." + paused, AppDesign.ColorCodes.SUCCESS_GREEN, FreebieMessages.stopControls(offer));
    }

    /** Tell the owner once when discovery has been failing for an hour, and once when it recovers. */
    private void checkDiscoveryHealth(Instant now) {
        Instant reference = lastPollOk != null ? lastPollOk : startedAt;
        boolean unhealthy = Duration.between(reference, now).compareTo(Duration.ofHours(1)) >= 0;
        if (unhealthy == healthAlerted) return;
        healthAlerted = unhealthy;
        notifyOwners(unhealthy
                ? "⚠️ Free-game discovery has not worked for over an hour (last problem: " + lastPollProblem
                        + "). New giveaways may be missed until GamerPower is reachable again."
                : "✅ Free-game discovery is working again.");
    }

    private void notifyOwners(String text) {
        JDA client = jda.get();
        var channel = client == null ? null : reviewChannel(client);
        if (channel == null) { BunnyLog.warning("[Freebies] " + text); return; }
        channel.sendMessage(text).setAllowedMentions(List.of()).queue(null,
                error -> BunnyLog.warning("[Freebies] Owner notice failed | " + FailureDiagnostics.describe(error)));
    }

    private void finish(FreebieOffer offer) {
        if (!repository.claimSummary(offer.id())) return;
        JDA client = jda.get();
        var channel = client == null ? null : reviewChannel(client);
        var counts = repository.counts(offer.id());
        var failures = repository.failures(offer.id(), 40);
        Map<String, String> names = new HashMap<>();
        if (client != null) for (var f : failures) {
            var guild = client.getGuildById(f.guildId());
            if (guild != null) names.put(f.guildId(), guild.getName());
        }
        editReview(offer, (offer.state() == FreebieOffer.State.COMPLETED ? "Done" : "Stopped") + " — sent " + counts.sent()
                + ", failed " + counts.failed() + ", cancelled " + counts.cancelled() + ".",
                counts.failed() == 0 ? AppDesign.ColorCodes.SUCCESS_GREEN : AppDesign.ColorCodes.PASTEL_RED, List.of());
        if (channel == null) { BunnyLog.warning("[Freebies] Summary for " + offer.id() + " could not be posted."); return; }
        channel.sendMessage(FreebieMessages.summary(offer, counts, failures, names)).queue(null,
                error -> BunnyLog.warning("[Freebies] Summary for " + offer.id() + " failed | " + FailureDiagnostics.describe(error)));
    }

    private void editReview(FreebieOffer offer, String status, Color color, List<ActionRow> components) {
        JDA client = jda.get();
        var channel = client == null ? null : reviewChannel(client);
        if (channel == null || offer.reviewMessageId().isEmpty()) return;
        var audience = repository.audience(offer.store());
        channel.editMessageEmbedsById(offer.reviewMessageId().get(), FreebieMessages.alertEmbed(offer, "preview"),
                        FreebieMessages.reviewEmbed(offer, audience.channels(), audience.guilds(), status, color))
                .setComponents(components).queue(null,
                        error -> BunnyLog.warning("[Freebies] Could not update review " + offer.id() + " | " + FailureDiagnostics.describe(error)));
    }

    // ------------------------------------------------------------------ owner actions (button threads)

    public sealed interface Reply permits Text, Prompt {}
    public record Text(String message) implements Reply {}
    public record Prompt(MessageCreateData message) implements Reply {}

    /** First click: show exactly how many channels will be messaged and ask for a second confirmation. */
    public Reply requestApproval(String actorId, String offerId) {
        requireOwner(actorId);
        var offer = repository.offer(offerId).orElse(null);
        if (offer == null) return new Text("That offer no longer exists.");
        if (offer.state() != FreebieOffer.State.PENDING) return new Text("Already handled: " + offer.state().name().toLowerCase(Locale.ROOT) + ".");
        if (offer.ended(clock.instant())) return new Text("This giveaway has already ended.");
        var audience = repository.audience(offer.store());
        return new Prompt(FreebieMessages.confirm(offer, audience.channels(), audience.guilds()));
    }

    public String confirmApproval(String actorId, String offerId) {
        requireOwner(actorId);
        Instant now = clock.instant();
        var current = repository.offer(offerId).orElse(null);
        if (current == null) return "That offer no longer exists.";
        if (current.ended(now)) return "This giveaway has already ended; nothing was sent.";
        var approved = repository.transition(offerId, FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, actorId, now);
        if (approved.isEmpty()) return "Already handled by someone else (or expired). Nothing changed.";
        fanOut(approved.get());
        var refreshed = repository.offer(offerId).orElse(approved.get());
        var counts = repository.counts(offerId);
        editReview(refreshed, "Approved by <@" + actorId + "> — sending to " + counts.open() + " channel(s).",
                AppDesign.ColorCodes.SUCCESS_GREEN, FreebieMessages.stopControls(refreshed));
        return "Approved. Sending to " + counts.open() + " channel(s); a summary will be posted here when it finishes.";
    }

    public String reject(String actorId, String offerId) {
        requireOwner(actorId);
        var rejected = repository.transition(offerId, FreebieOffer.State.PENDING, FreebieOffer.State.REJECTED, actorId, clock.instant());
        if (rejected.isEmpty()) return "Already handled. Nothing changed.";
        editReview(rejected.get(), "Rejected by <@" + actorId + ">. Nothing was sent.", AppDesign.ColorCodes.PASTEL_RED, List.of());
        return "Rejected. Nothing was sent.";
    }

    /** Kill switch for one offer: messages already sent stay, everything not yet sent is cancelled. */
    public String stop(String actorId, String offerId) {
        requireOwner(actorId);
        Instant now = clock.instant();
        var stopped = repository.transition(offerId, FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, actorId, now);
        if (stopped.isEmpty()) return "This offer is not currently sending.";
        long cancelled = repository.cancelPending(offerId, "stopped by owner", now);
        editReview(stopped.get(), "Stopped by <@" + actorId + ">. " + cancelled + " pending message(s) cancelled; summary follows.",
                AppDesign.ColorCodes.PASTEL_RED, List.of());
        return "Stopped. " + cancelled + " pending message(s) cancelled. Up to " + FreebieSender.MAX_IN_FLIGHT
                + " already in flight may still arrive.";
    }

    /** Owner fixes a wrongly detected launcher before approving; the confirmation then counts the new audience. */
    public String changeStore(String actorId, String offerId, String storeId) {
        requireOwner(actorId);
        var store = FreebieStore.byId(storeId).orElse(null);
        if (store == null) return "Unknown launcher.";
        var changed = repository.changeStore(offerId, store, clock.instant());
        if (changed.isEmpty()) return "Only offers still waiting for review can change launcher.";
        editReview(changed.get(), "Waiting for review (launcher set to " + store.label() + " by <@" + actorId + ">)",
                AppDesign.ColorCodes.DEFAULT, FreebieMessages.reviewControls(changed.get()));
        return "Launcher changed to **" + store.label() + "**.";
    }

    /** Approved giveaways that a newly configured channel can still claim. Owner approval is never bypassed. */
    public List<FreebieOffer> liveFor(FreebieStore store) { return repository.liveApproved(store, clock.instant()); }

    public int catchUp(FreebieRepository.Subscription subscription) {
        int queued = 0;
        for (var offer : liveFor(subscription.store()))
            if (repository.planDelivery(offer, subscription, clock.instant())) queued++;
        return queued;
    }

    // ------------------------------------------------------------------ owner admin

    public String status(String actorId) {
        requireOwner(actorId);
        var o = repository.overview();
        Instant now = clock.instant();
        return "**Free-game alerts**\n"
                + "Sending: " + (repository.sendingPaused() ? "⏸️ **paused**" : "▶️ running") + "\n"
                + "Last GamerPower check: " + (lastPollAt == null ? "not yet" : "<t:" + lastPollAt.getEpochSecond() + ":R>")
                + (lastPollProblem.isEmpty() ? " ✅" : " ⚠️ " + lastPollProblem) + "\n"
                + "Last successful check: " + (lastPollOk == null ? "none since start" : "<t:" + lastPollOk.getEpochSecond() + ":R>") + "\n"
                + "Waiting for review: " + o.pendingReviews() + "\n"
                + "Offers sending: " + o.sending() + " • queued messages: " + o.queuedDeliveries() + " (" + o.retrying() + " retrying)\n"
                + "In flight now: " + sender.inFlight() + " • checked <t:" + now.getEpochSecond() + ":T>";
    }

    public String setPaused(String actorId, boolean paused) {
        requireOwner(actorId);
        repository.setSendingPaused(paused, actorId, clock.instant());
        notifyOwners(paused ? "⏸️ Sending paused by <@" + actorId + ">. Queued alerts wait; nothing is lost."
                : "▶️ Sending resumed by <@" + actorId + ">.");
        return paused ? "Paused. Up to " + FreebieSender.MAX_IN_FLIGHT + " messages already in flight may still arrive."
                : "Resumed.";
    }

    /** One extra GamerPower check on the discovery thread; the regular schedule is unchanged. */
    public String pollNow(String actorId) {
        requireOwner(actorId);
        discovery.execute(guarded("manual poll", () -> poll()));
        return "Checking GamerPower now; new giveaways will appear in the review channel.";
    }

    private void requireOwner(String actorId) {
        if (!config.isOwner(actorId)) throw new SecurityException("Not a freebie owner.");
    }

    // ------------------------------------------------------------------ helpers

    private static Runnable guarded(String name, Runnable task) {
        // An exception would silently cancel a fixed-delay schedule; log and keep going instead.
        return () -> {
            try { task.run(); }
            catch (Throwable failure) { BunnyLog.error("[Freebies] " + name + " failed | " + FailureDiagnostics.describe(failure)); }
        };
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
