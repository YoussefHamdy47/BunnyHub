package org.bunnys.commands;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.ImageFormat;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarTest {
    private User user() {
        var user = mock(User.class);
        when(user.getId()).thenReturn("123");
        when(user.getEffectiveName()).thenReturn("Bunny");
        when(user.getEffectiveAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/avatars/123/a_avatar.gif"));
        when(user.getEffectiveAvatar(ImageFormat.PNG)).thenReturn(new ImageProxy("https://cdn.discordapp.com/avatars/123/a_avatar.png"));
        return user;
    }

    @Test void bothAvatarsAreFullSizeAndServerPriorityIsRespected() {
        var member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Server Bunny");
        when(member.getAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/server.gif"));
        when(member.getAvatar(ImageFormat.PNG)).thenReturn(new ImageProxy("https://cdn.discordapp.com/server.png"));
        try (var view = org.bunnys.bunnynexus.commands.info.AvatarCommand.buildView(user(), member, true, true, 4096, true)) {
            assertEquals(2, view.getEmbeds().size());
            assertTrue(view.getEmbeds().getFirst().getTitle().contains("Server avatar"));
            assertTrue(view.getEmbeds().getFirst().getImage().getUrl().contains("size=4096"));
            assertTrue(view.getEmbeds().get(1).getImage().getUrl().contains(".gif"));
            assertNotNull(view.toData());
        }
    }

    @Test void directMessagesFallBackToGlobalWithExplanation() {
        try (var view = org.bunnys.bunnynexus.commands.info.AvatarCommand.buildView(user(), null, true, true, 2048, false)) {
            assertEquals(1, view.getEmbeds().size());
            assertTrue(view.getEmbeds().getFirst().getDescription().contains("available in servers"));
            assertTrue(view.getEmbeds().getFirst().getImage().getUrl().contains(".gif?size=2048"));
        }
    }

    @Test void defaultAvatarsAndMissingServerAvatarsRemainSupported() {
        var user = user();
        when(user.getEffectiveAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/embed/avatars/0.png"));
        try (var view = org.bunnys.bunnynexus.commands.info.AvatarCommand.buildView(user, mock(Member.class), true, false, 512, true)) {
            assertEquals(1, view.getEmbeds().size());
            assertTrue(view.getEmbeds().getFirst().getDescription().contains("No server avatar"));
        }
    }

    @Test void rejectsInvalidResolution() {
        assertThrows(IllegalArgumentException.class, () -> org.bunnys.bunnynexus.commands.info.AvatarCommand.buildView(user(), null, false, false, 1000, false));
    }
}
