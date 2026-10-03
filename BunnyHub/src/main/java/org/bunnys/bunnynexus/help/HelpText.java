package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.Map;

/** Text and field helpers for the help menu: option syntax, bounded text, and embeds clamped to Discord limits. */
final class HelpText {
    private HelpText() {}

    static final int MAX_FIELD_VALUE = MessageEmbed.VALUE_MAX_LENGTH;
    static final int MAX_FIELD_NAME = MessageEmbed.TITLE_MAX_LENGTH;
    /** Held under the real ceiling: the footer and title are counted after the fields. */
    static final int MAX_EMBED_TOTAL = MessageEmbed.EMBED_MAX_LENGTH_BOT - 300;
    static final int MAX_FIELDS = 24;

    private static final Map<String, String> CATEGORY_EMOJI = Map.of(
            "Study", "📚", "Free Games", "🎁", "Information", "🔎", "Utility", "🧰", "Owner", "🛠️");

    /** {@code <required>} and {@code [optional]}, in declared order. */
    static String syntax(List<OptionData> options) {
        StringBuilder args = new StringBuilder();
        for (OptionData option : options)
            args.append(option.isRequired() ? " <" + option.getName() + ">" : " [" + option.getName() + "]");
        return args.toString();
    }

    static String typeHint(OptionData option) {
        return switch (option.getType()) {
            case USER -> "member";
            case ROLE -> "role";
            case CHANNEL -> "channel";
            case INTEGER -> "whole number";
            case NUMBER -> "number";
            case BOOLEAN -> "true / false";
            case ATTACHMENT -> "file";
            case MENTIONABLE -> "member or role";
            default -> option.isAutoComplete() ? "text, with suggestions" : "text";
        };
    }

    static String sample(OptionData option) {
        if (!option.getChoices().isEmpty()) return option.getChoices().getFirst().getAsString();
        return switch (option.getType()) {
            case USER, MENTIONABLE -> "@member";
            case ROLE -> "@role";
            case CHANNEL -> "#channel";
            case INTEGER, NUMBER -> "1";
            case BOOLEAN -> "true";
            default -> option.getName();
        };
    }

    static String emoji(String category) {
        return CATEGORY_EMOJI.getOrDefault(category, "📁");
    }

    static String describe(String text) {
        return text == null || text.isBlank() ? "No description." : text;
    }

    static String shorten(String text, int limit) {
        String value = describe(text);
        return value.length() <= limit ? value : value.substring(0, limit - 1).stripTrailing() + "…";
    }

    /** User-typed text echoed back: no markdown breakouts, bounded. */
    static String sanitize(String typed, int limit) {
        return shorten(typed.replace("`", "'").replaceAll("\\s+", " ").trim(), limit);
    }

    /** Splits lines across as many fields as needed instead of truncating the list. */
    static void chunked(EmbedBuilder embed, String name, List<String> lines) {
        StringBuilder current = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String piece = line.length() > MAX_FIELD_VALUE - 2 ? shorten(line, MAX_FIELD_VALUE - 2) : line;
            if (current.length() + piece.length() + 2 > MAX_FIELD_VALUE) {
                if (!field(embed, first ? name : name + " (cont.)", current.toString())) return;
                first = false;
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(piece);
        }
        if (!current.isEmpty()) field(embed, first ? name : name + " (cont.)", current.toString());
    }

    /**
     * Adds a field clamped to Discord's limits and reports whether it fitted. JDA throws on an
     * oversized field, which would take the whole menu down, and a page can pass the 6,000
     * character embed total while every single field is legal on its own.
     */
    static boolean field(EmbedBuilder embed, String name, String value) {
        String safeName = clamp(name, MAX_FIELD_NAME);
        String safeValue = clamp(value, MAX_FIELD_VALUE);
        if (embed.getFields().size() >= MAX_FIELDS
                || embed.length() + safeName.length() + safeValue.length() > MAX_EMBED_TOTAL)
            return false;
        embed.addField(safeName, safeValue, false);
        return true;
    }

    static String clamp(String text, int limit) {
        if (text == null || text.isBlank()) return "​";
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
