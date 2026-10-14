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

    /** The channel alert card with no ping and no mentions at all, plus a stateless Unsubscribe button. */
    static MessageCreateData card(FreebieOffer offer, String jobId) {
        return new MessageCreateBuilder().setEmbeds(FreebieMessages.alertEmbed(offer, reference(jobId)))
                .setComponents(ActionRow.of(FreebieCards.claimButton(offer), Button.link(offer.pageUrl().toString(), "Offer details"),
                        unsubscribeButton()))
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
        var embed = Embeds.of("📬", "Free-game DMs are on")
                .setDescription("You'll get a DM when the bot owner approves a free game in: **" + labels(sections) + "**.\n"
                        + "At most " + DAILY_CAP + " a day. Use the button below to stop.")
                .setTimestamp(null).build();
        return new MessageCreateBuilder().setEmbeds(embed).setComponents(ActionRow.of(unsubscribeButton()))
                .setAllowedMentions(List.of()).build();
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

    public static String labels(Set<FreebieSection> sections) {
        return String.join(", ", Arrays.stream(FreebieSection.values()).filter(sections::contains).map(FreebieSection::label).toList());
    }
}
