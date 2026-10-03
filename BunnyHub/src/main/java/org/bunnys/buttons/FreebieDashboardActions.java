package org.bunnys.buttons;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieServerSetup;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import java.util.List;

/**
 * Dashboard writes: auto-setup (one shared channel or a channel per section) and posting the role picker in the
 * current channel. Each creates things in Discord, so the clicker is rate limited and the click is acknowledged
 * before any work; the dashboard is then redrawn with the outcome.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieDashboardActions extends BunnyButton {
    @Override public String getPrefix() { return FreebieDashboard.RUN_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        if (system == null || args.length < 3 || !FreebieDashboard.canManage(guild, event.getMember(), args[2])) {
            event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        event.deferEdit().complete();
        var subscriptions = system.subscriptions();
        String notice = switch (args[1]) {
            case FreebieDashboard.ONE_CHANNEL, FreebieDashboard.CHANNEL_PER_SECTION -> args.length != 4 ? "⚠️ This control is malformed."
                    : FreebieServerSetup.autoSetup(guild, event.getMember(), FreebieSection.decode(args[3]),
                            FreebieDashboard.CHANNEL_PER_SECTION.equals(args[1]), subscriptions);
            case FreebieDashboard.ROLE_PANEL -> {
                var actor = event.getMember();
                // The picker hands roles out on the admin's behalf, so they must be able to hand them out themselves.
                if (!actor.hasPermission(Permission.MANAGE_ROLES))
                    yield "⚠️ Posting a role picker needs **Manage Roles**.";
                var roles = FreebieServerSetup.pickableRoles(guild, subscriptions.forGuild(guild.getId()));
                roles.values().removeIf(role -> !actor.canInteract(role));
                var channel = event.getGuildChannel();
                if (roles.isEmpty())
                    yield "⚠️ No section pings a role members can safely pick. A picker role must grant nothing beyond "
                            + "@everyone, have no channel overrides, and sit below both my role and yours (auto-setup creates such roles).";
                if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
                    yield "⚠️ I can't post in " + channel.getAsMention() + ". Open the dashboard in the channel where the picker should go.";
                channel.sendMessage(FreebieServerSetup.rolePanel(roles)).complete();
                yield "✅ Role picker posted in " + channel.getAsMention() + ".";
            }
            default -> "⚠️ Unknown action.";
        };
        var saved = subscriptions.forGuild(guild.getId());
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(guild, saved, notice))).queue();
    }
}
