package org.bunnys.commands;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;

public class Avatar extends BunnyCommand {
    public Avatar(BunnyHub client) {
        super(client);
        setName("avatar");
        setUserContextName("Avatar");
        addAliases("av", "pfp");
        setDeferBeforeDispatch(true);
        setDescription("View and download global and server avatars, including animated originals.");
        setDmEnabled(true);
        setCooldown(3);
        addOption(new OptionData(OptionType.USER, "user", "Whose avatar? Defaults to you", false));
        addOption(new OptionData(OptionType.STRING, "priority", "Which avatar should appear first?", false)
                .addChoice("Global", "Global").addChoice("Server", "Server"));
        addOption(new OptionData(OptionType.BOOLEAN, "show_both", "Show both avatars at full size when available", false));
        addOption(new OptionData(OptionType.INTEGER, "size", "Image resolution (default: 2048)", false)
                .addChoice("128", 128).addChoice("512", 512).addChoice("1024", 1024)
                .addChoice("2048", 2048).addChoice("4096", 4096));
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Only show the result to you", false));
    }

    private final org.bunnys.bunnynexus.commands.info.AvatarCommand implementation =
            new org.bunnys.bunnynexus.commands.info.AvatarCommand();

    @Override public boolean defaultEphemeral(org.bunnys.handler.commands.context.CommandContext context) {
        return context instanceof org.bunnys.handler.commands.context.UserContext;
    }

    @Override public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        implementation.execute(client, context);
    }
}
