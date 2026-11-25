package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Personal DM alerts: rules and messages. Pure; {@link FreebieDmSubscribers} and {@link FreebieDmJobs} persist,
 * {@link FreebieDmWorker} sends. A subscriber stores only the user ID, chosen sections, timestamps and counters.
 */
public final class FreebieDm {
    private FreebieDm() {}

    /** Game DMs a user may receive per UTC day; jobs over it end skipped and are not carried over. */
    public static final int DAILY_CAP = 5;
    public static final String UNSUBSCRIBE_PREFIX = "freebie_dmunsub", PICKER_PREFIX = "freebie_dmsub";

    enum Skip {
        DAILY_CAP("The user already got " + FreebieDm.DAILY_CAP + " game DMs that UTC day.");
        final String text;
        Skip(String text) { this.text = text; }
    }
    enum Cancel {
        OFFER_ENDED("The giveaway ended or was stopped before the DM was sent."),
        UNSUBSCRIBED("The user unsubscribed or no longer follows this section."),
        CANNOT_DM("Discord refused DMs to this user, so their subscription was removed.");
        final String text;
        Cancel(String text) { this.text = text; }
    }

    static String jobId(String offerId, String userId) { return offerId + "|" + userId; }
    static String reference(String jobId) { return FreebieMessages.reference("dm|" + jobId); }
    static String nonce(String jobId) { return FreebieMessages.nonce("dm|" + jobId); }
    /** UTC day bucket for the daily cap. */
    static String day(Instant now) { return now.atOffset(ZoneOffset.UTC).toLocalDate().toString(); }

    /** The same live-approval rule as channel alerts: approved, still free, not stopped or gone. */
    static boolean live(FreebieOffer offer, Instant now) {
        return offer != null && offer.decidedBy().isPresent() && !offer.ended(now)
                && (offer.state() == FreebieOffer.State.APPROVED || offer.state() == FreebieOffer.State.COMPLETED);
    }

    /** A subscriber gets an offer when it is live and they follow the offer's section. */
    static boolean wants(Set<FreebieSection> sections, FreebieOffer offer, Instant now) {
        return live(offer, now) && sections.contains(offer.store().section());
    }

    enum Outcome { UNREACHABLE, RETRY, FAIL }

    /** 50007 (cannot send to user) and unknown user mean the DM can never arrive; 5xx/timeouts/unexpected retry. */
    static Outcome classify(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof ErrorResponseException response) {
            if (response.isServerError()) return Outcome.RETRY;
            if (response.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER
                    || response.getErrorResponse() == ErrorResponse.UNKNOWN_USER) return Outcome.UNREACHABLE;
            return response.getErrorCode() == 0 || response.getErrorCode() >= 130000 ? Outcome.RETRY : Outcome.FAIL;
        }
        return Outcome.RETRY;
    }

    // ------------------------------------------------------------------ messages

    /** The channel alert card with no ping and no mentions at all, plus a stateless Unsubscribe button below it. */
    static MessageCreateData card(FreebieOffer offer, String jobId) {
        var member = new FreebieAnnouncementCards.Member(offer, FreebieFacts.NONE, "dm|" + jobId, FreebieAnnouncementCards.Status.LIVE);
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> top = new ArrayList<>(
                FreebieComponentCards.components(List.of(member), Optional.empty(), false, Instant.now()));
        top.add(ActionRow.of(unsubscribeButton()));
        return new MessageCreateBuilder().useComponentsV2(true).setComponents(top)
                .setAllowedMentions(List.of()).mentionRepliedUser(false).build();
    }

    /** Acts on whoever clicks it, so it carries no user ID and survives restarts. */
    public static Button unsubscribeButton() {
        return Button.secondary(UNSUBSCRIBE_PREFIX, "Unsubscribe").withEmoji(Emoji.fromUnicode("🔕"));
    }

    static boolean carriesReference(Message message, String jobId) {
        return FreebieMessages.carriesReference(message, reference(jobId));
    }

    /** The consent DM; the subscription is only saved once Discord accepts it. */
    public static MessageCreateData consent(Set<FreebieSection> sections) {
        return org.bunnys.handler.cards.Card.builder().heading("Free-game DMs are on")
                .text("You'll get a DM when a new free game is posted in **" + labels(sections) + "**.")
                .text("-# At most " + DAILY_CAP + " a day. Use the button below to stop.")
                .buttons(unsubscribeButton()).build().message();
    }

    /** Section picker for {@code /free-games dms}; bound to the user who asked. */
    public static List<ActionRow> picker(String userId, Set<FreebieSection> current) {
        var menu = StringSelectMenu.create(PICKER_PREFIX + ":" + userId).setPlaceholder("Sections to get DMs for")
                .setRequiredRange(1, FreebieSection.values().length);
        for (FreebieSection section : FreebieSection.values())
            menu.addOption(section.label(), String.valueOf(section.code()), Emoji.fromUnicode(section.emoji()));
        menu.setDefaultValues((current.isEmpty() ? EnumSet.of(FreebieSection.MAJOR_PC) : current).stream()
                .map(s -> String.valueOf(s.code())).toList());
        return List.of(ActionRow.of(menu.build()));
    }

    // ------------------------------------------------------------------ the /free-games dms flow: pick, confirm, result

    public static final String CONFIRM_PREFIX = "freebie_dmok", YES = "yes", NO = "no";

    private static String rules() {
        return "-# At most " + DAILY_CAP + org.bunnys.handler.commands.CommandLinks.render(" a day. `/free-games help` explains how games are checked.");
    }

    /** Step 1: the section picker. Picking only shows the confirm step; nothing is saved or sent yet. */
    public static org.bunnys.handler.cards.Card pickerCard(String userId, Optional<Set<FreebieSection>> current) {
        var card = org.bunnys.handler.cards.Card.builder()
                .heading(current.isPresent() ? "Change your free-game DMs" : "Free-game DMs")
                .text(current.isPresent()
                        ? "You get DMs for **" + labels(current.get()) + "**. Pick different sections below, or **Unsubscribe** to stop."
                        : "Pick the sections you want a DM for. You'll confirm before anything is turned on.")
                .text(rules()).rows(picker(userId, current.orElse(Set.of())));
        if (current.isPresent()) card.buttons(unsubscribeButton());
        return card.build();
    }

    /** Step 2: are you sure? Confirm subscribes (and DMs a new subscriber once), Cancel leaves everything as it was. */
    public static org.bunnys.handler.cards.Card confirmCard(String userId, Set<FreebieSection> chosen, boolean subscribed) {
        String codes = FreebieSection.encode(chosen);
        return org.bunnys.handler.cards.Card.builder()
                .heading(subscribed ? "Update your free-game DMs?" : "Turn on free-game DMs?")
                .text("You'll get a DM when a new free game is posted in **" + labels(chosen) + "**.")
                .text(subscribed ? "-# The change applies right away. No DM is sent for it."
                        : "-# When you confirm, I send you one DM to check I can reach you. If Discord blocks it, nothing is saved.")
                .rows(picker(userId, chosen))
                .buttons(Button.success(CONFIRM_PREFIX + ":" + YES + ":" + userId + ":" + codes, subscribed ? "Save" : "Confirm"),
                        Button.secondary(CONFIRM_PREFIX + ":" + NO + ":" + userId, "Cancel"))
                .build();
    }

    /** Shown the moment Confirm is clicked, while the check DM goes out. */
    public static org.bunnys.handler.cards.Card sendingCard() {
        return org.bunnys.handler.cards.Card.builder().text("Sending you a DM to check I can reach you…").build();
    }

    /** Step 3: the outcome. Subscribers keep an off switch. */
    public static org.bunnys.handler.cards.Card resultCard(String text, boolean subscribed) {
        var card = org.bunnys.handler.cards.Card.builder().text(text);
        if (subscribed) card.buttons(unsubscribeButton());
        return card.build();
    }

    public static String labels(Set<FreebieSection> sections) {
        return String.join(", ", Arrays.stream(FreebieSection.values()).filter(sections::contains).map(FreebieSection::label).toList());
    }
}
