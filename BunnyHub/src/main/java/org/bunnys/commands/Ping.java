package org.bunnys.commands;

import org.bunnys.bunnynexus.info.StatusEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.util.concurrent.TimeUnit;

/** {@code /ping}: the gateway heartbeat and a fresh round trip to Discord's API. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Ping extends BunnyCommand {
    public Ping(BunnyHub client) {
        super(client);
        setName("ping");
        setCategory("Information");
        setDescription("Show the bot's latency to Discord.");
        addAliases("latency");
        setDeferBeforeDispatch(true);
        setCooldown(3);
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        var jda = ctx.getJDA();
        long api;
        try {
            api = jda.getRestPing().submit().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            api = -1;
        } catch (Exception failed) {
            BunnyLog.warning("[Ping] API latency not measured | " + FailureDiagnostics.describe(failed));
            api = -1;
        }
        ctx.reply(StatusEmbeds.ping(jda.getGatewayPing(), api));
    }
}
