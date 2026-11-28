package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.deals.DealRepository;
import org.bunnys.bunnynexus.deals.DealStore;
import org.bunnys.bunnynexus.deals.DealSystem;

import java.util.*;

/**
 * The deals board in {@code /free-games setup} (docs/deals/05): a dashboard line, a button
 * ({@code freebie_dash:deals:<guild>:<channel|->:<role|->:<launcher mask>:<hours>:<style>}), a form ({@code deals_board:<guild>})
 * with channel, ping role, launchers that ping and ping cooldown, and the auto-setup action that creates #deals and
 * @Deals. Shown only while the deals system runs.
 */
public final class DealBoardSetup {
    public static final String ACTION = "deals", AUTO_ACTION = "dealsauto", MODAL_PREFIX = "deals_board",
            CHANNEL_FIELD = "channel", ROLE_FIELD = "role", LAUNCHERS_FIELD = "launchers", EVERY_FIELD = "every", STYLE_FIELD = "style";
    static final String CHANNEL_NAME = "deals", ROLE_NAME = "Deals";

    private DealBoardSetup() {}

    /** The board's saved state, when deals are running; read by the dashboard (not on the gateway thread). */
    static Optional<Optional<DealRepository.Board>> state(Guild guild) {
        return DealSystem.current().map(system -> system.repository().board(guild.getId()));
    }

    static String describe(Guild guild, Optional<DealRepository.Board> board) {
        if (board.isEmpty()) return "Off\n-# The best deals on PC and console, posted as new ones arrive or kept as one board. Auto-setup can create it.";
        var b = board.get();
        if (!b.on()) return "Off" + b.offReason().filter(r -> !r.equals("turned off")).map(r -> ": **" + r + "**").orElse("")
                + "\n-# The best deals on PC and console, posted as new ones arrive or kept as one board.";
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, b.channelId());
        boolean ok = channel != null && guild.getSelfMember().hasPermission(channel, DealSystem.BOARD_PERMISSIONS);
        var ping = b.ping();
        String pings = ping.roleId().map(r -> "<@&" + r + "> for " + (ping.stores().isEmpty() ? "every launcher"
                : String.join(", ", ping.stores().stream().map(s -> s.label).toList())) + ", at most every " + ping.everyHours() + " hours")
                .orElse("nobody");
        return "<#" + b.channelId() + ">" + (ok ? "" : channel == null ? " • **channel was deleted**" : " • **I can't post here**")
                + "\n**Pings** · " + pings + (b.style() == DealRepository.Style.POSTS
                ? "\n-# New deals get a post of their own, at most every " + DealSystem.POST_GAP_HOURS + " hours."
                : "\n-# One board, edited quietly as prices change; reposted when new deals arrive.");
    }

    /** The current settings ride in the button id so the form opens without a database read. */
    static Button button(Guild guild, Optional<DealRepository.Board> board) {
        var on = board.filter(DealRepository.Board::on);
        String channel = on.map(DealRepository.Board::channelId).filter(c -> guild.getGuildChannelById(c) != null).orElse(FreebieDashboard.NONE);
        var ping = on.map(DealRepository.Board::ping).orElse(DealRepository.Ping.NONE);
        String role = ping.roleId().filter(r -> guild.getRoleById(r) != null).orElse(FreebieDashboard.NONE);
        return Button.of(on.isPresent() ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY, String.join(":", FreebieDashboard.NAV_PREFIX,
                ACTION, guild.getId(), channel, role, Integer.toHexString(mask(ping.stores())), String.valueOf(ping.everyHours()),
                on.map(DealRepository.Board::style).orElse(DealRepository.Style.POSTS).id), "Deals Board");
    }

    static int mask(Set<DealStore> stores) {
        int mask = 0;
        for (DealStore store : stores) mask |= 1 << store.ordinal();
        return mask;
    }

    static Set<DealStore> unmask(int mask) {
        Set<DealStore> stores = EnumSet.noneOf(DealStore.class);
        for (DealStore store : DealStore.values()) if ((mask & (1 << store.ordinal())) != 0) stores.add(store);
        return stores;
    }

    public static Modal form(String[] args) {
        var channel = EntitySelectMenu.create(CHANNEL_FIELD, SelectTarget.CHANNEL).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
                .setRequiredRange(0, 1).setRequired(false).setPlaceholder("No board");
        var role = EntitySelectMenu.create(ROLE_FIELD, SelectTarget.ROLE).setRequiredRange(0, 1).setRequired(false).setPlaceholder("No ping");
        var launchers = StringSelectMenu.create(LAUNCHERS_FIELD).setRequiredRange(0, DealStore.values().length).setRequired(false)
                .setPlaceholder("Every launcher");
        for (DealStore store : DealStore.values()) launchers.addOption(store.label, store.choice);
        var every = StringSelectMenu.create(EVERY_FIELD).setRequiredRange(1, 1);
        for (int hours : DealRepository.Ping.CHOICES) every.addOption("At most every " + hours + " hours", String.valueOf(hours));
        var style = StringSelectMenu.create(STYLE_FIELD).setRequiredRange(1, 1);
        for (var s : DealRepository.Style.values()) style.addOption(s.label, s.id);
        // New boards post; a saved board keeps its style.
        style.setDefaultValues((args.length == 8 ? DealRepository.Style.byId(args[7]) : DealRepository.Style.POSTS).id);
        int hours = DealRepository.Ping.DEFAULT_HOURS;
        if (args.length == 7 || args.length == 8) {
            if (args[3].matches("[0-9]{17,20}")) channel.setDefaultValues(DefaultValue.channel(args[3]));
            if (args[4].matches("[0-9]{17,20}")) role.setDefaultValues(DefaultValue.role(args[4]));
            try {
                var ticked = unmask(Integer.parseInt(args[5], 16));
                if (!ticked.isEmpty()) launchers.setDefaultValues(ticked.stream().map(s -> s.choice).toList());
                hours = Integer.parseInt(args[6]);
            } catch (NumberFormatException malformed) { /* defaults */ }
        }
        every.setDefaultValues(String.valueOf(DealRepository.Ping.CHOICES.contains(hours) ? hours : DealRepository.Ping.DEFAULT_HOURS));
        return Modal.create(MODAL_PREFIX + ":" + args[2], "Deals Board")
                .addComponents(Label.of("Channel", "A quiet channel works best. Leave empty to turn the board off.", channel.build()),
                        Label.of("Style", "Posts: new deals get their own message. Board: one message kept up to date.", style.build()),
                        Label.of("Role to ping for new deals", "Optional. The first post never pings.", role.build()),
                        Label.of("Launchers that ping", "Leave empty for every launcher", launchers.build()),
                        Label.of("Ping cooldown", "New deals still post; only the ping waits", every.build()))
                .build();
    }

    /**
     * Auto-setup for deals: reuses or creates #deals (read-only for members) in the free-games category and a
     * self-assignable @Deals role, and turns the board on there pinging @Deals for every launcher, at most daily.
     * Returns the notice for the dashboard.
     */
    public static String autoSetup(Guild guild, Member actor, DealSystem deals) {
        if (!actor.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ Creating the Deals Board needs **Manage Roles** and **Manage Channels**.";
        var self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ I need **Manage Roles** and **Manage Channels** in Server Settings → Roles to set this up.";
        // Next to the server's free-game alerts when it has them, otherwise in a "Free Games" category.
        var categories = guild.getCategoriesByName(FreebieServerSetup.CATEGORY_NAME, true);
        Category category = alertCategory(guild).orElseGet(() -> categories.isEmpty()
                ? guild.createCategory(FreebieServerSetup.CATEGORY_NAME).complete() : categories.getFirst());
        TextChannel channel = category.getTextChannels().stream().filter(c -> c.getName().equals(CHANNEL_NAME)).findFirst()
                .orElseGet(() -> category.createTextChannel(CHANNEL_NAME).setTopic("Big discounts on well-reviewed PC and console games, kept up to date.")
                        .addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.MESSAGE_SEND))
                        .addMemberPermissionOverride(self.getIdLong(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY), EnumSet.noneOf(Permission.class))
                        .complete());
        Role role = guild.getRolesByName(ROLE_NAME, false).stream().filter(r -> FreebieServerSetup.selfAssignable(r, self)).findFirst()
                .orElseGet(() -> guild.createRole().setName(ROLE_NAME).setMentionable(true).setPermissions(0L).complete());
        boolean pickerUpdated = save(guild, channel, Optional.of(role.getId()), Set.of(), DealRepository.Ping.DEFAULT_HOURS,
                DealRepository.Style.POSTS, actor.getId(), deals);
        return "✅ Deals Board on in " + channel.getAsMention() + ", pinging " + role.getAsMention() + " at most once a day. "
                + (pickerUpdated ? "The role picker now has a **Game deals** button so members can opt in."
                : "Use **Post Role Picker** so members can opt in.");
    }

    /** The category holding this server's free-game alert channels (the one most of them share). */
    static Optional<Category> alertCategory(Guild guild) {
        var freebies = org.bunnys.bunnynexus.freebies.FreebieSystem.current().orElse(null);
        if (freebies == null) return Optional.empty();
        Map<Category, Long> counts = new HashMap<>();
        for (var s : freebies.subscriptions().forGuild(guild.getId())) {
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, s.channelId());
            if (channel != null && channel.getParentCategory() != null) counts.merge(channel.getParentCategory(), 1L, Long::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    /**
     * Saves the board, removes the old message when it moved, posts it right away, and rebuilds the server's role
     * picker so its deals button matches the new ping role. True when there was a picker to update.
     */
    public static boolean save(Guild guild, StandardGuildMessageChannel channel, Optional<String> role, Set<DealStore> stores, int hours,
                     DealRepository.Style style, String userId, DealSystem deals) {
        var before = deals.repository().setBoard(guild.getId(), channel.getId(), new DealRepository.Ping(role, stores, hours), style, userId, deals.now());
        // An old board message goes when the board moves or becomes posts; posts are never deleted.
        before.filter(b -> b.messageId().isPresent() && b.style() == DealRepository.Style.BOARD
                        && (!b.channelId().equals(channel.getId()) || style != DealRepository.Style.BOARD))
                .ifPresent(b -> deals.removeBoardMessage(guild.getId(), b.channelId(), b.messageId().get()));
        deals.boardChanged(guild.getId());
        return refreshRolePanel(guild);
    }

    /** Rebuilds the role picker after the deals board's role changed or the board was turned off. */
    public static boolean refreshRolePanel(Guild guild) {
        return org.bunnys.bunnynexus.freebies.FreebieSystem.current()
                .map(freebies -> FreebieServerSetup.refreshRolePanel(guild, freebies.subscriptions())).orElse(false);
    }

    /** The board's ping role, when members may pick it from the role picker. */
    public static Optional<Role> pickableRole(Guild guild) {
        return DealSystem.current().flatMap(system -> system.repository().board(guild.getId()))
                .filter(DealRepository.Board::on).flatMap(b -> b.ping().roleId()).map(guild::getRoleById)
                .filter(role -> FreebieServerSetup.selfAssignable(role, guild.getSelfMember()));
    }
}
