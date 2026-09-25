package org.bunnys.handler.events;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

public class EventLoader {

    public static void loadEvents(BunnyHub client, JDABuilder jdaBuilder, String packageName) {
        BunnyLog.info("Scanning event package: " + packageName + "...");

        // Constrained classpath scan
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackage(packageName)
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.SubTypes));

        Set<Class<? extends BunnyEvent>> eventClasses = reflections.getSubTypesOf(BunnyEvent.class);

        int loadedCount = 0;

        for (Class<? extends BunnyEvent> clazz : eventClasses.stream().sorted(java.util.Comparator.comparing(Class::getName)).toList()) {
            if (clazz.isAnonymousClass() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) continue;
            try {
                BunnyEvent eventInstance = clazz.getDeclaredConstructor(BunnyHub.class).newInstance(client);
                jdaBuilder.addEventListeners(eventInstance);
                loadedCount++;

            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Missing BunnyHub constructor: " + clazz.getName(), e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Could not initialize event: " + clazz.getName(), e);
            }
        }

        BunnyLog.success("Successfully loaded " + loadedCount + " events.");
    }

    public static int clearEvents(JDA jda) {
        if (jda == null)
            return 0;
        Object[] listeners = jda.getRegisteredListeners().stream().filter(BunnyEvent.class::isInstance).toArray();
        for (Object listener : listeners)
            jda.removeEventListener(listener);
        return listeners.length;
    }
}