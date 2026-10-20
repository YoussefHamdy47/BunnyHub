package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Gmail API message ({@code users.messages.get}, {@code format=full}) into a {@link MailMessage}.
 * Bodies are base64url-encoded per MIME part; parts are walked depth-first with bounded depth and size so a hostile
 * message can't blow up memory. Nothing found in the email is fetched or executed.
 */
public final class MailParser {
    /** Bodies larger than this are cut; completion notices are a few kilobytes. */
    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_LINKS = 50;
    private static final Pattern ADDRESS = Pattern.compile("<([^<>\\s]+@[^<>\\s]+)>");
    private static final Pattern BARE_ADDRESS = Pattern.compile("([^\\s<>\"]+@[^\\s<>\"]+)");
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_BLOCKS = Pattern.compile("(?is)<(script|style|head|title)\\b.*?</\\1\\s*>");
    private static final Pattern BREAKS = Pattern.compile("(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6])\\b[^>]*>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)");

    private MailParser() {}

    public static MailMessage parse(DataObject message) {
        String id = message.getString("id", "");
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Message has no usable id");
        Instant received = Instant.ofEpochMilli(parseLong(message.getString("internalDate", "0")));
        Set<String> labels = new LinkedHashSet<>();
        message.optArray("labelIds").ifPresent(array -> {
            for (int i = 0; i < array.length(); i++) labels.add(array.getString(i, ""));
        });
        DataObject payload = message.optObject("payload").orElse(DataObject.empty());
        Map<String, String> headers = headers(payload);
        String from = headers.getOrDefault("from", "");

        StringBuilder plain = new StringBuilder(), html = new StringBuilder();
        collect(payload, plain, html, 0);
        String text = !plain.isEmpty() ? normalise(plain.toString()) : normalise(htmlToText(html.toString()));
        Set<URI> links = new LinkedHashSet<>();
        addLinks(links, HREF, html.toString());
        addLinks(links, URL, plain.toString());
        if (links.isEmpty()) addLinks(links, URL, text);
        return new MailMessage(id, received, address(from), displayName(from), decodeHeader(headers.getOrDefault("subject", "")),
                text, new ArrayList<>(links), labels);
    }

    private static Map<String, String> headers(DataObject part) {
        Map<String, String> headers = new HashMap<>();
        part.optArray("headers").ifPresent(array -> {
            for (int i = 0; i < array.length(); i++) {
                DataObject header = array.getObject(i);
                headers.putIfAbsent(header.getString("name", "").toLowerCase(Locale.ROOT), header.getString("value", ""));
            }
        });
        return headers;
    }

    private static void collect(DataObject part, StringBuilder plain, StringBuilder html, int depth) {
        if (depth > MAX_DEPTH) return;
        String type = part.getString("mimeType", "").toLowerCase(Locale.ROOT);
        Optional<DataArray> children = part.optArray("parts");
        if (children.isPresent()) {
            for (int i = 0; i < children.get().length(); i++) collect(children.get().getObject(i), plain, html, depth + 1);
            return;
        }
        if (headers(part).getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT).startsWith("attachment")) return;
        String data = part.optObject("body").map(body -> body.getString("data", "")).orElse("");
        if (data.isEmpty()) return;
        StringBuilder target = type.startsWith("text/plain") ? plain : type.startsWith("text/html") ? html : null;
        if (target == null || target.length() >= MAX_BODY_BYTES) return;
        target.append(decode(data, charset(headers(part).getOrDefault("content-type", ""))));
        if (target.length() > MAX_BODY_BYTES) target.setLength(MAX_BODY_BYTES);
        target.append('\n');
    }

    static String decode(String base64url, Charset charset) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(base64url.replaceAll("[\\s=]", ""));
            int length = Math.min(bytes.length, MAX_BODY_BYTES);
            return new String(bytes, 0, length, charset);
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    private static Charset charset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(matcher.group(1));
        } catch (RuntimeException unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    static String htmlToText(String html) {
        String text = DROP_BLOCKS.matcher(html).replaceAll(" ");
        text = BREAKS.matcher(text).replaceAll("\n");
        text = TAGS.matcher(text).replaceAll(" ");
        return entities(text);
    }

    static String entities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1), replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) replacement = codePoint(entity.substring(2), 16, matcher.group());
            else if (entity.startsWith("#")) replacement = codePoint(entity.substring(1), 10, matcher.group());
            else replacement = switch (entity.toLowerCase(Locale.ROOT)) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "ndash" -> "–";
                case "mdash" -> "—";
                case "rsquo", "lsquo" -> "'";
                case "rdquo", "ldquo" -> "\"";
                case "hellip" -> "…";
                default -> matcher.group();
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix, String original) {
        try {
            int value = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(value) && !Character.isISOControl(value) ? new String(Character.toChars(value)) : " ";
        } catch (NumberFormatException bad) {
            return original;
        }
    }

    /** Collapses runs of spaces, keeps single line breaks, and drops invisible control characters. */
    static String normalise(String text) {
        String cleaned = text.replace("\r", "").replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        cleaned = cleaned.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n");
        return cleaned.strip();
    }

    private static void addLinks(Set<URI> links, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && links.size() < MAX_LINKS) {
            String raw = entities(matcher.groupCount() > 0 ? matcher.group(1) : matcher.group()).strip();
            if (raw.length() > 2048) continue;
            try {
                URI uri = new URI(raw.replaceAll("[.,;:!?]+$", ""));
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ((scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null) links.add(uri);
            } catch (Exception malformed) {
                // Not a usable link; ignored.
            }
        }
    }

    static String address(String from) {
        Matcher bracketed = ADDRESS.matcher(from);
        if (bracketed.find()) return bracketed.group(1).toLowerCase(Locale.ROOT);
        Matcher bare = BARE_ADDRESS.matcher(from);
        return bare.find() ? bare.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static String displayName(String from) {
        int bracket = from.indexOf('<');
        String name = bracket > 0 ? from.substring(0, bracket) : "";
        return decodeHeader(name.replace("\"", "").strip());
    }

    /** RFC 2047 encoded words ({@code =?UTF-8?B?...?=}), which Gmail usually decodes already. */
    static String decodeHeader(String value) {
        if (!value.contains("=?")) return value.strip();
        // Whitespace between two encoded words is not part of the text (RFC 2047 section 6.2).
        Matcher matcher = ENCODED_WORD.matcher(value.replaceAll("\\?=\\s+=\\?", "?==?"));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String decoded;
            try {
                Charset charset = Charset.forName(matcher.group(1));
                byte[] bytes = matcher.group(2).equalsIgnoreCase("b") ? Base64.getDecoder().decode(matcher.group(3))
                        : quotedPrintable(matcher.group(3).replace('_', ' '));
                decoded = new String(bytes, charset);
            } catch (RuntimeException bad) {
                decoded = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }

    private static byte[] quotedPrintable(String text) {
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' && i + 2 < text.length()) {
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else out.write(c);
        }
        return out.toByteArray();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
