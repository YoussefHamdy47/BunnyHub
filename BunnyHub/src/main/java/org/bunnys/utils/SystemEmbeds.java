package org.bunnys.utils;

import net.dv8tion.jda.api.entities.MessageEmbed;

/** Shared handler messages. Everything except {@link #notice} and {@link #slashOnly} is an error embed. */
public final class SystemEmbeds {
    private SystemEmbeds() {}

    public static MessageEmbed error(String title, String text) { return Embeds.error(title, text); }
    public static MessageEmbed denied(String title, String text) { return Embeds.error(title, text); }
    public static MessageEmbed warning(String title, String text) { return Embeds.error(title, text); }
    public static MessageEmbed notice(String title, String text) {
        return Embeds.of(AppDesign.Emojis.VERIFY, title, text).build();
    }
    public static MessageEmbed busy() { return error("Busy", "Please try again shortly."); }
    public static MessageEmbed slashOnly(String path) { return notice("Use the slash command", "Use `/" + path + "` for this action."); }
    public static MessageEmbed buttonCooldown(long remaining) {
        return error("Please wait", "Try again in " + Math.max(1, (remaining + 999) / 1000) + " seconds.");
    }
    public static MessageEmbed rateLimited(String mention, long deadline) {
        return error("Please wait", mention + ", try again <t:" + deadline + ":R>.");
    }
    public static MessageEmbed missingImplementation() {
        return error("Unavailable", "This command is not implemented yet.");
    }
    public static MessageEmbed crashed(String reference, boolean vanishes) {
        return error("Action failed", "Please try again. Error reference: `" + reference + "`."
                + (vanishes ? " This notice disappears after " + Embeds.ERROR_SECONDS + " seconds." : ""));
    }
}
