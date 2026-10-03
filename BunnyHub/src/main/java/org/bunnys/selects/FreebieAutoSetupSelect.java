package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import java.util.List;

/** Section picker on the auto-setup view; redraws it so the run buttons carry the chosen sections. */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieAutoSetupSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieDashboard.AUTO_SELECT_PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var chosen = FreebieSection.decode(String.join("", event.getValues()));
        if (args.length != 2 || chosen.isEmpty() || !FreebieDashboard.canManage(event.getGuild(), event.getMember(), args[1])) {
            event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        event.editMessage(FreebieDashboard.asEdit(FreebieDashboard.autoSetup(args[1], chosen))).queue();
    }
}
