package org.bunnys.utils;

import java.awt.Color;

/** The bot's shared palette and custom emoji. */
public final class AppDesign {
    private AppDesign() {}

    public static final class Emojis {
        private Emojis() {}

        public static final String VERIFY = "<:verified:821419611438317638>";
        public static final String ERROR = "<:error:822091680605011978>";
        public static final String STOP = "<:Stop:1518033663047241929>";
        public static final String EPIC = "<:Epic:824679104220626954>";
        public static final String STEAM = "<:steam:705062577817780315>";

        // Launcher badges: the bot's own application emojis (usable in every server), added 2026-11-17.
        public static final String GOG = "<:GOG:1572185523924115587>";
        public static final String UBISOFT = "<:Ubisoft:1572185526381977680>";
        public static final String EA = "<:EA:1572185527870816359>";
        public static final String BATTLENET = "<:BattleNet:1572185529687089235>";
        public static final String ITCHIO = "<:Itchio:1572185532002336848>";
        public static final String DRM_FREE = "<:DRMFree:1572185533533388821>";
        public static final String PLAYSTATION = "<:PlayStation:1572185535043215361>";
        public static final String XBOX = "<:Xbox:1572185536851091466>";
        public static final String SWITCH = "<:Switch:1572185538616631337>";
        public static final String ANDROID = "<:Android:1572185540030242857>";
        public static final String IOS = "<:iOS:1572185541514891267>";

        // Progress Bar Components
        public static final String PROGRESS_BAR_LEFT_EMPTY = "<:leftEmpty:1068143435095220265>";
        public static final String PROGRESS_BAR_LEFT_FULL = "<:leftFull:1068143511179894804>";
        public static final String PROGRESS_BAR_RIGHT_EMPTY = "<:rightEmpty:1068143887010517032>";
        public static final String PROGRESS_BAR_RIGHT_FULL = "<:rightFull:1068143806622470244>";
        public static final String PROGRESS_BAR_MIDDLE_EMPTY = "<:middleEmpty:1068143614804377681>";
        public static final String PROGRESS_BAR_MIDDLE_FULL = "<:middleFull:1068143723080319038>";

        // Animated Emojis
        /** Not an actual spinning diamond, until we fix the animation */
        public static final String DIAMOND_SPIN = "💎";
        public static final String BLACK_HEART_SPIN = "<a:blackSpin:1025851052442005594>";
        public static final String WHITE_HEART_SPIN = "<a:whiteSpin:1025851168720687174>";
        public static final String RED_HEART_SPIN = "<a:redSpin:1025851361583173773>";
        public static final String PINK_HEART_SPIN = "<a:pinkSpin:1025851222068052101>";
        public static final String DONUT_SPIN = "<a:donutSpin:1025851417421955204>";
    }

    public static final class ColorCodes {
        private ColorCodes() {}

        public static final Color DEFAULT = Color.decode("#00D4FF");
        public static final Color ERROR_RED = Color.decode("#FF0000");
        public static final Color SUCCESS_GREEN = Color.decode("#33a532");
        public static final Color PASTEL_RED = Color.decode("#FAA0A0");
        public static final Color CYAN = Color.decode("#00FFFF");
        /** Live study-session cards. */
        public static final Color SESSION_CYAN = new Color(5, 217, 232);
    }
}
