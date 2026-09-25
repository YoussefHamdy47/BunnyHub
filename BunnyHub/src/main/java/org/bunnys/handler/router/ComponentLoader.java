package org.bunnys.handler.router;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Validates a complete component table before the router publishes it. */
public final class ComponentLoader {
    private ComponentLoader() {}
    public static <T> Map<String, T> load(String packageName, Class<T> type, Function<T, String> prefix) {
        var reflections = new Reflections(new ConfigurationBuilder().forPackage(packageName)
                .filterInputsBy(new FilterBuilder().includePackage(packageName)).setScanners(Scanners.SubTypes));
        Map<String, T> loaded = new LinkedHashMap<>();
        for (var candidate : reflections.getSubTypesOf(type).stream().sorted(Comparator.comparing(Class::getName)).toList()) {
            if (candidate.isAnonymousClass() || Modifier.isAbstract(candidate.getModifiers())) continue;
            try {
                T handler = candidate.getDeclaredConstructor().newInstance();
                String key = prefix.apply(handler);
                if (key == null || key.isBlank() || key.contains(":"))
                    throw new IllegalArgumentException("Invalid component prefix on " + candidate.getName());
                if (loaded.putIfAbsent(key, handler) != null)
                    throw new IllegalArgumentException("Duplicate component prefix: " + key);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot load component " + candidate.getName(), error);
            }
        }
        return Map.copyOf(loaded);
    }
}
