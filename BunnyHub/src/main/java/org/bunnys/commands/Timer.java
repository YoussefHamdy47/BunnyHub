package org.bunnys.commands;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;


@SuppressWarnings("unused")
public class Timer extends BunnyCommand {
    @Override public boolean defaultEphemeral(org.bunnys.handler.commands.context.CommandContext context) {
        return context instanceof org.bunnys.handler.commands.context.SlashContext slash
                && "gpa".equals(slash.event().getSubcommandName());
    }

    public Timer(BunnyHub client) {
        super(client);
        setName("timer");
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        setDescription("Track study sessions, courses, and academic progress.");
        setDmEnabled(true);
        setNsfw(false);
        setTestOnly(false);
        setCooldown(3);
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.UpdateSubject());

        // View Stats Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.Stats());

        // Register Semester Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.Register());

        // View GPA Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.Gpa());

        // Add Subject Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.AddSubject());

        // Remove Subject Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.RemoveSubject());
        // Start Study Session Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.Start());

        // Switch Subject Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.SwitchSubject());

        // End Study Session Subcommand (recovery when the session menu is unavailable)
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.EndSession());

        // End Semester Subcommand
        addSubcommand(new org.bunnys.bunnynexus.commands.timer.EndSemester());
    }

}
