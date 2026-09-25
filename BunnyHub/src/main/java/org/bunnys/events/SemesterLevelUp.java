package org.bunnys.events;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.GenericEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.bunnynexus.events.custom.SemesterLevelUpEvent;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.AppDesign;

import java.time.Instant;

public class SemesterLevelUp extends BunnyEvent {

        public SemesterLevelUp(BunnyHub client) {
                super(client);
        }

        @Override
        public void onGenericEvent(GenericEvent genericEvent) {
                if (!(genericEvent instanceof SemesterLevelUpEvent event))
                        return;

                int newLevel = event.getTimerData().getCurrentSemester().getSemesterLevel();
                EmbedBuilder levelUpEmbed = new EmbedBuilder()
                                .setColor(AppDesign.ColorCodes.CYAN)
                                .setFooter("Academic Progression")
                                .setTimestamp(Instant.now());

                if (newLevel >= LevelEngine.MAX_RANK) {
                        levelUpEmbed.setTitle(LevelEngine.rankUpEmoji(newLevel) + " MAX Semester Level Reached!")
                                        .setDescription(String.format(
                                                        "✦ **Final Semester Level:** `%d` 👑\n" +
                                                                        "✦ **Levels Gained:** `%d`\n" +
                                                                        "✦ **Overflow XP:** `%,.0f`\n\n" +
                                                                        "> *Absolute mastery achieved. You have reached the pinnacle of this semester!*",
                                                        LevelEngine.MAX_RANK,
                                                        event.getLevelUps(),
                                                        event.getCarryOverXP()));
                } else {
                        long xpRequired = LevelEngine.xpRequired(newLevel);
                        levelUpEmbed.setTitle(LevelEngine.rankUpEmoji(newLevel) + " Semester Level Up!")
                                        .setDescription(String.format(
                                                        "✦ **New Semester Level:** `%d`\n" +
                                                                        "✦ **XP to Level %d:** `%,.0f / %,d`\n" +
                                                                        "✦ **Levels Gained:** `%d`\n\n" +
                                                                        "> *Another step forward. Keep up the momentum!*",
                                                        newLevel,
                                                        newLevel + 1,
                                                        event.getCarryOverXP(),
                                                        xpRequired,
                                                        event.getLevelUps()));
                }

                event.getInteraction().getHook()
                                .sendMessage("<@" + event.getInteraction().getUser().getId() + ">")
                                .addEmbeds(levelUpEmbed.build())
                                .queue();
        }
}