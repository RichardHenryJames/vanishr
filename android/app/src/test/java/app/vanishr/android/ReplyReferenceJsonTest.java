package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/** How a quoted reply crosses between this app and an earlier one: the author flag is optional in both directions. */
public class ReplyReferenceJsonTest {
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** The shape an earlier app reads: no author component. */
    private record Earlier(UUID messageId, String snippet) { }

    @Test public void aReplyFromAnEarlierAppHasNoAuthor() {
        ChatEnvelope.Reference read = RelayApi.JSON.fromJson("{\"messageId\":\"" + ID + "\",\"snippet\":\"Hello\"}", ChatEnvelope.Reference.class);
        assertEquals(ID, read.messageId());
        assertEquals("Hello", read.snippet());
        assertNull(read.ownMessage());
    }

    @Test public void aReplyWithNoAuthorIsWrittenExactlyAsEarlierAppsWriteIt() {
        assertEquals("{\"messageId\":\"" + ID + "\",\"snippet\":\"Hello\"}", RelayApi.JSON.toJson(new ChatEnvelope.Reference(ID, "Hello")));
    }

    @Test public void theAuthorFlagTravelsBothWaysAndAnEarlierAppIgnoresIt() {
        for (boolean own : new boolean[]{true, false}) {
            String json = RelayApi.JSON.toJson(new ChatEnvelope.Reference(ID, "Hello", own));
            assertTrue(json, json.contains("\"ownMessage\":" + own));
            assertEquals(new ChatEnvelope.Reference(ID, "Hello", own), RelayApi.JSON.fromJson(json, ChatEnvelope.Reference.class));
            Earlier earlier = RelayApi.JSON.fromJson(json, Earlier.class);
            assertEquals(ID, earlier.messageId());
            assertEquals("Hello", earlier.snippet());
        }
    }
}
