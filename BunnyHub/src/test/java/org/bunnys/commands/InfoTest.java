package org.bunnys.commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import org.bunnys.commands.info.Info;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.bunnynexus.info.InfoEmbeds;
import org.bunnys.bunnynexus.info.ServerInfoEmbeds;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.function.Consumer;
import net.dv8tion.jda.api.Permission;

class InfoTest {
    private User user() {
        var user = mock(User.class, RETURNS_DEEP_STUBS);
        when(user.getId()).thenReturn("123456789012345678");
        when(user.getName()).thenReturn("test-user");
        when(user.getEffectiveName()).thenReturn("Test User");
        when(user.getAsMention()).thenReturn("<@123456789012345678>");
        when(user.getEffectiveAvatar().getUrl(1024)).thenReturn("https://cdn.discordapp.com/embed/avatars/0.png");
        when(user.getTimeCreated()).thenReturn(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        doReturn(EnumSet.noneOf(User.UserFlag.class)).when(user).getFlags();
        return user;
    }

    @Test void bothBranchesAndAliasesUseTheSharedCommandModel() {
        var info = new Info(null);
        assertNotNull(info.buildCommandData().toData());
        assertEquals(Set.of("user", "server"), info.getSubcommands().keySet());
        assertTrue(info.getAliases().containsAll(List.of("whois", "about")));
        assertSame(info.resolveSubcommand("user"), info.resolveSubcommand("member"));
        assertSame(info.resolveSubcommand("server"), info.resolveSubcommand("guild"));
        assertTrue(info.isDeferBeforeDispatch());
    }

    @Test void nonMemberCardOmitsMembershipAndKeepsAccountDetails() {
        var card = InfoEmbeds.userInfo(user(), null);
        assertTrue(card.plainText().contains("**Joined Discord**"));
        assertFalse(card.plainText().contains("Joined Server"));
        assertTrue(card.message().isUsingComponentsV2());
    }

    @Test void largeRoleListsAreTruncatedAndAdministratorIsShownAlone() {
        var member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Member");
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        var roles = new ArrayList<Role>();
        for (int i = 0; i < 200; i++) {
            var role = mock(Role.class);
            when(role.getAsMention()).thenReturn("<@&123456789012345678>");
            roles.add(role);
        }
        when(member.getRoles()).thenReturn(roles);
        var card = InfoEmbeds.userInfo(user(), member); // build() checks Discord's limits
        String text = card.plainText();
        assertTrue(text.contains("### Roles [200]") && text.substring(text.indexOf("### Roles [200]")).contains("more"), text);
        assertTrue(text.contains("### Key Permissions [1]"));
    }

    @Test void serverCardWorksWithoutOwnerMemberCacheAndClampsLongFeatures() {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("123456789012345678");
        when(guild.getName()).thenReturn("Test Server");
        when(guild.getOwnerId()).thenReturn("223456789012345678");
        when(guild.getTimeCreated()).thenReturn(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        when(guild.getMemberCount()).thenReturn(1234);
        when(guild.getBoostTier()).thenReturn(Guild.BoostTier.NONE);
        when(guild.getVerificationLevel()).thenReturn(Guild.VerificationLevel.HIGH);
        when(guild.getNSFWLevel()).thenReturn(Guild.NSFWLevel.DEFAULT);
        when(guild.getLocale()).thenReturn(DiscordLocale.ENGLISH_US);
        when(guild.getFeatures()).thenReturn(Set.of("FEATURE_".repeat(200)));
        when(guild.getDescription()).thenReturn("x".repeat(1024));
        var card = ServerInfoEmbeds.serverInfo(guild); // build() checks Discord's limits
        assertTrue(card.plainText().contains("👑 **Owner** <@" + guild.getOwnerId() + ">"));
        assertTrue(card.message().getMentionedUsers().isEmpty(), "the owner is shown, never pinged");
        verify(guild, never()).getOwner();
    }

    @Test void serverBranchInDmRepliesWithAnExplanation() {
        var ctx = mock(CommandContext.class);
        new Info(null).resolveSubcommand("server").execute(null, ctx);
        verify(ctx).replyTransient(argThat(e -> e.getTitle().endsWith("Server Only")));
        verify(ctx, never()).getGuild();
    }

    @Test void missingMemberIsRetrievedAfterDeferral() {
        var ctx = mock(CommandContext.class);
        var user = user();
        var guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        var jda = mock(JDA.class, RETURNS_DEEP_STUBS);
        when(ctx.getUserOption("user")).thenReturn(user);
        when(ctx.isFromGuild()).thenReturn(true);
        when(ctx.getGuild()).thenReturn(guild);
        when(ctx.getJDA()).thenReturn(jda);
        when(jda.getSelfUser().getId()).thenReturn("999456789012345678");
        var member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Resolved Member");
        var request = guild.retrieveMemberById(user.getId());
        doAnswer(invocation -> {
            Consumer<Member> success = invocation.getArgument(0);
            success.accept(member);
            return null;
        }).when(request).queue(any(), any());
        new Info(null).resolveSubcommand("user").execute(null, ctx);
        var order = inOrder(ctx, request);
        order.verify(ctx).defer();
        order.verify(request).queue(any(), any());
        verify(ctx).replyCard(argThat(c -> c.plainText().contains("Resolved Member")));
    }

    @Test void omittedUserDefaultsToCallerAndUnknownExplicitUserDoesNot() {
        var ctx = mock(CommandContext.class);
        var user = user();
        when(ctx.getUser()).thenReturn(user);
        var jda = mock(JDA.class);
        var self = mock(SelfUser.class);
        when(self.getId()).thenReturn("999456789012345678");
        when(jda.getSelfUser()).thenReturn(self);
        when(ctx.getJDA()).thenReturn(jda);
        var branch = new Info(null).resolveSubcommand("user");
        branch.execute(null, ctx);
        verify(ctx).replyCard(argThat(c -> c.plainText().contains(user.getAsMention())));
        when(ctx.getString("user")).thenReturn("invalid");
        branch.execute(null, ctx);
        verify(ctx).replyTransient(argThat(e -> e.getTitle().endsWith("User Not Found")));
    }
}
