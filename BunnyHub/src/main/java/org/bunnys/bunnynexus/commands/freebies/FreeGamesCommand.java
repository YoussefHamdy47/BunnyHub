package org.bunnys.bunnynexus.commands.freebies;

import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.SystemEmbeds;

import java.util.Optional;
import java.util.Arrays;
import java.util.Locale;

/** {@code /free-games [launcher]}: anyone can see the owner-approved games that are free right now. */
public final class FreeGamesCommand {

    public void execute(BunnyHub client, CommandContext ctx) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) {
            ctx.reply(SystemEmbeds.notice("Free games", "Free-game alerts are not available right now."), true);
            return;
        }
        String typed = ctx.getString("launcher");
        Optional<FreebieStore> store = Optional.empty();
        if (typed != null) {
            store = FreebieStore.byId(typed.strip().toLowerCase(Locale.ROOT));
            if (store.isEmpty()) {
                ctx.reply(SystemEmbeds.error("Unknown launcher", "Try one of: " + String.join(", ",
                        Arrays.stream(FreebieStore.values()).map(FreebieStore::id).toList()) + "."), true);
                return;
            }
        }
        ctx.replyMessage(FreebieMessages.liveList(system.liveNow(store), store));
    }
}
