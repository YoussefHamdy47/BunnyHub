package org.bunnys.bunnynexus.freebies;

import org.bunnys.handler.utils.TokenLoader;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Optional announcement features, read only from .env / the process environment (never Discord commands).
 *
 * <ul>
 *   <li>{@code FREEBIE_GROUPED_ANNOUNCEMENTS} (default true): new approvals post through announcement pages (one
 *       message per Epic release group and channel). false is the rollback: new approvals use the per-game path;
 *       existing pages keep recovery, edits and publishing.</li>
 *   <li>{@code FREEBIE_PUBLISH_ENABLED} (default false) and {@code FREEBIE_PUBLISH_CHANNEL_IDS} (comma-separated
 *       channel IDs): automatic publishing of new announcement pages, only in these Announcement Channels. Both are
 *       required. Channels are never converted.</li>
 *   <li>{@code FREEBIE_EPIC_CHECKOUT} (default false): the Epic checkout button ("Instant Checkout" for one game,
 *       "Claim all PC games" for several).</li>
 *   <li>{@code FREEBIE_EGDATA_ENABLED} (default true): EGData region and mobile-offer enrichment.</li>
 *   <li>{@code FREEBIE_RESOLVE_GAMERPOWER_LINKS} (default false): follow GamerPower claim redirects to the Epic product
 *       page for automatic duplicate joining. Off: the redirect returned HTTP 520 when validated on 2026-10-24.</li>
 *   <li>{@code FREEBIE_STEAM_ENABLED} (default true): Steam's own store as a source of free-to-keep games
 *       ({@link SteamFeed}); false leaves Steam games to GamerPower.</li>
 *   <li>{@code FREEBIE_DROP_WINDOW_SECONDS} (default 120, 10-600): how long a channel's drop collects new games after
 *       its first one before the one shared message is posted ({@link FreebieDrops}). Only used while grouped.</li>
 *   <li>{@code FREEBIE_PING_COOLDOWN_MINUTES} (default 60, 0-1440; 0 is off): a post that would mention a role in a
 *       channel pinged within this long posts without the mention ({@link FreebiePings}).</li>
 *   <li>{@code FREEBIE_COMPONENTS_V2} (default true): new announcement pages are Discord Components V2 cards
 *       ({@link FreebieComponentCards}); false is the rollback to embeds. Each posted page keeps the format it was
 *       posted in for its later edits.</li>
 * </ul>
 */
public record FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                              boolean egdata, boolean resolveGamerPowerLinks, boolean steam, Duration dropWindow,
                              Duration pingCooldown, boolean componentsV2) {
    public static final Duration DEFAULT_DROP_WINDOW = Duration.ofSeconds(120);
    static final int MIN_DROP_SECONDS = 10, MAX_DROP_SECONDS = 600;
    public static final FreebieFeatures DEFAULT = new FreebieFeatures(true, false, Set.of(), false, true, false, true,
            DEFAULT_DROP_WINDOW, FreebiePings.DEFAULT_COOLDOWN, true);

    public FreebieFeatures {
        publishChannels = Set.copyOf(publishChannels);
        Objects.requireNonNull(dropWindow);
        Objects.requireNonNull(pingCooldown);
    }

    /**
     * Every feature but the card format chosen: embeds. The shorter constructors below lead here too (callers and tests
     * that predate Components V2); the environment and {@link #DEFAULT} use V2.
     */
    public FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                           boolean egdata, boolean resolveGamerPowerLinks, boolean steam, Duration dropWindow, Duration pingCooldown) {
        this(grouped, publishEnabled, publishChannels, checkout, egdata, resolveGamerPowerLinks, steam, dropWindow, pingCooldown, false);
    }

    /** Every feature but the ping cooldown chosen; the default cooldown. */
    public FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                           boolean egdata, boolean resolveGamerPowerLinks, boolean steam, Duration dropWindow) {
        this(grouped, publishEnabled, publishChannels, checkout, egdata, resolveGamerPowerLinks, steam, dropWindow, FreebiePings.DEFAULT_COOLDOWN);
    }

    /** Every feature but the drop window chosen; the default window. */
    public FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                           boolean egdata, boolean resolveGamerPowerLinks, boolean steam) {
        this(grouped, publishEnabled, publishChannels, checkout, egdata, resolveGamerPowerLinks, steam, DEFAULT_DROP_WINDOW);
    }

    /** The drop window new deliveries use, or empty for the per-game rollback ({@code grouped=false}). */
    Optional<Duration> drops() { return grouped ? Optional.of(dropWindow) : Optional.empty(); }

    /** Every feature but the Steam source chosen; Steam on, as by default. */
    public FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                           boolean egdata, boolean resolveGamerPowerLinks) {
        this(grouped, publishEnabled, publishChannels, checkout, egdata, resolveGamerPowerLinks, true);
    }

    /** Whether a page sent to {@code channelId} must be published automatically. */
    public boolean publishes(String channelId) { return publishEnabled && publishChannels.contains(channelId); }

    static FreebieFeatures load(Function<String, String> env, Consumer<String> warn) {
        Set<String> channels = new LinkedHashSet<>();
        String raw = env.apply("FREEBIE_PUBLISH_CHANNEL_IDS");
        if (raw != null) for (String part : raw.split(",")) {
            String id = part.strip();
            if (id.isEmpty()) continue;
            if (id.matches("[1-9][0-9]{16,19}")) channels.add(id);
            else warn.accept("[Freebies] Ignoring invalid FREEBIE_PUBLISH_CHANNEL_IDS entry.");
        }
        boolean publish = flag(env, "FREEBIE_PUBLISH_ENABLED", false, warn);
        if (publish && channels.isEmpty()) {
            warn.accept("[Freebies] FREEBIE_PUBLISH_ENABLED is true but FREEBIE_PUBLISH_CHANNEL_IDS is empty; automatic publishing stays off.");
            publish = false;
        }
        return new FreebieFeatures(flag(env, "FREEBIE_GROUPED_ANNOUNCEMENTS", true, warn), publish, channels,
                flag(env, "FREEBIE_EPIC_CHECKOUT", false, warn), flag(env, "FREEBIE_EGDATA_ENABLED", true, warn),
                flag(env, "FREEBIE_RESOLVE_GAMERPOWER_LINKS", false, warn), flag(env, "FREEBIE_STEAM_ENABLED", true, warn),
                dropWindow(env.apply("FREEBIE_DROP_WINDOW_SECONDS"), warn), pingCooldown(env.apply("FREEBIE_PING_COOLDOWN_MINUTES"), warn),
                flag(env, "FREEBIE_COMPONENTS_V2", true, warn));
    }

    /** Seconds, clamped to 10-600; anything that is not a whole number is logged and the default used. */
    static Duration dropWindow(String value, Consumer<String> warn) {
        if (value == null || value.isBlank()) return DEFAULT_DROP_WINDOW;
        try {
            long seconds = Long.parseLong(value.strip());
            long clamped = Math.clamp(seconds, MIN_DROP_SECONDS, MAX_DROP_SECONDS);
            if (clamped != seconds) warn.accept("[Freebies] FREEBIE_DROP_WINDOW_SECONDS must be " + MIN_DROP_SECONDS + "-"
                    + MAX_DROP_SECONDS + "; using " + clamped + ".");
            return Duration.ofSeconds(clamped);
        } catch (NumberFormatException invalid) {
            warn.accept("[Freebies] FREEBIE_DROP_WINDOW_SECONDS must be a whole number of seconds; using "
                    + DEFAULT_DROP_WINDOW.toSeconds() + ".");
            return DEFAULT_DROP_WINDOW;
        }
    }

    /** Minutes, clamped to 0-1440 (0 turns the budget off); anything that is not a whole number is logged and 60 used. */
    static Duration pingCooldown(String value, Consumer<String> warn) {
        if (value == null || value.isBlank()) return FreebiePings.DEFAULT_COOLDOWN;
        long max = FreebiePings.MAX_COOLDOWN.toMinutes();
        try {
            long minutes = Long.parseLong(value.strip());
            long clamped = Math.clamp(minutes, 0, max);
            if (clamped != minutes) warn.accept("[Freebies] FREEBIE_PING_COOLDOWN_MINUTES must be 0-" + max + "; using " + clamped + ".");
            return Duration.ofMinutes(clamped);
        } catch (NumberFormatException invalid) {
            warn.accept("[Freebies] FREEBIE_PING_COOLDOWN_MINUTES must be a whole number of minutes; using "
                    + FreebiePings.DEFAULT_COOLDOWN.toMinutes() + ".");
            return FreebiePings.DEFAULT_COOLDOWN;
        }
    }

    static FreebieFeatures fromEnvironment(Consumer<String> warn) { return load(TokenLoader::getEnvQuietly, warn); }

    private static boolean flag(Function<String, String> env, String key, boolean fallback, Consumer<String> warn) {
        String value = env.apply(key);
        if (value == null || value.isBlank()) return fallback;
        if (value.strip().equalsIgnoreCase("true")) return true;
        if (value.strip().equalsIgnoreCase("false")) return false;
        warn.accept("[Freebies] " + key + " must be true or false; using " + fallback + ".");
        return fallback;
    }
}
