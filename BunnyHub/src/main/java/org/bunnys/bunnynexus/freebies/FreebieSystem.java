package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
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
    private final FreebieSubscriptions subscriptions;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final GamerPowerFeed feed;
    private final ScheduledExecutorService discoveryThread, sendingThread;
    private final FreebieSender sender;
    private final FreebieNotifier notifier;
    private final FreebieReviewChannel reviews;
    private final FreebieDiscovery discovery;

    // Package-private for tests; production code goes through start().
    FreebieSystem(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions,
                          Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this.config = config; this.repository = repository; this.subscriptions = subscriptions;
        this.jda = jda; this.clock = clock; this.feed = feed;
        discoveryThread = Executors.newSingleThreadScheduledExecutor(daemon("FreebieDiscovery"));
        sendingThread = Executors.newSingleThreadScheduledExecutor(daemon("FreebieSender"));
        reviews = new FreebieReviewChannel(config, repository, subscriptions, jda, sendingThread, clock);
        discovery = new FreebieDiscovery(config, repository, feed, clock, reviews, this::ended);
        notifier = new FreebieNotifier(repository, jda, clock, sendingThread);
        sender = new FreebieSender(repository, jda, sendingThread, clock, notifier).withSubscriptions(subscriptions);
    }

    public static Optional<FreebieSystem> current() { return Optional.ofNullable(instance); }
    public FreebieConfig config() { return config; }
    public FreebieRepository repository() { return repository; }
    public FreebieSubscriptions subscriptions() { return subscriptions; }
    public FreebieDeliveryAdmin deliveryAdmin() { return new FreebieDeliveryAdmin(config, repository, subscriptions, jda, clock); }

    /** Starts only when FREEBIE_REVIEW_CHANNEL_ID and FREEBIE_OWNER_IDS are set; otherwise logs why and stays off. */
    public static synchronized void start(BunnyHub client) {
        if (instance != null) return;
        List<String> problems = new ArrayList<>();
        var config = FreebieConfig.fromEnvironment(problems);
        if (config.isEmpty()) {
            BunnyLog.warning("[Freebies] Disabled: " + String.join("; ", problems));
            return;
        }
        FreebieRepository repository;
        FreebieSubscriptions subscriptions;
        try {
            repository = new FreebieRepository(DB.getDatabase());
            subscriptions = new FreebieSubscriptions(DB.getDatabase());
            repository.installIndexes();
            subscriptions.installIndexes();
        } catch (RuntimeException failure) {
            // The rest of the bot keeps running; alerts stay off until the next restart.
            BunnyLog.error("[Freebies] Disabled: database setup failed | " + FailureDiagnostics.describe(failure));
            return;
        }
        var system = new FreebieSystem(config.get(), repository, subscriptions, client::getJDA, Clock.systemUTC(), new GamerPowerFeed());
        instance = system;
        system.discoveryThread.scheduleWithFixedDelay(guarded("housekeeping", system::housekeeping), 20, 30, TimeUnit.SECONDS);
        system.discovery.start(system.discoveryThread, 15);
        system.sendingThread.scheduleWithFixedDelay(guarded("sender", system.sender::tick), 10, 1, TimeUnit.SECONDS);
        system.sendingThread.scheduleWithFixedDelay(guarded("notices", system.notifier::tick), 10, 5, TimeUnit.SECONDS);
        BunnyLog.success("[Freebies] Started. Review channel " + config.get().reviewChannelId() + ", polling every "
                + config.get().pollMinutes() + " min.");
    }

    public static synchronized void shutdown() {
        var system = instance;
        if (system == null) return;
        instance = null;
        system.close();
    }

    void close() {
        sender.close();
        notifier.close();
        discoveryThread.shutdownNow();
        sendingThread.shutdown();
        try {
            // Let in-flight sends record their outcome; anything left is recovered safely on next start.
            if (!sendingThread.awaitTermination(5, TimeUnit.SECONDS)) sendingThread.shutdownNow();
        } catch (InterruptedException interrupted) {
            sendingThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        feed.close();
    }

    // ------------------------------------------------------------------ housekeeping (discovery thread)

    private void housekeeping() {
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        Instant now = clock.instant();
        for (int i = 0; i < 5; i++) {
            var offer = repository.reviews().claim(clock.instant());
            if (offer.isEmpty()) break;
            reviews.post(client, offer.get());
        }
        for (var offer : repository.offersIn(FreebieOffer.State.PENDING, 200))
            if (offer.ended(now)) ended(offer, "ended before review");
        for (var offer : repository.offersIn(FreebieOffer.State.APPROVED, 200)) {
            if (offer.ended(now)) { ended(offer, "the giveaway ended"); continue; }
            if (!offer.fanoutDone()) { fanOut(offer); continue; }
            var counts = repository.counts(offer.id());
            if (counts.open() == 0)
                repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now)
                        .ifPresent(reviews::summarize);
            else reviews.progress(offer, counts, now);
        }
        for (var offer : repository.summaries().awaiting(now, 50))
            if (repository.counts(offer.id()).open() == 0) reviews.summarize(offer);
    }

    /**
     * Plans one delivery per subscribed channel. Runs from the approval click and from housekeeping, possibly at
     * once; inserts are idempotent. A stop that lands while rows are still being inserted cannot cancel rows that
     * do not exist yet, so anything planned for an offer that is no longer sending is cancelled here.
     */
    void fanOut(FreebieOffer offer) {
        Instant now = clock.instant();
        int planned = repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now);
        repository.setFanoutDone(offer.id(), planned);
        if (repository.offer(offer.id()).map(FreebieOffer::state).orElse(null) == FreebieOffer.State.STOPPED)
            repository.cancelPending(offer.id(), "stopped while sending was being planned", now);
    }

    /** The giveaway ended (or vanished from the feed): close review or stop remaining sends. */
    private void ended(FreebieOffer offer, String why) {
        Instant now = clock.instant();
        switch (offer.state()) {
            case PENDING -> repository.transition(offer.id(), FreebieOffer.State.PENDING, FreebieOffer.State.EXPIRED, null, now)
                    .ifPresent(expired -> reviews.edit(expired, "Expired — " + why + ". Nothing was sent.",
                            List.of()));
            case APPROVED -> repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, null, now)
                    .ifPresent(stopped -> {
                        repository.cancelPending(stopped.id(), why, now);
                        reviews.edit(stopped, "Stopped automatically — " + why + ". Summary follows.",
                                List.of());
                    });
            // Only late-subscriber catch-up sends can still be queued; the sender also refuses ended offers.
            case COMPLETED -> repository.cancelPending(offer.id(), why, now);
            default -> { /* Already final. */ }
        }
    }

    // ------------------------------------------------------------------ owner actions (command workers)

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
        var audience = subscriptions.audience(offer.store());
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
        reviews.edit(refreshed, "Approved by <@" + actorId + "> — sending to " + counts.open() + " channel(s).",
                FreebieMessages.stopControls(refreshed));
        return "Approved. Sending to " + counts.open() + " channel(s); a summary will be posted here when it finishes.";
    }

    public String reject(String actorId, String offerId) {
        requireOwner(actorId);
        var rejected = repository.transition(offerId, FreebieOffer.State.PENDING, FreebieOffer.State.REJECTED, actorId, clock.instant());
        if (rejected.isEmpty()) return "Already handled. Nothing changed.";
        reviews.edit(rejected.get(), "Rejected by <@" + actorId + ">. Nothing was sent.", List.of());
        return "Rejected. Nothing was sent.";
    }

    /** Kill switch for one offer: messages already sent stay, everything not yet sent is cancelled. */
    public String stop(String actorId, String offerId) {
        requireOwner(actorId);
        Instant now = clock.instant();
        var stopped = repository.transition(offerId, FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, actorId, now);
        if (stopped.isEmpty()) stopped = repository.transition(offerId, FreebieOffer.State.COMPLETED, FreebieOffer.State.STOPPED, actorId, now);
        if (stopped.isEmpty()) return "This offer is not currently sending.";
        long cancelled = repository.cancelPending(offerId, "stopped by owner", now);
        reviews.edit(stopped.get(), "Stopped by <@" + actorId + ">. " + cancelled + " pending message(s) cancelled; summary follows.",
                List.of());
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
        reviews.edit(changed.get(), "Waiting for review (launcher set to " + store.label() + " by <@" + actorId + ">)",
                FreebieMessages.reviewControls(changed.get()));
        return "Launcher changed to **" + store.label() + "**.";
    }

    /** Approved giveaways that a newly configured channel can still claim. Owner approval is never bypassed. */
    public List<FreebieOffer> liveFor(FreebieStore store) { return repository.liveApproved(store, clock.instant()); }

    /** What anyone can claim right now, for {@code /free-games}; empty store means every launcher. */
    public List<FreebieOffer> liveNow(Optional<FreebieStore> store) {
        return repository.liveApproved(store, clock.instant(), FreebieMessages.MAX_LIVE_LISTED);
    }

    public int catchUp(FreebieSubscriptions.Subscription subscription) {
        int queued = 0;
        for (var offer : liveFor(subscription.store()))
            if (repository.planDelivery(offer, subscription, clock.instant())) queued++;
        return queued;
    }

    // ------------------------------------------------------------------ owner admin

    public String status(String actorId) {
        requireOwner(actorId);
        var overview = repository.overview();
        String text = "**Free-game alerts**\n"
                + "Sending: " + (repository.sendingPaused() ? "⏸️ **paused**" : "▶️ running") + "\n"
                + discovery.status() + "\n"
                + "Waiting for review: " + overview.pendingReviews() + "\n"
                + "Offers sending: " + overview.sending() + " • queued messages: " + overview.queuedDeliveries()
                + " (" + overview.retrying() + " retrying)\n"
                + "In flight now: " + sender.inFlight() + " • checked <t:" + clock.instant().getEpochSecond() + ":T>"
                + blockedStatus()
                + FreebieHealth.render(repository.health().snapshot(clock.instant()), jda.get(), config, clock.instant());
        return text.length() <= 4000 ? text : text.substring(0, 3900) + "\nDetails shortened; use delivery history for complete destination pages.";
    }

    private String blockedStatus() {
        StringBuilder result = new StringBuilder("\n**Blocked alerts** (up to 3; use history for all)\n");
        for (var d : repository.deliveries().blocked()) result.append('`').append(d.getString("offerId"))
                .append("` / `").append(d.getString("channelId")).append("`: ")
                .append(FreebieDeliveryStore.reason(d)).append(" Next check ")
                .append(FreebieDeliveryAdmin.time(d, "nextAttemptAt")).append('\n');
        return result.toString();
    }

    public String setPaused(String actorId, boolean paused) {
        requireOwner(actorId);
        repository.setSendingPaused(paused, actorId, clock.instant());
        reviews.notifyOwners(paused ? "⏸️ Sending paused by <@" + actorId + ">. Queued alerts wait; nothing is lost."
                : "▶️ Sending resumed by <@" + actorId + ">.");
        return paused ? "Paused. Up to " + FreebieSender.MAX_IN_FLIGHT + " messages already in flight may still arrive."
                : "Resumed.";
    }

    public String pollNow(String actorId) {
        requireOwner(actorId);
        discovery.pollNow();
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


