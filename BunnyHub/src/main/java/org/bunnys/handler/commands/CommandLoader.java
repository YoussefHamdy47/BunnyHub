package org.bunnys.handler.commands;

import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Set;

/** Scans the command package and instantiates every {@link BunnyCommand} it finds. */
public class CommandLoader {

    private CommandLoader() {}

    public static void loadCommands(BunnyHub client, CommandRegistry registry, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            BunnyLog.warning("[CommandLoader] No command package specified; skipping auto-load.");
            return;
        }

        BunnyLog.info("[CommandLoader] Scanning " + packageName + "...");

        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackage(packageName)
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.SubTypes));

        Set<Class<? extends BunnyCommand>> classes = reflections.getSubTypesOf(BunnyCommand.class);
        int loaded = 0;

        for (Class<? extends BunnyCommand> clazz : classes.stream().sorted(java.util.Comparator.comparing(Class::getName)).toList()) {
            // Anonymous and abstract subclasses are structure, not commands.
            if (clazz.isAnonymousClass() || Modifier.isAbstract(clazz.getModifiers()))
                continue;

            try {
                registry.registerCommand(clazz.getDeclaredConstructor(BunnyHub.class).newInstance(client));
                loaded++;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("[CommandLoader] " + clazz.getSimpleName()
                        + " has no BunnyHub constructor.", e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("[CommandLoader] Could not initialise " + clazz.getSimpleName(), e);
            }
        }

        BunnyLog.success("[CommandLoader] Loaded " + loaded + " commands.");
    }
}
