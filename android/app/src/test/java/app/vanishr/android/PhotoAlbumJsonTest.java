package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/** How a photo's size and album cross between this app and an earlier one: both are optional in both directions. */
public class PhotoAlbumJsonTest {
    private static final UUID PHOTO = UUID.fromString("11111111-2222-3333-4444-555555555555"), ALBUM = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    /** The shape an earlier app reads: no size and no album. */
    private record Earlier(UUID id, byte[] key, byte[] nonce, String mimeType) { }

    @Test public void aPhotoFromAnEarlierAppHasNoSizeAndNoAlbum() {
        String json = RelayApi.JSON.toJson(new Earlier(PHOTO, new byte[32], new byte[12], "image/jpeg"));
        ChatEnvelope.Attachment read = RelayApi.JSON.fromJson(json, ChatEnvelope.Attachment.class);
        assertEquals(PHOTO, read.id());
        assertEquals(0, read.width());
        assertEquals(0, read.height());
        assertNull(read.album());
    }

    @Test public void theSizeAndTheAlbumTravelBothWaysAndAnEarlierAppIgnoresThem() {
        ChatEnvelope.Attachment sent = new ChatEnvelope.Attachment(PHOTO, new byte[32], new byte[12], "image/jpeg", 1600, 1200, new ChatEnvelope.Album(ALBUM, 2, 6));
        String json = RelayApi.JSON.toJson(sent);
        assertTrue(json, json.contains("\"width\":1600") && json.contains("\"height\":1200") && json.contains("\"index\":2") && json.contains("\"total\":6"));
        ChatEnvelope.Attachment back = RelayApi.JSON.fromJson(json, ChatEnvelope.Attachment.class);
        assertEquals(1600, back.width());
        assertEquals(1200, back.height());
        assertEquals(new ChatEnvelope.Album(ALBUM, 2, 6), back.album());
        Earlier earlier = RelayApi.JSON.fromJson(json, Earlier.class);
        assertEquals(PHOTO, earlier.id());
        assertEquals("image/jpeg", earlier.mimeType());
    }

    @Test public void aPhotoWithNoAlbumWritesNoAlbum() {
        String json = RelayApi.JSON.toJson(new ChatEnvelope.Attachment(PHOTO, new byte[32], new byte[12], "image/jpeg", 800, 600, null));
        assertFalse(json, json.contains("album"));
    }

    @Test public void theRecordOfAPhotoThatAnswersAMessageHoldsAFlagAndNeverTheWordsItQuotes() {
        ChatEnvelope.Attachment attachment = new ChatEnvelope.Attachment(PHOTO, new byte[32], new byte[12], "image/jpeg", 800, 600, new ChatEnvelope.Album(ALBUM, 0, 2));
        ChatEngine.Entry answer = new ChatEngine.Entry(PHOTO, ALBUM, 1_000_000L, ChatEnvelope.Expiry.HOUR_1, true, true, "QUEUED")
                .withPhoto(ChatEngine.PhotoInfo.of(attachment, new ChatEnvelope.Reference(ALBUM, "the words that were quoted 4417", Boolean.FALSE)));
        String json = RelayApi.JSON.toJson(answer);
        assertFalse("Words are kept in the sealed message, not in the record that lists it: " + json, json.contains("the words that were quoted"));
        assertFalse(json, json.contains("snippet"));
        ChatEngine.Entry back = RelayApi.JSON.fromJson(json, ChatEngine.Entry.class);
        assertTrue("but the record knows that it is an answer", back.photo().quotes());
        assertEquals(800, back.photo().width());
        assertEquals(new ChatEnvelope.Album(ALBUM, 0, 2), back.photo().album());
        assertFalse(ChatEngine.PhotoInfo.of(attachment, null).quotes());
        assertNull(ChatEngine.PhotoInfo.of(null, new ChatEnvelope.Reference(ALBUM, "words", Boolean.FALSE)));
    }
}
