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

    public static final String ROLE_PREFIX = "freebie_role";
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
                keepLauncherPings, reminderHours, actorId, Instant.now());
        if (result == SaveResult.LIMIT_REACHED)
            return failed("⚠️ This server already uses " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                    + " alert channels. Put this section in one of those, or turn another section off first.");
        return new SectionSaved("✅ **" + section.label() + "** now posts " + labels(picked) + " in " + channel.getAsMention()
                + (keepLauncherPings ? ", each launcher keeping its own ping"
                        : role.map(r -> ", pinging " + r.getAsMention()).orElse(" without a ping")) + "."
                + (reminderHours != null && reminderHours > 0 ? " Reminder " + reminderHours + " hours before a game ends." : "")
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
    public static MessageCreateData rolePanel(Map<Role, Set<FreebieStore>> roles) {
        var lines = new StringBuilder("Your platforms. Your notifications.\nChoose what you'd like to hear about below.\n\n");
        List<Button> buttons = new ArrayList<>();
        for (var entry : roles.entrySet()) {
            var stores = entry.getValue();
            var sections = Arrays.stream(FreebieSection.values()).filter(sec -> stores.containsAll(sec.stores())).toList();
            boolean whole = !sections.isEmpty() && sections.stream().mapToInt(sec -> sec.stores().size()).sum() == stores.size();
            String label;
            String emoji;
            if (whole) {
                label = sections.size() == FreebieSection.values().length ? "All free games"
                        : String.join(" + ", sections.stream().map(FreebieSection::label).toList());
                emoji = sections.getFirst().emoji();
                for (FreebieSection section : sections)
                    lines.append(section.emoji()).append(" **").append(section.label()).append("**\n")
                            .append(labels(EnumSet.copyOf(section.stores()))).append("\n\n");
            } else {
                label = labels(stores);
                emoji = stores.iterator().next().section().emoji();
                lines.append(emoji).append(" **").append(label).append("**\n\n");
            }
            if (buttons.size() < 25)
                buttons.add(Button.secondary(ROLE_PREFIX + ":" + entry.getKey().getId(), label.length() <= 80 ? label : label.substring(0, 79) + "…")
                        .withEmoji(Emoji.fromUnicode(emoji)));
        }
        String body = lines.toString().strip();
        var embed = Embeds.of("🔔", "Never miss your next free game", body.length() <= 4000 ? body : body.substring(0, 3999) + "…")
                .setTimestamp(null);
        Embeds.footer(embed, "Tap to opt in • Tap again to opt out");
        return new MessageCreateBuilder().setEmbeds(embed.build()).setComponents(ActionRow.partitionOf(buttons))
                .setAllowedMentions(List.of()).build();
    }

    static String labels(Set<FreebieStore> stores) {
        return String.join(", ", stores.stream().sorted().map(FreebieStore::label).toList());
    }
}
