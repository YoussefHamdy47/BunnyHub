package org.bunnys.selects;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieDmService;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.util.List;

/**
 * The section picker from {@code /free-games dms}: {@code freebie_dmsub:<userId>}. Only that user may use it.
 * A new subscription is saved only once the consent DM was accepted by Discord.
 */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieDmSubscribeSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieDm.PICKER_PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var sections = FreebieSection.decode(String.join("", event.getValues()));
        if (system == null || args.length != 2 || !args[1].equals(event.getUser().getId()) || sections.isEmpty()) {
            event.reply("Use `/free-games dms` to choose your own free-game DMs.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        // The consent DM and the database work can take longer than Discord's three seconds.
        event.deferEdit().complete();
        var result = system.dms().subscribe(event.getUser().getId(), sections, data -> consent(event.getUser(), data));
        event.getHook().editOriginal(new MessageEditBuilder().setContent(reply(result, sections)).setEmbeds().setComponents()
                .setAllowedMentions(List.of()).build()).queue();
    }

    /** True only when Discord accepted the DM; a refusal (or an unknown outcome) means "not saved". */
    static boolean consent(User user, MessageCreateData data) {
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
            case SUBSCRIBED -> "You're subscribed to free-game DMs for **" + FreebieDm.labels(sections) + "**. I sent you a confirmation DM.";
            case EDITED -> "Your free-game DMs now cover **" + FreebieDm.labels(sections) + "**.";
            case FULL -> "Free-game DMs are full right now. Try again later, or follow a server's free-games channel instead.";
            case DISABLED -> "Personal DM alerts are turned off right now.";
            case BUSY -> "Your subscription is still being set up. Try again in a minute.";
            case DM_REFUSED -> "I couldn't DM you, so nothing was saved. Allow **direct messages from server members** "
                    + "(Privacy Settings for a server we share) and try again.";
        };
    }
}
