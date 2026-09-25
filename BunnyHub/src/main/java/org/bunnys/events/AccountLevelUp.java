package org.bunnys.events;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.GenericEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.bunnynexus.events.custom.AccountLevelUpEvent;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.AppDesign;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public class AccountLevelUp extends BunnyEvent {

        public AccountLevelUp(BunnyHub client) {
                super(client);
        }

        @Override
        public void onGenericEvent(@NotNull GenericEvent genericEvent) {
                if (!(genericEvent instanceof AccountLevelUpEvent event))
                        return;

                int newRank = event.getUserData().getRank();
                EmbedBuilder rankUpEmbed = new EmbedBuilder()
                                .setColor(AppDesign.ColorCodes.CYAN)
                                .setFooter("Global Progression")
                                .setTimestamp(Instant.now());

                if (newRank >= LevelEngine.MAX_RANK) {
                        rankUpEmbed.setTitle(LevelEngine.rankUpEmoji(newRank) + " MAX Account Rank Reached!")
                                        .setDescription(String.format(
                                                        "✦ **Final Account Rank:** `%d` 👑\n" +
                                                                        "✦ **Ranks Gained:** `%d`\n" +
                                                                        "✦ **Overflow RP:** `%,.0f`\n\n" +
                                                                        "> *A true legend. You have conquered the global ranks!*",
                                                        LevelEngine.MAX_RANK,
                                                        event.getLevelUps(),
                                                        event.getCarryOverXP()));
                } else {
                        long rpRequired = LevelEngine.rpRequired(newRank);
                        rankUpEmbed.setTitle(LevelEngine.rankUpEmoji(newRank) + " Account Rank Up!")
                                        .setDescription(String.format(
                                                        "✦ **New Account Rank:** `%d`\n" +
                                                                        "✦ **RP to Rank %d:** `%,.0f / %,d`\n" +
                                                                        "✦ **Ranks Gained:** `%d`\n\n" +
                                                                        "> *Your dedication is paying off. Outstanding work!*",
                                                        newRank,
                                                        newRank + 1,
                                                        event.getCarryOverXP(),
                                                        rpRequired,
                                                        event.getLevelUps()));
                }

                event.getInteraction().getHook()
                                .sendMessage("<@" + event.getInteraction().getUser().getId() + ">")
                                .addEmbeds(rankUpEmbed.build())
                                .queue();
        }
}