package app.vanishr.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The faces typed with keys that stand for an emoji: ":)" is a smile and "&lt;3" a heart. A face counts only when it is a word of its own,
 * so "10:30", "http://" and "std::cout" stay as they are, and a stretched face (":)))", "&lt;333") is left as the one who typed it meant it.
 * Worked out from the text alone, so that it can be tested without a phone.
 */
final class Emoticons {
    private Emoticons() { }

    /** A face found in a text: where it starts and ends, and the emoji it stands for. An emoji is never longer than its face. */
    record Found(int start, int end, String emoji) { }

    /** The longest word that can hold a face: the face and the sentence punctuation that may follow it. */
    static final int REACH = 12;

    // Marks that may follow a face without making it another word: "see you :)." is a face and a full stop.
    private static final String AFTER = ".,!?";

    private static final Map<String, String> FACES = new HashMap<>();

    private static String emoji(int... cps) { return new String(cps, 0, cps.length); }

    private static void add(String emoji, String... faces) { for (String face : faces) FACES.put(face, emoji); }

    static {
        add(emoji(0x1F642), ":)", ":-)", "=)");
        add(emoji(0x1F604), ":D", ":d", ":-D", ":-d", "=D");
        add(emoji(0x1F606), "xD", "XD");
        add(emoji(0x1F602), ":'D", ":\u2019D");
        add(emoji(0x1F641), ":(", ":-(", "=(");
        add(emoji(0x1F622), ":'(", ":\u2019(", ":'-(");
        add(emoji(0x1F609), ";)", ";-)");
        add(emoji(0x1F61B), ":P", ":p", ":-P", ":-p");
        add(emoji(0x1F61C), ";P", ";p");
        add(emoji(0x1F62E), ":O", ":o", ":-O", ":-o");
        add(emoji(0x1F618), ":*", ":-*");
        add(emoji(0x2764, 0xFE0F), "<3");
        add(emoji(0x1F494), "</3");
        add(emoji(0x1F615), ":/", ":-/", ":\\", ":-\\");
        add(emoji(0x1F610), ":|", ":-|");
        add(emoji(0x1F60A), "^_^");
        add(emoji(0x1F611), "-_-");
        add(emoji(0x1F62D), "T_T");
    }

    /** What separates words, including the no-break space that some keyboards type. */
    static boolean white(char c) { return Character.isWhitespace(c) || c == '\u00A0'; }

    /** The face that is the word {@code start} to {@code end} of {@code text}, with any sentence punctuation after it left out; or null. */
    static Found at(CharSequence text, int start, int end) {
        if (end - start > REACH) return null;
        int stop = end;
        while (stop > start && AFTER.indexOf(text.charAt(stop - 1)) >= 0) stop--;
        if (stop - start < 2) return null;
        String emoji = FACES.get(text.subSequence(start, stop).toString());
        return emoji == null ? null : new Found(start, stop, emoji);
    }

    /** Every face in {@code text}, in order. */
    static List<Found> in(CharSequence text) {
        List<Found> found = new ArrayList<>();
        int index = 0, length = text.length();
        while (index < length) {
            if (white(text.charAt(index))) { index++; continue; }
            int end = index + 1;
            while (end < length && !white(text.charAt(end))) end++;
            Found face = at(text, index, end);
            if (face != null) found.add(face);
            index = end;
        }
        return found;
    }

    /** The faces and the emoji they stand for, for the tests. */
    static Map<String, String> faces() { return java.util.Collections.unmodifiableMap(FACES); }
}
