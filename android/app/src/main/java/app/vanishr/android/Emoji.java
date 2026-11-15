package app.vanishr.android;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which text is emoji, and which emoji the panel offers. Worked out from the text alone, so that it can be tested without a phone. A
 * message that is one to three emoji and nothing else is drawn large and without a bubble, as other messengers do; a letter, a digit
 * or a symbol that is only text never counts, and neither does a bare symbol that needs a variation selector to be an emoji.
 */
final class Emoji {
    private Emoji() { }

    /** The most emoji a message may have for it to be drawn large. */
    static final int JUMBO_MAX = 3;

    /** The size in sp of a message that is {@code count} emoji and nothing else: the fewer, the bigger. 0 for any other count. */
    static int jumboSize(int count) { return switch (count) { case 1 -> 48; case 2 -> 40; case 3 -> 34; default -> 0; }; }

    /** How long the panel waits for the keyboard it replaces to say that it has gone, before it comes up anyway. */
    static final long PANEL_WAIT_MILLIS = 1_500;

    /** Whether the panel may come up: when the keyboard has gone, or when it has been waited for as long as it is worth. */
    static boolean panelMayShow(boolean keyboardShown, long waitedMillis) { return !keyboardShown || waitedMillis >= PANEL_WAIT_MILLIS; }

    private static final int VARIATION = 0xFE0F, JOINER = 0x200D, KEYCAP = 0x20E3;

    // Symbols outside the pictograph planes that are drawn as emoji by default (Emoji_Presentation); the others of those blocks are
    // text unless a variation selector follows.
    private static final int[] DEFAULT_BMP = {0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE,
        0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B,
        0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55};
    // Symbols that are emoji when a variation selector follows them.
    private static final int[] TEXT_BMP = {0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B,
        0x2328, 0x2328, 0x23CF, 0x23CF, 0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
        0x25FB, 0x25FE, 0x2600, 0x27BF, 0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030,
        0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299};

    private static boolean within(int[] pairs, int cp) {
        for (int index = 0; index < pairs.length; index += 2) if (cp >= pairs[index] && cp <= pairs[index + 1]) return true;
        return false;
    }

    private static boolean pictograph(int cp) { return cp >= 0x1F000 && cp <= 0x1FAFF; }
    private static boolean regional(int cp) { return cp >= 0x1F1E6 && cp <= 0x1F1FF; }
    private static boolean modifier(int cp) { return cp >= 0x1F3FB && cp <= 0x1F3FF; }
    private static boolean tag(int cp) { return cp >= 0xE0020 && cp <= 0xE007F; }

    /** Drawn as an emoji whatever follows it. */
    private static boolean plain(int cp) { return (pictograph(cp) && !regional(cp)) || within(DEFAULT_BMP, cp); }

    /** The first code point of an emoji at {@code index}: an emoji on its own, or a symbol that a variation selector turns into one. */
    private static boolean opens(int[] cps, int index) {
        int cp = cps[index];
        if (plain(cp)) return true;
        return within(TEXT_BMP, cp) && index + 1 < cps.length && cps[index + 1] == VARIATION;
    }

    // One element of a sequence: the emoji and what modifies it (variation selector, skin tone, tags).
    private static int element(int[] cps, int index) {
        int next = index + 1;
        while (next < cps.length && (cps[next] == VARIATION || modifier(cps[next]) || tag(cps[next]))) next++;
        return next;
    }

    /** The index after the emoji that starts at {@code start}, or {@code start} if no emoji does. */
    private static int cluster(int[] cps, int start) {
        int cp = cps[start];
        if (regional(cp)) return start + 1 < cps.length && regional(cps[start + 1]) ? start + 2 : start;
        if ((cp >= '0' && cp <= '9') || cp == '#' || cp == '*') {
            int next = start + 1;
            if (next < cps.length && cps[next] == VARIATION) next++;
            return next < cps.length && cps[next] == KEYCAP ? next + 1 : start;
        }
        if (!opens(cps, start)) return start;
        int end = element(cps, start);
        while (end + 1 < cps.length && cps[end] == JOINER && (plain(cps[end + 1]) || within(TEXT_BMP, cps[end + 1]))) end = element(cps, end + 1);
        return end;
    }

    /**
     * How many emoji {@code text} is, from one to {@link #JUMBO_MAX}, when it is those and nothing else but the spaces between them;
     * otherwise 0. A flag, a skin tone, a keycap and a sequence joined into one picture (a family) each count as one.
     */
    static int jumbo(String text) {
        if (text == null || text.isEmpty() || text.length() > 80) return 0;
        int[] cps = text.codePoints().toArray();
        int count = 0, index = 0;
        while (index < cps.length) {
            if (Character.isWhitespace(cps[index])) { index++; continue; }
            int end = cluster(cps, index);
            if (end == index) return 0;
            if (++count > JUMBO_MAX) return 0;
            index = end;
        }
        return count;
    }

    // ---- What the panel offers ----

    /** A tab of the panel: the emoji that stands for it, its name, the ranges of code points that are in it, and emoji that are single sequences. */
    record Category(String icon, String name, int[] ranges, String[] extra) {
        List<String> all() {
            Set<String> found = new LinkedHashSet<>();
            for (int index = 0; index < ranges.length; index += 2)
                for (int cp = ranges[index]; cp <= ranges[index + 1]; cp++) found.add(new String(Character.toChars(cp)));
            for (String each : extra) found.add(each);
            return new ArrayList<>(found);
        }
    }

    private static String s(int cp) { return new String(Character.toChars(cp)); }
    private static String v(int cp) { return new String(Character.toChars(cp)) + "\uFE0F"; }
    private static String flag(String country) {
        return s(0x1F1E6 + country.charAt(0) - 'A') + s(0x1F1E6 + country.charAt(1) - 'A');
    }

    private static String[] flags() {
        String[] countries = {"IN", "US", "GB", "CA", "AU", "DE", "FR", "IT", "ES", "PT", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "IE", "PL",
            "UA", "RU", "TR", "GR", "IL", "SA", "AE", "QA", "EG", "ZA", "NG", "KE", "PK", "BD", "LK", "NP", "CN", "JP", "KR", "TH", "VN", "ID",
            "MY", "SG", "PH", "NZ", "BR", "AR", "MX", "CO", "CL", "PE"};
        List<String> found = new ArrayList<>(List.of(s(0x1F3C1), s(0x1F6A9), s(0x1F38C), s(0x1F3F4), v(0x1F3F3)));
        for (String country : countries) found.add(flag(country));
        return found.toArray(new String[0]);
    }

    static final List<Category> CATEGORIES = List.of(
        new Category(s(0x1F600), "Smileys", new int[]{0x1F600, 0x1F644, 0x1F648, 0x1F64A, 0x1F910, 0x1F917, 0x1F920, 0x1F925, 0x1F927, 0x1F92F,
            0x1F970, 0x1F97A, 0x1F9D0, 0x1F9D0, 0x1F47B, 0x1F47B, 0x1F47D, 0x1F47F, 0x1F916, 0x1F916, 0x1F4A9, 0x1F4A9},
            new String[]{v(0x263A), v(0x2639)}),
        new Category(s(0x1F44B), "People", new int[]{0x1F446, 0x1F450, 0x1F64C, 0x1F64C, 0x1F64F, 0x1F64F, 0x1F645, 0x1F647, 0x1F64B, 0x1F64E,
            0x1F918, 0x1F91F, 0x1F926, 0x1F926, 0x1F930, 0x1F939, 0x1F4AA, 0x1F4AA, 0x1F595, 0x1F596, 0x1F440, 0x1F445, 0x1F466, 0x1F478,
            0x1F481, 0x1F487, 0x1F9B0, 0x1F9B9, 0x1F9D1, 0x1F9DF}, new String[]{v(0x270C), v(0x261D), s(0x270A), s(0x270B), v(0x270D), v(0x1F590)}),
        new Category(s(0x1F436), "Animals and nature", new int[]{0x1F400, 0x1F43F, 0x1F980, 0x1F9AE, 0x1F330, 0x1F344, 0x1F490, 0x1F490,
            0x1F311, 0x1F31F, 0x1F320, 0x1F320, 0x1F308, 0x1F308, 0x1F30A, 0x1F30B}, new String[]{v(0x2600), v(0x2601), s(0x26A1), v(0x2744), s(0x2B50), v(0x1F54A)}),
        new Category(s(0x1F354), "Food and drink", new int[]{0x1F345, 0x1F37F, 0x1F950, 0x1F96F, 0x1F9C0, 0x1F9CB}, new String[]{s(0x2615)}),
        new Category(s(0x26BD), "Activities", new int[]{0x1F383, 0x1F3CA, 0x1F3D0, 0x1F3D3, 0x1F93A, 0x1F94F, 0x1F3AE, 0x1F3AF}, new String[]{s(0x26BD), s(0x26BE), s(0x26F3)}),
        new Category(s(0x1F697), "Travel and places", new int[]{0x1F680, 0x1F6C5, 0x1F30D, 0x1F310, 0x1F3E0, 0x1F3F0, 0x1F5FA, 0x1F5FF, 0x1F3D4, 0x1F3DF},
            new String[]{s(0x26F2), s(0x26FA), s(0x26EA), s(0x26F5), v(0x2708)}),
        new Category(s(0x1F4A1), "Objects", new int[]{0x1F4A1, 0x1F4FD, 0x1F4FF, 0x1F4FF, 0x1F451, 0x1F465, 0x1F526, 0x1F52E, 0x1F9E0, 0x1F9FF, 0x1F489, 0x1F48A,
            0x1F50B, 0x1F525, 0x1F3A7, 0x1F3A7}, new String[]{s(0x231A), s(0x231B), s(0x23F0), s(0x23F3), v(0x2709), v(0x270F)}),
        new Category(v(0x2764), "Symbols", new int[]{0x1F493, 0x1F49F, 0x1F90D, 0x1F90E, 0x1F5A4, 0x1F5A4, 0x1F4AF, 0x1F4AF, 0x1F4A2, 0x1F4A5, 0x1F4A4, 0x1F4A4,
            0x1F525, 0x1F525, 0x1F51E, 0x1F51E, 0x1F500, 0x1F50A, 0x1F514, 0x1F514, 0x1F7E0, 0x1F7EB, 0x1F534, 0x1F53D, 0x2648, 0x2653},
            new String[]{v(0x2764), v(0x2763), s(0x2705), s(0x274C), s(0x2728), s(0x2757), s(0x2753), s(0x2755), s(0x2754), v(0x26A0), v(0x267B), s(0x2B55),
                s(0x2795), s(0x2796), s(0x2797), v(0x2714), v(0x2716), v(0x1F3F3)}),
        new Category(s(0x1F3C1), "Flags", new int[0], flags()));

    /** The first thing in the panel before anything has been used: what people send most. */
    static final List<String> FAVOURITES = List.of(s(0x1F602), v(0x2764), s(0x1F44D), s(0x1F64F), s(0x1F62D), s(0x1F618), s(0x1F970), s(0x1F60A), s(0x1F525),
        s(0x1F389), s(0x1F60D), s(0x1F923), s(0x1F601), s(0x1F44F), s(0x1F4AF), s(0x1F60E), s(0x1F914), s(0x2728), s(0x1F4AA), s(0x1F64C));

    /** The tab that shows what was used lately (kept in memory only), and its icon. */
    static final String RECENT_ICON = new String(Character.toChars(0x1F558)), RECENT_NAME = "Recent";
}
