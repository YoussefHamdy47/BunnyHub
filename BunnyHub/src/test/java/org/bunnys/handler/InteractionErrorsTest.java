package org.bunnys.handler;

import org.bunnys.handler.utils.InteractionErrors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractionErrorsTest {
    @Test void internalExceptionsNeverExposeTheirMessage() {
        for (Throwable error : new Throwable[] {new IllegalArgumentException("private connection"),
                new IllegalStateException("private query"), new RuntimeException("private token")}) {
            assertEquals("Something went wrong. Please try again.", InteractionErrors.userMessage(error));
        }
    }
    @Test void explicitValidationIsBoundedAndCannotPing() {
        assertEquals("Choose a subject.", InteractionErrors.userMessage(new InteractionErrors.InputFailure("Choose a subject.")));
        assertEquals("@\u200Beveryone", InteractionErrors.userMessage(new InteractionErrors.StateFailure("@everyone")));
        assertEquals(1500, InteractionErrors.userMessage(new InteractionErrors.InputFailure("x".repeat(3000))).length());
    }
}
