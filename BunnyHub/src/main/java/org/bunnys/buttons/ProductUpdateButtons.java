package org.bunnys.buttons;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.bunnynexus.updates.ProductUpdates;
import org.bunnys.bunnynexus.updates.UpdateCards;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.handler.utils.InteractionErrors;

import java.time.Instant;
import java.util.List;

/**
 * {@code updates:off} on a product update (server admins turn them off for the server) and the owner's
 * {@code updates:send:<token>} / {@code updates:cancel:<token>} on a preview.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class ProductUpdateButtons extends BunnyButton {
    @Override public String getPrefix() { return UpdateCards.PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        try {
            var updates = ProductUpdates.current().orElse(null);
            if (updates == null) { reply(event, "Product updates are not available right now."); return; }
            if (args.length == 2 && UpdateCards.OFF.equals(args[1])) { turnOff(event, updates); return; }
            if (args.length != 3) { event.deferEdit().queue(); return; }
            boolean owner = FreebieSystem.current().map(s -> s.config().isOwner(event.getUser().getId())).orElse(false);
            if (!owner) { reply(event, "Only bot owners can do this."); return; }
            if (ProductUpdates.CANCEL.equals(args[1])) {
                updates.cancel(args[2]);
                event.editMessage("Cancelled. Nothing was sent.").setComponents(List.of()).useComponentsV2(false).queue();
                return;
            }
            if (!ProductUpdates.SEND.equals(args[1])) { event.deferEdit().queue(); return; }
            event.deferEdit().complete();
            String result = updates.confirm(args[2], event.getUser().getId());
            event.getHook().editOriginalComponents(List.of()).queue();
            event.getHook().sendMessage(result).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            InteractionErrors.report(event, e);
        }
    }

    private static void turnOff(ButtonInteractionEvent event, ProductUpdates updates) {
        var guild = event.getGuild();
        var member = event.getMember();
        if (guild == null || member == null) { reply(event, "This only works in a server."); return; }
        if (!member.hasPermission(Permission.MANAGE_SERVER)) { reply(event, "Only members with **Manage Server** can turn product updates off."); return; }
        event.deferReply(true).complete();
        updates.prefs().setOff(guild.getId(), true, member.getId(), Instant.now());
        event.getHook().sendMessage(org.bunnys.handler.commands.CommandLinks.render("Product updates are off for this server. You can turn them back on in `/free-games setup`."))
                .setEphemeral(true).setAllowedMentions(List.of()).queue();
    }

    private static void reply(ButtonInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
