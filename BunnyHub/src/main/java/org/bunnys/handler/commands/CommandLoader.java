package org.bunnys.handler.commands;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.ClassScanner;
import org.bunnys.utils.BunnyLog;
import java.lang.reflect.InvocationTargetException;

/** Instantiates and registers every {@link BunnyCommand} found in the command package. */
public final class CommandLoader {
    private CommandLoader() {}

    public static void loadCommands(BunnyHub client, CommandRegistry registry, ClassScanner scanner, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            BunnyLog.warning("[CommandLoader] No command package specified; skipping auto-load.");
            return;
        }
        int loaded = 0;
        for (Class<? extends BunnyCommand> type : scanner.find(BunnyCommand.class, packageName)) {
            try {
                registry.registerCommand(type.getDeclaredConstructor(BunnyHub.class).newInstance(client));
                loaded++;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("[CommandLoader] " + type.getSimpleName() + " has no BunnyHub constructor.", e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("[CommandLoader] Could not initialise " + type.getSimpleName(), e);
            }
        }
        if (loaded == 0)
            BunnyLog.warning("[CommandLoader] No commands found in " + packageName + ". Check the folder/package name.");
        else BunnyLog.success("[CommandLoader] Loaded " + loaded + " commands from " + packageName + ".");
    }
}
