package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bunnys.bunnynexus.commands.freebies.FreebieExistingSetup.Found;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Auto-setup asks before using channels and roles the server already made for free games. */
class FreebieExistingSetupTest {
    static final String GUILD = "9223372036854775801";
    final Guild guild = mock(Guild.class);
    final List<TextChannel> channels = new ArrayList<>();
    final List<Role> roles = new ArrayList<>();

    FreebieExistingSetupTest() {
        when(guild.getId()).thenReturn(GUILD);
        when(guild.getTextChannels()).thenAnswer(i -> channels);
        when(guild.getNewsChannels()).thenReturn(List.<NewsChannel>of());
        when(guild.getRoles()).thenAnswer(i -> roles);
    }

    TextChannel channel(String id, String name, String category) {
        var channel = mock(TextChannel.class);
        when(channel.getId()).thenReturn(id); when(channel.getName()).thenReturn(name);
        if (category != null) {
            var parent = mock(Category.class);
            when(parent.getName()).thenReturn(category);
            when(channel.getParentCategory()).thenReturn(parent);
        }
        channels.add(channel);
        return channel;
    }

    Role role(String id, String name, boolean managed) {
        var role = mock(Role.class);
        when(role.getId()).thenReturn(id); when(role.getName()).thenReturn(name); when(role.isManaged()).thenReturn(managed);
        roles.add(role);
        return role;
    }

    @Test void launcherNamesMatchWholeWordsOnly() {
        assertEquals(Optional.of(FreebieStore.EPIC), FreebieExistingSetup.launcher("epic-games"));
        assertEquals(Optional.of(FreebieStore.EPIC), FreebieExistingSetup.launcher("Epic Games"));
        assertEquals(Optional.of(FreebieStore.STEAM), FreebieExistingSetup.launcher("🎮・steam-deals"));
        assertEquals(Optional.of(FreebieStore.PLAYSTATION), FreebieExistingSetup.launcher("ps-plus"));
        assertEquals(Optional.of(FreebieStore.SWITCH), FreebieExistingSetup.launcher("NintendoSwitch"));
        assertEquals(Optional.of(FreebieStore.EA), FreebieExistingSetup.launcher("ea-app"));
        assertEquals(Optional.empty(), FreebieExistingSetup.launcher("teams"));
        assertEquals(Optional.empty(), FreebieExistingSetup.launcher("general"));
        assertEquals(Optional.empty(), FreebieExistingSetup.launcher("beach"), "no substring match on short keywords");
        assertEquals(Optional.empty(), FreebieExistingSetup.launcher("epic-and-steam"), "ambiguous names are not guessed");
        assertTrue(FreebieExistingSetup.generic("free-games"));
        assertTrue(FreebieExistingSetup.generic("Freebies"));
        assertFalse(FreebieExistingSetup.generic("games"));
    }

    @Test void scanOffersMatchingChannelsAndRolesForTheChosenSectionsOnly() {
        channel("1", "epic-games", null);
        channel("2", "steam", null);
        channel("3", "xbox", null);                       // Console not chosen.
        channel("4", "free-pc-games", "Free Games");      // Auto-setup's own channel, reused anyway.
        channel("5", "freebies", null);
        channel("6", "general", null);
        role("7", "Steam", false);
        role("8", "Steam bot", true);                     // Integration roles are never offered.
        role("9", "Free Games: Major PC", false);         // Auto-setup's own role.
        role("10", "Free Games", false);
        var found = FreebieExistingSetup.scan(guild, EnumSet.of(FreebieSection.MAJOR_PC), false);
        assertEquals(List.of(new Found(true, Optional.of(FreebieStore.EPIC), "1", "epic-games"),
                new Found(true, Optional.of(FreebieStore.STEAM), "2", "steam"),
                new Found(false, Optional.of(FreebieStore.STEAM), "7", "Steam"),
                new Found(true, Optional.empty(), "5", "freebies"),
                new Found(false, Optional.empty(), "10", "Free Games")), found);
        // A shared channel only makes sense for "one channel for everything".
        assertTrue(FreebieExistingSetup.scan(guild, EnumSet.of(FreebieSection.MAJOR_PC), true).stream()
                .noneMatch(f -> f.channel() && f.store().isEmpty()));
        assertTrue(FreebieExistingSetup.scan(guild, EnumSet.of(FreebieSection.MOBILE), true).stream()
                .allMatch(f -> f.store().isEmpty()));
    }

    @Test void answersAreBoundToTheExactScan() {
        channel("1", "epic-games", null);
        role("7", "Steam", false);
        var found = FreebieExistingSetup.scan(guild, EnumSet.of(FreebieSection.MAJOR_PC), true);
        String print = FreebieExistingSetup.fingerprint(found);
        assertEquals(Optional.of(Set.of(1)), FreebieExistingSetup.answer("2." + print, found));
        assertEquals(Optional.of(Set.of()), FreebieExistingSetup.answer("0." + print, found), "create everything new");
        assertTrue(FreebieExistingSetup.answer("3.wrong", found).isEmpty(), "scan changed: ask again");
        assertTrue(FreebieExistingSetup.answer("4." + print, found).isEmpty(), "index beyond the scan");
        assertTrue(FreebieExistingSetup.answer("!!." + print, found).isEmpty());
        channel("2", "steam", null);
        var rescanned = FreebieExistingSetup.scan(guild, EnumSet.of(FreebieSection.MAJOR_PC), true);
        assertNotEquals(print, FreebieExistingSetup.fingerprint(rescanned));
        assertEquals(List.of(found.getFirst()), FreebieExistingSetup.select(found, Set.of(0)));
        assertEquals(Set.of(0, 3, 24), FreebieExistingSetup.decode(FreebieExistingSetup.encode(Set.of(0, 3, 24))).orElseThrow());
    }

    @Test void promptListsEveryFindingAndFitsDiscordLimits() {
        for (int i = 0; i < 30; i++) channel(String.valueOf(1000 + i), "steam-" + i, null);
        for (FreebieStore store : FreebieStore.values()) {
            channel(String.valueOf(2000 + store.ordinal()), store.id() + "-games", null);
            role(String.valueOf(3000 + store.ordinal()), store.label(), false);
        }
        var sections = EnumSet.allOf(FreebieSection.class);
        var found = FreebieExistingSetup.scan(guild, sections, false);
        assertTrue(found.size() <= 25);
        var prompt = FreebieExistingSetup.prompt(GUILD, sections, FreebieDashboard.ONE_CHANNEL, found, FreebieExistingSetup.all(found), null);
        assertTrue(prompt.embed().getDescription().length() <= 4096);
        var menu = prompt.rows().get(0).getComponents().getFirst().asStringSelectMenu();
        assertTrue(menu.getCustomId().length() <= 100);
        assertEquals(found.size(), menu.getOptions().size());
        for (var button : prompt.rows().get(1).getComponents())
            if (button.asButton().getCustomId() != null) assertTrue(button.asButton().getCustomId().length() <= 100);
    }

    @Test void reusedChannelsCountTowardsTheChannelLimit() {
        var saved = List.of(new Subscription(GUILD, "9223372036854775805", FreebieStore.ANDROID, Optional.empty()));
        // Mobile's channel kept, two existing launcher channels, one new section channel.
        assertEquals(4, FreebieAutoSetup.channelsAfterAutoSetup(saved, EnumSet.of(FreebieSection.MAJOR_PC), Set.of("1", "2"), 1));
        assertEquals(2, FreebieAutoSetup.channelsAfterAutoSetup(saved, EnumSet.of(FreebieSection.MAJOR_PC), Set.of("9223372036854775805"), 1),
                "a channel already in use is not counted twice");
    }

    @Test void rolePickerLabelsLauncherRolesByLauncher() {
        var steam = mock(Role.class); when(steam.getId()).thenReturn("7");
        var all = mock(Role.class); when(all.getId()).thenReturn("8");
        Map<Role, Set<FreebieStore>> roles = new LinkedHashMap<>();
        roles.put(steam, EnumSet.of(FreebieStore.STEAM));
        roles.put(all, EnumSet.copyOf(FreebieSection.MAJOR_PC.stores()));
        try (var panel = FreebieServerSetup.rolePanel(roles)) {
            assertFalse(panel.getAllowedMentions().contains(Message.MentionType.ROLE));
            var buttons = panel.getComponents().getFirst().asActionRow().getComponents();
            assertEquals("Steam", buttons.get(0).asButton().getLabel());
            assertEquals(FreebieSection.MAJOR_PC.label(), buttons.get(1).asButton().getLabel());
        }
    }

    @Test void autoSetupUsesThePickedChannelsAndRolesWithoutCreatingAnything() {
        var self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        var actor = mock(net.dv8tion.jda.api.entities.Member.class);
        var everyone = mock(Role.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(guild.getChannels(true)).thenReturn(List.of());
        when(actor.getId()).thenReturn("42");
        when(actor.hasPermission(org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.Permission[].class))).thenReturn(true);
        when(self.hasPermission(org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.Permission[].class))).thenReturn(true);
        when(self.hasPermission(org.mockito.ArgumentMatchers.<Collection<net.dv8tion.jda.api.Permission>>any())).thenReturn(true);
        when(self.hasPermission(org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class),
                org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.Permission[].class))).thenReturn(true);
        when(self.canInteract(org.mockito.ArgumentMatchers.any(Role.class))).thenReturn(true);
        var epic = channel("1", "epic-games", null);
        var shared = channel("5", "freebies", null);
        var steamRole = role("7", "Steam", false);
        var allRole = role("10", "Free Games", false);
        // Ids read before stubbing: a mock call inside when(...) would break Mockito's stubbing.
        for (var entry : Map.of("1", epic, "5", shared).entrySet()) {
            var c = entry.getValue();
            when(guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel.class, entry.getKey())).thenReturn(c);
            when(c.getAsMention()).thenReturn("<#" + entry.getKey() + ">");
        }
        for (var entry : Map.of("7", steamRole, "10", allRole).entrySet()) {
            var r = entry.getValue();
            when(guild.getRoleById(entry.getKey())).thenReturn(r);
            when(r.getGuild()).thenReturn(guild); when(r.isMentionable()).thenReturn(true);
            when(r.getAsMention()).thenReturn("<@&" + entry.getKey() + ">");
        }
        var post = mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class);
        when(epic.sendMessage(org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.utils.messages.MessageCreateData.class))).thenReturn(post);
        when(shared.sendMessage(org.mockito.ArgumentMatchers.any(net.dv8tion.jda.api.utils.messages.MessageCreateData.class))).thenReturn(post);
        var subscriptions = mock(org.bunnys.bunnynexus.freebies.FreebieSubscriptions.class);
        when(subscriptions.setSection(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.bunnys.bunnynexus.freebies.FreebieSubscriptions.SaveResult.UPDATED);
        when(subscriptions.save(any(), any(), any())).thenReturn(org.bunnys.bunnynexus.freebies.FreebieSubscriptions.SaveResult.CREATED);

        var sections = EnumSet.of(FreebieSection.MAJOR_PC);
        var found = FreebieExistingSetup.scan(guild, sections, false);
        String notice = FreebieAutoSetup.run(guild, actor, sections, false, subscriptions, found);

        assertTrue(notice.startsWith("✅"), notice);
        var remaining = EnumSet.copyOf(FreebieSection.MAJOR_PC.stores());
        remaining.remove(FreebieStore.EPIC);
        verify(subscriptions).setSection(eq(GUILD), eq(FreebieSection.MAJOR_PC), eq("5"), eq(remaining), eq(Optional.of("10")), eq("42"), any());
        verify(subscriptions).save(eq(new Subscription(GUILD, "5", FreebieStore.STEAM, Optional.of("7"))), eq("42"), any());
        verify(subscriptions).save(eq(new Subscription(GUILD, "1", FreebieStore.EPIC, Optional.of("10"))), eq("42"), any());
        verify(guild, never()).createCategory(any());
        verify(guild, never()).createRole();
        verify(post).complete(); // Role picker posted once.
    }
}
