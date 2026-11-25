package org.bunnys.modals;

import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.commands.freebies.DealBoardSetup;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands;
import org.bunnys.bunnynexus.deals.DealRepository;
import org.bunnys.bunnynexus.deals.DealStore;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

import java.util.*;

/**
 * The deals board form from {@code /free-games setup}: {@code deals_board:<guild>}. A channel turns the board on
 * (posted right away, never pinging); no channel turns it off and removes the message. Then the dashboard is redrawn.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class DealBoardModal extends BunnyModal {
    @Override public String getPrefix() { return DealBoardSetup.MODAL_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var guild = event.getGuild();
        var freebies = FreebieSystem.current().orElse(null);
        var deals = DealSystem.current().orElse(null);
        if (freebies == null || deals == null || args.length != 2 || !FreebieDashboard.canManage(guild, event.getMember(), args[1])) {
            event.getHook().sendMessage("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        var channel = values(event, DealBoardSetup.CHANNEL_FIELD).stream()
                .map(id -> guild.getChannelById(StandardGuildMessageChannel.class, id)).filter(Objects::nonNull).findFirst().orElse(null);
        var role = values(event, DealBoardSetup.ROLE_FIELD).stream().map(guild::getRoleById).filter(Objects::nonNull).findFirst();
        Set<DealStore> stores = EnumSet.noneOf(DealStore.class);
        values(event, DealBoardSetup.LAUNCHERS_FIELD).forEach(choice -> DealStore.byChoice(choice).ifPresent(stores::add));
        if (stores.size() == DealStore.values().length) stores.clear(); // every launcher
        int hours = values(event, DealBoardSetup.EVERY_FIELD).stream().findFirst().filter(v -> v.matches("[0-9]{1,2}"))
                .map(Integer::parseInt).orElse(DealRepository.Ping.DEFAULT_HOURS);

        String notice;
        if (channel == null) {
            var before = deals.repository().turnOff(guild.getId(), event.getUser().getId(), deals.now());
            before.filter(b -> b.messageId().isPresent())
                    .ifPresent(b -> deals.removeBoardMessage(guild.getId(), b.channelId(), b.messageId().get()));
            DealBoardSetup.refreshRolePanel(guild);
            notice = "Deals Board turned off.";
        } else {
            // Same checks as free-game channels: the bot can post there, and the role is a real, pingable one.
            var problem = FreebieSettingsCommands.validate(guild, channel, role);
            if (problem.isPresent()) notice = "⚠️ " + problem.get();
            else {
                DealBoardSetup.save(guild, channel, role.map(r -> r.getId()), stores, hours, event.getUser().getId(), deals);
                notice = "Deals Board on in " + channel.getAsMention() + ". It appears there in a moment.";
            }
        }
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(guild,
                freebies.subscriptions().forGuild(guild.getId()), notice))).queue();
    }

    private static List<String> values(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }
}
