package app.vanishr.android;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Where the tiles of an album go, and the "+N" on the fourth, as other messengers show them. */
public class AlbumLayoutTest {
    private static final int WIDTH = 520, GAP = 4;

    private static boolean overlap(AlbumLayout.Tile a, AlbumLayout.Tile b) {
        return a.left() < b.left() + b.width() && b.left() < a.left() + a.width() && a.top() < b.top() + b.height() && b.top() < a.top() + a.height();
    }

    private static void assertInside(AlbumLayout.Plan plan) {
        for (AlbumLayout.Tile tile : plan.tiles()) {
            assertTrue(tile.toString(), tile.left() >= 0 && tile.top() >= 0 && tile.width() > 0 && tile.height() > 0);
            assertTrue(tile.toString(), tile.left() + tile.width() <= plan.width() && tile.top() + tile.height() <= plan.height());
        }
        for (int a = 0; a < plan.tiles().size(); a++)
            for (int b = a + 1; b < plan.tiles().size(); b++) assertFalse("Tiles " + a + " and " + b + " overlap", overlap(plan.tiles().get(a), plan.tiles().get(b)));
    }

    @Test public void twoPhotosStandSideBySideWithAGapBetweenThem() {
        AlbumLayout.Plan plan = AlbumLayout.plan(2, WIDTH, GAP);
        assertEquals(2, plan.tiles().size());
        AlbumLayout.Tile left = plan.tiles().get(0), right = plan.tiles().get(1);
        assertEquals(left.width(), right.width());
        assertEquals(0, left.left());
        assertEquals(WIDTH, right.left() + right.width());
        assertEquals(GAP, right.left() - (left.left() + left.width()));
        assertEquals(left.top(), right.top());
        assertEquals(left.height(), plan.height());
        assertInside(plan);
    }

    @Test public void threePhotosAreOneTallTileBesideTwoSmallOnes() {
        AlbumLayout.Plan plan = AlbumLayout.plan(3, WIDTH, GAP);
        assertEquals(3, plan.tiles().size());
        AlbumLayout.Tile tall = plan.tiles().get(0), top = plan.tiles().get(1), bottom = plan.tiles().get(2);
        assertEquals("The tall tile fills the album's height", plan.height(), tall.height());
        assertEquals("and the two small ones fill the other column", plan.height(), bottom.top() + bottom.height());
        assertEquals(top.left(), bottom.left());
        assertEquals(GAP, bottom.top() - (top.top() + top.height()));
        assertEquals("A square album", WIDTH, plan.height());
        assertInside(plan);
    }

    @Test public void fourPhotosFillASquareOfFourAndNoneSaysHowManyAreBehindIt() {
        AlbumLayout.Plan plan = AlbumLayout.plan(4, WIDTH, GAP);
        assertEquals(4, plan.tiles().size());
        assertEquals(WIDTH, plan.height());
        for (AlbumLayout.Tile tile : plan.tiles()) { assertEquals(plan.tiles().get(0).width(), tile.width()); assertEquals(tile.width(), tile.height()); assertEquals(0, tile.more()); }
        assertInside(plan);
    }

    @Test public void moreThanFourPhotosShowFourAndTheFourthSaysHowManyAreBehindIt() {
        // Six photos: the fourth tile says +3, which is the fourth, the fifth and the sixth.
        for (int[] pair : new int[][]{{5, 2}, {6, 3}, {7, 4}, {10, 7}, {30, 27}}) {
            AlbumLayout.Plan plan = AlbumLayout.plan(pair[0], WIDTH, GAP);
            assertEquals("Only four tiles are drawn for " + pair[0], 4, plan.tiles().size());
            for (int index = 0; index < 3; index++) assertEquals(0, plan.tiles().get(index).more());
            assertEquals("+N for " + pair[0] + " photos", pair[1], plan.tiles().get(3).more());
            assertInside(plan);
        }
    }

    @Test public void aLoneAlbumPhotoIsDrawnAsAPhotoOfItsOwn() {
        AlbumLayout.Plan plan = AlbumLayout.plan(1, WIDTH, GAP);
        assertEquals(1, plan.tiles().size());
        assertEquals(WIDTH, plan.tiles().get(0).width());
        assertEquals(0, plan.tiles().get(0).more());
    }

    @Test public void aSinglePhotoKeepsItsProportionsWithinLimitsAndIsThreeQuartersHighWhenItsSizeIsNotKnown() {
        assertEquals("Landscape 4:3", 390, AlbumLayout.singleHeight(520, 1600, 1200));
        assertEquals("Not known", 390, AlbumLayout.singleHeight(520, 0, 0));
        assertEquals("A very wide one is held to the flattest", Math.round(520 * AlbumLayout.FLATTEST), AlbumLayout.singleHeight(520, 4000, 400));
        assertEquals("A very tall one is held to the tallest", Math.round(520 * AlbumLayout.TALLEST), AlbumLayout.singleHeight(520, 400, 4000));
        assertEquals("Square", 520, AlbumLayout.singleHeight(520, 800, 800));
    }

    @Test public void everyCountFromTwoToThirtyFitsInsideItsSquareWithoutOverlap() {
        for (int count = 2; count <= 30; count++) assertInside(AlbumLayout.plan(count, WIDTH, GAP));
        for (int width = 200; width <= 700; width += 37) for (int count = 2; count <= 8; count++) assertInside(AlbumLayout.plan(count, width, 6));
    }
}
