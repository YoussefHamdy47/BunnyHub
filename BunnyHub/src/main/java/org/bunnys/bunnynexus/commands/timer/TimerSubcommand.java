package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.Embeds;

/**
 * Shared shape of every {@code /timer} subcommand: slash-only (the parent disables mentions), already deferred by
 * the handler, and answered by editing that deferred reply. Authored input and state failures are shown to the
 * user as a private or self-deleting error; anything else propagates to the handler, which logs it with a
 * reference because it is a bug.
 */
abstract class TimerSubcommand extends BunnySubcommand {
    @Override
    public final void execute(BunnyHub client, CommandContext context) {
        var event = ((SlashContext) context).event();
        try {
            run(event, event.getUser().getId());
        } catch (InteractionErrors.InputFailure | InteractionErrors.StateFailure failure) {
            // Private when the reply was deferred privately; otherwise it removes itself after a few seconds.
            context.replyTransient(Embeds.error("Action failed", InteractionErrors.userMessage(failure)));
        }
    }

    abstract void run(SlashCommandInteractionEvent event, String userId);

    static String string(SlashCommandInteractionEvent event, String name) {
        var option = event.getOption(name);
        return option == null ? null : option.getAsString();
    }

    static Integer integer(SlashCommandInteractionEvent event, String name) {
        var option = event.getOption(name);
        return option == null ? null : option.getAsInt();
    }
}
