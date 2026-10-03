package org.bunnys.bunnynexus.commands.help;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.bunnynexus.help.HelpMenu;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/** {@code /help [command]}: opens the overview, or goes straight to one command's card. */
public final class HelpCommand {

    public void execute(BunnyHub client, CommandContext ctx) {
        CommandRegistry registry = client.getCommandRegistry();
        HelpMenu.Rendered view = HelpMenu.lookup(bot(ctx.getJDA(), registry),
                registry.getCommands().values(), viewer(registry, ctx.getUser()), ctx.getString("command"));
        ctx.reply(view.embed(), view.rows());
    }

    public List<String> autocomplete(BunnyHub client, User user) {
        CommandRegistry registry = client.getCommandRegistry();
        return HelpMenu.paths(registry.getCommands().values(), viewer(registry, user).developer());
    }

    /** The bot as the reader sees it. Shared by the command and its menu handlers. */
    public static HelpMenu.Bot bot(JDA jda, CommandRegistry registry) {
        User self = jda.getSelfUser();
        return new HelpMenu.Bot(self.getName(), self.getEffectiveAvatarUrl(), registry.getCommandIds());
    }

    public static HelpMenu.Viewer viewer(CommandRegistry registry, User user) {
        return new HelpMenu.Viewer(user.getId(), registry.getDeveloperIds().contains(user.getId()));
    }
}
