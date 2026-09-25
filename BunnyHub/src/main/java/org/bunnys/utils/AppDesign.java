package org.bunnys.utils;

import java.awt.Color;

@SuppressWarnings("unused")
public class AppDesign {

    public static class Emojis {
        public static final String VERIFY = "<:verified:821419611438317638>";
        public static final String ERROR = "<:error:822091680605011978>";
        public static final String SPOTIFY = "<:Spotify:962905037649096815>";
        public static final String GBF_LOGO = "<:LogoTransparent:838994085527945266>";
        public static final String STOP = "<:Stop:1518033663047241929>";

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
        public static final String CROWN_ANIMATED = "<a:Crown:1335252412394377250>";
        public static final String BLACK_HEART_SPIN = "<a:blackSpin:1025851052442005594>";
        public static final String WHITE_HEART_SPIN = "<a:whiteSpin:1025851168720687174>";
        public static final String RED_HEART_SPIN = "<a:redSpin:1025851361583173773>";
        public static final String PINK_HEART_SPIN = "<a:pinkSpin:1025851222068052101>";
        public static final String DONUT_SPIN = "<a:donutSpin:1025851417421955204>";
    }

    public static class ColorCodes {
        public static final Color DEFAULT = Color.decode("#00D4FF");
        public static final Color ERROR_RED = Color.decode("#FF0000");
        public static final Color SUCCESS_GREEN = Color.decode("#33a532");
        public static final Color SALMON_PINK = Color.decode("#ff91a4");
        public static final Color CARDINAL_RED = Color.decode("#C41E3A");
        public static final Color CHERRY = Color.decode("#D2042D");
        public static final Color PASTEL_RED = Color.decode("#FAA0A0");
        public static final Color CYAN = Color.decode("#00FFFF");
    }
}