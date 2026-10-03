package org.bunnys.handler.router;

import org.bunnys.handler.ClassScanner;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Validates a complete component table before the router publishes it. */
public final class ComponentLoader {
    private ComponentLoader() {}

    public static <T> Map<String, T> load(ClassScanner scanner, String packageName, Class<T> type, Function<T, String> prefix) {
        Map<String, T> loaded = new LinkedHashMap<>();
        for (var candidate : scanner.find(type, packageName)) {
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
