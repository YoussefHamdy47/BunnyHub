package org.bunnys.events;

import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;

/**
 * Removes free-game settings for a deleted channel and cancels its queued alerts, so summaries can finish; the
 * server owner is told once which launchers stopped there.
 */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class FreebieChannelDelete extends BunnyEvent {
    public FreebieChannelDelete(BunnyHub client) { super(client); }

    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        FreebieSystem.current().ifPresent(system -> handle(system, event));
    }

    static void handle(FreebieSystem system, ChannelDeleteEvent event) {
        // Alerts only go to guild text-like channels; DMs and threads never hold settings.
        if (!event.isFromGuild() || event.getChannelType().isThread()) return;
        system.channelDeleted(event.getGuild().getId(), event.getChannel().getId(), event.getChannel().getName());
    }
}
