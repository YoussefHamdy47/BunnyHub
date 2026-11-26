package org.bunnys.events;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.commands.CommandLinks;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One short welcome when the bot is added to a server: what it does and the one command an admin needs. Posted in
 * the server's system channel, or else the first channel everyone can read where the bot can post. Pings nobody;
 * nothing is posted when there is no such channel.
 */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class GuildWelcome extends BunnyEvent {
    static final String SUPPORT = "https://discord.gg/gGsdHYhTFv";

    public GuildWelcome(BunnyHub client) { super(client); }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        var guild = event.getGuild();
        channel(guild).ifPresent(channel -> channel.sendMessage(card().message()).setAllowedMentions(List.of()).queue(
                sent -> BunnyLog.info("[Welcome] Posted in " + guild.getId()),
                failure -> BunnyLog.info("[Welcome] Not posted in " + guild.getId() + ": " + failure.getClass().getSimpleName())));
    }

    static Card card() {
        return Card.builder()
                .title("Thanks for adding BunnyNexus")
                .text("Free games from Epic, Steam, GOG and more, posted the moment they go live. "
                        + "Plus a live Deals Board for big Steam and GOG sales, and study timers.")
                .text(CommandLinks.render("**Admins:** `/free-games setup` creates the channels and ping roles in one click.\n"
                        + "**Everyone:** `/free-games list` shows what's free now, `/deals` the best sales, `/help` everything else."))
                .buttons(Button.link(SUPPORT, "Support server"))
                .build();
    }

    /** The system channel when the bot can post there, otherwise the highest public text channel it can post in. */
    static Optional<TextChannel> channel(Guild guild) {
        var self = guild.getSelfMember();
        var system = guild.getSystemChannel();
        if (system != null && canPost(self, system)) return Optional.of(system);
        var everyone = guild.getPublicRole();
        return guild.getTextChannels().stream()
                .filter(c -> canPost(self, c) && everyone.hasPermission(c, Permission.VIEW_CHANNEL))
                .min(Comparator.comparingInt(TextChannel::getPositionRaw));
    }

    private static boolean canPost(net.dv8tion.jda.api.entities.Member self, TextChannel channel) {
        return self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
    }
}
