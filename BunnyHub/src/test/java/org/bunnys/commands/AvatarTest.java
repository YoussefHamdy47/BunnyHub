package org.bunnys.commands;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.bunnys.bunnynexus.commands.info.AvatarCommand;
import org.bunnys.utils.AppDesign;
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

    @Test void bothAvatarsAreLinkedAndPriorityPicksTheLargeImage() {
        try (var view = AvatarCommand.buildView(user(), withServerAvatar(), true, 4096)) {
            assertEquals(1, view.getEmbeds().size());
            var embed = view.getEmbeds().getFirst();
            assertEquals("🖼️ Server Bunny's Avatar", embed.getTitle());
            assertTrue(embed.getDescription().matches("\\[Global Avatar]\\(.+\\) \\| \\[Server Avatar]\\(.+\\)"), embed.getDescription());
            assertTrue(embed.getImage().getUrl().contains("server.gif?size=4096"));
            assertEquals(AppDesign.ColorCodes.DEFAULT, embed.getColor());
            assertNotNull(embed.getTimestamp());
            assertTrue(embed.getFooter().getText().startsWith("4096px"));
            var buttons = view.getComponents().getFirst().asActionRow().getComponents();
            assertEquals(2, buttons.size());
            assertEquals("Server Avatar", buttons.get(1).asButton().getLabel());
        }
        try (var globalFirst = AvatarCommand.buildView(user(), withServerAvatar(), false, 1024)) {
            assertTrue(globalFirst.getEmbeds().getFirst().getImage().getUrl().contains("a_avatar.gif?size=1024"));
        }
    }

    @Test void globalOnlyShowsOneLinkAndOneButton() {
        try (var view = AvatarCommand.buildView(user(), null, true, 2048)) {
            var embed = view.getEmbeds().getFirst();
            assertEquals("🖼️ Bunny's Avatar", embed.getTitle());
            assertTrue(embed.getDescription().startsWith("[Global Avatar]("));
            assertFalse(embed.getDescription().contains("|"));
            assertTrue(embed.getImage().getUrl().contains("a_avatar.gif?size=2048"), "server priority falls back to global");
            var buttons = view.getComponents().getFirst().asActionRow().getComponents();
            assertEquals(1, buttons.size());
            Button only = buttons.getFirst().asButton();
            assertEquals("Global Avatar", only.getLabel());
        }
    }

    @Test void membersWithoutAServerAvatarGetOnlyTheGlobalOne() {
        try (var view = AvatarCommand.buildView(user(), mock(Member.class), true, 512)) {
            assertEquals(1, view.getComponents().getFirst().asActionRow().getComponents().size());
        }
    }

    @Test void rejectsInvalidResolution() {
        assertThrows(IllegalArgumentException.class, () -> AvatarCommand.buildView(user(), null, false, 1000));
    }
}
