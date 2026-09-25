package org.bunnys.bunnynexus.events.custom;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.database.models.timers.Semester;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RecordBrokenEvent extends Event {

    public enum RecordType {
        SESSION, SEMESTER
    }

    private final IReplyCallback interaction;
    private final RecordType type;
    private final Double sessionTime; // Nullable when type is SEMESTER
    private final Semester semester; // Nullable when type is SESSION

    public RecordBrokenEvent(@NotNull JDA api, @NotNull IReplyCallback interaction, @NotNull RecordType type,
            @Nullable Double sessionTime, @Nullable Semester semester) {
        super(api);
        this.interaction = interaction;
        this.type = type;
        this.sessionTime = sessionTime;
        this.semester = semester;
    }

    @NotNull
    public IReplyCallback getInteraction() {
        return interaction;
    }

    @NotNull
    public RecordType getType() {
        return type;
    }

    @Nullable
    public Double getSessionTime() {
        return sessionTime;
    }

    @Nullable
    public Semester getSemester() {
        return semester;
    }
}