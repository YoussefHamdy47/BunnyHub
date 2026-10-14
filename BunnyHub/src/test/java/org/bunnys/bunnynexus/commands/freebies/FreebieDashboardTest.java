package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieDashboardTest {
    static final String GUILD = "9223372036854775801", CHANNEL = "9223372036854775802", ROLE = "9223372036854775803";

    static Guild guild() {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD);
        when(guild.getGuildChannelById(CHANNEL)).thenReturn(mock(GuildChannel.class));
        when(guild.getRoleById(ROLE)).thenReturn(mock(Role.class));
        return guild;
    }

    @Test void statesGroupLaunchersBySectionAndChannel() {
        var states = FreebieDashboard.states(List.of(
                new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(ROLE)),
                new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.of(ROLE)),
                new Subscription(GUILD, "9223372036854775804", FreebieStore.GOG, Optional.empty()),
                new Subscription(GUILD, CHANNEL, FreebieStore.ANDROID, Optional.of(ROLE))));
        var major = states.get(FreebieSection.MAJOR_PC);
        assertTrue(major.on());
        assertEquals(EnumSet.of(FreebieStore.EPIC, FreebieStore.STEAM, FreebieStore.GOG), major.enabled());
        assertEquals(Optional.of(CHANNEL), major.mainChannel());
        assertEquals(Optional.empty(), major.role(), "mixed roles have no single default");
        assertEquals(Optional.of(ROLE), states.get(FreebieSection.MOBILE).role());
        assertFalse(states.get(FreebieSection.CONSOLE).on());
    }

    @Test void sectionButtonCarriesItsStateIntoTheForm() {
        var state = FreebieDashboard.states(List.of(new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.of(ROLE)),
                new Subscription(GUILD, CHANNEL, FreebieStore.BATTLENET, Optional.of(ROLE)))).get(FreebieSection.MAJOR_PC);
        var button = FreebieDashboard.sectionButton(guild(), state);
        String id = button.getCustomId();
        assertTrue(id.length() <= 100, id.length() + " chars");
        String[] args = id.split(":", -1);
        assertEquals(List.of(FreebieDashboard.NAV_PREFIX, FreebieDashboard.OPEN_SECTION, GUILD, "m", CHANNEL, ROLE), List.of(args).subList(0, 6));

        var form = FreebieDashboard.sectionForm(args).orElseThrow();
        assertEquals(FreebieDashboard.SECTION_MODAL_PREFIX + ":" + GUILD + ":m", form.getId());
        var labels = form.getComponents().stream().map(c -> (Label) c).toList();
        assertEquals(5, labels.size(), "Discord's limit is five");
        var reminder = labels.get(3).getChild().asStringSelectMenu();
        assertEquals(FreebieDashboard.REMINDER_FIELD, reminder.getCustomId());
        assertEquals(Set.of("0"), ticked(reminder.getOptions()), "existing settings without a value mean off");
        var channel = labels.get(0).getChild().asEntitySelectMenu();
        assertEquals(CHANNEL, channel.getDefaultValues().getFirst().getId());
        assertEquals(ROLE, labels.get(1).getChild().asEntitySelectMenu().getDefaultValues().getFirst().getId());
        var launchers = labels.get(2).getChild().asStringSelectMenu();
        assertEquals(0, launchers.getMinValues());
        assertEquals(Set.of("steam", "battlenet"), ticked(launchers.getOptions()));
    }

    @Test void anOffSectionOpensWithEveryLauncherTickedAndNoDefaults() {
        var state = FreebieDashboard.states(List.of()).get(FreebieSection.CONSOLE);
        String[] args = FreebieDashboard.sectionButton(guild(), state).getCustomId().split(":", -1);
        assertEquals("-", args[4]);
        assertEquals("-", args[5]);
        var labels = FreebieDashboard.sectionForm(args).orElseThrow().getComponents().stream().map(c -> (Label) c).toList();
        assertTrue(labels.get(0).getChild().asEntitySelectMenu().getDefaultValues().isEmpty());
        assertEquals(Set.of("playstation", "xbox", "switch"), ticked(labels.get(2).getChild().asStringSelectMenu().getOptions()));
    }

    @Test void malformedSectionIdsAreRefused() {
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "z", "-", "-", "0"}).isEmpty());
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "-", "-", "zz"}).isEmpty());
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD}).isEmpty());
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "99999999999999999999", "-", "1"}).isEmpty());
    }

    @Test void theSectionReminderRidesInTheButtonAndDefaultsTheForm() {
        var state = FreebieDashboard.states(List.of(new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.of(ROLE), 24),
                new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(ROLE), 24))).get(FreebieSection.MAJOR_PC);
        assertEquals(24, state.reminder());
        String id = FreebieDashboard.sectionButton(guild(), state).getCustomId();
        assertTrue(id.length() <= 100, id.length() + " chars");
        String[] args = id.split(":", -1);
        assertEquals("24", args[7]);
        var labels = FreebieDashboard.sectionForm(args).orElseThrow().getComponents().stream().map(c -> (Label) c).toList();
        assertEquals(Set.of("24"), ticked(labels.get(3).getChild().asStringSelectMenu().getOptions()));
        assertEquals(List.of("0", "24", "6"), labels.get(3).getChild().asStringSelectMenu().getOptions().stream()
                .map(o -> o.getValue()).toList());

        var mixed = FreebieDashboard.states(List.of(new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.empty(), 24),
                new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.empty(), 6))).get(FreebieSection.MAJOR_PC);
        assertEquals(0, mixed.reminder(), "a section that disagrees opens as off");
    }

    @Test void olderDashboardButtonsStillOpenAndUnknownRemindersAreRefused() {
        var legacy = FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "-", "-", "1"}).orElseThrow();
        var labels = legacy.getComponents().stream().map(c -> (Label) c).toList();
        assertEquals(Set.of("0"), ticked(labels.get(3).getChild().asStringSelectMenu().getOptions()));
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "-", "-", "1", "12"}).isEmpty());
        assertTrue(FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "-", "-", "1", "x"}).isEmpty());
    }

    @Test void launcherPingsKeepTheirOwnRolesThroughTheSectionForm() {
        String epicRole = "9223372036854775805";
        var saved = List.of(new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(epicRole)),
                new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.of(ROLE)));
        var state = FreebieDashboard.states(saved).get(FreebieSection.MAJOR_PC);
        assertTrue(state.mixedPings());
        String[] args = FreebieDashboard.sectionButton(guild(), state).getCustomId().split(":", -1);
        assertEquals(FreebieDashboard.MIXED, args[5]);
        var form = FreebieDashboard.sectionForm(args).orElseThrow();
        assertEquals(FreebieDashboard.SECTION_MODAL_PREFIX + ":" + GUILD + ":m:k", form.getId(), "empty role = keep the pings");
        assertTrue(((Label) form.getComponents().get(1)).getChild().asEntitySelectMenu().getDefaultValues().isEmpty());
        assertEquals(Map.of(FreebieStore.EPIC, Set.of(Optional.of(epicRole)), FreebieStore.STEAM, Set.of(Optional.of(ROLE))),
                FreebieDashboard.launcherPings(saved));
    }

    @Test void pingsViewOffersEveryEnabledLauncherAndOpensItsForm() {
        var guild = guild();
        var view = FreebieDashboard.pings(guild, List.of(new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(ROLE)),
                new Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.empty())), null);
        assertTrue(view.embed().getDescription().contains("**Epic Games Store** · <@&" + ROLE + ">"), view.embed().getDescription());
        assertTrue(view.embed().getDescription().contains("**Steam** · nobody"));
        var menu = view.rows().getFirst().getComponents().getFirst().asStringSelectMenu();
        assertEquals(FreebieDashboard.PING_SELECT_PREFIX + ":" + GUILD, menu.getCustomId());
        assertEquals(List.of("epic:" + ROLE, "steam:-"), menu.getOptions().stream().map(SelectOption::getValue).toList());

        var form = FreebieDashboard.pingForm(GUILD, "epic:" + ROLE).orElseThrow();
        assertEquals(FreebieDashboard.PING_MODAL_PREFIX + ":" + GUILD + ":epic", form.getId());
        assertEquals(ROLE, ((Label) form.getComponents().getFirst()).getChild().asEntitySelectMenu().getDefaultValues().getFirst().getId());
        assertTrue(FreebieDashboard.pingForm(GUILD, "nope:-").isEmpty());
        assertTrue(FreebieDashboard.pingForm(GUILD, "epic").isEmpty());
        assertEquals(1, FreebieDashboard.pings(guild, List.of(), null).rows().size(), "nothing on: just Back");
    }

    @Test void quickSetupAsksForEverythingInOneForm() {
        var form = FreebieDashboard.quickForm(GUILD);
        assertEquals(FreebieDashboard.QUICK_MODAL_PREFIX + ":" + GUILD, form.getId());
        var labels = form.getComponents().stream().map(c -> (Label) c).toList();
        assertEquals(4, labels.size());
        assertEquals(Set.of("m"), ticked(labels.get(1).getChild().asStringSelectMenu().getOptions()), "major PC by default");
        assertEquals(Set.of("y"), ticked(labels.get(3).getChild().asStringSelectMenu().getOptions()));
    }

    @Test void dashboardLeadsWithQuickSetupAndHelp() {
        var guild = guild();
        when(guild.getSelfMember()).thenReturn(mock(net.dv8tion.jda.api.entities.SelfMember.class));
        var empty = FreebieDashboard.dashboard(guild, List.of(), null);
        var row = empty.rows().get(1).getButtons();
        assertEquals(List.of("Quick setup", "Create channels for me", "Launcher pings", "Post role picker", "Help"),
                row.stream().map(b -> b.getLabel()).toList());
        assertEquals(net.dv8tion.jda.api.components.buttons.ButtonStyle.PRIMARY, row.getFirst().getStyle());
        assertTrue(row.get(2).isDisabled(), "no launchers to ping yet");
        var guide = FreebieGuide.embed(Set.of(FreebieStore.EPIC)).getFields().stream().map(f -> f.getValue()).toList();
        assertTrue(String.join("\n", guide).contains("**Epic Games Store** games are posted as soon as they are found"), guide.toString());
    }

    @Test void masksRoundTripEverySubset() {
        for (FreebieSection section : FreebieSection.values())
            for (int mask = 0; mask < 1 << section.stores().size(); mask++)
                assertEquals(mask, FreebieDashboard.mask(section, FreebieDashboard.unmask(section, mask)));
    }

    @Test void autoSetupControlsCarryTheChosenSections() {
        var view = FreebieDashboard.autoSetup(GUILD, EnumSet.of(FreebieSection.MAJOR_PC, FreebieSection.CONSOLE));
        var buttons = view.rows().get(1).getComponents().stream().map(c -> c.asButton().getCustomId()).toList();
        assertEquals(FreebieDashboard.RUN_PREFIX + ":one:" + GUILD + ":mc", buttons.get(0));
        assertEquals(FreebieDashboard.RUN_PREFIX + ":split:" + GUILD + ":mc", buttons.get(1));
        var picker = view.rows().getFirst().getComponents().getFirst().asStringSelectMenu();
        assertEquals(Set.of("m", "c"), ticked(picker.getOptions()));
        for (var row : view.rows()) for (var c : row.getComponents())
            if (c instanceof net.dv8tion.jda.api.components.attribute.ICustomId custom) assertTrue(custom.getCustomId().length() <= 100);
    }

    @Test void onlyRolesThatGrantNothingCanBeSelfAssigned() {
        long everyonePerms = Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
        var guild = mock(Guild.class);
        var publicRole = mock(Role.class);
        when(publicRole.getPermissionsRaw()).thenReturn(everyonePerms);
        when(guild.getPublicRole()).thenReturn(publicRole);
        var gatedChannel = mock(TextChannel.class);
        when(guild.getChannels(true)).thenReturn(List.of(gatedChannel));
        var self = mock(Member.class);

        var safe = role(guild, self, 0L);
        var likeEveryone = role(guild, self, everyonePerms); // roles made in Discord's UI carry the defaults
        var moderator = role(guild, self, Permission.MESSAGE_MANAGE.getRawValue());
        // No permissions of its own, but it opens a private channel: a verification gate must never be self-service.
        var gate = role(guild, self, 0L);
        var override = mock(PermissionOverride.class);
        when(override.getAllowedRaw()).thenReturn(Permission.VIEW_CHANNEL.getRawValue());
        when(gatedChannel.getPermissionOverride(gate)).thenReturn(override);
        var aboveBot = role(guild, self, 0L);
        when(self.canInteract(aboveBot)).thenReturn(false);
        var everyone = role(guild, self, 0L);
        when(everyone.isPublicRole()).thenReturn(true);

        assertTrue(FreebieServerSetup.selfAssignable(safe, self));
        assertTrue(FreebieServerSetup.selfAssignable(likeEveryone, self));
        assertFalse(FreebieServerSetup.selfAssignable(moderator, self));
        assertFalse(FreebieServerSetup.selfAssignable(gate, self));
        assertFalse(FreebieServerSetup.selfAssignable(aboveBot, self));
        assertFalse(FreebieServerSetup.selfAssignable(everyone, self));
    }

    private static Role role(Guild guild, Member self, long permissions) {
        var role = mock(Role.class);
        when(role.getGuild()).thenReturn(guild);
        when(role.getPermissionsRaw()).thenReturn(permissions);
        when(self.canInteract(role)).thenReturn(true);
        return role;
    }

    @Test void autoSetupCountsChannelsBeforeCreatingAnything() {
        var saved = List.of(new Subscription(GUILD, "9223372036854775804", FreebieStore.EPIC, Optional.empty()),
                new Subscription(GUILD, "9223372036854775805", FreebieStore.PLAYSTATION, Optional.empty()),
                new Subscription(GUILD, "9223372036854775806", FreebieStore.ANDROID, Optional.empty()));
        var pcAndConsole = EnumSet.of(FreebieSection.MAJOR_PC, FreebieSection.CONSOLE);
        // Their current channels are replaced; mobile's is kept.
        assertEquals(3, FreebieAutoSetup.channelsAfterAutoSetup(saved, pcAndConsole, true));
        assertEquals(2, FreebieAutoSetup.channelsAfterAutoSetup(saved, pcAndConsole, false));
        assertEquals(4, FreebieAutoSetup.channelsAfterAutoSetup(List.of(), EnumSet.allOf(FreebieSection.class), true));
    }

    @Test void rolePickerNeverPingsAndMergesSharedRoles() {
        var shared = mock(Role.class);
        when(shared.getId()).thenReturn(ROLE);
        var roles = new EnumMap<FreebieSection, Role>(FreebieSection.class);
        for (FreebieSection section : FreebieSection.values()) roles.put(section, shared);
        try (var panel = FreebieServerSetup.sectionRolePanel(roles)) {
            assertTrue(panel.getMentionedRoles().isEmpty());
            assertFalse(panel.getAllowedMentions().contains(Message.MentionType.ROLE));
            var buttons = panel.getComponents().getFirst().asActionRow().getComponents();
            assertEquals(1, buttons.size());
            assertEquals(FreebieServerSetup.ROLE_PREFIX + ":" + ROLE, buttons.getFirst().asButton().getCustomId());
            assertEquals("All free games", buttons.getFirst().asButton().getLabel());
        }
        assertTrue(FreebieServerSetup.isPingRole(ROLE, List.of(new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(ROLE)))));
        assertFalse(FreebieServerSetup.isPingRole(ROLE, List.of(new Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.empty()))));
    }

    private static Set<String> ticked(List<SelectOption> options) {
        Set<String> values = new HashSet<>();
        for (var option : options) if (option.isDefault()) values.add(option.getValue());
        return values;
    }
}
