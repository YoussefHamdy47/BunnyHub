package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import java.util.*;

/**
 * The forms behind the {@link FreebieDashboard} buttons: a section's settings and quick setup. Opening a form
 * can't wait for the database, so each is built from what the button id carries; the submission is re-checked
 * by its handler in {@code modals}.
 */
public final class FreebieDashboardForms {
    private FreebieDashboardForms() {}

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
        var channel = EntitySelectMenu.create(FreebieDashboard.CHANNEL_FIELD, SelectTarget.CHANNEL).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
                .setRequiredRange(0, 1).setRequired(false).setPlaceholder("Pick a channel");
        var role = EntitySelectMenu.create(FreebieDashboard.ROLE_FIELD, SelectTarget.ROLE).setRequiredRange(0, 1).setRequired(false)
                .setPlaceholder("No ping");
        try {
            mask = Integer.parseInt(args[6], 16);
            if (!FreebieDashboard.NONE.equals(args[4])) channel.setDefaultValues(DefaultValue.channel(args[4]));
            if (!FreebieDashboard.NONE.equals(args[5]) && !FreebieDashboard.MIXED.equals(args[5])) role.setDefaultValues(DefaultValue.role(args[5]));
        } catch (NumberFormatException malformed) { return Optional.empty(); }
        String reminderNow = args.length == 8 ? args[7] : "0";
        if (!FreebieSubscriptions.REMINDER_CHOICES.contains(parse(reminderNow))) return Optional.empty();
        var reminder = StringSelectMenu.create(FreebieDashboard.REMINDER_FIELD).setRequiredRange(1, 1)
                .addOption("Off", "0", "No reminder")
                .addOption("24 hours before", "24", "A short reply to the alert a day before a game ends")
                .addOption("6 hours before", "6", "A short reply to the alert 6 hours before a game ends")
                .setDefaultValues(reminderNow);
        var post = StringSelectMenu.create(FreebieDashboard.POST_FIELD).setRequiredRange(1, 1);
        for (FreebieCatchUp choice : FreebieCatchUp.values()) post.addOption(choice.option(), String.valueOf(choice.code()));
        post.setDefaultValues(String.valueOf(FreebieCatchUp.AUTO.code()));
        var ticked = mask == 0 ? EnumSet.copyOf(section.stores()) : FreebieDashboard.unmask(section, mask);
        var launchers = StringSelectMenu.create(FreebieDashboard.LAUNCHERS_FIELD).setRequiredRange(0, section.stores().size()).setRequired(false)
                .setPlaceholder("Nothing ticked = section off");
        for (FreebieStore store : section.stores()) launchers.addOption(store.label(), store.id());
        launchers.setDefaultValues(ticked.stream().map(FreebieStore::id).toList());

        boolean mixed = FreebieDashboard.MIXED.equals(args[5]);
        if (mixed) role.setPlaceholder("Keep each launcher's own ping");
        // ":k" tells the form handler that an empty role means "keep the launcher pings", not "no ping".
        return Optional.of(Modal.create(FreebieDashboard.SECTION_MODAL_PREFIX + ":" + args[2] + ":" + section.code() + (mixed ? ":k" : ""), section.label())
                .addComponents(Label.of("Channel", "Where these free games are posted", channel.build()),
                        Label.of("Role to ping", mixed ? "Launchers have their own pings. Pick one role to replace them all."
                                : "Leave empty to post without a ping", role.build()),
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

    /** One channel, the sections to post there and an optional ping: everything a first setup needs. */
    public static Modal quickForm(String guildId) {
        var channel = EntitySelectMenu.create(FreebieDashboard.CHANNEL_FIELD, SelectTarget.CHANNEL).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
                .setRequiredRange(1, 1).setPlaceholder("Pick a channel");
        var sections = StringSelectMenu.create(FreebieDashboard.SECTIONS_FIELD).setRequiredRange(1, FreebieSection.values().length);
        for (FreebieSection section : FreebieSection.values())
            sections.addOption(section.label(), String.valueOf(section.code()),
                    String.join(", ", section.stores().stream().map(FreebieStore::label).toList()));
        sections.setDefaultValues(String.valueOf(FreebieSection.MAJOR_PC.code()));
        var role = EntitySelectMenu.create(FreebieDashboard.ROLE_FIELD, SelectTarget.ROLE).setRequiredRange(0, 1).setRequired(false)
                .setPlaceholder("No ping");
        var post = StringSelectMenu.create(FreebieDashboard.POST_FIELD).setRequiredRange(1, 1)
                .addOption("Yes, post them now", String.valueOf(FreebieCatchUp.ON.code()))
                .addOption("No, only new games", String.valueOf(FreebieCatchUp.OFF.code()))
                .setDefaultValues(String.valueOf(FreebieCatchUp.ON.code()));
        return Modal.create(FreebieDashboard.QUICK_MODAL_PREFIX + ":" + guildId, "Quick setup")
                .addComponents(Label.of("Channel", "Where free games are posted", channel.build()),
                        Label.of("What to post", "You can fine-tune each launcher later", sections.build()),
                        Label.of("Role to ping", "Optional. Per-launcher pings are under Launcher pings", role.build()),
                        Label.of("Games that are free right now", "Post them in the channel straight away?", post.build()))
                .build();
    }
}
