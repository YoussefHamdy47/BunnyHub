package org.bunnys.handler.router.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.handler.BunnyHub;

public abstract class BunnyModal {
    public boolean deferEditBeforeDispatch() { return false; }
    public abstract String getPrefix();
    public abstract void execute(BunnyHub client, ModalInteractionEvent event, String[] args);
}
