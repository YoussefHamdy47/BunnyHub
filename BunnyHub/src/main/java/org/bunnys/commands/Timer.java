package org.bunnys.commands;

import org.bunnys.bunnynexus.commands.timer.*;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;

/** Registration only; each subcommand lives in {@code bunnynexus/commands/timer}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Timer extends BunnyCommand {
    public Timer(BunnyHub client) {
        super(client);
        setName("timer");
        setCategory("Study");
        setDescription("Track study sessions, courses, and academic progress.");
        // Several subcommands open modals or autocomplete, which a mention cannot do.
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        setCooldown(3);
        addSubcommand(new UpdateSubject());
        addSubcommand(new Stats());
        addSubcommand(new Register());
        addSubcommand(new Gpa());
        addSubcommand(new AddSubject());
        addSubcommand(new RemoveSubject());
        addSubcommand(new Start());
        addSubcommand(new SwitchSubject());
        // Recovery when the session menu is unavailable.
        addSubcommand(new EndSession());
        addSubcommand(new EndSemester());
    }

    /** A GPA record is personal, so it is private unless the user asks otherwise. */
    @Override public boolean defaultEphemeral(CommandContext context) {
        return context instanceof SlashContext slash && "gpa".equals(slash.event().getSubcommandName());
    }
}
