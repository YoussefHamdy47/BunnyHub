package org.bunnys.handler;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One classpath scan shared by every loader. Scanning each package separately re-reads the whole (shaded) JAR per
 * kind; on the release JAR that was ~180 ms cold for six scans against ~30 ms for one.
 */
public final class ClassScanner {
    private final Reflections reflections;

    public ClassScanner(Collection<String> packages) {
        Set<String> roots = new LinkedHashSet<>();
        packages.stream().filter(Objects::nonNull).forEach(roots::add);
        var filter = new FilterBuilder();
        roots.forEach(filter::includePackage);
        reflections = roots.isEmpty() ? null : new Reflections(new ConfigurationBuilder()
                .forPackages(roots.toArray(String[]::new)).filterInputsBy(filter).setScanners(Scanners.SubTypes));
    }

    public static ClassScanner of(String... packages) {
        return new ClassScanner(List.of(packages));
    }

    /**
     * Concrete classes extending or implementing {@code type} that live in {@code packageName} or below, in
     * class-name order so loading is deterministic. Abstract, anonymous and interface types are skipped.
     */
    public <T> List<Class<? extends T>> find(Class<T> type, String packageName) {
        if (reflections == null || packageName == null) return List.of();
        String prefix = packageName + ".";
        return reflections.getSubTypesOf(type).stream()
                .filter(candidate -> candidate.getName().startsWith(prefix))
                .filter(candidate -> !candidate.isInterface() && !candidate.isAnonymousClass()
                        && !Modifier.isAbstract(candidate.getModifiers()))
                .sorted(Comparator.comparing(Class::getName))
                .<Class<? extends T>>map(candidate -> candidate)
                .toList();
    }
}
