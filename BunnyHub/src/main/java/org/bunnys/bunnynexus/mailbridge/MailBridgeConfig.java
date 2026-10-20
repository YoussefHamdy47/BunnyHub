package org.bunnys.bunnynexus.mailbridge;

import org.bunnys.handler.utils.TokenLoader;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Settings for the Gmail → Discord bridge, all from the environment. The bridge stays off, with the reason logged,
 * until the three Gmail OAuth values are present.
 *
 * @param rules        per category, the patterns that identify it (any one matching is enough)
 * @param senders      the From address must end with one of these (an address or a domain)
 * @param query        Gmail search used to find candidate messages; the cutoff is added to it
 * @param diagnosticChannelId where "couldn't classify" notices go, without the email's content; empty to only log
 */
public record MailBridgeConfig(String clientId, String clientSecret, String refreshToken,
                               String guildId, String channelId, String mentionUserId, Optional<String> diagnosticChannelId,
                               String query, Set<String> senders, Map<MailCategory, List<Pattern>> rules,
                               Duration pollInterval, int maxAttempts) {

    static final String DEFAULT_GUILD = "1043515676960247828";
    static final String DEFAULT_CHANNEL = "1562067773033807912";
    static final String DEFAULT_MENTION = "333644367539470337";
    static final String DEFAULT_QUERY = "from:(openai.com OR chatgpt.com)";
    static final String DEFAULT_SENDERS = "openai.com,chatgpt.com";

    public MailBridgeConfig {
        senders = Set.copyOf(senders);
        rules = Map.copyOf(rules);
    }

    public static Optional<MailBridgeConfig> fromEnvironment(List<String> problems) {
        return load(TokenLoader::getEnvQuietly, problems);
    }

    /** Empty when the bridge is off or misconfigured; the reasons go to {@code problems}, never the secret values. */
    static Optional<MailBridgeConfig> load(Function<String, String> env, List<String> problems) {
        if ("false".equalsIgnoreCase(trim(env.apply("MAIL_BRIDGE_ENABLED")))) {
            problems.add("MAIL_BRIDGE_ENABLED=false");
            return Optional.empty();
        }
        String clientId = trim(env.apply("GMAIL_CLIENT_ID")), secret = trim(env.apply("GMAIL_CLIENT_SECRET")),
                refresh = trim(env.apply("GMAIL_REFRESH_TOKEN"));
        if (clientId.isEmpty()) problems.add("GMAIL_CLIENT_ID is missing");
        if (secret.isEmpty()) problems.add("GMAIL_CLIENT_SECRET is missing");
        if (refresh.isEmpty()) problems.add("GMAIL_REFRESH_TOKEN is missing");
        if (!problems.isEmpty()) return Optional.empty();
        try {
            String guild = snowflake("MAIL_BRIDGE_GUILD_ID", or(env.apply("MAIL_BRIDGE_GUILD_ID"), DEFAULT_GUILD));
            String channel = snowflake("MAIL_BRIDGE_CHANNEL_ID", or(env.apply("MAIL_BRIDGE_CHANNEL_ID"), DEFAULT_CHANNEL));
            String mention = snowflake("MAIL_BRIDGE_MENTION_USER_ID", or(env.apply("MAIL_BRIDGE_MENTION_USER_ID"), DEFAULT_MENTION));
            String diagnostic = trim(env.apply("MAIL_BRIDGE_DIAGNOSTIC_CHANNEL_ID"));
            Optional<String> diagnosticChannel = diagnostic.isEmpty() ? Optional.empty()
                    : Optional.of(snowflake("MAIL_BRIDGE_DIAGNOSTIC_CHANNEL_ID", diagnostic));
            String query = or(env.apply("MAIL_BRIDGE_QUERY"), DEFAULT_QUERY);
            Set<String> senders = new LinkedHashSet<>();
            for (String sender : or(env.apply("MAIL_BRIDGE_SENDERS"), DEFAULT_SENDERS).split(","))
                if (!sender.isBlank()) senders.add(sender.strip().toLowerCase(Locale.ROOT));
            if (senders.isEmpty()) throw new IllegalArgumentException("MAIL_BRIDGE_SENDERS needs at least one sender");
            Map<MailCategory, List<Pattern>> rules = new EnumMap<>(MailCategory.class);
            for (MailCategory category : MailCategory.values()) {
                if (category == MailCategory.UNKNOWN) continue;
                String raw = trim(env.apply("MAIL_BRIDGE_PATTERNS_" + category.name()));
                rules.put(category, raw.isEmpty() ? List.of(literal(category.defaultPattern())) : patterns(category, raw));
            }
            int minutes = range("MAIL_BRIDGE_POLL_MINUTES", env.apply("MAIL_BRIDGE_POLL_MINUTES"), 5, 1, 60);
            int attempts = range("MAIL_BRIDGE_MAX_ATTEMPTS", env.apply("MAIL_BRIDGE_MAX_ATTEMPTS"), 6, 1, 20);
            return Optional.of(new MailBridgeConfig(clientId, secret, refresh, guild, channel, mention, diagnosticChannel,
                    query, senders, rules, Duration.ofMinutes(minutes), attempts));
        } catch (IllegalArgumentException invalid) {
            problems.add(invalid.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code ;}-separated patterns. A pattern written as {@code /regex/} is a case-insensitive regular expression;
     * anything else is a case-insensitive literal.
     */
    static List<Pattern> patterns(MailCategory category, String raw) {
        List<Pattern> patterns = new ArrayList<>();
        for (String part : raw.split(";")) {
            String value = part.strip();
            if (value.isEmpty()) continue;
            if (value.length() > 2 && value.startsWith("/") && value.endsWith("/")) {
                try {
                    patterns.add(Pattern.compile(value.substring(1, value.length() - 1), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException bad) {
                    throw new IllegalArgumentException("MAIL_BRIDGE_PATTERNS_" + category.name() + " has an invalid regex");
                }
            } else patterns.add(literal(value));
        }
        if (patterns.isEmpty()) throw new IllegalArgumentException("MAIL_BRIDGE_PATTERNS_" + category.name() + " is empty");
        return List.copyOf(patterns);
    }

    static Pattern literal(String text) {
        return Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static int range(String name, String raw, int fallback, int min, int max) {
        String value = trim(raw);
        if (value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max);
    }

    private static String snowflake(String name, String value) {
        if (!value.matches("[1-9][0-9]{16,19}")) throw new IllegalArgumentException(name + " must be a Discord ID");
        return value;
    }

    private static String or(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String trim(String value) { return value == null ? "" : value.strip(); }

    /** Never prints the OAuth values. */
    @Override public String toString() {
        return "MailBridgeConfig[channel=" + channelId + ", query=" + query + ", senders=" + senders + "]";
    }
}
