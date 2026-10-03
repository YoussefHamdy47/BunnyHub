package org.bunnys.commands;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.bunnynexus.commands.info.AvatarCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.UserContext;

@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Avatar extends BunnyCommand {
    public Avatar(BunnyHub client) {
        super(client);
        setName("avatar");
        setCategory("Information");
        setUserContextName("Avatar");
        addAliases("av", "pfp");
        setDeferBeforeDispatch(true);
        setDescription("View and download someone's global and server avatars.");
        setDmEnabled(true);
        setCooldown(3);
        addOption(new OptionData(OptionType.USER, "user", "Whose avatar? Defaults to you", false));
        addOption(new OptionData(OptionType.STRING, "priority", "Which avatar to show large (both are linked)", false)
                .addChoice("Global", "Global").addChoice("Server", "Server"));
        addOption(new OptionData(OptionType.INTEGER, "size", "Image resolution (default: 2048)", false)
                .addChoice("128", 128).addChoice("512", 512).addChoice("1024", 1024)
                .addChoice("2048", 2048).addChoice("4096", 4096));
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Only show the result to you", false));
    }

    private final AvatarCommand implementation =
            new AvatarCommand();

    @Override public boolean defaultEphemeral(CommandContext context) {
        return context instanceof UserContext;
    }

    @Override public void execute(BunnyHub client, CommandContext context) {
        implementation.execute(client, context);
    }
}
