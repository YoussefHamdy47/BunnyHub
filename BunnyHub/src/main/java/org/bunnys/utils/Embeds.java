package org.bunnys.utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import java.time.Instant;

/**
 * The one embed style for the whole bot: an emoji-led title, a description, a footer and a timestamp, in the
 * default colour. Only errors are red, and errors are always shown privately or deleted after
 * {@link #ERROR_SECONDS}. Custom (bot) emoji render in titles and descriptions; footers are plain text.
 */
public final class Embeds {
    private Embeds() {}

    public static final String FOOTER = "BunnyNexus";
    /** How long a public error stays up before it deletes itself, where it cannot be ephemeral. */
    public static final int ERROR_SECONDS = 15;

    /** Default-coloured embed with footer and timestamp; add a description or fields as needed. */
    public static EmbedBuilder of(String emoji, String title) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.DEFAULT).setTitle(title(emoji, title))
                .setFooter(FOOTER).setTimestamp(Instant.now());
    }

    public static EmbedBuilder of(String emoji, String title, String description) {
        return of(emoji, title).setDescription(description);
    }

    /** Footer text specific to this embed, still signed with the bot name. */
    public static EmbedBuilder footer(EmbedBuilder embed, String text) {
        return embed.setFooter(signed(text));
    }

    public static EmbedBuilder footer(EmbedBuilder embed, String text, String iconUrl) {
        return embed.setFooter(signed(text), iconUrl);
    }

    public static MessageEmbed error(String title, String description) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.ERROR_RED).setTitle(title(AppDesign.Emojis.ERROR, title))
                .setDescription(description).setFooter(FOOTER).setTimestamp(Instant.now()).build();
    }

    private static String signed(String text) {
        return text == null || text.isBlank() ? FOOTER : text + " • " + FOOTER;
    }

    private static String title(String emoji, String title) {
        return emoji == null || emoji.isBlank() ? title : emoji + " " + title;
    }
}
