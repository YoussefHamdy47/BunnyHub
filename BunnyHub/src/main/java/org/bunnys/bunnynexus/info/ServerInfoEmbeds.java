package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.entities.Guild;
import org.bunnys.handler.cards.Card;
import org.bunnys.utils.Embeds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bunnys.bunnynexus.info.InfoEmbeds.clamp;
import static org.bunnys.bunnynexus.info.InfoEmbeds.moment;

/** The {@code /info server} card. Rendering only; everything comes from the cached guild. */
public final class ServerInfoEmbeds {
    private ServerInfoEmbeds() {}

    public static Card serverInfo(Guild guild) {
        List<String> lines = new ArrayList<>();
        // Deliberately the total, not a humans-and-bots split. That split needs a full
        // member cache, which needs the GUILD_MEMBERS intent this bot does not take -
        // counting whatever happens to be cached would report a confidently wrong number.
        lines.add("👥 **Members** " + String.format("%,d", guild.getMemberCount()));
        lines.add("👑 **Owner** <@" + guild.getOwnerId() + ">");
        lines.add("🏷️ **Roles** " + guild.getRoles().size());
        lines.add("💬 **Channels** " + guild.getTextChannels().size() + " text · "
                + guild.getVoiceChannels().size() + " voice · " + guild.getCategories().size() + " categories");
        lines.add("💎 **Boosts** " + guild.getBoostCount() + " (" + boostTier(guild) + ")");
        lines.add("✅ **Verification** " + verification(guild));
        lines.add("🔞 **Age Restriction** " + nsfwLevel(guild));
        lines.add("🌐 **Language** " + guild.getLocale().getLanguageName());
        if (guild.getAfkChannel() != null)
            lines.add("⏱️ **AFK** " + guild.getAfkChannel().getAsMention() + " after "
                    + duration(guild.getAfkTimeout().getSeconds() * 1000L));

        // The banner is the one image worth showing large; the icon sits beside the heading.
        var card = Card.builder()
                .image(guild.getBannerUrl())
                .header("## 🏠 " + guild.getName() + "\nCreated " + moment(guild.getTimeCreated().toEpochSecond()), guild.getIconUrl())
                .divider()
                .text(String.join("\n", lines));

        if (guild.getDescription() != null)
            card.field("Description", clamp("ℹ️ " + guild.getDescription()));

        String features = features(guild);
        if (features != null)
            card.field("Features", clamp("✅ " + features));

        return card.footer("Server ID: " + guild.getId() + " • " + Embeds.FOOTER).build();
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
