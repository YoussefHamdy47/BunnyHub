package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One launcher's ping: {@code freebie_pingform:<guild>:<launcher>}. Applies to every channel posting that launcher,
 * then redraws the Launcher pings view. The role is re-checked against each of those channels.
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class FreebiePingModal extends BunnyModal {
    @Override public String getPrefix() { return FreebieDashboard.PING_MODAL_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        var store = args.length == 3 ? FreebieStore.byId(args[2]).orElse(null) : null;
        if (system == null || store == null || !FreebieDashboard.canManage(guild, event.getMember(), args[1])) {
            event.getHook().sendMessage("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        ModalMapping mapping = event.getValue(FreebieDashboard.ROLE_FIELD);
        Optional<Role> role = (mapping == null ? List.<String>of() : mapping.getAsStringList()).stream()
                .map(guild::getRoleById).filter(Objects::nonNull).findFirst();
        var subscriptions = system.subscriptions();
        var saved = subscriptions.forGuild(guild.getId());

        String notice = null;
        for (var s : saved) {
            if (s.store() != store) continue;
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, s.channelId());
            if (channel == null) continue;
            var problem = FreebieSettingsCommands.validate(guild, channel, role);
            if (problem.isPresent()) { notice = problem.get(); break; }
        }
        if (notice == null) {
            subscriptions.setStoreRole(guild.getId(), store, role.map(Role::getId), event.getUser().getId(), Instant.now());
            notice = "**" + store.label() + "** now " + role.map(r -> "pings " + r.getAsMention()).orElse("posts without a ping") + ".";
            saved = subscriptions.forGuild(guild.getId());
        }
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.pings(guild, saved, notice))).queue();
    }
}
