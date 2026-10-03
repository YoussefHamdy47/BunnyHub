package org.bunnys.buttons;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieServerSetup;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import java.util.List;

/**
 * The public free-game role picker: {@code freebie_role:<role>} toggles that role on the member who pressed it.
 * Only a role this server saved as a free-game ping role, and one that grants no permissions, can be toggled.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieRoleButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieServerSetup.ROLE_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        var member = event.getMember();
        if (system == null || guild == null || member == null || args.length != 2) {
            event.reply("Free-game pings are not available right now.").setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        event.deferReply(true).complete();
        var role = guild.getRoleById(args[1]);
        var self = guild.getSelfMember();
        if (role == null || !FreebieServerSetup.isPingRole(role.getId(), system.subscriptions().forGuild(guild.getId()))
                || !FreebieServerSetup.selfAssignable(role, self)) {
            followUp(event, "This ping role is no longer offered here. Ask an admin to post a new role picker.");
            return;
        }
        if (!self.hasPermission(Permission.MANAGE_ROLES)) {
            followUp(event, "I need **Manage Roles** to hand out ping roles. Please tell a server admin.");
            return;
        }
        boolean has = member.getRoles().contains(role);
        (has ? guild.removeRoleFromMember(member, role) : guild.addRoleToMember(member, role)).complete();
        followUp(event, has ? "Removed " + role.getAsMention() + ". You won't be pinged for these free games any more."
                : "Added " + role.getAsMention() + ". You'll be pinged when these free games are posted.");
    }

    private static void followUp(ButtonInteractionEvent event, String text) {
        event.getHook().sendMessage(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
