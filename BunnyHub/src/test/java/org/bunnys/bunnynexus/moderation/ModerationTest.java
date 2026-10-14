package org.bunnys.bunnynexus.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.bunnynexus.moderation.Moderation.Action;
import org.bunnys.commands.moderation.*;
import org.bunnys.handler.commands.BunnyCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModerationTest {
    private static final long MOD = 1, BOT = 2, TARGET = 3;

    @Test void aNormalMemberCanBeActedOn() {
        var moderator = member(MOD, true);
        var target = member(TARGET, true);
        when(target.isTimedOut()).thenReturn(true);
        for (Action action : Action.values())
            assertEquals(Optional.empty(), Moderation.problem(action, moderator, self(true, true), user(TARGET), target), action.name());
    }

    @Test void permissionsAreCheckedForBothSides() {
        var target = member(TARGET, true);
        assertTrue(problem(Action.BAN, member(MOD, false), self(true, true), target).contains("You need the **Ban Members**"));
        assertTrue(problem(Action.MUTE, member(MOD, true), self(false, true), target).contains("I need the **Timeout Members**"));
    }

    @Test void hierarchyAndSelfTargetsAreRefused() {
        var moderator = member(MOD, true);
        assertTrue(problem(Action.KICK, moderator, self(true, true), moderator).contains("yourself"));
        var bot = self(true, true);
        assertTrue(Moderation.problem(Action.KICK, moderator, bot, user(BOT), bot).orElseThrow().contains("myself"));

        var owner = member(TARGET, true);
        when(owner.isOwner()).thenReturn(true);
        assertTrue(problem(Action.BAN, moderator, self(true, true), owner).contains("server owner"));

        var higher = member(TARGET, true);
        when(moderator.canInteract(higher)).thenReturn(false);
        assertTrue(problem(Action.KICK, moderator, self(true, true), higher).contains("equal to or higher than yours"));

        var aboveBot = member(TARGET, true);
        assertTrue(problem(Action.MUTE, member(MOD, true), self(true, false), aboveBot).contains("above mine"));
    }

    @Test void muteRulesFollowDiscord() {
        var admin = member(TARGET, true);
        when(admin.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        assertTrue(problem(Action.MUTE, member(MOD, true), self(true, true), admin).contains("administrators"));
        var notMuted = member(TARGET, true);
        assertTrue(problem(Action.UNMUTE, member(MOD, true), self(true, true), notMuted).contains("isn't muted"));
    }

    @Test void banAndUnbanWorkForPeopleWhoLeft() {
        assertEquals(Optional.empty(), Moderation.problem(Action.BAN, member(MOD, true), self(true, true), user(TARGET), null));
        assertEquals(Optional.empty(), Moderation.problem(Action.UNBAN, member(MOD, true), self(true, true), user(TARGET), null));
        assertTrue(Moderation.problem(Action.KICK, member(MOD, true), self(true, true), user(TARGET), null).orElseThrow()
                .contains("isn't in this server"));
    }

    @Test void confirmationsAndFailuresReadPlainly() {
        var muted = Moderation.done(Action.MUTE, user(TARGET), user(MOD), null, 10080, 0);
        assertEquals("Member muted", muted.getTitle());
        assertTrue(muted.getDescription().contains("Muted for 1 week, until <t:"), muted.getDescription());
        assertTrue(muted.getDescription().contains("**Reason:** None given"));
        var banned = Moderation.done(Action.BAN, user(TARGET), user(MOD), "spam *links*", 0, 24);
        assertTrue(banned.getDescription().contains("last 24 hours were deleted"));
        assertTrue(banned.getDescription().contains("spam \\*links\\*"));
        assertEquals("28 days", Moderation.durationLabel(40320));
        assertEquals("2 weeks", Moderation.durationLabel(20160));
        assertEquals("6 hours", Moderation.durationLabel(360));
        assertEquals("1 minute", Moderation.durationLabel(1));
        assertTrue(Moderation.failure(Action.UNBAN, user(TARGET), ErrorResponse.UNKNOWN_BAN).contains("isn't banned"));
        assertTrue(Moderation.auditReason(user(MOD), "x".repeat(600)).length() <= 512);
    }

    @Test void commandsAreServerOnlyAndHiddenFromMembersWithoutThePermission() {
        List<BunnyCommand> commands = List.of(new Ban(null), new Unban(null), new Kick(null), new Mute(null), new Unmute(null));
        for (BunnyCommand command : commands) {
            assertFalse(command.isDmEnabled(), command.getName());
            assertEquals(1, command.getUserPermissions().size(), command.getName());
            assertEquals("Moderation", command.getCategory());
            assertNotNull(command.buildCommandData().toData());
        }
        assertEquals(Permission.MODERATE_MEMBERS, new Mute(null).getUserPermissions().getFirst());
    }

    private static String problem(Action action, Member moderator, SelfMember self, Member target) {
        return Moderation.problem(action, moderator, self, user(target.getIdLong()), target).orElse("");
    }

    private static User user(long id) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getName()).thenReturn("user" + id);
        when(user.getAsMention()).thenReturn("<@" + id + ">");
        return user;
    }

    private static Member member(long id, boolean permitted) {
        Member member = mock(Member.class);
        when(member.getIdLong()).thenReturn(id);
        when(member.hasPermission(any(Permission.class))).thenReturn(permitted);
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(false);
        when(member.canInteract(any(Member.class))).thenReturn(true);
        return member;
    }

    private static SelfMember self(boolean permitted, boolean ranksAbove) {
        SelfMember self = mock(SelfMember.class);
        when(self.getIdLong()).thenReturn(BOT);
        when(self.hasPermission(any(Permission.class))).thenReturn(permitted);
        when(self.canInteract(any(Member.class))).thenReturn(ranksAbove);
        return self;
    }
}
