package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.bunnynexus.timers.buttons.GPAPaginator;

@SuppressWarnings("unused")
public class GPAMenuButtons extends BunnyButton {

    @Override
    public String getPrefix() {
        return "gpa";
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        // Ensure the ID has the expected parts: ["gpa", "action", "sessionId"]
        if (args.length < 3)
            return;

        String actionId = args[1];
        String sessionId = args[2];

        GPAPaginator.handle(event, actionId, sessionId);
    }
}