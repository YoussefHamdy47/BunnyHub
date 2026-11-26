package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class EpicPollScheduleTest {
    private static final Duration NORMAL = Duration.ofMinutes(10);

    @Test void hits1800CairoInsteadOfDriftingWithStartup() {
        assertEquals(Duration.ofMinutes(2), EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T15:58:00Z"), NORMAL));
        assertEquals(Duration.ofMillis(250), EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T15:59:59.750Z"), NORMAL));
    }

    @Test void retriesEveryThirtySecondsWhileTheFeedRollsOver() {
        assertEquals(Duration.ofSeconds(30), EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T16:00:00Z"), NORMAL));
        assertEquals(Duration.ofSeconds(18), EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T16:04:12Z"), NORMAL));
        assertEquals(NORMAL, EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T16:15:00Z"), NORMAL));
    }

    @Test void followsCairoSummerTimeAndChecksDailyForHolidayGiveaways() {
        assertEquals(Duration.ofSeconds(1), EpicPollSchedule.nextDelay(Instant.parse("2026-07-03T14:59:59Z"), NORMAL));
        assertEquals(Duration.ofMinutes(1), EpicPollSchedule.nextDelay(Instant.parse("2026-12-25T15:59:00Z"), NORMAL));
        assertEquals(Duration.ofMinutes(10), EpicPollSchedule.nextDelay(Instant.parse("2026-11-26T20:00:00Z"), NORMAL));
    }
}
