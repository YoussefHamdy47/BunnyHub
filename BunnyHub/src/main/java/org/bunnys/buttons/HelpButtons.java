package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.help.HelpMenu;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.selects.HelpSelect;

/** Help navigation: {@code help:go:<owner>:<view>:<slot>}. Read-only, so unthrottled. */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class HelpButtons extends BunnyButton {
    @Override public String getPrefix() { return HelpMenu.PREFIX; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        String view = args.length >= 4 && "go".equals(args[1]) ? args[3] : HelpMenu.OVERVIEW;
        String owner = args.length >= 3 ? args[2] : "";
        HelpSelect.show(client, event, owner, view);
    }
}
