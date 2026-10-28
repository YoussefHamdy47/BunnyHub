package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/**
 * Confirmation messages of release approvals. The owner confirms exactly the games shown; the preview token in the
 * button binds the confirmation to that owner, that list of games at their reviewed revisions and five minutes
 * ({@link FreebieReleases}). Nothing is approved by the first click.
 */
final class FreebieGroupApproval {
    private FreebieGroupApproval() {}

    /** The confirmation for a previewed release snapshot; the button carries the scope and the preview token. */
    static MessageCreateData confirm(FreebieOffer offer, List<FreebieOffer> members, boolean wholeRelease, String token, int channels, int guilds) {
        var lines = new StringBuilder();
        for (var member : members) lines.append("• **").append(text(title(member.title()), 120)).append("**\n");
        return new MessageCreateBuilder()
                .setEmbeds(Embeds.of("📣", "Send this release?")
                        .setDescription(lines + (members.size() > 1
                                ? "\nThese " + members.size() + " games are posted together: one message and one ping per channel."
                                : "\nPart of an Epic release: games approved later join this post silently.")
                                + "\nOnly these games, as shown, are approved. If anything changes before you confirm, nothing is approved."
                                + "\nMessages cannot be recalled once sent.")
                        .addField("CHANNELS", String.valueOf(channels), true)
                        .addField("SERVERS", String.valueOf(guilds), true).setTimestamp(null).build())
                .setComponents(ActionRow.of(Button.success(FreebieMessages.BUTTON_PREFIX + ":confirmrelease-" + (wholeRelease ? "r" : "s")
                        + "-" + token + ":" + offer.id(), members.size() > 1 ? "Yes, send all " + members.size() : "Yes, send it")))
                .setAllowedMentions(List.of()).build();
    }

    static String result(List<FreebieOffer> approved, int skipped, long channels) {
        return "Approved " + approved.size() + " game(s) as reviewed; sending to " + channels + " channel(s)."
                + (skipped > 0 ? " " + skipped + " game(s) changed or ended before they could be approved and were left out; review them again." : "")
                + " A summary per game will be posted here.";
    }
}
