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
