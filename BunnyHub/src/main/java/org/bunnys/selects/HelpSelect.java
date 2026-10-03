package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.bunnys.bunnynexus.commands.help.HelpCommand;
import org.bunnys.bunnynexus.help.HelpMenu;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;

/** Help menus: {@code help:cat:<owner>} and {@code help:cmd:<owner>}; the chosen value is the view. */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class HelpSelect extends BunnySelect {
    @Override public String getPrefix() { return HelpMenu.PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        String view = event.getValues().isEmpty() ? HelpMenu.OVERVIEW : event.getValues().getFirst();
        show(client, event, args.length >= 3 ? args[2] : "", view);
    }

    /**
     * Moves the owner's menu to {@code view}. Anyone else gets a private copy of that view that
     * they can drive themselves, so a public help message never gets hijacked or dead-ends.
     */
    public static void show(BunnyHub client, GenericComponentInteractionCreateEvent event, String owner, String view) {
        var registry = client.getCommandRegistry();
        var rendered = HelpMenu.render(HelpCommand.bot(event.getJDA(), registry), registry.getCommands().values(),
                HelpCommand.viewer(registry, event.getUser()), view);

        if (event.getUser().getId().equals(owner)) {
            event.editMessage(new MessageEditBuilder().setEmbeds(rendered.embed())
                    .setComponents(rendered.rows()).build()).queue(null, ignored -> {});
        } else {
            event.reply(new MessageCreateBuilder().setEmbeds(rendered.embed())
                    .setComponents(rendered.rows()).build()).setEphemeral(true).queue(null, ignored -> {});
        }
    }
}
