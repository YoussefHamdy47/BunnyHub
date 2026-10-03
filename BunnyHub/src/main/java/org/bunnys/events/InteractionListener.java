package org.bunnys.events;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.CommandGate;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.commands.context.AccessContext;
import org.bunnys.handler.commands.context.InteractionContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.handler.commands.context.UserContext;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.handler.router.selects.SelectRouter;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.SystemEmbeds;
import org.jetbrains.annotations.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Routes every interaction: slash and user-menu commands, autocomplete, and component/modal events. */
@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class InteractionListener extends BunnyEvent {
    /** Discord drops autocomplete answers after three seconds; leave headroom for the reply itself. */
    static final long AUTOCOMPLETE_BUDGET_MILLIS = 2_500;

    public InteractionListener(BunnyHub client) {
        super(client);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (!event.getUser().isBot()) ButtonRouter.handle(client, event);
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (!event.getUser().isBot()) SelectRouter.handle(client, event);
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        ModalRouter.handle(client, event);
    }

    // ------------------------------------------------------------------ autocomplete

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        try {
            client.executeAutocomplete(event.getUser().getId(), () -> autocomplete(event));
        } catch (RejectedExecutionException busy) {
            answer(event, List.of());
        }
    }

    static boolean autocompleteExpired(OffsetDateTime created, Instant now) {
        return created != null && Duration.between(created.toInstant(), now).toMillis() > AUTOCOMPLETE_BUDGET_MILLIS;
    }

    private void autocomplete(CommandAutoCompleteInteractionEvent event) {
        // A request that waited out its window in the queue is superseded by later
        // keystrokes; answering it only wastes a database read and logs an unknown interaction.
        if (autocompleteExpired(event.getTimeCreated(), Instant.now())) return;
        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(event.getName());
        BunnySubcommand subcommand = command == null || event.getSubcommandName() == null ? null
                : command.resolve(event.getSubcommandGroup(), event.getSubcommandName());
        if (command == null || (event.getSubcommandName() != null && subcommand == null)) {
            answer(event, List.of());
            return;
        }
        try {
            var access = new AccessContext.Snapshot(event.getUser(), event.getMember(), event.getGuild(),
                    event.getChannel() instanceof MessageChannel channel ? channel : null);
            if (CommandGate.checkAccess(access, registry, command, subcommand) != null) {
                answer(event, List.of());
                return;
            }
            List<String> raw = subcommand != null ? subcommand.autocomplete(client, event) : command.autocomplete(client, event);
            String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
            answer(event, raw == null ? List.of() : raw.stream()
                    .filter(Objects::nonNull)
                    .filter(choice -> !choice.isBlank() && choice.length() <= 100)
                    .filter(choice -> choice.toLowerCase(Locale.ROOT).contains(typed))
                    .distinct()
                    .limit(25)
                    .toList());
        } catch (RuntimeException failure) {
            BunnyLog.error("Autocomplete Exception (" + event.getName() + ")", failure);
            answer(event, List.of());
        }
    }

    /** Late answers are rejected by Discord; that is expected under load, not an error. */
    private static void answer(CommandAutoCompleteInteractionEvent event, List<String> choices) {
        event.replyChoiceStrings(choices).queue(null, ignored -> {});
    }

    // ------------------------------------------------------------------ commands

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getUser().isBot()) return;
        BunnyCommand command = client.getCommandRegistry().resolveCommand(event.getName());
        if (command == null) {
            // A stale registration (e.g. a removed command still cached by the client)
            // would otherwise leave the user on "The application did not respond".
            refuse(event, "This command is no longer available.");
            return;
        }
        BunnySubcommand subcommand = event.getSubcommandName() == null ? null
                : command.resolve(event.getSubcommandGroup(), event.getSubcommandName());
        if (event.getSubcommandName() != null && subcommand == null) {
            refuse(event, "This command has changed. Please reopen the command picker and try again.");
            return;
        }
        handleCommand(event, new SlashContext(event), command, subcommand);
    }

    @Override
    public void onUserContextInteraction(@NotNull UserContextInteractionEvent event) {
        if (event.getUser().isBot()) return;
        BunnyCommand command = client.getCommandRegistry().resolveUserContext(event.getName());
        if (command == null) {
            refuse(event, "This command is no longer available.");
            return;
        }
        handleCommand(event, new UserContext(event), command, null);
    }

    private static void refuse(GenericCommandInteractionEvent event, String message) {
        event.replyEmbeds(Embeds.error("Unavailable", message)).setEphemeral(true).queue(null, ignored -> {});
    }

    private void handleCommand(GenericCommandInteractionEvent event, InteractionContext context,
                               BunnyCommand command, BunnySubcommand subcommand) {
        var denial = CommandGate.check(context, client.getCommandRegistry(), command, subcommand);
        if (denial != null) {
            context.reply(denial, true);
            return;
        }
        Runnable execute = () -> {
            try {
                if (subcommand != null) subcommand.execute(client, context);
                else command.execute(client, context);
            } catch (Throwable error) {
                // Errors too: otherwise a linkage/assertion failure leaves the user on "thinking...".
                if (error instanceof VirtualMachineError fatal) throw fatal;
                String reference = ErrorReporter.report(event.getFullCommandName(),
                        event.isFromGuild() && event.getGuild() != null ? event.getGuild().getId() : null, error);
                var failure = SystemEmbeds.crashed(reference, false);
                if (event.isAcknowledged() && command.isDeferBeforeDispatch())
                    // Replace the whole response so buttons from a half-finished reply stay unclickable.
                    event.getHook().editOriginal(new MessageEditBuilder().setContent("").setEmbeds(failure).setComponents().build())
                            .queue(null, ignored -> {});
                else context.replyTransient(failure);
            }
        };
        if (!command.isDeferBeforeDispatch()) {
            dispatch(event, context, command, subcommand, execute);
            return;
        }
        boolean ephemeral = context.getBool("ephemeral", command.defaultEphemeral(context));
        event.deferReply(ephemeral).queue(hook -> {
            context.markDeferred(ephemeral);
            dispatch(event, context, command, subcommand, execute);
        }, error -> CommandGate.release(context, command, subcommand));
    }

    private void dispatch(GenericCommandInteractionEvent event, InteractionContext context, BunnyCommand command,
                          BunnySubcommand subcommand, Runnable work) {
        try {
            client.executeForUser(event.getUser().getId(), work);
        } catch (RejectedExecutionException busy) {
            CommandGate.release(context, command, subcommand);
            if (event.isAcknowledged())
                context.replyTransient(SystemEmbeds.busy());
            else context.reply(SystemEmbeds.busy(), true);
        }
    }
}
