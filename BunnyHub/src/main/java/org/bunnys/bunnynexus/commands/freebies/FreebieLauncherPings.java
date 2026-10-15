package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands.Reply;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.utils.Embeds;
import java.util.*;

/**
 * Launcher pings: a different role per launcher (for example @Epic for Epic Games Store only), shown as a list
 * with a picker, and the one-field form behind each choice. Reached from the {@link FreebieDashboard}.
 */
public final class FreebieLauncherPings {
    private FreebieLauncherPings() {}

    /** Every enabled launcher with its ping, and a picker to change one. */
    public static Reply view(Guild guild, List<Subscription> saved, String notice) {
        var pings = FreebieDashboard.launcherPings(saved);
        var embed = Embeds.of(null, "Launcher pings").setTimestamp(null);
        StringBuilder text = new StringBuilder();
        if (notice != null && !notice.isBlank()) text.append(notice).append("\n\n");
        text.append("Give each launcher its own ping, like @Epic for Epic Games Store only. "
                + "Members can opt in to these roles from the role picker.\n\n");
        if (pings.isEmpty()) text.append("No launchers are on yet. Use **Quick setup** first.");
        for (var entry : pings.entrySet())
            text.append("**").append(entry.getKey().label()).append("** · ").append(FreebieDashboard.mentions(entry.getValue())).append("\n");
        embed.setDescription(text.length() <= 4000 ? text.toString() : text.substring(0, 3999) + "…");

        List<ActionRow> rows = new ArrayList<>();
        if (!pings.isEmpty()) {
            var menu = StringSelectMenu.create(FreebieDashboard.PING_SELECT_PREFIX + ":" + guild.getId()).setPlaceholder("Change the ping for…");
            for (var entry : pings.entrySet()) {
                // The current role rides in the value, so the form can open without a database read.
                String current = entry.getValue().size() == 1 ? entry.getValue().iterator().next()
                        .filter(r -> guild.getRoleById(r) != null).orElse(FreebieDashboard.NONE) : FreebieDashboard.NONE;
                menu.addOption(entry.getKey().label(), entry.getKey().id() + ":" + current);
            }
            rows.add(ActionRow.of(menu.build()));
        }
        rows.add(ActionRow.of(Button.secondary(FreebieDashboard.NAV_PREFIX + ":" + FreebieDashboard.HOME + ":" + guild.getId(), "Back")));
        return new Reply(embed.build(), rows);
    }

    /** From a picker value {@code <launcher>:<current role or ->}. */
    public static Optional<Modal> form(String guildId, String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2) return Optional.empty();
        var store = FreebieStore.byId(parts[0]).orElse(null);
        if (store == null) return Optional.empty();
        var role = EntitySelectMenu.create(FreebieDashboard.ROLE_FIELD, SelectTarget.ROLE).setRequiredRange(0, 1).setRequired(false)
                .setPlaceholder("No ping");
        try {
            if (!FreebieDashboard.NONE.equals(parts[1])) role.setDefaultValues(DefaultValue.role(parts[1]));
        } catch (IllegalArgumentException malformed) { return Optional.empty(); }
        return Optional.of(Modal.create(FreebieDashboard.PING_MODAL_PREFIX + ":" + guildId + ":" + store.id(), store.label() + " ping")
                .addComponents(Label.of("Role to ping", "Leave empty to post " + store.label() + " games without a ping", role.build()))
                .build());
    }
}
