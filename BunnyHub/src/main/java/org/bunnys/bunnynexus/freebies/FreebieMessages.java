package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.Embeds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Every Discord message the freebie system sends. Pure builders: no REST calls here. */
public final class FreebieMessages {
    private FreebieMessages() {}

    public static final String BUTTON_PREFIX = "freebie_review";
    private static final String FOOTER_PREFIX = "Powered by GamerPower • ref ";
    private static final String LEGACY_FOOTER_PREFIX = "Source: GamerPower • ref ";

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
        return message.getEmbeds().stream().anyMatch(embed -> isAlertFooter(embed, reference));
    }
    static boolean isAlertFooter(MessageEmbed embed, String reference) {
        return embed.getFooter() != null && ((FOOTER_PREFIX + reference).equals(embed.getFooter().getText())
                || (LEGACY_FOOTER_PREFIX + reference).equals(embed.getFooter().getText())
                || ("Powered by Epic Games • ref " + reference).equals(embed.getFooter().getText()));
    }

    /** The public alert. Only {@code roleId} may be pinged - never @everyone/@here or users. */
    public static MessageCreateData alert(FreebieOffer offer, Optional<String> roleId, String reference) {
        var builder = new MessageCreateBuilder().setEmbeds(alertEmbed(offer, reference))
                .setComponents(FreebieCards.claimRow(offer))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        roleId.ifPresent(role -> builder.setContent("<@&" + role + ">").mentionRoles(role));
        return builder.build();
    }

    static MessageEmbed alertEmbed(FreebieOffer offer, String reference) {
        return FreebieCards.embed(offer, "Powered by " + offer.sourceLabel() + " • ref " + reference);
    }

    /** Every review pings the owners, whatever the section. */
    public static MessageCreateData review(FreebieOffer offer, int channels, int guilds, Set<String> ownerIds) {
        return review(offer, FreebieFacts.NONE, 0, channels, guilds, ownerIds, false, java.time.Instant.now());
    }

    /** Main's signature, kept for callers: {@code automatic} marks a launcher on the auto-send list. */
    public static MessageCreateData review(FreebieOffer offer, int channels, int guilds, Set<String> ownerIds, boolean automatic) {
        return review(offer, FreebieFacts.NONE, 0, channels, guilds, ownerIds, automatic, java.time.Instant.now());
    }

    /**
     * {@code automatic}: the launcher is on the auto-send list, so it goes out on its own; the owners are still pinged.
     * {@code groupPending}: other games of the same Epic release still awaiting review (enables "Approve release").
     */
    static MessageCreateData review(FreebieOffer offer, FreebieFacts facts, int groupPending, int channels, int guilds,
                                    Set<String> ownerIds, boolean automatic, java.time.Instant now) {
        boolean priority = offer.store().section().priority();
        String pings = String.join(" ", ownerIds.stream().sorted().map(id -> "<@" + id + ">").toList());
        String headline = automatic ? "**Found · " + offer.store().label() + " is sent automatically**"
                : priority ? "**Ready for review · Major PC**"
                : "**Ready for review · " + offer.store().section().label().toLowerCase(Locale.ROOT) + "**";
        String hint = automatic ? "It goes out within a minute. Use **Stop** afterwards if something is wrong."
                : "Public preview below. Review the launcher and audience before approving.";
        return new MessageCreateBuilder().setContent((pings.isEmpty() ? "" : pings + " ") + headline + "\n" + hint)
                .setAllowedMentions(List.of()).mentionUsers(ownerIds)
                .setEmbeds(FreebieAnnouncementCards.preview(offer, facts, now), reviewEmbed(offer, facts, channels, guilds, "Waiting for review", now))
                .setComponents(reviewControls(offer, groupPending))
                .build();
    }


    public static final String STORE_MENU_PREFIX = "freebie_store";
    public static final String CATCH_UP_PREFIX = "freebie_catchup";

    /** Approve / Reject plus a launcher picker to correct the auto-detected store before approving. */
    public static List<ActionRow> reviewControls(FreebieOffer offer) { return reviewControls(offer, 0); }

    /** With other games of the same Epic release pending, one extra button approves them together (one first post). */
    static List<ActionRow> reviewControls(FreebieOffer offer, int groupPending) {
        var menu = StringSelectMenu.create(STORE_MENU_PREFIX + ":" + offer.id()).setPlaceholder("Wrong launcher? Change it here");
        for (FreebieStore store : FreebieStore.values()) menu.addOption(store.label(), store.id(), Emoji.fromFormatted(FreebieCards.emoji(store)));
        menu.setDefaultValues(offer.store().id());
        List<Button> decisions = new ArrayList<>(List.of(Button.success(BUTTON_PREFIX + ":approve:" + offer.id(), "Approve"),
                Button.danger(BUTTON_PREFIX + ":reject:" + offer.id(), "Reject"), clearButton()));
        if (groupPending > 0) decisions.add(1, Button.primary(BUTTON_PREFIX + ":approvegroup:" + offer.id(),
                "Approve release (" + (groupPending + 1) + " games)"));
        return List.of(ActionRow.of(decisions),
                ActionRow.of(menu.build()));
    }

    /** Catch-up for every launcher the channel is set up for (after a multi-launcher setup). */
    public static List<ActionRow> catchUpAllControls(String guildId, String channelId, int live) {
        return catchUp(guildId, channelId, CATCH_UP_ALL, live);
    }

    public static final String CATCH_UP_ALL = "all";

    private static List<ActionRow> catchUp(String guildId, String channelId, String scope, int live) {
        return List.of(ActionRow.of(Button.primary(CATCH_UP_PREFIX + ":" + guildId + ":" + channelId + ":" + scope,
                "Post " + live + (live == 1 ? " available game" : " available games"))));
    }

    public static MessageEmbed reviewEmbed(FreebieOffer offer, int channels, int guilds, String status) {
        return reviewEmbed(offer, FreebieFacts.NONE, channels, guilds, status, java.time.Instant.now());
    }

    static MessageEmbed reviewEmbed(FreebieOffer offer, FreebieFacts facts, int channels, int guilds, String status, java.time.Instant now) {
        var embed = Embeds.of("🔎", "Owner review")
                .setDescription("**" + text(title(offer.title()), 200) + "**\n" + status)
                .addField("LAUNCHER", offer.store().label() + "\n" + offer.store().section().emoji() + " "
                        + offer.store().section().label(), true)
                .addField("AUDIENCE", "**" + channels + "** channels\n**" + guilds + "** servers", true)
                .addField("SOURCE / REGION", offer.sourceLabel() + " · " + offer.region(), false)
                .addField("SOURCE PLATFORMS", text(offer.platforms(), 200), false)
                .setTimestamp(null);
        var checks = new ArrayList<String>();
        if (facts.official()) checks.add("Official Epic title, description, artwork and claim window.");
        facts.reportedPlatforms().filter(p -> !p.equals(offer.platforms())).ifPresent(p -> checks.add("GamerPower also reported: "
                + text(p, 120) + " (not verified)."));
        var mobile = facts.variants().stream().filter(v -> v.liveAt(now)).map(FreebieFacts.Variant::label).toList();
        if (!mobile.isEmpty()) checks.add("Separate mobile offers (EGData): " + String.join(", ", mobile) + ". Approving includes their links.");
        if (facts.official() && facts.regions().isEmpty()) checks.add("Regional restrictions not verified.");
        if (!checks.isEmpty()) embed.addField("CHECKS", text(String.join("\n", checks), 1000), false);
        return embed.setFooter("Freebie review • ref " + nonce("review|" + offer.id())).build();
    }

    public static MessageCreateData confirm(FreebieOffer offer, int channels, int guilds) {
        return new MessageCreateBuilder()
                .setEmbeds(Embeds.of("📣", "Ready to send?")
                        .setDescription("**" + text(title(offer.title()), 200) + "**\n" + offer.store().label()
                                + "\n\nMessages cannot be recalled once sent.")
                        .addField("CHANNELS", String.valueOf(channels), true)
                        .addField("SERVERS", String.valueOf(guilds), true).setTimestamp(null).build())
                .setComponents(ActionRow.of(Button.success(BUTTON_PREFIX + ":confirm:" + offer.id(), "Yes, send it"),
                        Button.secondary(BUTTON_PREFIX + ":" + CONFIRM_QUIET + ":" + offer.id(), "Send without ping")))
                .setAllowedMentions(List.of()).build();
    }

    /** The confirmation that sends without mentioning the servers' roles. */
    public static final String CONFIRM_QUIET = "confirmquiet";

    public static List<ActionRow> stopControls(FreebieOffer offer) {
        return List.of(ActionRow.of(Button.danger(BUTTON_PREFIX + ":stop:" + offer.id(), "Stop sending"), clearButton()));
    }

    public static final String CLEAR_ACTION = "clear";

    /** Deletes the bot's review messages that no longer need an owner. See {@link FreebieReviewCleanup}. */
    public static Button clearButton() {
        return Button.secondary(BUTTON_PREFIX + ":" + CLEAR_ACTION + ":finished", "Clear finished");
    }

    /** The rows plus a Clear finished button, so every review message can tidy the channel, even a decided one. */
    public static List<ActionRow> withClear(List<ActionRow> rows) {
        if (rows.isEmpty()) return List.of(ActionRow.of(clearButton()));
        boolean present = rows.stream().flatMap(row -> row.getComponents().stream())
                .anyMatch(c -> c instanceof Button b && clearButton().getCustomId().equals(b.getCustomId()));
        if (present || rows.size() >= 5) return rows;
        List<ActionRow> withButton = new ArrayList<>(rows);
        withButton.add(ActionRow.of(clearButton()));
        return withButton;
    }

    /**
     * Whether a review message still has something for an owner to do: an Approve, Reject, Stop or confirm button,
     * or the launcher picker. Clear finished alone doesn't count.
     */
    public static boolean needsOwner(List<? extends net.dv8tion.jda.api.components.MessageTopLevelComponent> rows) {
        for (var top : rows) {
            if (!(top instanceof ActionRow row)) continue;
            for (var component : row.getComponents()) {
                if (component instanceof StringSelectMenu) return true;
                if (component instanceof Button button && button.getCustomId() != null
                        && button.getCustomId().startsWith(BUTTON_PREFIX + ":")
                        && !button.getCustomId().startsWith(BUTTON_PREFIX + ":" + CLEAR_ACTION + ":")
                        && !button.isDisabled()) return true;
            }
        }
        return false;
    }

    public static MessageCreateData summary(FreebieOffer offer, FreebieRepository.Counts counts, List<FreebieRepository.FailedDelivery> failures,
                                            Map<String, String> guildNames) {
        return summary(offer, counts, failures, guildNames, null);
    }

    /** {@code dms}: personal DM totals when this is reported; DMs still queued never hold the summary back. */
    static MessageCreateData summary(FreebieOffer offer, FreebieRepository.Counts counts, List<FreebieRepository.FailedDelivery> failures,
                                     Map<String, String> guildNames, FreebieDmJobs.Totals dms) {
        var embed = Embeds.of("📬", "Delivery report")
                .setDescription("**" + text(title(offer.title()), 200) + "**\n" + offer.store().label())
                .addField("✓ SENT", String.valueOf(counts.sent()), true)
                .addField("! FAILED", String.valueOf(counts.failed()), true)
                .addField("— CANCELLED", String.valueOf(counts.cancelled()), true);
        embed.setFooter(summaryReference(offer.id()));
        if (dms != null && dms.sent() + dms.skipped() + dms.failed() + dms.cancelled() + dms.open() > 0)
            embed.appendDescription("\n\n**Personal DMs** · " + dms.sent() + " sent · " + dms.open() + " still queued · "
                    + dms.skipped() + " over the daily limit · " + dms.failed() + " failed · " + dms.cancelled() + " cancelled");
        if (!failures.isEmpty()) {
            var lines = new StringBuilder();
            for (var failure : failures) {
                String line = "• " + text(guildNames.getOrDefault(failure.guildId(), "Unknown server"), 60) + " (`" + failure.guildId()
                        + "`) <#" + failure.channelId() + "> — " + failure.reason().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + (failure.serverNotified() ? " (owner told)" : "") + "\n";
                if (lines.length() + line.length() > 3500) { lines.append("…and more"); break; }
                lines.append(line);
            }
            embed.appendDescription("\n\n**Destinations needing attention**\n" + lines);
        }
        return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
    }

    public static final int MAX_LIVE_LISTED = 15;

    static String summaryReference(String offerId) { return "Freebie summary • ref " + nonce("summary|" + offerId); }

    static boolean carriesSummaryReference(Message message, String offerId) {
        return message.getEmbeds().stream().anyMatch(embed -> embed.getFooter() != null
                && summaryReference(offerId).equals(embed.getFooter().getText()));
    }

    static boolean carriesReviewReference(Message message, String offerId) {
        return message.getEmbeds().stream().anyMatch(embed -> embed.getFooter() != null
                && (("Freebie review • ref " + nonce("review|" + offerId)).equals(embed.getFooter().getText())
                || ("Offer " + offerId + " • " + Embeds.FOOTER).equals(embed.getFooter().getText())));
    }

    static String noticeReference(String deliveryId) { return "Freebie notice • ref " + nonce("notice|" + deliveryId); }

    static boolean carriesNoticeReference(Message message, String deliveryId) {
        return message.getEmbeds().stream().anyMatch(embed -> embed.getFooter() != null
                && noticeReference(deliveryId).equals(embed.getFooter().getText()));
    }

    /**
     * {@code /free-games}: every owner-approved game still free, one line each. Titles link to the claim page;
     * buttons cover the newest few so the common case is one click.
     */
    public static MessageCreateData liveList(List<FreebieOffer> offers, Optional<FreebieStore> store) {
        String scope = store.map(FreebieStore::label).orElse("every launcher");
        var embed = Embeds.of("🎁", "Free right now" + store.map(s -> " on " + s.label()).orElse(""))
                .setAuthor("BunnyNexus • THE FREE GAMES COLLECTION").setTimestamp(null);
        if (offers.isEmpty()) {
            embed.setDescription("Nothing is free on " + scope + " right now. New games are checked every few minutes."
                    + "\n\nSet up your next discovery with `/free-games setup`.");
            return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
        }
        var lines = new StringBuilder();
        int listed = 0;
        for (FreebieOffer offer : offers) {
            String line = "**[" + text(title(offer.title()), 120) + "](" + offer.claimUrl() + ")**\n"
                    + offer.store().label()
                    + offer.worth().map(w -> " · ~~" + text(w, 20) + "~~").orElse("") + " · **FREE**"
                    + offer.endsAt().map(end -> " · ends <t:" + end.getEpochSecond() + ":R>").orElse("") + "\n\n";
            if (lines.length() + line.length() > 3800) break;
            lines.append(line);
            listed++;
        }
        embed.setDescription(lines.toString().strip());
        Embeds.footer(embed, listed + (listed == 1 ? " game" : " games") + " • Sources credited on individual cards");
        List<Button> buttons = new ArrayList<>();
        for (FreebieOffer offer : offers.subList(0, Math.min(listed, 5)))
            buttons.add(Button.link(offer.claimUrl().toString(), label(title(offer.title()))));
        return new MessageCreateBuilder().setEmbeds(embed.build()).setComponents(ActionRow.of(buttons))
                .setAllowedMentions(List.of()).build();
    }

    /** Sent to a server owner when their alert channel could not receive a game. */
    public static MessageCreateData serverNotice(String guildName, String channelId, FreebieFailure reason, FreebieOffer offer) {
        var embed = Embeds.of("⚠️", "A free-game alert could not be delivered")
                .setDescription("Alerts need attention in **" + text(guildName, 100) + "** · <#" + channelId + ">")
                .addField("WHAT HAPPENED", reason.explanation(), false)
                .addField("NEXT STEP", "Check channel access and permissions, or choose a new destination in `/free-games setup`."
                        + "\nTo stop alerts, open a section there and untick its launchers.", false)
                .addField("VIEW THE OFFER", "[" + text(title(offer.title()), 200) + "](" + offer.claimUrl() + ")", false);
        Embeds.footer(embed, "You get at most one of these notices per day.");
        return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
    }

    /** Sent to a server owner when an alert channel or ping role they set up was deleted. */
    static MessageCreateData changeNotice(String guildName, FreebieNoticeStore.Change change) {
        var launchers = String.join(", ", change.stores().stream().map(id -> FreebieStore.byId(id).map(FreebieStore::label).orElse(id)).toList());
        String name = change.targetName().map(n -> text(n, 80)).orElse(null);
        boolean channel = FreebieNoticeStore.CHANNEL_DELETED.equals(change.kind());
        var embed = Embeds.of(channel ? "🗑️" : "🔕", channel ? "A free-game alert channel was deleted" : "A free-game ping role was deleted");
        if (channel) {
            embed.setDescription("In **" + text(guildName, 100) + "**, the channel " + (name == null ? "" : "**#" + name + "** ")
                    + "that received free-game alerts no longer exists.")
                    .addField("WHAT CHANGED", "I stopped posting **" + text(launchers, 900) + "** there and removed those settings. "
                            + "Alerts that were still queued for it were cancelled.", false)
                    .addField("NEXT STEP", "Pick a new channel in `/free-games setup` to keep getting these games.", false);
        } else {
            String where = String.join(", ", change.channels().stream().distinct().map(id -> "<#" + id + ">").toList());
            embed.setDescription("In **" + text(guildName, 100) + "**, the role " + (name == null ? "" : "**@" + name + "** ")
                    + "pinged for free games no longer exists.")
                    .addField("WHAT CHANGED", "Alerts for **" + text(launchers, 900) + "** still post"
                            + (where.isEmpty() ? "" : " in " + where) + ", but without a ping.", false)
                    .addField("NEXT STEP", "Choose a new ping role in `/free-games setup`, then post a new role picker if you use one.", false);
        }
        Embeds.footer(embed, "Sent once for this change.");
        return new MessageCreateBuilder().setEmbeds(embed.build()).setAllowedMentions(List.of()).build();
    }

    /** GamerPower titles end in "(Store) Giveaway"; the store is already shown separately. */
    static String title(String raw) {
        String title = raw.strip();
        if (title.endsWith(" Giveaway")) title = title.substring(0, title.length() - " Giveaway".length()).strip();
        return title.isEmpty() ? raw : title;
    }

    /** Button labels are plain text (no markdown) capped at Discord's 80 characters. */
    static String label(String raw) {
        String clean = raw == null || raw.isBlank() ? "Claim" : raw.strip();
        return clean.length() <= 80 ? clean : clean.substring(0, 79) + "…";
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

