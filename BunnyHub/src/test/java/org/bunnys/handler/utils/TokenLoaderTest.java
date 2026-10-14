package org.bunnys.handler.utils;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** A requested env file is used as-is or the start fails; it never silently falls back to the production .env. */
class TokenLoaderTest {
    final Set<String> present = Set.of(".env", ".env.test");

    @Test void noRequestMeansTheUsualEnvFile() {
        assertNull(TokenLoader.requestedFile(null, present::contains));
        assertNull(TokenLoader.requestedFile(" ", present::contains));
    }

    @Test void aRequestedFileIsUsed() {
        assertEquals(".env.test", TokenLoader.requestedFile(" .env.test ", present::contains));
    }

    @Test void aMissingRequestedFileFailsInsteadOfFallingBack() {
        var failure = assertThrows(IllegalStateException.class, () -> TokenLoader.requestedFile(".env.staging", present::contains));
        assertTrue(failure.getMessage().contains("refusing to fall back"));
    }

    @Test void onlyEnvFilesInTheWorkingDirectory() {
        for (String bad : new String[]{"../.env.test", "C:\\x\\.env.test", "config.txt", "sub/.env.test"})
            assertThrows(IllegalStateException.class, () -> TokenLoader.requestedFile(bad, n -> true), bad);
    }
}
