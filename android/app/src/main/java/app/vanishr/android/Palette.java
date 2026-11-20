package app.vanishr.android;

/**
 * The colors of the app in its light and its dark look, and the choice between them. It holds numbers only (no Android classes), so the
 * colors can be checked on the JVM: {@code PaletteTest} holds every text and icon color to a contrast of at least 4.5 to 1 against what
 * it is drawn on, and 3 to 1 for the lines and the fills that carry white.
 *
 * <p>Two things that were one color in the light look are two here, because a color that reads on a dark page is too light to carry white
 * text: {@code accent} (text and icons on the page, the bubbles and the sheets) and {@code primary} (a fill with white on it), and the
 * same for the fills of the swipe buttons and the unread badge ({@code *Fill}). In the light look each pair is one color, as before.
 */
final class Palette {
    private Palette() { }

    /** What the person chose in the profile. Until they choose, the app follows the phone. */
    enum Choice {
        SYSTEM("system"), LIGHT("light"), DARK("dark");

        private final String stored;

        Choice(String stored) { this.stored = stored; }

        String stored() { return stored; }

        /** What was stored, or {@link #SYSTEM} for anything else (nothing stored, or a value this version does not know). */
        static Choice of(String stored) {
            for (Choice choice : values()) if (choice.stored.equals(stored)) return choice;
            return SYSTEM;
        }
    }

    record Colors(boolean dark,
                  int ink, int muted, int canvas, int surface, int line, int outline, int ripple,
                  int primary, int accent, int tint,
                  int blue, int blueTint, int error, int errorTint, int warm, int warmTint,
                  int mutedFill, int blueFill, int errorFill, int warmFill,
                  int doodles, int quoteTint, int quoteSurface, int snippet) { }

    static final Colors LIGHT = new Colors(false,
        rgb(24, 39, 36), rgb(103, 117, 111), rgb(245, 248, 246), rgb(255, 255, 255), rgb(226, 232, 228), rgb(226, 232, 228), 0x14146C58,
        rgb(20, 108, 88), rgb(20, 108, 88), rgb(221, 241, 233),
        rgb(53, 92, 190), rgb(232, 237, 250), rgb(166, 63, 80), rgb(252, 238, 240), rgb(137, 107, 25), rgb(247, 239, 214),
        rgb(103, 117, 111), rgb(53, 92, 190), rgb(166, 63, 80), rgb(137, 107, 25),
        rgb(221, 232, 226), rgb(205, 228, 218), rgb(238, 243, 240), rgb(64, 82, 76));

    static final Colors DARK = new Colors(true,
        rgb(230, 239, 235), rgb(163, 179, 172), rgb(14, 21, 19), rgb(24, 34, 31), rgb(43, 58, 53), rgb(70, 86, 79), 0x1FFFFFFF,
        rgb(27, 122, 99), rgb(79, 195, 161), rgb(26, 66, 53),
        rgb(146, 170, 242), rgb(31, 43, 77), rgb(240, 140, 155), rgb(60, 30, 38), rgb(221, 185, 90), rgb(58, 49, 20),
        rgb(94, 110, 103), rgb(53, 92, 190), rgb(166, 63, 80), rgb(137, 107, 25),
        rgb(27, 40, 36), rgb(18, 48, 39), rgb(17, 26, 23), rgb(190, 204, 198));

    static final int WHITE = 0xFFFFFFFF;

    static Colors colors(boolean dark) { return dark ? DARK : LIGHT; }

    /** Whether the dark look is the one in use: when it was chosen, or when nothing was chosen and the phone is in its dark mode. */
    static boolean dark(Choice choice, boolean phoneIsDark) {
        return choice == Choice.DARK || (choice == Choice.SYSTEM && phoneIsDark);
    }

    /** The contrast ratio of two opaque colors, 1 to 21, as the web accessibility guidelines work it out. */
    static double contrast(int first, int second) {
        double one = luminance(first), two = luminance(second);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 0xFF) + 0.7152 * linear((color >> 8) & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static int rgb(int red, int green, int blue) { return 0xFF000000 | (red << 16) | (green << 8) | blue; }
}
