package org.bunnys.bunnynexus.help;

import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.BunnySubcommandGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The commands a viewer may see, grouped into ordered categories, and path lookup by name or alias. */
final class HelpCatalog {
    /** Preferred category order; anything else follows alphabetically. */
    private static final List<String> CATEGORY_ORDER = List.of("Study", "Free Games", "Information", "Utility", "Owner");

    record Branch(String path, BunnySubcommand subcommand) {}

    record Target(String path, BunnyCommand command, BunnySubcommand subcommand) {}

    /** Every branch in declared order, groups flattened to {@code command group action}. */
    static List<Branch> branches(BunnyCommand command) {
        List<Branch> branches = new ArrayList<>();
        for (BunnySubcommand sub : command.getSubcommands().values())
            branches.add(new Branch(command.getName() + " " + sub.getName(), sub));
        for (BunnySubcommandGroup group : command.getSubcommandGroups().values())
            for (BunnySubcommand sub : group.getSubcommands().values())
                branches.add(new Branch(command.getName() + " " + group.getName() + " " + sub.getName(), sub));
        return branches;
    }

    private final Map<String, List<BunnyCommand>> categories;
    private final List<BunnyCommand> all;

    HelpCatalog(Collection<BunnyCommand> commands, boolean developer) {
        Map<String, List<BunnyCommand>> grouped = new TreeMap<>(Comparator
                .comparingInt((String name) -> {
                    int index = CATEGORY_ORDER.indexOf(name);
                    return index < 0 ? CATEGORY_ORDER.size() : index;
                })
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        List<BunnyCommand> visible = new ArrayList<>();
        for (BunnyCommand command : commands) {
            if (!developer && (command.isDeveloperOnly() || command.isTestOnly())) continue;
            visible.add(command);
            grouped.computeIfAbsent(command.getCategory(), key -> new ArrayList<>()).add(command);
        }
        grouped.values().forEach(list -> list.sort(Comparator.comparing(BunnyCommand::getName)));
        visible.sort(Comparator.comparing(BunnyCommand::getName));
        this.categories = new LinkedHashMap<>(grouped);
        this.all = visible;
    }

    Map<String, List<BunnyCommand>> categories() { return categories; }
    List<BunnyCommand> all() { return all; }
    boolean isEmpty() { return all.isEmpty(); }

    /** The canonical category name, case-insensitively, or null. */
    String category(String name) {
        for (String category : categories.keySet())
            if (category.equalsIgnoreCase(name)) return category;
        return null;
    }

    int pageOf(String category) {
        return new ArrayList<>(categories.keySet()).indexOf(category) + 1;
    }

    /** {@code timer}, {@code timer start} or {@code cmd group action}, by name or alias. */
    Target resolve(String path) {
        String[] words = path.trim().toLowerCase(Locale.ROOT).split("\\s+");
        BunnyCommand command = null;
        for (BunnyCommand candidate : all)
            if (candidate.matches(words[0])) { command = candidate; break; }
        if (command == null) return null;

        if (words.length == 1) return new Target(command.getName(), command, null);
        BunnySubcommand sub = words.length == 2
                ? command.resolveSubcommand(words[1])
                : command.resolve(words[1], words[2]);
        if (sub == null) return null;
        for (Branch branch : branches(command))
            if (branch.subcommand() == sub) return new Target(branch.path(), command, sub);
        return null;
    }
}
