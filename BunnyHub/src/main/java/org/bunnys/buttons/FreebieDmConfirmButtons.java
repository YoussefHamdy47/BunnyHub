package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieDmService;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.selects.FreebieDmSubscribeSelect;

/**
 * Confirm or Cancel on the {@code /free-games dms} confirm step: {@code freebie_dmok:yes:<userId>:<codes>} or
 * {@code freebie_dmok:no:<userId>}. Only that user may click. Confirm shows progress at once, then the result.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieDmConfirmButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieDm.CONFIRM_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || args.length < 3 || !args[2].equals(event.getUser().getId())) {
            event.reply(org.bunnys.handler.cards.Card.builder().text("Use `/free-games dms` to choose your own free-game DMs.").build().message())
                    .setEphemeral(true).queue();
            return;
        }
        if (FreebieDm.NO.equals(args[1])) {
            event.editMessage(Card.builder().text("Cancelled. Nothing changed.").build().edit()).queue();
            return;
        }
        var sections = args.length == 4 ? FreebieSection.decode(args[3]) : java.util.Set.<FreebieSection>of();
        if (sections.isEmpty()) { event.editMessage(Card.builder().text("Pick at least one section with `/free-games dms`.").build().edit()).queue(); return; }

        // Answer the click straight away so it never looks stuck while the check DM and the save run.
        event.editMessage(FreebieDm.sendingCard().edit()).complete();
        var result = system.dms().subscribe(event.getUser().getId(), sections,
                data -> FreebieDmSubscribeSelect.consent(event.getUser(), data));
        boolean on = result == FreebieDmService.Subscribed.SUBSCRIBED || result == FreebieDmService.Subscribed.EDITED;
        event.getHook().editOriginal(FreebieDm.resultCard(FreebieDmSubscribeSelect.reply(result, sections), on).edit()).queue();
    }
}
