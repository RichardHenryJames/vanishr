package app.vanishr.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** The two looks of the app: that text can be read in both, that the light one has not changed, and that the theme files say what the palette says. */
public class PaletteTest {
    private record Pair(String name, int foreground, int ground) { }

    // Every text, icon or fill that the app draws on something, by what the code does.
    private static List<Pair> pairs(Palette.Colors c) {
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair("ink on the page", c.ink(), c.canvas()));
        pairs.add(new Pair("ink on a bubble, a card or a sheet", c.ink(), c.surface()));
        pairs.add(new Pair("ink on my bubble", c.ink(), c.tint()));
        pairs.add(new Pair("muted on the page", c.muted(), c.canvas()));
        pairs.add(new Pair("muted on a bubble, a card or a sheet", c.muted(), c.surface()));
        pairs.add(new Pair("the accent on the page", c.accent(), c.canvas()));
        pairs.add(new Pair("the accent on a card or a sheet", c.accent(), c.surface()));
        pairs.add(new Pair("the accent on a tint", c.accent(), c.tint()));
        pairs.add(new Pair("white on the green fill", Palette.WHITE, c.primary()));
        pairs.add(new Pair("blue on the page", c.blue(), c.canvas()));
        pairs.add(new Pair("blue on a card or a sheet", c.blue(), c.surface()));
        pairs.add(new Pair("blue on its tint", c.blue(), c.blueTint()));
        pairs.add(new Pair("blue on my bubble", c.blue(), c.tint()));
        pairs.add(new Pair("an error on the page", c.error(), c.canvas()));
        pairs.add(new Pair("an error on a card or a sheet", c.error(), c.surface()));
        pairs.add(new Pair("an error on its tint", c.error(), c.errorTint()));
        pairs.add(new Pair("warm on a card or a sheet", c.warm(), c.surface()));
        pairs.add(new Pair("white on the grey fill (a muted count, a swipe button)", Palette.WHITE, c.mutedFill()));
        pairs.add(new Pair("white on the blue fill", Palette.WHITE, c.blueFill()));
        pairs.add(new Pair("white on the red fill", Palette.WHITE, c.errorFill()));
        pairs.add(new Pair("white on the warm fill", Palette.WHITE, c.warmFill()));
        pairs.add(new Pair("what is quoted, in my bubble", c.snippet(), c.quoteTint()));
        pairs.add(new Pair("what is quoted, in their bubble", c.snippet(), c.quoteSurface()));
        pairs.add(new Pair("the accent name of a quote in my bubble", c.accent(), c.quoteTint()));
        pairs.add(new Pair("the accent name of a quote in their bubble", c.accent(), c.quoteSurface()));
        pairs.add(new Pair("the blue name of a quote in my bubble", c.blue(), c.quoteTint()));
        pairs.add(new Pair("the blue name of a quote in their bubble", c.blue(), c.quoteSurface()));
        return pairs;
    }

    @Test public void everyTextAndFillOfTheDarkLookReachesAContrastOfFourAndAHalf() {
        for (Pair pair : pairs(Palette.DARK))
            assertTrue(pair.name() + " is " + String.format("%.2f", Palette.contrast(pair.foreground(), pair.ground())) + " to 1 in the dark look",
                Palette.contrast(pair.foreground(), pair.ground()) >= 4.5);
        // The two that are under 4.5 in the light look are not in the dark one.
        assertTrue("muted on my bubble", Palette.contrast(Palette.DARK.muted(), Palette.DARK.tint()) >= 4.5);
        assertTrue("warm on its tint", Palette.contrast(Palette.DARK.warm(), Palette.DARK.warmTint()) >= 4.5);
        // What is not text: the lines and the outline of a field must be seen, without being loud.
        assertTrue(Palette.contrast(Palette.DARK.line(), Palette.DARK.surface()) >= 1.2);
        assertTrue(Palette.contrast(Palette.DARK.outline(), Palette.DARK.surface()) >= 2.0);
        assertTrue(Palette.contrast(Palette.DARK.surface(), Palette.DARK.canvas()) >= 1.1);
    }

    @Test public void theLightLookKeepsTheContrastsItHad() {
        for (Pair pair : pairs(Palette.LIGHT)) {
            double ratio = Palette.contrast(pair.foreground(), pair.ground());
            assertTrue(pair.name() + " is " + String.format("%.2f", ratio) + " to 1 in the light look", ratio >= 4.5);
        }
        // Two were already under 4.5 in the light look before the dark one was added (small grey text on my bubble, the initials of a warm
        // avatar). They are kept as they were, and the palette test below holds every light color to its old value.
        assertEquals("muted on my bubble, as it was", 4.10, Palette.contrast(Palette.LIGHT.muted(), Palette.LIGHT.tint()), 0.01);
        assertEquals("warm on its tint, as it was", 4.37, Palette.contrast(Palette.LIGHT.warm(), Palette.LIGHT.warmTint()), 0.01);
    }

    @Test public void theLightLookIsExactlyWhatItWasBeforeTheDarkOneWasAdded() {
        Palette.Colors c = Palette.LIGHT;
        assertFalse(c.dark());
        assertEquals(0xFF182724, c.ink()); assertEquals(0xFF67756F, c.muted()); assertEquals(0xFFF5F8F6, c.canvas()); assertEquals(0xFFFFFFFF, c.surface());
        assertEquals(0xFFE2E8E4, c.line()); assertEquals(0xFF146C58, c.primary()); assertEquals(0xFFDDF1E9, c.tint());
        assertEquals(0xFF355CBE, c.blue()); assertEquals(0xFFE8EDFA, c.blueTint()); assertEquals(0xFFA63F50, c.error()); assertEquals(0xFFFCEEF0, c.errorTint());
        assertEquals(0xFF896B19, c.warm()); assertEquals(0xFFF7EFD6, c.warmTint());
        assertEquals(0xFFCDE4DA, c.quoteTint()); assertEquals(0xFFEEF3F0, c.quoteSurface()); assertEquals(0xFF40524C, c.snippet());
        assertEquals(0xFFDDE8E2, c.doodles()); assertEquals(0x14146C58, c.ripple());
        // The pairs that are two colors in the dark look are one in the light look.
        assertEquals(c.primary(), c.accent()); assertEquals(c.muted(), c.mutedFill()); assertEquals(c.blue(), c.blueFill());
        assertEquals(c.error(), c.errorFill()); assertEquals(c.warm(), c.warmFill()); assertEquals(c.line(), c.outline());
    }

    @Test public void theDarkLookIsDark() {
        Palette.Colors c = Palette.DARK;
        assertTrue(c.dark());
        assertTrue("Light text on a dark page", Palette.contrast(c.ink(), 0xFF000000) > Palette.contrast(c.ink(), 0xFFFFFFFF));
        assertTrue("A card is raised above the page, which is the darkest thing there is",
            Palette.contrast(c.surface(), 0xFF000000) > Palette.contrast(c.canvas(), 0xFF000000));
        assertTrue(Palette.contrast(c.canvas(), 0xFF000000) < 1.5);
        for (int color : new int[]{c.ink(), c.muted(), c.canvas(), c.surface(), c.line(), c.outline(), c.primary(), c.accent(), c.tint(), c.blue(), c.error(), c.warm()})
            assertEquals("Opaque", 0xFF, color >>> 24);
    }

    @Test public void theLookFollowsTheChoiceAndOnlyWhenNothingIsChosenThePhone() {
        assertFalse(Palette.dark(Palette.Choice.LIGHT, false));
        assertFalse("Light was chosen, so a phone in its dark mode does not matter", Palette.dark(Palette.Choice.LIGHT, true));
        assertTrue("Dark was chosen, so a phone in its light mode does not matter", Palette.dark(Palette.Choice.DARK, false));
        assertTrue(Palette.dark(Palette.Choice.DARK, true));
        assertFalse(Palette.dark(Palette.Choice.SYSTEM, false));
        assertTrue(Palette.dark(Palette.Choice.SYSTEM, true));
    }

    @Test public void aStoredChoiceIsReadBackAndAnythingElseIsTheSystemOne() {
        for (Palette.Choice choice : Palette.Choice.values()) assertEquals(choice, Palette.Choice.of(choice.stored()));
        assertEquals("Nothing stored yet", Palette.Choice.SYSTEM, Palette.Choice.of(null));
        assertEquals(Palette.Choice.SYSTEM, Palette.Choice.of(""));
        assertEquals("A value that a later version might store", Palette.Choice.SYSTEM, Palette.Choice.of("sepia"));
        assertEquals("The stored words are matched exactly", Palette.Choice.SYSTEM, Palette.Choice.of("Dark"));
        assertSame(Palette.LIGHT, Palette.colors(false));
        assertSame(Palette.DARK, Palette.colors(true));
    }

    @Test public void theContrastOfBlackAndWhiteIsTwentyOneAndOfTheSameColorIsOne() {
        assertEquals(21.0, Palette.contrast(0xFF000000, 0xFFFFFFFF), 0.001);
        assertEquals(21.0, Palette.contrast(0xFFFFFFFF, 0xFF000000), 0.001);
        assertEquals(1.0, Palette.contrast(0xFF146C58, 0xFF146C58), 0.0001);
    }

    // The theme files are written by hand, so that what they say is checked against the palette: a color changed in one place and
    // not in the other would show as a dark sheet over a darker page, or white text in a light one.
    @Test public void theThemeFilesSayWhatThePaletteSays() throws Exception {
        String themes = read("src/main/res/values/themes.xml");
        check(themes, "Theme.Vanishr.Light", Palette.LIGHT);
        check(themes, "Theme.Vanishr.Dark", Palette.DARK);
        // What a dialog, a bottom sheet and its navigation bar are filled with.
        checkOverlay(themes, "ThemeOverlay.Vanishr.Dialog", "colorSurfaceContainerHigh", Palette.LIGHT.surface());
        checkOverlay(themes, "ThemeOverlay.Vanishr.Dialog.Dark", "colorSurfaceContainerHigh", Palette.DARK.surface());
        checkOverlay(themes, "ThemeOverlay.Vanishr.BottomSheet", "android:navigationBarColor", Palette.LIGHT.surface());
        checkOverlay(themes, "ThemeOverlay.Vanishr.BottomSheet.Dark", "android:navigationBarColor", Palette.DARK.surface());
        checkOverlay(themes, "Widget.Vanishr.BottomSheet", "backgroundTint", Palette.LIGHT.surface());
        checkOverlay(themes, "Widget.Vanishr.BottomSheet.Dark", "backgroundTint", Palette.DARK.surface());
        assertTrue("The dark look is the one the phone's dark mode starts the app in",
            read("src/main/res/values-night/themes.xml").contains("parent=\"Theme.Vanishr.Dark\""));
        assertTrue(themes.contains("<style name=\"Theme.Vanishr\" parent=\"Theme.Vanishr.Light\" />"));
    }

    private static String read(String file) throws java.io.IOException {
        return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
    }

    private static String styleBody(String themes, String name) {
        Matcher block = Pattern.compile("<style name=\"" + Pattern.quote(name) + "\"[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(themes);
        assertTrue(name + " is defined", block.find());
        return block.group(1);
    }

    private static void checkOverlay(String themes, String name, String attribute, int color) {
        assertEquals(name + " " + attribute, hex(color), item(styleBody(themes, name), attribute));
    }

    private static void check(String themes, String name, Palette.Colors c) {
        String body = styleBody(themes, name);
        assertEquals(name + " colorPrimary", hex(c.accent()), item(body, "colorPrimary"));
        assertEquals(name + " colorPrimaryContainer", hex(c.tint()), item(body, "colorPrimaryContainer"));
        assertEquals(name + " colorSecondary", hex(c.blue()), item(body, "colorSecondary"));
        assertEquals(name + " colorSecondaryContainer", hex(c.blueTint()), item(body, "colorSecondaryContainer"));
        assertEquals(name + " colorSurface", hex(c.surface()), item(body, "colorSurface"));
        assertEquals(name + " colorSurfaceContainer", hex(c.surface()), item(body, "colorSurfaceContainer"));
        assertEquals(name + " colorSurfaceContainerHigh", hex(c.surface()), item(body, "colorSurfaceContainerHigh"));
        assertEquals(name + " colorSurfaceContainerHighest", hex(c.canvas()), item(body, "colorSurfaceContainerHighest"));
        assertEquals(name + " colorOnSurface", hex(c.ink()), item(body, "colorOnSurface"));
        assertEquals(name + " colorOnSurfaceVariant", hex(c.muted()), item(body, "colorOnSurfaceVariant"));
        assertEquals(name + " colorOutline", hex(c.outline()), item(body, "colorOutline"));
        assertEquals(name + " colorError", hex(c.error()), item(body, "colorError"));
        assertEquals(name + " colorBackground", hex(c.canvas()), item(body, "android:colorBackground"));
        assertEquals(name + " windowBackground", hex(c.canvas()), item(body, "android:windowBackground"));
        assertEquals(name + " navigationBarColor", hex(c.canvas()), item(body, "android:navigationBarColor"));
        assertEquals(name + " windowLightStatusBar", c.dark() ? "FALSE" : "TRUE", item(body, "android:windowLightStatusBar"));
        assertEquals(name + " windowLightNavigationBar", c.dark() ? "FALSE" : "TRUE", item(body, "android:windowLightNavigationBar"));
    }

    private static String item(String body, String name) {
        Matcher match = Pattern.compile("<item name=\"" + Pattern.quote(name) + "\">([^<]*)</item>").matcher(body);
        assertTrue(name + " is set", match.find());
        return match.group(1).strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static String hex(int color) { return String.format("#%06X", color & 0xFFFFFF); }
}
