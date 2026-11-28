package org.bunnys.events;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GuildWelcomeTest {
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final Role everyone = mock(Role.class);

    TextChannel channel(int position, boolean botCanPost, boolean publicChannel) {
        var c = mock(TextChannel.class);
        when(c.getPositionRaw()).thenReturn(position);
        when(self.hasPermission(eq(c), any(Permission[].class))).thenReturn(botCanPost);
        when(everyone.hasPermission(c, Permission.VIEW_CHANNEL)).thenReturn(publicChannel);
        return c;
    }

    GuildWelcomeTest() {
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getPublicRole()).thenReturn(everyone);
    }

    @Test void theSystemChannelComesFirst() {
        var system = channel(5, true, true);
        var top = channel(0, true, true);
        when(guild.getSystemChannel()).thenReturn(system);
        when(guild.getTextChannels()).thenReturn(List.of(top, system));
        assertEquals(Optional.of(system), GuildWelcome.channel(guild));
    }

    @Test void otherwiseTheTopPublicChannelTheBotCanPostIn() {
        var hidden = channel(0, true, false);
        var readOnly = channel(1, false, true);
        var general = channel(2, true, true);
        var lower = channel(3, true, true);
        var closedSystem = channel(9, false, true);
        when(guild.getSystemChannel()).thenReturn(closedSystem);
        when(guild.getTextChannels()).thenReturn(List.of(lower, hidden, readOnly, general));
        assertEquals(Optional.of(general), GuildWelcome.channel(guild));
        when(guild.getTextChannels()).thenReturn(List.of(hidden, readOnly));
        assertEquals(Optional.empty(), GuildWelcome.channel(guild), "nowhere suitable: nothing is posted");
    }

    @Test void theCardSaysWhatItDoesAndWhereToStartWithoutPinging() {
        var card = GuildWelcome.card();
        String text = card.plainText();
        assertTrue(text.contains("Thanks for adding BunnyNexus") && text.contains("/free-games setup") && text.contains("/deals"));
        assertEquals(List.of(GuildWelcome.SUPPORT, "https://top.gg/bot/795361755223556116/vote"),
                MessageComponentTree.of(card.components()).findAll(Button.class).stream().map(Button::getUrl).toList());
        assertTrue(card.message().getMentionedRoles().isEmpty() && card.message().getMentionedUsers().isEmpty());
        assertFalse(text.contains("Free right now"), "no live games known: no line");
        var withGames = GuildWelcome.card(List.of("[Hades](https://store.epicgames.com/p/hades)")).plainText();
        assertTrue(withGames.contains("**Free right now:** [Hades](https://store.epicgames.com/p/hades)"), withGames);
    }

    @Test void attachingAnAdKeepsTheReplyWithinDiscordsLimits() {
        var reply = GuildWelcome.card().message();
        var ad = org.bunnys.bunnynexus.updates.AdCards.card(new org.bunnys.bunnynexus.updates.Ad("x1", Optional.of("Title"), "Text",
                Optional.empty(), Optional.empty(), org.bunnys.bunnynexus.updates.Ad.Audience.ALL, true, java.time.Instant.EPOCH));
        var with = org.bunnys.bunnynexus.updates.AdCards.attach(reply, Optional.of(ad));
        assertEquals(reply.getComponents().size() + 1, with.getComponents().size());
        assertSame(reply, org.bunnys.bunnynexus.updates.AdCards.attach(reply, Optional.empty()), "no ad: the reply as it was");
    }
}
