package org.bunnys.events;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdatePendingEvent;
import org.bunnys.bunnynexus.setup.Greetings;
import org.bunnys.bunnynexus.setup.ServerSettings;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.bunnys.bunnynexus.setup.ServerSettingsStore;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * Posts the welcome and goodbye messages and gives join roles, as set up with {@code /setup}. Needs the Server
 * Members intent. Members still on the rules screen (membership screening) get their roles once they accept.
 */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class MemberGreetings extends BunnyEvent {
    public MemberGreetings(BunnyHub client) { super(client); }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        run(guild, "join", settings -> {
            greet(guild, Kind.WELCOME, settings, Greetings.Subject.of(member.getUser(), member));
            if (!member.isPending()) giveJoinRoles(guild, member, settings);
        });
    }

    @Override
    public void onGuildMemberUpdatePending(GuildMemberUpdatePendingEvent event) {
        if (!event.getOldPending() || event.getNewPending()) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        run(guild, "screening", settings -> giveJoinRoles(guild, member, settings));
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        Guild guild = event.getGuild();
        User user = event.getUser();
        Member member = event.getMember();
        run(guild, "leave", settings -> greet(guild, Kind.GOODBYE, settings, Greetings.Subject.of(user, member)));
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        String guildId = event.getGuild().getId();
        execute("left server " + guildId, () -> ServerSettingsStore.current().ifPresent(store -> store.remove(guildId)));
    }

    private void greet(Guild guild, Kind kind, ServerSettings settings, Greetings.Subject subject) {
        var greeting = settings.greeting(kind);
        if (!greeting.on()) return;
        var channel = Greetings.channel(guild, greeting.channelId());
        // The panel lists both problems under "Needs attention"; nothing to report from here.
        if (!Greetings.canPost(guild, channel)) return;
        channel.sendMessage(Greetings.message(kind, greeting.text(kind), subject, guild.getName(), guild.getMemberCount()))
                .queue(null, failed -> BunnyLog.warning("[Setup] " + kind.label() + " not posted in " + guild.getId()
                        + " | " + FailureDiagnostics.describe(failed)));
    }

    private static void giveJoinRoles(Guild guild, Member member, ServerSettings settings) {
        if (settings.joinRoles().isEmpty() || member.getUser().isBot()) return;
        var roles = Greetings.givableRoles(guild, settings.joinRoles());
        if (roles.isEmpty()) return;
        guild.modifyMemberRoles(member, roles, List.of()).reason("Join roles (/setup)")
                .queue(null, failed -> BunnyLog.warning("[Setup] Join roles not given in " + guild.getId()
                        + " | " + FailureDiagnostics.describe(failed)));
    }

    private interface Action { void run(ServerSettings settings); }

    /** Settings lookups can touch the database, which never runs on the gateway thread. */
    private void run(Guild guild, String what, Action action) {
        String guildId = guild.getId();
        execute(what + " in " + guildId, () -> {
            var store = ServerSettingsStore.current().orElse(null);
            if (store == null) return;
            var settings = store.get(guildId);
            if (!settings.isEmpty()) action.run(settings);
        });
    }

    private void execute(String what, Runnable task) {
        try {
            client.getCommandExecutor().execute(() -> {
                try { task.run(); }
                catch (RuntimeException failure) {
                    BunnyLog.warning("[Setup] " + what + " failed | " + FailureDiagnostics.describe(failure));
                }
            });
        } catch (RejectedExecutionException busy) {
            BunnyLog.warning("[Setup] Busy; skipped " + what + ".");
        }
    }
}
