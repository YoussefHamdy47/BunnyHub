package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.EmbedBuilder;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;

public final class EndSemester extends BunnySubcommand {
    public EndSemester() {
        setName("end_semester");
        setDescription("Permanently conclude and archive your current academic semester.");
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();

        try {
            Timers timerSystem = new Timers(userId, event);
            timerSystem.checkSemester();
            // Bind the confirmation to the semester state the user is being warned about.
            String revision = timerSystem.revisionToken();

            // Miami Aesthetic Warning Embed
            EmbedBuilder warningEmbed = new EmbedBuilder()
                    .setColor(AppDesign.ColorCodes.ERROR_RED)
                    .setTitle("🛑 Semester Termination Protocol")
                    .setDescription("""
                            You are about to permanently conclude your current semester.
                            
                            ✦ **This action will archive your active modules.**
                            ✦ **Your telemetry data will be locked and finalized.**
                            
                            > *Are you absolutely sure you want to proceed?*""");

            net.dv8tion.jda.api.components.buttons.Button confirmBtn =
                    net.dv8tion.jda.api.components.buttons.Button.danger("semester_end_btn:confirm:" + userId + ":" + revision,"🛑 Terminate Semester");

            net.dv8tion.jda.api.components.buttons.Button cancelBtn =
                    net.dv8tion.jda.api.components.buttons.Button.secondary("semester_end_btn:cancel:" + userId, "Cancel");

            event.getHook().editOriginalEmbeds(warningEmbed.build()).setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(confirmBtn, cancelBtn)).queue(message -> {
                var hook = event.getHook();

                // --- AUTO TIMEOUT LOGIC ---
                // Wait exactly 60 seconds without blocking the thread
                hook.retrieveOriginal().queueAfter(60, java.util.concurrent.TimeUnit.SECONDS, msg -> {

                    // Check if the title is still the warning (meaning the user hasn't clicked Cancel)
                    if (!msg.getEmbeds().isEmpty() && "🛑 Semester Termination Protocol".equals(msg.getEmbeds().getFirst().getTitle())) {
                        msg.editMessageComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                confirmBtn.asDisabled(), cancelBtn.asDisabled()
                        )).queue(null, new net.dv8tion.jda.api.exceptions.ErrorHandler().ignore(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE));
                    }
                }, new net.dv8tion.jda.api.exceptions.ErrorHandler().ignore(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE));
            });

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
}
