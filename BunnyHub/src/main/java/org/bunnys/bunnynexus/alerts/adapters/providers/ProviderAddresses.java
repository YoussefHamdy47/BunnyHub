package org.bunnys.bunnynexus.alerts.adapters.providers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;

/** Conservative IPv4-only pins. Resolution/refresh is an explicit deployment operation, never a fetch side effect. */
public final class ProviderAddresses {
    private ProviderAddresses() {}
    public static Dns gamerPower(List<InetAddress> addresses) {
        var pins = List.copyOf(addresses);
        if (pins.isEmpty() || pins.size() > 8 || pins.stream().anyMatch(a -> !publicIpv4(a)))
            throw new IllegalArgumentException("One to eight public IPv4 addresses required.");
        return hostname -> {
            if (!"www.gamerpower.com".equals(hostname)) throw new UnknownHostException("Unapproved provider host.");
            return pins;
        };
    }
    static boolean publicIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99) || b == 168))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113));
    }
}
