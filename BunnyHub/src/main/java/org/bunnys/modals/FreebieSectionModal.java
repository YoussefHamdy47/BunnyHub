package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bunnys.bunnynexus.commands.freebies.FreebieCatchUp;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.commands.freebies.FreebieServerSetup;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
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
        // An older form without the reminder field keeps the saved setting.
        Integer reminder = values(event, FreebieDashboard.REMINDER_FIELD).stream().findFirst()
                .map(FreebieSectionModal::hours).filter(FreebieSubscriptions.REMINDER_CHOICES::contains).orElse(null);
        var post = values(event, FreebieDashboard.POST_FIELD).stream().findFirst().map(FreebieCatchUp::of).orElse(FreebieCatchUp.AUTO);
        // New for this section = the channel had none of the section's launchers before this save.
        boolean fresh = channel == null || subscriptions.forGuild(guild.getId()).stream()
                .noneMatch(s -> s.channelId().equals(channel.getId()) && s.store().section() == section);
        var result = FreebieServerSetup.saveSection(guild, section, channel, role, stores, reminder, event.getUser().getId(), subscriptions);
        String notice = result.notice();
        boolean posted = result.channelId().isPresent() && post.resolve(fresh);
        // Only queued here; the sends run on the freebie sender at catch-up priority.
        if (posted) notice += "\n" + FreebieCatchUp.reply(system.catchUpChannel(guild.getId(), result.channelId().get(),
                result.stores()::contains), "<#" + result.channelId().get() + ">");
        var saved = subscriptions.forGuild(guild.getId());
        event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(guild, saved, notice))).queue();

        // Otherwise late subscribers can still opt in to games that were already approved and are still free.
        if (result.channelId().isEmpty() || posted) return;
        int live = result.stores().stream().mapToInt(store -> system.liveFor(store).size()).sum();
        if (live > 0) event.getHook().sendMessage("Some approved games are still free for this section.")
                .setComponents(FreebieMessages.catchUpAllControls(guild.getId(), result.channelId().get(), live))
                .setEphemeral(true).setAllowedMentions(List.of()).queue();
    }

    private static Integer hours(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException malformed) { return -1; }
    }

    private static List<String> values(ModalInteractionEvent event, String field) {
        ModalMapping mapping = event.getValue(field);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }
}
