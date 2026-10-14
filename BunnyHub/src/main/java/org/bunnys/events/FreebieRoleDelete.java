package org.bunnys.events;

import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;

/** A deleted ping role is cleared from free-game settings; those alerts keep posting, without a ping. */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class FreebieRoleDelete extends BunnyEvent {
    public FreebieRoleDelete(BunnyHub client) { super(client); }

    @Override
    public void onRoleDelete(RoleDeleteEvent event) {
        FreebieSystem.current().ifPresent(system -> handle(system, event));
    }

    static void handle(FreebieSystem system, RoleDeleteEvent event) {
        system.roleDeleted(event.getGuild().getId(), event.getRole().getId(), event.getRole().getName());
    }
}
