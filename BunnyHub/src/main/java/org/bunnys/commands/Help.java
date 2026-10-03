package org.bunnys.commands;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.commands.help.HelpCommand;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/** Registration only; see {@link HelpCommand} and {@link org.bunnys.bunnynexus.help.HelpMenu}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Help extends BunnyCommand {
    private final HelpCommand implementation = new HelpCommand();

    public Help(BunnyHub client) {
        super(client);
        setName("help");
        setDescription("Browse every command, with usage, options and examples.");
        setCategory("Information");
        addAliases("commands", "h");
        setDeferBeforeDispatch(true);
        setCooldown(3);
        setExample("/help command:timer start");
        addOption(new OptionData(OptionType.STRING, "command", "Jump straight to one command, e.g. timer start", false)
                .setAutoComplete(true));
    }

    /** Private on the slash path so browsing never clutters a channel; a mention is always public. */
    @Override public boolean defaultEphemeral(CommandContext context) { return true; }

    @Override public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        return implementation.autocomplete(client, event.getUser());
    }

    @Override public void execute(BunnyHub client, CommandContext context) {
        implementation.execute(client, context);
    }
}
