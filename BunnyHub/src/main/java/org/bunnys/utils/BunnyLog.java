package org.bunnys.utils;

public class BunnyLog {
    public static void error(String message, Throwable error) {
        org.slf4j.LoggerFactory.getLogger(BunnyLog.class).error("{} | {}", message, FailureDiagnostics.describe(error));
    }
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String CYAN = "\u001B[36m";

    private static boolean showInfoLogs = true;

    public static void setLogActions(boolean logActions) {
        showInfoLogs = logActions;
    }

    public static void raw(String color, String message) {
        System.out.println(color + message + RESET);
    }

    public static void info(String message) {
        if (showInfoLogs)
            System.out.println(CYAN + "[INFO] " + RESET + message);
    }

    public static void success(String message) {
        System.out.println(GREEN + "[SUCCESS] " + RESET + message);
    }

    public static void warning(String message) {
        System.out.println(YELLOW + "[WARNING] " + RESET + message);
    }

    public static void error(String message) {
        System.out.println(RED + "[ERROR] " + RESET + message);
    }
}
