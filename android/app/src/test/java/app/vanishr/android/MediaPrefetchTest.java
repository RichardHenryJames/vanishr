package app.vanishr.android;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Pictures fetched early, a few at a time, in the order of the messages; and that an early fetch never decides anything. */
public class MediaPrefetchTest {
    private static byte[] picture(int index) { return new byte[]{(byte) index, 1, 2, 3}; }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    @Test public void picturesAreFetchedAFewAtATimeNeverMoreThanTheLimitAndHandedBackInOrder() throws Exception {
        AtomicInteger running = new AtomicInteger(), most = new AtomicInteger();
        List<UUID> ids = new ArrayList<>();
        try (MediaPrefetch media = new MediaPrefetch()) {
            for (int index = 0; index < 9; index++) {
                UUID id = UUID.randomUUID(); ids.add(id);
                int number = index;
                media.expect(id, () -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try { Thread.sleep(40); } finally { running.decrementAndGet(); }
                    return picture(number);
                });
            }
            media.start();
            for (int index = 0; index < ids.size(); index++) {
                int number = index;
                assertArrayEquals(picture(index), media.take(ids.get(index), () -> { fail("Fetched the ordinary way: " + number); return null; }));
            }
        }
        assertTrue("Several were fetched at the same time: " + most.get(), most.get() >= 2);
        assertTrue("But never more than the limit: " + most.get(), most.get() <= MediaPrefetch.PARALLEL);
    }

    @Test public void nothingIsFetchedBeforeStartAndTheWindowMovesAsPicturesAreTaken() throws Exception {
        AtomicInteger started = new AtomicInteger();
        List<UUID> ids = new ArrayList<>();
        try (MediaPrefetch media = new MediaPrefetch()) {
            for (int index = 0; index < 6; index++) {
                UUID id = UUID.randomUUID(); ids.add(id);
                int number = index;
                media.expect(id, () -> { started.incrementAndGet(); return picture(number); });
            }
            Thread.sleep(50);
            assertEquals("expect() starts nothing", 0, started.get());
            media.start();
            waitFor(() -> started.get() == MediaPrefetch.PARALLEL, "the first pictures");
            Thread.sleep(50);
            assertEquals("Only as many as the limit are fetched until one is taken", MediaPrefetch.PARALLEL, started.get());
            media.take(ids.get(0), () -> null);
            waitFor(() -> started.get() == MediaPrefetch.PARALLEL + 1, "the next picture once one was taken");
            media.take(ids.get(1), () -> null); media.take(ids.get(2), () -> null);
            waitFor(() -> started.get() == 6, "the rest");
        }
    }

    @Test public void aPictureThatWasNeverExpectedIsFetchedTheOrdinaryWay() throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        try (MediaPrefetch media = new MediaPrefetch()) {
            media.start();
            assertArrayEquals(picture(7), media.take(UUID.randomUUID(), () -> { ordinary.incrementAndGet(); return picture(7); }));
        }
        assertEquals(1, ordinary.get());
    }

    @Test public void aFailedEarlyFetchIsDoneAgainTheOrdinaryWayAndThatDecidesWhatHappens() throws Exception {
        UUID worked = UUID.randomUUID(), refused = UUID.randomUUID();
        try (MediaPrefetch media = new MediaPrefetch()) {
            media.expect(worked, () -> { throw new IOException("early failure"); });
            media.expect(refused, () -> { throw new IOException("early failure"); });
            media.start();
            assertArrayEquals("The ordinary fetch succeeds, so the picture is there", picture(1), media.take(worked, () -> picture(1)));
            IOException thrown = assertThrows("The ordinary fetch fails, so that failure is what happens",
                IOException.class, () -> media.take(refused, () -> { throw new IOException("ordinary failure"); }));
            assertEquals("ordinary failure", thrown.getMessage());
        }
    }

    @Test public void aPictureIsHandedOverOnceAndAnotherAskIsFetchedTheOrdinaryWay() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicInteger early = new AtomicInteger(), ordinary = new AtomicInteger();
        try (MediaPrefetch media = new MediaPrefetch()) {
            media.expect(id, () -> { early.incrementAndGet(); return picture(1); });
            media.start();
            assertArrayEquals(picture(1), media.take(id, () -> { ordinary.incrementAndGet(); return picture(9); }));
            assertArrayEquals(picture(9), media.take(id, () -> { ordinary.incrementAndGet(); return picture(9); }));
        }
        assertEquals(1, early.get()); assertEquals(1, ordinary.get());
    }

    @Test public void aPictureExpectedTwiceIsFetchedOnce() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicInteger fetched = new AtomicInteger();
        try (MediaPrefetch media = new MediaPrefetch()) {
            media.expect(id, () -> { fetched.incrementAndGet(); return picture(1); });
            media.expect(id, () -> { fetched.incrementAndGet(); return picture(2); });
            media.start();
            assertArrayEquals(picture(1), media.take(id, () -> null));
        }
        assertEquals(1, fetched.get());
    }

    @Test public void closingStartsNothingMoreAndWhatIsAskedForAfterwardsIsFetchedTheOrdinaryWay() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        List<UUID> ids = new ArrayList<>();
        MediaPrefetch media = new MediaPrefetch();
        for (int index = 0; index < 6; index++) {
            UUID id = UUID.randomUUID(); ids.add(id);
            media.expect(id, () -> { started.incrementAndGet(); hold.await(5, TimeUnit.SECONDS); return picture(1); });
        }
        media.start();
        waitFor(() -> started.get() == MediaPrefetch.PARALLEL, "the first pictures");
        media.close();
        hold.countDown();
        Thread.sleep(80);
        assertEquals("Nothing more was started after closing", MediaPrefetch.PARALLEL, started.get());
        assertArrayEquals(picture(5), media.take(ids.get(0), () -> picture(5)));
        assertEquals(MediaPrefetch.PARALLEL, started.get());
    }

    @Test public void aMissingIdOrWayToFetchIsRefused() {
        try (MediaPrefetch media = new MediaPrefetch()) {
            assertThrows(IllegalArgumentException.class, () -> media.expect(null, () -> null));
            assertThrows(IllegalArgumentException.class, () -> media.expect(UUID.randomUUID(), null));
        }
    }
}
