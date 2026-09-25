package org.bunnys.handler.commands;

import java.util.List;

/**
 * The alias rules, in one place.
 *
 * <p>Commands and subcommands both accept alternative names, and the two would otherwise
 * carry identical copies of "fold the case, drop the blanks, refuse a duplicate, refuse
 * the primary name". Two copies of a matching rule is how one of them quietly stops
 * matching the way the other does.
 */
final class Aliases {

    private Aliases() {}

    /**
     * Records alternative names, ignoring anything unusable.
     *
     * <p>Silently skips rather than throwing. Aliases are declared in a constructor that
     * runs during reflective command loading, and a typo in a convenience name is not
     * worth refusing to start the bot over.
     *
     * @param primary the owner's real name, which is never stored as an alias of itself
     */
    static void addTo(List<String> target, String primary, String... values) {
        if (values == null)
            return;

        for (String value : values) {
            if (value == null)
                continue;

            String alias = value.trim().toLowerCase(java.util.Locale.ROOT);

            if (alias.isEmpty() || alias.equalsIgnoreCase(primary) || target.contains(alias))
                continue;

            target.add(alias);
        }
    }

    /** True when {@code candidate} is the primary name or any of its aliases. */
    static boolean matches(String primary, List<String> aliases, String candidate) {
        if (candidate == null)
            return false;

        String needle = candidate.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty())
            return false;

        return needle.equalsIgnoreCase(primary) || aliases.contains(needle);
    }
}
