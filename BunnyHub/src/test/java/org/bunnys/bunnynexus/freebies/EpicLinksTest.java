package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Product-page normalization and the repeated-parameter checkout contract from the ten supplied examples. */
class EpicLinksTest {
    static final EpicLinks.Offer A = new EpicLinks.Offer("c7e46cce751b40b6937e878abd0fc531", "06190191cede42afb633fe89ef08c27e"),
            B = new EpicLinks.Offer("156c17659ff0468bb533eba104a1f19f", "6aab3ddef4f84fbc9dd81c60f9d8549c"),
            C = new EpicLinks.Offer("a0c80bf673a548bc97b4bb25abfaf2b4", "6a846880836044a2adad614ac771f487"),
            D = new EpicLinks.Offer("45f125178d8c4bc18e1827a307bcc5c0", "db1202a30c5541dd99cdf54c918a9348"),
            E = new EpicLinks.Offer("436099305a2249a8bbe4d9113fc41d53", "cb733f3432db46bca94965b422c33ede"),
            F = new EpicLinks.Offer("bfb80547289b477193ce3b14d83cd292", "af6b823b8698465f9aedc3bddf2e8686");

    static String expected(EpicLinks.Offer... offers) {
        var joiner = new StringJoiner("&", "https://store.epicgames.com/purchase?", "");
        for (var o : offers) joiner.add("offers=1-" + o.namespace() + "-" + o.offerId());
        return joiner.toString();
    }

    @Test void localeAndLangFormsOfOneProductPageAreTheSameProduct() {
        for (String url : List.of("https://store.epicgames.com/en-US/p/home-19212a", "https://store.epicgames.com/p/home-19212a?lang=en-US",
                "https://store.epicgames.com/de/p/home-19212a?utm_source=x", "https://www.epicgames.com/store/fr/p/home-19212a",
                "https://store.epicgames.com/zh-Hant/p/home-19212a#about"))
            assertEquals(Optional.of("home-19212a"), EpicLinks.productSlug(URI.create(url)), url);
    }

    @Test void platformAndEditionSlugsStayDistinct() {
        assertEquals(Optional.of("home-android-9ba07f"), EpicLinks.productSlug(URI.create("https://store.epicgames.com/en-US/p/home-android-9ba07f")));
        assertNotEquals(EpicLinks.productSlug(URI.create("https://store.epicgames.com/p/home-ios-69cfcf")),
                EpicLinks.productSlug(URI.create("https://store.epicgames.com/p/home-19212a")));
    }

    @Test void otherHostsPathsAndCheckoutLinksAreNotProductPages() {
        for (String url : List.of("https://www.gamerpower.com/open/home", "http://store.epicgames.com/p/home-19212a",
                "https://store.epicgames.com.evil.example/p/home-19212a", "https://store.epicgames.com/en-US/p/home-19212a/extra",
                "https://store.epicgames.com/purchase?offers=1-a-b", "https://user@store.epicgames.com/p/home-19212a",
                "https://store.epicgames.com:8443/p/home-19212a", "https://store.epicgames.com/p/Home_19212a"))
            assertTrue(EpicLinks.productSlug(URI.create(url)).isEmpty(), url);
    }

    @Test void reproducesAllTenSuppliedCheckoutUrlsFromTheSixPairs() {
        List<List<EpicLinks.Offer>> inputs = List.of(List.of(A), List.of(B), List.of(A, B), List.of(C), List.of(D, C), List.of(D),
                List.of(C), List.of(E, F), List.of(E), List.of(F));
        assertEquals(10, inputs.size());
        for (var input : inputs)
            assertEquals(expected(input.toArray(EpicLinks.Offer[]::new)), EpicLinks.checkout(input).orElseThrow().toString());
        assertEquals(6, new HashSet<>(inputs.stream().flatMap(List::stream).toList()).size());
    }

    @Test void duplicatesCollapseButSameNamespaceDifferentOfferStays() {
        var sibling = new EpicLinks.Offer(A.namespace(), "b4363ca6c155415da6d2686d117b9eca");
        assertEquals(expected(A, B), EpicLinks.checkout(List.of(A, B, A)).orElseThrow().toString());
        assertEquals(expected(A, sibling), EpicLinks.checkout(List.of(A, sibling)).orElseThrow().toString());
        assertTrue(EpicLinks.checkout(List.of()).isEmpty());
        assertTrue(EpicLinks.checkout(List.of(A)).orElseThrow().toString().contains("offers=1-"), "prefix 1 is fixed");
    }

    @Test void invalidIdentifiersAreRejectedAndOverlongLinksAreNeverTruncated() {
        assertThrows(IllegalArgumentException.class, () -> new EpicLinks.Offer("bad namespace", "x"));
        assertThrows(IllegalArgumentException.class, () -> new EpicLinks.Offer("ns", "id&offers=1-evil-evil"));
        assertThrows(IllegalArgumentException.class, () -> new EpicLinks.Offer(null, "id"));
        List<EpicLinks.Offer> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(new EpicLinks.Offer("n".repeat(32), String.format("%032d", i)));
        assertTrue(EpicLinks.checkout(many).isEmpty(), "eight 32+32 pairs exceed Discord's 512-character button URL");
        assertTrue(EpicLinks.checkout(many.subList(0, 6)).orElseThrow().toString().length() <= EpicLinks.MAX_BUTTON_URL);
    }
}
