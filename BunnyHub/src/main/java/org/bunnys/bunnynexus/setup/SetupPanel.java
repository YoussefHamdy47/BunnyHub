package org.bunnys.bunnynexus.setup;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.bunnynexus.setup.ServerSettings.Greeting;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.bunnys.utils.Embeds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code /setup}: one private panel showing the welcome message, goodbye message and join roles, with one button
 * per setting. Each button opens a short form; saving redraws the panel. Pure builders; the handlers in
 * {@code buttons} and {@code modals} do the work and re-check that the clicker may manage the server.
 *
 * <p>Component ids: {@code setup:<action>:<guild>} for buttons and {@code setup_form:<action>:<guild>} for forms,
 * where the action is {@code welcome}, {@code goodbye}, {@code roles} or (buttons only) {@code preview}.
 */
public final class SetupPanel {
    private SetupPanel() {}

    public static final String PREFIX = "setup", FORM_PREFIX = "setup_form";
    public static final String ROLES = "roles", PREVIEW = "preview";
    /** Form field ids. */
    public static final String CHANNEL_FIELD = "channel", MESSAGE_FIELD = "message", ROLES_FIELD = "roles";

    public record Panel(MessageEmbed embed, List<ActionRow> rows) {
        public MessageEditData asEdit() {
            return new MessageEditBuilder().setContent("").setEmbeds(embed).setComponents(rows).setAllowedMentions(List.of()).build();
        }
    }

    /** Every control re-checks this: the clicker still manages the server the panel was built for. */
    public static boolean canManage(Guild guild, Member member, String guildId) {
        return guild != null && member != null && guild.getId().equals(guildId) && member.hasPermission(Permission.MANAGE_SERVER);
    }

    public static Panel panel(Guild guild, ServerSettings settings, String notice) {
        var embed = Embeds.of(null, "Server setup").setTimestamp(null);
        StringBuilder intro = new StringBuilder();
        if (notice != null && !notice.isBlank()) intro.append(notice).append("\n\n");
        intro.append("Click a button below to change a setting. Changes apply straight away.");
        embed.setDescription(intro);

        for (Kind kind : Kind.values()) embed.addField(kind.label(), describe(settings.greeting(kind), kind), false);
        embed.addField("Join roles", joinRoles(guild, settings), false);

        var problems = Greetings.problems(guild, settings);
        if (!problems.isEmpty())
            embed.addField("Needs attention", String.join("\n", problems.stream().map(p -> "- " + p).toList()), false);
        Embeds.footer(embed, "Placeholders: " + Greetings.PLACEHOLDERS);

        String id = guild.getId();
        return new Panel(embed.build(), List.of(ActionRow.of(
                button(Kind.WELCOME.key(), id, "Welcome message", settings.welcome().on()),
                button(Kind.GOODBYE.key(), id, "Goodbye message", settings.goodbye().on()),
                button(ROLES, id, "Join roles", !settings.joinRoles().isEmpty()),
                Button.secondary(PREFIX + ":" + PREVIEW + ":" + id, "Preview"))));
    }

    private static Button button(String action, String guildId, String label, boolean on) {
        String id = PREFIX + ":" + action + ":" + guildId;
        return on ? Button.success(id, label) : Button.secondary(id, label);
    }

    private static String describe(Greeting greeting, Kind kind) {
        if (!greeting.on()) return "Off";
        return "On, posted in <#" + greeting.channelId() + ">\n" + quote(greeting.text(kind));
    }

    private static String joinRoles(Guild guild, ServerSettings settings) {
        if (settings.joinRoles().isEmpty()) return "Off";
        var mentions = settings.joinRoles().stream().map(guild::getRoleById).filter(Objects::nonNull)
                .map(role -> role.getAsMention()).toList();
        return mentions.isEmpty() ? "Off (the saved roles were deleted)"
                : "New members get " + String.join(", ", mentions) + ". Bots are skipped.";
    }

    /** The saved text as a block quote, short enough for a field. */
    static String quote(String text) {
        String shown = text.length() > 300 ? text.substring(0, 299) + "…" : text;
        return "> " + shown.replace("\n", "\n> ");
    }

    // ------------------------------------------------------------------ forms

    public static Modal greetingForm(String guildId, Kind kind, Greeting current) {
        var channel = EntitySelectMenu.create(CHANNEL_FIELD, SelectTarget.CHANNEL).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
                .setRequiredRange(0, 1).setRequired(false).setPlaceholder("No channel (turned off)");
        if (current.on()) channel.setDefaultValues(DefaultValue.channel(current.channelId()));
        var message = TextInput.create(MESSAGE_FIELD, TextInputStyle.PARAGRAPH).setRequired(false)
                .setMaxLength(ServerSettings.MAX_MESSAGE).setValue(current.text(kind))
                .setPlaceholder(kind.defaultMessage());
        return Modal.create(FORM_PREFIX + ":" + kind.key() + ":" + guildId, kind.label())
                .addComponents(
                        Label.of("Channel", "Where to post it. Leave empty to turn it off.", channel.build()),
                        Label.of("Message", "You can use " + Greetings.PLACEHOLDERS, message.build()))
                .build();
    }

    public static Modal rolesForm(String guildId, List<String> current) {
        var roles = EntitySelectMenu.create(ROLES_FIELD, SelectTarget.ROLE)
                .setRequiredRange(0, ServerSettings.MAX_JOIN_ROLES).setRequired(false).setPlaceholder("No roles (turned off)");
        List<DefaultValue> defaults = new ArrayList<>();
        for (String id : current) defaults.add(DefaultValue.role(id));
        if (!defaults.isEmpty()) roles.setDefaultValues(defaults);
        return Modal.create(FORM_PREFIX + ":" + ROLES + ":" + guildId, "Join roles")
                .addComponents(Label.of("Roles to give new members",
                        "Up to " + ServerSettings.MAX_JOIN_ROLES + ". Leave empty to turn it off.", roles.build()))
                .build();
    }
}
