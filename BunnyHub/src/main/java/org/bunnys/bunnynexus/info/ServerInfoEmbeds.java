package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bunnys.bunnynexus.info.InfoEmbeds.clamp;
import static org.bunnys.bunnynexus.info.InfoEmbeds.moment;

/** The {@code /info server} card. Rendering only; everything comes from the cached guild. */
public final class ServerInfoEmbeds {
    private ServerInfoEmbeds() {}

    public static MessageEmbed serverInfo(Guild guild) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏠 " + guild.getName())
                .setDescription("🏠" + " " + guild.getName() + " was created "
                        + moment(guild.getTimeCreated().toEpochSecond()))
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Server ID: " + guild.getId() + " • " + Embeds.FOOTER)
                .setTimestamp(Instant.now());

        if (guild.getIconUrl() != null)
            embed.setThumbnail(guild.getIconUrl());

        // Deliberately the total, not a humans-and-bots split. That split needs a full
        // member cache, which needs the GUILD_MEMBERS intent this bot does not take -
        // counting whatever happens to be cached would report a confidently wrong number.
        embed.addField("Members", "👥" + " "
                + String.format("%,d", guild.getMemberCount()), true);
        embed.addField("Owner", "👑" + " <@" + guild.getOwnerId() + ">", true);
        embed.addField("Roles", "🏷️" + " " + guild.getRoles().size(), true);

        embed.addField("Channels", "💬" + " "
                + guild.getTextChannels().size() + " text\n"
                + guild.getVoiceChannels().size() + " voice\n"
                + guild.getCategories().size() + " categories", true);

        embed.addField("Boosts", "💎" + " " + guild.getBoostCount()
                + "\n" + boostTier(guild), true);

        embed.addField("Verification", "✅" + " " + verification(guild), true);
        embed.addField("Age Restriction", "🔞" + " " + nsfwLevel(guild), true);
        embed.addField("Language", "🌐" + " " + guild.getLocale().getLanguageName(), true);

        if (guild.getAfkChannel() != null)
            embed.addField("AFK", "⏱️" + " " + guild.getAfkChannel().getAsMention()
                    + "\nafter " + duration(guild.getAfkTimeout().getSeconds() * 1000L), true);

        if (guild.getDescription() != null)
            embed.addField("Description", clamp("ℹ️ " + guild.getDescription()), false);

        String features = features(guild);
        if (features != null)
            embed.addField("Features", clamp("✅ " + features), false);

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

    /**
     * Renders a span as {@code 3d 4h 12m 8s}, dropping leading units that are zero.
     *
     * <p>Seconds are always kept, so a bot that restarted moments ago reads as "6s"
     * rather than as an empty string.
     */
    private static String duration(long millis) {
        long totalSeconds = millis / 1000L;

        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder text = new StringBuilder();
        if (days > 0)
            text.append(days).append("d ");
        if (days > 0 || hours > 0)
            text.append(hours).append("h ");
        if (days > 0 || hours > 0 || minutes > 0)
            text.append(minutes).append("m ");
        text.append(seconds).append("s");

        return text.toString();
    }
}
