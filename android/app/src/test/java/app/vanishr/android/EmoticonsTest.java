package app.vanishr.android;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The faces typed with keys, ":)" and the like, and which words are one: a face counts only as a word of its own. */
public class EmoticonsTest {
    private static String cp(int... points) { return new String(points, 0, points.length); }

    private static String only(String word) {
        List<Emoticons.Found> found = Emoticons.in(word);
        assertEquals(word + " is one face", 1, found.size());
        return found.get(0).emoji();
    }

    @Test public void theFacesPeopleTypeMostAreTheEmojiTheyStandFor() {
        assertEquals(cp(0x1F642), only(":)"));
        assertEquals(cp(0x1F642), only(":-)"));
        assertEquals(cp(0x1F604), only(":D"));
        assertEquals(cp(0x1F604), only(":-D"));
        assertEquals(cp(0x1F641), only(":("));
        assertEquals(cp(0x1F609), only(";)"));
        assertEquals(cp(0x1F61B), only(":P"));
        assertEquals(cp(0x1F62E), only(":O"));
        assertEquals(cp(0x1F618), only(":*"));
        assertEquals(cp(0x2764, 0xFE0F), only("<3"));
        assertEquals(cp(0x1F494), only("</3"));
        assertEquals(cp(0x1F622), only(":'("));
        assertEquals(cp(0x1F615), only(":/"));
        assertEquals(cp(0x1F610), only(":|"));
        assertEquals(cp(0x1F606), only("xD"));
        assertEquals(cp(0x1F60A), only("^_^"));
        assertEquals(cp(0x1F611), only("-_-"));
        assertEquals(cp(0x1F62D), only("T_T"));
    }

    @Test public void aFaceIsTheSameWithOrWithoutANoseAndInTheCaseItIsOftenTyped() {
        assertEquals(only(":)"), only(":-)"));
        assertEquals(only(":)"), only("=)"));
        assertEquals(only(":D"), only(":d"));
        assertEquals(only(":D"), only("=D"));
        assertEquals(only(":P"), only(":p"));
        assertEquals(only(":P"), only(":-p"));
        assertEquals(only(":O"), only(":o"));
        assertEquals(only("xD"), only("XD"));
        assertEquals("A curly apostrophe, which some keyboards type, is the same", only(":'("), only(":\u2019("));
        assertEquals(only(":/"), only(":\\"));
    }

    @Test public void aFaceIsNeverLongerThanItsEmojiSoChangingItCannotPushTheMessagePastItsLimit() {
        for (Map.Entry<String, String> face : Emoticons.faces().entrySet()) {
            assertTrue(face.getKey() + " is at least as long as its emoji", face.getValue().length() <= face.getKey().length());
            assertEquals(face.getKey() + " is one emoji to the rule that draws one emoji large", 1, Emoji.jumbo(face.getValue()));
            assertTrue(face.getKey() + " is short enough to be found", face.getKey().length() <= Emoticons.REACH);
        }
    }

    @Test public void textThatMerelyContainsTheKeysOfAFaceIsLeftAsItIs() {
        for (String text : new String[]{"10:30", "std::cout", "http://example.com", "https://x.y/z", "ok:)", "a:D", "word;)", "x<3", "a=)", "key:/value",
                ":Dog", ":Poland", ":pizza", ":one", "<30", "<3a", "v1:2", "(:)", "(:D)", "[:)]", "\"<3\"", "8)", "B)", "b)", "xd", "Xd", "t_t", "x_y", "-_-x"})
            assertTrue(text + " is not a face", Emoticons.in(text).isEmpty());
    }

    @Test public void aFaceThatIsStretchedIsLeftAsTheOneWhoTypedItMeantIt() {
        for (String text : new String[]{":))", ":)))", ":((", ":(((", "<33", "<333", ":DD", ":DDD", ":**", ":PP", ":OO", ";))", "::)", "))", ":-))", "^_^^"})
            assertTrue(text + " is not a face", Emoticons.in(text).isEmpty());
    }

    @Test public void sentencePunctuationAfterAFaceDoesNotMakeItAnotherWord() {
        List<Emoticons.Found> found = Emoticons.in("thanks :). see");
        assertEquals(1, found.size());
        assertEquals("The punctuation is not part of the face", new Emoticons.Found(7, 9, cp(0x1F642)), found.get(0));
        assertEquals(1, Emoticons.in("great <3!").size());
        assertEquals(1, Emoticons.in("really :D?!").size());
        assertEquals(1, Emoticons.in(":/,").size());
        assertTrue("A face needs to be there for the punctuation to follow", Emoticons.in("...").isEmpty());
        assertTrue(Emoticons.in("?!").isEmpty());
    }

    @Test public void everyFaceInATextIsFoundWhereItIsAndOnlyThose() {
        String text = "ok :) see you :D\nnow ;)  at 10:30 </3\t^_^";
        List<Emoticons.Found> found = Emoticons.in(text);
        assertEquals(5, found.size());
        for (Emoticons.Found face : found) assertEquals(face.emoji(), Emoticons.in(text.substring(face.start(), face.end())).get(0).emoji());
        assertEquals(":)", text.substring(found.get(0).start(), found.get(0).end()));
        assertEquals(":D", text.substring(found.get(1).start(), found.get(1).end()));
        assertEquals(";)", text.substring(found.get(2).start(), found.get(2).end()));
        assertEquals("</3", text.substring(found.get(3).start(), found.get(3).end()));
        assertEquals("^_^", text.substring(found.get(4).start(), found.get(4).end()));
        for (int index = 1; index < found.size(); index++) assertTrue(found.get(index).start() > found.get(index - 1).end());
    }

    @Test public void aNewLineAndTheNoBreakSpaceSeparateWordsLikeASpaceDoes() {
        assertEquals(2, Emoticons.in(":)\n:D").size());
        assertEquals(2, Emoticons.in(":)\u00A0:D").size());
        assertEquals(2, Emoticons.in("\t:)\t:D\t").size());
        assertTrue(Emoticons.white(' ') && Emoticons.white('\n') && Emoticons.white('\u00A0'));
        assertFalse(Emoticons.white(':') || Emoticons.white('a'));
    }

    @Test public void aWordTooLongToHoldAFaceIsNotLookedAtAndAnEmptyTextHasNone() {
        assertTrue(Emoticons.in("").isEmpty());
        assertTrue(Emoticons.in("   \n  ").isEmpty());
        assertNull(Emoticons.at("x".repeat(40), 0, 40));
        assertTrue(Emoticons.in(":)" + "!".repeat(Emoticons.REACH)).isEmpty());
        assertEquals(1, Emoticons.in(":)" + "!".repeat(Emoticons.REACH - 2)).size());
        assertTrue(Emoticons.in("a".repeat(4096)).isEmpty());
        assertNull(Emoticons.at(":", 0, 1));
    }

    @Test public void aFaceIsFoundBetweenEmojiAndLettersOfOtherLanguages() {
        assertEquals(1, Emoticons.in("\u0928\u092E\u0938\u094D\u0924\u0947 :) " + cp(0x1F600)).size());
        assertTrue("An emoji beside a face makes the word something else", Emoticons.in(cp(0x1F600) + ":)").isEmpty());
    }
}
