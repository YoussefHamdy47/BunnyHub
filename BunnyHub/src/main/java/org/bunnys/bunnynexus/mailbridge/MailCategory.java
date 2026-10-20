package org.bunnys.bunnynexus.mailbridge;

/** The career-search task a notification belongs to. UNKNOWN is never forwarded with its content. */
public enum MailCategory {
    EEE_TRAINING("EEE Industrial Training Hunter", 0xF5A623, "EEE Industrial Training Hunter"),
    POWER_ELECTRONICS("Power Electronics Career Intelligence", 0x4A90E2, "Power Electronics Career Intelligence"),
    RTL_VERIFICATION("RTL / Digital Verification Career Tracker", 0x7ED321, "RTL / Digital Verification Career Tracker"),
    UNKNOWN("Unclassified", 0x9B9B9B, "");

    private final String label;
    private final int colour;
    private final String defaultPattern;

    MailCategory(String label, int colour, String defaultPattern) {
        this.label = label;
        this.colour = colour;
        this.defaultPattern = defaultPattern;
    }

    public String label() { return label; }
    public int colour() { return colour; }

    /** The task's name as typed when the task was created; case-insensitive substring match by default. */
    String defaultPattern() { return defaultPattern; }
}
