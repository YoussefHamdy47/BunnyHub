package org.bunnys.bunnynexus.timers.engine;

import org.bunnys.utils.AppDesign.Emojis;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntToLongFunction;

@SuppressWarnings("unused")
public class LevelEngine {

    public static final int MAX_RANK = 5000;

    /**
     * XP cost to advance from {@code level} → {@code level + 1} (season track).
     * Starts at 250 XP (level 1) and scales as level^1.15.
     */
    public static long xpRequired(int level) {
        return (long) (100 + 150 * Math.pow(level, 1.15));
    }

    /**
     * RP cost to advance from {@code rank} → {@code rank + 1} (account track).
     * Steeper than XP — starts at 600 RP (rank 1), the same 1.15 exponent.
     */
    public static long rpRequired(int rank) {
        return (long) (300 + 300 * Math.pow(rank, 1.15));
    }

    /**
     * Hours of playtime needed to earn {@code requiredPoints} at the standard
     * award rate: 180 points per 5-minute block = 2 160 points/hour.
     */
    public static double hoursRequired(long requiredPoints) {
        return requiredPoints / 2_160.0;
    }

    /**
     * XP awarded for {@code timeInMinutes} of playtime.
     * Grants 180 XP per complete 5-minute block; partial blocks earn nothing.
     */
    public static long calculateXP(double timeInMinutes) {
        if (!Double.isFinite(timeInMinutes) || timeInMinutes < 0 || timeInMinutes / 5.0 > Long.MAX_VALUE / 180L)
            throw new IllegalArgumentException("Study duration must be finite and non-negative.");
        return Math.multiplyExact((long) (timeInMinutes / 5.0), 180L);
    }

    public record LevelResult(boolean hasLeveledUp, int addedLevels, long remainingXP) {
    }

    public record RankResult(boolean hasRankedUp, int addedLevels, long remainingRP) {
    }

    /** Internal carrier for the shared progression loop. */
    private record Progress(int gained, long remaining) {
    }

    /**
     * Applies {@code addedXP} and reports any season level-ups that result.
     */
    public static LevelResult checkLevel(int currentLevel, long currentXP, long addedXP) {
        var p = checkProgress(currentLevel, currentXP, addedXP, LevelEngine::xpRequired);
        return new LevelResult(p.gained() > 0, p.gained(), p.remaining());
    }

    /**
     * Applies {@code addedRP} and reports any account rank-ups that result.
     */
    public static RankResult checkRank(int currentRank, long currentRP, long addedRP) {
        var p = checkProgress(currentRank, currentRP, addedRP, LevelEngine::rpRequired);
        return new RankResult(p.gained() > 0, p.gained(), p.remaining());
    }

    /**
     * Shared progression loop used by both tracks.
     * Drains current + added against successive thresholds.
     */
    private static Progress checkProgress(int base, long current, long added, IntToLongFunction costFn) {
        if (base < 0 || base > MAX_RANK || current < 0 || added < 0)
            throw new IllegalArgumentException("Invalid progression values.");
        int gained = 0;
        long points = Math.addExact(current, added);
        long cost = costFn.applyAsLong(base);

        while (base + gained < MAX_RANK && points >= cost) {
            points -= cost;
            gained++;

            cost = costFn.applyAsLong(base + gained);
        }

        return new Progress(gained, points);
    }

    /**
     * Prefix sums indexed by level/rank. Computed once in class init.
     */
    private static final long[] CUMULATIVE_XP = new long[MAX_RANK + 1];
    private static final long[] CUMULATIVE_RP = new long[MAX_RANK + 1];

    static {
        // BUG FIX: Array must start at 0, and cost applies to the previous index
        // to correctly sum the total cost to REACH a level.
        CUMULATIVE_XP[0] = 0;
        CUMULATIVE_RP[0] = 0;

        for (int i = 1; i <= MAX_RANK; i++) {
            CUMULATIVE_XP[i] = i == 1 ? 0 : CUMULATIVE_XP[i - 1] + xpRequired(i - 1);
            CUMULATIVE_RP[i] = CUMULATIVE_RP[i - 1] + rpRequired(i - 1);
        }
    }

    /** Total XP required to reach {@code seasonLevel} (inclusive). O(1). */
    public static long calculateTotalSeasonXP(int seasonLevel) {
        if (seasonLevel < 0 || seasonLevel > MAX_RANK)
            throw new IllegalArgumentException("seasonLevel out of range [0, " + MAX_RANK + "]: " + seasonLevel);
        return CUMULATIVE_XP[seasonLevel];
    }

    /** Total RP required to reach {@code accountRank} (inclusive). O(1). */
    public static long calculateTotalAccountRP(int accountRank) {
        if (accountRank < 0 || accountRank > MAX_RANK)
            throw new IllegalArgumentException("accountRank out of range [0, " + MAX_RANK + "]: " + accountRank);
        return CUMULATIVE_RP[accountRank];
    }

    /**
     * Maps an account-level index to its equivalent season-level score, adding
     * a +5 milestone bonus for every 5 complete account levels.
     */
    public static int convertSeasonLevel(int level) {
        return level * 500 + (level / 5) * 5;
    }

    private static final NavigableMap<Integer, String> EMOJI_MAP = new TreeMap<>();

    static {
        EMOJI_MAP.put(0, Emojis.VERIFY);
        EMOJI_MAP.put(26, Emojis.BLACK_HEART_SPIN);
        EMOJI_MAP.put(51, Emojis.WHITE_HEART_SPIN);
        EMOJI_MAP.put(101, Emojis.PINK_HEART_SPIN);
        EMOJI_MAP.put(151, Emojis.RED_HEART_SPIN);
        EMOJI_MAP.put(251, Emojis.DONUT_SPIN);
    }

    /** Returns the rank-up emoji for the tier that {@code level} falls into. */
    public static String rankUpEmoji(int level) {
        var entry = EMOJI_MAP.floorEntry(level);
        return entry != null ? entry.getValue() : Emojis.VERIFY;
    }
}
