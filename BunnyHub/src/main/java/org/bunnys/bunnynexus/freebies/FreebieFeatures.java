package org.bunnys.bunnynexus.freebies;

import org.bunnys.handler.utils.TokenLoader;
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
 * </ul>
 */
public record FreebieFeatures(boolean grouped, boolean publishEnabled, Set<String> publishChannels, boolean checkout,
                              boolean egdata, boolean resolveGamerPowerLinks, boolean steam) {
    public static final FreebieFeatures DEFAULT = new FreebieFeatures(true, false, Set.of(), false, true, false, true);

    public FreebieFeatures { publishChannels = Set.copyOf(publishChannels); }

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
                flag(env, "FREEBIE_RESOLVE_GAMERPOWER_LINKS", false, warn), flag(env, "FREEBIE_STEAM_ENABLED", true, warn));
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
