package org.bunnys.bunnynexus.events.custom;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.database.models.user.BunnyUser;
import org.jetbrains.annotations.NotNull;

public class AccountLevelUpEvent extends Event {
    private final IReplyCallback interaction;
    private final int levelUps;
    private final double carryOverXP;
    private final BunnyUser userData;

    public AccountLevelUpEvent(@NotNull JDA api, @NotNull IReplyCallback interaction, int levelUps, double carryOverXP,
            @NotNull BunnyUser userData) {
        super(api);
        this.interaction = interaction;
        this.levelUps = levelUps;
        this.carryOverXP = carryOverXP;
        this.userData = userData;
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
    public BunnyUser getUserData() {
        return userData;
    }
}