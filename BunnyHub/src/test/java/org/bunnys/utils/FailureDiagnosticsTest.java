package org.bunnys.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class FailureDiagnosticsTest {
    @Test void interactionReportersDoNotPassRawThrowablesToLoggingBackend() {
        var event = org.mockito.Mockito.mock(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var failure = new IllegalStateException("private-password");
        for (Class<?> type : java.util.List.of(ErrorReporter.class, org.bunnys.handler.utils.InteractionErrors.class)) {
            var logger = (Logger) LoggerFactory.getLogger(type);
            var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
            try {
                if (type == ErrorReporter.class) ErrorReporter.report("test", "123", failure);
                else org.bunnys.handler.utils.InteractionErrors.report(event, failure);
                assertNull(appender.list.getFirst().getThrowableProxy());
                assertFalse(appender.list.getFirst().getFormattedMessage().contains("private-password"));
            } finally { logger.detachAppender(appender); appender.stop(); }
        }
    }
    @Test void messagesCausesAndSuppressedPayloadsNeverReachApplicationErrorLogs() {
        var error = new IllegalArgumentException("mongodb://private:secret@host/db",
                new IllegalStateException("Authorization: Bot secret-token"));
        error.addSuppressed(new RuntimeException("hidden-password"));
        var logger = (Logger) LoggerFactory.getLogger(BunnyLog.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            BunnyLog.error("Synthetic failure", error);
            var event = appender.list.getFirst();
            assertNull(event.getThrowableProxy());
            var output = event.getFormattedMessage();
            assertFalse(output.contains("secret")); assertFalse(output.contains("password"));
            assertFalse(output.contains("mongodb://"));
            assertTrue(output.contains("IllegalArgumentException"));
            assertTrue(output.contains("IllegalStateException"));
            assertTrue(output.contains("FailureDiagnosticsTest"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void cyclesAndOversizedLocationsStayBoundedAndSingleLine() {
        var first = new RuntimeException("private");
        var second = new IllegalStateException("private");
        first.initCause(second); second.initCause(first);
        var oversized = new StackTraceElement("x".repeat(10_000) + "\n", "\rforged", "secret-file", 7);
        first.setStackTrace(java.util.Collections.nCopies(100, oversized).toArray(StackTraceElement[]::new));
        var output = FailureDiagnostics.describe(first);
        assertTrue(output.length() < 10_000);
        assertFalse(output.contains("\n")); assertFalse(output.contains("\r"));
        assertFalse(output.contains("secret-file")); assertFalse(output.contains("private"));
    }
}
