package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.deals.DealCards;
import org.bunnys.bunnynexus.deals.DealStore;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.handler.utils.InteractionErrors;

import java.util.Optional;

/**
 * Previous / Next on the /deals list ({@code deals:page:<all|store>:<page>}) and the launcher buttons on a deals board
 * ({@code deals:store:<store>}, a private list). Read-only and from memory, so anyone may use them and there is no cooldown.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class DealButtons extends BunnyButton {
    @Override public String getPrefix() { return DealCards.PREFIX; }

    @Override public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        try {
            // deals:store:<launcher> on a board: that launcher's full list, privately.
            if (args.length == 3 && "store".equals(args[1])) { showStore(event, DealStore.byChoice(args[2])); return; }
            if (args.length != 4 || !"page".equals(args[1]) || !args[3].matches("[0-9]{1,3}")) { event.deferEdit().queue(); return; }
            Optional<DealStore> store = "all".equals(args[2]) ? Optional.empty() : DealStore.byChoice(args[2]);
            if (store.isEmpty() && !"all".equals(args[2])) { event.deferEdit().queue(); return; }
            var system = DealSystem.current().orElse(null);
            if (system == null || system.snapshot().stale(system.now())) { event.editMessage(DealCards.unavailable().edit()).queue(); return; }
            event.editMessage(DealCards.listEdit(system.snapshot(), store, Integer.parseInt(args[3]))).queue();
        } catch (RuntimeException e) {
            InteractionErrors.report(event, e);
        }
    }

    private static void showStore(ButtonInteractionEvent event, Optional<DealStore> store) {
        var system = DealSystem.current().orElse(null);
        if (system == null || store.isEmpty() || system.snapshot().stale(system.now())) {
            event.reply(DealCards.unavailable().message()).setEphemeral(true).queue();
            return;
        }
        event.reply(DealCards.list(system.snapshot(), store, 0)).setEphemeral(true).queue();
    }
}
