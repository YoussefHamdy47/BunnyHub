package org.bunnys.utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import java.util.List;

/** Shared handler messages, using BunnyNexus's own presentation. */
public final class SystemEmbeds {
    private SystemEmbeds() {}
    public static MessageEmbed error(String title, String text) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.ERROR_RED).setTitle(title).setDescription(text).build();
    }
    public static MessageEmbed denied(String title, String text) { return error(title, text); }
    public static MessageEmbed warning(String title, String text) { return error(title, text); }
    public static MessageEmbed notice(String title, String text) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.CYAN).setTitle(title).setDescription(text).build();
    }
    public static MessageEmbed busy() { return error("Busy", "Please try again shortly."); }
    public static MessageEmbed slashOnly(String path) { return notice("Use the slash command", "Use `/" + path + "` for this action."); }
    public static MessageEmbed buttonCooldown(long remaining) {
        return error("Please wait", "Try again in " + Math.max(1, (remaining + 999) / 1000) + " seconds.");
    }
    public static MessageEmbed rateLimited(String mention, long deadline) {
        return error("Please wait", mention + ", try again <t:" + deadline + ":R>.");
    }
    public static MessageEmbed missingImplementation(String path, List<String> developers) {
        return error("Unavailable", "This command is not implemented yet.");
    }
    public static MessageEmbed crashed(String reference, boolean vanishes) {
        return error("Action failed", "Please try again. Error reference: `" + reference + "`."
                + (vanishes ? " This notice disappears after 15 seconds." : ""));
    }
}
