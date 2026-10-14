package org.bunnys.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.bunnynexus.moderation.Moderation.Action;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /moderation ban | unban | kick | mute | unmute}. Registration only; see {@link Moderation}.
 *
 * <p>Each action needs its own permission (Ban, Kick or Timeout Members), which {@link Moderation#problem} checks.
 * Discord can only hide a whole command, so the command is shown to members with Timeout Members; server admins
 * can change who sees it in Server Settings → Integrations.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class ModerationCommand extends BunnyCommand {
    public ModerationCommand(BunnyHub client) {
        super(client);
        setName("moderation");
        addAliases("mod");
        setCategory("Moderation");
        setDescription("Ban, kick, mute and unmute members.");
        setDmEnabled(false);
        setMentionEnabled(false);
        setCooldown(2);
        addSubcommand(new Branch(Action.BAN, "Ban someone from the server. Works even if they already left.",
                "/moderation ban user:@spammer delete_messages:Last 24 hours reason:Spam"));
        addSubcommand(new Branch(Action.UNBAN, "Let a banned user join the server again.",
                "/moderation unban user:123456789012345678"));
        addSubcommand(new Branch(Action.KICK, "Remove someone from the server. They can rejoin with an invite.",
                "/moderation kick user:@member reason:Breaking rule 3"));
        addSubcommand(new Branch(Action.MUTE, "Mute someone with a Discord timeout: no chatting, reacting or voice.",
                "/moderation mute user:@member duration:1 hour reason:Spamming"));
        addSubcommand(new Branch(Action.UNMUTE, "End someone's mute early.", "/moderation unmute user:@member"));
    }

    @Override public SlashCommandData buildCommandData() {
        return super.buildCommandData().setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS));
    }

    static final class Branch extends BunnySubcommand {
        private final Action action;

        Branch(Action action, String description, String example) {
            this.action = action;
            setName(action.verb());
            setDescription(description);
            setExample(example);
            setMentionEnabled(false);
            addOption(Moderation.userOption(action == Action.UNBAN
                    ? "Who to unban (paste their user ID if they are not listed)" : "Who to " + action.verb()));
            if (action == Action.MUTE) addOption(Moderation.durationOption());
            addOption(Moderation.reasonOption());
            if (action == Action.BAN) addOption(Moderation.deleteMessagesOption());
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(action, ctx); }
    }
}
