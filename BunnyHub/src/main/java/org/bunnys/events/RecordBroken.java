package org.bunnys.events;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.GenericEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.bunnynexus.events.custom.RecordBrokenEvent;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Durations;
import org.bunnys.utils.Embeds;

import java.time.Instant;

@SuppressWarnings("unused") // Discovered reflectively by EventLoader.
public class RecordBroken extends BunnyEvent {

    public RecordBroken(BunnyHub client) {
        super(client);
    }

    @Override
    public void onGenericEvent(GenericEvent genericEvent) {
        if (!(genericEvent instanceof RecordBrokenEvent event))
            return;

        User user = event.getInteraction().getUser();
        EmbedBuilder recordEmbed = new EmbedBuilder();

        recordEmbed.setAuthor(user.getName() + " reached a new milestone!", null, user.getEffectiveAvatarUrl());
        recordEmbed.setTimestamp(Instant.now());

        if (event.getType() == RecordBrokenEvent.RecordType.SEMESTER && event.getSemester() != null) {
            long timeMs = (long) (event.getSemester().getSemesterTime() * 1000);

            recordEmbed
                    .setTitle("👑 Semester Record Broken!")
                    .setColor(AppDesign.ColorCodes.DEFAULT)
                    .setDescription(
                            "✦ **Semester Name:** `" + event.getSemester().getSemesterName() + "`\n" +
                                    "✦ **Total Focus Time:** `" + Durations.format(timeMs) + "`\n\n" +
                                    "> *You have surpassed your limits and set a new all-time semester record!*")
                    .setFooter("Outstanding Dedication • " + Embeds.FOOTER);

        } else if (event.getType() == RecordBrokenEvent.RecordType.SESSION && event.getSessionTime() != null) {
            long timeMs = (long) (event.getSessionTime() * 1000);

            recordEmbed
                    .setTitle("💎 Session Record Broken!")
                    .setColor(AppDesign.ColorCodes.DEFAULT)
                    .setDescription(
                            "✦ **Session Time:** `" + Durations.format(timeMs) + "`\n\n" +
                                    "> *An incredible display of focus. You just set a new personal best for a single session!*")
                    .setFooter("Focus & Consistency • " + Embeds.FOOTER);
        } else
            return;

        event.getInteraction().getHook()
                .sendMessage("<@" + user.getId() + ">")
                .addEmbeds(recordEmbed.build())
                .queue();
    }
}