package org.bunnys.bunnynexus.alerts.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared versioned, length-prefixed identity encoding. Never include mutable presentation metadata. */
public final class StableIdentity {
    private StableIdentity() {}
    public static String hash(String... parts) {
        if (parts == null || parts.length == 0) throw new IllegalArgumentException("Identity needs a namespace.");
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = AlertIdentity.token(part).getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
