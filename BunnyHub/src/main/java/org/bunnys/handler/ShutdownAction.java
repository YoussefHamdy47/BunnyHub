package org.bunnys.handler;

import org.bunnys.utils.BunnyLog;
import java.util.Objects;

/** A named cleanup stage whose failure cannot skip subsequent stages. */
public record ShutdownAction(String name, Runnable action) implements Runnable {
    public ShutdownAction {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Cleanup name is required.");
        Objects.requireNonNull(action, "Cleanup action");
    }

    @Override public void run() {
        try { action.run(); }
        catch (VirtualMachineError fatal) { throw fatal; }
        // Linkage and assertion errors from feature code must not skip later stages either.
        catch (Throwable error) { BunnyLog.error("[Shutdown] Failed: " + name, error); }
    }
}
