package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** The words kept in memory: that they come back only for the record they were read from, never at or after a deadline, and stay bounded. */
public class MessageWordsTest {
    private static MessageWords.Words words(String text) { return new MessageWords.Words(text, null); }

    private static byte[] sealed(int seed) { return new byte[]{1, (byte) seed, 2, 3, (byte) (seed >> 8)}; }

    @Test public void wordsComeBackForTheSameSealedRecordAndNotForAnotherOne() {
        MessageWords kept = new MessageWords();
        UUID id = UUID.randomUUID();
        kept.put(id, sealed(1), 10_000, words("See you at six"), 0);
        assertEquals("See you at six", kept.get(id, sealed(1), 100).text());
        assertNull("The record was replaced by another one (an edit)", kept.get(id, sealed(2), 100));
        assertFalse("A stale entry is dropped, not kept for later", kept.holds(id));
        assertNull(kept.get(id, sealed(1), 100));
    }

    @Test public void aQuoteComesBackWithTheWordsItBelongsTo() {
        MessageWords kept = new MessageWords();
        UUID id = UUID.randomUUID(), quoted = UUID.randomUUID();
        ChatEnvelope.Reference reference = new ChatEnvelope.Reference(quoted, "Coffee at 4?", Boolean.FALSE);
        kept.put(id, sealed(1), 10_000, new MessageWords.Words("Yes", reference), 0);
        MessageWords.Words found = kept.get(id, sealed(1), 1);
        assertEquals("Yes", found.text());
        assertEquals(reference, found.reference());
    }

    @Test public void aMessageIsNotGivenBackAtOrAfterItsDeadlineAndGoesThen() {
        MessageWords kept = new MessageWords();
        UUID before = UUID.randomUUID(), at = UUID.randomUUID(), after = UUID.randomUUID();
        for (UUID id : List.of(before, at, after)) kept.put(id, sealed(1), 1_000, words("words"), 0);
        assertNotNull(kept.get(before, sealed(1), 999));
        assertNull("At the deadline it is gone", kept.get(at, sealed(1), 1_000));
        assertNull("After the deadline too", kept.get(after, sealed(1), 5_000));
        assertFalse(kept.holds(at));
        assertFalse(kept.holds(after));
        assertEquals("What was not asked for is still held until it is", 1, kept.size());
        assertEquals(1, kept.dropExpired(1_000));
        assertEquals(0, kept.size());
        assertEquals(0, kept.characters());
    }

    @Test public void aMissingRecordIsAMissAndDropsWhatWasHeld() {
        MessageWords kept = new MessageWords();
        UUID id = UUID.randomUUID();
        kept.put(id, sealed(1), 10_000, words("words"), 0);
        assertNull("The record is gone from the store", kept.get(id, null, 1));
        assertFalse(kept.holds(id));
        assertEquals(0, kept.characters());
    }

    @Test public void theOneReadLongestAgoGoesFirstWhenTheCountIsReached() {
        MessageWords kept = new MessageWords(3, 1_000_000);
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (int index = 0; index < 3; index++) kept.put(ids[index], sealed(index), 10_000, words("m" + index), 0);
        // Reading the first makes it the most recently used, so the second is now the one read longest ago.
        assertNotNull(kept.get(ids[0], sealed(0), 1));
        kept.put(ids[3], sealed(3), 10_000, words("m3"), 0);
        assertEquals(3, kept.size());
        assertTrue(kept.holds(ids[0]));
        assertFalse(kept.holds(ids[1]));
        assertTrue(kept.holds(ids[2]));
        assertTrue(kept.holds(ids[3]));
    }

    @Test public void theCharactersAreBoundedSoAChatOfLongMessagesCannotHoldMuchMemory() {
        MessageWords kept = new MessageWords(1_000, 1_000);
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            UUID id = UUID.randomUUID(); ids.add(id);
            kept.put(id, sealed(index), 10_000, words("x".repeat(300)), 0);
            assertTrue("At most the bound: " + kept.characters(), kept.characters() <= 1_000);
        }
        assertEquals("Each costs 364 and 1000 / 364 is 2 whole ones", 2, kept.size());
        assertTrue("The newest two are the ones that stay", kept.holds(ids.get(9)) && kept.holds(ids.get(8)));
    }

    @Test public void oneMessageBiggerThanTheBoundIsStillKeptAloneRatherThanLoopingOrLosingTheNewestRead() {
        MessageWords kept = new MessageWords(10, 100);
        UUID id = UUID.randomUUID();
        kept.put(id, sealed(1), 10_000, words("y".repeat(500)), 0);
        assertEquals(1, kept.size());
        assertNotNull(kept.get(id, sealed(1), 1));
        UUID next = UUID.randomUUID();
        kept.put(next, sealed(2), 10_000, words("z"), 0);
        assertFalse("The big one goes as soon as another one needs the room", kept.holds(id));
        assertTrue(kept.holds(next));
        assertTrue(kept.characters() <= 100);
    }

    @Test public void theAccountingNeverDriftsWhateverIsPutReplacedRemovedAndDropped() {
        MessageWords kept = new MessageWords(50, 5_000);
        Random random = new Random(3);
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 40; index++) ids.add(UUID.randomUUID());
        for (int step = 0; step < 2_000; step++) {
            UUID id = ids.get(random.nextInt(ids.size()));
            switch (random.nextInt(5)) {
                case 0, 1 -> kept.put(id, sealed(step), 100 + random.nextInt(900), words("w".repeat(random.nextInt(400))), step);
                case 2 -> kept.remove(id);
                case 3 -> kept.get(id, sealed(random.nextInt(2_000)), step);
                default -> kept.dropExpired(step);
            }
            assertTrue(kept.characters() >= 0);
            assertTrue(kept.size() <= 50);
            assertTrue("Within the bound unless one message alone is over it: " + kept.characters(), kept.characters() <= 5_000 || kept.size() == 1);
        }
        for (UUID id : ids) kept.remove(id);
        assertEquals("Everything removed, nothing counted", 0, kept.characters());
        assertEquals(0, kept.size());
    }

    @Test public void retainOnlyDropsWhatTheStoreNoLongerHoldsExceptWhatWasJustPut() {
        MessageWords kept = new MessageWords();
        UUID stored = UUID.randomUUID(), gone = UUID.randomUUID(), fresh = UUID.randomUUID();
        kept.put(stored, sealed(1), 1_000_000, words("still stored"), 0);
        kept.put(gone, sealed(2), 1_000_000, words("removed by something that did not say so"), 0);
        long later = MessageWords.GRACE_MILLIS + 1;
        kept.put(fresh, sealed(3), 1_000_000, words("put a moment ago, its record is in a commit that cannot be seen yet"), later - 10);
        assertEquals(1, kept.retainOnly(Set.of(stored), later));
        assertTrue(kept.holds(stored));
        assertFalse(kept.holds(gone));
        assertTrue("Spared during the grace period", kept.holds(fresh));
        assertEquals("Not spared once the grace period has passed", 1, kept.retainOnly(Set.of(stored), later + MessageWords.GRACE_MILLIS));
        assertFalse(kept.holds(fresh));
    }

    @Test public void putAgainReplacesTheWordsAndKeepsOneEntry() {
        MessageWords kept = new MessageWords();
        UUID id = UUID.randomUUID();
        kept.put(id, sealed(1), 10_000, words("first"), 0);
        assertEquals(5 + 64, kept.characters());
        kept.put(id, sealed(2), 10_000, words("second, a good deal longer"), 1);
        assertEquals(1, kept.size());
        assertEquals("Only the words that replaced them are counted", 26 + 64, kept.characters());
        assertEquals("second, a good deal longer", kept.get(id, sealed(2), 2).text());
        assertNull(kept.get(id, sealed(1), 2));
        assertEquals("A miss drops what was held, and its count with it", 0, kept.characters());
    }

    @Test public void aReadThatBeganBeforeARemovalCannotKeepWhatItRead() {
        MessageWords kept = new MessageWords();
        UUID erased = UUID.randomUUID(), other = UUID.randomUUID();
        long ticket = kept.ticket();
        // The message is erased while its words are being decrypted, and nothing was held for it yet.
        kept.remove(erased);
        assertFalse("What the read found is refused", kept.put(erased, sealed(1), 10_000, words("words of an erased message"), 0, ticket));
        assertFalse(kept.holds(erased));
        assertEquals(0, kept.size());
        assertEquals(0, kept.characters());
        // Any removal counts, so a read that was under way for another message is refused as well; it is only read again.
        long later = kept.ticket();
        kept.remove(UUID.randomUUID());
        assertFalse(kept.put(other, sealed(2), 10_000, words("w"), 0, later));
        assertEquals(0, kept.size());
    }

    @Test public void aReadThatBeganBeforeAClearCannotKeepWhatItRead() {
        MessageWords kept = new MessageWords();
        long ticket = kept.ticket();
        kept.clear();
        assertFalse(kept.put(UUID.randomUUID(), sealed(1), 10_000, words("words read as the app was closed"), 0, ticket));
        assertEquals(0, kept.size());
        assertEquals(0, kept.characters());
    }

    @Test public void aReadThatBeganAfterARemovalKeepsWhatItRead() {
        MessageWords kept = new MessageWords();
        UUID id = UUID.randomUUID();
        kept.remove(UUID.randomUUID());
        kept.clear();
        long ticket = kept.ticket();
        assertTrue(kept.put(id, sealed(1), 10_000, words("read after the removal"), 0, ticket));
        assertEquals("read after the removal", kept.get(id, sealed(1), 1).text());
        assertTrue("Reading and keeping do not move the ticket, so reads that overlap each other all keep what they read",
            kept.put(UUID.randomUUID(), sealed(2), 10_000, words("another"), 0, ticket));
        assertEquals(2, kept.size());
    }

    @Test public void onlyARemovalOrAClearMovesTheTicket() {
        MessageWords kept = new MessageWords(3, 1_000);
        UUID id = UUID.randomUUID(), stale = UUID.randomUUID(), expired = UUID.randomUUID();
        long ticket = kept.ticket();
        kept.put(id, sealed(1), 10_000, words("kept"), 0);
        kept.put(stale, sealed(1), 10_000, words("stale"), 0);
        kept.put(expired, sealed(1), 50, words("expires"), 0);
        assertNull("A record that changed is a miss", kept.get(id, sealed(9), 1));
        assertEquals(1, kept.dropExpired(100));
        assertEquals(0, kept.retainOnly(Set.of(stale), 1));
        for (int index = 0; index < 5; index++) kept.put(UUID.randomUUID(), sealed(index), 10_000, words("more"), 0);
        assertEquals("Dropping for these reasons, and the bound, say nothing about a message that is being read", ticket, kept.ticket());
        kept.remove(UUID.randomUUID());
        assertNotEquals("A removal of a message that is not held is counted", ticket, kept.ticket());
        long afterOne = kept.ticket();
        kept.clear();
        assertNotEquals(afterOne, kept.ticket());
    }

    @Test public void clearDropsEverything() {
        MessageWords kept = new MessageWords();
        for (int index = 0; index < 20; index++) kept.put(UUID.randomUUID(), sealed(index), 10_000, words("w" + index), 0);
        kept.clear();
        assertEquals(0, kept.size());
        assertEquals(0, kept.characters());
    }

    @Test public void aMessageWithoutWordsOrAQuoteWeighsNothingButIsStillCounted() {
        MessageWords kept = new MessageWords(2, 1_000_000);
        for (int index = 0; index < 5; index++) kept.put(UUID.randomUUID(), sealed(index), 10_000, new MessageWords.Words(null, null), 0);
        assertEquals("Bounded by count even when every one is empty", 2, kept.size());
    }

    @Test public void requiredArgumentsAreRefused() {
        MessageWords kept = new MessageWords();
        assertThrows(IllegalArgumentException.class, () -> kept.put(null, sealed(1), 1, words("w"), 0));
        assertThrows(IllegalArgumentException.class, () -> kept.put(UUID.randomUUID(), null, 1, words("w"), 0));
        assertThrows(IllegalArgumentException.class, () -> kept.put(UUID.randomUUID(), sealed(1), 1, null, 0));
        assertEquals(0, kept.size());
    }

    @Test public void useFromSeveralThreadsKeepsTheBoundsAndNeverFails() throws Exception {
        MessageWords kept = new MessageWords(40, 4_000);
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 60; index++) ids.add(UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(4);
        for (int worker = 0; worker < 4; worker++) {
            int seed = worker;
            new Thread(() -> {
                try {
                    Random random = new Random(seed);
                    for (int step = 0; step < 5_000; step++) {
                        UUID id = ids.get(random.nextInt(ids.size()));
                        switch (random.nextInt(6)) {
                            case 0, 1 -> kept.put(id, sealed(step), 50_000, words("v".repeat(random.nextInt(200))), step);
                            case 2 -> kept.get(id, sealed(step), step);
                            case 3 -> kept.remove(id);
                            case 4 -> kept.retainOnly(Set.copyOf(ids.subList(0, 30)), step);
                            default -> kept.dropExpired(step);
                        }
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { done.countDown(); }
            }).start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNull("No thread failed: " + failure.get(), failure.get());
        assertTrue(kept.size() <= 40);
        assertTrue(kept.characters() <= 4_000 || kept.size() == 1);
        kept.clear();
        assertEquals(0, kept.characters());
    }
}
