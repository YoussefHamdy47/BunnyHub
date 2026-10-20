package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Posts through the bot's existing gateway connection; JDA handles Discord's rate limits and Retry-After. */
public final class JdaMailPoster implements MailPoster {
    /** How far back the duplicate check looks. Retries happen within minutes, so this is plenty. */
    static final int HISTORY = 50;
    private final Supplier<JDA> jda;

    public JdaMailPoster(Supplier<JDA> jda) { this.jda = jda; }

    @Override public Optional<String> problem(String guildId, String channelId) {
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return Optional.of("not connected to Discord");
        var guild = client.getGuildById(guildId);
        if (guild == null) return Optional.of("the bot is not in server " + guildId);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) return Optional.of("channel " + channelId + " was not found in " + guild.getName());
        var self = guild.getSelfMember();
        for (Permission needed : new Permission[]{Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY})
            if (!self.hasPermission(channel, needed))
                return Optional.of("missing " + needed.getName() + " in #" + channel.getName());
        return Optional.empty();
    }

    @Override public CompletableFuture<String> post(String channelId, MessageCreateData message, String nonce) {
        GuildMessageChannel channel = channel(channelId);
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("channel unavailable"));
        return channel.sendMessage(message).setNonce(nonce).submit().thenApply(Message::getId);
    }

    @Override public Optional<Boolean> alreadyPosted(String channelId, String ref) {
        GuildMessageChannel channel = channel(channelId);
        if (channel == null) return Optional.empty();
        try {
            String self = channel.getJDA().getSelfUser().getId();
            for (Message message : channel.getHistory().retrievePast(HISTORY).submit().get(20, TimeUnit.SECONDS)) {
                if (!message.getAuthor().getId().equals(self)) continue;
                for (MessageEmbed embed : message.getEmbeds())
                    if (embed.getFooter() != null && ref.equals(embed.getFooter().getText())) return Optional.of(true);
            }
            return Optional.of(false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    private GuildMessageChannel channel(String channelId) {
        JDA client = jda.get();
        return client == null ? null : client.getChannelById(GuildMessageChannel.class, channelId);
    }
}
