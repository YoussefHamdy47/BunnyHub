package org.bunnys.bunnynexus.deals;

import org.bunnys.handler.utils.TokenLoader;

import java.util.List;
import java.util.function.Function;

/**
 * Settings from the environment (docs/deals/02-architecture.md). Nothing is required: with no keys set, deals are on
 * and the User-Agent carries no contact.
 */
public record DealConfig(boolean enabled, String userAgent) {
    static final String AGENT = "BunnyNexus-Deals/1.0";

    /** Problems (invalid values that fell back to a default) are added to {@code warnings}. */
    static DealConfig load(Function<String, String> env, List<String> warnings) {
        boolean enabled = !"false".equalsIgnoreCase(strip(env.apply("DEALS_ENABLED")));
        String contact = strip(env.apply("DEALS_CONTACT"));
        String agent = AGENT + " (Discord bot)";
        if (contact != null) {
            if (contact.length() <= 100 && contact.chars().allMatch(c -> c >= 0x20 && c < 0x7f && c != '(' && c != ')'))
                agent = AGENT + " (Discord bot; " + contact + ")";
            else warnings.add("DEALS_CONTACT ignored: up to 100 printable ASCII characters, no brackets");
        }
        return new DealConfig(enabled, agent);
    }

    public static DealConfig fromEnvironment(List<String> warnings) { return load(TokenLoader::getEnvQuietly, warnings); }

    private static String strip(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
