package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.Embeds;
import java.util.List;

/** Private pagination and actor-bound retry confirmations. */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public final class FreebieDeliveryButtons extends BunnyButton {
    @Override public String getPrefix() { return "freebie_delivery"; }
    @Override public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || !system.config().isOwner(event.getUser().getId())) {
            event.reply("Only freebie owners can use this.").setEphemeral(true).queue(); return;
        }
        if (args.length < 4 || args.length > 5 || !args[2].matches("[0-9]{1,20}")
                || !args[3].matches("[0-9]{17,20}")) {
            event.reply("Invalid delivery control.").setEphemeral(true).queue(); return;
        }
        event.deferReply(true).complete();
        String offer = "gamerpower:" + args[2], actor = event.getUser().getId();
        var view = args.length == 5 && args[1].equals("retry")
                ? system.deliveryAdmin().confirm(actor, offer, args[3], args[4])
                : system.deliveryAdmin().history(actor, offer, offer + "|" + args[3]);
        event.getHook().editOriginalEmbeds(Embeds.of("🎁", "Delivery history", view.text()).build())
                .setComponents(view.rows()).setAllowedMentions(List.of()).queue();
    }
}
