package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.entities.Guild;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** "Post current games now": defaults, how it travels in controls, and that it uses the one catch-up path. */
class FreebieCatchUpTest {
    static final String GUILD = "100000000000000000", NEW = "200000000000000001", OLD = "200000000000000002";

    @Test void theDefaultPostsForANewChannelAndNotWhenOnlyEditing() {
        assertTrue(FreebieCatchUp.AUTO.resolve(true));
        assertFalse(FreebieCatchUp.AUTO.resolve(false));
        assertTrue(FreebieCatchUp.ON.resolve(false));
        assertFalse(FreebieCatchUp.OFF.resolve(true));
        assertEquals(FreebieCatchUp.AUTO, FreebieCatchUp.of(null), "older controls and forms mean the default");
        assertEquals(FreebieCatchUp.AUTO, FreebieCatchUp.of("zz"));
        assertEquals(FreebieCatchUp.OFF, FreebieCatchUp.of("n"));
        assertEquals(List.of(FreebieCatchUp.OFF, FreebieCatchUp.ON, FreebieCatchUp.AUTO),
                List.of(FreebieCatchUp.AUTO.next(), FreebieCatchUp.OFF.next(), FreebieCatchUp.ON.next()));
    }

    @Test void theChoiceRidesWithTheSectionsWithoutBreakingOlderIds() {
        var sections = EnumSet.of(FreebieSection.MAJOR_PC, FreebieSection.CONSOLE);
        assertEquals("mc", FreebieCatchUp.token(sections, FreebieCatchUp.AUTO), "the default leaves ids as they were");
        String off = FreebieCatchUp.token(sections, FreebieCatchUp.OFF);
        assertEquals("mc!n", off);
        assertEquals(sections, FreebieCatchUp.sectionsOf(off));
        assertEquals(FreebieCatchUp.OFF, FreebieCatchUp.choiceOf(off));
        assertEquals(FreebieCatchUp.AUTO, FreebieCatchUp.choiceOf("mc"));
        assertEquals(sections, FreebieCatchUp.sectionsOf("mc"));
    }

    @Test void theSectionFormOffersTheChoiceDefaultingToNewChannelsOnly() {
        var form = FreebieDashboard.sectionForm(new String[]{"freebie_dash", "s", GUILD, "m", "-", "-", "1", "0"}).orElseThrow();
        var labels = form.getComponents().stream().map(c -> (Label) c).toList();
        var post = labels.get(4).getChild().asStringSelectMenu();
        assertEquals(FreebieDashboard.POST_FIELD, post.getCustomId());
        assertEquals(List.of("a", "y", "n"), post.getOptions().stream().map(SelectOption::getValue).toList());
        assertEquals(List.of("a"), post.getOptions().stream().filter(SelectOption::isDefault).map(SelectOption::getValue).toList());
    }

    static List<Button> buttons(FreebieSettingsCommands.Reply reply) {
        List<Button> buttons = new ArrayList<>();
        for (var row : reply.rows()) if (row instanceof ActionRow r) r.getComponents().forEach(c -> { if (c instanceof Button b) buttons.add(b); });
        return buttons;
    }

    @Test void autoSetupCarriesTheChoiceOnItsRunButtonsAndToggles() {
        var sections = EnumSet.of(FreebieSection.MAJOR_PC);
        var view = FreebieDashboard.autoSetup(GUILD, sections);
        var ids = buttons(view).stream().map(Button::getCustomId).toList();
        assertTrue(ids.contains("freebie_dashrun:one:" + GUILD + ":m"), ids.toString());
        assertTrue(ids.contains("freebie_dash:auto:" + GUILD + ":m!n"), "the toggle flips to off");
        var off = FreebieDashboard.autoSetup(GUILD, sections, FreebieCatchUp.OFF);
        var offIds = buttons(off).stream().map(Button::getCustomId).toList();
        assertTrue(offIds.contains("freebie_dashrun:split:" + GUILD + ":m!n"), offIds.toString());
        assertTrue(buttons(off).stream().anyMatch(b -> b.getLabel().equals(FreebieCatchUp.OFF.toggle())));
        assertTrue(offIds.stream().allMatch(id -> id.length() <= 100));
    }

    @Test void autoSetupPostsOnlyInChannelsNewToTheSectionThroughTheSharedPath() {
        var system = mock(FreebieSystem.class);
        when(system.catchUpChannel(eq(GUILD), any(), any())).thenReturn(new FreebieSystem.CatchUp(3, 2));
        var guild = mock(Guild.class); when(guild.getId()).thenReturn(GUILD);
        // OLD already carried a major PC launcher; NEW gets the section for the first time.
        var before = List.of(new Subscription(GUILD, OLD, FreebieStore.STEAM, Optional.empty()));
        Map<String, Set<FreebieStore>> placed = new LinkedHashMap<>();
        placed.put(NEW, EnumSet.of(FreebieStore.EPIC));
        placed.put(OLD, EnumSet.of(FreebieStore.GOG));
        String notice = FreebieAutoSetup.catchUp(guild, before, placed, FreebieCatchUp.AUTO, system);
        verify(system).catchUpChannel(eq(GUILD), eq(NEW), any());
        verify(system, never()).catchUpChannel(eq(GUILD), eq(OLD), any());
        assertTrue(notice.contains("Posting 2 current free game(s)"), notice);

        reset(system);
        when(system.catchUpChannel(eq(GUILD), any(), any())).thenReturn(new FreebieSystem.CatchUp(0, 0));
        assertTrue(FreebieAutoSetup.catchUp(guild, before, placed, FreebieCatchUp.ON, system).contains("None of the approved games are live"));
        verify(system, times(2)).catchUpChannel(eq(GUILD), any(), any());
        reset(system);
        assertEquals("", FreebieAutoSetup.catchUp(guild, before, placed, FreebieCatchUp.OFF, system));
        verifyNoInteractions(system);
    }

    @Test void theSaveReplySaysWhatWasQueued() {
        assertEquals("📤 Posting 2 current free game(s) in <#1> shortly.", FreebieCatchUp.reply(new FreebieSystem.CatchUp(4, 2), "<#1>"));
        assertEquals("None of the approved games are live right now.", FreebieCatchUp.reply(new FreebieSystem.CatchUp(0, 0), "<#1>"));
        assertEquals("<#1> already has the current games.", FreebieCatchUp.reply(new FreebieSystem.CatchUp(2, 0), "<#1>"));
    }
}
