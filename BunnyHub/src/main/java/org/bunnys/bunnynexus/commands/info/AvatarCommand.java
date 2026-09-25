package org.bunnys.bunnynexus.commands.info;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.ImageFormat;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.AppDesign;
import java.util.ArrayList;
import java.util.List;

public final class AvatarCommand {
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        User user = context.getUserOptionOrSelf("user");
        Member member = context.getString("user") == null ? context.getMember() : context.getMemberOption("user");
        context.defer(context.getBool("ephemeral", context instanceof org.bunnys.handler.commands.context.UserContext));
        if (member == null && context.isFromGuild()) {
            context.getGuild().retrieveMemberById(user.getId()).queue(
                    resolved -> respond(context, user, resolved),
                    failure -> respond(context, user, null));
        } else respond(context, user, member);
    }

    private static void respond(org.bunnys.handler.commands.context.CommandContext context, User user, Member member) {
        boolean userMenu = context instanceof org.bunnys.handler.commands.context.UserContext;
        try (var view = buildView(user, member, "Server".equalsIgnoreCase(context.getString("priority", userMenu ? "Server" : "Global")),
                context.getBool("show_both", userMenu), context.getInt("size", 2048), context.isFromGuild());
             var message = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder()
                     .setEmbeds(view.getEmbeds()).setComponents(view.getComponents()).build()) {
            context.replyMessage(message);
        } catch (RuntimeException error) {
            String reference = org.bunnys.utils.ErrorReporter.report("avatar", null, error);
            context.replyTransient(org.bunnys.utils.SystemEmbeds.crashed(reference, context.transientRepliesVanish()));
        }
    }
    public static MessageEditData buildView(User user, Member member, boolean serverFirst, boolean both, int size, boolean guild) {
        if (size < 16 || size > 4096 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("Avatar resolution must be a power of two between 16 and 4096.");
        String global = user.getEffectiveAvatar().getUrl(size);
        String server = member == null || member.getAvatar() == null ? null : member.getAvatar().getUrl(size);
        String name = member == null ? user.getEffectiveName() : member.getEffectiveName();
        var embeds = new ArrayList<net.dv8tion.jda.api.entities.MessageEmbed>();
        String fallback = serverFirst && server == null
                ? (guild ? "No server avatar is available; showing the global avatar." : "Server avatars are available in servers; showing the global avatar.")
                : null;
        if (serverFirst && server != null) {
            embeds.add(embed(name, user.getId(), "Server", server, size, null));
            if (both) embeds.add(embed(name, user.getId(), "Global", global, size, null));
        } else {
            embeds.add(embed(name, user.getId(), "Global", global, size, fallback));
            if (both && server != null) embeds.add(embed(name, user.getId(), "Server", server, size, null));
        }
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.link(global, "Global original"));
        buttons.add(Button.link(user.getEffectiveAvatar(ImageFormat.PNG).getUrl(size), "Global PNG"));
        if (server != null) {
            buttons.add(Button.link(server, "Server original"));
            buttons.add(Button.link(member.getAvatar(ImageFormat.PNG).getUrl(size), "Server PNG"));
        }
        return new MessageEditBuilder().setEmbeds(embeds).setComponents(ActionRow.of(buttons)).build();
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed embed(String name, String userId, String type,
                                                                  String url, int size, String note) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.CYAN).setTitle(name + " — " + type + " avatar")
                .setImage(url).setDescription(note).setFooter("User " + userId + " • " + size + "px").build();
    }
}
