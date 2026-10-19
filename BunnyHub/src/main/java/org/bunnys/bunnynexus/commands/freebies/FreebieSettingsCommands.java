package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

import java.util.List;
import java.util.Optional;

/**
 * {@code /freebie}: opens the dashboard, which is the one place to set up, change and turn off free-game alerts.
 * Also the destination check shared by every way of saving a section.
 */
public final class FreebieSettingsCommands {
    private FreebieSettingsCommands() {}

    /** Runs on the command worker after the handler deferred an ephemeral reply. */
    public static void openDashboard(CommandContext ctx) {
        if (!ctx.isFromGuild() || ctx.getMember() == null) {
            ctx.reply(Embeds.error("Free-game alerts", "Use this inside a server."), true); return;
        }
        // Run from someone's account install in a server the bot hasn't joined: nothing to set up there yet.
        if (ctx.getGuild().isDetached()) {
            ctx.reply(Embeds.error("Free-game alerts", "Add me to this server first, then run this again."), true); return;
        }
        if (!ctx.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            ctx.reply(Embeds.error("Free-game alerts", "You need **Manage Server** to change free-game alerts."), true); return;
        }
        var system = FreebieSystem.current().orElse(null);
        if (system == null) { ctx.reply(Embeds.error("Free-game alerts", "Free-game alerts are not available right now."), true); return; }
        var reply = FreebieDashboard.dashboard(ctx.getGuild(), system.subscriptions().forGuild(ctx.getGuild().getId()), null);
        ctx.reply(reply.embed(), reply.rows(), true);
    }

    /**
     * Checks a destination before saving. Re-run on every save because permissions and roles can change between
     * opening the dashboard and submitting a form.
     */
    public static Optional<String> validate(Guild guild, StandardGuildMessageChannel channel, Optional<Role> role) {
        if (channel == null) return Optional.of("Pick a text or announcement channel in this server.");
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
            return Optional.of("I need **View Channel**, **Send Messages** and **Embed Links** in " + channel.getAsMention() + ".");
        if (role.isPresent()) {
            Role r = role.get();
            if (r.isPublicRole() || !r.getGuild().getId().equals(guild.getId()))
                return Optional.of("Pick a specific role; @everyone pings are not allowed.");
            if (!r.isMentionable() && !self.hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE))
                return Optional.of(r.getAsMention() + " can't be pinged by me. Make it mentionable in Server Settings → Roles.");
        }
        return Optional.empty();
    }

    /** Ephemeral answer, optionally with follow-up controls. */
    public record Reply(MessageEmbed embed, List<ActionRow> rows) {}
}
