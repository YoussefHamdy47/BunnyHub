package org.bunnys.commands;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.setup.ServerSettingsStore;
import org.bunnys.bunnynexus.setup.SetupPanel;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.SystemEmbeds;

/** {@code /setup}: opens the server setup panel (welcome, goodbye, join roles). See {@link SetupPanel}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Setup extends BunnyCommand {
    public Setup(BunnyHub client) {
        super(client);
        setName("setup");
        setCategory("Server");
        setDescription("Set up welcome messages, goodbye messages and roles for new members.");
        setDmEnabled(false);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        addUserPermissions(Permission.MANAGE_SERVER);
        setCooldown(3);
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        if (!ctx.isFromGuild() || ctx.getMember() == null || !ctx.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            ctx.reply(SystemEmbeds.error("Server setup", "You need **Manage Server** in a server to use this."), true);
            return;
        }
        var store = ServerSettingsStore.current().orElse(null);
        if (store == null) {
            ctx.reply(SystemEmbeds.error("Server setup", "Server settings are not available right now. Try again shortly."), true);
            return;
        }
        var panel = SetupPanel.panel(ctx.getGuild(), store.get(ctx.getGuild().getId()), null);
        ctx.reply(panel.embed(), panel.rows(), true);
    }
}
