package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.commands.freebies.FreebieCatchUp;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieServerSetup;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Quick setup: {@code freebie_quick:<guild>}. Every chosen section goes to one channel with all its launchers and
 * the one ping, optionally posting the games that are free right now, then the dashboard is redrawn.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class FreebieQuickModal extends BunnyModal {
    @Override public String getPrefix() { return FreebieDashboard.QUICK_MODAL_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        if (system == null || args.length != 2 || !FreebieDashboard.canManage(guild, event.getMember(), args[1])) {
            event.getHook().sendMessage("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        StandardGuildMessageChannel channel = values(event, FreebieDashboard.CHANNEL_FIELD).stream()
                .map(id -> guild.getChannelById(StandardGuildMessageChannel.class, id)).filter(Objects::nonNull).findFirst().orElse(null);
        Optional<Role> role = values(event, FreebieDashboard.ROLE_FIELD).stream().map(guild::getRoleById).filter(Objects::nonNull).findFirst();
        Set<FreebieSection> sections = FreebieSection.decode(String.join("", values(event, FreebieDashboard.SECTIONS_FIELD)));
        var post = values(event, FreebieDashboard.POST_FIELD).stream().findFirst().map(FreebieCatchUp::of).orElse(FreebieCatchUp.ON);

        var subscriptions = system.subscriptions();
        List<String> notices = new ArrayList<>();
        Set<FreebieStore> saved = EnumSet.noneOf(FreebieStore.class);
        for (FreebieSection section : sections) {
            var result = FreebieServerSetup.saveSection(guild, section, channel, role, EnumSet.copyOf(section.stores()),
                    event.getUser().getId(), subscriptions);
            notices.add(result.notice());
            saved.addAll(result.stores());
            // The same problem (no channel, no permission) would repeat for every section.
            if (result.channelId().isEmpty()) break;
        }
        String notice = String.join("\n", notices);
        if (channel != null && !saved.isEmpty() && post == FreebieCatchUp.ON)
            notice += "\n" + FreebieCatchUp.reply(system.catchUpChannel(guild.getId(), channel.getId(), saved::contains), channel.getAsMention());
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(guild,
                subscriptions.forGuild(guild.getId()), notice))).queue();
    }

    private static List<String> values(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }
}
