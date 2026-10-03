package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the text after {@code @BotName command} onto the command's declared options: named {@code key:value}
 * pairs first, then positional values in declared order, with the last string option taking the rest.
 */
final class MentionArguments {
    private MentionArguments() {}

    /**
     * Splits on whitespace while keeping quoted runs together.
     *
     * <p>Three alternatives, order-sensitive: {@code key:"quoted value"} must be tried
     * before a bare quoted run, which must be tried before a plain word - otherwise
     * {@code query:"a b"} would split at the space and lose half the value.
     */
    private static final Pattern TOKEN = Pattern.compile("([^\\s:]+):\"([^\"]*)\"|\"([^\"]*)\"|(\\S+)");

    static Map<String, String> parse(List<OptionData> options, String argumentTail) {
        Map<String, String> resolved = new HashMap<>();
        if (argumentTail == null || argumentTail.isBlank() || options.isEmpty())
            return resolved;

        List<String> tokens = tokenize(argumentTail);
        List<String> positional = new ArrayList<>();

        // Pass 1: name:value pairs, matched only against declared option names so a
        // literal colon in free text ("ratio 3:1") is not mistaken for one.
        for (String token : tokens) {
            int colon = token.indexOf(':');
            boolean named = false;

            if (colon > 0) {
                String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                for (OptionData option : options) {
                    if (option.getName().equalsIgnoreCase(key)) {
                        resolved.put(option.getName(), token.substring(colon + 1));
                        named = true;
                        break;
                    }
                }
            }

            if (!named)
                positional.add(token);
        }

        // Pass 2: fill what is still empty, in declared order.
        int cursor = 0;
        for (int i = 0; i < options.size() && cursor < positional.size(); i++) {
            OptionData option = options.get(i);
            if (resolved.containsKey(option.getName()))
                continue;

            boolean isLastFillable = isFinalStringOption(options, i, resolved);

            if (isLastFillable && option.getType() == OptionType.STRING) {
                resolved.put(option.getName(), String.join(" ", positional.subList(cursor, positional.size())));
                cursor = positional.size();
            } else {
                resolved.put(option.getName(), positional.get(cursor++));
            }
        }

        return resolved;
    }

    /** True when no later option could still take a positional value. */
    private static boolean isFinalStringOption(List<OptionData> options, int index, Map<String, String> resolved) {
        for (int i = index + 1; i < options.size(); i++)
            if (!resolved.containsKey(options.get(i).getName()))
                return false;
        return true;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input.trim());

        while (matcher.find()) {
            if (matcher.group(1) != null)
                tokens.add(matcher.group(1) + ":" + matcher.group(2)); // key:"quoted value"
            else if (matcher.group(3) != null)
                tokens.add(matcher.group(3));                          // "quoted value"
            else
                tokens.add(matcher.group(4));                          // plain word
        }

        return tokens;
    }
}
