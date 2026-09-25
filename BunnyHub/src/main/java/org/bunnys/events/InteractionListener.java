package org.bunnys.events;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.utils.BunnyLog;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
public class InteractionListener extends BunnyEvent {


    public InteractionListener(BunnyHub client) {
        super(client);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getUser().isBot())
            return;

        ButtonRouter.handle(client, event);
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        ModalRouter.handle(client, event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        try {
            client.executeAutocomplete(event.getUser().getId(), () -> autocomplete(event));
        } catch (java.util.concurrent.RejectedExecutionException error) {
            event.replyChoiceStrings(Collections.emptyList()).queue(null, ignored -> {});
        }
    }

    /** Discord drops autocomplete answers after three seconds; leave headroom for the reply itself. */
    static final long AUTOCOMPLETE_BUDGET_MILLIS = 2_500;

    static boolean autocompleteExpired(java.time.OffsetDateTime created, java.time.Instant now) {
        return created != null
                && java.time.Duration.between(created.toInstant(), now).toMillis() > AUTOCOMPLETE_BUDGET_MILLIS;
    }

    private void autocomplete(CommandAutoCompleteInteractionEvent event) {
        // A request that waited out its window in the queue is superseded by later
        // keystrokes; answering it only wastes a database read and logs an unknown interaction.
        if (autocompleteExpired(event.getTimeCreated(), java.time.Instant.now()))
            return;
        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(event.getName());

        if (command == null) {
            event.replyChoiceStrings(Collections.emptyList()).queue();
            return;
        }

        BunnySubcommand subcommand = null;
        if (event.getSubcommandName() != null)
            subcommand = command.resolve(event.getSubcommandGroup(), event.getSubcommandName());

        if (event.getSubcommandName() != null && subcommand == null) {
            event.replyChoiceStrings(Collections.emptyList()).queue();
            return;
        }
        try {
            var access = new org.bunnys.handler.commands.context.AccessContext.Snapshot(event.getUser(),
                    event.getMember(), event.getGuild(), event.getChannel() instanceof
                    net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel ? channel : null);
            if (org.bunnys.handler.commands.CommandGate.checkAccess(access, registry, command, subcommand) != null) {
                event.replyChoiceStrings(Collections.emptyList()).queue();
                return;
            }
            List<String> rawChoices = (subcommand != null)
                    ? subcommand.autocomplete(client, event)
                    : command.autocomplete(client, event);

            if (rawChoices == null || rawChoices.isEmpty()) {
                event.replyChoiceStrings(Collections.emptyList()).queue();
                return;
            }

            String userInput = event.getFocusedOption().getValue().toLowerCase(java.util.Locale.ROOT);

            List<String> filteredChoices = rawChoices.stream()
                    .filter(Objects::nonNull)
                    .filter(choice -> !choice.isBlank() && choice.length() <= 100)
                    .filter(choice -> choice.toLowerCase(java.util.Locale.ROOT).contains(userInput))
                    .distinct()
                    .limit(25)
                    .toList();

            // Late answers are rejected by Discord; that is expected under load, not an error.
            event.replyChoiceStrings(filteredChoices).queue(null, ignored -> {});

        } catch (Exception err) {
            BunnyLog.error("Autocomplete Exception (" + event.getName() + ")", err);
            event.replyChoiceStrings(Collections.emptyList()).queue(null, ignored -> {});
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getUser().isBot())
            return;

        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(event.getName());

        if (command == null) {
            // A stale registration (e.g. a removed command still cached by the client)
            // would otherwise leave the user on "The application did not respond".
            event.reply("This command is no longer available.").setEphemeral(true).queue(null, ignored -> {});
            return;
        }

        BunnySubcommand subcommand = null;
        if (event.getSubcommandName() != null)
            subcommand = command.resolve(event.getSubcommandGroup(), event.getSubcommandName());

        if (event.getSubcommandName() != null && subcommand == null) {
            event.reply("This command has changed. Please reopen the command picker and try again.")
                    .setEphemeral(true).queue();
            return;
        }
        var context = new org.bunnys.handler.commands.context.SlashContext(event);
        handleCommand(event, context, command, subcommand);
    }

    @Override public void onUserContextInteraction(net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent event) {
        if (event.getUser().isBot()) return;
        BunnyCommand command = client.getCommandRegistry().resolveUserContext(event.getName());
        if (command == null) {
            event.reply("This command is no longer available.").setEphemeral(true).queue(null, ignored -> {});
            return;
        }
        handleCommand(event, new org.bunnys.handler.commands.context.UserContext(event), command, null);
    }

    private void handleCommand(net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent event,
            org.bunnys.handler.commands.context.InteractionContext context, BunnyCommand command, BunnySubcommand subcommand) {
        var registry = client.getCommandRegistry();
        var denial = org.bunnys.handler.commands.CommandGate.check(context, registry, command, subcommand);
        if (denial != null) { context.reply(denial, true); return; }
        BunnySubcommand selected = subcommand;
        Runnable execute = () -> {
            try {
                if (selected != null) selected.execute(client, context);
                else command.execute(client, context);
            } catch (Throwable error) {
                // Errors too: otherwise a linkage/assertion failure leaves the user on "thinking...".
                if (error instanceof VirtualMachineError fatal) throw fatal;
                String reference = org.bunnys.utils.ErrorReporter.report(event.getFullCommandName(),
                        event.isFromGuild() ? event.getGuild().getId() : null, error);
                var failure = org.bunnys.utils.SystemEmbeds.crashed(reference, false);
                if (event.isAcknowledged() && command.isDeferBeforeDispatch())
                    // Replace the whole response so buttons from a half-finished reply stay unclickable.
                    event.getHook().editOriginal(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                            .setContent("").setEmbeds(failure).setComponents().build()).queue(null, ignored -> {});
                else context.replyTransient(failure);
            }
        };
        if (command.isDeferBeforeDispatch()) {
            boolean ephemeral = context.getBool("ephemeral", command.defaultEphemeral(context));
            event.deferReply(ephemeral).queue(hook -> {
                context.markDeferred(ephemeral);
                dispatchCommand(event, context, command, selected, execute);
            }, error -> org.bunnys.handler.commands.CommandGate.release(context, command, selected));
        } else {
            dispatchCommand(event, context, command, selected, execute);
        }
    }

    @Override public void onStringSelectInteraction(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent event) {
        if (!event.getUser().isBot()) org.bunnys.handler.router.selects.SelectRouter.handle(client, event);
    }

    private void dispatchCommand(net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent event,
            org.bunnys.handler.commands.context.InteractionContext context, BunnyCommand command,
            BunnySubcommand subcommand, Runnable work) {
        try {
            client.executeForUser(event.getUser().getId(), work);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            org.bunnys.handler.commands.CommandGate.release(context, command, subcommand);
            if (event.isAcknowledged())
                event.getHook().editOriginal("The bot is busy. Please try again shortly.").queue();
            else context.reply(org.bunnys.utils.SystemEmbeds.busy(), true);
        }
    }
}
