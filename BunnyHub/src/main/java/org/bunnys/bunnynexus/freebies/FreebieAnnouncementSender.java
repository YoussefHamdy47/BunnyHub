package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bson.Document;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * First posts of announcement pages, inside the alert sender's slots and on its thread. A claimed delivery row
 * either follows the page it belongs to (sent, failed, cancelled, or still being submitted), joins a posted page with
 * room silently, or creates the next page with every queued member of the same announcement and channel and submits
 * it once: one message, one ping (first page only, saved role only).
 *
 * <p>Retry safety matches single alerts: the page's uncertainty is persisted before submission; any later submitter
 * first searches recent history for a bot message carrying one of the page's footer references, and missing history
 * permission blocks instead of resending.
 */
final class FreebieAnnouncementSender {
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);
    private final FreebieRepository repository;
    private final FreebieFeatures features;
    private final Executor executor;
    private final Clock clock;
    private final FreebieNotifier notifier;
    private FreebieSubscriptions subscriptions;

    FreebieAnnouncementSender(FreebieRepository repository, FreebieFeatures features, Executor executor, Clock clock, FreebieNotifier notifier) {
        this.repository = repository; this.features = features; this.executor = executor; this.clock = clock; this.notifier = notifier;
    }

    /** Needed for owner retries: the destination is revalidated against current settings right before resubmitting. */
    FreebieAnnouncementSender withSubscriptions(FreebieSubscriptions value) { subscriptions = value; return this; }

    private FreebieAnnouncementStore store() { return repository.announcements(); }

    /** @return true when Discord work is in flight and its completion runs {@code release} exactly once */
    boolean start(JDA client, FreebieRepository.Claimed job, Guild guild, StandardGuildMessageChannel channel,
                  Optional<String> role, Runnable release) {
        Instant now = clock.instant();
        String key = job.announcementKey().orElseThrow();
        var pages = store().pages(key, job.channelId());
        for (Document page : pages)
            if (page.getList("members", String.class, List.of()).contains(job.offerId())) return follow(client, job, page, guild, channel, role, release);
        Document last = pages.isEmpty() ? null : pages.getLast();
        if (last != null && "SUBMITTING".equals(last.getString("state"))) {
            repository.release(job, now.plusSeconds(10), now); // Wait for that post, then join it silently.
            return false;
        }
        if (last != null && "SENT".equals(last.getString("state"))
                && last.getList("members", String.class, List.of()).size() < FreebieAnnouncementStore.PAGE_SIZE) {
            if (store().join(last.getString("_id"), job.offerId(), job.id(), now)) repository.markSent(job, last.getString("messageId"), now);
            else repository.release(job, now.plusSeconds(2), now);
            return false;
        }
        int next = pages.size() + 1;
        if (next > FreebieAnnouncementStore.MAX_PAGES) {
            repository.markCancelled(job, "announcement page limit reached", now);
            return false;
        }
        List<String> members = new ArrayList<>(List.of(job.offerId())), rows = new ArrayList<>(List.of(job.id()));
        for (Document peer : repository.routes().announcementPeers(key, job.channelId(), job.offerId(), FreebieAnnouncementStore.PAGE_SIZE * 2)) {
            if (members.size() >= FreebieAnnouncementStore.PAGE_SIZE) break;
            String offerId = peer.getString("offerId");
            if (members.contains(offerId) || !eligible(repository.offer(offerId).orElse(null), now) || !authorized(repository, key, offerId)) continue;
            members.add(offerId); rows.add(peer.getString("_id"));
        }
        var created = store().create(key, job.guildId(), job.channelId(), next, role, members, rows, job.token(), LEASE, now);
        if (created.isEmpty()) { repository.release(job, now.plusSeconds(2), now); return false; }
        return submit(client, job, created.get(), guild, channel, role, release);
    }

    private boolean follow(JDA client, FreebieRepository.Claimed job, Document page, Guild guild, StandardGuildMessageChannel channel,
                           Optional<String> role, Runnable release) {
        Instant now = clock.instant();
        switch (page.getString("state")) {
            case "SENT" -> repository.markSent(job, page.getString("messageId"), now);
            case "CANCELLED" -> repository.markCancelled(job, "announcement withdrawn before posting", now);
            case "FAILED" -> {
                // An owner retry confirmed after the page failed reopens it (history is checked first).
                if (retriedAfter(job, page)) {
                    var reopened = store().reopen(page, job.token(), LEASE, now);
                    if (reopened.isPresent()) return submit(client, job, reopened.get(), guild, channel, role, release);
                }
                FreebieFailure failure = FreebieFailure.valueOf(page.getString("failure"));
                if (repository.markFailed(job, failure, "announcement page was rejected", now))
                    repository.offer(job.offerId()).ifPresent(offer -> notifier.deliveryFailed(job, offer, failure));
            }
            default -> {
                Date lease = page.getDate("leaseUntil");
                if (job.token().equals(page.getString("token")) && lease != null && lease.toInstant().isAfter(now))
                    return submit(client, job, page, guild, channel, role, release);
                var taken = store().takeOver(page, job.token(), LEASE, now);
                if (taken.isEmpty()) { repository.release(job, now.plusSeconds(10), now); return false; }
                return submit(client, job, taken.get(), guild, channel, role, release);
            }
        }
        return false;
    }

    private boolean submit(JDA client, FreebieRepository.Claimed job, Document page, Guild guild, StandardGuildMessageChannel channel,
                           Optional<String> role, Runnable release) {
        Instant now = clock.instant();
        String id = page.getString("_id");
        boolean verify = page.getBoolean("verifyFirst", false);
        var self = guild.getSelfMember();
        if (verify && !self.hasPermission(channel, Permission.MESSAGE_HISTORY)) {
            store().release(id, job.token(), now);
            repository.deliveries().block(job, FreebieDeliveryStore.Block.HISTORY_PERMISSION, now);
            return false;
        }
        List<String> references = page.getList("deliveries", String.class, List.of()).stream().map(FreebieMessages::reference).toList();
        String selfId = client.getSelfUser().getId();
        CompletableFuture<Optional<Message>> existing;
        try {
            existing = verify ? channel.getHistory().retrievePast(50).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenApply(messages -> messages.stream().filter(m -> m.getAuthor().getId().equals(selfId))
                            .filter(m -> references.stream().anyMatch(r -> FreebieMessages.carriesReference(m, r))).findFirst())
                    : CompletableFuture.completedFuture(Optional.empty());
        } catch (RuntimeException failure) { existing = CompletableFuture.failedFuture(failure); }
        existing.whenComplete((found, error) -> dispatch(release, () -> {
            boolean submitted = false;
            try {
                Instant at = clock.instant();
                if (error != null) {
                    store().release(id, job.token(), at);
                    repository.deliveries().block(job, FreebieDeliveryStore.Block.HISTORY_FAILED, at);
                } else if (found.isPresent()) {
                    accepted(job, page, found.get().getId());
                } else {
                    var members = members(repository, page, at);
                    if (members.stream().noneMatch(m -> m.status() == FreebieAnnouncementCards.Status.LIVE)) {
                        store().cancel(id, job.token(), at);
                        store().release(id, job.token(), at);
                        repository.markCancelled(job, "offer no longer eligible", at);
                    } else if (!destinationStillValid(client, job)) {
                        // An owner retry is only a request: the channel must still be set up for this launcher, with
                        // the same ping role, access and permissions, immediately before Discord is asked again.
                        store().release(id, job.token(), at);
                        repository.deliveries().block(job, FreebieDeliveryStore.Block.DESTINATION, at);
                    } else if (repository.sendingPaused()) {
                        store().release(id, job.token(), at);
                        repository.release(job, at.plusSeconds(120), at);
                    } else if (!repository.deliveries().owns(job, at) || !store().submitting(id, job.token(), at)) {
                        store().release(id, job.token(), at);
                        repository.deliveries().block(job, FreebieDeliveryStore.Block.EXHAUSTED, at);
                    } else {
                        int number = page.getInteger("page", 1);
                        Optional<String> ping = number == 1 ? role.filter(r -> r.equals(page.getString("roleId"))) : Optional.empty();
                        var request = channel.sendMessage(FreebieAnnouncementCards.create(members, ping, features.checkout(), at))
                                .setNonce(FreebieAnnouncementStore.nonce(id)).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit();
                        submitted = true;
                        request.whenComplete((message, sendError) -> dispatch(release, () -> {
                            try {
                                if (sendError == null) accepted(job, page, message.getId());
                                else rejected(job, page, verify, sendError);
                            } catch (RuntimeException storage) { log(storage); }
                            finally { release.run(); }
                        }));
                    }
                }
            } catch (RuntimeException failure) { log(failure); } // The page's uncertainty marker survives storage failures.
            finally { if (!submitted) release.run(); }
        }));
        return true;
    }

    private void accepted(FreebieRepository.Claimed job, Document page, String messageId) {
        Instant now = clock.instant();
        store().sent(page.getString("_id"), job.token(), messageId, features.publishes(page.getString("channelId")), now);
        repository.markSent(job, messageId, now);
        var others = new ArrayList<>(page.getList("deliveries", String.class, List.of()));
        others.remove(job.id());
        repository.routes().followPage(others, messageId, now);
    }

    private void rejected(FreebieRepository.Claimed job, Document page, boolean verified, Throwable error) {
        Instant now = clock.instant();
        String id = page.getString("_id");
        FreebieFailure failure = FreebieFailure.classify(error);
        String detail = FailureDiagnostics.describe(error);
        if (verified || failure == FreebieFailure.TRANSIENT) {
            store().release(id, job.token(), now);
            repository.deliveries().block(job, FreebieDeliveryStore.Block.UNCERTAIN, now);
            return;
        }
        Duration delay = failure.retryable() ? FreebieFailure.backoff(job.attempts()) : null;
        if (delay != null) {
            store().release(id, job.token(), now);
            repository.markRetry(job, failure, detail, now.plus(delay), true, now);
            return;
        }
        store().failed(id, job.token(), failure.name(), now);
        if (repository.markFailed(job, failure, detail, now))
            repository.offer(job.offerId()).ifPresent(offer -> notifier.deliveryFailed(job, offer, failure));
    }

    /**
     * For a row the owner retried, the same destination check as single alerts ({@link FreebieDeliveryAdmin#destination}):
     * connected, channel present with View/Send/Embed (and history when uncertain), the saved role still pingable and a
     * current subscription for this launcher, channel and role. Rows never retried by an owner pass unchanged.
     */
    private boolean destinationStillValid(JDA client, FreebieRepository.Claimed job) {
        var row = repository.deliveries().get(job.id()).orElse(null);
        if (row == null) return false;
        if (row.getInteger("manualRetries", 0) == 0) return true;
        var offer = repository.offer(job.offerId()).orElse(null);
        return offer != null && subscriptions != null && FreebieDeliveryAdmin.destination(row, offer, subscriptions, client);
    }

    /** Whether this row's latest owner retry was confirmed after its page failed. */
    private boolean retriedAfter(FreebieRepository.Claimed job, Document page) {
        var row = repository.deliveries().get(job.id()).orElse(null);
        Date failed = page.getDate("finishedAt");
        if (row == null || failed == null || row.getInteger("manualRetries", 0) == 0) return false;
        var audit = row.getList("retryAudit", Document.class, List.of());
        return !audit.isEmpty() && audit.getLast().getDate("at") != null && audit.getLast().getDate("at").after(failed);
    }

    /** Release-keyed pages need the release snapshot; pages of single offers follow the offer's own approval. */
    static boolean authorized(FreebieRepository repository, String key, String offerId) {
        return key == null || !key.startsWith("epic|") || repository.releases() == null || repository.releases().approved(key, offerId);
    }

    static boolean eligible(FreebieOffer offer, Instant now) {
        return offer != null && (offer.state() == FreebieOffer.State.APPROVED || offer.state() == FreebieOffer.State.COMPLETED)
                && !offer.ended(now);
    }

    static FreebieAnnouncementCards.Status status(FreebieOffer offer, boolean ownerStopped, Instant now) {
        if (offer.state() == FreebieOffer.State.STOPPED && ownerStopped) return FreebieAnnouncementCards.Status.WITHDRAWN;
        return eligible(offer, now) ? FreebieAnnouncementCards.Status.LIVE : FreebieAnnouncementCards.Status.ENDED;
    }

    /** The page's members in page order, rebuilt from current state; purged offers are left out. */
    static List<FreebieAnnouncementCards.Member> members(FreebieRepository repository, Document page, Instant now) {
        List<String> offers = page.getList("members", String.class, List.of()), rows = page.getList("deliveries", String.class, List.of());
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (int i = 0; i < offers.size() && i < rows.size(); i++) {
            var doc = repository.offerDocument(offers.get(i)).orElse(null);
            if (doc == null) continue;
            var offer = FreebieOffer.read(doc);
            var status = status(offer, doc.getBoolean("stoppedByOwner", false), now);
            // A release page only shows a game as claimable when the committed release snapshot approved it.
            if (status == FreebieAnnouncementCards.Status.LIVE && !authorized(repository, page.getString("key"), offer.id()))
                status = FreebieAnnouncementCards.Status.ENDED;
            members.add(new FreebieAnnouncementCards.Member(offer, FreebieFacts.read(doc), rows.get(i), status));
        }
        return members;
    }

    private void dispatch(Runnable release, Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); } // Leases remain recoverable after shutdown.
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Announcement delivery will recover | " + FailureDiagnostics.describe(failure));
    }
}
