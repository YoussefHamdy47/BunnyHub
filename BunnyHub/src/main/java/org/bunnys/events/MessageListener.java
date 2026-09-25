package org.bunnys.events;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.BunnySubcommandGroup;
import org.bunnys.handler.commands.CommandGate;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.commands.context.MentionContext;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mention entry point: {@code @BotName command [subcommand] [args]}.
 *
 * <p>This replaces prefix routing entirely. Without the Message Content intent
 * {@code getContentRaw()} is empty for ordinary messages, so a {@code !command} listener
 * would never fire again. Discord does still deliver content when the bot is explicitly
 * mentioned, and that is the whole surface this class serves.
 *
 * <p>Resolution, permission checks and cooldowns all run through {@link CommandGate},
 * exactly as the slash path does. The only real divergences are structural: replies
 * cannot be ephemeral, and modals cannot be opened at all - both handled explicitly
 * rather than silently.
 */
public class MessageListener extends BunnyEvent {

    /**
     * The bot's own leading mention, in both the plain and legacy nickname forms.
     *
     * <p>Built once on first use rather than per message: this runs for every message
     * the bot can see in every guild, and compiling a regex on that path would be real
     * work done overwhelmingly to reject ordinary conversation.
     */
    private volatile Pattern mentionPattern;

    /**
     * Throttle for replies the command gate never sees: bare pings, branch help, slash-only
     * notices, option errors and denials. Without it, repeated pings make the bot post one
     * reply per message, which is an easy way to spam a channel through the bot.
     */
    private static final org.bunnys.handler.CooldownStore NOTICES =
            new org.bunnys.handler.CooldownStore("mention.notices", 20_000);
    static final long NOTICE_COOLDOWN_MILLIS = 5_000;

    static boolean mayNotify(String userId) {
        return NOTICES.reserve(userId, NOTICE_COOLDOWN_MILLIS).remainingMillis() == 0;
    }

    public MessageListener(BunnyHub client) {
        super(client);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage())
            return;

        String content = event.getMessage().getContentRaw();

        // Fast reject before any regex. A command mention must start with '<', so this
        // discards almost every message in one character comparison. Content is also
        // empty for anything that does not mention us, since MESSAGE_CONTENT is off.
        if (content.isEmpty() || content.charAt(0) != '<')
            return;

        Matcher mention = mentionPattern(event.getJDA().getSelfUser().getId()).matcher(content);

        // Must be the *leading* token. A mention anywhere else is ordinary conversation.
        if (!mention.find())
            return;

        String remainder = content.substring(mention.end()).trim();

        // A bare ping is a question, not a command - answer it with the help pointer.
        if (remainder.isEmpty()) {
            if (!mayNotify(event.getAuthor().getId())) return;
            event.getMessage().replyEmbeds(SystemEmbeds.notice("Hey there",
                            "Use `/timer` to study, or `@" + event.getJDA().getSelfUser().getName()
                                    + " avatar`, to view an avatar."))
                    .mentionRepliedUser(false).queue(null, e -> {});
            return;
        }

        String[] parts = remainder.split("\\s+", 2);
        String commandName = parts[0].toLowerCase(java.util.Locale.ROOT);
        String tail = parts.length > 1 ? parts[1] : "";

        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(commandName);

        // Unknown word after a ping is conversation, not a typo worth correcting.
        if (command == null)
            return;

        BunnySubcommand subcommand = null;

        // Built from canonical names, not from what was typed. Somebody who reached this
        // through an alias still gets told about `leg leaderboard` rather than `lb`,
        // because that is the name the slash command and the help menu use.
        String path = command.getName();

        if (command.hasBranches()) {
            String[] first = tail.split("\\s+", 2);
            String branch = first[0].toLowerCase(java.util.Locale.ROOT);
            tail = first.length > 1 ? first[1] : "";

            // Groups first: `/admin logging set` needs two tokens consumed, a plain
            // subcommand only one.
            BunnySubcommandGroup group = command.resolveGroup(branch);

            if (group != null) {
                String[] second = tail.split("\\s+", 2);
                String action = second[0].toLowerCase(java.util.Locale.ROOT);
                tail = second.length > 1 ? second[1] : "";

                subcommand = group.resolve(action);

                if (subcommand == null) {
                    replyEmbed(event, branchHelp(event, path + " " + group.getName(),
                            group.getSubcommands().keySet()));
                    return;
                }

                path = path + " " + group.getName() + " " + subcommand.getName();
            } else {
                subcommand = command.resolveSubcommand(branch);

                if (subcommand == null) {
                    // Nothing named a branch. If the command nominates a default one, run
                    // that and hand it back the token, which was never a branch name and
                    // may well be an argument. `@BotName ooc` is the point of this, but so
                    // is anything that reads naturally without its most common verb.
                    subcommand = command.defaultSubcommand();

                    if (subcommand == null) {
                        Set<String> options = new LinkedHashSet<>(command.getSubcommands().keySet());
                        options.addAll(command.getSubcommandGroups().keySet());
                        replyEmbed(event, branchHelp(event, path, options));
                        return;
                    }

                    tail = first.length > 1 ? first[0] + " " + first[1] : first[0];
                }

                path = path + " " + subcommand.getName();
            }
        }

        // Anything that opens a modal is genuinely unreachable from a message.
        boolean mentionable = command.isMentionEnabled()
                && (subcommand == null || subcommand.isMentionEnabled());
        if (!mentionable) {
            replyEmbed(event, SystemEmbeds.slashOnly(path));
            return;
        }

        // Snapshotted for the lambda below: all three are reassigned while resolving a
        // group/subcommand branch, so none is effectively final at this point.
        final BunnySubcommand target = subcommand;
        final String arguments = tail;
        final String invoked = path;

        MentionContext ctx = new MentionContext(
                event, CommandGate.optionsFor(command, target), arguments);

        // Authorize before validating so restricted commands do not describe their options
        // to people who cannot run them; the cooldown is only claimed once input is valid.
        MessageEmbed denial = CommandGate.checkAccess(ctx, registry, command, target);
        String invalid = denial == null ? ctx.validationError(CommandGate.optionsFor(command, target)) : null;
        if (denial == null && invalid == null)
            denial = CommandGate.check(ctx, registry, command, target);
        if (denial != null || invalid != null) {
            if (mayNotify(event.getAuthor().getId()))
                ctx.replyTransient(denial != null ? denial : SystemEmbeds.warning("Check your options", invalid));
            return;
        }

        try {
            client.executeForUser(event.getAuthor().getId(), () -> {
                try {
                    if (target != null)
                        target.execute(client, ctx);
                    else
                        command.execute(client, ctx);
                } catch (Throwable err) {
                    String reference = ErrorReporter.report("@mention " + invoked,
                            ctx.getGuild() == null ? null : ctx.getGuild().getId(), err);
                    ctx.replyTransient(SystemEmbeds.crashed(reference, ctx.transientRepliesVanish()));
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[MessageListener] @mention " + invoked + " rejected under load");
            CommandGate.release(ctx, command, target);
            ctx.replyTransient(SystemEmbeds.busy());
        }
    }

    private static void replyEmbed(MessageReceivedEvent event, MessageEmbed embed) {
        if (!mayNotify(event.getAuthor().getId())) return;
        event.getMessage().replyEmbeds(embed).mentionRepliedUser(false).queue(null, e -> {});
    }

    /** Lists what can follow a partial command path, with a worked example. */
    private static MessageEmbed branchHelp(MessageReceivedEvent event, String path, Set<String> options) {
        String first = options.isEmpty() ? "" : options.iterator().next();

        return SystemEmbeds.warning("Which One?",
                "`" + path + "` needs one of: " + String.join(", ", options)
                        + "\nFor example: `@" + event.getJDA().getSelfUser().getName()
                        + " " + path + " " + first + "`");
    }

    /**
     * The compiled leading-mention pattern.
     *
     * <p>Lazily built because the bot's own ID is not known until after login. The race
     * is benign: two threads may each compile a pattern, but both are identical and
     * either is correct, so no lock is warranted on this path.
     */
    private Pattern mentionPattern(String selfId) {
        Pattern cached = mentionPattern;
        if (cached == null) {
            cached = Pattern.compile("^<@!?" + selfId + ">");
            mentionPattern = cached;
        }
        return cached;
    }
}
