package org.bunnys.buttons;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import java.util.List;

/**
 * "Also post the games free right now" after /freebie setup. Only already owner-approved, still-live
 * giveaways are queued, each at most once per channel. The clicker must still have Manage Server here.
 */
@SuppressWarnings("unused")
public class FreebieCatchUpButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieMessages.CATCH_UP_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var member = event.getMember();
        if (system == null || args.length != 4 || event.getGuild() == null || member == null
                || !event.getGuild().getId().equals(args[1]) || !member.hasPermission(Permission.MANAGE_SERVER)) {
            reply(event, "You need **Manage Server** in this server to do that.");
            return;
        }
        var store = FreebieStore.byId(args[3]).orElse(null);
        var channel = event.getGuild().getChannelById(StandardGuildMessageChannel.class, args[2]);
        // Re-read the saved setting: the role and channel come from the database, not from the button.
        var subscription = system.repository().subscriptions(args[1]).stream()
                .filter(s -> s.channelId().equals(args[2]) && s.store() == store).findFirst();
        if (channel == null || subscription.isEmpty()) { reply(event, "That alert setting no longer exists."); return; }
        int queued = system.catchUp(subscription.get());
        event.editComponents().queue();
        reply(event, queued == 0 ? "Nothing new to post; " + channel.getAsMention() + " already has them."
                : "Posting " + queued + " current free game(s) in " + channel.getAsMention() + " shortly.");
    }

    private static void reply(ButtonInteractionEvent event, String text) {
        if (event.isAcknowledged()) event.getHook().sendMessage(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
        else event.reply(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
