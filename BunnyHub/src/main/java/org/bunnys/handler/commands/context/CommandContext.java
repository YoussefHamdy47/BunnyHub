package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.Collection;
import java.util.List;

/**
 * One command invocation, however it arrived.
 *
 * <p>The bot is losing the Message Content intent, so prefix commands are gone. Discord
 * still delivers content when the bot is explicitly mentioned, which leaves two live
 * entry points: a slash command and an {@code @BotName command} mention. Rather than
 * write each command twice, a command is written once against this interface and the
 * two listeners each supply an implementation.
 *
 * <p>The two are not identical, and this type is deliberate about where they differ:
 * <ul>
 *   <li><b>Ephemeral replies</b> only exist for interactions. {@link #isEphemeralCapable()}
 *       reports that; a mention context silently sends a normal message instead, since
 *       refusing to answer would be worse than answering publicly.</li>
 *   <li><b>Modals</b> can only be opened from an interaction. {@link #replyModal} returns
 *       false on the mention path so the caller can direct the user to the slash
 *       command rather than appearing to do nothing.</li>
 * </ul>
 *
 * <p>Call {@link #defer()} before any network or database work; both implementations
 * need it, for different reasons - the interaction has a three-second acknowledgement
 * window, and the message path uses it to start typing.
 */
public abstract class CommandContext implements AccessContext {

    private java.util.Map<String, org.bunnys.handler.CooldownStore.Reservation> cooldowns;

    public final synchronized void recordCooldown(String path, org.bunnys.handler.CooldownStore.Reservation reservation) {
        if (cooldowns == null) cooldowns = new java.util.HashMap<>();
        cooldowns.put(path, reservation);
    }

    public final synchronized void releaseCooldown(String path) {
        if (cooldowns == null) return;
        var reservation = cooldowns.remove(path);
        if (reservation != null) reservation.release();
    }

    private volatile boolean deferred;
    private volatile boolean deferredEphemeral;

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public abstract JDA getJDA();

    public abstract User getUser();

    /** Null outside a guild. */
    public abstract Member getMember();

    /** Null outside a guild. */
    public abstract Guild getGuild();

    public abstract MessageChannel getChannel();

    /** True when this arrived as a slash command. */
    public abstract boolean isSlash();

    /** True when {@code ephemeral} flags on replies will actually be honoured. */
    public boolean isEphemeralCapable() {
        return isSlash();
    }

    public boolean isFromGuild() {
        return getGuild() != null;
    }

    /** How the user invoked this, for error copy that needs to name it back to them. */
    public String invocationLabel(String commandPath) {
        return isSlash() ? "/" + commandPath : "@" + getJDA().getSelfUser().getName() + " " + commandPath;
    }

    // ------------------------------------------------------------------
    // Replying
    // ------------------------------------------------------------------

    protected boolean isDeferred() {
        return deferred;
    }

    protected boolean isDeferredEphemeral() {
        return deferredEphemeral;
    }

    public final void defer() {
        defer(false);
    }

    /** Acknowledges the invocation. Safe to call at most once; later calls are ignored. */
    public final synchronized void defer(boolean ephemeral) {
        if (deferred)
            return;
        deferred = true;
        deferredEphemeral = ephemeral && isEphemeralCapable();
        doDefer(deferredEphemeral);
    }

    protected abstract void doDefer(boolean ephemeral);

    /** Imports a router's completed acknowledgement without sending it twice. */
    public final synchronized void markDeferred(boolean ephemeral) {
        deferredEphemeral = ephemeral && isEphemeralCapable();
        deferred = true;
    }

    public abstract void replyMessage(net.dv8tion.jda.api.utils.messages.MessageCreateData message);

    /**
     * The single reply primitive. Everything else here funnels into it.
     *
     * @param embed      required; every reply in this bot is an embed
     * @param rows       may be null or empty
     * @param file       may be null
     * @param ephemeral  honoured only when {@link #isEphemeralCapable()}
     */
    protected abstract void doReply(MessageEmbed embed,
                                    Collection<? extends ActionRow> rows,
                                    FileUpload file,
                                    boolean ephemeral);

    public void reply(MessageEmbed embed) {
        doReply(embed, List.of(), null, false);
    }

    public void reply(MessageEmbed embed, boolean ephemeral) {
        doReply(embed, List.of(), null, ephemeral);
    }

    public void reply(MessageEmbed embed, Collection<? extends ActionRow> rows) {
        doReply(embed, rows, null, false);
    }

    public void reply(MessageEmbed embed, Collection<? extends ActionRow> rows, boolean ephemeral) {
        doReply(embed, rows, null, ephemeral);
    }

    public void replyWithFile(MessageEmbed embed, Collection<? extends ActionRow> rows, FileUpload file) {
        doReply(embed, rows, file, false);
    }

    /**
     * A plain message with no embed of our own.
     *
     * <p>The one deliberate exception to "every reply in this bot is an embed", and it
     * exists for images. An image inside an embed is fetched through Discord's proxy and
     * re-laid-out as the embed resolves, which reads as a flash on anything large. A bare
     * URL in the message content is unfurled natively - same picture, no reflow, and it
     * opens full size on click instead of being boxed to embed width.
     *
     * <p>Use it only where the image <em>is</em> the message. Anything with copy around it
     * still belongs in an embed, where the house style applies.
     */
    public abstract void replyContent(String content, Collection<? extends ActionRow> rows);

    /** How long a reply that cannot be ephemeral is left on screen before it removes itself. */
    public static final int TRANSIENT_SECONDS = 15;

    /**
     * A reply that must not linger in the channel.
     *
     * <p>For errors and refusals: information the person who ran the command needs and
     * nobody else does. Ephemeral where the context supports it, and otherwise sent
     * normally and deleted after {@link #TRANSIENT_SECONDS} - so the mention path, which
     * has no ephemeral messages at all, still does not leave crash notices lying around
     * in a busy channel.
     */
    public abstract void replyTransient(MessageEmbed embed);

    /**
     * Whether {@link #replyTransient} will delete its message rather than hide it.
     *
     * <p>Callers use this to decide whether to say so in the copy. An ephemeral reply is
     * already invisible to everyone else and needs no warning; a self-deleting public one
     * does, because anything the reader wants to keep has to be copied before it goes.
     */
    public abstract boolean transientRepliesVanish();

    /**
     * Opens a modal.
     *
     * @return false when this context cannot show one (any mention invocation), leaving
     *         the caller to explain why rather than failing silently.
     */
    public abstract boolean replyModal(Modal modal);

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    /** Null when the option was not supplied. */
    public abstract String getString(String name);

    public String getString(String name, String fallback) {
        String value = getString(name);
        return value != null ? value : fallback;
    }

    /** Null when the option was not supplied or is not a whole number. */
    public abstract Integer getInt(String name);

    public int getInt(String name, int fallback) {
        Integer value = getInt(name);
        return value != null ? value : fallback;
    }

    /** Null when the option was not supplied or is not a boolean. */
    public abstract Boolean getBool(String name);

    public boolean getBool(String name, boolean fallback) {
        Boolean value = getBool(name);
        return value != null ? value : fallback;
    }

    /** Null when the option was not supplied or names nobody reachable. */
    public abstract User getUserOption(String name);

    /**
     * The same option resolved as a guild member.
     *
     * <p>Null outside a guild, when the option was not supplied, or when the named user
     * is not a member of this guild. Neither implementation performs a blocking REST
     * fetch to find one - both run on the bounded command pool.
     */
    public abstract Member getMemberOption(String name);

    /** Null when the option was not supplied or names no channel in this guild. */
    public abstract GuildChannel getChannelOption(String name);

    /** The supplied user, or the caller when the option was omitted. */
    public User getUserOptionOrSelf(String name) {
        User user = getUserOption(name);
        return user != null ? user : getUser();
    }
}
