package org.bunnys.events;

import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.util.concurrent.RejectedExecutionException;

/** Drops a server's free-game settings when the bot is removed, so future alerts don't target it. */
@SuppressWarnings("unused")
public class FreebieGuildLeave extends BunnyEvent {
    public FreebieGuildLeave(BunnyHub client) { super(client); }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) return;
        String guildId = event.getGuild().getId();
        try {
            // Database work never runs on the gateway thread.
            client.getCommandExecutor().execute(() -> {
                try { system.repository().removeGuild(guildId); }
                catch (RuntimeException failure) {
                    BunnyLog.warning("[Freebies] Could not remove settings for left server " + guildId + " | " + FailureDiagnostics.describe(failure));
                }
            });
        } catch (RejectedExecutionException busy) {
            BunnyLog.warning("[Freebies] Busy; settings for left server " + guildId + " stay until a delivery fails.");
        }
    }
}
