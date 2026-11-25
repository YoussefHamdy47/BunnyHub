package org.bunnys.handler.commands;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clickable slash-command mentions ({@code </free-games setup:id>}) in bot text. Discord makes them runnable with one
 * click; they work in message text, card text and embed descriptions and field values (not in titles or footers).
 * Before the first global deploy, or for another bot's command, the plain {@code `/path`} code text stays.
 */
public final class CommandLinks {
    private CommandLinks() {}

    /** A whole code span that is just a command path: {@code `/timer gpa`}, not {@code `/timer register <semester>`}. */
    private static final Pattern CODE = Pattern.compile("`/([a-z0-9_-]{1,32}(?: [a-z0-9_-]{1,32}){0,2})`");
    /** The written form for owner-made text, such as ads: {@code {/free-games setup}}. */
    private static final Pattern BRACES = Pattern.compile("\\{/([a-z0-9_-]{1,32}(?: [a-z0-9_-]{1,32}){0,2})}");

    private static volatile Supplier<Map<String, String>> ids = Map::of;

    /** Where the deployed command IDs come from: the registry, set once at start. */
    public static void use(Supplier<Map<String, String>> commandIds) { ids = commandIds == null ? Map::of : commandIds; }

    /** One command: a link when deployed, {@code `/path`} otherwise. */
    public static String of(String path) { return of(path, ids.get()); }

    /** Every {@code `/path`} and {@code {/path}} of this bot's commands in {@code text}, as links. */
    public static String render(String text) { return render(text, ids.get()); }

    static String of(String path, Map<String, String> known) {
        String id = known.get(path.split(" ")[0]);
        return id == null ? "`/" + path + "`" : "</" + path + ":" + id + ">";
    }

    static String render(String text, Map<String, String> known) {
        if (text == null || text.indexOf('/') < 0) return text;
        // Braces always become a command (code text until deployed); a code span only when it names one of ours.
        String braces = BRACES.matcher(text).replaceAll(m -> Matcher.quoteReplacement(of(m.group(1), known)));
        return CODE.matcher(braces).replaceAll(m -> Matcher.quoteReplacement(
                known.containsKey(m.group(1).split(" ")[0]) ? of(m.group(1), known) : m.group()));
    }
}
