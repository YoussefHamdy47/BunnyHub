package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.utils.Embeds;

import java.util.EnumSet;
import java.util.Optional;

/**
 * {@code /freebie-admin connect}: sets up every section in one channel of any server the bot is in, for servers
 * whose admins can't run {@code /free-games setup} themselves. Same checks and save path as the dashboard.
 */
public final class FreebieConnectCommand extends BunnySubcommand {
    public FreebieConnectCommand() {
        setName("connect");
        setDescription("Send every section to one channel of any server the bot is in");
        addOption(new OptionData(OptionType.STRING, "server", "Server ID", true).setMaxLength(20));
        addOption(new OptionData(OptionType.STRING, "channel", "Channel ID in that server", true).setMaxLength(20));
        addOption(new OptionData(OptionType.STRING, "role", "Role ID or exact name to ping; leave empty to post without a ping", false).setMaxLength(100));
        addOption(new OptionData(OptionType.BOOLEAN, "post-current", "Also post the games that are free right now", false));
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || !system.config().isOwner(ctx.getUser().getId())) {
            reply(ctx, "Only active freebie owners can use this."); return;
        }
        if (!(ctx instanceof SlashContext slash)) return;
        ctx.defer(true);
        String serverId = slash.event().getOption("server").getAsString().trim();
        String channelId = slash.event().getOption("channel").getAsString().trim();
        String roleQuery = slash.event().getOption("role") == null ? "" : slash.event().getOption("role").getAsString().trim();
        boolean postCurrent = slash.event().getOption("post-current") != null && slash.event().getOption("post-current").getAsBoolean();
        if (!serverId.matches("[0-9]{17,20}") || !channelId.matches("[0-9]{17,20}")) {
            reply(ctx, "Use numeric server and channel IDs."); return;
        }
        Guild guild = ctx.getJDA().getGuildById(serverId);
        if (guild == null) { reply(ctx, "I'm not in a server with ID `" + serverId + "`."); return; }
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);
        if (channel == null) { reply(ctx, "**" + guild.getName() + "** has no text or announcement channel with ID `" + channelId + "`."); return; }
        Optional<Role> role = roleQuery.isEmpty() ? Optional.empty() : findRole(guild, roleQuery);
        if (!roleQuery.isEmpty() && role.isEmpty()) { reply(ctx, "**" + guild.getName() + "** has no role matching `" + roleQuery + "`."); return; }

        var text = new StringBuilder("**" + guild.getName() + "** → #" + channel.getName() + "\n");
        for (FreebieSection section : FreebieSection.values()) {
            var saved = FreebieServerSetup.saveSection(guild, section, channel, role, EnumSet.copyOf(section.stores()),
                    null, ctx.getUser().getId(), system.subscriptions());
            text.append(saved.notice()).append('\n');
        }
        if (postCurrent) {
            var caughtUp = system.catchUpChannel(guild.getId(), channel.getId(), (FreebieStore store) -> true);
            text.append("Posting ").append(caughtUp.queued()).append(" game(s) that are free right now.");
        }
        reply(ctx, text.toString().strip());
    }

    /** A role ID, or a case-insensitive exact name; never @everyone. */
    static Optional<Role> findRole(Guild guild, String query) {
        if (query.matches("[0-9]{17,20}")) {
            Role byId = guild.getRoleById(query);
            return byId == null || byId.isPublicRole() ? Optional.empty() : Optional.of(byId);
        }
        return guild.getRolesByName(query, true).stream().filter(r -> !r.isPublicRole()).findFirst();
    }

    private static void reply(CommandContext ctx, String text) {
        ctx.reply(Embeds.of("🎁", "Connect a server", text).build(), true);
    }
}
