package org.bunnys.selects;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieDmService;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

/**
 * The section picker from {@code /free-games dms}: {@code freebie_dmsub:<userId>}. Only that user may use it.
 * Picking saves nothing: it turns the picker into a confirm step, and {@code FreebieDmConfirmButtons} does the
 * consent DM and the save.
 */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieDmSubscribeSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieDm.PICKER_PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var sections = FreebieSection.decode(String.join("", event.getValues()));
        if (system == null || args.length != 2 || !args[1].equals(event.getUser().getId()) || sections.isEmpty()) {
            event.reply(org.bunnys.handler.cards.Card.builder().text(org.bunnys.handler.commands.CommandLinks.render("Use `/free-games dms` to choose your own free-game DMs.")).build().message())
                    .setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
        boolean subscribed = system.dms().current(event.getUser().getId()).isPresent();
        event.getHook().editOriginal(FreebieDm.confirmCard(event.getUser().getId(), sections, subscribed).edit()).queue();
    }

    /** True only when Discord accepted the DM; a refusal (or an unknown outcome) means "not saved". */
    public static boolean consent(User user, MessageCreateData data) {
        try {
            user.openPrivateChannel().complete().sendMessage(data).complete();
            return true;
        } catch (RuntimeException refused) {
            BunnyLog.warning("[Freebies] Consent DM not delivered | " + FailureDiagnostics.describe(refused));
            return false;
        }
    }

    public static String reply(FreebieDmService.Subscribed result, java.util.Set<FreebieSection> sections) {
        return switch (result) {
            case SUBSCRIBED -> "**Free-game DMs are on** for **" + FreebieDm.labels(sections) + "**. Check your DMs for the confirmation.";
            case EDITED -> "**Saved.** Your free-game DMs now cover **" + FreebieDm.labels(sections) + "**.";
            case FULL -> "Free-game DMs are full right now. Try again later, or follow a server's free-games channel instead.";
            case DISABLED -> "Personal DM alerts are turned off right now.";
            case BUSY -> "Your subscription is still being set up. Try again in a minute.";
            case DM_REFUSED -> "**I couldn't DM you, so nothing was saved.** Allow **direct messages from server members** "
                    + org.bunnys.handler.commands.CommandLinks.render("(Privacy Settings for a server we share), then run `/free-games dms` again.");
        };
    }
}
