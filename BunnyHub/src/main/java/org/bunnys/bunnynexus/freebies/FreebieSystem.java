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
    private SteamFeed steamFeed;
    private FreebieDiscovery steamDiscovery;
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
    private final FreebieReviewDecisions decisions;
    private Instant nextSweep = Instant.MIN; // Discovery thread only.
    private boolean refreshDueSettled; // Discovery thread only.
    /**
     * The deleted channel/role sweep only catches deletions missed while the bot was offline; live delete events handle
     * the rest. It also runs once right after each start (nextSweep starts at MIN).
     */
    static final Duration SWEEP_EVERY = Duration.ofMinutes(30);

    // Package-private for tests; production code goes through start().
    FreebieSystem(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions,
                          Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this(config, FreebieFeatures.DEFAULT, repository, subscriptions, jda, clock, feed);
    }

    FreebieSystem(FreebieConfig config, FreebieFeatures features, FreebieRepository repository, FreebieSubscriptions subscriptions,
                  Supplier<JDA> jda, Clock clock, GamerPowerFeed feed) {
        this(config, features, repository, subscriptions, jda, clock, feed, Executors.newSingleThreadScheduledExecutor(daemon("FreebieSender")));
    }

    /** {@code sendingThread}: the one thread every send runs on; tests pass a scheduler they control. */
    FreebieSystem(FreebieConfig config, FreebieFeatures features, FreebieRepository repository, FreebieSubscriptions subscriptions,
                  Supplier<JDA> jda, Clock clock, GamerPowerFeed feed, ScheduledExecutorService sendingThread) {
        this.config = config; this.features = features; this.repository = repository; this.subscriptions = subscriptions;
        this.jda = jda; this.clock = clock; this.feed = feed;
        // New first posts are collected into per-channel drops; FREEBIE_GROUPED_ANNOUNCEMENTS=false posts per game.
        repository.dropWindow(features.drops());
        // One ping per channel per FREEBIE_PING_COOLDOWN_MINUTES (0: off). Null only for a mocked repository.
        FreebiePings pings = repository.pings();
        if (pings != null) pings.cooldown(features.pingCooldown());
        discoveryThread =Executors.newSingleThreadScheduledExecutor(daemon("FreebieDiscovery"));
        this.sendingThread = sendingThread;
        reviews = new FreebieReviewChannel(config, repository, subscriptions, jda, sendingThread, clock);
        discovery = new FreebieDiscovery(config, repository, feed, clock, reviews, this::ended);
        // Only GamerPower links go through a redirect; Epic's feed already gives the store page.
        if (feed.provider() == FreebieFeed.Provider.GAMERPOWER)
            discovery.afterPoll(new FreebieClaimLinks(repository, new GamerPowerLinks(), clock));
        notifier = new FreebieNotifier(repository, jda, clock, sendingThread);
        lifecycle = new FreebieLifecyclePlanner(repository, subscriptions);
        lifecycleWorker = new FreebieLifecycleWorker(repository, subscriptions, sendingThread, clock).withPings(pings);
        dmWorker = new FreebieDmWorker(repository, sendingThread, clock);
        approvals = new FreebieApprovals(repository, subscriptions, reviews, features, this::fanOut, clock);
        announcementWorker = new FreebieAnnouncementWorker(repository, features, sendingThread, clock);
        broadcasts = new FreebieBroadcasts(repository, subscriptions, clock, reviews::notifyOwners);
        broadcastWorker = new FreebieBroadcastWorker(repository, sendingThread, clock);
        publisher = new FreebiePublisher(repository, features, sendingThread, clock);
        sender = new FreebieSender(repository, jda, sendingThread, clock, notifier).withSubscriptions(subscriptions)
                .withLifecycle(lifecycleWorker).withDms(dmWorker).withBroadcasts(broadcastWorker).withPings(pings)
                .withAnnouncements(new FreebieAnnouncementSender(repository, features, sendingThread, clock, notifier)
                        .withSubscriptions(subscriptions).withPings(pings), announcementWorker, publisher);
        decisions = new FreebieReviewDecisions(config, repository, subscriptions, reviews, clock, this::fanOut,
                offerId -> background("withdrawn cards", () -> markEnded(offerId)));
    }

    public FreebieFeatures features() { return features; }

    public static Optional<FreebieSystem> current() { return Optional.ofNullable(instance); }
    public FreebieConfig config() { return config; }
    public FreebieRepository repository() { return repository; }
    public FreebieSubscriptions subscriptions() { return subscriptions; }
    private FreebieCleanup cleanup() { return new FreebieCleanup(subscriptions, repository.deliveries(), repository.notices()); }
    /** Personal DM alerts: subscribe, unsubscribe, consent. */
    public FreebieDmService dms() { return new FreebieDmService(config, repository, clock, sender::wake); }
    public FreebieDeliveryAdmin deliveryAdmin() { return new FreebieDeliveryAdmin(config, repository, subscriptions, jda, clock, sender::wake); }

    /**
     * Starts the two self-rescheduling ticks on the sender thread. Both slow down while their queues are idle, so
     * everything that queues work due now wakes them ({@link FreebieSender#wake}, {@link FreebieNotifier#wake}).
     */
    void startSending(Duration initialDelay) {
        sender.start(initialDelay);
        notifier.start(sendingThread, initialDelay);
    }

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
        if (features.steam()) {
            system.steamFeed = new SteamFeed(system.clock);
            system.steamDiscovery = new FreebieDiscovery(config.get(), repository, system.steamFeed, system.clock, system.reviews, system::ended);
        }
        if (features.resolveGamerPowerLinks()) system.discovery.withLinkResolver(new FreebieStorePages(new GamerPowerLinks()));
        if (features.egdata()) {
            system.egdataFeed = new EgDataFeed(system.clock);
            system.enrichment = new FreebieEnrichment(repository.offerCollection(), system.egdataFeed, system.clock);
        }
        instance = system;
        system.discoveryThread.scheduleWithFixedDelay(guarded("housekeeping", system::housekeepingTick), 20, 30, TimeUnit.SECONDS);
        Runnable runSoon = () -> system.housekeepingRequested = true;
        system.discovery.onNewGiveaways(runSoon);
        system.epicDiscovery.onNewGiveaways(runSoon);
        if (system.steamDiscovery != null) system.steamDiscovery.onNewGiveaways(runSoon);
        system.discovery.start(system.discoveryThread, 15);
        system.epicDiscovery.start(system.discoveryThread, 25);
        if (system.steamDiscovery != null) system.steamDiscovery.start(system.discoveryThread, 35);
        if (system.enrichment != null) system.enrichment.start(system.discoveryThread, 45);
        // Publications left open for a channel that is no longer enabled stop before the first tick can claim one.
        system.discoveryThread.execute(guarded("publishing clean-up", system.publisher::cleanUp));
        system.startSending(Duration.ofSeconds(10));
        BunnyLog.success("[Freebies] Started. Review channel " + config.get().reviewChannelId() + ", polling every "
                + config.get().pollMinutes() + " min. Grouped announcements " + (features.grouped() ? "on (drop window "
                + features.dropWindow().toSeconds() + " s)" : "off")
                + ", ping cooldown " + (features.pingCooldown().isZero() ? "off" : features.pingCooldown().toMinutes() + " min")
                + ", automatic publishing " + (features.publishEnabled() ? "on for " + features.publishChannels().size() + " channel(s)" : "off")
                + ", Steam source " + (features.steam() ? "on" : "off") + ".");
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
        if (steamFeed != null) steamFeed.close();
        if (egdataFeed != null) egdataFeed.close();
    }

    // ------------------------------------------------------------------ housekeeping (discovery thread)

    /**
     * Every 30 s while something is in progress; every 2 minutes while nothing is, since each run costs about twenty
     * database round trips. A poll that finds a new game asks for a run, so it is handled as quickly as before.
     */
    void housekeepingTick() {
        if (!housekeepingDue(housekeepingActive, housekeepingRequested, ++quietTicks)) return;
        quietTicks = 0;
        housekeepingRequested = false;
        housekeeping();
    }

    static boolean housekeepingDue(boolean active, boolean requested, int quietTicks) {
        return active || requested || quietTicks >= QUIET_TICKS;
    }

    static final int QUIET_TICKS = 4;
    private volatile boolean housekeepingActive = true, housekeepingRequested;
    private int quietTicks; // Discovery thread only.

    void housekeeping() {
        // Stays set if this run stops early (disconnected, or a failure), so the next tick runs on time.
        housekeepingActive = true;
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
        // The kill switch as the database has it (another process may have written it); the sender's tick uses this copy.
        repository.refreshSendingPaused();
        // Expired leases of every sender queue are recovered here, at most every 2 minutes against a 3-minute lease, and not on the
        // sender's tick: a recovered row is due at once and still "verify first", so the sender is woken for it and
        // checks the channel's history before any resend. A pause cannot strand them either.
        long due = repository.recoverExpiredLeases(now) + repository.lifecycle().recover(now);
        // Lifecycle jobs are database-planned.
        var planned = lifecycle.run(now);
        due += planned.edits() + planned.reminders() + repository.dmJobs().recover(now);
        if (repository.broadcasts() != null) due += repository.broadcasts().recover(now);
        // Publications: crashed leases, jobs of channels no longer enabled, and pages not planned yet.
        if (publisher.housekeeping()) due++;
        // Interrupted notice enqueues and reconciliations are replayed; a notice queued by that is sent at once.
        if (repository.notices() != null && repository.notices().recover(now) > 0) notifier.wake();
        repository.dmSubscribers().expirePending(now);
        // Corrections and region changes saved on offers reach their posted announcement pages as silent edits.
        if (repository.announcements() != null) due += repository.announcements().propagateCorrections(now, 50);
        // Once per process: pages refreshed before refreshed() cleared their due time stop matching the refresh claim.
        if (!refreshDueSettled && repository.announcements() != null)
            refreshDueSettled = repository.announcements().settleRefreshDue(now, 200) == 0;
        // Release approvals committed before a crash finish reconciling into per-game state and planning.
        approvals.recover();
        due += broadcasts.housekeeping();
        if (due > 0) sender.wake();
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        checkUpcoming();
        if (!now.isBefore(nextSweep)) {
            var swept = cleanup().sweep(client, now);
            nextSweep = now.plus(SWEEP_EVERY);
            if (swept.channels() + swept.roles() > 0) notifier.wake(); // Each one may have queued a notice.
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
        var pending = repository.offersIn(FreebieOffer.State.PENDING, 200);
        for (var offer : pending) {
            if (offer.ended(now)) ended(offer, "ended before review");
            // Once its review message is up (the audit trail and the Stop button), an auto-send launcher goes out.
            // A game of an Epic release is auto-approved through a release snapshot, so the release shares one post.
            else if (config.autoSends(offer.store()) && offer.reviewMessageId().isPresent()) {
                if (!approvals.autoApprove(offer)) decisions.autoApprove(offer);
            }
        }
        var refreshes = repository.routes() == null ? null : repository.routes().reviewRefreshes(10);
        for (var offer : refreshes == null ? List.<FreebieOffer>of() : refreshes) {
            reviews.edit(offer, "Waiting for review (updated with details from another source)",
                    FreebieMessages.reviewControls(offer, repository.routes().groupPending(offer, now)));
            repository.routes().clearReviewRefresh(offer.id());
        }
        var approved = repository.offersIn(FreebieOffer.State.APPROVED, 200);
        for (var offer : approved) {
            if (offer.ended(now)) { ended(offer, "the giveaway ended"); continue; }
            if (!offer.fanoutDone()) { fanOut(offer); continue; }
            var counts = repository.counts(offer.id());
            if (counts.open() == 0)
                repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now)
                        .ifPresent(reviews::summarize);
            else reviews.progress(offer, counts, now);
        }
        var awaiting = repository.summaries().awaiting(now, 50);
        for (var offer : awaiting)
            if (repository.counts(offer.id()).open() == 0) reviews.summarize(offer);
        housekeepingActive = due > 0 || !pending.isEmpty() || !approved.isEmpty() || !awaiting.isEmpty();
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
            return;
        }
        // Approval click or housekeeping: the rows (and DMs) just planned are sent without waiting for an idle poll.
        sender.wake();
    }

    /** Recorded as the approver of auto-sent offers; never a real user ID, so it is never mentioned. */
    public static final String AUTO_ACTOR = "auto";

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
        FreebieLifecycle.endTrigger(offer, owner, now).ifPresent(end -> {
            lifecycle.ended(offerId, end, now);
            sender.wake(); // Ended-card edits and the touched announcement pages are due now.
        });
    }

    // ------------------------------------------------------------------ deletion events (gateway threads)

    /** A guild channel was deleted; the database work runs on the freebie discovery thread. */
    public void channelDeleted(String guildId, String channelId, String name) {
        background("channel cleanup", () -> {
            cleanup().channelDeleted(guildId, channelId, Optional.ofNullable(name), clock.instant());
            notifier.wake(); // The owner's notice, when one was queued.
        });
    }

    /** A role was deleted; settings that pinged it keep posting without a ping. */
    public void roleDeleted(String guildId, String roleId, String name) {
        background("role cleanup", () -> {
            cleanup().roleDeleted(guildId, roleId, Optional.ofNullable(name), clock.instant());
            notifier.wake();
        });
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
    public FreebieSourceDecisions.Outcome resolveSource(String actor, String offerId, String action) {
        requireOwner(actor);
        return FreebieSourceDecisions.resolve(repository.sources(), actor, offerId, action, clock.instant());
    }

    /**
     * For Clear finished: whether a source decision button still belongs to an open question. Any other button counts
     * as open (its own rules apply). Database read: never call this on a JDA thread.
     */
    public boolean sourceQuestionOpen(String customId) {
        // The weekly "Free next week" card is optional: it never holds Clear finished back.
        if (customId.startsWith(FreebieMessages.BUTTON_PREFIX + ":" + FreebieUpcoming.ACTION + ":")) return false;
        return FreebieSourceDecisions.question(customId).map(q -> repository.sources().questionOpen(q[0], q[1])).orElse(true);
    }

    public java.time.Instant now() { return clock.instant(); }

    /** Next week's free Epic games, from the last Epic poll (no request of its own). */
    public List<FreebieUpcoming> upcoming() { return epicFeed == null ? List.of() : epicFeed.upcoming(); }

    /** "Prepare announcement" on the weekly card: the usual announcement preview, to Epic alert channels. */
    public FreebieBroadcasts.Reply upcomingAnnouncement(String actorId) {
        var games = upcoming();
        if (games.isEmpty()) return new FreebieBroadcasts.Problem("Epic hasn't listed next week's free games yet.");
        return broadcasts.preview(actorId, FreebieUpcoming.TITLE, FreebieUpcoming.body(games),
                new FreebieBroadcasts.Target(Optional.of(FreebieStore.EPIC.section()), false));
    }

    private String upcomingPosted; // Discovery thread only; null until read from the database.

    /** Once a week, when Epic lists new upcoming games: the card for the owners. Discovery thread, after a poll. */
    void checkUpcoming() {
        var games = upcoming();
        if (games.isEmpty()) return;
        String week = FreebieUpcoming.key(games);
        if (upcomingPosted == null) upcomingPosted = repository.upcomingPosted().orElse("");
        if (week.equals(upcomingPosted)) return;
        if (!reviews.postUpcoming(FreebieUpcoming.reviewCard(games))) return;
        repository.upcomingPosted(week, clock.instant());
        upcomingPosted = week;
    }

    /**
     * First click: shows exactly what would be sent and asks for a second confirmation. A game of an Epic release is
     * previewed as a one-game release snapshot (see {@link FreebieApprovals}); other games use the single approval.
     */
    public Reply requestApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return approvals.isRelease(offerId) ? approvals.request(actorId, offerId, false) : decisions.requestApproval(actorId, offerId);
    }

    /** Legacy single confirmation; a game of an Epic release must be confirmed through its release snapshot instead. */
    public String confirmApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return confirmApproval(actorId, offerId, false);
    }

    /** {@code quiet}: the owner pressed "Send without ping". */
    public String confirmApproval(String actorId, String offerId, boolean quiet) {
        requireOwner(actorId);
        return approvals.isRelease(offerId) ? approvals.confirmSingle(actorId, offerId) : decisions.confirmApproval(actorId, offerId, quiet);
    }

    public String reject(String actorId, String offerId) { return decisions.reject(actorId, offerId); }

    /** First click of "Approve release": shows every pending game of the release; approves nothing yet. */
    public Reply requestGroupApproval(String actorId, String offerId) {
        requireOwner(actorId);
        return approvals.request(actorId, offerId, true);
    }

    /** Confirmation of a previewed release snapshot ({@code confirmrelease-<r|s>-<token>}). */
    public String confirmRelease(String actorId, String offerId, String action) {
        requireOwner(actorId);
        String[] parts = action.split("-", 3);
        if (parts.length != 3 || !parts[1].matches("[rsRS]") || !parts[2].matches("[0-9a-f]{24}")) return "This control is malformed.";
        // Upper case: the same confirmation, without a ping.
        return approvals.confirmRelease(actorId, offerId, parts[1].equalsIgnoreCase("r"), parts[2], Character.isUpperCase(parts[1].charAt(0)));
    }

    /** /freebie-admin announce: saves a draft and shows exactly what every subscribed server would get. */
    public FreebieBroadcasts.Reply announce(String actorId, String title, String body) { return announce(actorId, title, body, null, false); }

    /** Every server with free-game alerts and its main alert channel (the one announcements use). Blocking. */
    public Map<String, String> mainChannels() { return broadcasts.recipients(); }

    /**
     * {@code section}: a section code ({@link FreebieSection#code()}) to reach only servers with that section's
     * channel, or null for every server's main channel. {@code fallback}: servers without that section get it in
     * their main channel instead of being skipped.
     */
    public FreebieBroadcasts.Reply announce(String actorId, String title, String body, String section, boolean fallback) {
        requireOwner(actorId);
        return broadcasts.preview(actorId, title, body, FreebieBroadcasts.target(section, fallback));
    }

    public FreebieBroadcasts.Reply announce(String actorId, String title, String body, String section, boolean fallback, String imageUrl) {
        requireOwner(actorId);
        return broadcasts.preview(actorId, title, body, FreebieBroadcasts.target(section, fallback), imageUrl);
    }

    /** The owner's confirmation button ({@code broadcast-<token>}). */
    public String confirmAnnouncement(String actorId, String id, String action) {
        requireOwner(actorId);
        boolean quiet = action.startsWith(FreebieBroadcasts.SEND_QUIET);
        String token = action.substring((quiet ? FreebieBroadcasts.SEND_QUIET : FreebieBroadcasts.SEND).length());
        if (!token.matches("[0-9a-f]{24}")) return "This control is malformed.";
        String reply = broadcasts.confirm(actorId, id, token, !quiet);
        sender.wake(); // One job per server was just planned (nothing is claimed when the draft had expired).
        return reply;
    }

    public String stopAnnouncement(String actorId, String id) {
        requireOwner(actorId);
        return broadcasts.stop(actorId, id);
    }

    public static java.util.List<net.dv8tion.jda.api.components.actionrow.ActionRow> announcementStopControls(String id) {
        return FreebieBroadcasts.stopControls(id);
    }

    /** Kill switch for one offer: messages already sent stay, everything not yet sent is cancelled. */
    public String stop(String actorId, String offerId) { return decisions.stop(actorId, offerId); }
    /** Owner fixes a wrongly detected launcher before approving; the confirmation then counts the new audience. */
    public String changeStore(String actorId, String offerId, String storeId) { return decisions.changeStore(actorId, offerId, storeId); }

    /** Approved (or auto-sent) giveaways that a newly configured channel can still claim. */
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
        // One request is one drop: every game it queues in this channel shares one message, without a ping.
        Optional<String> drop = catchUpDrop(channelId);
        for (var subscription : subscriptions.forGuild(guildId))
            if (subscription.channelId().equals(channelId) && stores.test(subscription.store())) {
                live += liveFor(subscription.store()).size();
                queued += catchUp(subscription, drop);
            }
        // Only now, with every row of the request inserted, do they become due together.
        drop.ifPresent(key -> repository.releaseDrop(key, clock.instant()));
        if (queued > 0) sender.wake();
        return new CatchUp(live, queued);
    }

    public int catchUp(FreebieSubscriptions.Subscription subscription) {
        var drop = catchUpDrop(subscription.channelId());
        int queued = catchUp(subscription, drop);
        drop.ifPresent(key -> repository.releaseDrop(key, clock.instant()));
        if (queued > 0) sender.wake();
        return queued;
    }

    private int catchUp(FreebieSubscriptions.Subscription subscription, Optional<String> drop) {
        int queued = 0;
        for (var offer : liveFor(subscription.store()))
            if (repository.planDelivery(offer, subscription, drop, clock.instant())) queued++;
        return queued;
    }

    /** A fresh catch-up drop key, or empty for the per-game rollback. */
    private Optional<String> catchUpDrop(String channelId) {
        return features.drops().map(window -> FreebieDrops.catchUpKey(channelId, clock.instant()));
    }

    // ------------------------------------------------------------------ owner admin

    public String status(String actorId) {
        requireOwner(actorId);
        return FreebieStatusText.render(repository, config, discovery.status() + (epicDiscovery == null ? "" : "\n" + epicDiscovery.status())
                + (steamDiscovery == null ? "" : "\n" + steamDiscovery.status())
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
        // The sender's in-memory copy changes with this write: a pause stops claims at once, a resume sends at once.
        repository.setSendingPaused(paused, actorId, clock.instant());
        if (!paused) sender.wake();
        reviews.notifyOwners(paused ? "⏸️ Sending paused by <@" + actorId + ">. Queued alerts wait; nothing is lost."
                : "▶️ Sending resumed by <@" + actorId + ">.");
        return paused ? "Paused. Up to " + FreebieSender.MAX_IN_FLIGHT + " messages already in flight may still arrive."
                : "Resumed.";
    }

    public String pollNow(String actorId) {
        requireOwner(actorId);
        discovery.pollNow();
        if (epicDiscovery != null) epicDiscovery.pollNow();
        if (steamDiscovery != null) steamDiscovery.pollNow();
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

