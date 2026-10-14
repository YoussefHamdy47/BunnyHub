package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import org.bunnys.bunnynexus.freebies.FreebieDmJobs.Job;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends personal game DMs on the freebie sender thread, inside the alert sender's budget: {@link FreebieSender}
 * calls {@link #tick} only once no channel alert is due, and all lower-priority work together holds at most
 * {@link FreebieSender#BACKGROUND_SLOTS}. The global pause applies because the sender returns before calling here.
 * Database work and outcomes run on the sender thread, never on JDA callback threads.
 */
final class FreebieDmWorker {
    static final int MAX_IN_FLIGHT = FreebieSender.MAX_IN_FLIGHT / 2;
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);

    private final FreebieRepository repository;
    private final Executor executor;
    private final Clock clock;
    private int own; // Sender thread only.
    private volatile boolean closed;

    FreebieDmWorker(FreebieRepository repository, Executor executor, Clock clock) {
        this.repository = repository; this.executor = executor; this.clock = clock;
    }

    void close() { closed = true; }
    int inFlight() { return own; }
    private FreebieDmJobs jobs() { return repository.dmJobs(); }
    private FreebieDmSubscribers subscribers() { return repository.dmSubscribers(); }

    /** Everything due, within the caps; the sender instead calls {@link #prepare} and {@link #claimOne}. */
    void tick(JDA client, AtomicInteger shared, AtomicInteger background) {
        prepare();
        while (claimOne(client, shared, background)) { /* Until a cap is reached or nothing is due. */ }
    }

    /** Once per sender tick: expired leases go back to the queue. */
    void prepare() { if (!closed) jobs().recover(clock.instant()); }

    /**
     * Claims and starts at most one job when this worker, the background budget and the alert budget all have room.
     * @return true when a job was claimed (whatever its outcome), so the caller may offer the next slot
     */
    boolean claimOne(JDA client, AtomicInteger shared, AtomicInteger background) {
        if (closed || own >= MAX_IN_FLIGHT || shared.get() >= FreebieSender.MAX_IN_FLIGHT
                || background.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
        var claimed = jobs().claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return false;
        own++; shared.incrementAndGet(); background.incrementAndGet();
        Runnable release = () -> { own--; shared.decrementAndGet(); background.decrementAndGet(); };
        boolean handedOff = false;
        try { handedOff = start(client, claimed.get(), release); }
        catch (RuntimeException failure) { log(failure); } // The lease expires and the job is recovered.
        finally { if (!handedOff) release.run(); }
        return true;
    }

    /** @return true when a Discord request is in flight and its completion will run {@code release} */
    private boolean start(JDA client, Job job, Runnable release) {
        Instant now = clock.instant();
        if (job.attempts() > FreebieFailure.MAX_ATTEMPTS) { jobs().failed(job, "attempts exhausted", now); return false; }
        var cancel = cancelReason(job, now);
        if (cancel.isPresent()) { jobs().cancelled(job, cancel.get(), now); return false; }
        // Counted once, before the first submission; a recovered or retried DM is never counted again.
        if (!job.counted()) {
            if (!subscribers().countToday(job.userId(), now)) { jobs().skipped(job, FreebieDm.Skip.DAILY_CAP, now); return false; }
            if (!jobs().counted(job, now)) return false; // Lease lost; recovery takes it from here.
        }
        CompletableFuture<String> work;
        try {
            work = client.openPrivateChannelById(job.userId()).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenComposeAsync(channel -> deliver(client, job, channel), executor);
        } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        work.whenComplete((messageId, error) -> dispatch(() -> {
            try { outcome(job, messageId, error); }
            catch (RuntimeException storage) { log(storage); }
            finally { release.run(); }
        }, release));
        return true;
    }

    private CompletableFuture<String> deliver(JDA client, Job job, PrivateChannel channel) {
        CompletableFuture<Optional<Message>> existing = job.verifyFirst()
                ? channel.getHistory().retrievePast(50).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenApply(messages -> messages.stream().filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                            .filter(m -> FreebieDm.carriesReference(m, job.id())).findFirst())
                : CompletableFuture.completedFuture(Optional.empty());
        return existing.thenComposeAsync(found -> {
            if (found.isPresent()) return CompletableFuture.completedFuture(found.get().getId());
            Instant now = clock.instant();
            // Re-check right before posting: the offer may have been stopped or the user unsubscribed meanwhile.
            var cancel = cancelReason(job, now);
            if (cancel.isPresent()) return CompletableFuture.failedFuture(new Cancelled(cancel.get()));
            if (closed || !jobs().submitting(job, channel.getId(), now)) return CompletableFuture.failedFuture(new LeaseLost());
            var offer = repository.offer(job.offerId()).orElseThrow();
            return channel.sendMessage(FreebieDm.card(offer, job.id())).setNonce(FreebieDm.nonce(job.id()))
                    .timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit().thenApply(Message::getId);
        }, executor);
    }

    private Optional<FreebieDm.Cancel> cancelReason(Job job, Instant now) {
        var offer = repository.offer(job.offerId()).orElse(null);
        if (!FreebieDm.live(offer, now)) return Optional.of(FreebieDm.Cancel.OFFER_ENDED);
        var sections = subscribers().sections(job.userId());
        if (sections.isEmpty() || !sections.get().contains(offer.store().section())) return Optional.of(FreebieDm.Cancel.UNSUBSCRIBED);
        return Optional.empty();
    }

    private void outcome(Job job, String messageId, Throwable error) {
        Instant now = clock.instant();
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause == null) { jobs().sent(job, messageId, now); return; }
        if (cause instanceof LeaseLost) return;
        if (cause instanceof Cancelled cancelled) { jobs().cancelled(job, cancelled.reason, now); return; }
        String detail = detail(cause);
        switch (FreebieDm.classify(cause)) {
            case UNREACHABLE -> {
                // Discord will never deliver to this user: drop their subscription and everything queued for them.
                if (jobs().cancelled(job, FreebieDm.Cancel.CANNOT_DM, now)) {
                    subscribers().unsubscribe(job.userId());
                    jobs().cancelForUser(job.userId(), FreebieDm.Cancel.CANNOT_DM, now);
                }
            }
            case FAIL -> jobs().failed(job, detail, now);
            case RETRY -> {
                Duration delay = FreebieFailure.backoff(job.attempts());
                if (delay == null) jobs().failed(job, detail, now);
                else jobs().retry(job, now.plus(delay), true, detail, now);
            }
        }
    }

    private static String detail(Throwable cause) {
        try { return FailureDiagnostics.describe(cause); }
        catch (RuntimeException unreadable) { return cause.getClass().getSimpleName(); }
    }

    private void dispatch(Runnable action, Runnable release) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); } // Lease remains recoverable after shutdown.
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Game DM will recover | " + FailureDiagnostics.describe(failure));
    }

    private static final class Cancelled extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        final FreebieDm.Cancel reason;
        Cancelled(FreebieDm.Cancel reason) { super(reason.name(), null, false, false); this.reason = reason; }
    }
    private static final class LeaseLost extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        LeaseLost() { super("lease lost", null, false, false); }
    }
}
