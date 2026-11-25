package org.bunnys.events;

import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.util.concurrent.RejectedExecutionException;

/** Drops a server's daily-deals setting when the bot is removed. Without it the post turns off after 3 failed days. */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class DealGuildLeave extends BunnyEvent {
    public DealGuildLeave(BunnyHub client) { super(client); }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        var system = DealSystem.current().orElse(null);
        if (system == null) return;
        String guildId = event.getGuild().getId();
        try {
            // Database work never runs on the gateway thread.
            client.getCommandExecutor().execute(() -> {
                try { system.repository().forget(guildId); }
                catch (RuntimeException failure) {
                    BunnyLog.warning("[Deals] Could not remove the daily post of left server " + guildId + " | " + FailureDiagnostics.describe(failure));
                }
            });
        } catch (RejectedExecutionException busy) {
            BunnyLog.warning("[Deals] Busy; the daily post of left server " + guildId + " turns off after 3 failed days.");
        }
    }
}
