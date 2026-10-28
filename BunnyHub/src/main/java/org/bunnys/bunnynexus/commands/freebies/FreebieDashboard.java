package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands.Reply;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.utils.Embeds;
import java.util.*;

/**
 * {@code /free-games setup}: one ephemeral panel showing every section (major PC, indie PC, console, mobile) with
 * its channel, ping role and launchers, plus buttons to edit a section, auto-setup the server or post a role picker.
 * Pure builders; the handlers in {@code buttons}, {@code selects} and {@code modals} do the work and re-check access.
 *
 * <p>Component ids: {@code freebie_dash:<action>:<guild>[...]} for navigation (no cooldown),
 * {@code freebie_dashrun:<action>:<guild>[:<sections>]} for writes, {@code freebie_autosel:<guild>} and
 * {@code freebie_section:<guild>:<section>} for the auto-setup picker and the section form.
 */
public final class FreebieDashboard {
    private FreebieDashboard() {}

    public static final String NAV_PREFIX = "freebie_dash", RUN_PREFIX = "freebie_dashrun",
            AUTO_SELECT_PREFIX = "freebie_autosel", SECTION_MODAL_PREFIX = "freebie_section";
    public static final String OPEN_SECTION = "s", AUTO = "auto", HOME = "home", ONE_CHANNEL = "one",
            CHANNEL_PER_SECTION = "split", ROLE_PANEL = "panel", QUICK = "quick", PINGS = "pings", HELP = "help";
    /** Launcher pings: the picker ({@code freebie_ping:<guild>}) and its form ({@code freebie_pingform:<guild>:<launcher>}). */
    public static final String PING_SELECT_PREFIX = "freebie_ping", PING_MODAL_PREFIX = "freebie_pingform",
            QUICK_MODAL_PREFIX = "freebie_quick";
    /** Quick-setup form fields. */
    public static final String SECTIONS_FIELD = "sections";
    /** In a section button's role slot: the section's launchers ping different roles. */
    static final String MIXED = "*";
    /** Modal field ids. */
    public static final String CHANNEL_FIELD = "channel", ROLE_FIELD = "role", LAUNCHERS_FIELD = "launchers",
            REMINDER_FIELD = "reminder", POST_FIELD = "post";
    /** In a component id or select value: no channel or no role. */
    static final String NONE = "-";

    /** One section as saved: its channel(s) with their launchers, and the role(s) pinged. */
    public record SectionState(FreebieSection section, Map<String, Set<FreebieStore>> channels, Set<Optional<String>> roles,
                               Set<Integer> reminders) {
        public SectionState(FreebieSection section, Map<String, Set<FreebieStore>> channels, Set<Optional<String>> roles) {
            this(section, channels, roles, new TreeSet<>());
        }
        /** Hours before the end for the ending-soon reminder; 0 (off) unless the whole section agrees. */
        public int reminder() { return reminders.size() == 1 ? reminders.iterator().next() : 0; }
        public boolean on() { return !channels.isEmpty(); }
        public Set<FreebieStore> enabled() {
            Set<FreebieStore> all = EnumSet.noneOf(FreebieStore.class);
            channels.values().forEach(all::addAll);
            return all;
        }
        /** The channel holding most of the section; older per-launcher setups may split one across channels. */
        public Optional<String> mainChannel() {
            return channels.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().size())).map(Map.Entry::getKey);
        }
        /** The role pinged, when the whole section pings the same one. */
        public Optional<String> role() { return roles.size() == 1 ? roles.iterator().next() : Optional.empty(); }
        /** The launchers ping different roles (set from Launcher pings). */
        public boolean mixedPings() { return roles.size() > 1; }
    }

    public static Map<FreebieSection, SectionState> states(List<Subscription> saved) {
        Map<FreebieSection, SectionState> states = new EnumMap<>(FreebieSection.class);
        for (FreebieSection section : FreebieSection.values())
            states.put(section, new SectionState(section, new TreeMap<>(), new LinkedHashSet<>()));
        for (Subscription s : saved) {
            var state = states.get(s.store().section());
            state.channels().computeIfAbsent(s.channelId(), k -> EnumSet.noneOf(FreebieStore.class)).add(s.store());
            state.roles().add(s.roleId());
            state.reminders().add(s.reminderHours());
        }
        return states;
    }

    /** Every dashboard control re-checks this: the clicker still manages the server the control was built for. */
    public static boolean canManage(Guild guild, Member member, String guildId) {
        return guild != null && member != null && guild.getId().equals(guildId) && member.hasPermission(Permission.MANAGE_SERVER);
    }

    public static MessageEditData asEdit(Reply reply) {
        return new MessageEditBuilder().setEmbeds(reply.embed()).setComponents(reply.rows()).setAllowedMentions(List.of()).build();
    }

    // ------------------------------------------------------------------ main view

    public static Reply dashboard(Guild guild, List<Subscription> saved, String notice) {
        var states = states(saved);
        long active = states.values().stream().filter(SectionState::on).count();
        var embed = Embeds.of(null, "Free-game alerts").setTimestamp(null);
        embed.setDescription((notice == null || notice.isBlank() ? "" : notice + "\n\n")
                + (saved.isEmpty() ? "New here? **Auto-setup** creates the channels and ping roles for you.\n"
                        : "**" + active + " of " + FreebieSection.values().length + " sections on.** ")
                + "Click a section to change its channel, launchers or ping. Untick every launcher to turn it off.\n"
                + "-# Quick setup: one channel in one step. Launcher pings: a different role per launcher, like @Epic.");
        var pings = launcherPings(saved);
        for (var state : states.values()) embed.addField(state.section().label(), describe(guild, state, pings), false);
        long channels = saved.stream().map(Subscription::channelId).distinct().count();
        Embeds.footer(embed, channels + "/" + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD + " alert channels used");

        List<Button> sections = new ArrayList<>();
        for (var state : states.values()) sections.add(sectionButton(guild, state));
        String id = guild.getId();
        return new Reply(embed.build(), List.of(ActionRow.of(sections), ActionRow.of(
                (saved.isEmpty() ? Button.primary(NAV_PREFIX + ":" + QUICK + ":" + id, "Quick setup")
                        : Button.secondary(NAV_PREFIX + ":" + QUICK + ":" + id, "Quick setup")),
                Button.secondary(NAV_PREFIX + ":" + AUTO + ":" + id, "Create channels for me"),
                Button.secondary(NAV_PREFIX + ":" + PINGS + ":" + id, "Launcher pings").withDisabled(saved.isEmpty()),
                Button.secondary(RUN_PREFIX + ":" + ROLE_PANEL + ":" + id, "Post role picker"),
                Button.secondary(NAV_PREFIX + ":" + HELP + ":" + id, "Help"))));
    }

    /** Each enabled launcher's ping(s), across every channel that posts it. */
    public static Map<FreebieStore, Set<Optional<String>>> launcherPings(List<Subscription> saved) {
        Map<FreebieStore, Set<Optional<String>>> pings = new EnumMap<>(FreebieStore.class);
        for (Subscription s : saved) pings.computeIfAbsent(s.store(), k -> new LinkedHashSet<>()).add(s.roleId());
        return pings;
    }

    static String mentions(Set<Optional<String>> roles) {
        return String.join(", ", roles.stream().map(role -> role.map(r -> "<@&" + r + ">").orElse("nobody")).toList());
    }

    private static String describe(Guild guild, SectionState state, Map<FreebieStore, Set<Optional<String>>> pings) {
        var labels = state.section().stores().stream().map(FreebieStore::label).toList();
        if (!state.on()) return "Off\n-# " + String.join(" · ", labels);
        var text = new StringBuilder();
        for (var entry : state.channels().entrySet()) {
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, entry.getKey());
            boolean ok = channel != null && guild.getSelfMember().hasPermission(channel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            text.append("<#").append(entry.getKey()).append(">")
                    .append(ok ? "" : channel == null ? " • **channel was deleted**" : " • **I can't post here**");
            if (state.channels().size() > 1)
                text.append(" • ").append(String.join(", ", entry.getValue().stream().map(FreebieStore::label).toList()));
            text.append("\n");
        }
        if (state.mixedPings()) {
            text.append("**Pings** · per launcher: ").append(String.join(" · ", state.enabled().stream().sorted()
                    .map(store -> store.label() + " " + mentions(pings.getOrDefault(store, Set.of(Optional.empty())))).toList()));
        } else {
            text.append("**Pings** · ").append(mentions(state.roles()));
            text.append("\n**Launchers** · ").append(String.join(" · ", state.enabled().stream().sorted().map(FreebieStore::label).toList()));
        }
        if (state.reminder() > 0) text.append("\n**Reminder** · ").append(state.reminder()).append(" hours before a game ends");
        var off = state.section().stores().stream().filter(s -> !state.enabled().contains(s)).map(FreebieStore::label).toList();
        if (!off.isEmpty()) text.append("\n**Off** · ").append(String.join(", ", off));
        return text.toString();
    }

    /**
     * Opening a modal cannot wait for the database, so the section's current channel, role and launchers ride in
     * the button id (at most 88 characters). They are only defaults: the form's submission is what gets saved.
     */
    static Button sectionButton(Guild guild, SectionState state) {
        String channel = state.mainChannel().filter(c -> guild.getGuildChannelById(c) != null).orElse(NONE);
        String role = state.mixedPings() ? MIXED : state.role().filter(r -> guild.getRoleById(r) != null).orElse(NONE);
        String id = String.join(":", NAV_PREFIX, OPEN_SECTION, guild.getId(), String.valueOf(state.section().code()),
                channel, role, Integer.toHexString(mask(state.section(), state.enabled())), String.valueOf(state.reminder()));
        return Button.of(state.on() ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY, id, state.section().label());
    }

    static int mask(FreebieSection section, Set<FreebieStore> stores) {
        int mask = 0;
        var all = section.stores();
        for (int i = 0; i < all.size(); i++) if (stores.contains(all.get(i))) mask |= 1 << i;
        return mask;
    }

    static Set<FreebieStore> unmask(FreebieSection section, int mask) {
        Set<FreebieStore> stores = EnumSet.noneOf(FreebieStore.class);
        var all = section.stores();
        for (int i = 0; i < all.size(); i++) if ((mask & (1 << i)) != 0) stores.add(all.get(i));
        return stores;
    }

    // ------------------------------------------------------------------ auto-setup view

    public static Reply autoSetup(String guildId, Set<FreebieSection> chosen) { return autoSetup(guildId, chosen, FreebieCatchUp.AUTO); }

    /** {@code post}: whether auto-setup also posts the games that are free right now (toggled in place). */
    public static Reply autoSetup(String guildId, Set<FreebieSection> chosen, FreebieCatchUp post) {
        var embed = Embeds.of(null, "Auto-setup").setTimestamp(null)
                .setDescription("Choose your sections, then pick a layout. I'll create read-only channels in **Free Games**, "
                        + "a ping role per section, and a picker so members can opt in.")
                .addField("ONE SHARED CHANNEL", "All selected sections in `#free-games`.\nEach section keeps its own ping role.", true)
                .addField("SEPARATE CHANNELS", "A dedicated channel for each section.\nKeep different platforms together.", true)
                .addField("BEFORE YOU CREATE", "You and the bot need **Manage Roles** and **Manage Channels**. "
                        + "If you already have channels or roles for free games (like #epic-games or @Steam), I'll ask before using them. "
                        + "Selected sections' settings are replaced; other sections stay unchanged.", false);
        embed.addField("Chosen sections", String.join("\n", Arrays.stream(FreebieSection.values()).filter(chosen::contains)
                .map(s -> s.label() + " • " + String.join(", ", s.stores().stream().map(FreebieStore::label).toList()))
                .toList()), false);
        var picker = StringSelectMenu.create(AUTO_SELECT_PREFIX + ":" + guildId + (post == FreebieCatchUp.AUTO ? "" : ":" + post.code()))
                .setPlaceholder("Sections to set up")
                .setRequiredRange(1, FreebieSection.values().length);
        for (FreebieSection section : FreebieSection.values())
            picker.addOption(section.label(), String.valueOf(section.code()));
        picker.setDefaultValues(chosen.stream().map(s -> String.valueOf(s.code())).toList());
        String codes = FreebieCatchUp.token(chosen, post);
        return new Reply(embed.build(), List.of(ActionRow.of(picker.build()), ActionRow.of(
                Button.success(RUN_PREFIX + ":" + ONE_CHANNEL + ":" + guildId + ":" + codes, "One channel for everything"),
                Button.primary(RUN_PREFIX + ":" + CHANNEL_PER_SECTION + ":" + guildId + ":" + codes, "A channel per section"),
                Button.secondary(NAV_PREFIX + ":" + AUTO + ":" + guildId + ":" + FreebieCatchUp.token(chosen, post.next()), post.toggle()),
                Button.secondary(NAV_PREFIX + ":" + HOME + ":" + guildId, "Back"))));
    }
}
