package org.bunnys.bunnynexus.commands.freebies;

import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

/** One pending identity question at a time, with persisted owner decisions. */
public final class FreebieSourceCommand extends BunnySubcommand {
    public FreebieSourceCommand() { setName("sources"); setDescription("Resolve a possible duplicate between discovery sources"); }
    @Override public void execute(BunnyHub client, CommandContext ctx) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || !system.config().isOwner(ctx.getUser().getId())) {
            ctx.reply(Embeds.of("🎁", "Source identities", "Only active freebie owners can use this.").build(), true); return;
        }
        ctx.defer(true);
        var view = system.sourceDecision(ctx.getUser().getId());
        ctx.reply(Embeds.of("🎁", "Source identities", view.text()).build(), view.rows(), true);
    }
}
