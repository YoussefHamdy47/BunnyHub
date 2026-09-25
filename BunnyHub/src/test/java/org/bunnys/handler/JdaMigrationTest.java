package org.bunnys.handler;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bson.conversions.Bson;
import org.bunnys.commands.Avatar;
import org.bunnys.commands.Timer;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdaMigrationTest {
    @Test void allCommandPayloadsSerializeWithJda6() {
        assertEquals("timer", new Timer(null).buildCommandData().toData().getString("name"));
        assertEquals("avatar", new Avatar(null).buildCommandData().toData().getString("name"));
        for (var state : SessionMenuManager.SessionState.values()) {
            var buttons = SessionMenuManager.buildButtons("123", state);
            assertEquals(4, buttons.size());
            try (var message = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder().setContent("Session")
                    .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(buttons)).build()) {
                assertNotNull(message.toData());
            }
        }
    }

    @Test void longSemesterNamesUseLabelModalWithoutDiscordLengthViolations() {
        var timer = new TimerData();
        timer.getCurrentSemester().setSemesterName("S".repeat(80));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            var modal = new Timers("123", mock(IReplyCallback.class)).buildEndSemesterModal("7");
            assertNotNull(modal.toData());
            assertEquals(1, modal.getComponents().size());
            assertInstanceOf(Label.class, modal.getComponents().getFirst());
        }
    }
}
