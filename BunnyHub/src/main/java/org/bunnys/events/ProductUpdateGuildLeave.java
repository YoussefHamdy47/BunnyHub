package org.bunnys.events;

import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import org.bunnys.bunnynexus.updates.ProductUpdates;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.util.concurrent.RejectedExecutionException;

/** Drops a server's product-update settings when the bot is removed. */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class ProductUpdateGuildLeave extends BunnyEvent {
    public ProductUpdateGuildLeave(BunnyHub client) { super(client); }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        var system = ProductUpdates.current().orElse(null);
        if (system == null) return;
        String guildId = event.getGuild().getId();
        try {
            // Database work never runs on the gateway thread.
            client.getCommandExecutor().execute(() -> {
                try { system.prefs().forget(guildId); }
                catch (RuntimeException failure) {
                    BunnyLog.warning("[Updates] Could not remove product-update settings of left server " + guildId + " | " + FailureDiagnostics.describe(failure));
                }
            });
        } catch (RejectedExecutionException busy) {
            BunnyLog.warning("[Updates] Busy; product-update settings of left server " + guildId + " stay.");
        }
    }
}
