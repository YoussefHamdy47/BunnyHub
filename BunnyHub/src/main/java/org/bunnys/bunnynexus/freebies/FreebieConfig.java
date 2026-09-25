package org.bunnys.bunnynexus.freebies;

import org.bunnys.handler.utils.TokenLoader;
import java.util.*;
import java.util.function.Function;

/**
 * Deployment-only settings read from .env / the process environment. Nothing here can be changed
 * from Discord, so a server admin (or a compromised command) cannot redirect reviews or approve alerts.
 *
 * <ul>
 *   <li>FREEBIE_REVIEW_CHANNEL_ID - private channel where discovered games wait for approval (required)</li>
 *   <li>FREEBIE_OWNER_IDS - comma-separated user IDs allowed to approve/reject/stop (required)</li>
 *   <li>FREEBIE_POLL_MINUTES - GamerPower poll interval, 5-120, default 10</li>
 *   <li>FREEBIE_ENABLED - set to false to keep the whole system off without removing the other keys</li>
 * </ul>
 */
public record FreebieConfig(String reviewChannelId, Set<String> ownerIds, int pollMinutes) {
    public FreebieConfig {
        reviewChannelId = snowflake(reviewChannelId);
        ownerIds = Set.copyOf(ownerIds);
        if (ownerIds.isEmpty()) throw new IllegalArgumentException("At least one freebie owner ID is required.");
        ownerIds.forEach(FreebieConfig::snowflake);
        if (pollMinutes < 5 || pollMinutes > 120) throw new IllegalArgumentException("Poll interval must be 5-120 minutes.");
    }

    public boolean isOwner(String userId) { return ownerIds.contains(userId); }

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
            return Optional.of(new FreebieConfig(channel.strip(), ids, poll));
        } catch (IllegalArgumentException invalid) {
            problems.add("invalid freebie settings: " + invalid.getMessage());
            return Optional.empty();
        }
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
