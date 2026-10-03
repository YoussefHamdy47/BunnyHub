package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieServerSetup;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import java.util.*;

/**
 * The section form from the dashboard: {@code freebie_section:<guild>:<section>}. Saves the section's channel,
 * ping role and launchers, then redraws the dashboard with the outcome. Everything submitted is re-validated.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class FreebieSectionModal extends BunnyModal {
    @Override public String getPrefix() { return FreebieDashboard.SECTION_MODAL_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        var section = args.length == 3 ? FreebieSection.byCode(args[2]).orElse(null) : null;
        if (system == null || section == null || !FreebieDashboard.canManage(guild, event.getMember(), args[1])) {
            event.getHook().sendMessage("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        StandardGuildMessageChannel channel = values(event, FreebieDashboard.CHANNEL_FIELD).stream()
                .map(id -> guild.getChannelById(StandardGuildMessageChannel.class, id)).filter(Objects::nonNull).findFirst().orElse(null);
        Optional<Role> role = values(event, FreebieDashboard.ROLE_FIELD).stream().map(guild::getRoleById).filter(Objects::nonNull).findFirst();
        Set<FreebieStore> stores = EnumSet.noneOf(FreebieStore.class);
        values(event, FreebieDashboard.LAUNCHERS_FIELD).forEach(id -> FreebieStore.byId(id).ifPresent(stores::add));

        var subscriptions = system.subscriptions();
        var result = FreebieServerSetup.saveSection(guild, section, channel, role, stores, event.getUser().getId(), subscriptions);
        var saved = subscriptions.forGuild(guild.getId());
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(guild, saved, result.notice()))).queue();

        // Late subscribers can opt in to games that were already approved and are still free.
        if (result.channelId().isEmpty()) return;
        int live = result.stores().stream().mapToInt(store -> system.liveFor(store).size()).sum();
        if (live > 0) event.getHook().sendMessage("Some approved games are still free for this section.")
                .setComponents(FreebieMessages.catchUpAllControls(guild.getId(), result.channelId().get(), live))
                .setEphemeral(true).setAllowedMentions(List.of()).queue();
    }

    private static List<String> values(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }
}
