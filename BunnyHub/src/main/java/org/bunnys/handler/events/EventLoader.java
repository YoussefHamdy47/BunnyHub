package org.bunnys.handler.events;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.ClassScanner;
import org.bunnys.utils.BunnyLog;
import java.lang.reflect.InvocationTargetException;

/** Instantiates every {@link BunnyEvent} in the event package and registers it with JDA. */
public final class EventLoader {
    private EventLoader() {}

    public static void loadEvents(BunnyHub client, JDABuilder jdaBuilder, ClassScanner scanner, String packageName) {
        int loaded = 0;
        for (Class<? extends BunnyEvent> type : scanner.find(BunnyEvent.class, packageName)) {
            try {
                jdaBuilder.addEventListeners(type.getDeclaredConstructor(BunnyHub.class).newInstance(client));
                loaded++;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Missing BunnyHub constructor: " + type.getName(), e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Could not initialize event: " + type.getName(), e);
            }
        }
        if (loaded == 0)
            BunnyLog.warning("[EventLoader] No events found in " + packageName + ". Check the folder/package name.");
        else BunnyLog.success("[EventLoader] Loaded " + loaded + " events from " + packageName + ".");
    }

    public static int clearEvents(JDA jda) {
        if (jda == null) return 0;
        Object[] listeners = jda.getRegisteredListeners().stream().filter(BunnyEvent.class::isInstance).toArray();
        for (Object listener : listeners) jda.removeEventListener(listener);
        return listeners.length;
    }
}
