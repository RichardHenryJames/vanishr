package app.vanishr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public class ChatListModelTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = at(2026, 11, 19, 12, 50, UTC); // a Thursday

    private static long at(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli();
    }

    private static ChatListModel.Message message(long createdAt, long expiresAt, boolean outgoing, boolean photo, boolean viewOnce, String state) {
        return new ChatListModel.Message(createdAt, expiresAt, outgoing, photo, viewOnce, state);
    }

    private static ChatListModel.Summary summary(ChatListModel.Message... messages) {
        ChatListModel.Builder builder = new ChatListModel.Builder();
        for (ChatListModel.Message message : messages) builder.add(message);
        return builder.build();
    }

    @Test public void deliveryFollowsTheChatsOwnReceiptStates() {
        assertEquals(ChatListModel.Delivery.READ, ChatListModel.Delivery.of("READ"));
        assertEquals(ChatListModel.Delivery.DELIVERED, ChatListModel.Delivery.of("DELIVERED"));
        assertEquals(ChatListModel.Delivery.SENT, ChatListModel.Delivery.of("QUEUED"));
        assertEquals(ChatListModel.Delivery.SENDING, ChatListModel.Delivery.of("PENDING"));
        assertEquals(ChatListModel.Delivery.NONE, ChatListModel.Delivery.of("DELETED"));
        assertEquals(ChatListModel.Delivery.NONE, ChatListModel.Delivery.of(null));
    }

    @Test public void aMessageThatWasNotSentIsFailedAndAGroupMessageSaysHowFarItGotByItsCounts() {
        assertEquals(ChatListModel.Delivery.FAILED, ChatListModel.Delivery.of("Not sent: contact unavailable"));
        assertEquals(ChatListModel.Delivery.FAILED, ChatListModel.Delivery.of("Not sent: membership changed"));
        // What a group message says once the relay has reported on it: how many were reached and how many read, of how many.
        assertEquals(ChatListModel.Delivery.SENT, ChatListModel.Delivery.of("0 delivered / 0 of 3 read"));
        assertEquals(ChatListModel.Delivery.DELIVERED, ChatListModel.Delivery.of("2 delivered / 0 of 3 read"));
        assertEquals(ChatListModel.Delivery.DELIVERED, ChatListModel.Delivery.of("3 delivered / 2 of 3 read"));
        assertEquals("Read by all of them", ChatListModel.Delivery.READ, ChatListModel.Delivery.of("3 delivered / 3 of 3 read"));
        assertEquals("A group of nobody has not been read by anyone", ChatListModel.Delivery.SENT, ChatListModel.Delivery.of("0 delivered / 0 of 0 read"));
        assertEquals("Anything else that is not one of ours says nothing", ChatListModel.Delivery.NONE, ChatListModel.Delivery.of("2 delivered / x of 3 read"));
        assertEquals("nor does a number that is not one", ChatListModel.Delivery.NONE, ChatListModel.Delivery.of("99999999999999 delivered / 1 of 1 read"));
        assertTrue("A message that failed is the least far, so that a failure is never hidden by another receipt of the same moment",
            ChatListModel.Delivery.FAILED.compareTo(ChatListModel.Delivery.NONE) < 0);
    }

    @Test public void aFailedSendIsSaidSoWhenItIsTheNewestAndNothingElseIsNew() {
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.FAILED, "Not sent", true),
            ChatListModel.line(summary(message(1, 9, true, false, false, "Not sent: contact unavailable"))));
        assertEquals("Something new is what the line says; a failed send is not shown over it", new ChatListModel.Line(ChatListModel.Icon.NONE, "New message", true),
            ChatListModel.line(summary(message(1, 9, false, false, false, "DELIVERED"), message(2, 9, true, false, false, "Not sent: contact unavailable"))));
        assertEquals("An earlier failure is not the newest message", new ChatListModel.Line(ChatListModel.Icon.SENT, "Message", false),
            ChatListModel.line(summary(message(1, 9, true, false, false, "Not sent: contact unavailable"), message(2, 9, true, false, false, "QUEUED"))));
        assertEquals("A group message that reached everyone and was read by everyone", new ChatListModel.Line(ChatListModel.Icon.READ, "Message", false),
            ChatListModel.line(summary(message(1, 9, true, false, false, "3 delivered / 3 of 3 read"))));
    }

    @Test public void theCountdownIsSpokenInWordsForTheSameMoments() {
        long now = 1_000_000;
        assertEquals("", ChatListModel.countdownSpoken(now + 2 * 3_600_000L, now));
        assertEquals("", ChatListModel.countdownSpoken(0, now));
        assertEquals("", ChatListModel.countdownSpoken(now - 5, now));
        assertEquals("The next message vanishes in 38 minutes", ChatListModel.countdownSpoken(now + 38 * 60_000L + 5_000, now));
        assertEquals("The next message vanishes in 1 minute", ChatListModel.countdownSpoken(now + 60_000L + 5_000, now));
        assertEquals("The next message vanishes in under a minute", ChatListModel.countdownSpoken(now + 30_000, now));
        for (long left : new long[]{1, 59_999, 60_000, 61_000, 3_599_999, 3_600_000, 3_600_001})
            assertEquals("spoken exactly when shown, at " + left, !ChatListModel.countdown(now + left, now).isEmpty(), !ChatListModel.countdownSpoken(now + left, now).isEmpty());
    }

    @Test public void theTimeOfADayThatChangesItsClocksIsStillTheRightDay() {
        // Berlin ended its summer time at 03:00 on 25 October 2026, so that day has 25 hours.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        long noon = at(2026, 10, 25, 12, 0, berlin);
        assertEquals("01:30", ChatListModel.time(at(2026, 10, 25, 1, 30, berlin), noon, berlin, Locale.ENGLISH));
        assertEquals("Yesterday", ChatListModel.time(at(2026, 10, 24, 23, 30, berlin), noon, berlin, Locale.ENGLISH));
        // The clocks went back at 03:00 that night, so 11 hours before noon was 02:00 (the first time it was 02:00), 13 hours before was midnight,
        // and 14 hours before was the evening of the day before.
        long hour = 3_600_000L;
        assertEquals("02:00", ChatListModel.time(noon - 11 * hour, noon, berlin, Locale.ENGLISH));
        assertEquals("00:00", ChatListModel.time(noon - 13 * hour, noon, berlin, Locale.ENGLISH));
        assertEquals("Yesterday", ChatListModel.time(noon - 14 * hour, noon, berlin, Locale.ENGLISH));
    }

    @Test public void aSummaryCountsWhatIsUnreadAndKnowsTheNewestMessage() {
        ChatListModel.Summary summary = summary(
            message(100, 9_000, false, false, false, "DELIVERED"),
            message(200, 8_000, false, true, false, "READ"),
            message(300, 7_000, true, false, false, "DELIVERED"));
        assertEquals(3, summary.live());
        assertEquals(1, summary.unread());
        assertEquals(0, summary.unreadPhotos());
        assertEquals(300, summary.newestAt());
        assertTrue(summary.newestOutgoing());
        assertFalse(summary.newestPhoto());
        assertEquals(ChatListModel.Delivery.DELIVERED, summary.newestDelivery());
        assertEquals("The soonest deadline of any live message", 7_000, summary.nextExpiryAt());
    }

    @Test public void anOutgoingMessageIsNeverUnreadWhateverItsState() {
        ChatListModel.Summary summary = summary(message(100, 5_000, true, false, false, "DELIVERED"), message(200, 5_000, true, false, false, "QUEUED"));
        assertEquals(0, summary.unread());
    }

    @Test public void anEmptyChatHasTheEmptySummary() {
        assertEquals(ChatListModel.Summary.EMPTY, new ChatListModel.Builder().build());
        assertEquals(0, ChatListModel.Summary.EMPTY.live());
        assertEquals(0, ChatListModel.Summary.EMPTY.newestAt());
    }

    @Test public void theResultDoesNotDependOnTheOrderMessagesWereAdded() {
        // Photos of one album share a moment; so can a text and a view-once message. Whatever the order, the same summary.
        List<ChatListModel.Message> messages = List.of(
            message(500, 9_000, true, true, false, "READ"),
            message(500, 9_000, true, true, false, "DELIVERED"),
            message(500, 9_000, true, false, false, "QUEUED"),
            message(500, 9_000, false, false, true, "DELIVERED"),
            message(500, 9_000, false, true, false, "DELIVERED"),
            message(400, 3_000, false, false, false, "DELIVERED"),
            message(400, 3_000, false, true, true, "DELIVERED"));
        ChatListModel.Summary expected = null;
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            List<ChatListModel.Message> shuffled = new ArrayList<>(messages);
            Collections.shuffle(shuffled, random);
            ChatListModel.Builder builder = new ChatListModel.Builder();
            for (ChatListModel.Message message : shuffled) builder.add(message);
            ChatListModel.Summary built = builder.build();
            if (expected == null) expected = built; else assertEquals("round " + round, expected, built);
        }
        assertEquals(500, expected.newestAt());
        assertTrue("Of messages at one moment the view-once one is the newest", expected.newestViewOnce());
    }

    @Test public void ofTwoReceiptsForTheSameMomentTheOneThatGotLeastFarWins() {
        ChatListModel.Summary summary = summary(message(500, 9_000, true, true, false, "READ"), message(500, 9_000, true, true, false, "DELIVERED"));
        assertEquals("One photo of the album is not read yet, so the chat does not say it was", ChatListModel.Delivery.DELIVERED, summary.newestDelivery());
    }

    @Test public void oneNewMessageAPhotoAViewOncePhotoAndAViewOnceMessageAreNamedByTheirKind() {
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.NONE, "New message", true), ChatListModel.line(summary(message(1, 9, false, false, false, "DELIVERED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.PHOTO, "New photo", true), ChatListModel.line(summary(message(1, 9, false, true, false, "DELIVERED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.VIEW_ONCE, "New view-once photo", true), ChatListModel.line(summary(message(1, 9, false, true, true, "DELIVERED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.VIEW_ONCE, "New view-once message", true), ChatListModel.line(summary(message(1, 9, false, false, true, "DELIVERED"))));
    }

    @Test public void severalNewMessagesAreCountedAndSeveralNewPhotosSaySo() {
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.PHOTO, "3 new photos", true), ChatListModel.line(summary(
            message(1, 9, false, true, false, "DELIVERED"), message(2, 9, false, true, false, "DELIVERED"), message(3, 9, false, true, true, "DELIVERED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.NONE, "3 new messages", true), ChatListModel.line(summary(
            message(1, 9, false, true, false, "DELIVERED"), message(2, 9, false, false, false, "DELIVERED"), message(3, 9, false, false, false, "DELIVERED"))));
        assertEquals("What is read is not new", new ChatListModel.Line(ChatListModel.Icon.NONE, "New message", true), ChatListModel.line(summary(
            message(1, 9, false, false, false, "READ"), message(2, 9, false, false, false, "DELIVERED"))));
    }

    @Test public void whenNothingIsNewTheLineNamesTheKindAndForYourOwnMessageHowFarItGot() {
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.READ, "Message", false), ChatListModel.line(summary(message(1, 9, true, false, false, "READ"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.DELIVERED, "Photo", false), ChatListModel.line(summary(message(1, 9, true, true, false, "DELIVERED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.SENT, "Message", false), ChatListModel.line(summary(message(1, 9, true, false, false, "QUEUED"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.SENDING, "Message", false), ChatListModel.line(summary(message(1, 9, true, false, false, "PENDING"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.READ, "View once", false), ChatListModel.line(summary(message(1, 9, true, true, true, "READ"))));
        assertEquals("A message that was read and is theirs has no receipt mark", new ChatListModel.Line(ChatListModel.Icon.PHOTO, "Photo", false),
            ChatListModel.line(summary(message(1, 9, false, true, false, "READ"))));
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.NONE, "Message", false), ChatListModel.line(summary(message(1, 9, false, false, false, "READ"))));
    }

    @Test public void aChatWithNoMessagesSaysSo() {
        assertEquals(new ChatListModel.Line(ChatListModel.Icon.NONE, "No messages", false), ChatListModel.line(ChatListModel.Summary.EMPTY));
    }

    @Test public void theLineNeverCarriesAnythingFromAMessage() {
        // The model is given no words, so there is nothing to leak: every text it can make is one of these.
        Set<String> allowed = new HashSet<>(List.of("New message", "New photo", "New view-once photo", "New view-once message", "No messages", "Message", "Photo", "View once", "Not sent"));
        List<String> states = List.of("READ", "DELIVERED", "QUEUED", "PENDING", "Not sent: contact unavailable", "2 delivered / 1 of 3 read");
        Random random = new Random(3);
        for (int round = 0; round < 500; round++) {
            ChatListModel.Builder builder = new ChatListModel.Builder();
            int count = random.nextInt(6);
            for (int index = 0; index < count; index++)
                builder.add(message(random.nextInt(50), 100 + random.nextInt(50), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    states.get(random.nextInt(states.size()))));
            ChatListModel.Summary built = builder.build();
            String text = ChatListModel.line(built).text();
            boolean counted = text.matches("\\d+ new (messages|photos)");
            assertTrue(text, counted || allowed.contains(text));
        }
    }

    @Test public void aChatMarkedUnreadShowsAsUnreadWithoutNewMessages() {
        ChatListModel.Summary read = summary(message(1, 9, false, false, false, "READ"));
        ChatListModel.Summary fresh = summary(message(1, 9, false, false, false, "DELIVERED"));
        assertFalse(ChatListModel.unread(read, false));
        assertTrue(ChatListModel.unread(read, true));
        assertTrue(ChatListModel.unread(fresh, false));
        assertTrue(ChatListModel.unread(ChatListModel.Summary.EMPTY, true));
        assertFalse(ChatListModel.unread(ChatListModel.Summary.EMPTY, false));
    }

    @Test public void theTimeIsTheClockTodayYesterdayTheWeekdayAndThenTheDate() {
        assertEquals("12:41", ChatListModel.time(at(2026, 11, 19, 12, 41, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("00:00", ChatListModel.time(at(2026, 11, 19, 0, 0, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("Yesterday", ChatListModel.time(at(2026, 11, 18, 23, 59, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("Mon", ChatListModel.time(at(2026, 11, 16, 9, 0, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("Fri", ChatListModel.time(at(2026, 11, 13, 9, 0, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("A week ago it is a date", "12 Nov", ChatListModel.time(at(2026, 11, 12, 9, 0, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("30 Dec 2025", ChatListModel.time(at(2025, 12, 30, 9, 0, UTC), NOW, UTC, Locale.ENGLISH));
        assertEquals("No message, no time", "", ChatListModel.time(0, NOW, UTC, Locale.ENGLISH));
    }

    @Test public void theTimeIsReadInThePhonesOwnZone() {
        ZoneId india = ZoneId.of("Asia/Kolkata");
        long sent = at(2026, 11, 18, 19, 0, UTC); // 00:30 on the 19th in India
        assertEquals("Yesterday", ChatListModel.time(sent, NOW, UTC, Locale.ENGLISH));
        assertEquals("00:30", ChatListModel.time(sent, NOW, india, Locale.ENGLISH));
    }

    @Test public void theCountdownShowsOnlyInTheLastHour() {
        long minute = 60_000;
        assertEquals("", ChatListModel.countdown(NOW + 61 * minute, NOW));
        assertEquals("60 min", ChatListModel.countdown(NOW + 60 * minute, NOW));
        assertEquals("38 min", ChatListModel.countdown(NOW + 38 * minute + 55_000, NOW));
        assertEquals("1 min", ChatListModel.countdown(NOW + minute, NOW));
        assertEquals("<1 min", ChatListModel.countdown(NOW + 59_999, NOW));
        assertEquals("What has vanished shows nothing", "", ChatListModel.countdown(NOW, NOW));
        assertEquals("", ChatListModel.countdown(NOW - 5, NOW));
        assertEquals("No message, no countdown", "", ChatListModel.countdown(0, NOW));
    }

    private static ChatListModel.Row row(String name, boolean pinned, long newestAt) {
        return new ChatListModel.Row(UUID.nameUUIDFromBytes(name.getBytes()), pinned, newestAt);
    }

    private static List<UUID> ids(List<ChatListModel.Row> rows) { return rows.stream().map(ChatListModel.Row::id).toList(); }

    @Test public void withDetailsPinnedComeFirstThenLatestFirstAndChatsWithoutMessagesLast() {
        List<ChatListModel.Row> given = List.of(row("a-old", false, 100), row("b-none", false, 0), row("c-new", false, 900), row("d-pin-old", true, 50), row("e-pin-new", true, 800), row("f-mid", false, 500));
        List<ChatListModel.Row> ordered = ChatListModel.order(given, true);
        assertEquals(List.of(row("e-pin-new", true, 800).id(), row("d-pin-old", true, 50).id(), row("c-new", false, 900).id(), row("f-mid", false, 500).id(),
            row("a-old", false, 100).id(), row("b-none", false, 0).id()), ids(ordered));
    }

    @Test public void chatsThatTieKeepTheOrderTheyWereGivenIn() {
        List<ChatListModel.Row> given = List.of(row("b", false, 0), row("a", false, 0), row("d", false, 70), row("c", false, 70));
        assertEquals(List.of(row("d", false, 70).id(), row("c", false, 70).id(), row("b", false, 0).id(), row("a", false, 0).id()), ids(ChatListModel.order(given, true)));
    }

    @Test public void withoutDetailsTheGivenOrderIsKeptAndOnlyPinnedMoveUp() {
        List<ChatListModel.Row> given = List.of(row("group", false, 10), row("anna", false, 900), row("ben", true, 5), row("cara", false, 700), row("dev", true, 1));
        assertEquals("Recency plays no part", List.of(row("ben", true, 5).id(), row("dev", true, 1).id(), row("group", false, 10).id(), row("anna", false, 900).id(), row("cara", false, 700).id()),
            ids(ChatListModel.order(given, false)));
    }

    private static void assertLatestFirst(List<ChatListModel.Row> rows) {
        long previous = Long.MAX_VALUE;
        for (ChatListModel.Row row : rows) {
            long key = row.newestAt() <= 0 ? Long.MIN_VALUE : row.newestAt();
            assertTrue("Latest first, chats without a message last", key <= previous);
            previous = key;
        }
    }

    @Test public void theOrderIsAPermutationWithPinnedAlwaysFirstAndNeverChangesTheInput() {
        Random random = new Random(11);
        for (int round = 0; round < 400; round++) {
            List<ChatListModel.Row> given = new ArrayList<>();
            int size = random.nextInt(12);
            for (int index = 0; index < size; index++) given.add(row("chat-" + round + "-" + index, random.nextInt(4) == 0, random.nextInt(3) == 0 ? 0 : random.nextInt(1000)));
            List<ChatListModel.Row> before = List.copyOf(given);
            for (boolean details : new boolean[]{true, false}) {
                List<ChatListModel.Row> ordered = ChatListModel.order(given, details);
                assertEquals("The input is left as it was", before, given);
                assertEquals(given.size(), ordered.size());
                assertEquals("Nothing lost, nothing added", new HashSet<>(given), new HashSet<>(ordered));
                List<ChatListModel.Row> pinned = ordered.stream().filter(ChatListModel.Row::pinned).toList();
                List<ChatListModel.Row> others = ordered.stream().filter(row -> !row.pinned()).toList();
                List<ChatListModel.Row> together = new ArrayList<>(pinned); together.addAll(others);
                assertEquals("Every pinned chat comes before every other", together, ordered);
                if (details) { assertLatestFirst(pinned); assertLatestFirst(others); }
                else assertEquals("Without details the others keep the order given",
                    given.stream().filter(row -> !row.pinned()).toList(), others);
            }
        }
    }

    @Test public void filtersPassTheRightChats() {
        for (boolean group : new boolean[]{false, true}) for (boolean unread : new boolean[]{false, true}) for (boolean muted : new boolean[]{false, true}) {
            assertTrue(ChatListModel.passes(ChatListModel.Filter.ALL, group, unread, muted));
            assertEquals(unread, ChatListModel.passes(ChatListModel.Filter.UNREAD, group, unread, muted));
            assertEquals(group, ChatListModel.passes(ChatListModel.Filter.GROUPS, group, unread, muted));
            assertEquals(muted, ChatListModel.passes(ChatListModel.Filter.MUTED, group, unread, muted));
        }
    }

    @Test public void atMostThreeChatsCanBePinnedAndAPinnedOneAlwaysStaysSo() {
        assertEquals(3, ChatListModel.MAX_PINNED);
        assertTrue(ChatListModel.canPin(0, false));
        assertTrue(ChatListModel.canPin(2, false));
        assertFalse(ChatListModel.canPin(3, false));
        assertTrue(ChatListModel.canPin(3, true));
    }
}
