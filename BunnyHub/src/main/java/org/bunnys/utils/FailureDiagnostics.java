package org.bunnys.utils;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Bounded exception metadata. Never includes messages, toString(), or suppressed exception payloads. */
public final class FailureDiagnostics {
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]");
    private FailureDiagnostics() {}

    public static String describe(Throwable failure) {
        if (failure == null) return "No exception supplied";
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var result = new StringBuilder();
        for (int cause = 0; failure != null && cause < 4 && seen.add(failure); cause++) {
            if (cause > 0) result.append("; caused by ");
            result.append(location(failure.getClass().getName()));
            var frames = failure.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) {
                // Omit file names/module metadata: the code location is enough to locate a failure.
                var frame = frames[i];
                result.append(" at ").append(location(frame.getClassName())).append('.').append(location(frame.getMethodName()))
                        .append(':').append(frame.getLineNumber());
            }
            failure = failure.getCause();
        }
        return result.toString();
    }

    private static String location(String value) {
        var bounded = value.substring(0, Math.min(value.length(), 128));
        return CONTROL.matcher(bounded).replaceAll("?");
    }
}
