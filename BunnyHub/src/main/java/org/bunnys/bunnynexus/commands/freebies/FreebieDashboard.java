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
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands.Reply;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.utils.Embeds;
import java.util.*;

/**
 * {@code /freebie dashboard}: one ephemeral panel showing every section (major PC, indie PC, console, mobile) with
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
    public static final String CHANNEL_FIELD = "channel", ROLE_FIELD = "role", LAUNCHERS_FIELD = "launchers";
    private static final String NONE = "-";

    /** One section as saved: its channel(s) with their launchers, and the role(s) pinged. */
    public record SectionState(FreebieSection section, Map<String, Set<FreebieStore>> channels, Set<Optional<String>> roles) {
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
        var embed = Embeds.of("🎁", "Free-game alerts dashboard");
        embed.setDescription((notice == null || notice.isBlank() ? "" : notice + "\n\n")
                + "Press a section to choose its channel, the role to ping and which launchers to post. "
                + "**Auto-setup** creates the channels and roles for you. Every game is checked by the bot owner first.");
        for (var state : states.values()) embed.addField(state.section().emoji() + " " + state.section().label(), describe(guild, state), false);
        long channels = saved.stream().map(Subscription::channelId).distinct().count();
        Embeds.footer(embed, channels + "/" + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD + " alert channels used");

        List<Button> sections = new ArrayList<>();
        for (var state : states.values()) sections.add(sectionButton(guild, state));
        String id = guild.getId();
        return new Reply(embed.build(), List.of(ActionRow.of(sections), ActionRow.of(
                Button.primary(NAV_PREFIX + ":" + AUTO + ":" + id, "Auto-setup").withEmoji(Emoji.fromUnicode("⚡")),
                Button.secondary(RUN_PREFIX + ":" + ROLE_PANEL + ":" + id, "Post role picker here").withEmoji(Emoji.fromUnicode("🔔")),
                Button.secondary(NAV_PREFIX + ":" + HOME + ":" + id, "Refresh").withEmoji(Emoji.fromUnicode("🔄")))));
    }

    private static String describe(Guild guild, SectionState state) {
        var labels = state.section().stores().stream().map(FreebieStore::label).toList();
        if (!state.on()) return "⚪ Off • " + String.join(", ", labels);
        var text = new StringBuilder();
        for (var entry : state.channels().entrySet()) {
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, entry.getKey());
            boolean ok = channel != null && guild.getSelfMember().hasPermission(channel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            text.append(ok ? "✅ " : "⚠️ ").append("<#").append(entry.getKey()).append(">")
                    .append(ok ? "" : channel == null ? " • **channel missing**" : " • **I can't post here**");
            if (state.channels().size() > 1)
                text.append(" • ").append(String.join(", ", entry.getValue().stream().map(FreebieStore::label).toList()));
            text.append("\n");
        }
        text.append("Pings: ").append(String.join(", ", state.roles().stream()
                .map(role -> role.map(r -> "<@&" + r + ">").orElse("nobody")).toList()));
        var off = state.section().stores().stream().filter(s -> !state.enabled().contains(s)).map(FreebieStore::label).toList();
        if (!off.isEmpty()) text.append("\nNot posted: ").append(String.join(", ", off));
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
                channel, role, Integer.toHexString(mask(state.section(), state.enabled())));
        return Button.of(state.on() ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY, id, state.section().label())
                .withEmoji(Emoji.fromUnicode(state.section().emoji()));
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
        if (args.length != 7) return Optional.empty();
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
        var ticked = mask == 0 ? EnumSet.copyOf(section.stores()) : unmask(section, mask);
        var launchers = StringSelectMenu.create(LAUNCHERS_FIELD).setRequiredRange(0, section.stores().size()).setRequired(false)
                .setPlaceholder("Nothing ticked = section off");
        for (FreebieStore store : section.stores()) launchers.addOption(store.label(), store.id());
        launchers.setDefaultValues(ticked.stream().map(FreebieStore::id).toList());

        return Optional.of(Modal.create(SECTION_MODAL_PREFIX + ":" + args[2] + ":" + section.code(), section.label())
                .addComponents(Label.of("Channel", "Where these free games are posted", channel.build()),
                        Label.of("Role to ping", "Leave empty to post without a ping", role.build()),
                        Label.of("Launchers", "Untick everything to turn this section off", launchers.build()))
                .build());
    }

    // ------------------------------------------------------------------ auto-setup view

    public static Reply autoSetup(String guildId, Set<FreebieSection> chosen) {
        var embed = Embeds.of("⚡", "Auto-setup free-game alerts").setDescription("""
                I'll create a **Free Games** category with read-only alert channels, one pingable role per section, \
                and post a role picker so members choose their own pings. Channels and roles with the same names are \
                reused, so running this again is safe.

                **One channel for everything** puts every chosen section in `#free-games`, each pinging its own role.
                **A channel per section** gives each section its own channel.

                The chosen sections' current settings are replaced; the others are left alone. \
                You need **Manage Roles** and **Manage Channels**, and so do I.""");
        embed.addField("Chosen sections", String.join("\n", Arrays.stream(FreebieSection.values()).filter(chosen::contains)
                .map(s -> s.emoji() + " " + s.label() + " • " + String.join(", ", s.stores().stream().map(FreebieStore::label).toList()))
                .toList()), false);
        var picker = StringSelectMenu.create(AUTO_SELECT_PREFIX + ":" + guildId).setPlaceholder("Sections to set up")
                .setRequiredRange(1, FreebieSection.values().length);
        for (FreebieSection section : FreebieSection.values())
            picker.addOption(section.label(), String.valueOf(section.code()), Emoji.fromUnicode(section.emoji()));
        picker.setDefaultValues(chosen.stream().map(s -> String.valueOf(s.code())).toList());
        String codes = FreebieSection.encode(chosen);
        return new Reply(embed.build(), List.of(ActionRow.of(picker.build()), ActionRow.of(
                Button.success(RUN_PREFIX + ":" + ONE_CHANNEL + ":" + guildId + ":" + codes, "One channel for everything"),
                Button.primary(RUN_PREFIX + ":" + CHANNEL_PER_SECTION + ":" + guildId + ":" + codes, "A channel per section"),
                Button.secondary(NAV_PREFIX + ":" + HOME + ":" + guildId, "Back"))));
    }
}
