package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.entities.channel.ChannelType;
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
            CHANNEL_PER_SECTION = "split", ROLE_PANEL = "panel";
    /** Modal field ids. */
    public static final String CHANNEL_FIELD = "channel", ROLE_FIELD = "role", LAUNCHERS_FIELD = "launchers",
            REMINDER_FIELD = "reminder", POST_FIELD = "post";
    private static final String NONE = "-";

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
                + "Click a section to change its channel, launchers or ping. Untick every launcher to turn it off.");
        for (var state : states.values()) embed.addField(state.section().label(), describe(guild, state), false);
        long channels = saved.stream().map(Subscription::channelId).distinct().count();
        Embeds.footer(embed, channels + "/" + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD + " alert channels used");

        List<Button> sections = new ArrayList<>();
        for (var state : states.values()) sections.add(sectionButton(guild, state));
        String id = guild.getId();
        return new Reply(embed.build(), List.of(ActionRow.of(sections), ActionRow.of(
                Button.primary(NAV_PREFIX + ":" + AUTO + ":" + id, "Auto-setup"),
                Button.secondary(RUN_PREFIX + ":" + ROLE_PANEL + ":" + id, "Post role picker here"),
                Button.secondary(NAV_PREFIX + ":" + HOME + ":" + id, "Refresh"))));
    }

    private static String describe(Guild guild, SectionState state) {
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
        text.append("**Pings** · ").append(String.join(", ", state.roles().stream()
                .map(role -> role.map(r -> "<@&" + r + ">").orElse("nobody")).toList()));
        text.append("\n**Launchers** · ").append(String.join(" · ", state.enabled().stream().sorted().map(FreebieStore::label).toList()));
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
        String role = state.role().filter(r -> guild.getRoleById(r) != null).orElse(NONE);
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

    // ------------------------------------------------------------------ section form

    /**
     * The form behind a section button, from its id {@code freebie_dash:s:<guild>:<section>:<channel>:<role>:<mask>}.
     * A section that is off starts with every launcher ticked, so picking a channel is all it takes.
     */
    public static Optional<Modal> sectionForm(String[] args) {
        // Seven parts: dashboards drawn before reminders existed (reminder off).
        if (args.length != 7 && args.length != 8) return Optional.empty();
        var section = FreebieSection.byCode(args[3]).orElse(null);
        if (section == null) return Optional.empty();
        int mask;
        var channel = EntitySelectMenu.create(CHANNEL_FIELD, SelectTarget.CHANNEL).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
                .setRequiredRange(0, 1).setRequired(false).setPlaceholder("Pick a channel");
        var role = EntitySelectMenu.create(ROLE_FIELD, SelectTarget.ROLE).setRequiredRange(0, 1).setRequired(false)
                .setPlaceholder("No ping");
        try {
            mask = Integer.parseInt(args[6], 16);
            if (!NONE.equals(args[4])) channel.setDefaultValues(DefaultValue.channel(args[4]));
            if (!NONE.equals(args[5])) role.setDefaultValues(DefaultValue.role(args[5]));
        } catch (NumberFormatException malformed) { return Optional.empty(); }
        String reminderNow = args.length == 8 ? args[7] : "0";
        if (!FreebieSubscriptions.REMINDER_CHOICES.contains(parse(reminderNow))) return Optional.empty();
        var reminder = StringSelectMenu.create(REMINDER_FIELD).setRequiredRange(1, 1)
                .addOption("Off", "0", "No reminder")
                .addOption("24 hours before", "24", "A short reply to the alert a day before a game ends")
                .addOption("6 hours before", "6", "A short reply to the alert 6 hours before a game ends")
                .setDefaultValues(reminderNow);
        var post = StringSelectMenu.create(POST_FIELD).setRequiredRange(1, 1);
        for (FreebieCatchUp choice : FreebieCatchUp.values()) post.addOption(choice.option(), String.valueOf(choice.code()));
        post.setDefaultValues(String.valueOf(FreebieCatchUp.AUTO.code()));
        var ticked = mask == 0 ? EnumSet.copyOf(section.stores()) : unmask(section, mask);
        var launchers = StringSelectMenu.create(LAUNCHERS_FIELD).setRequiredRange(0, section.stores().size()).setRequired(false)
                .setPlaceholder("Nothing ticked = section off");
        for (FreebieStore store : section.stores()) launchers.addOption(store.label(), store.id());
        launchers.setDefaultValues(ticked.stream().map(FreebieStore::id).toList());

        return Optional.of(Modal.create(SECTION_MODAL_PREFIX + ":" + args[2] + ":" + section.code(), section.label())
                .addComponents(Label.of("Channel", "Where these free games are posted", channel.build()),
                        Label.of("Role to ping", "Leave empty to post without a ping", role.build()),
                        Label.of("Launchers", "Untick everything to turn this section off", launchers.build()),
                        Label.of("Ending-soon reminder", "Only for games with a real end date; pings like new alerts",
                                reminder.build()),
                        Label.of("Post current games now", "Approved games that are still free; each at most once per channel",
                                post.build()))
                .build());
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException malformed) { return -1; }
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
