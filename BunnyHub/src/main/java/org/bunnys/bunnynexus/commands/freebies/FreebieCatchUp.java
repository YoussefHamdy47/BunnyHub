package org.bunnys.bunnynexus.commands.freebies;

import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import java.util.Set;

/**
 * "Post current games now" on the section form and auto-setup. {@link #AUTO}, the default, posts when the channel
 * is newly added to the section and not when an existing setting is only edited. Posting goes through the same
 * catch-up path as the button ({@link FreebieSystem#catchUpChannel}), so eligibility and limits are shared.
 */
public enum FreebieCatchUp {
    AUTO('a', "Only if the channel is new for this section", "Post current games: new channels"),
    ON('y', "Yes, post them now", "Post current games: yes"),
    OFF('n', "No, only future games", "Post current games: no");

    private final char code;
    private final String option, toggle;

    FreebieCatchUp(char code, String option, String toggle) { this.code = code; this.option = option; this.toggle = toggle; }

    public char code() { return code; }
    public String option() { return option; }
    public String toggle() { return toggle; }

    /** Whether to post now for a channel that is ({@code newlyAdded}) or is not new to the section. */
    public boolean resolve(boolean newlyAdded) { return this == ON || (this == AUTO && newlyAdded); }

    /** The auto-setup toggle cycles AUTO, OFF, ON. */
    public FreebieCatchUp next() { return this == AUTO ? OFF : this == OFF ? ON : AUTO; }

    /** Unknown or missing values (older controls) mean the default. */
    public static FreebieCatchUp of(String value) {
        if (value != null && value.length() == 1)
            for (FreebieCatchUp choice : values()) if (choice.code == value.charAt(0)) return choice;
        return AUTO;
    }

    /** Sections plus a non-default choice in one id token: {@code mic} or {@code mic!n}. */
    public static String token(Set<FreebieSection> sections, FreebieCatchUp choice) {
        return FreebieSection.encode(sections) + (choice == AUTO ? "" : "!" + choice.code);
    }

    public static Set<FreebieSection> sectionsOf(String token) {
        return FreebieSection.decode(token == null ? null : token.split("!", 2)[0]);
    }

    public static FreebieCatchUp choiceOf(String token) {
        if (token == null || !token.contains("!")) return AUTO;
        return of(token.substring(token.indexOf('!') + 1));
    }

    /** The save reply: how many games were queued, without waiting for any send. */
    public static String reply(FreebieSystem.CatchUp result, String where) {
        if (result.queued() > 0) return "📤 Posting " + result.queued() + " current free game(s) in " + where + " shortly.";
        return result.live() == 0 ? "None of the approved games are live right now." : where + " already has the current games.";
    }
}
