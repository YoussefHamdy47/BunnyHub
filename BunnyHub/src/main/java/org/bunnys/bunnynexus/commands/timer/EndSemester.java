package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.utils.Embeds;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.concurrent.TimeUnit;

public final class EndSemester extends TimerSubcommand {
    private static final String WARNING_TITLE = AppDesign.Emojis.STOP + " Semester Termination Protocol";
    private static final long PROMPT_SECONDS = 60;

    public EndSemester() {
        setName("end_semester");
        setDescription("Permanently conclude and archive your current academic semester.");
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        Timers timers = new Timers(userId, event);
        // Bind the confirmation to the semester state the user is being warned about.
        String revision = timers.revisionToken();
        // No emoji argument: WARNING_TITLE already carries it and is compared verbatim by the timeout below.
        var warning = Embeds.of(null, WARNING_TITLE)
                .setDescription("""
                        You are about to permanently conclude your current semester.

                        ✦ **This action will archive your active modules.**
                        ✦ **Your telemetry data will be locked and finalized.**

                        > *Are you absolutely sure you want to proceed?*""")
                .build();
        Button confirm = Button.danger("semester_end_btn:confirm:" + userId + ":" + revision, "🛑 Terminate Semester");
        Button cancel = Button.secondary("semester_end_btn:cancel:" + userId, "Cancel");

        var hook = event.getHook();
        hook.editOriginalEmbeds(warning).setComponents(ActionRow.of(confirm, cancel)).queue(sent ->
                // Disable the prompt after a minute unless the user already answered it.
                hook.retrieveOriginal().queueAfter(PROMPT_SECONDS, TimeUnit.SECONDS, message -> {
                    if (!message.getEmbeds().isEmpty() && WARNING_TITLE.equals(message.getEmbeds().getFirst().getTitle()))
                        message.editMessageComponents(ActionRow.of(confirm.asDisabled(), cancel.asDisabled()))
                                .queue(null, new ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE));
                }, new ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE)));
    }
}
