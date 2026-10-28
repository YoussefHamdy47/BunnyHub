package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
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
    private final FreebieSubscriptions subscriptions;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final GamerPowerFeed feed;
    private final ScheduledExecutorService discoveryThread, sendingThread;
    private final FreebieSender sender;
    private final FreebieNotifier notifier;
    private final FreebieReviewChannel reviews;
    private final FreebieDiscovery discovery;
    private EpicFeed epicFeed;
    private FreebieDiscovery epicDiscovery;
    private final FreebieLifecyclePlanner lifecycle;
    private final FreebieLifecycleWorker lifecycleWorker;
    private final FreebieDmWorker dmWorker;
    private final FreebieFeatures features;
    private final FreebieAnnouncementWorker announcementWorker;
    private final FreebiePublisher publisher;
    private final FreebieApprovals approvals;
    private final FreebieBroadcasts broadcasts;
    private final FreebieBroadcastWorker broadcastWorker;
    private EgDataFeed egdataFeed;
    private FreebieEnrichment enrichment;
    private Instant nextSweep = Instant.MIN; // Discovery thread only.
    static final Duration SWEEP_EVERY = Duration.ofMinutes(10);

    // Package-private for tests; production code goes through start().
    FreebieSystem(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions,
                          Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this(config, FreebieFeatures.DEFAULT, repository, subscriptions, jda, clock, feed);
    }

    FreebieSystem(FreebieConfig config, FreebieFeatures features, FreebieRepository repository, FreebieSubscriptions subscriptions,
                  Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this.config = config; this.features = features; this.repository = repository; this.subscriptions = subscriptions;
        this.jda = jda; this.clock = clock; this.feed = feed;
        discoveryThread = Executors.newSingleThreadScheduledExecutor(daemon("FreebieDiscovery"));
        sendingThread = Executors.newSingleThreadScheduledExecutor(daemon("FreebieSender"));
        reviews = new FreebieReviewChannel(config, repository, subscriptions, jda, sendingThread, clock);
        discovery = new FreebieDiscovery(config, repository, feed, clock, reviews, this::ended);
        notifier = new FreebieNotifier(repository, jda, clock, sendingThread);
        lifecycle = new FreebieLifecyclePlanner(repository, subscriptions);
        lifecycleWorker = new FreebieLifecycleWorker(repository, subscriptions, sendingThread, clock);
        dmWorker = new FreebieDmWorker(repository, sendingThread, clock);
        approvals = new FreebieApprovals(repository, subscriptions, reviews, features, this::fanOut, clock);
        announcementWorker = new FreebieAnnouncementWorker(repository, features, sendingThread, clock);
        broadcasts = new FreebieBroadcasts(repository, subscriptions, clock, reviews::notifyOwners);
        broadcastWorker = new FreebieBroadcastWorker(repository, sendingThread, clock);
        publisher = new FreebiePublisher(repository, features, sendingThread, clock);
        sender = new FreebieSender(repository, jda, sendingThread, clock, notifier).withSubscriptions(subscriptions)
                .withLifecycle(lifecycleWorker).withDms(dmWorker).withBroadcasts(broadcastWorker)
                .withAnnouncements(new FreebieAnnouncementSender(repository, features, sendingThread, clock, notifier).withSubscriptions(subscriptions), announcementWorker, publisher);
    }

    public FreebieFeatures features() { return features; }

    public static Optional<FreebieSystem> current() { return Optional.ofNullable(instance); }
    public FreebieConfig config() { return config; }
    public FreebieRepository repository() { return repository; }
    public FreebieSubscriptions subscriptions() { return subscriptions; }
    private FreebieCleanup cleanup() { return new FreebieCleanup(subscriptions, repository.deliveries(), repository.notices()); }
    /** Personal DM alerts: subscribe, unsubscribe, consent. */
    public FreebieDmService dms() { return new FreebieDmService(config, repository, clock); }
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
        var features = FreebieFeatures.fromEnvironment(BunnyLog::warning);
        var system = new FreebieSystem(config.get(), features, repository, subscriptions, client::getJDA, Clock.systemUTC(), new GamerPowerFeed());
        system.epicFeed = new EpicFeed(system.clock);
        system.epicDiscovery = new FreebieDiscovery(config.get(), repository, system.epicFeed, system.clock, system.reviews, system::ended);
        if (features.resolveGamerPowerLinks()) system.discovery.withLinkResolver(new GamerPowerLinks());
        if (features.egdata()) {
            system.egdataFeed = new EgDataFeed(system.clock);
            system.enrichment = new FreebieEnrichment(repository.offerCollection(), system.egdataFeed, system.clock);
        }
        instance = system;
        system.discoveryThread.scheduleWithFixedDelay(guarded("housekeeping", system::housekeeping), 20, 30, TimeUnit.SECONDS);
        system.discovery.start(system.discoveryThread, 15);
        system.epicDiscovery.start(system.discoveryThread, 25);
        if (system.enrichment != null) system.enrichment.start(system.discoveryThread, 45);
        system.sendingThread.scheduleWithFixedDelay(guarded("sender", system.sender::tick), 10, 1, TimeUnit.SECONDS);
        system.sendingThread.scheduleWithFixedDelay(guarded("notices", system.notifier::tick), 10, 5, TimeUnit.SECONDS);
        BunnyLog.success("[Freebies] Started. Review channel " + config.get().reviewChannelId() + ", polling every "
                + config.get().pollMinutes() + " min. Grouped announcements " + (features.grouped() ? "on" : "off")
                + ", automatic publishing " + (features.publishEnabled() ? "on for " + features.publishChannels().size() + " channel(s)" : "off") + ".");
    }

    public static synchronized void shutdown() {
        var system = instance;
        if (system == null) return;
        instance = null;
        system.close();
    }

    void close() {
        sender.close();
        lifecycleWorker.close();
        dmWorker.close();
        announcementWorker.close();
        broadcastWorker.close();
        publisher.close();
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
        if (epicFeed != null) epicFeed.close();
        if (egdataFeed != null) egdataFeed.close();
    }

    // ------------------------------------------------------------------ housekeeping (discovery thread)

    void housekeeping() {
        Instant now = clock.instant();
        // Advance legacy identity migration in bounded batches, even while disconnected.
        if (repository.sources() != null) repository.sources().backfill();
        // Database-only work: it needs no Discord connection.
        if (repository.retention().due(now)) {
            var purged = repository.retention().purge(now, config.retentionDays(), FreebieRetention.Limits.DEFAULT);
            if (purged.deliveries() + purged.offers() + purged.notices() > 0)
                BunnyLog.info("[Freebies] Retention purge removed " + purged.deliveries() + " alert(s), " + purged.offers()
                        + " offer(s) and " + purged.notices() + " notice record(s).");
        }
        // Lifecycle jobs are database-planned; expired leases are also recovered here, so a pause cannot strand them.
        repository.lifecycle().recover(now);
        lifecycle.run(now);
        repository.dmJobs().recover(now);
        repository.dmSubscribers().expirePending(now);
        // Corrections and region changes saved on offers reach their posted announcement pages as silent edits.
        if (repository.announcements() != null) repository.announcements().propagateCorrections(now, 50);
        // Release approvals committed before a crash finish reconciling into per-game state and planning.
        approvals.recover();
        broadcasts.housekeeping();
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        if (!now.isBefore(nextSweep)) {
            cleanup().sweep(client, now);
            nextSweep = now.plus(SWEEP_EVERY);
        }
        // Held source reports are announced once with their decision buttons, so none is suppressed unnoticed.
        if (repository.sources() != null)
            for (var question : repository.sources().unannounced(5))
                if (reviews.postQuestion(question.parent().getString("store"), FreebieSourceDecisions.view(question.parent(), question.token())))
                    repository.sources().announced(question);
        for (int i = 0; i < 5; i++) {
            var offer = repository.reviews().claim(clock.instant());
            if (offer.isEmpty()) break;
            reviews.post(client, offer.get());
        }
        for (var offer : repository.offersIn(FreebieOffer.State.PENDING, 200))
            if (offer.ended(now)) ended(offer, "ended before review");
        var refreshes = repository.routes() == null ? null : repository.routes().reviewRefreshes(10);
        for (var offer : refreshes == null ? List.<FreebieOffer>of() : refreshes) {
            reviews.edit(offer, "Waiting for review (updated with details from another source)",
                    FreebieMessages.reviewControls(offer, repository.routes().groupPending(offer, now)));
            repository.routes().clearReviewRefresh(offer.id());
        }
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
        // The route is reserved once, before any row exists: announcement pages for new approvals, legacy otherwise.
        if (repository.routes() != null) repository.routes().route(offer.id(), features.grouped(), Optional.empty());
        // A release-keyed route sends only what a committed release snapshot approved; per-game state alone never does.
        if (!approvals.authorized(offer)) {
            BunnyLog.warning("[Freebies] " + offer.id() + " is approved but not in its release approval; nothing planned.");
            repository.setFanoutDone(offer.id(), 0);
            return;
        }
        // Channels known to be deleted are skipped; an uncached one is planned and judged by the sender later.
        int planned = repository.planDeliveries(offer, FreebieCleanup.deliverable(jda.get(), subscriptions.forStore(offer.store())), now);
        // DMs are planned before fan-out is recorded as done, so a crash in between replans them (idempotently).
        if (config.dmEnabled()) repository.dmJobs().plan(offer.id(), repository.dmSubscribers().following(offer.store().section()), now);
        repository.setFanoutDone(offer.id(), planned);
        if (repository.offer(offer.id()).map(FreebieOffer::state).orElse(null) == FreebieOffer.State.STOPPED) {
            repository.cancelPending(offer.id(), "stopped while sending was being planned", now);
            repository.dmJobs().cancelForOffer(offer.id(), now);
        }
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
                        repository.dmJobs().cancelForOffer(stopped.id(), now);
                        markEnded(stopped.id());
                        reviews.edit(stopped, "Stopped automatically — " + why + ". Summary follows.",
                                List.of());
                    });
            // Only late-subscriber catch-up sends can still be queued; the sender also refuses ended offers.
            case COMPLETED -> {
                repository.cancelPending(offer.id(), why, now);
                repository.dmJobs().cancelForOffer(offer.id(), now);
                markEnded(offer.id());
            }
            default -> { /* Already final. */ }
        }
    }

    /** Queues ended-card edits now instead of at the next housekeeping run, when the trigger rules agree. */
    private void markEnded(String offerId) {
        Instant now = clock.instant();
        var offer = repository.offer(offerId).orElse(null);
        if (offer == null) return;
        // Owner stops are recorded on the offer itself; housekeeping reads the same flag.
        boolean owner = offer.state() == FreebieOffer.State.STOPPED && repository.stoppedByOwner(offerId);
        FreebieLifecycle.endTrigger(offer, owner, now).ifPresent(end -> lifecycle.ended(offerId, end, now));
    }

    // ------------------------------------------------------------------ deletion events (gateway threads)

    /** A guild channel was deleted; the database work runs on the freebie discovery thread. */
    public void channelDeleted(String guildId, String channelId, String name) {
        background("channel cleanup", () -> cleanup().channelDeleted(guildId, channelId, Optional.ofNullable(name), clock.instant()));
    }

    /** A role was deleted; settings that pinged it keep posting without a ping. */
    public void roleDeleted(String guildId, String roleId, String name) {
        background("role cleanup", () -> cleanup().roleDeleted(guildId, roleId, Optional.ofNullable(name), clock.instant()));
    }

    private void background(String name, Runnable task) {
        try { discoveryThread.execute(guarded(name, task)); }
        catch (RejectedExecutionException shuttingDown) {
            BunnyLog.warning("[Freebies] Shutting down; " + name + " is left to the next housekeeping sweep.");
        }
    }

    // ------------------------------------------------------------------ owner actions (command workers)

    public sealed interface Reply permits Text, Prompt {}
    public record Text(String message) implements Reply {}
    public record Prompt(MessageCreateData message) implements Reply {}

    public FreebieDeliveryAdmin.View sourceDecision(String actor) {
        requireOwner(actor);
        return FreebieSourceDecisions.view(repository.sources());
    }
    public String resolveSource(String actor, String offerId, String action) {
        requireOwner(actor);
        return FreebieSourceDecisions.resolve(repository.sources(), actor, offerId, action, clock.instant());
    }

    /**
     * First click: shows exactly what would be sent and asks for a second confirmation. A game of an Epic release is
     * previewed as a one-game release snapshot (see {@link FreebieApprovals}).
     */
    public Reply requestApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return approvals.request(actorId, offerId, false);
    }

    public String confirmApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return approvals.confirmSingle(actorId, offerId);
    }

    /** First click of "Approve release": shows every pending game of the release; approves nothing yet. */
    public Reply requestGroupApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return approvals.request(actorId, offerId, true);
    }

    /** Confirmation of a previewed release snapshot ({@code confirmrelease-<r|s>-<token>}). */
    public String confirmRelease(String actorId, String offerId, String action) {
        requireOwner(actorId);
        String[] parts = action.split("-", 3);
        if (parts.length != 3 || !parts[1].matches("[rs]") || !parts[2].matches("[0-9a-f]{24}")) return "This control is malformed.";
        return approvals.confirmRelease(actorId, offerId, parts[1].equals("r"), parts[2]);
    }

    /** /freebie-admin announce: saves a draft and shows exactly what every subscribed server would get. */
    public FreebieBroadcasts.Reply announce(String actorId, String title, String body) {
        requireOwner(actorId);
        return broadcasts.preview(actorId, title, body);
    }

    /** The owner's confirmation button ({@code broadcast-<token>}). */
    public String confirmAnnouncement(String actorId, String id, String action) {
        requireOwner(actorId);
        String token = action.substring(FreebieBroadcasts.SEND.length());
        if (!token.matches("[0-9a-f]{24}")) return "This control is malformed.";
        return broadcasts.confirm(actorId, id, token);
    }

    public String stopAnnouncement(String actorId, String id) {
        requireOwner(actorId);
        return broadcasts.stop(actorId, id);
    }

    public static java.util.List<net.dv8tion.jda.api.components.actionrow.ActionRow> announcementStopControls(String id) {
        return FreebieBroadcasts.stopControls(id);
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
        var stopped = repository.stopByOwner(offerId, FreebieOffer.State.APPROVED, actorId, now);
        if (stopped.isEmpty()) stopped = repository.stopByOwner(offerId, FreebieOffer.State.COMPLETED, actorId, now);
        if (stopped.isEmpty()) return "This offer is not currently sending.";
        long cancelled = repository.cancelPending(offerId, "stopped by owner", now);
        repository.dmJobs().cancelForOffer(offerId, now);
        background("withdrawn cards", () -> markEnded(offerId));
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

    /** Games live for a channel's launchers, and how many of them were newly queued there. */
    public record CatchUp(int live, int queued) {}

    /**
     * The one catch-up path (button, section form, auto-setup): the channel's saved settings for the given
     * launchers, each still-live approved game at most once per channel (the delivery ID), queued at catch-up
     * priority so first posts of new approvals go first. Only plans; never waits for a send.
     */
    public CatchUp catchUpChannel(String guildId, String channelId, java.util.function.Predicate<FreebieStore> stores) {
        int live = 0, queued = 0;
        for (var subscription : subscriptions.forGuild(guildId))
            if (subscription.channelId().equals(channelId) && stores.test(subscription.store())) {
                live += liveFor(subscription.store()).size();
                queued += catchUp(subscription);
            }
        return new CatchUp(live, queued);
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
        return FreebieStatusText.render(repository, config, discovery.status() + (epicDiscovery == null ? "" : "\n" + epicDiscovery.status())
                + (enrichment == null ? "" : "\n" + enrichment.status()) + "\nHeld source reports awaiting a decision: " + (repository.sources() == null ? 0 : repository.sources().openQuestions()) + " (/freebie-admin sources)"
                + "\n" + broadcasts.status() + (repository.publications() == null ? "" : "\n" + FreebieStatusText.publishing(features, repository.publications().counts())),
                sender.inFlight(), jda.get(), clock.instant());
    }

    static String lifecycleStatus(FreebieLifecycleStore.Stats edits, FreebieLifecycleStore.Stats reminders, Instant now) {
        return FreebieStatusText.lifecycle(edits, reminders, now);
    }
    static String retentionStatus(int days, Optional<FreebieRetention.Last> last) { return FreebieStatusText.retention(days, last); }
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
        if (epicDiscovery != null) epicDiscovery.pollNow();
        return "Source checks requested; any active failure backoff is respected. New giveaways will appear in the review channel.";
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
