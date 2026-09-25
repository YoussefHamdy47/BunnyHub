package org.bunnys.bunnynexus.alerts.domain;

import java.net.URI;
import java.util.Objects;

/** Revision-bound display data. Structural validation is not proof of provider eligibility or URL semantics. */
public record AlertDisplayContent(AlertIdentity.OfferKey offerKey, long contentRevision, String title,
                                  String description, URI claimUrl, String sourceName, URI attributionUrl) {
    public AlertDisplayContent {
        Objects.requireNonNull(offerKey);
        if (contentRevision < 1) throw new IllegalArgumentException("Positive content revision required.");
        title = text(title, 256); description = text(description, 4000); sourceName = text(sourceName, 128);
        link(claimUrl); link(attributionUrl);
    }
    private static String text(String value, int maximum) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > maximum || value.codePoints().anyMatch(cp ->
                (Character.isISOControl(cp) && cp != '\n' && cp != '\t') || (cp >= 0xD800 && cp <= 0xDFFF)))
            throw new IllegalArgumentException("Invalid bounded display text.");
        return value;
    }
    private static void link(URI uri) {
        Objects.requireNonNull(uri);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || uri.getRawFragment() != null || uri.toASCIIString().length() > 1024)
            throw new IllegalArgumentException("Bounded HTTPS link required.");
    }
}
