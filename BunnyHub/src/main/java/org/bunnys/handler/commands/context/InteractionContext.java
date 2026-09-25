package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.utils.BunnyLog;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared acknowledgements and replies for slash commands and context menus. */
public abstract class InteractionContext extends CommandContext {
    @Override public boolean isEphemeralCapable() { return true; }

    protected final net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent event;

    protected InteractionContext(net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent event) {
        this.event = event;
    }

    @Override public void replyMessage(net.dv8tion.jda.api.utils.messages.MessageCreateData message) {
        if (event.isAcknowledged()) event.getHook().sendMessage(message).setEphemeral(isDeferredEphemeral())
                .queue(null, e -> replyFailed("message", e));
        else event.reply(message).queue(null, e -> replyFailed("message", e));
    }

    public net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent event() {
        return event;
    }

    @Override
    public JDA getJDA() {
        return event.getJDA();
    }

    @Override
    public User getUser() {
        return event.getUser();
    }

    @Override
    public Member getMember() {
        return event.getMember();
    }

    @Override
    public Guild getGuild() {
        return event.getGuild();
    }

    @Override
    public MessageChannel getChannel() {
        return event.getChannel() instanceof MessageChannel channel ? channel : null;
    }

    @Override
    public boolean isSlash() {
        return event instanceof SlashCommandInteractionEvent;
    }

    /**
     * Reports a reply that Discord rejected.
     *
     * <p>These used to be discarded with {@code e -> {}}. A rejected reply is invisible
     * from the outside - the interaction simply sits on "thinking..." forever - so
     * throwing the reason away left the single most confusing failure in the bot with
     * no trace anywhere. If it can't be delivered, it gets logged.
     */
    private void replyFailed(String stage, Throwable error) {
        BunnyLog.error("[SlashContext] /" + event.getFullCommandName() + " " + stage + " failed", error);
    }

    @Override
    protected void doDefer(boolean ephemeral) {
        event.deferReply().setEphemeral(ephemeral).queue(null, e -> replyFailed("defer", e));
    }

    @Override
    protected void doReply(MessageEmbed embed, Collection<? extends ActionRow> rows,
                           FileUpload file, boolean ephemeral) {
        List<ActionRow> components = rows == null ? List.of() : List.copyOf(rows);

        if (event.isAcknowledged()) {
            WebhookMessageCreateAction<?> action = event.getHook()
                    .sendMessageEmbeds(embed)
                    // A deferral fixes ephemerality for the whole interaction, so a
                    // follow-up cannot contradict it.
                    .setEphemeral(isDeferredEphemeral());

            if (!components.isEmpty())
                action = action.setComponents(components);
            if (file != null)
                action = action.setFiles(file);

            action.queue(null, e -> replyFailed("follow-up", e));
            return;
        }

        ReplyCallbackAction action = event.replyEmbeds(embed).setEphemeral(ephemeral);

        if (!components.isEmpty())
            action = action.setComponents(components);
        if (file != null)
            action = action.setFiles(file);

        action.queue(null, e -> replyFailed("reply", e));
    }

    @Override
    public void replyTransient(MessageEmbed embed) {
        // Not yet answered: an ephemeral reply is always available and leaves nothing.
        if (!event.isAcknowledged()) {
            event.replyEmbeds(embed).setEphemeral(true)
                    .queue(null, e -> replyFailed("transient reply", e));
            return;
        }

        // Deferred ephemerally: the follow-up inherits that, so it is already private.
        if (isDeferredEphemeral()) {
            event.getHook().sendMessageEmbeds(embed).setEphemeral(true)
                    .queue(null, e -> replyFailed("transient follow-up", e));
            return;
        }

        // Deferred publicly. The interaction is committed to a visible reply, so the
        // only way to keep a crash notice from becoming permanent channel furniture is
        // to send it and take it back down.
        // Delete through the interaction webhook: a channel delete needs the bot to see the
        // channel, which DMs and user-installed contexts do not guarantee.
        event.getHook().sendMessageEmbeds(embed).queue(
                sent -> event.getHook().deleteMessageById(sent.getId())
                        .queueAfter(TRANSIENT_SECONDS, TimeUnit.SECONDS, null, e -> {}),
                e -> replyFailed("transient follow-up", e));
    }

    @Override
    public void replyContent(String content, Collection<? extends ActionRow> rows) {
        List<ActionRow> components = rows == null ? List.of() : List.copyOf(rows);

        if (event.isAcknowledged()) {
            var followUp = event.getHook().sendMessage(content).setEphemeral(isDeferredEphemeral());
            if (!components.isEmpty())
                followUp = followUp.setComponents(components);
            followUp.queue(null, e -> replyFailed("content follow-up", e));
            return;
        }

        var reply = event.reply(content);
        if (!components.isEmpty())
            reply = reply.setComponents(components);
        reply.queue(null, e -> replyFailed("content reply", e));
    }

    @Override
    public boolean transientRepliesVanish() {
        // Only the publicly-deferred case sends something everyone can see.
        return isDeferred() && !isDeferredEphemeral();
    }

    @Override
    public boolean replyModal(Modal modal) {
        if (event.isAcknowledged()) return false;
        event.replyModal(modal).queue(null, e -> replyFailed("modal", e));
        return true;
    }

    @Override
    public String getString(String name) {
        OptionMapping option = event.getOption(name);
        return option != null ? option.getAsString() : null;
    }

    @Override
    public Integer getInt(String name) {
        OptionMapping option = event.getOption(name);
        if (option == null)
            return null;
        try {
            return option.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Boolean getBool(String name) {
        OptionMapping option = event.getOption(name);
        if (option == null)
            return null;
        try {
            return option.getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public User getUserOption(String name) {
        OptionMapping option = event.getOption(name);
        return option != null ? option.getAsUser() : null;
    }

    @Override
    public Member getMemberOption(String name) {
        OptionMapping option = event.getOption(name);
        if (option == null)
            return null;

        // Discord resolves member data into the interaction payload, so this needs no
        // member cache and no intent. Null in a DM, or if the option is not a user.
        try {
            return option.getAsMember();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public GuildChannel getChannelOption(String name) {
        OptionMapping option = event.getOption(name);
        return option != null ? option.getAsChannel() : null;
    }
}
