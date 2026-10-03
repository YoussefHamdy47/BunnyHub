package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.SaveResult;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.util.*;

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
            var reply = run(slash.event(), ctx.getGuild(), system.subscriptions());
            ctx.reply(reply.embed(), reply.rows(), true);
        }
        abstract Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions);
    }

    /** Defaults ticked for a channel's first setup: the PC storefronts most people want. */
    static final Set<FreebieStore> DEFAULT_LAUNCHERS = EnumSet.of(FreebieStore.EPIC, FreebieStore.STEAM, FreebieStore.GOG);

    public static final class Setup extends Base {
        public Setup() {
            super("setup", "Post free games in a channel. Leave launcher empty to pick several at once.");
            addOption(channelOption("Where alerts should be posted", true));
            addOption(storeOption("Add just this launcher (leave empty to choose from a list)", false));
            addOption(new OptionData(OptionType.ROLE, "role", "Role to ping (leave empty for no ping)", false));
            setExample("/freebie setup channel:#free-games role:@Gamers");
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions) {
            var channel = channel(event, guild);
            Optional<Role> role = Optional.ofNullable(event.getOption("role")).map(OptionMapping::getAsRole);
            var problem = validate(guild, channel, role);
            if (problem.isPresent()) return error(problem.get());

            var launcher = event.getOption("launcher");
            if (launcher == null) {
                // Show the channel's current launchers ticked, so the menu edits rather than surprises.
                Set<FreebieStore> current = EnumSet.noneOf(FreebieStore.class);
                for (var s : subscriptions.forGuild(guild.getId())) if (s.channelId().equals(channel.getId())) current.add(s.store());
                return new Reply(embed("Which launchers should " + channel.getAsMention()
                        + " get free games from" + role.map(r -> ", pinging " + r.getAsMention()).orElse("") + "?\n"
                        + "Pick any number below. Unticking one removes it from this channel."),
                        FreebieMessages.launcherPicker(guild.getId(), channel.getId(), role.map(Role::getId),
                                current.isEmpty() ? DEFAULT_LAUNCHERS : current));
            }

            var store = FreebieStore.byId(launcher.getAsString()).orElse(null);
            if (store == null) return error("Unknown launcher.");
            var result = subscriptions.save(new Subscription(guild.getId(), channel.getId(), store,
                    role.map(Role::getId)), event.getUser().getId(), Instant.now());
            if (result == SaveResult.LIMIT_REACHED) return channelLimit();
            var saved = success((result == SaveResult.CREATED ? "Saved! " : "Updated! ") + channel.getAsMention()
                    + " will get free games from **" + store.label() + "**"
                    + role.map(r -> ", pinging " + r.getAsMention()).orElse(" (no ping)") + "."
                    + "\nNew giveaways are posted after the bot owner reviews them." + historyWarning(guild, channel));
            // Late subscribers can opt in to games that were already approved and are still free.
            int live = FreebieSystem.current().map(s -> s.liveFor(store).size()).orElse(0);
            return live == 0 ? saved : new Reply(saved.embed(), FreebieMessages.catchUpControls(guild.getId(), channel.getId(), store, live));
        }
    }

    /**
     * Checks a destination before saving. Shared by the command and the launcher menu, which re-runs it because
     * permissions and roles can change between the command and the click.
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

    /** Applies a launcher-menu choice: the channel ends up with exactly {@code stores}. */
    public static Reply saveLaunchers(Guild guild, StandardGuildMessageChannel channel, Set<FreebieStore> stores,
                                      Optional<Role> role, String actorId, FreebieSubscriptions subscriptions) {
        var problem = validate(guild, channel, role);
        if (problem.isPresent()) return error(problem.get());
        if (stores.isEmpty()) return error("Pick at least one launcher, or use `/freebie remove` to stop alerts here.");
        var change = subscriptions.setChannelStores(guild.getId(), channel.getId(), stores, role.map(Role::getId), actorId, Instant.now());
        if (change.result() == SaveResult.LIMIT_REACHED) return channelLimit();

        StringBuilder text = new StringBuilder(change.result() == SaveResult.CREATED ? "Saved! " : "Updated! ")
                .append(channel.getAsMention()).append(" will get free games from **").append(labels(stores)).append("**")
                .append(role.map(r -> ", pinging " + r.getAsMention()).orElse(" (no ping)")).append(".");
        if (!change.removed().isEmpty()) text.append("\nRemoved: ").append(labels(change.removed())).append(".");
        text.append("\nNew giveaways are posted after the bot owner reviews them.").append(historyWarning(guild, channel));
        var saved = success(text.toString());

        int live = FreebieSystem.current().map(s -> stores.stream().mapToInt(store -> s.liveFor(store).size()).sum()).orElse(0);
        return live == 0 ? saved : new Reply(saved.embed(), FreebieMessages.catchUpAllControls(guild.getId(), channel.getId(), live));
    }

    private static String labels(Set<FreebieStore> stores) {
        return String.join(", ", stores.stream().sorted().map(FreebieStore::label).toList());
    }

    private static Reply channelLimit() {
        return error("This server already uses " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                + " alert channels. Add launchers to one of those, or remove a channel first.");
    }

    private static String historyWarning(Guild guild, StandardGuildMessageChannel channel) {
        return guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY) ? ""
                : "\n\n⚠️ Please also give me **Read Message History** there; it lets me safely retry after network errors without double-posting.";
    }

    /** Opens the server dashboard: every section at a glance, with buttons to edit, auto-setup or post a role picker. */
    public static final class Dashboard extends Base {
        public Dashboard() {
            super("dashboard", "Set up free-game alerts by section (major PC, indie, console, mobile), with auto-setup.");
            setExample("/freebie dashboard");
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions) {
            return FreebieDashboard.dashboard(guild, subscriptions.forGuild(guild.getId()), null);
        }
    }

    public static final class Remove extends Base {
        public Remove() {
            super("remove", "Stop free-game alerts (one channel/launcher, or everything if no channel is given).");
            addOption(channelOption("Channel to stop alerts in (leave empty to remove ALL of this server's alerts)", false));
            addOption(storeOption("Only stop this launcher (leave empty for every launcher)", false));
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions) {
            var launcher = event.getOption("launcher");
            var store = launcher == null ? Optional.<FreebieStore>empty() : FreebieStore.byId(launcher.getAsString());
            // An unrecognised launcher must never widen into "remove every launcher".
            if (launcher != null && store.isEmpty()) return error("Unknown launcher.");
            var channelOption = event.getOption("channel");
            // No channel = everything, which also covers channels that were deleted and can't be picked any more.
            List<String> channels = channelOption != null ? List.of(channelOption.getAsString())
                    : subscriptions.forGuild(guild.getId()).stream().map(Subscription::channelId).distinct().toList();
            long removed = 0;
            for (String channelId : channels) removed += subscriptions.remove(guild.getId(), channelId, store);
            if (removed == 0) return error("No matching free-game alerts were set up.");
            return success("Removed " + removed + " alert setting(s). Anything already being sent may still arrive.");
        }
    }

    public static final class Status extends Base {
        public Status() { super("status", "Show where this server receives free-game alerts."); }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions) {
            List<Subscription> saved = subscriptions.forGuild(guild.getId());
            if (saved.isEmpty()) return info("No free-game alerts set up yet. Use `/freebie dashboard` (it can set everything up for you).");
            // One line per channel: its launchers, and the role(s) they ping.
            Map<String, List<Subscription>> byChannel = new LinkedHashMap<>();
            for (var s : saved) byChannel.computeIfAbsent(s.channelId(), k -> new ArrayList<>()).add(s);
            var lines = new StringBuilder();
            for (var entry : byChannel.entrySet()) {
                var channel = guild.getChannelById(StandardGuildMessageChannel.class, entry.getKey());
                boolean ok = channel != null && guild.getSelfMember().hasPermission(channel,
                        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
                var stores = entry.getValue().stream().map(Subscription::store).sorted().map(FreebieStore::label).toList();
                var roles = new TreeSet<String>();
                entry.getValue().forEach(s -> roles.add(s.roleId().map(r -> "<@&" + r + ">").orElse("no ping")));
                lines.append(ok ? "✅ " : "⚠️ ").append("<#").append(entry.getKey()).append(">")
                        .append(ok ? "" : channel == null ? " • **channel missing**" : " • **I can't post here**")
                        .append("\n**").append(String.join(", ", stores)).append("** • ").append(String.join(", ", roles)).append("\n\n");
            }
            lines.append(byChannel.size()).append("/").append(FreebieSubscriptions.MAX_CHANNELS_PER_GUILD)
                    .append(" channels used. Manage everything from `/freebie dashboard`.");
            return info(lines.toString());
        }
    }

    public static final class Test extends Base {
        public Test() {
            super("test", "Send a test message to check the bot can post in a channel.");
            addOption(channelOption("Channel to test", true));
        }
        @Override Reply run(SlashCommandInteractionEvent event, Guild guild, FreebieSubscriptions subscriptions) {
            var channel = channel(event, guild);
            if (channel == null) return error("Pick a text or announcement channel in this server.");
            try {
                channel.sendMessageEmbeds(Embeds.of(AppDesign.Emojis.VERIFY, "Free-game alerts test")
                        .setDescription("Free-game alerts can be posted in this channel. (Requested by "
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
    public record Reply(MessageEmbed embed, List<ActionRow> rows) {}
    static Reply success(String text) { return new Reply(Embeds.of(AppDesign.Emojis.VERIFY, "Free-game alerts", text).build(), List.of()); }
    static Reply info(String text) { return new Reply(embed(text), List.of()); }
    static Reply error(String text) { return new Reply(Embeds.error("Free-game alerts", text), List.of()); }
    private static MessageEmbed embed(String text) {
        return Embeds.of("🎁", "Free-game alerts", text).build();
    }
}
