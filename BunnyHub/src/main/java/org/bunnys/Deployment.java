package org.bunnys;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which database this process uses, and the guard that keeps a test instance away from production.
 *
 * <p>{@code MONGO_DB_NAME} (optional, from .env) overrides the database name; without it the bot uses
 * {@value #PRODUCTION_DATABASE} exactly as before. A test instance ({@code -Dbunny.test.instance=true}, set by
 * {@code start_test_server.bat}) refuses to start on the production database.
 */
public final class Deployment {
    private Deployment() {}

    public static final String PRODUCTION_DATABASE = "GBF", DATABASE_KEY = "MONGO_DB_NAME", TEST_INSTANCE = "bunny.test.instance";
    /** MongoDB database name rules: no {@code / \ . " $ *  < > : | ?}, spaces or NUL, at most 63 bytes. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,63}");

    /** The configured database name, or {@value #PRODUCTION_DATABASE} when the key is missing or blank. */
    public static String databaseName(Function<String, String> env) {
        String raw = env.apply(DATABASE_KEY);
        if (raw == null || raw.isBlank()) return PRODUCTION_DATABASE;
        String name = raw.strip();
        if (!VALID.matcher(name).matches())
            throw new IllegalArgumentException(DATABASE_KEY + " must be 1-63 letters, digits, '_' or '-'.");
        return name;
    }

    /** A test instance must never use the production database (compared case-insensitively, as on Windows). */
    public static void requireSafe(boolean testInstance, String databaseName) {
        if (testInstance && PRODUCTION_DATABASE.equalsIgnoreCase(databaseName))
            throw new IllegalStateException("Test instance refused: " + DATABASE_KEY + " must not be " + PRODUCTION_DATABASE
                    + ". Set a separate test database name in the test env file.");
    }

    public static boolean testInstance() { return Boolean.getBoolean(TEST_INSTANCE); }
}
