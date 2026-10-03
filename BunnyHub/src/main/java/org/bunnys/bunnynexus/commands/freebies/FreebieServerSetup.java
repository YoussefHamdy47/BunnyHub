package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
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
 * Dashboard writes: saving one section, auto-setup (category, channels, roles, role picker) and the public role
 * picker. Blocking Discord calls; command workers only, after the interaction was acknowledged.
 */
public final class FreebieServerSetup {
    private FreebieServerSetup() {}

    public static final String ROLE_PREFIX = "freebie_role";
    static final String CATEGORY_NAME = "Free Games", SHARED_CHANNEL = "free-games";
    private static final EnumSet<Permission> BOT_CHANNEL_ACCESS = EnumSet.of(Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);

    /** Outcome of the section form: a line for the dashboard, and where catch-up could post live games. */
    public record SectionSaved(String notice, Optional<String> channelId, Set<FreebieStore> stores) {}

    public static SectionSaved saveSection(Guild guild, FreebieSection section, StandardGuildMessageChannel channel,
                                           Optional<Role> role, Set<FreebieStore> stores, String actorId,
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
        var result = subscriptions.setSection(guild.getId(), section, channel.getId(), picked, role.map(Role::getId), actorId, Instant.now());
        if (result == SaveResult.LIMIT_REACHED)
            return failed("⚠️ This server already uses " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                    + " alert channels. Put this section in one of those, or turn another section off first.");
        return new SectionSaved("✅ **" + section.label() + "** now posts " + labels(picked) + " in " + channel.getAsMention()
                + role.map(r -> ", pinging " + r.getAsMention()).orElse(" without a ping") + "."
                + (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY) ? ""
                : "\n⚠️ Please also give me **Read Message History** there, so retries can never double-post."),
                Optional.of(channel.getId()), picked);
    }

    private static SectionSaved failed(String notice) { return new SectionSaved(notice, Optional.empty(), Set.of()); }

    // ------------------------------------------------------------------ auto-setup

    /**
     * Creates (or reuses by name) the category, alert channels and one ping role per section, saves every chosen
     * section with all of its launchers, then posts the role picker. Returns the dashboard notice.
     */
    public static String autoSetup(Guild guild, Member actor, Set<FreebieSection> sections, boolean channelPerSection,
                                   FreebieSubscriptions subscriptions) {
        if (sections.isEmpty()) return "⚠️ Pick at least one section.";
        if (!actor.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ Auto-setup creates channels and roles, so you need **Manage Roles** and **Manage Channels**.";
        var self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ I need **Manage Roles** and **Manage Channels** in Server Settings → Roles to set this up.";
        if (!self.hasPermission(BOT_CHANNEL_ACCESS))
            return "⚠️ I need **View Channels**, **Send Messages**, **Embed Links** and **Read Message History** server-wide "
                    + "to create alert channels I can post in.";
        // Refuse before creating anything, so a full server is not left with channels that receive nothing.
        long needed = channelsAfterAutoSetup(subscriptions.forGuild(guild.getId()), sections, channelPerSection);
        if (needed > FreebieSubscriptions.MAX_CHANNELS_PER_GUILD)
            return "⚠️ That would use " + needed + " alert channels; a server can have " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                    + ". Choose **One channel for everything**, fewer sections, or turn another section off first.";
        try {
            Category category = category(guild);
            Map<FreebieSection, TextChannel> channels = new EnumMap<>(FreebieSection.class);
            Map<FreebieSection, Role> roles = new EnumMap<>(FreebieSection.class);
            TextChannel shared = channelPerSection ? null : channel(guild, category, SHARED_CHANNEL);
            for (FreebieSection section : sections) {
                channels.put(section, shared != null ? shared : channel(guild, category, section.channelName()));
                roles.put(section, role(guild, section.roleName()));
            }
            Set<FreebieSection> skipped = EnumSet.noneOf(FreebieSection.class);
            List<String> problems = new ArrayList<>();
            for (FreebieSection section : sections) {
                // A reused channel or role may have been changed since an earlier run; check it like a manual setup.
                var problem = FreebieSettingsCommands.validate(guild, channels.get(section), Optional.of(roles.get(section)));
                if (problem.isPresent()) { skipped.add(section); problems.add(section.label() + ": " + problem.get()); continue; }
                var result = subscriptions.setSection(guild.getId(), section, channels.get(section).getId(),
                        EnumSet.copyOf(section.stores()), Optional.of(roles.get(section).getId()), actor.getId(), Instant.now());
                if (result == SaveResult.LIMIT_REACHED) { skipped.add(section); problems.add(section.label() + ": channel limit reached"); }
            }
            var saved = new EnumMap<>(roles);
            saved.keySet().removeAll(skipped);
            if (saved.isEmpty()) return "⚠️ Auto-setup saved nothing:\n• " + String.join("\n• ", problems);
            var panelChannel = channels.get(saved.keySet().iterator().next());
            panelChannel.sendMessage(rolePanel(saved)).complete();

            var notice = new StringBuilder("✅ Auto-setup done: ");
            notice.append(String.join(", ", new LinkedHashSet<>(channels.values()).stream().map(TextChannel::getAsMention).toList()))
                    .append(" in **").append(CATEGORY_NAME).append("**, with roles ")
                    .append(String.join(", ", saved.values().stream().map(Role::getAsMention).toList())).append(".")
                    .append("\nMembers can pick their pings from the role picker in ").append(panelChannel.getAsMention()).append(".");
            if (!problems.isEmpty()) notice.append("\n⚠️ Not saved:\n• ").append(String.join("\n• ", problems));
            return notice.toString();
        } catch (InsufficientPermissionException missing) {
            return "⚠️ I'm missing **" + missing.getPermission().getName() + "** to finish. Anything created so far is kept; run it again after fixing it.";
        } catch (ErrorResponseException refused) {
            if (refused.getErrorResponse() != ErrorResponse.MISSING_PERMISSIONS && refused.getErrorResponse() != ErrorResponse.MISSING_ACCESS)
                throw refused;
            return "⚠️ Discord refused because of my permissions (is my role high enough?). Anything created so far is kept; run it again after fixing it.";
        }
    }

    /**
     * Alert channels in use once auto-setup has run: those still used by sections it leaves alone, plus the ones it
     * adds. Conservative: a reused channel that already carries another section is counted twice.
     */
    static long channelsAfterAutoSetup(List<FreebieSubscriptions.Subscription> saved, Set<FreebieSection> sections,
                                       boolean channelPerSection) {
        long kept = saved.stream().filter(s -> !sections.contains(s.store().section()))
                .map(FreebieSubscriptions.Subscription::channelId).distinct().count();
        return kept + (channelPerSection ? sections.size() : 1);
    }

    private static Category category(Guild guild) {
        var existing = guild.getCategoriesByName(CATEGORY_NAME, true);
        return existing.isEmpty() ? guild.createCategory(CATEGORY_NAME).complete() : existing.getFirst();
    }

    /** Alert channels are read-only for members; the bot gets exactly what delivery needs. */
    private static TextChannel channel(Guild guild, Category category, String name) {
        for (TextChannel channel : category.getTextChannels()) if (channel.getName().equals(name)) return channel;
        return category.createTextChannel(name).setTopic("Free games, checked by the bot owner before they are posted.")
                .addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.MESSAGE_SEND))
                .addMemberPermissionOverride(guild.getSelfMember().getIdLong(), BOT_CHANNEL_ACCESS, EnumSet.noneOf(Permission.class))
                .complete();
    }

    /** Reuses a same-named role only when members could safely hand it to themselves. */
    private static Role role(Guild guild, String name) {
        for (Role role : guild.getRolesByName(name, false)) if (selfAssignable(role, guild.getSelfMember())) return role;
        return guild.createRole().setName(name).setMentionable(true).setPermissions(0L).complete();
    }

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

    /** The saved ping role of each section that members may safely give themselves. */
    public static Map<FreebieSection, Role> pickableRoles(Guild guild, List<FreebieSubscriptions.Subscription> saved) {
        Map<FreebieSection, Role> roles = new EnumMap<>(FreebieSection.class);
        for (var state : FreebieDashboard.states(saved).values())
            state.role().map(guild::getRoleById).filter(role -> selfAssignable(role, guild.getSelfMember()))
                    .ifPresent(role -> roles.put(state.section(), role));
        return roles;
    }

    /** True when {@code roleId} is a ping role this server saved for free-game alerts. */
    public static boolean isPingRole(String roleId, List<FreebieSubscriptions.Subscription> saved) {
        return saved.stream().anyMatch(s -> s.roleId().filter(roleId::equals).isPresent());
    }

    /** Public message: one toggle per ping role, labelled with the section(s) it covers. */
    public static MessageCreateData rolePanel(Map<FreebieSection, Role> roles) {
        Map<Role, List<FreebieSection>> bySection = new LinkedHashMap<>();
        roles.forEach((section, role) -> bySection.computeIfAbsent(role, r -> new ArrayList<>()).add(section));
        var lines = new StringBuilder("Press a button to get pinged for those free games; press it again to stop.\n\n");
        List<Button> buttons = new ArrayList<>();
        for (var entry : bySection.entrySet()) {
            var sections = entry.getValue();
            String label = sections.size() == FreebieSection.values().length ? "All free games"
                    : String.join(" + ", sections.stream().map(FreebieSection::label).toList());
            for (FreebieSection section : sections)
                lines.append(section.emoji()).append(" **").append(section.label()).append("** • ")
                        .append(labels(EnumSet.copyOf(section.stores()))).append("\n");
            if (buttons.size() < 5)
                buttons.add(Button.secondary(ROLE_PREFIX + ":" + entry.getKey().getId(), label.length() <= 80 ? label : label.substring(0, 79) + "…")
                        .withEmoji(Emoji.fromUnicode(sections.getFirst().emoji())));
        }
        var embed = Embeds.of("🔔", "Free-game pings", lines.toString().strip());
        return new MessageCreateBuilder().setEmbeds(embed.build()).setComponents(ActionRow.of(buttons))
                .setAllowedMentions(List.of()).build();
    }

    static String labels(Set<FreebieStore> stores) {
        return String.join(", ", stores.stream().sorted().map(FreebieStore::label).toList());
    }
}
