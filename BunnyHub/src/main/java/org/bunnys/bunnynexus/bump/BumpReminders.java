package org.bunnys.bunnynexus.bump;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.handler.cards.Card;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * DMs the bot owner "time to bump" every two hours, Disboard's cooldown. Nothing to set up and nothing to detect.
 * The time of the last DM is saved, so a restart or deploy neither sends an extra DM nor resets the two hours.
 */
public final class BumpReminders {
    public static final Duration EVERY = Duration.ofHours(2);
    static final Duration RETRY = Duration.ofMinutes(15);

    private final Function<MessageCreateData, CompletableFuture<Void>> dm;
    private final Supplier<Optional<Instant>> lastSent;
    private final Consumer<Instant> saveSent;
    private final ScheduledExecutorService executor;
    private final Clock clock;

    public BumpReminders(Function<MessageCreateData, CompletableFuture<Void>> dm, Supplier<Optional<Instant>> lastSent,
                         Consumer<Instant> saveSent, ScheduledExecutorService executor, Clock clock) {
        this.dm = dm; this.lastSent = lastSent; this.saveSent = saveSent; this.executor = executor; this.clock = clock;
    }

    static MessageCreateData message() {
        return Card.builder().text("**Time to bump.** Run `/bump` with Disboard in your server.")
                .text("-# Next reminder in " + EVERY.toHours() + " hours.").build().message();
    }

    /** The first DM goes out now if none was ever sent or the last one is two hours old; otherwise when it is. */
    public void start() {
        executor.execute(() -> guard(() -> {
            Instant now = clock.instant();
            Instant due = lastSent.get().map(last -> last.plus(EVERY)).filter(now::isBefore).orElse(now);
            later(Duration.between(now, due));
        }));
    }

    void send() {
        dm.apply(message()).whenComplete((ok, failure) -> executor.execute(() -> guard(() -> {
            if (failure != null) {
                BunnyLog.warning("[Bump] Reminder DM not sent; trying again in 15 minutes | " + FailureDiagnostics.describe(failure));
                later(RETRY);
                return;
            }
            saveSent.accept(clock.instant());
            later(EVERY);
        })));
    }

    private void later(Duration delay) {
        executor.schedule(() -> guard(this::send), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    public void shutdown() { executor.shutdownNow(); }

    private void guard(Runnable work) {
        try { work.run(); }
        catch (RuntimeException failure) {
            BunnyLog.warning("[Bump] Reminder failed; trying again in 15 minutes | " + FailureDiagnostics.describe(failure));
            if (!executor.isShutdown()) executor.schedule(() -> guard(this::send), RETRY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
