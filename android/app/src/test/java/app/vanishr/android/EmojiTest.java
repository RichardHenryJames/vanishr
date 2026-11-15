package app.vanishr.android;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Which messages are emoji and nothing else, so that they are drawn large and bare, and what the panel offers. */
public class EmojiTest {
    private static String cp(int... points) { return new String(points, 0, points.length); }

    @Test public void oneToThreeEmojiAreCountedAndFourOrMoreAreNot() {
        assertEquals(1, Emoji.jumbo(cp(0x1F600)));
        assertEquals(2, Emoji.jumbo(cp(0x1F600, 0x1F602)));
        assertEquals(3, Emoji.jumbo(cp(0x1F600, 0x1F602, 0x1F44D)));
        assertEquals("More than three is a message like any other", 0, Emoji.jumbo(cp(0x1F600, 0x1F602, 0x1F44D, 0x1F525)));
    }

    @Test public void spacesBetweenEmojiAreAllowedButAnyOtherTextMakesItAnOrdinaryMessage() {
        assertEquals(3, Emoji.jumbo(cp(0x1F600) + " " + cp(0x1F602) + "  " + cp(0x1F44D) + " "));
        assertEquals(0, Emoji.jumbo("ok " + cp(0x1F600)));
        assertEquals(0, Emoji.jumbo(cp(0x1F600) + "!"));
        assertEquals(0, Emoji.jumbo("hello"));
        assertEquals(0, Emoji.jumbo(""));
        assertEquals(0, Emoji.jumbo("   "));
        assertEquals(0, Emoji.jumbo(null));
    }

    @Test public void aSkinToneAFlagAKeycapAndAJoinedFamilyEachCountAsOne() {
        assertEquals(1, Emoji.jumbo(cp(0x1F44D, 0x1F3FD)));
        assertEquals(1, Emoji.jumbo(cp(0x1F1EE, 0x1F1F3)));
        assertEquals(1, Emoji.jumbo("1\uFE0F\u20E3"));
        assertEquals(1, Emoji.jumbo(cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)));
        assertEquals(1, Emoji.jumbo(cp(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F)));
        assertEquals(3, Emoji.jumbo(cp(0x1F44D, 0x1F3FD) + cp(0x1F1EE, 0x1F1F3) + cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)));
    }

    @Test public void aLoneRegionLetterADigitOrATextSymbolIsNotAnEmoji() {
        assertEquals("One half of a flag is a letter", 0, Emoji.jumbo(cp(0x1F1EE)));
        assertEquals(0, Emoji.jumbo("1"));
        assertEquals(0, Emoji.jumbo("123"));
        assertEquals(0, Emoji.jumbo("#"));
        assertEquals("A heart without the variation selector is a text symbol", 0, Emoji.jumbo("\u2764"));
        assertEquals(0, Emoji.jumbo("\u00A9"));
        assertEquals(0, Emoji.jumbo("\u2605"));
        assertEquals(0, Emoji.jumbo("\u2713"));
    }

    @Test public void aSymbolThatTheVariationSelectorMakesAnEmojiOrThatIsOneByDefaultIsCounted() {
        assertEquals(1, Emoji.jumbo("\u2764\uFE0F"));
        assertEquals(1, Emoji.jumbo("\u2714\uFE0F"));
        assertEquals(1, Emoji.jumbo("\u00A9\uFE0F"));
        assertEquals(1, Emoji.jumbo("\u2705"));
        assertEquals(1, Emoji.jumbo("\u26A1"));
        assertEquals(1, Emoji.jumbo("\u2728"));
        assertEquals(1, Emoji.jumbo("\u2B50"));
        assertEquals(2, Emoji.jumbo("\u2764\uFE0F\uD83D\uDD25"));
    }

    @Test public void aMessageThatIsTooLongIsNotScannedAndIsNeverLarge() {
        assertEquals(0, Emoji.jumbo(cp(0x1F600).repeat(100)));
        assertEquals(0, Emoji.jumbo((cp(0x1F600) + " ").repeat(41)));
    }

    @Test public void theSizeGrowsAsThereAreFewerEmoji() {
        assertTrue(Emoji.jumboSize(1) > Emoji.jumboSize(2) && Emoji.jumboSize(2) > Emoji.jumboSize(3) && Emoji.jumboSize(3) > 13);
        assertEquals(0, Emoji.jumboSize(0));
        assertEquals(0, Emoji.jumboSize(Emoji.JUMBO_MAX + 1));
    }

    @Test public void everyTabOfThePanelHasThingsToOfferAndNoneOfThemIsEmpty() {
        assertEquals(9, Emoji.CATEGORIES.size());
        Set<String> names = new HashSet<>();
        for (Emoji.Category category : Emoji.CATEGORIES) {
            assertTrue(category.name(), names.add(category.name()));
            assertFalse(category.icon().isEmpty());
            List<String> all = category.all();
            assertTrue(category.name() + " has " + all.size(), all.size() >= 20);
            assertEquals("Nothing is offered twice within a tab", all.size(), new HashSet<>(all).size());
        }
    }

    @Test public void theFlagsTabHasRealFlagsMadeOfTwoRegionLetters() {
        List<String> flags = Emoji.CATEGORIES.get(Emoji.CATEGORIES.size() - 1).all();
        long pairs = flags.stream().filter(each -> each.codePointCount(0, each.length()) == 2 && Character.codePointAt(each, 0) >= 0x1F1E6 && Character.codePointAt(each, 0) <= 0x1F1FF).count();
        assertTrue(pairs >= 40);
        assertTrue(flags.contains(cp(0x1F1EE, 0x1F1F3)));
        for (String each : flags) assertEquals("Every flag is one emoji: " + each, 1, Emoji.jumbo(each));
    }

    @Test public void theFavouritesAreEmojiAndTheHeartHasItsSelector() {
        assertTrue(Emoji.FAVOURITES.size() >= 12);
        for (String each : Emoji.FAVOURITES) assertEquals(each, 1, Emoji.jumbo(each));
        assertTrue(Emoji.FAVOURITES.contains("\u2764\uFE0F"));
    }

    @Test public void thePanelWaitsForTheKeyboardToGoAndComesUpAnywayWhenItNeverSaysItHas() {
        assertTrue("With no keyboard there is nothing to wait for", Emoji.panelMayShow(false, 0));
        assertFalse("The keyboard is still there", Emoji.panelMayShow(true, 0));
        assertFalse("and a loaded phone that is slow to take it away is given time", Emoji.panelMayShow(true, 450));
        assertFalse(Emoji.panelMayShow(true, Emoji.PANEL_WAIT_MILLIS - 1));
        assertTrue("A keyboard that never says it has gone does not hold the panel back for good", Emoji.panelMayShow(true, Emoji.PANEL_WAIT_MILLIS));
        assertTrue(Emoji.panelMayShow(false, Emoji.PANEL_WAIT_MILLIS));
        assertTrue("The wait is longer than the 450 ms that a loaded emulator overran", Emoji.PANEL_WAIT_MILLIS >= 1_000);
    }
}
