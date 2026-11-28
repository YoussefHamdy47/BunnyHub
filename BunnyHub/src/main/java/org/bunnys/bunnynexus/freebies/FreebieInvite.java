package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;

/**
 * The "Add BunnyNexus to your server" line on live free-game cards: every alert that reaches a server or a DM also
 * shows people where to get the bot. The link asks only for what free-game alerts use (moderation permissions are
 * left to admins, since asking for ban rights puts people off).
 */
public final class FreebieInvite {
    private FreebieInvite() {}

    static final String LABEL = "Add BunnyNexus to your server";
    private static final Permission[] PERMISSIONS = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_HISTORY, Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL};

    private static volatile String url;

    /** Set once Discord reports the bot ready; until then cards are built without the line. */
    public static void from(JDA jda) {
        jda.setRequiredScopes("applications.commands");
        url = jda.getInviteUrl(PERMISSIONS);
    }

    static void set(String inviteUrl) { url = inviteUrl; }

    /** The bot's invite link, once Discord reported the bot ready. */
    public static java.util.Optional<String> url() { return java.util.Optional.ofNullable(url); }

    /** The community server, where people get help and vote; {@code COMMUNITY_URL} overrides it (default: the support server invite). */
    public static final String DEFAULT_SERVER = "https://discord.gg/gGsdHYhTFv";
    public static final String VOTE = "https://top.gg/bot/795361755223556116/vote";
    private static volatile String server;

    public static String serverUrl() {
        String current = server;
        if (current == null) {
            String configured = org.bunnys.handler.utils.TokenLoader.getEnvQuietly("COMMUNITY_URL");
            current = configured != null && configured.strip().startsWith("https://") ? configured.strip() : DEFAULT_SERVER;
            server = current;
        }
        return current;
    }

    static void server(String url) { server = url; }

    /** "[Add BunnyNexus](…) · [Join our server](…)": the links under every free-game and deal post. Empty before the bot is ready. */
    public static String links() {
        String current = url;
        return current == null ? "" : "[" + LABEL + "](" + current + ") · [Join our server](" + serverUrl() + ")";
    }

    /** A subtext line for under a card's footer, or nothing before the bot is ready. */
    static String line() {
        String links = links();
        return links.isEmpty() ? "" : "\n" + FreebieComponentCards.SUBTEXT + links;
    }

    /** As {@link #line()}, for other cards: the subtext line itself, or empty. */
    public static java.util.Optional<String> subtext() {
        String links = links();
        return links.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of("-# " + links);
    }
}
