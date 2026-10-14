package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;

import java.util.List;

/**
 * Launcher pings picker: {@code freebie_ping:<guild>}, value {@code <launcher>:<current role or ->}. Opens the form
 * for that launcher's ping; nothing is written here.
 */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebiePingSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieDashboard.PING_SELECT_PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        if (args.length != 2 || event.getValues().isEmpty() || !FreebieDashboard.canManage(event.getGuild(), event.getMember(), args[1])) {
            event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        FreebieDashboard.pingForm(args[1], event.getValues().getFirst()).ifPresentOrElse(
                form -> event.replyModal(form).queue(),
                () -> event.reply("This menu is out of date. Run `/free-games setup` again.").setEphemeral(true).queue());
    }
}
