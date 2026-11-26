package org.bunnys.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.router.modals.BunnyModal;

/**
 * The owner's "Add console free game" form ({@code console_tip_form:<tip>}): checks the store link and queues the game
 * for a normal review. On success the tip message says so; otherwise the reason is shown privately and the tip stays.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class ConsoleTipModal extends BunnyModal {
    @Override public String getPrefix() { return FreebieSystem.CONSOLE_TIP_MODAL; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] ignored) {
        var system = FreebieSystem.current().orElse(null);
        String[] args = event.getModalId().split(":", 2);
        if (system == null || args.length != 2 || !system.config().isReviewChannel(event.getChannelId())) {
            reply(event, system == null ? FreebieSystem.unavailable() : "Use this in a free-game review channel.");
            return;
        }
        var result = system.addConsoleGame(event.getUser().getId(), args[1], value(event, FreebieSystem.CONSOLE_TIP_LINK),
                value(event, FreebieSystem.CONSOLE_TIP_TITLE), value(event, FreebieSystem.CONSOLE_TIP_ENDS));
        if (result.added() && event.getMessage() != null)
            event.getHook().editOriginal(Card.builder().text("**Console tip** · " + result.text() + " (<@" + event.getUser().getId() + ">)")
                    .build().edit()).queue();
        else reply(event, result.text());
    }

    private static void reply(ModalInteractionEvent event, String text) {
        event.getHook().sendMessage(Card.builder().text(text).build().message()).setEphemeral(true).queue();
    }

    private static String value(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? "" : mapping.getAsString();
    }
}
