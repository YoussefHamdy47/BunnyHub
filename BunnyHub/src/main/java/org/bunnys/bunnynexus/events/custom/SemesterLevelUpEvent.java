package org.bunnys.bunnynexus.events.custom;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.database.models.timers.TimerData;
import org.jetbrains.annotations.NotNull;

public class SemesterLevelUpEvent extends Event {
    private final IReplyCallback interaction;
    private final int levelUps;
    private final double carryOverXP;
    private final TimerData timerData;

    public SemesterLevelUpEvent(@NotNull JDA api, @NotNull IReplyCallback interaction, int levelUps, double carryOverXP,
            @NotNull TimerData timerData) {
        super(api);
        this.interaction = interaction;
        this.levelUps = levelUps;
        this.carryOverXP = carryOverXP;
        this.timerData = timerData;
    }

    @NotNull
    public IReplyCallback getInteraction() {
        return interaction;
    }

    public int getLevelUps() {
        return levelUps;
    }

    public double getCarryOverXP() {
        return carryOverXP;
    }

    @NotNull
    public TimerData getTimerData() {
        return timerData;
    }
}