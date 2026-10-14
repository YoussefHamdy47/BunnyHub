package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.setup.Greetings;
import org.bunnys.bunnynexus.setup.ServerSettings;
import org.bunnys.bunnynexus.setup.ServerSettings.Greeting;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.bunnys.bunnynexus.setup.ServerSettingsStore;
import org.bunnys.bunnynexus.setup.SetupPanel;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The forms behind the {@code /setup} buttons: {@code setup_form:<action>:<guild>}. Saves the one setting, then
 * redraws the panel with what changed. Everything submitted is re-checked against the server.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class SetupModal extends BunnyModal {
    @Override public String getPrefix() { return SetupPanel.FORM_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var store = ServerSettingsStore.current().orElse(null);
        Guild guild = event.getGuild();
        if (store == null || args.length != 3 || !SetupPanel.canManage(guild, event.getMember(), args[2])) {
            event.getHook().sendMessage("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        ServerSettings settings = store.get(guild.getId());
        String notice;
        if (SetupPanel.ROLES.equals(args[1])) {
            var result = saveRoles(guild, settings, values(event, SetupPanel.ROLES_FIELD));
            settings = result.settings();
            notice = result.notice();
        } else {
            Kind kind = Kind.byKey(args[1]).orElse(null);
            if (kind == null) return;
            var result = saveGreeting(guild, settings, kind, values(event, SetupPanel.CHANNEL_FIELD), text(event));
            settings = result.settings();
            notice = result.notice();
        }
        store.save(guild.getId(), settings, event.getUser().getId());
        event.getHook().editOriginal(SetupPanel.panel(guild, settings, notice).asEdit()).queue();
    }

    record Saved(ServerSettings settings, String notice) {}

    static Saved saveGreeting(Guild guild, ServerSettings settings, Kind kind, List<String> channelIds, String typed) {
        // Blank or unchanged-from-default text is stored as "default", so a later wording change reaches it.
        String message = typed == null || typed.isBlank() || typed.strip().equals(kind.defaultMessage()) ? null : typed.strip();
        var channel = channelIds.stream().map(id -> Greetings.channel(guild, id)).filter(Objects::nonNull).findFirst().orElse(null);
        if (channel == null)
            return new Saved(settings.with(kind, new Greeting(null, message)), "Saved. The " + kind.label().toLowerCase() + " is off.");
        String notice = "Saved. The " + kind.label().toLowerCase() + " will be posted in " + channel.getAsMention() + ".";
        if (!Greetings.canPost(guild, channel))
            notice += "\nI can't post there yet. Give me **View Channel** and **Send Messages** in " + channel.getAsMention() + ".";
        return new Saved(settings.with(kind, new Greeting(channel.getId(), message)), notice);
    }

    static Saved saveRoles(Guild guild, ServerSettings settings, List<String> roleIds) {
        List<String> kept = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Role role : roleIds.stream().distinct().map(guild::getRoleById).filter(Objects::nonNull).toList()) {
            var problem = Greetings.roleProblem(guild, role);
            if (problem.isPresent()) refused.add(problem.get());
            else if (kept.size() < ServerSettings.MAX_JOIN_ROLES) kept.add(role.getId());
        }
        String notice = kept.isEmpty() ? "Saved. Join roles are off."
                : "Saved. New members will get " + String.join(", ", kept.stream().map(id -> "<@&" + id + ">").toList()) + ".";
        if (!refused.isEmpty()) notice += "\nNot added:\n" + String.join("\n", refused.stream().map(p -> "- " + p).toList());
        return new Saved(settings.withJoinRoles(kept), notice);
    }

    private static String text(ModalInteractionEvent event) {
        ModalMapping mapping = event.getValue(SetupPanel.MESSAGE_FIELD);
        return mapping == null ? null : mapping.getAsString();
    }

    private static List<String> values(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }
}
