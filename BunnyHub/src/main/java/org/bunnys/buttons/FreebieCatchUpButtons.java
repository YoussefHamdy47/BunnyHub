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
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieCatchUpButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieMessages.CATCH_UP_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        var member = event.getMember();
        if (system == null || args.length != 4 || guild == null || member == null
                || !guild.getId().equals(args[1]) || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        // Several reads and inserts follow; removing the button also acknowledges the click straight away.
        event.editComponents().complete();
        // "all" = every launcher this channel is set up for (after a multi-launcher setup).
        boolean all = FreebieMessages.CATCH_UP_ALL.equals(args[3]);
        var store = all ? null : FreebieStore.byId(args[3]).orElse(null);
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, args[2]);
        // Re-read the saved settings: the role and channel come from the database, not from the button.
        var subscriptions = system.subscriptions().forGuild(args[1]).stream()
                .filter(s -> s.channelId().equals(args[2]) && (all || s.store() == store)).toList();
        if (channel == null || subscriptions.isEmpty()) { followUp(event, "That alert setting no longer exists."); return; }
        int queued = 0;
        for (var subscription : subscriptions) queued += system.catchUp(subscription);
        followUp(event, queued == 0 ? "Nothing new to post; " + channel.getAsMention() + " already has them."
                : "Posting " + queued + " current free game(s) in " + channel.getAsMention() + " shortly.");
    }

    private static void followUp(ButtonInteractionEvent event, String text) {
        event.getHook().sendMessage(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
