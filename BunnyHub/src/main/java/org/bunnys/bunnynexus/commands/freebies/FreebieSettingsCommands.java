package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieRepository;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.utils.AppDesign;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** /freebie setup | remove | status | test - per-server alert settings (Manage Server required). */
public final class FreebieSettingsCommands {
    private FreebieSettingsCommands() {}

    static OptionData channelOption(String description, boolean required) {
        return new OptionData(OptionType.CHANNEL, "channel", description, required).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS);
    }
    static OptionData storeOption(String description, boolean required) {
        var option = new OptionData(OptionType.STRING, "launcher", description, required);
        for (FreebieStore store : FreebieStore.values()) option.addChoice(store.label(), store.id());
        return option;
    }

    /** Shared guard: runs on the command worker after the handler deferred an ephemeral reply. */
    abstract static class Base extends BunnySubcommand {
        Base(String name, String description) { setName(name); setDescription(description); }

        @Override public final void execute(BunnyHub client, CommandContext ctx) {
            if (!(ctx instanceof SlashContext slash) || !ctx.isFromGuild() || ctx.getMember() == null) {
                ctx.reply(error("Use this inside a server.").embed(), true); return;
            }
            if (!ctx.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                ctx.reply(error("You need **Manage Server** to change free-game alerts.").embed(), true); return;
            }
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { ctx.reply(error("Free-game alerts are not available right now.").embed(), true); return; }
            var reply = run(slash.event(), ctx.getGuild(), system.repository());
            ctx.reply(reply.embed(), reply.rows(), true);
        }
        abstract Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieRepository repository);
    }

    public static final class Setup extends Base {
        public Setup() {
            super("setup", "Post free games from a launcher in a channel, optionally pinging a role.");
            addOption(channelOption("Where alerts should be posted", true));
            addOption(storeOption("Which launcher's free games to post", true));
            addOption(new OptionData(OptionType.ROLE, "role", "Role to ping (leave empty for no ping)", false));
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieRepository repository) {
            var channel = channel(event, guild);
            if (channel == null) return error("Pick a text or announcement channel in this server.");
            var store = FreebieStore.byId(event.getOption("launcher").getAsString()).orElse(null);
            if (store == null) return error("Unknown launcher.");
            var self = guild.getSelfMember();
            if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
                return error("I need **View Channel**, **Send Messages** and **Embed Links** in " + channel.getAsMention() + ".");
            Optional<Role> role = Optional.ofNullable(event.getOption("role")).map(o -> o.getAsRole());
            if (role.isPresent()) {
                Role r = role.get();
                if (r.isPublicRole() || !r.getGuild().getId().equals(guild.getId()))
                    return error("Pick a specific role; @everyone pings are not allowed.");
                if (!r.isMentionable() && !self.hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE))
                    return error(r.getAsMention() + " can't be pinged by me. Make it mentionable in Server Settings → Roles.");
            }
            var result = repository.saveSubscription(new FreebieRepository.Subscription(guild.getId(), channel.getId(), store,
                    role.map(Role::getId)), event.getUser().getId(), Instant.now());
            if (result == FreebieRepository.SaveResult.LIMIT_REACHED)
                return error("This server already has " + FreebieRepository.MAX_SUBSCRIPTIONS_PER_GUILD + " alert settings. Remove one first.");
            String warning = self.hasPermission(channel, Permission.MESSAGE_HISTORY) ? ""
                    : "\n\n⚠️ Please also give me **Read Message History** there; it lets me safely retry after network errors without double-posting.";
            var saved = success((result == FreebieRepository.SaveResult.CREATED ? "Saved! " : "Updated! ") + channel.getAsMention()
                    + " will get free games from **" + store.label() + "**"
                    + role.map(r -> ", pinging " + r.getAsMention()).orElse(" (no ping)") + "."
                    + "\nNew giveaways are posted after the bot owner reviews them." + warning);
            // Late subscribers can opt in to games that were already approved and are still free.
            int live = FreebieSystem.current().map(s -> s.liveFor(store).size()).orElse(0);
            return live == 0 ? saved : new Reply(saved.embed(), FreebieMessages.catchUpControls(guild.getId(), channel.getId(), store, live));
        }
    }

    public static final class Remove extends Base {
        public Remove() {
            super("remove", "Stop free-game alerts (one channel/launcher, or everything if no channel is given).");
            addOption(channelOption("Channel to stop alerts in (leave empty to remove ALL of this server's alerts)", false));
            addOption(storeOption("Only stop this launcher (leave empty for every launcher)", false));
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieRepository repository) {
            var store = Optional.ofNullable(event.getOption("launcher")).flatMap(o -> FreebieStore.byId(o.getAsString()));
            var channelOption = event.getOption("channel");
            // No channel = everything, which also covers channels that were deleted and can't be picked any more.
            List<String> channels = channelOption != null ? List.of(channelOption.getAsString())
                    : repository.subscriptions(guild.getId()).stream().map(FreebieRepository.Subscription::channelId).distinct().toList();
            long removed = 0;
            for (String channelId : channels) removed += repository.removeSubscriptions(guild.getId(), channelId, store);
            if (removed == 0) return error("No matching free-game alerts were set up.");
            return success("Removed " + removed + " alert setting(s). Anything already being sent may still arrive.");
        }
    }

    public static final class Status extends Base {
        public Status() { super("status", "Show where this server receives free-game alerts."); }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieRepository repository) {
            List<FreebieRepository.Subscription> subscriptions = repository.subscriptions(guild.getId());
            if (subscriptions.isEmpty()) return info("No free-game alerts set up yet. Use `/freebie setup`.");
            var lines = new StringBuilder();
            for (var s : subscriptions) {
                var channel = guild.getChannelById(StandardGuildMessageChannel.class, s.channelId());
                boolean ok = channel != null && guild.getSelfMember().hasPermission(channel,
                        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
                lines.append(ok ? "✅ " : "⚠️ ").append("<#").append(s.channelId()).append("> — **").append(s.store().label()).append("**")
                        .append(s.roleId().map(r -> " • pings <@&" + r + ">").orElse(""))
                        .append(ok ? "" : channel == null ? " • channel missing" : " • I can't post here").append('\n');
            }
            return info(lines.toString());
        }
    }

    public static final class Test extends Base {
        public Test() {
            super("test", "Send a test message to check the bot can post in a channel.");
            addOption(channelOption("Channel to test", true));
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieRepository repository) {
            var channel = channel(event, guild);
            if (channel == null) return error("Pick a text or announcement channel in this server.");
            try {
                channel.sendMessageEmbeds(new EmbedBuilder().setColor(AppDesign.ColorCodes.SUCCESS_GREEN)
                        .setDescription("✅ Test: free-game alerts can be posted in this channel. (Requested by "
                                + event.getUser().getAsMention() + ")").build()).setAllowedMentions(List.of()).complete();
                return success("Test message sent to " + channel.getAsMention() + ".");
            } catch (RuntimeException failed) {
                return error("I couldn't post in " + channel.getAsMention() + ". Check that I have **View Channel**, **Send Messages** and **Embed Links** there.");
            }
        }
    }

    private static StandardGuildMessageChannel channel(SlashCommandInteractionEvent event, Guild guild) {
        var option = event.getOption("channel");
        if (option == null) return null;
        return guild.getChannelById(StandardGuildMessageChannel.class, option.getAsString());
    }
    /** Ephemeral answer, optionally with follow-up controls. */
    record Reply(MessageEmbed embed, List<ActionRow> rows) {}
    static Reply success(String text) { return new Reply(embed(AppDesign.ColorCodes.SUCCESS_GREEN, text), List.of()); }
    static Reply info(String text) { return new Reply(embed(AppDesign.ColorCodes.DEFAULT, text), List.of()); }
    static Reply error(String text) { return new Reply(embed(AppDesign.ColorCodes.PASTEL_RED, AppDesign.Emojis.ERROR + " " + text), List.of()); }
    private static MessageEmbed embed(java.awt.Color color, String text) {
        return new EmbedBuilder().setColor(color).setTitle("Free-game alerts").setDescription(text).build();
    }
}
