package org.bunnys.commands;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.bunnys.bunnynexus.commands.info.AvatarCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.AppDesign;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarTest {
    private User user() {
        var user = mock(User.class);
        when(user.getId()).thenReturn("123");
        when(user.getEffectiveName()).thenReturn("Bunny");
        when(user.getEffectiveAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/avatars/123/a_avatar.gif"));
        return user;
    }

    private Member withServerAvatar() {
        var member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Server Bunny");
        when(member.getAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/server.gif"));
        return member;
    }

    static Container card(MessageCreateData view) {
        assertTrue(view.isUsingComponentsV2());
        assertTrue(view.getEmbeds().isEmpty());
        return (Container) view.getComponents().getFirst();
    }
    static List<String> texts(MessageCreateData view) {
        return card(view).getComponents().stream().filter(c -> c instanceof TextDisplay).map(c -> ((TextDisplay) c).getContent()).toList();
    }
    static String image(MessageCreateData view) {
        return card(view).getComponents().stream().filter(c -> c instanceof MediaGallery).map(c -> ((MediaGallery) c).getItems().getFirst().getUrl()).findFirst().orElseThrow();
    }
    static List<Button> buttons(MessageCreateData view) {
        // Buttons sit under the card, as under the old embed.
        return view.getComponents().stream().filter(c -> c instanceof ActionRow).flatMap(c -> ((ActionRow) c).getComponents().stream()).map(c -> (Button) c).toList();
    }

    @Test void bothAvatarsAreLinkedAndPriorityPicksTheLargeImage() {
        try (var view = AvatarCommand.buildView(user(), withServerAvatar(), true, 4096)) {
            var texts = texts(view);
            assertEquals("### Server Bunny's Avatar", texts.get(0));
            assertTrue(texts.get(1).matches("\\[Global Avatar]\\(.+\\) \\| \\[Server Avatar]\\(.+\\)"), texts.get(1));
            assertTrue(image(view).contains("server.gif?size=4096"));
            assertNull(card(view).getAccentColorRaw(), "plain card");
            assertEquals(2, texts.size(), "title and links only");
            var buttons = buttons(view);
            assertEquals(2, buttons.size());
            assertEquals("Server Avatar", buttons.get(1).getLabel());
        }
        try (var globalFirst = AvatarCommand.buildView(user(), withServerAvatar(), false, 1024)) {
            assertTrue(image(globalFirst).contains("a_avatar.gif?size=1024"));
        }
    }

    @Test void globalOnlyShowsOneLinkAndOneButton() {
        try (var view = AvatarCommand.buildView(user(), null, true, 2048)) {
            var texts = texts(view);
            assertEquals("### Bunny's Avatar", texts.get(0));
            assertTrue(texts.get(1).startsWith("[Global Avatar]("));
            assertFalse(texts.get(1).contains("|"));
            assertTrue(image(view).contains("a_avatar.gif?size=2048"), "server priority falls back to global");
            var buttons = buttons(view);
            assertEquals(1, buttons.size());
            Button only = buttons.getFirst();
            assertEquals("Global Avatar", only.getLabel());
        }
    }

    @Test void membersWithoutAServerAvatarGetOnlyTheGlobalOne() {
        try (var view = AvatarCommand.buildView(user(), mock(Member.class), true, 512)) {
            assertEquals(1, buttons(view).size());
        }
    }

    @Test void rejectsInvalidResolution() {
        assertThrows(IllegalArgumentException.class, () -> AvatarCommand.buildView(user(), null, false, 1000));
    }
}
