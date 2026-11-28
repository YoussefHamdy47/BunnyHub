package org.bunnys.bunnynexus.commands.deals;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.deals.DealCards;
import org.bunnys.bunnynexus.deals.DealStore;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code /deals list [store]}: anyone sees today's good deals (docs/deals/04). Server admins set up a deals board in
 * {@code /free-games setup} (docs/deals/05).
 */
public final class DealsCommand {
    private DealsCommand() {}

    public static final class ListDeals extends BunnySubcommand {
        public ListDeals() {
            setName("list");
            setDescription("Big discounts on well-reviewed PC and console games right now.");
            setExample("/deals list store:steam");
            var store = new OptionData(OptionType.STRING, "store", "Only show one store", false);
            for (DealStore s : DealStore.values()) store.addChoice(s.label, s.choice);
            addOption(store);
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = DealSystem.current().orElse(null);
            if (system == null || system.snapshot().stale(system.now())) { ctx.replyCard(DealCards.unavailable(), true); return; }
            String typed = ctx.getString("store");
            Optional<DealStore> store = Optional.empty();
            if (typed != null) {
                store = DealStore.byChoice(typed);
                if (store.isEmpty()) {
                    ctx.replyTransient(Embeds.error("Unknown store", "Try one of: "
                            + Arrays.stream(DealStore.values()).map(s -> s.choice).collect(Collectors.joining(", ")) + "."));
                    return;
                }
            }
            ctx.replyMessage(org.bunnys.bunnynexus.updates.AdCards.attach(DealCards.list(system.snapshot(), store, 0),
                    org.bunnys.bunnynexus.updates.ProductUpdates.userAd(ctx.getUser().getId(), Optional.ofNullable(ctx.getGuild()).map(g -> g.getId()))));
        }
    }

}
