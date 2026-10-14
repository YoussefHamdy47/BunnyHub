package org.bunnys.bunnynexus.freebies;

import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Deployment-only settings read from .env / the process environment. Nothing here can be changed
 * from Discord, so a server admin (or a compromised command) cannot redirect reviews or approve alerts.
 *
 * <ul>
 *   <li>FREEBIE_REVIEW_CHANNEL_ID - private channel where discovered games wait for approval (required);
 *       major PC launchers are reviewed here with an owner ping</li>
 *   <li>FREEBIE_OTHER_REVIEW_CHANNEL_ID - optional private channel for the other sections (indie PC, console,
 *       mobile), reviewed without a ping; defaults to the main review channel</li>
 *   <li>FREEBIE_OWNER_IDS - comma-separated user IDs allowed to approve/reject/stop (required)</li>
 *   <li>FREEBIE_POLL_MINUTES - GamerPower poll interval, 5-120, default 10</li>
 *   <li>FREEBIE_RETENTION_DAYS - how long finished alerts, offers and notices are kept, at least 30, default 90;
 *       an invalid value is logged and the default used</li>
 *   <li>FREEBIE_DM_MAX_SUBSCRIBERS - most users who may get personal DM alerts, 0-100000, default 100; 0 turns
 *       DM alerts off. An invalid value is logged and the default used</li>
 *   <li>FREEBIE_AUTO_SEND - comma-separated launcher ids posted as soon as they are found, without waiting for
 *       an owner to approve (the review message still appears, with a Stop button). Default
 *       {@code epic,steam,gog}; {@code none} reviews everything. Unknown ids are logged and ignored</li>
 *   <li>FREEBIE_ENABLED - set to false to keep the whole system off without removing the other keys</li>
 * </ul>
 */
public record FreebieConfig(String reviewChannelId, String otherReviewChannelId, Set<String> ownerIds, int pollMinutes,
                            int retentionDays, int dmMaxSubscribers, Set<FreebieStore> autoSend) {
    public static final int DEFAULT_RETENTION_DAYS = 90, MIN_RETENTION_DAYS = 30, MAX_RETENTION_DAYS = 3650;
    public static final int DEFAULT_DM_SUBSCRIBERS = 100, MAX_DM_SUBSCRIBERS = 100_000;
    /** The big PC storefronts: posted without waiting for an owner unless FREEBIE_AUTO_SEND says otherwise. */
    public static final Set<FreebieStore> DEFAULT_AUTO_SEND = Set.of(FreebieStore.EPIC, FreebieStore.STEAM, FreebieStore.GOG);

    public FreebieConfig {
        reviewChannelId = snowflake(reviewChannelId);
        otherReviewChannelId = snowflake(otherReviewChannelId);
        ownerIds = Set.copyOf(ownerIds);
        autoSend = autoSend == null || autoSend.isEmpty() ? Set.of() : Set.copyOf(autoSend);
        if (ownerIds.isEmpty()) throw new IllegalArgumentException("At least one freebie owner ID is required.");
        ownerIds.forEach(FreebieConfig::snowflake);
        if (pollMinutes < 5 || pollMinutes > 120) throw new IllegalArgumentException("Poll interval must be 5-120 minutes.");
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS)
            throw new IllegalArgumentException("Retention must be " + MIN_RETENTION_DAYS + "-" + MAX_RETENTION_DAYS + " days.");
        if (dmMaxSubscribers < 0 || dmMaxSubscribers > MAX_DM_SUBSCRIBERS)
            throw new IllegalArgumentException("DM subscriber cap must be 0-" + MAX_DM_SUBSCRIBERS + ".");
    }

    /** Everything reviewed by an owner first; the shape tests and older callers use. */
    public FreebieConfig(String reviewChannelId, String otherReviewChannelId, Set<String> ownerIds, int pollMinutes,
                         int retentionDays, int dmMaxSubscribers) {
        this(reviewChannelId, otherReviewChannelId, ownerIds, pollMinutes, retentionDays, dmMaxSubscribers, Set.of());
    }

    public FreebieConfig(String reviewChannelId, String otherReviewChannelId, Set<String> ownerIds, int pollMinutes, int retentionDays) {
        this(reviewChannelId, otherReviewChannelId, ownerIds, pollMinutes, retentionDays, DEFAULT_DM_SUBSCRIBERS);
    }

    /** Personal DM alerts are on unless the cap is 0. */
    public boolean dmEnabled() { return dmMaxSubscribers > 0; }

    public FreebieConfig(String reviewChannelId, String otherReviewChannelId, Set<String> ownerIds, int pollMinutes) {
        this(reviewChannelId, otherReviewChannelId, ownerIds, pollMinutes, DEFAULT_RETENTION_DAYS);
    }

    /** Every section reviewed in one channel. */
    public FreebieConfig(String reviewChannelId, Set<String> ownerIds, int pollMinutes) {
        this(reviewChannelId, reviewChannelId, ownerIds, pollMinutes);
    }

    public boolean isOwner(String userId) { return ownerIds.contains(userId); }

    /** True when giveaways for {@code store} are posted without an owner approving them first. */
    public boolean autoSends(FreebieStore store) { return autoSend.contains(store); }

    /** Where a giveaway for {@code store} is reviewed. */
    public String reviewChannelFor(FreebieStore store) {
        return store.section().priority() ? reviewChannelId : otherReviewChannelId;
    }

    public boolean isReviewChannel(String channelId) {
        return reviewChannelId.equals(channelId) || otherReviewChannelId.equals(channelId);
    }

    /** Empty when the system is disabled or not configured; the reason is written to {@code problems}. */
    public static Optional<FreebieConfig> load(Function<String, String> env, List<String> problems) {
        String enabled = env.apply("FREEBIE_ENABLED");
        if (enabled != null && enabled.strip().equalsIgnoreCase("false")) {
            problems.add("FREEBIE_ENABLED=false");
            return Optional.empty();
        }
        String channel = env.apply("FREEBIE_REVIEW_CHANNEL_ID"), owners = env.apply("FREEBIE_OWNER_IDS");
        if (channel == null || channel.isBlank()) problems.add("FREEBIE_REVIEW_CHANNEL_ID is missing");
        if (owners == null || owners.isBlank()) problems.add("FREEBIE_OWNER_IDS is missing");
        if (!problems.isEmpty()) return Optional.empty();
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (String part : owners.split(",")) if (!part.isBlank()) ids.add(part.strip());
            String minutes = env.apply("FREEBIE_POLL_MINUTES");
            int poll = minutes == null || minutes.isBlank() ? 10 : Integer.parseInt(minutes.strip());
            String other = env.apply("FREEBIE_OTHER_REVIEW_CHANNEL_ID");
            String otherChannel = other == null || other.isBlank() ? channel.strip() : other.strip();
            int retention = retentionDays(env.apply("FREEBIE_RETENTION_DAYS"), BunnyLog::warning);
            int dm = dmMaxSubscribers(env.apply("FREEBIE_DM_MAX_SUBSCRIBERS"), BunnyLog::warning);
            var auto = autoSend(env.apply("FREEBIE_AUTO_SEND"), BunnyLog::warning);
            return Optional.of(new FreebieConfig(channel.strip(), otherChannel, ids, poll, retention, dm, auto));
        } catch (IllegalArgumentException invalid) {
            problems.add("invalid freebie settings: " + invalid.getMessage());
            return Optional.empty();
        }
    }

    /** Retention never disables the system: a missing value means the default, an invalid one is logged. */
    static int retentionDays(String raw, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
        try {
            int days = Integer.parseInt(raw.strip());
            if (days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS) return days;
        } catch (NumberFormatException ignored) { /* Reported below. */ }
        warn.accept("[Freebies] FREEBIE_RETENTION_DAYS must be a whole number from " + MIN_RETENTION_DAYS + " to "
                + MAX_RETENTION_DAYS + "; using " + DEFAULT_RETENTION_DAYS + ".");
        return DEFAULT_RETENTION_DAYS;
    }

    /** Like retention, the DM cap never disables the system: missing means the default, invalid is logged. */
    static int dmMaxSubscribers(String raw, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return DEFAULT_DM_SUBSCRIBERS;
        try {
            int cap = Integer.parseInt(raw.strip());
            if (cap >= 0 && cap <= MAX_DM_SUBSCRIBERS) return cap;
        } catch (NumberFormatException ignored) { /* Reported below. */ }
        warn.accept("[Freebies] FREEBIE_DM_MAX_SUBSCRIBERS must be a whole number from 0 to " + MAX_DM_SUBSCRIBERS
                + "; using " + DEFAULT_DM_SUBSCRIBERS + ".");
        return DEFAULT_DM_SUBSCRIBERS;
    }

    /** Missing means the default list; {@code none} (or {@code off}) means review everything. */
    static Set<FreebieStore> autoSend(String raw, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return DEFAULT_AUTO_SEND;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.equals("none") || value.equals("off")) return Set.of();
        Set<FreebieStore> stores = EnumSet.noneOf(FreebieStore.class);
        for (String id : value.split(",")) {
            if (id.isBlank()) continue;
            var store = FreebieStore.byId(id.strip());
            if (store.isPresent()) stores.add(store.get());
            else warn.accept("[Freebies] FREEBIE_AUTO_SEND: unknown launcher '" + id.strip() + "' ignored.");
        }
        return stores;
    }

    public static Optional<FreebieConfig> fromEnvironment(List<String> problems) {
        return load(TokenLoader::getEnvQuietly, problems);
    }

    static String snowflake(String value) {
        if (value == null || !value.matches("[1-9][0-9]{16,19}"))
            throw new IllegalArgumentException("Expected a Discord ID.");
        return value;
    }
}
