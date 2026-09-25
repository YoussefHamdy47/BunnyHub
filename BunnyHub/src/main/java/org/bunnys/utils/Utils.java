package org.bunnys.utils;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * General-purpose utility methods for the Nexus Discord bot.
 *
 * <p>
 * Ported from {@code utils.ts} with the following fixes applied:
 * <ol>
 * <li><b>capitalize</b> — now lowercases the input first (the original ignored
 * its own
 * JSDoc) and capitalizes the first character of the string (the original regex
 * {@code (?&lt;=\W)(\w)} can never match position 0).</li>
 * <li><b>GetRandomFromArray / chooseRandomFromArray</b> — were byte-for-byte
 * duplicates;
 * consolidated into {@link #randomElement} using {@link ThreadLocalRandom},
 * which is
 * faster than {@code Math.random()} under concurrent load.</li>
 * <li><b>timeUnitValues / fullTimeUnitNames</b> — were declared after the
 * function that
 * used them and contained a dead {@code ms} entry (present in the values map
 * but
 * absent from the names map, so always skipped). Replaced by the private
 * {@link TimeGranularity} enum declared at the top of the class.</li>
 * <li><b>getTimestamp</b> — the {@code switch} was entirely redundant: every
 * branch
 * returned {@code <t:${unix}:${type}>} with the same substitution, and the
 * {@code default: return undefined} was unreachable because {@code type} is
 * exhaustively typed. Replaced by a single expression.</li>
 * <li><b>SendAndDelete</b> — declared {@code Promise<Message<false>>} but the
 * non-DM
 * branch returned nothing (suppressed with {@code // @ts-ignore}). Replaced
 * with JDA's
 * {@code queueAfter}, the idiomatic async approach.</li>
 * <li><b>trimArray</b> — pushed a {@code String} into a generic {@code T[]} via
 * {@code as unknown as T}, which is unsound. Split into {@link #trimList}
 * (type-safe
 * truncation) and {@link #trimListDisplay} (produces the "and N more"
 * suffix).</li>
 * </ol>
 */
@SuppressWarnings("unused")
public final class Utils {

    private Utils() {
        /* utility class — no instances */ }

    // ==========================================
    // Time Granularity Enum
    // Replaces the two separate maps at the bottom of utils.ts and removes the
    // dead `ms` entry that existed in timeUnitValues but was never reachable.
    // ==========================================

    private enum TimeGranularity {
        YEAR(31_557_600_000L, "year", "yr"),
        DAY(86_400_000L, "day", "day"),
        HOUR(3_600_000L, "hour", "hr"),
        MINUTE(60_000L, "minute", "min"),
        SECOND(1_000L, "second", "sec");

        final long millis;
        final String longName;
        final String shortName;

        TimeGranularity(long millis, String longName, String shortName) {
            this.millis = millis;
            this.longName = longName;
            this.shortName = shortName;
        }
    }

    // ==========================================
    // Public Enums & Records
    // ==========================================

    /** Controls the verbosity of {@link #msToTime} output. */
    public enum TimeFormat {
        LONG, SHORT
    }

    /**
     * Discord inline timestamp format codes — mirrors the TypeScript
     * {@code UNIXFormat} type.
     *
     * <pre>
     *  SHORT_DATE      'd'  →  01/30/2025
     *  LONG_DATE       'D'  →  January 30, 2025
     *  SHORT_TIME      't'  →  12:00 AM
     *  LONG_TIME       'T'  →  12:00:00 AM
     *  SHORT_DATETIME  'f'  →  Jan 30, 2025 12:00 AM
     *  LONG_DATETIME   'F'  →  January 30, 2025 12:00:00 AM
     *  RELATIVE        'R'  →  5 minutes ago
     * </pre>
     */
    public enum DiscordTimestampFormat {
        SHORT_DATE('d'),
        LONG_DATE('D'),
        SHORT_TIME('t'),
        LONG_TIME('T'),
        SHORT_DATETIME('f'),
        LONG_DATETIME('F'),
        RELATIVE('R');

        public final char code;

        DiscordTimestampFormat(char code) {
            this.code = code;
        }
    }

    /** Return type for {@link #keyPerms(Member)}. */
    public record KeyPermsResult(String permissionsLabel, int count) {
    }

    // ==========================================
    // msToTime Options (Builder pattern — cleaner than a plain options object)
    // ==========================================

    /**
     * Fluent configuration for {@link #msToTime(long, MsToTimeOptions)}.
     *
     * <p>
     * The original had a {@code spaces} boolean that was redundant with
     * {@code joinString=" "}. Removed; just set {@code joinString} directly.
     */
    public static final class MsToTimeOptions {
        private TimeFormat format = TimeFormat.LONG;
        private String joinString = " ";
        private int unitRounding = 100;

        /** {@code LONG} → "1 hour 30 minutes", {@code SHORT} → "1 hr 30 min". */
        public MsToTimeOptions format(TimeFormat format) {
            this.format = format;
            return this;
        }

        /**
         * String inserted between each unit. Default {@code " "}. Example:
         * {@code ", "}.
         */
        public MsToTimeOptions joinString(String joinString) {
            this.joinString = joinString;
            return this;
        }

        /** Maximum number of units to include. Default 100 (effectively unlimited). */
        public MsToTimeOptions unitRounding(int unitRounding) {
            this.unitRounding = unitRounding;
            return this;
        }
    }

    // ==========================================
    // String Utilities
    // ==========================================

    /**
     * Lowercases {@code str}, then capitalizes the first letter of every word.
     * A word boundary is defined as the start of the string or the position
     * immediately following a non-word character ({@code \W}).
     *
     * <p>
     * <b>Fixes vs original:</b> the TypeScript version skipped lowercasing
     * (ignoring
     * its own JSDoc) and the regex {@code (?&lt;=\W)(\w)} can never match position
     * 0,
     * so the very first character was never capitalized.
     */
    public static String capitalize(String str) {
        if (str == null || str.isEmpty())
            return str;
        var lower = str.toLowerCase(Locale.ROOT);
        // '^(\\w)' handles the first character; '(?<=\\W)(\\w)' handles the rest.
        return Pattern.compile("(?<=\\W)(\\w)|^(\\w)")
                .matcher(lower)
                .replaceAll(mr -> mr.group().toUpperCase(Locale.ROOT));
    }

    // ==========================================
    // Collection Utilities
    // ==========================================

    /**
     * Returns a uniformly random element from {@code list}.
     *
     * <p>
     * <b>Consolidation:</b> the original contained two identical functions
     * ({@code GetRandomFromArray} and {@code chooseRandomFromArray}). Uses
     * {@link ThreadLocalRandom} instead of {@code Math.random()} for better
     * throughput under concurrent access.
     *
     * @throws IllegalArgumentException if {@code list} is null or empty
     */
    public static <T> T randomElement(List<T> list) {
        if (list == null || list.isEmpty())
            throw new IllegalArgumentException("List must not be null or empty");
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** Array overload for {@link #randomElement(List)}. */
    @SafeVarargs
    public static <T> T randomElement(T... array) {
        if (array == null || array.length == 0)
            throw new IllegalArgumentException("Array must not be null or empty");
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

    /**
     * Returns a defensive copy of {@code list} truncated to at most {@code maxLen}
     * elements.
     * The original list is never mutated.
     *
     * <p>
     * <b>Fix vs original:</b> {@code trimArray} pushed a bare {@code String} into a
     * generic {@code T[]} using {@code as unknown as T}, which is unsound. Use
     * {@link #trimListDisplay} when you need the "and N more" suffix as a display
     * string.
     */
    public static <T> List<T> trimList(List<T> list, int maxLen) {
        if (maxLen < 0) throw new IllegalArgumentException("Maximum length cannot be negative");
        if (list == null)
            throw new IllegalArgumentException("List must not be null");
        return new ArrayList<>(list.subList(0, Math.min(list.size(), maxLen)));
    }

    /**
     * Formats {@code list} as a comma-joined string, appending
     * {@code "and N more <type>..."}
     * if the list exceeds {@code maxLen} elements.
     *
     * <p>
     * Example: {@code trimListDisplay(List.of("a","b","c","d"), 2, "role(s)")}
     * → {@code "a, b and 2 more role(s)..."}
     */
    public static <T> String trimListDisplay(List<T> list, int maxLen, String type) {
        if (list == null || maxLen < 0) throw new IllegalArgumentException("Invalid list or maximum length");
        if (list.size() <= maxLen)
            return list.stream().map(Object::toString).collect(Collectors.joining(", "));
        int overflow = list.size() - maxLen;
        String visible = list.subList(0, maxLen).stream()
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return visible + " and " + overflow + " more " + type + "...";
    }

    // ==========================================
    // Validation
    // ==========================================

    /**
     * Returns {@code true} if {@code url} is a syntactically valid, absolute URL.
     * Mirrors the TypeScript {@code new URL(string)} constructor check.
     */
    public static boolean isValidUrl(String url) {
        if (url == null)
            return false;
        try {
            new URI(url).toURL();
            return true;
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            return false;
        }
    }

    // ==========================================
    // Time Formatting
    // ==========================================

    /**
     * Converts {@code timeMs} milliseconds into a human-readable duration string
     * using default options (long format, space-joined, all units).
     *
     * @return {@link Optional} of the formatted string, or empty if
     *         {@code timeMs < 0}
     *         or no units are large enough to display
     */
    public static Optional<String> msToTime(long timeMs) {
        return msToTime(timeMs, new MsToTimeOptions());
    }

    /**
     * Converts {@code timeMs} milliseconds into a human-readable duration string.
     *
     * <pre>
     * msToTime(5_400_000L)
     *   → Optional["1 hour 30 minutes"]
     *
     * msToTime(189_000_000L, new MsToTimeOptions().format(TimeFormat.SHORT).unitRounding(3))
     *   → Optional["2 yr 3 hr 45 min"]
     * </pre>
     */
    public static Optional<String> msToTime(long timeMs, MsToTimeOptions options) {
        if (timeMs < 0)
            return Optional.empty();

        var parts = new ArrayList<String>();
        long remaining = timeMs;

        for (var unit : TimeGranularity.values()) {
            if (parts.size() >= options.unitRounding)
                break;
            long count = remaining / unit.millis;
            if (count >= 1) {
                String name = (options.format == TimeFormat.SHORT) ? unit.shortName : unit.longName;
                // Pluralize only in long format (abbreviations like "hr" don't take an 's')
                String plural = (count != 1 && options.format == TimeFormat.LONG) ? "s" : "";
                parts.add(count + " " + name + plural);
                remaining -= count * unit.millis;
            }
        }

        // String.join avoids a trailing joinString that the original trimmed away
        if (parts.isEmpty())
            return Optional.empty();
        return Optional.of(String.join(options.joinString, parts));
    }

    /**
     * Converts {@code seconds} into a human-readable hours string with two decimal
     * places.
     *
     * @throws IllegalArgumentException if {@code seconds} is negative
     */
    public static String secondsToHours(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0)
            throw new IllegalArgumentException("Seconds cannot be negative");
        return String.format("%.2f hours", seconds / 3_600.0);
    }

    // ==========================================
    // Discord Timestamp
    // ==========================================

    /**
     * Formats an {@link Instant} as a Discord inline timestamp Markdown string.
     *
     * <p>
     * <b>Fix vs original:</b> the TypeScript {@code switch} was entirely redundant
     * —
     * every branch returned the same {@code <t:${unix}:${letter}>} pattern, and the
     * {@code default: return undefined} branch was unreachable because {@code type}
     * is
     * exhaustively typed as a {@code UNIXFormat}. Replaced with a single
     * expression.
     *
     * @param instant the point in time to represent
     * @param format  display format (see {@link DiscordTimestampFormat})
     * @return a string such as {@code <t:1706659200:R>}
     */
    public static String getTimestamp(Instant instant, DiscordTimestampFormat format) {
        return "<t:" + instant.getEpochSecond() + ":" + format.code + ">";
    }

    // ==========================================
    // Object Nullification (reflection)
    // ==========================================

    /**
     * Recursively resets all non-static fields of {@code obj} to their zero/null
     * equivalents: reference types → {@code null} (with recursion into nested
     * objects),
     * {@link Collection}s and {@link Map}s → cleared in place, arrays → emptied,
     * primitives → {@code 0} / {@code false} / {@code '\0'}.
     *
     * <p>
     * Uses reflection — do not call in hot paths.
     *
     * @return {@code obj} itself, for chaining convenience
     */
    public static <T> T nullifyObjectInPlace(T obj) {
        if (obj == null)
            return null;
        for (Field field : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()))
                continue;
            field.setAccessible(true);
            try {
                nullifyField(obj, field);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot nullify field: " + field.getName(), e);
            }
        }
        return obj;
    }

    private static void nullifyField(Object obj, Field field) throws IllegalAccessException {
        Class<?> type = field.getType();
        Object value = field.get(obj);

        if (type.isPrimitive()) {
            // Primitives can't be null; set to their natural zero-value instead
            if (type == boolean.class)
                field.setBoolean(obj, false);
            else if (type == byte.class)
                field.setByte(obj, (byte) 0);
            else if (type == short.class)
                field.setShort(obj, (short) 0);
            else if (type == int.class)
                field.setInt(obj, 0);
            else if (type == long.class)
                field.setLong(obj, 0L);
            else if (type == float.class)
                field.setFloat(obj, 0f);
            else if (type == double.class)
                field.setDouble(obj, 0.0);
            else if (type == char.class)
                field.setChar(obj, '\0');
        } else if (value instanceof Collection<?> c) {
            c.clear();
        } else if (value instanceof Map<?, ?> m) {
            m.clear();
        } else if (value != null && type.isArray()) {
            if (type.getComponentType().isPrimitive()) {
                // Can't null primitive array elements; replace with a fresh empty array
                field.set(obj, Array.newInstance(type.getComponentType(), 0));
            } else {
                Arrays.fill((Object[]) value, null);
            }
        } else if (value != null) {
            nullifyObjectInPlace(value); // recurse into nested reference types
        }
    }

    /**
     * Returns a shallow {@link Map} of {@code obj}'s non-static fields where every
     * value is {@code null} and every {@link Collection}/{@link Map}/array field is
     * replaced with an empty {@link ArrayList}.
     *
     * <p>
     * This is the idiomatic Java equivalent of:
     * {@code Object.fromEntries(Object.keys(obj).map(k => [k, Array.isArray(obj[k]) ? [] : null]))}
     *
     * <p>
     * Field declaration order is preserved via {@link LinkedHashMap}.
     */
    public static <T> Map<String, Object> nullifyObjectShallow(T obj) {
        var result = new LinkedHashMap<String, Object>();
        for (Field field : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()))
                continue;
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                boolean isCollection = value instanceof Collection<?> || value instanceof Map<?, ?>;
                boolean isArray = value != null && value.getClass().isArray();
                result.put(field.getName(), (isCollection || isArray) ? new ArrayList<>() : null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot read field: " + field.getName(), e);
            }
        }
        return result;
    }

    // ==========================================
    // Discord / JDA Utilities
    // ==========================================

    /**
     * Returns a backtick-formatted list of permission names that {@code member} is
     * missing.
     *
     * @param member   guild member to check
     * @param required permissions to verify against
     * @return mutable list of formatted missing permission names; empty if none are
     *         missing
     */
    public static List<String> missingPermissions(Member member, Permission... required) {
        var held = member.getPermissions();
        return Arrays.stream(required)
                .filter(p -> !held.contains(p))
                .map(p -> "`" + p.getName() + "`")
                .collect(Collectors.toList());
    }

    /**
     * Sends a message to {@code channel} and deletes it after {@code delaySeconds}.
     * For {@link PrivateChannel}s the message is sent without scheduling deletion
     * (bots cannot delete their own DM messages).
     *
     * <p>
     * <b>Fix vs original:</b> the TypeScript version's non-DM branch returned
     * nothing
     * and suppressed the resulting type error with {@code // @ts-ignore}. Replaced
     * with
     * JDA's {@code queueAfter} for clean asynchronous scheduling.
     *
     * @param channel      target channel
     * @param message      message to send
     * @param delaySeconds seconds to wait before deleting (ignored for DMs)
     */
    public static void sendAndDelete(MessageChannel channel, MessageCreateData message, int delaySeconds) {
        if (channel instanceof PrivateChannel) {
            channel.sendMessage(message).queue();
            return;
        }
        channel.sendMessage(message)
                .queue(msg -> msg.delete().queueAfter(delaySeconds, TimeUnit.SECONDS));
    }

    // Precomputed permission → label mapping preserves the original declaration
    // order
    private static final Map<Permission, String> KEY_PERM_LABELS = new LinkedHashMap<>();
    static {
        KEY_PERM_LABELS.put(Permission.MANAGE_SERVER, "Manage Server");
        KEY_PERM_LABELS.put(Permission.MANAGE_ROLES, "Manage Roles");
        KEY_PERM_LABELS.put(Permission.MANAGE_CHANNEL, "Manage Channels");
        KEY_PERM_LABELS.put(Permission.KICK_MEMBERS, "Kick Members");
        KEY_PERM_LABELS.put(Permission.BAN_MEMBERS, "Ban Members");
        KEY_PERM_LABELS.put(Permission.NICKNAME_MANAGE, "Manage Nicknames");
        KEY_PERM_LABELS.put(Permission.MANAGE_GUILD_EXPRESSIONS, "Manage Emojis & Stickers");
        KEY_PERM_LABELS.put(Permission.MESSAGE_MANAGE, "Manage Messages");
        KEY_PERM_LABELS.put(Permission.MESSAGE_MENTION_EVERYONE, "Mention Everyone");
        KEY_PERM_LABELS.put(Permission.MODERATE_MEMBERS, "Moderate Members");
    }

    /**
     * Returns the notable moderation permissions held by {@code member}.
     * Administrator short-circuits the full check and is returned alone.
     *
     * @param member the guild member to inspect
     * @return a {@link KeyPermsResult} with a comma-joined label and the permission
     *         count
     */
    public static KeyPermsResult keyPerms(Member member) {
        if (member.hasPermission(Permission.ADMINISTRATOR))
            return new KeyPermsResult("Administrator", 1);

        var names = KEY_PERM_LABELS.entrySet().stream()
                .filter(e -> member.hasPermission(e.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        return new KeyPermsResult(
                names.isEmpty() ? "No Permissions" : String.join(", ", names),
                names.size());
    }
}
