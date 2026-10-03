package org.bunnys.bunnynexus.commands.info;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.UserContext;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /avatar} and {@code Apps → Avatar}: one large image (the chosen priority), links to every avatar the user
 * has in the description ({@code Global | Server}), and one download button per avatar.
 */
public final class AvatarCommand {
    public void execute(BunnyHub client, CommandContext context) {
        User user = context.getUserOptionOrSelf("user");
        Member member = context.getString("user") == null ? context.getMember() : context.getMemberOption("user");
        context.defer(context.getBool("ephemeral", context instanceof UserContext));
        if (member == null && context.isFromGuild() && !context.getGuild().isDetached()) {
            context.getGuild().retrieveMemberById(user.getId()).queue(
                    resolved -> respond(context, user, resolved),
                    failure -> respond(context, user, null));
        } else respond(context, user, member);
    }

    private static void respond(CommandContext context, User user, Member member) {
        // The user menu is about this server, so it leads with the server avatar; the slash command with the global one.
        boolean serverFirst = "Server".equalsIgnoreCase(context.getString("priority", context instanceof UserContext ? "Server" : "Global"));
        try (var message = buildView(user, member, serverFirst, context.getInt("size", 2048))) {
            context.replyMessage(message);
        } catch (RuntimeException error) {
            String reference = ErrorReporter.report("avatar", null, error);
            context.replyTransient(SystemEmbeds.crashed(reference, context.transientRepliesVanish()));
        }
    }

    public static MessageCreateData buildView(User user, Member member, boolean serverFirst, int size) {
        if (size < 16 || size > 4096 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("Avatar resolution must be a power of two between 16 and 4096.");
        String global = user.getEffectiveAvatar().getUrl(size);
        String server = member == null || member.getAvatar() == null ? null : member.getAvatar().getUrl(size);
        String name = member == null ? user.getEffectiveName() : member.getEffectiveName();

        String links = "[Global Avatar](" + global + ")" + (server == null ? "" : " | [Server Avatar](" + server + ")");
        var embed = Embeds.of("🖼️", name + "'s Avatar", links)
                .setImage(serverFirst && server != null ? server : global);
        Embeds.footer(embed, size + "px");

        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.link(global, "Global Avatar"));
        if (server != null) buttons.add(Button.link(server, "Server Avatar"));
        return new MessageCreateBuilder().setEmbeds(embed.build()).setComponents(ActionRow.of(buttons)).build();
    }
}
