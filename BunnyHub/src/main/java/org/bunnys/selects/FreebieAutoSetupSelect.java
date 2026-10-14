package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieCatchUp;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import org.bunnys.bunnynexus.commands.freebies.FreebieExistingSetup;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Section picker on the auto-setup view, and the picker of existing channels/roles to reuse; both redraw their view
 * so the run buttons carry the choice.
 */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieAutoSetupSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieDashboard.AUTO_SELECT_PREFIX; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        if (args.length == 5) { existing(event, args); return; }
        var chosen = FreebieSection.decode(String.join("", event.getValues()));
        if ((args.length != 2 && args.length != 3) || chosen.isEmpty()
                || !FreebieDashboard.canManage(event.getGuild(), event.getMember(), args[1])) {
            deny(event);
            return;
        }
        var post = args.length == 3 ? FreebieCatchUp.of(args[2]) : FreebieCatchUp.AUTO;
        event.editMessage(FreebieDashboard.asEdit(FreebieDashboard.autoSetup(args[1], chosen, post))).queue();
    }

    /** {@code freebie_autosel:<guild>:<layout>:<sections>:<fingerprint>}: which existing channels/roles to use. */
    private static void existing(StringSelectInteractionEvent event, String[] args) {
        var guild = event.getGuild();
        var sections = FreebieCatchUp.sectionsOf(args[3]);
        if (sections.isEmpty() || !FreebieDashboard.canManage(guild, event.getMember(), args[1])) { deny(event); return; }
        // Cache reads only; nothing is created until a run button is pressed.
        var found = FreebieExistingSetup.scan(guild, sections, FreebieDashboard.CHANNEL_PER_SECTION.equals(args[2]));
        Set<Integer> chosen = new TreeSet<>();
        String notice = null;
        if (FreebieExistingSetup.fingerprint(found).equals(args[4])) {
            for (String value : event.getValues()) {
                try { int i = Integer.parseInt(value); if (i >= 0 && i < found.size()) chosen.add(i); }
                catch (NumberFormatException ignored) { /* Not one of ours. */ }
            }
        } else {
            chosen = FreebieExistingSetup.all(found);
            notice = "⚠️ Your channels or roles changed; here is the current list.";
        }
        event.editMessage(FreebieDashboard.asEdit(FreebieExistingSetup.prompt(args[1], sections, args[2], found, chosen, notice,
                FreebieCatchUp.choiceOf(args[3])))).queue();
    }

    private static void deny(StringSelectInteractionEvent event) {
        event.reply("You need **Manage Server** in this server to do that.").setEphemeral(true)
                .setAllowedMentions(List.of()).queue();
    }
}
