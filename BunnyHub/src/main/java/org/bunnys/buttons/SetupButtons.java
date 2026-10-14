package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.setup.Greetings;
import org.bunnys.bunnynexus.setup.ServerSettings;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.bunnys.bunnynexus.setup.ServerSettingsStore;
import org.bunnys.bunnynexus.setup.SetupPanel;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;

import java.util.List;

/**
 * The {@code /setup} panel buttons: {@code setup:<action>:<guild>}. Opens the form for one setting, or shows a
 * private preview of both messages. Nothing here writes, so there is no cooldown.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class SetupButtons extends BunnyButton {
    @Override public String getPrefix() { return SetupPanel.PREFIX; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var store = ServerSettingsStore.current().orElse(null);
        var guild = event.getGuild();
        if (store == null || args.length != 3 || !SetupPanel.canManage(guild, event.getMember(), args[2])) {
            reply(event, "You need **Manage Server** in this server to do that.");
            return;
        }
        // Usually cached: the panel itself just loaded these settings.
        ServerSettings settings = store.get(guild.getId());
        if (SetupPanel.ROLES.equals(args[1])) {
            event.replyModal(SetupPanel.rolesForm(guild.getId(), settings.joinRoles())).queue();
        } else if (SetupPanel.PREVIEW.equals(args[1])) {
            reply(event, preview(event, settings));
        } else {
            Kind.byKey(args[1]).ifPresentOrElse(
                    kind -> event.replyModal(SetupPanel.greetingForm(guild.getId(), kind, settings.greeting(kind))).queue(),
                    () -> reply(event, "This button is out of date. Run `/setup` again."));
        }
    }

    /** Both messages as they would be posted, with the clicker standing in for the member. */
    static String preview(ButtonInteractionEvent event, ServerSettings settings) {
        var guild = event.getGuild();
        var subject = Greetings.Subject.of(event.getUser(), event.getMember());
        StringBuilder text = new StringBuilder();
        for (Kind kind : Kind.values()) {
            var greeting = settings.greeting(kind);
            text.append("**").append(kind.label()).append("**");
            if (!greeting.on()) { text.append(": off\n\n"); continue; }
            text.append(" in <#").append(greeting.channelId()).append(">\n")
                    .append(Greetings.render(greeting.text(kind), subject, guild.getName(), guild.getMemberCount()))
                    .append("\n\n");
        }
        String result = text.toString().strip();
        return result.length() <= 2000 ? result : result.substring(0, 1999) + "…";
    }

    private static void reply(ButtonInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
