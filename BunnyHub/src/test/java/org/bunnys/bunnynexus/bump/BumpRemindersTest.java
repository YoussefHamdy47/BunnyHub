package org.bunnys.bunnynexus.bump;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BumpRemindersTest {
    final Instant now = Instant.parse("2026-11-21T12:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final List<MessageCreateData> sent = new CopyOnWriteArrayList<>();
    final AtomicReference<Instant> saved = new AtomicReference<>();
    final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    { executor.setRemoveOnCancelPolicy(true); }

    @AfterEach void stop() { executor.shutdownNow(); }

    BumpReminders reminders(Optional<Instant> last, boolean dmOpen) {
        return new BumpReminders(data -> {
            sent.add(data);
            return dmOpen ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(new IllegalStateException("DMs closed"));
        }, () -> last, saved::set, executor, clock);
    }

    void settle() throws Exception { for (int i = 0; i < 4; i++) executor.submit(() -> {}).get(5, TimeUnit.SECONDS); }

    /** Delay of the one reminder still scheduled. */
    long nextInMinutes() {
        var queued = new ArrayList<>(executor.getQueue());
        assertEquals(1, queued.size());
        return ((ScheduledFuture<?>) queued.getFirst()).getDelay(TimeUnit.MINUTES);
    }

    @Test void neverRemindedMeansADmNowThenEveryTwoHours() throws Exception {
        reminders(Optional.empty(), true).start();
        settle();
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().toData().toString().contains("Time to bump"));
        assertEquals(now, saved.get(), "the time is saved, so a restart does not send another");
        assertTrue(nextInMinutes() >= 119);
    }

    @Test void aRestartWaitsForTheRestOfTheTwoHours() throws Exception {
        reminders(Optional.of(now.minus(Duration.ofMinutes(30))), true).start();
        settle();
        assertTrue(sent.isEmpty());
        long next = nextInMinutes();
        assertTrue(next >= 89 && next <= 90, "about 90 minutes left: " + next);
    }

    @Test void aRefusedDmIsTriedAgainSoon() throws Exception {
        reminders(Optional.empty(), false).start();
        settle();
        assertEquals(1, sent.size());
        assertNull(saved.get());
        assertTrue(nextInMinutes() <= 15);
    }

    @Test void iJustBumpedStartsTheTwoHoursFromTheClick() throws Exception {
        var reminders = reminders(Optional.of(now.minus(Duration.ofMinutes(100))), true);
        reminders.start();
        settle();
        assertTrue(nextInMinutes() <= 20, "about 20 minutes left before the click");
        assertTrue(sent.isEmpty());
        assertTrue(BumpReminders.message().toData().toString().contains(BumpReminders.BUTTON));
        var card = reminders.bumpedNow();
        settle();
        assertEquals(now, saved.get(), "the click is saved as the last bump");
        assertTrue(nextInMinutes() >= 119, "the old reminder is replaced by one two hours out");
        assertTrue(card.plainText().contains("<t:" + now.plus(BumpReminders.EVERY).getEpochSecond() + ":R>"));
        assertEquals(Optional.of(reminders), BumpReminders.current());
        reminders.shutdown();
        assertTrue(BumpReminders.current().isEmpty());
    }
}
