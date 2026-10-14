package org.bunnys.bunnynexus.setup;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bson.Document;
import org.bunnys.bunnynexus.setup.ServerSettings.Greeting;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.bunnys.commands.Setup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ServerSetupTest {
    static final String GUILD = "123456789012345678", CHANNEL = "223456789012345678", ROLE = "323456789012345678";
    private final Greetings.Subject newcomer = new Greetings.Subject("423456789012345678", "Bun_ny*");

    @Test void placeholdersAreFilledAndNamesCannotBreakFormatting() {
        String text = Greetings.render("Hi {user} aka {name}, welcome to {server}! #{count}", newcomer, "Cozy *Club*", 42);
        assertEquals("Hi <@423456789012345678> aka Bun\\_ny\\*, welcome to Cozy \\*Club\\*! #42", text);
        assertTrue(Greetings.render("{user}".repeat(400), newcomer, "S", 1).length() <= Message.MAX_CONTENT_LENGTH);
    }

    @Test void onlyTheNewMemberCanBePinged() {
        var welcome = Greetings.message(Kind.WELCOME, "Hi {user} @everyone <@&" + ROLE + ">", newcomer, "S", 3);
        assertEquals(List.of("423456789012345678"), List.copyOf(welcome.getMentionedUsers()));
        assertTrue(welcome.getMentionedRoles().isEmpty());
        assertTrue(welcome.getAllowedMentions().isEmpty());
        var goodbye = Greetings.message(Kind.GOODBYE, "Bye {user}", newcomer, "S", 3);
        assertTrue(goodbye.getMentionedUsers().isEmpty() && goodbye.getAllowedMentions().isEmpty());
    }

    @Test void blankTextFallsBackToTheDefaultAndChannelDecidesOnOrOff() {
        var off = new Greeting(null, "custom");
        assertFalse(off.on());
        assertEquals("custom", off.text(Kind.WELCOME));
        assertEquals(Kind.GOODBYE.defaultMessage(), new Greeting(CHANNEL, " ").text(Kind.GOODBYE));
        assertTrue(ServerSettings.empty().isEmpty());
        assertFalse(ServerSettings.empty().withJoinRoles(List.of(ROLE)).isEmpty());
        assertTrue(ServerSettings.empty().with(Kind.WELCOME, new Greeting(CHANNEL, null)).welcome().on());
    }

    @Test void settingsSurviveTheDatabaseRoundTrip() {
        var settings = new ServerSettings(new Greeting(CHANNEL, "Hello {user}"), new Greeting(null, "Bye"), List.of(ROLE, ROLE));
        Document doc = ServerSettingsStore.write(GUILD, settings);
        assertEquals(GUILD, doc.get("_id"));
        var back = ServerSettingsStore.read(doc);
        assertEquals(settings.welcome(), back.welcome());
        assertEquals(settings.goodbye(), back.goodbye());
        assertEquals(List.of(ROLE), back.joinRoles(), "duplicates collapse");
        assertEquals(ServerSettings.empty(), ServerSettingsStore.read(new Document("_id", GUILD)));
    }

    @Test void panelShowsEverySettingAndFitsDiscordLimits() {
        Guild guild = guild(true, true);
        var settings = new ServerSettings(new Greeting(CHANNEL, "x".repeat(ServerSettings.MAX_MESSAGE)), null, List.of(ROLE));
        var panel = SetupPanel.panel(guild, settings, "Saved.");
        assertTrue(panel.embed().isSendable());
        assertEquals(List.of("Welcome message", "Goodbye message", "Join roles"),
                panel.embed().getFields().stream().map(f -> f.getName()).toList());
        assertTrue(panel.embed().getFields().get(0).getValue().startsWith("On, posted in <#" + CHANNEL + ">"));
        assertEquals("Off", panel.embed().getFields().get(1).getValue());
        List<Button> buttons = panel.rows().getFirst().getButtons();
        assertEquals(4, buttons.size());
        buttons.forEach(b -> {
            assertTrue(b.getCustomId().length() <= 100);
            assertNull(b.getEmoji(), "no emoji clutter");
        });
        assertEquals("setup:welcome:" + GUILD, buttons.getFirst().getCustomId());
    }

    @Test void panelFlagsWhatWillNotWork() {
        Guild guild = guild(false, false);
        var settings = new ServerSettings(new Greeting(CHANNEL, null), new Greeting("999", null), List.of(ROLE));
        var problems = Greetings.problems(guild, settings);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("Welcome message: I can't post")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("Goodbye message: its channel was deleted")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("above my highest role")), problems.toString());
        assertTrue(SetupPanel.panel(guild, settings, null).embed().getFields().stream().anyMatch(f -> f.getName().equals("Needs attention")));
    }

    @Test void formsCarryTheCurrentSettings() {
        var form = SetupPanel.greetingForm(GUILD, Kind.WELCOME, new Greeting(CHANNEL, null));
        assertEquals("setup_form:welcome:" + GUILD, form.getId());
        assertEquals(2, form.getComponents().size());
        var roles = SetupPanel.rolesForm(GUILD, List.of(ROLE));
        assertEquals("setup_form:roles:" + GUILD, roles.getId());
        assertNotNull(SetupPanel.rolesForm(GUILD, List.of()));
    }

    @Test void setupIsOneServerOnlyCommand() {
        var setup = new Setup(null);
        assertTrue(setup.getSubcommands().isEmpty());
        assertFalse(setup.isDmEnabled());
        assertTrue(setup.getUserPermissions().contains(Permission.MANAGE_SERVER));
        assertNotNull(setup.buildCommandData().toData());
    }

    /** A server with one channel and one role; whether the bot can post there and give the role is configurable. */
    public static Guild guild(boolean canPost, boolean canGiveRole) {
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
        Role role = mock(Role.class);
        when(guild.getId()).thenReturn(GUILD);
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getChannelById(eq(StandardGuildMessageChannel.class), eq(CHANNEL))).thenReturn(channel);
        when(guild.getRoleById(ROLE)).thenReturn(role);
        when(channel.getId()).thenReturn(CHANNEL);
        when(channel.getAsMention()).thenReturn("<#" + CHANNEL + ">");
        when(role.getId()).thenReturn(ROLE);
        when(role.getAsMention()).thenReturn("<@&" + ROLE + ">");
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(canPost);
        when(self.hasPermission(Permission.MANAGE_ROLES)).thenReturn(true);
        when(self.canInteract(role)).thenReturn(canGiveRole);
        return guild;
    }
}
