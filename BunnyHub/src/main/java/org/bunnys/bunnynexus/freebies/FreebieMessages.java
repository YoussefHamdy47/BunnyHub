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
    /**
     * Whether the message carries this alert reference: as an embed footer, or, on a Components V2 card
     * ({@link FreebieComponentCards}), as a whole subtext line of one of its text blocks.
     */
    public static boolean carriesReference(Message message, String reference) {
        if (message.getEmbeds().stream().anyMatch(embed -> isAlertFooter(embed, reference))) return true;
        if (!message.isUsingComponentsV2()) return false;
        var tree = message.getComponentTree();
        if (tree == null) return false;
        for (var block : tree.findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class))
            for (String line : block.getContent().split("\n"))
                if (line.startsWith(FreebieComponentCards.SUBTEXT)
                        && isAlertFooter(line.substring(FreebieComponentCards.SUBTEXT.length()), reference)) return true;
        return false;
    }
    /** A footer equal to {@code footer}: an embed footer, or a whole {@code -#} line of a Components V2 card. */
    public static boolean hasFooter(Message message, String footer) {
        if (message.getEmbeds().stream().anyMatch(e -> e.getFooter() != null && footer.equals(e.getFooter().getText()))) return true;
        if (!message.isUsingComponentsV2() || message.getComponentTree() == null) return false;
        for (var block : message.getComponentTree().findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class))
            for (String line : block.getContent().split("\n"))
                if (line.equals(FreebieComponentCards.SUBTEXT + footer)) return true;
        return false;
    }

    /**
     * The exact footer of a card with this reference: {@code Powered by <provider> • ref …} for every provider a card can
     * name ({@link FreebieOffer#sourceLabel()}), or the original {@code Source: GamerPower • ref …} wording.
     */
    static boolean isAlertFooter(MessageEmbed embed, String reference) {
        if (embed.getFooter() == null || embed.getFooter().getText() == null) return false;
        return isAlertFooter(embed.getFooter().getText(), reference);
    }

    static boolean isAlertFooter(String footer, String reference) {
        if ((FOOTER_PREFIX + reference).equals(footer) || (LEGACY_FOOTER_PREFIX + reference).equals(footer)) return true;
        for (var provider : FreebieFeed.Provider.values())
            if (("Powered by " + provider.label + " • ref " + reference).equals(footer)) return true;
        return false;
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
        return alertEmbed(offer, reference, false);
    }

    /** {@code steps}: include the claim steps (personal DMs). */
    static MessageEmbed alertEmbed(FreebieOffer offer, String reference, boolean steps) {
        return FreebieCards.embed(offer, "Powered by " + offer.sourceLabel() + " • ref " + reference, steps);
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
        var top = reviewComponents(offer, facts, channels, guilds, "Waiting for review", reviewControls(offer, groupPending),
                Optional.of((pings.isEmpty() ? "" : pings + " ") + headline + "\n" + hint), now);
        return new MessageCreateBuilder().useComponentsV2(true).setComponents(top)
                .setAllowedMentions(List.of()).mentionUsers(ownerIds).build();
    }

    /**
     * The review as Components V2: the ping line, the public card exactly as servers get it, then the owner panel
     * with its controls. When both do not fit Discord's limits, the preview is left out (the panel never is).
     */
    static List<net.dv8tion.jda.api.components.MessageTopLevelComponent> reviewComponents(FreebieOffer offer, FreebieFacts facts,
            int channels, int guilds, String status, List<ActionRow> controls, Optional<String> header, java.time.Instant now) {
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> top = new ArrayList<>();
        header.filter(h -> !h.isBlank()).ifPresent(h -> top.add(net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(h)));
        var panel = org.bunnys.handler.cards.Card.fromEmbed(reviewEmbed(offer, facts, channels, guilds, status, now), controls).components();
        var member = new FreebieAnnouncementCards.Member(offer, facts, "review|" + offer.id(),
                offer.ended(now) ? FreebieAnnouncementCards.Status.ENDED : FreebieAnnouncementCards.Status.LIVE);
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> withPreview = new ArrayList<>(top);
        withPreview.addAll(FreebieComponentCards.components(List.of(member), Optional.empty(), false, now));
        withPreview.addAll(panel);
        if (FreebieComponentCards.textLength(withPreview) <= FreebieComponentCards.MAX_TEXT - 100
                && FreebieComponentCards.componentCount(withPreview) <= FreebieComponentCards.MAX_COMPONENTS) return withPreview;
        top.addAll(panel);
        return top;
    }

    /** The same review rebuilt for an edit; {@code header} is the posted ping line, kept as it was. Edits never notify. */
    static net.dv8tion.jda.api.utils.messages.MessageEditData reviewEdit(FreebieOffer offer, FreebieFacts facts, int channels, int guilds,
            String status, List<ActionRow> controls, Optional<String> header, java.time.Instant now) {
        return new net.dv8tion.jda.api.utils.messages.MessageEditBuilder().useComponentsV2(true)
                .setComponents(reviewComponents(offer, facts, channels, guilds, status, controls, header, now))
                .setAllowedMentions(List.of()).build();
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
        var embed = Embeds.of(null, "Owner review")
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
        return org.bunnys.handler.cards.Card.fromEmbed(Embeds.of(null, "Ready to send?")
                        .setDescription("**" + text(title(offer.title()), 200) + "**\n" + offer.store().label()
                                + "\n\nMessages cannot be recalled once sent.")
                        .addField("CHANNELS", String.valueOf(channels), true)
                        .addField("SERVERS", String.valueOf(guilds), true).setTimestamp(null).build(),
                List.of(ActionRow.of(Button.success(BUTTON_PREFIX + ":confirm:" + offer.id(), "Yes, send it"),
                        Button.secondary(BUTTON_PREFIX + ":" + CONFIRM_QUIET + ":" + offer.id(), "Send without ping")))).message();
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
        return needsOwner(rows, customId -> true);
    }

    /**
     * As {@link #needsOwner(List)}, asking {@code stillOpen} about each enabled bot button: a held-report question
     * answered elsewhere (its decision buttons still showing on an older message) no longer needs the owner.
     */
    public static boolean needsOwner(List<? extends net.dv8tion.jda.api.components.MessageTopLevelComponent> rows,
                                     java.util.function.Predicate<String> stillOpen) {
        for (var row : actionRows(rows)) {
            for (var component : row.getComponents()) {
                if (component instanceof StringSelectMenu) return true;
                if (component instanceof Button button && button.getCustomId() != null
                        && button.getCustomId().startsWith(BUTTON_PREFIX + ":")
                        && !button.getCustomId().startsWith(BUTTON_PREFIX + ":" + CLEAR_ACTION + ":")
                        && !button.isDisabled() && stillOpen.test(button.getCustomId())) return true;
            }
        }
        return false;
    }

    /** Every button row of a message: top-level rows, and the rows inside Components V2 cards. */
    static List<ActionRow> actionRows(List<? extends net.dv8tion.jda.api.components.MessageTopLevelComponent> components) {
        List<ActionRow> rows = new ArrayList<>();
        for (var top : components) {
            if (top instanceof ActionRow row) rows.add(row);
            else if (top instanceof net.dv8tion.jda.api.components.container.Container container)
                for (var child : container.getComponents()) if (child instanceof ActionRow row) rows.add(row);
        }
        return rows;
    }

    public static MessageCreateData summary(FreebieOffer offer, FreebieRepository.Counts counts, List<FreebieRepository.FailedDelivery> failures,
                                            Map<String, String> guildNames) {
        return summary(offer, counts, failures, guildNames, null);
    }

    /** {@code dms}: personal DM totals when this is reported; DMs still queued never hold the summary back. */
    static MessageCreateData summary(FreebieOffer offer, FreebieRepository.Counts counts, List<FreebieRepository.FailedDelivery> failures,
                                     Map<String, String> guildNames, FreebieDmJobs.Totals dms) {
        var embed = Embeds.of(null, "Delivery report")
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
        return org.bunnys.handler.cards.Card.fromEmbed(embed.build()).message();
    }

    public static final int MAX_LIVE_LISTED = 15;
    /** Games shown with their own Claim button in the live list card (three components each, inside Discord's 40). */
    static final int LIVE_CARD_ROWS = 10;

    static String summaryReference(String offerId) { return "Freebie summary • ref " + nonce("summary|" + offerId); }

    static boolean carriesSummaryReference(Message message, String offerId) {
        return hasFooter(message, summaryReference(offerId));
    }

    static boolean carriesReviewReference(Message message, String offerId) {
        return hasFooter(message, "Freebie review • ref " + nonce("review|" + offerId))
                || hasFooter(message, "Offer " + offerId + " • " + Embeds.FOOTER);
    }

    static String noticeReference(String deliveryId) { return "Freebie notice • ref " + nonce("notice|" + deliveryId); }

    static boolean carriesNoticeReference(Message message, String deliveryId) {
        return hasFooter(message, noticeReference(deliveryId));
    }

    /**
     * {@code /free-games}: every owner-approved game still free, one line each. Titles link to the claim page;
     * buttons cover the newest few so the common case is one click.
     */
    public static MessageCreateData liveList(List<FreebieOffer> offers, Optional<FreebieStore> store) {
        String scope = store.map(FreebieStore::label).orElse("every launcher");
        var card = org.bunnys.handler.cards.Card.builder()
                .text("### Free right now" + store.map(s -> " on " + s.label()).orElse("") + "\n-# The free games collection");
        if (offers.isEmpty()) {
            return card.text("Nothing is free on " + scope + " right now. New games are checked every few minutes."
                    + "\nSet up your next discovery with `/free-games setup`.").build().message();
        }
        // One line per game with its own Claim button beside it; Discord's 40 components fit ten of them.
        card.divider();
        int listed = 0;
        for (FreebieOffer offer : offers) {
            if (listed == LIVE_CARD_ROWS) break;
            String line = "**[" + text(title(offer.title()), 120) + "](" + offer.claimUrl() + ")**\n"
                    + FreebieCards.emoji(offer.store()) + " " + offer.store().label()
                    + offer.worth().map(w -> " · ~~" + text(w, 20) + "~~").orElse("") + " · **Free**"
                    + offer.endsAt().map(end -> " · ends <t:" + end.getEpochSecond() + ":R>").orElse("");
            card.section(line, Button.link(offer.claimUrl().toString(), "Claim"));
            listed++;
        }
        int more = offers.size() - listed;
        return card.footer(listed + (listed == 1 ? " game" : " games") + (more > 0 ? " shown, " + more + " more" : "")
                + " • Sources credited on individual cards").build().message();
    }

    /** Sent to a server owner when their alert channel could not receive a game. */
    public static MessageCreateData serverNotice(String guildName, String channelId, FreebieFailure reason, FreebieOffer offer) {
        var embed = Embeds.of(null, "A free-game alert could not be delivered")
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
        var embed = Embeds.of(null, channel ? "A free-game alert channel was deleted" : "A free-game ping role was deleted");
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

