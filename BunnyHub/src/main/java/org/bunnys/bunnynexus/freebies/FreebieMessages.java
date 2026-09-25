package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.AppDesign;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Every Discord message the freebie system sends. Pure builders: no REST calls here. */
public final class FreebieMessages {
    private FreebieMessages() {}

    public static final String BUTTON_PREFIX = "freebie_review";
    private static final String FOOTER_PREFIX = "Source: GamerPower • ref ";

    /** Stable per channel+offer, so Discord drops a duplicate if a timed-out send actually arrived. */
    public static String nonce(String deliveryId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(deliveryId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 25);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Short reference printed in the footer; lets a retry find a message that was sent but not acknowledged. */
    public static String reference(String deliveryId) { return nonce(deliveryId).substring(0, 12); }
    public static boolean carriesReference(Message message, String reference) {
        return message.getEmbeds().stream().anyMatch(embed -> embed.getFooter() != null
                && (FOOTER_PREFIX + reference).equals(embed.getFooter().getText()));
    }

    /** The public alert. Only {@code roleId} may be pinged - never @everyone/@here or users. */
    public static MessageCreateData alert(FreebieOffer offer, Optional<String> roleId, String reference) {
        var builder = new MessageCreateBuilder().setEmbeds(alertEmbed(offer, reference))
                .setComponents(ActionRow.of(Button.link(offer.claimUrl().toString(), "Claim it"),
                        Button.link(offer.pageUrl().toString(), "Details")))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        roleId.ifPresent(role -> builder.setContent("<@&" + role + ">").mentionRoles(role));
        return builder.build();
    }

    static MessageEmbed alertEmbed(FreebieOffer offer, String reference) {
        var embed = new EmbedBuilder().setColor(AppDesign.ColorCodes.SUCCESS_GREEN)
                .setAuthor("Free game • " + offer.store().label())
                .setTitle(text(title(offer.title()), 250), offer.claimUrl().toString())
                .setDescription(text(offer.description(), 700))
                .addField("Normally", offer.worth().map(w -> "~~" + w + "~~ → **Free**").orElse("**Free**"), true)
                .addField("Ends", offer.endsAt().map(end -> "<t:" + end.getEpochSecond() + ":R>").orElse("Unknown / while supplies last"), true)
                .setFooter(FOOTER_PREFIX + reference);
        offer.image().ifPresent(image -> embed.setImage(image.toString()));
        return embed.build();
    }

    public static MessageCreateData review(FreebieOffer offer, int channels, int guilds, Set<String> ownerIds) {
        String pings = String.join(" ", ownerIds.stream().sorted().map(id -> "<@" + id + ">").toList());
        return new MessageCreateBuilder().setContent(pings + " **New free game found — waiting for approval.** The first embed is exactly what servers will receive.")
                .setAllowedMentions(List.of()).mentionUsers(ownerIds)
                .setEmbeds(alertEmbed(offer, "preview"), reviewEmbed(offer, channels, guilds, "Waiting for review", AppDesign.ColorCodes.DEFAULT))
                .setComponents(reviewControls(offer))
                .build();
    }


    public static final String STORE_MENU_PREFIX = "freebie_store";
    public static final String CATCH_UP_PREFIX = "freebie_catchup";

    /** Approve / Reject plus a launcher picker to correct the auto-detected store before approving. */
    public static List<ActionRow> reviewControls(FreebieOffer offer) {
        var menu = StringSelectMenu.create(STORE_MENU_PREFIX + ":" + offer.id()).setPlaceholder("Wrong launcher? Change it here");
        for (FreebieStore store : FreebieStore.values()) menu.addOption(store.label(), store.id());
        menu.setDefaultValues(offer.store().id());
        return List.of(ActionRow.of(Button.success(BUTTON_PREFIX + ":approve:" + offer.id(), "Approve"),
                        Button.danger(BUTTON_PREFIX + ":reject:" + offer.id(), "Reject")),
                ActionRow.of(menu.build()));
    }

    /** Offered after /freebie setup when approved games for that launcher are still claimable. */
    public static List<ActionRow> catchUpControls(String guildId, String channelId, FreebieStore store, int live) {
        return List.of(ActionRow.of(Button.primary(CATCH_UP_PREFIX + ":" + guildId + ":" + channelId + ":" + store.id(),
                "Also post the " + live + " game(s) free right now")));
    }
    public static MessageEmbed reviewEmbed(FreebieOffer offer, int channels, int guilds, String status, java.awt.Color color) {
        return new EmbedBuilder().setColor(color).setTitle("Review: " + text(title(offer.title()), 200))
                .addField("Status", status, false)
                .addField("Detected launcher", offer.store().label(), true)
                .addField("GamerPower platforms", text(offer.platforms(), 200), true)
                .addField("Subscribed now", channels + " channel(s) in " + guilds + " server(s)", true)
                .addField("Instructions", text(offer.instructions(), 900), false)
                .setFooter("Offer " + offer.id()).build();
    }

    public static MessageCreateData confirm(FreebieOffer offer, int channels, int guilds) {
        return new MessageCreateBuilder()
                .setContent("Send **" + text(title(offer.title()), 200) + "** to **" + channels + "** channel(s) in **" + guilds
                        + "** server(s) subscribed to " + offer.store().label() + "? This cannot be undone once messages go out.")
                .setComponents(ActionRow.of(Button.success(BUTTON_PREFIX + ":confirm:" + offer.id(), "Yes, send it")))
                .setAllowedMentions(List.of()).build();
    }

    public static List<ActionRow> stopControls(FreebieOffer offer) {
        return List.of(ActionRow.of(Button.danger(BUTTON_PREFIX + ":stop:" + offer.id(), "Stop sending")));
    }

    public static MessageCreateData summary(FreebieOffer offer, FreebieRepository.Counts counts, List<FreebieRepository.FailedDelivery> failures,
                                            Map<String, String> guildNames) {
        var embed = new EmbedBuilder().setTitle("Delivery finished: " + text(title(offer.title()), 200))
                .setColor(counts.failed() == 0 ? AppDesign.ColorCodes.SUCCESS_GREEN : AppDesign.ColorCodes.PASTEL_RED)
                .addField("Sent", String.valueOf(counts.sent()), true)
                .addField("Failed", String.valueOf(counts.failed()), true)
                .addField("Cancelled", String.valueOf(counts.cancelled()), true)
                .setFooter("Offer " + offer.id() + " • final state " + offer.state());
        if (!failures.isEmpty()) {
            var lines = new StringBuilder();
            for (var failure : failures) {
                String line = "• " + text(guildNames.getOrDefault(failure.guildId(), "Unknown server"), 60) + " (`" + failure.guildId()
                        + "`) <#" + failure.channelId() + "> — " + failure.reason().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + (failure.serverNotified() ? " (owner told)" : "") + "\n";
                if (lines.length() + line.length() > 3900) { lines.append("…and more"); break; }
                lines.append(line);
            }
            embed.setDescription(lines.toString());
        }
        return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
    }

    /** Sent to a server owner when their alert channel could not receive a game. */
    public static MessageCreateData serverNotice(String guildName, String channelId, FreebieFailure reason, FreebieOffer offer) {
        var embed = new EmbedBuilder().setColor(AppDesign.ColorCodes.PASTEL_RED)
                .setTitle("A free-game alert could not be delivered")
                .setDescription("Your server **" + text(guildName, 100) + "** is set up to receive free-game alerts in <#" + channelId
                        + ">, but I couldn't post there.\n\n**Why:** " + reason.explanation()
                        + "\n\n**Fix:** give me those permissions in the channel, or pick another channel with `/freebie setup`."
                        + " Use `/freebie remove` to stop these alerts.")
                .addField("The game you missed", "[" + text(title(offer.title()), 200) + "](" + offer.claimUrl() + ")", false)
                .setFooter("You get at most one of these notices per day.");
        return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
    }

    /** GamerPower titles end in "(Store) Giveaway"; the store is already shown separately. */
    static String title(String raw) {
        String title = raw.strip();
        if (title.endsWith(" Giveaway")) title = title.substring(0, title.length() - " Giveaway".length()).strip();
        return title.isEmpty() ? raw : title;
    }

    /** Escapes markdown and caps length; mentions are already neutralised by allowed-mentions. */
    static String text(String raw, int max) {
        if (raw == null || raw.isBlank()) return "—";
        String clean = MarkdownSanitizer.escape(raw.replace("\r", "").strip());
        if (clean.length() <= max) return clean;
        int cut = max - 1;
        if (Character.isHighSurrogate(clean.charAt(cut - 1))) cut--;
        // Never leave a dangling escape backslash at the cut.
        while (cut > 0 && clean.charAt(cut - 1) == '\\') cut--;
        return clean.substring(0, cut) + "…";
    }
}
