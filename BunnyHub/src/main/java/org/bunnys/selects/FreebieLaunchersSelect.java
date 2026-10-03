package org.bunnys.selects;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The launcher checklist from {@code /freebie setup} without a launcher:
 * {@code freebie_launchers:<guild>:<channel>:<role or ->}. Everything in the ID is re-checked here; the clicker
 * must still have Manage Server, and the channel and role must still be valid.
 */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieLaunchersSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieMessages.LAUNCHERS_PREFIX; }
    @Override public long cooldownMillis() { return WRITE_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        var guild = event.getGuild();
        var member = event.getMember();
        if (system == null || args.length != 4 || guild == null || member == null || !guild.getId().equals(args[1])
                || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        Optional<Role> role = Optional.empty();
        if (!"-".equals(args[3])) {
            Role found = guild.getRoleById(args[3]);
            if (found == null) {
                event.reply("That role no longer exists. Run `/freebie setup` again.").setEphemeral(true)
                        .setAllowedMentions(List.of()).queue();
                return;
            }
            role = Optional.of(found);
        }
        Set<FreebieStore> stores = EnumSet.noneOf(FreebieStore.class);
        for (String value : event.getValues()) FreebieStore.byId(value).ifPresent(stores::add);

        // Up to one write per launcher follows, so acknowledge first rather than race the 3-second window.
        event.deferEdit().queue();
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, args[2]);
        var reply = FreebieSettingsCommands.saveLaunchers(guild, channel, stores, role, event.getUser().getId(), system.subscriptions());
        // Replace the menu with the result, so a stale checklist cannot be submitted again.
        event.getHook().editOriginal(new MessageEditBuilder().setEmbeds(reply.embed()).setComponents(reply.rows())
                .setAllowedMentions(List.of()).build()).queue();
    }
}
