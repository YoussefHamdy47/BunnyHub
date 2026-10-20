package org.bunnys.bunnynexus.mailbridge;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One Gmail message, reduced to what the bridge needs. Everything except {@code id} came from the sender and is
 * untrusted: it is only ever matched against patterns or escaped before it reaches Discord.
 *
 * @param fromAddress the bare address from the From header, lower-cased; empty when it couldn't be read
 * @param text        the readable body: text/plain when present, otherwise text/html with the markup removed
 * @param links       http(s) links found in the body, in order, without duplicates
 */
public record MailMessage(String id, Instant received, String fromAddress, String fromName, String subject,
                          String text, List<URI> links, Set<String> labelIds) {
    public MailMessage {
        links = List.copyOf(links);
        labelIds = Set.copyOf(labelIds);
    }

    /** The domain part of the sender's address, for redacted diagnostics. */
    public String senderDomain() {
        int at = fromAddress.lastIndexOf('@');
        return at < 0 ? "" : fromAddress.substring(at + 1);
    }

    /** A link straight to a ChatGPT conversation or task, if the email has one. Tracking redirects don't count. */
    public Optional<URI> reportLink() {
        for (URI link : links) {
            String host = link.getHost() == null ? "" : link.getHost().toLowerCase(java.util.Locale.ROOT);
            if (("chatgpt.com".equals(host) || "chat.openai.com".equals(host)) && "https".equals(link.getScheme()))
                return Optional.of(link);
        }
        return Optional.empty();
    }
}
