package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.bunnynexus.updates.ProductUpdate;
import org.bunnys.bunnynexus.updates.ProductUpdates;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

/** /freebie-admin product-update: preview one catalog entry and who gets it; sending needs the Send button. */
public final class ProductUpdateCommand extends BunnySubcommand {
    public ProductUpdateCommand() {
        setName("product-update");
        setDescription("Preview a feature announcement to servers that don't use it yet (sending needs confirmation)");
        var update = new OptionData(OptionType.STRING, "update", "Which feature", true);
        for (ProductUpdate u : ProductUpdate.values()) update.addChoice(u.title(), u.id());
        addOption(update);
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        String user = ctx.getUser().getId();
        boolean owner = FreebieSystem.current().map(s -> s.config().isOwner(user)).orElse(false);
        var updates = ProductUpdates.current().orElse(null);
        var update = ProductUpdate.byId(ctx.getString("update")).orElse(null);
        if (!owner || updates == null || update == null) {
            ctx.replyCard(Card.builder().text(!owner ? "Only bot owners can use this." : "Product updates are not available right now.").build(), true);
            return;
        }
        ctx.defer(true);
        ctx.replyCard(updates.preview(update, user), true);
    }
}
