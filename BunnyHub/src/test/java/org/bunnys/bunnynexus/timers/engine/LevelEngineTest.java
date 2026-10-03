package org.bunnys.bunnynexus.timers.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelEngineTest {
    @Test void rewardsOnlyCompleteFiveMinuteBlocks() {
        assertEquals(0, LevelEngine.calculateXP(4.999));
        assertEquals(180, LevelEngine.calculateXP(5));
        assertEquals(2160, LevelEngine.calculateXP(60));
        assertThrows(IllegalArgumentException.class, () -> LevelEngine.calculateXP(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LevelEngine.calculateXP(-1));
    }

    @Test void longerUnbrokenStretchesEarnLessPerHourButNeverNothing() {
        double hour = 3600;
        assertEquals(60, LevelEngine.rewardedMinutes(java.util.List.of(hour)), 1e-9);
        assertEquals(120, LevelEngine.rewardedMinutes(java.util.List.of(2 * hour)), 1e-9);
        assertEquals(120 + 96, LevelEngine.rewardedMinutes(java.util.List.of(4 * hour)), 1e-9);
        // 20 h straight: 2 h full, 2 h at 80 %, 2 h at 60 %, 4 h at 40 %, 10 h at 20 %.
        assertEquals(120 + 96 + 72 + 96 + 120, LevelEngine.rewardedMinutes(java.util.List.of(20 * hour)), 1e-9);
        // The same 20 h in ten rested 2 h stretches earns in full.
        assertEquals(1200, LevelEngine.rewardedMinutes(java.util.Collections.nCopies(10, 2 * hour)), 1e-9);
        // Every extra hour still adds something, so grinding is never pointless.
        for (int h = 1; h < 100; h++)
            assertTrue(LevelEngine.rewardedMinutes(java.util.List.of((h + 1) * hour))
                    > LevelEngine.rewardedMinutes(java.util.List.of(h * hour)));
        assertEquals(0, LevelEngine.rewardedMinutes(java.util.List.of(0.0, 0.0)));
        assertThrows(IllegalArgumentException.class, () -> LevelEngine.rewardedMinutes(java.util.List.of(-1.0)));
        assertThrows(IllegalArgumentException.class, () -> LevelEngine.rewardedMinutes(java.util.List.of(Double.NaN)));
    }

    @Test void exactThresholdAdvancesAndKeepsRemainder() {
        var result = LevelEngine.checkLevel(1, 0, 250);
        assertTrue(result.hasLeveledUp());
        assertEquals(1, result.addedLevels());
        assertEquals(0, result.remainingXP());
    }

    @Test void cumulativeTotalsUseTheActualStartingLevels() {
        assertEquals(0, LevelEngine.calculateTotalSeasonXP(1));
        assertEquals(250, LevelEngine.calculateTotalSeasonXP(2));
        assertEquals(0, LevelEngine.calculateTotalAccountRP(0));
        assertEquals(300, LevelEngine.calculateTotalAccountRP(1));
        for (int level = 1; level < LevelEngine.MAX_RANK; level++) {
            assertEquals(LevelEngine.xpRequired(level), LevelEngine.calculateTotalSeasonXP(level + 1)
                    - LevelEngine.calculateTotalSeasonXP(level));
        }
    }

    @Test void capPreservesLongOverflowPointsWithoutExtraLevels() {
        var result = LevelEngine.checkRank(LevelEngine.MAX_RANK, Integer.MAX_VALUE, 500L);
        assertFalse(result.hasRankedUp());
        assertEquals((long) Integer.MAX_VALUE + 500, result.remainingRP());
        assertThrows(IllegalArgumentException.class, () -> LevelEngine.checkRank(-1, 0, 1));
        assertThrows(ArithmeticException.class, () -> LevelEngine.checkRank(1, Long.MAX_VALUE, 1));
    }
}
