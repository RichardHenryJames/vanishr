package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Which messages are drawn as one album, and what the album shows of the photos in it. */
public class AlbumsTest {
    private static final UUID PEER = UUID.randomUUID();
    private static final long T0 = 1_800_000_000_000L;

    private static ChatEngine.Entry photo(UUID id, boolean outgoing, ChatEnvelope.Expiry expiry, long created, UUID album, int index, int total, String state) {
        ChatEnvelope.Album shown = album == null ? null : new ChatEnvelope.Album(album, index, total);
        return new ChatEngine.Entry(id, PEER, created + expiry.milliseconds, expiry, outgoing, true, state, null, null, 0, null, false, null, null,
                new ChatEngine.PhotoInfo(720, 480, shown));
    }

    private static ChatEngine.Entry text(long created) {
        return new ChatEngine.Entry(UUID.randomUUID(), PEER, created + ChatEnvelope.Expiry.HOURS_24.milliseconds, ChatEnvelope.Expiry.HOURS_24, false, false, "READ");
    }

    private static List<ChatEngine.Entry> album(UUID id, boolean outgoing, int count, long created) {
        List<ChatEngine.Entry> result = new ArrayList<>();
        for (int index = 0; index < count; index++)
            result.add(photo(UUID.randomUUID(), outgoing, ChatEnvelope.Expiry.HOURS_24, created, id, index, count, "READ"));
        return result;
    }

    @Test public void thePhotosOfAnAlbumAreOneRowAtTheFirstOnesPlaceInTheOrderTheyWereSent() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> shown = album(id, false, 4, T0 + 10);
        ChatEngine.Entry before = text(T0), after = text(T0 + 50);
        List<ChatEngine.Entry> chat = new ArrayList<>(List.of(before));
        // Listed in a different order from the one they were sent in: the order of sending is what the album keeps.
        chat.add(shown.get(2)); chat.add(shown.get(0)); chat.add(shown.get(3)); chat.add(shown.get(1)); chat.add(after);
        List<Albums.Row> rows = Albums.rows(chat, true);
        assertEquals(3, rows.size());
        assertSame(before, rows.get(0).first());
        assertTrue(rows.get(1).album());
        assertEquals(List.of(shown.get(0), shown.get(1), shown.get(2), shown.get(3)), rows.get(1).members());
        assertSame("It is named by the photo that was sent first", shown.get(0), rows.get(1).first());
        assertSame(after, rows.get(2).first());
    }

    @Test public void aMessageThatIsNotAPhotoOfTheAlbumIsNeverPulledIn() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> shown = album(id, true, 2, T0);
        ChatEngine.Entry between = text(T0 + 5);
        List<ChatEngine.Entry> chat = List.of(shown.get(0), between, shown.get(1));
        List<Albums.Row> rows = Albums.rows(chat, true);
        assertEquals(2, rows.size());
        assertEquals("The album is where its first photo is", shown.get(0), rows.get(0).first());
        assertSame(between, rows.get(1).first());
        assertEquals(2, rows.get(0).members().size());
    }

    @Test public void aPhotoThatIsTheOnlyOneOfItsAlbumHereIsAPlainRow() {
        List<ChatEngine.Entry> chat = List.of(album(UUID.randomUUID(), false, 3, T0).get(1));
        List<Albums.Row> rows = Albums.rows(chat, true);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).album());
        assertEquals(chat, rows.get(0).members());
    }

    @Test public void aPhotoThatArrivesLateJoinsTheRowItBelongsToAndTheRowKeepsItsName() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> shown = album(id, false, 3, T0);
        List<Albums.Row> early = Albums.rows(List.of(shown.get(0), shown.get(1)), true);
        List<Albums.Row> late = Albums.rows(List.of(shown.get(0), shown.get(1), shown.get(2)), true);
        assertEquals(2, early.get(0).members().size());
        assertEquals(3, late.get(0).members().size());
        assertEquals("The row is the same row, so that it is not drawn afresh", early.get(0).first().id(), late.get(0).first().id());
        List<Albums.Row> first = Albums.rows(List.of(shown.get(1), shown.get(2)), true);
        List<Albums.Row> all = Albums.rows(shown, true);
        assertEquals("Only the first sent is the name, wherever the others are listed", shown.get(0).id(), all.get(0).first().id());
        assertEquals(shown.get(1).id(), first.get(0).first().id());
    }

    @Test public void photosISentAndPhotosIReceivedAreNeverOneAlbumEvenWithTheSameId() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> chat = new ArrayList<>(album(id, true, 2, T0));
        chat.addAll(album(id, false, 2, T0 + 1));
        List<Albums.Row> rows = Albums.rows(chat, true);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).first().outgoing());
        assertFalse(rows.get(1).first().outgoing());
        assertEquals(2, rows.get(0).members().size());
        assertEquals(2, rows.get(1).members().size());
    }

    @Test public void twoSendersInAGroupDoNotShareAnAlbum() {
        UUID id = UUID.randomUUID(), groupId = UUID.randomUUID(), one = UUID.randomUUID(), other = UUID.randomUUID();
        List<ChatEngine.Entry> chat = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            for (UUID sender : List.of(one, other)) {
                chat.add(new ChatEngine.Entry(UUID.randomUUID(), PEER, T0 + ChatEnvelope.Expiry.HOURS_24.milliseconds, ChatEnvelope.Expiry.HOURS_24, false, true, "READ",
                        null, groupId, 1, sender, false, null, null, new ChatEngine.PhotoInfo(720, 480, new ChatEnvelope.Album(id, index, 2))));
            }
        }
        List<Albums.Row> rows = Albums.rows(chat, true);
        assertEquals(2, rows.size());
        for (Albums.Row row : rows) {
            assertEquals(2, row.members().size());
            assertEquals(row.members().get(0).senderId(), row.members().get(1).senderId());
        }
    }

    @Test public void aViewOnceOrBrokenAlbumIsNeverJoined() {
        UUID id = UUID.randomUUID();
        ChatEngine.Entry once1 = photo(UUID.randomUUID(), false, ChatEnvelope.Expiry.VIEW_ONCE, T0, id, 0, 2, "DELIVERED");
        ChatEngine.Entry once2 = photo(UUID.randomUUID(), false, ChatEnvelope.Expiry.VIEW_ONCE, T0, id, 1, 2, "DELIVERED");
        ChatEngine.Entry broken1 = photo(UUID.randomUUID(), false, ChatEnvelope.Expiry.HOURS_24, T0, id, 5, 2, "READ");
        ChatEngine.Entry broken2 = photo(UUID.randomUUID(), false, ChatEnvelope.Expiry.HOURS_24, T0, id, 1, 1, "READ");
        List<Albums.Row> rows = Albums.rows(List.of(once1, once2, broken1, broken2), true);
        assertEquals(4, rows.size());
        for (Albums.Row row : rows) assertFalse(row.album());
    }

    @Test public void withPhotosSwitchedOffNothingIsJoined() {
        List<ChatEngine.Entry> chat = album(UUID.randomUUID(), false, 4, T0);
        List<Albums.Row> rows = Albums.rows(chat, false);
        assertEquals(4, rows.size());
        for (Albums.Row row : rows) assertFalse(row.album());
    }

    @Test public void aSenderCannotHideAPhotoInAnAlbumThatIsTooBigSoTheRestAreRowsOfTheirOwn() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> chat = new ArrayList<>();
        for (int index = 0; index < ChatEnvelope.MAX_ALBUM + 5; index++)
            chat.add(photo(UUID.randomUUID(), false, ChatEnvelope.Expiry.HOURS_24, T0, id, Math.min(index, ChatEnvelope.MAX_ALBUM - 1), ChatEnvelope.MAX_ALBUM, "READ"));
        List<Albums.Row> rows = Albums.rows(chat, true);
        int shown = 0;
        for (Albums.Row row : rows) shown += row.members().size();
        assertEquals("Every message is in exactly one row", chat.size(), shown);
        assertEquals(1 + 5, rows.size());
        assertEquals(ChatEnvelope.MAX_ALBUM, rows.get(0).members().size());
    }

    @Test public void theRowShowsTheLowestStateAndTheEarliestEndAndAnyReaction() {
        UUID id = UUID.randomUUID();
        ChatEngine.Entry one = photo(UUID.randomUUID(), true, ChatEnvelope.Expiry.HOURS_24, T0, id, 0, 3, "READ").withReaction(false, "\u2764\uFE0F");
        ChatEngine.Entry two = photo(UUID.randomUUID(), true, ChatEnvelope.Expiry.HOURS_24, T0, id, 1, 3, "QUEUED").withReaction(true, "\uD83D\uDC4D");
        ChatEngine.Entry three = photo(UUID.randomUUID(), true, ChatEnvelope.Expiry.HOURS_24, T0 - 1000, id, 2, 3, "DELIVERED");
        Albums.Row row = Albums.rows(List.of(one, two, three), true).get(0);
        ChatEngine.Entry summary = row.summary();
        assertEquals("The album is as far on as its slowest photo", "QUEUED", summary.state());
        assertEquals(three.expiresAt(), summary.expiresAt());
        assertEquals("\uD83D\uDC4D", summary.reactionMine());
        assertEquals("\u2764\uFE0F", summary.reactionTheirs());
        assertEquals("It is named by the one sent first, which is the earliest made", three.id(), row.first().id());
        ChatEngine.Entry single = text(T0);
        assertSame(single, Albums.rows(List.of(single), true).get(0).summary());
    }

    @Test public void anUnknownStateIsTheLowestSoAnAlbumNeverLooksFurtherOnThanItIs() {
        UUID id = UUID.randomUUID();
        ChatEngine.Entry sent = photo(UUID.randomUUID(), true, ChatEnvelope.Expiry.HOURS_24, T0, id, 0, 2, "READ");
        ChatEngine.Entry failed = photo(UUID.randomUUID(), true, ChatEnvelope.Expiry.HOURS_24, T0, id, 1, 2, "Not sent: contact unavailable");
        assertEquals("Not sent: contact unavailable", Albums.rows(List.of(sent, failed), true).get(0).summary().state());
    }

    @Test public void theRowKnowsWhichMessagesAreItsOwn() {
        UUID id = UUID.randomUUID();
        List<ChatEngine.Entry> shown = album(id, false, 3, T0);
        Albums.Row row = Albums.rows(shown, true).get(0);
        for (ChatEngine.Entry entry : shown) assertTrue(row.holds(entry.id()));
        assertFalse(row.holds(UUID.randomUUID()));
        assertSame(shown.get(0), row.head());
    }

    @Test public void theMostASendCarriesIsWhatTheRelayTakesInAMinute() {
        assertEquals(6, Albums.MAX_SENT);
        assertTrue(Albums.MAX_SENT <= ChatEnvelope.MAX_ALBUM);
    }
}
