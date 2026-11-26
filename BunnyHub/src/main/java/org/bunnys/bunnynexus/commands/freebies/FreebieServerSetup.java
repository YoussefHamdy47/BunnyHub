package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.SaveResult;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.util.*;

/**
 * Dashboard writes: saving one section and the public role picker (auto-setup is {@link FreebieAutoSetup}).
 * Blocking Discord calls; command workers only, after the interaction was acknowledged.
 */
public final class FreebieServerSetup {
    private FreebieServerSetup() {}

    public static final String ROLE_PREFIX = org.bunnys.bunnynexus.freebies.FreebieSubscriptions.ROLE_BUTTON;
    static final String CATEGORY_NAME = "Free Games", SHARED_CHANNEL = "free-games";

    /** Outcome of the section form: a line for the dashboard, and where catch-up could post live games. */
    public record SectionSaved(String notice, Optional<String> channelId, Set<FreebieStore> stores) {}

    public static SectionSaved saveSection(Guild guild, FreebieSection section, StandardGuildMessageChannel channel,
                                           Optional<Role> role, Set<FreebieStore> stores, String actorId,
                                           FreebieSubscriptions subscriptions) {
        return saveSection(guild, section, channel, role, stores, null, actorId, subscriptions);
    }

    /** As above; a non-null {@code reminderHours} (0, 6 or 24) also sets the section's ending-soon reminder. */
    public static SectionSaved saveSection(Guild guild, FreebieSection section, StandardGuildMessageChannel channel,
                                           Optional<Role> role, Set<FreebieStore> stores, Integer reminderHours, String actorId,
                                           FreebieSubscriptions subscriptions) {
        return saveSection(guild, section, channel, role, stores, false, reminderHours, actorId, subscriptions);
    }

    /** As above; {@code keepLauncherPings} leaves each launcher's own ping alone (see the dashboard's Launcher pings). */
    public static SectionSaved saveSection(Guild guild, FreebieSection section, StandardGuildMessageChannel channel,
                                           Optional<Role> role, Set<FreebieStore> stores, boolean keepLauncherPings,
                                           Integer reminderHours, String actorId, FreebieSubscriptions subscriptions) {
        return saveSection(guild, section, channel, role, stores, keepLauncherPings, reminderHours, null, actorId, subscriptions);
    }

    /** As above; a non-null {@code reminderPing} also sets whether the reminder pings the section's role. */
    public static SectionSaved saveSection(Guild guild, FreebieSection section, StandardGuildMessageChannel channel,
                                           Optional<Role> role, Set<FreebieStore> stores, boolean keepLauncherPings,
                                           Integer reminderHours, Boolean reminderPing, String actorId,
                                           FreebieSubscriptions subscriptions) {
        Set<FreebieStore> picked = EnumSet.noneOf(FreebieStore.class);
        stores.stream().filter(s -> s.section() == section).forEach(picked::add);
        if (picked.isEmpty()) {
            long removed = subscriptions.clearSection(guild.getId(), section);
            return failed(removed == 0 ? "ℹ️ **" + section.label() + "** was already off."
                    : "✅ **" + section.label() + "** is now off. Anything already being sent may still arrive.");
        }
        if (channel == null) return failed("⚠️ Pick a channel for **" + section.label() + "**, or untick every launcher to turn it off.");
        var problem = FreebieSettingsCommands.validate(guild, channel, role);
        if (problem.isPresent()) return failed("⚠️ " + problem.get());
        var result = subscriptions.setSection(guild.getId(), section, channel.getId(), picked, role.map(Role::getId),
                keepLauncherPings, reminderHours, reminderPing, actorId, Instant.now());
        if (result == SaveResult.LIMIT_REACHED)
            return failed("⚠️ This server already uses " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                    + " alert channels. Put this section in one of those, or turn another section off first.");
        return new SectionSaved("✅ **" + section.label() + "** now posts " + labels(picked) + " in " + channel.getAsMention()
                + (keepLauncherPings ? ", each launcher keeping its own ping"
                        : role.map(r -> ", pinging " + r.getAsMention()).orElse(" without a ping")) + "."
                + (reminderHours != null && reminderHours > 0 ? " Reminder " + reminderHours + " hours before a game ends"
                        + (Boolean.TRUE.equals(reminderPing) ? ", with a ping." : ", without a ping.") : "")
                + (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY) ? ""
                : "\n⚠️ Please also give me **Read Message History** there, so retries can never double-post."),
                Optional.of(channel.getId()), picked);
    }

    private static SectionSaved failed(String notice) { return new SectionSaved(notice, Optional.empty(), Set.of()); }

    // ------------------------------------------------------------------ role picker

    /**
     * The role picker toggles roles on anyone who presses it, so only a role that grants nothing may be offered:
     * a ping role that is also, say, a moderator role or a verification gate must never become self-service.
     * "Grants nothing" means no permission beyond @everyone's and no channel override that allows anything,
     * because a role with no permissions of its own can still unlock private channels through overrides.
     */
    public static boolean selfAssignable(Role role, Member self) {
        if (role.isPublicRole() || role.isManaged() || !self.canInteract(role)) return false;
        var guild = role.getGuild();
        if ((role.getPermissionsRaw() & ~guild.getPublicRole().getPermissionsRaw()) != 0) return false;
        for (GuildChannel channel : guild.getChannels(true))
            if (channel instanceof IPermissionContainer container) {
                var override = container.getPermissionOverride(role);
                if (override != null && override.getAllowedRaw() != 0) return false;
            }
        return true;
    }

    /** Every saved ping role members may safely give themselves, with the launchers it pings for. */
    public static Map<Role, Set<FreebieStore>> pickableRoles(Guild guild, List<FreebieSubscriptions.Subscription> saved) {
        Map<Role, Set<FreebieStore>> roles = new LinkedHashMap<>();
        for (var s : saved) {
            var role = s.roleId().map(guild::getRoleById).orElse(null);
            if (role != null && selfAssignable(role, guild.getSelfMember()))
                roles.computeIfAbsent(role, r -> EnumSet.noneOf(FreebieStore.class)).add(s.store());
        }
        return roles;
    }

    /** True when {@code roleId} is a ping role this server saved for free-game alerts. */
    public static boolean isPingRole(String roleId, List<FreebieSubscriptions.Subscription> saved) {
        return saved.stream().anyMatch(s -> s.roleId().filter(roleId::equals).isPresent());
    }

    /** One role per section, each covering the whole section. */
    public static MessageCreateData sectionRolePanel(Map<FreebieSection, Role> roles) {
        Map<Role, Set<FreebieStore>> byRole = new LinkedHashMap<>();
        roles.forEach((section, role) -> byRole.computeIfAbsent(role, r -> EnumSet.noneOf(FreebieStore.class)).addAll(section.stores()));
        return rolePanel(byRole);
    }

    /**
     * Public message: one toggle per ping role (up to 25). A role covering whole sections is labelled by section
     * ("All free games" when it covers everything); one covering single launchers, such as a reused {@code @Steam}
     * role, is labelled by launcher.
     */
    public static MessageCreateData rolePanel(Map<Role, Set<FreebieStore>> roles) { return rolePanel(roles, Optional.empty()); }

    /** As above, plus the deals board's ping role when the server has one. */
    public static MessageCreateData rolePanel(Map<Role, Set<FreebieStore>> roles, Optional<Role> deals) {
        return rolePanelCard(roles, deals).message();
    }

    /**
     * Posts the picker and remembers where, so {@link #refreshRolePanel} can rebuild it later. Blocking; never call
     * on the gateway thread.
     */
    public static void postRolePanel(Guild guild, StandardGuildMessageChannel channel, Map<Role, Set<FreebieStore>> roles, Optional<Role> deals,
                                     FreebieSubscriptions subscriptions, Instant now) {
        var message = channel.sendMessage(rolePanel(roles, deals)).complete();
        subscriptions.rolePickers().posted(guild.getId(), channel.getId(), message.getId(), now);
    }

    /**
     * Rebuilds the server's remembered picker from the roles it can offer now (free-game pings and the deals board's
     * role). False when there is no picker to update. A deleted picker is forgotten; a picker left with nothing to
     * offer is left as it is (its buttons already answer "no longer offered"). Blocking; never call on the gateway thread.
     */
    public static boolean refreshRolePanel(Guild guild, FreebieSubscriptions subscriptions) {
        var picker = subscriptions.rolePickers().find(guild.getId()).orElse(null);
        if (picker == null) return false;
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, picker.channelId());
        if (channel == null) { subscriptions.rolePickers().gone(guild.getId(), picker.messageId()); return false; }
        var roles = pickableRoles(guild, subscriptions.forGuild(guild.getId()));
        var deals = DealBoardSetup.pickableRole(guild);
        if (roles.isEmpty() && deals.isEmpty()) return true;
        channel.editMessageById(picker.messageId(), rolePanelCard(roles, deals).edit()).queue(null,
                new net.dv8tion.jda.api.exceptions.ErrorHandler().handle(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE,
                        gone -> subscriptions.rolePickers().gone(guild.getId(), picker.messageId())));
        return true;
    }

    /** Up to this many roles get their own row with the button beside it; more fall back to plain button rows. */
    static final int ROWS_WITH_BUTTONS = 10;

    /** One role on the picker: its name, a line of launcher emojis, and the toggle button. */
    private record Choice(String roleId, String name, String detail, String emoji) {}

    static org.bunnys.handler.cards.Card rolePanelCard(Map<Role, Set<FreebieStore>> roles, Optional<Role> deals) {
        List<Choice> choices = new ArrayList<>();
        for (var entry : roles.entrySet()) {
            var stores = entry.getValue();
            var sections = Arrays.stream(FreebieSection.values()).filter(sec -> stores.containsAll(sec.stores())).toList();
            boolean whole = !sections.isEmpty() && sections.stream().mapToInt(sec -> sec.stores().size()).sum() == stores.size();
            String name = !whole ? labels(stores) : sections.size() == FreebieSection.values().length ? "All free games"
                    : String.join(" + ", sections.stream().map(FreebieSection::label).toList());
            var badges = stores.stream().filter(s -> s != FreebieStore.OTHER).sorted().map(FreebieStore::badge).toList();
            String detail = whole && sections.size() == FreebieSection.values().length ? "Every launcher and platform" : String.join(" ", badges);
            choices.add(new Choice(entry.getKey().getId(), name, detail, badges.isEmpty() ? "🎁" : badges.getFirst()));
        }
        deals.filter(role -> !roles.containsKey(role)).ifPresent(role -> choices.add(new Choice(role.getId(), "Game deals",
                String.join(" ", Arrays.stream(org.bunnys.bunnynexus.deals.DealStore.values()).map(s -> s.emoji.orElse(s.label)).toList())
                        + " 75% off or more on well-reviewed games", org.bunnys.utils.AppDesign.Emojis.STEAM)));

        var card = org.bunnys.handler.cards.Card.builder()
                .title(deals.isPresent() ? "Free game and deal pings" : "Free game pings")
                .text("Get pinged for what you play. Tap again to remove the role.");
        List<Button> buttons = new ArrayList<>();
        for (Choice choice : choices) {
            if (buttons.size() == 25) break;
            String label = choice.name().length() <= 80 ? choice.name() : choice.name().substring(0, 79) + "…";
            buttons.add(Button.secondary(ROLE_PREFIX + ":" + choice.roleId(), label).withEmoji(Emoji.fromFormatted(choice.emoji())));
        }
        if (choices.size() <= ROWS_WITH_BUTTONS) {
            card.divider();
            for (int i = 0; i < buttons.size(); i++)
                card.section("**" + choices.get(i).name() + "**\n" + choices.get(i).detail(), buttons.get(i));
        } else {
            card.text(String.join("\n", choices.stream().map(c -> "**" + c.name() + "** " + c.detail()).toList()));
            card.rows(ActionRow.partitionOf(buttons));
        }
        return card.footer("Roles only ping. They don't unlock any channels.").build();
    }

    static String labels(Set<FreebieStore> stores) {
        return String.join(", ", stores.stream().sorted().map(FreebieStore::label).toList());
    }
}
