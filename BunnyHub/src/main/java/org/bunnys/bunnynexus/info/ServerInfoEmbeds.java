package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.utils.AppDesign;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bunnys.bunnynexus.info.InfoEmbeds.clamp;

/** The {@code /info server} card. Rendering only; everything comes from the cached guild. */
public final class ServerInfoEmbeds {
    private ServerInfoEmbeds() {}

    /** The classic server card: the icon in the corner, plain labels in a three-column grid, the server ID below. */
    public static MessageEmbed serverInfo(Guild guild) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(guild.getName())
                .setDescription(guild.getName() + " was created on <t:" + guild.getTimeCreated().toEpochSecond() + ":F>")
                .setThumbnail(guild.getIconUrl())
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Server ID: " + guild.getId(), guild.getIconUrl());

        // Deliberately the total, not a humans-and-bots split. That split needs a full
        // member cache, which needs the GUILD_MEMBERS intent this bot does not take -
        // counting whatever happens to be cached would report a confidently wrong number.
        embed.addField("Total Members", String.format("%,d", guild.getMemberCount()), true);
        embed.addField("Owner", "<@" + guild.getOwnerId() + ">", true);
        embed.addField("Role Count", String.valueOf(guild.getRoles().size()), true);

        embed.addField("Categories", String.valueOf(guild.getCategories().size()), true);
        embed.addField("Text Channels", String.valueOf(guild.getTextChannels().size()), true);
        embed.addField("Voice Channels", String.valueOf(guild.getVoiceChannels().size()), true);

        embed.addField("Boosts", String.valueOf(guild.getBoostCount()), true);
        embed.addField("Boost Tier", boostTier(guild), true);
        embed.addField("Verification Level", verification(guild), true);

        embed.addField("Explicit Content Filter", nsfwLevel(guild), true);
        embed.addField("Region", guild.getLocale().getLocale(), true);
        if (guild.getAfkChannel() != null) {
            embed.addField("AFK Channel", guild.getAfkChannel().getAsMention(), true);
            embed.addField("AFK Timeout", afkTimeout(guild.getAfkTimeout().getSeconds()), true);
        }

        if (guild.getDescription() != null)
            embed.addField("Description", clamp(guild.getDescription()), false);

        String features = features(guild);
        if (features != null)
            embed.addField("Features", clamp(features), false);

        // The banner is the one image worth showing large; the icon is already the thumbnail.
        if (guild.getBannerUrl() != null)
            embed.setImage(guild.getBannerUrl());

        return embed.build();
    }

    private static String boostTier(Guild guild) {
        return switch (guild.getBoostTier()) {
            case TIER_1 -> "Tier 1";
            case TIER_2 -> "Tier 2";
            case TIER_3 -> "Tier 3";
            case NONE -> "No tier";
            default -> "Unknown tier";
        };
    }

    /** Discord's own wording, shortened to something that fits an inline field. */
    private static String verification(Guild guild) {
        return switch (guild.getVerificationLevel()) {
            case NONE -> "Unrestricted";
            case LOW -> "Verified email";
            case MEDIUM -> "Registered 5+ minutes";
            case HIGH -> "Member 10+ minutes";
            case VERY_HIGH -> "Verified phone";
            default -> "Unknown";
        };
    }

    private static String nsfwLevel(Guild guild) {
        return switch (guild.getNSFWLevel()) {
            case DEFAULT -> "Default";
            case SAFE -> "Safe";
            case EXPLICIT -> "Explicit";
            case AGE_RESTRICTED -> "Age restricted";
            default -> "Unknown";
        };
    }

    /**
     * Guild feature flags, made readable.
     *
     * <p>Discord ships these as {@code SCREAMING_SNAKE_CASE}; printed raw they look like
     * a leaked internal constant.
     *
     * @return null when the guild has none, so the caller can drop the field
     */
    private static String features(Guild guild) {
        if (guild.getFeatures().isEmpty())
            return null;

        List<String> readable = new ArrayList<>();
        for (String feature : guild.getFeatures()) {
            StringBuilder pretty = new StringBuilder();
            for (String word : feature.toLowerCase(Locale.ROOT).split("_")) {
                if (word.isEmpty())
                    continue;
                if (!pretty.isEmpty())
                    pretty.append(' ');
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            readable.add(pretty.toString());
        }

        readable.sort(String::compareToIgnoreCase);
        return String.join(", ", readable);
    }

    /** "1 minute", "5 minutes", "1 hour": Discord's own AFK choices, written out. */
    static String afkTimeout(long seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, seconds / 60);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
