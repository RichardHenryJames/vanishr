package app.vanishr.android;

import org.junit.Test;

import static org.junit.Assert.*;

/** Where a chat row pulled sideways comes to rest, and how far the finger may take it past the actions. */
public class SwipeActionsRulesTest {
    private static final float LEFT = 152f, RIGHT = 152f, FLING = 1500f;

    private static float target(float offset, float speed) { return SwipeActions.target(offset, speed, LEFT, RIGHT, FLING); }

    @Test public void aShortOrSlowPullFallsBackToRest() {
        assertEquals(0f, target(0f, 0f), 0f);
        assertEquals(0f, target(LEFT / 2, 0f), 0f);
        assertEquals(0f, target(-RIGHT / 2, 0f), 0f);
        assertEquals(0f, target(30f, 800f), 0f);
        assertEquals(0f, target(-30f, -800f), 0f);
    }

    @Test public void aPullPastHalfwayStaysOpenOnThatSide() {
        assertEquals(LEFT, target(LEFT / 2 + 1, 0f), 0f);
        assertEquals(-RIGHT, target(-RIGHT / 2 - 1, 0f), 0f);
        assertEquals(LEFT, target(LEFT, 0f), 0f);
        assertEquals(-RIGHT, target(-RIGHT, 0f), 0f);
    }

    @Test public void aRowThatIsOpenStaysOpenUntilItIsPulledBackPastHalfway() {
        assertEquals(LEFT, target(LEFT - 10f, 0f), 0f);
        assertEquals(LEFT, target(LEFT / 2 + 5f, 0f), 0f);
        assertEquals(0f, target(LEFT / 2 - 5f, 0f), 0f);
        assertEquals(-RIGHT, target(-RIGHT + 10f, 0f), 0f);
        assertEquals(0f, target(-RIGHT / 2 + 5f, 0f), 0f);
    }

    @Test public void aQuickFlickOpensTheSideItPointsAt() {
        assertEquals(LEFT, target(10f, FLING + 1), 0f);
        assertEquals(-RIGHT, target(-10f, -FLING - 1), 0f);
        assertEquals(LEFT, target(0f, FLING + 1), 0f);
        assertEquals(-RIGHT, target(0f, -FLING - 1), 0f);
    }

    @Test public void aQuickFlickTheOtherWayClosesAnOpenRow() {
        assertEquals(0f, target(LEFT, -FLING - 1), 0f);
        assertEquals(0f, target(-RIGHT, FLING + 1), 0f);
        assertEquals(0f, target(5f, -FLING - 1), 0f);
        assertEquals(0f, target(-5f, FLING + 1), 0f);
    }

    @Test public void aSideWithNoActionsNeverOpens() {
        assertEquals(0f, SwipeActions.target(200f, 0f, 0f, RIGHT, FLING), 0f);
        assertEquals(0f, SwipeActions.target(10f, FLING + 1, 0f, RIGHT, FLING), 0f);
        assertEquals(0f, SwipeActions.target(-200f, 0f, LEFT, 0f, FLING), 0f);
        assertEquals(0f, SwipeActions.target(-10f, -FLING - 1, LEFT, 0f, FLING), 0f);
        assertEquals(0f, SwipeActions.target(200f, 0f, 0f, 0f, FLING), 0f);
    }

    @Test public void theFingerMovesTheRowOneToOneAsFarAsTheActionsGo() {
        assertEquals(0f, SwipeActions.resisted(0f, LEFT, RIGHT, 24f), 0f);
        assertEquals(100f, SwipeActions.resisted(100f, LEFT, RIGHT, 24f), 0f);
        assertEquals(-100f, SwipeActions.resisted(-100f, LEFT, RIGHT, 24f), 0f);
        assertEquals(LEFT, SwipeActions.resisted(LEFT, LEFT, RIGHT, 24f), 0f);
        assertEquals(-RIGHT, SwipeActions.resisted(-RIGHT, LEFT, RIGHT, 24f), 0f);
    }

    @Test public void pastTheActionsTheRowGivesWayAndNeverGoesFartherThanTheSlack() {
        assertEquals(LEFT + 10f, SwipeActions.resisted(LEFT + 100f / 3f * 1f, LEFT, RIGHT, 24f), 0.01f);
        assertEquals(LEFT + 24f, SwipeActions.resisted(LEFT + 1000f, LEFT, RIGHT, 24f), 0f);
        assertEquals(-RIGHT - 10f, SwipeActions.resisted(-RIGHT - 100f / 3f * 1f, LEFT, RIGHT, 24f), 0.01f);
        assertEquals(-RIGHT - 24f, SwipeActions.resisted(-RIGHT - 1000f, LEFT, RIGHT, 24f), 0f);
    }
}
