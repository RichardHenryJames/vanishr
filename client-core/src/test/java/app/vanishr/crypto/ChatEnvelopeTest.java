package app.vanishr.crypto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure validation of {@link ChatEnvelope#verify}, not touching Signal sessions or the relay: whether a reaction, an
 * edit or a delete-for-everyone control, or an ordinary message's optional quoted reference, is a shape the app
 * would accept at all.
 */
class ChatEnvelopeTest {
    private final UUID id = UUID.randomUUID(), sender = UUID.randomUUID(), senderDevice = UUID.randomUUID(),
        recipient = UUID.randomUUID(), recipientDevice = UUID.randomUUID();
    private final Instant now = Instant.now();
    private final long deadline = now.plusSeconds(3600).toEpochMilli();

    private void verify(ChatEnvelope envelope) {
        envelope.verify(id, sender, senderDevice, recipient, recipientDevice, ChatEnvelope.Expiry.HOUR_1, deadline, null, now);
    }

    private ChatEnvelope control(String kind, ChatEnvelope.Action action) {
        return new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice, now.toEpochMilli(), deadline,
            ChatEnvelope.Expiry.HOUR_1, null, null, kind, null, action);
    }

    @Test void anOrdinaryTextMessageIsUnaffectedByTheNewOptionalFields() {
        ChatEnvelope envelope = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, "hello", null);
        assertNull(envelope.kind()); assertNull(envelope.reference()); assertNull(envelope.action());
        assertDoesNotThrow(() -> verify(envelope));
    }

    @Test void aQuotedReferenceNeedsAMessageIdAndAShortNonBlankSnippet() {
        ChatEnvelope.Reference ok = new ChatEnvelope.Reference(UUID.randomUUID(), "Earlier message");
        assertDoesNotThrow(() -> verify(withReference(ok)));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(null, "Earlier message"))));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(UUID.randomUUID(), null))));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(UUID.randomUUID(), "   "))));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(UUID.randomUUID(), "a".repeat(161)))));
        assertDoesNotThrow(() -> verify(withReference(new ChatEnvelope.Reference(UUID.randomUUID(), "a".repeat(160)))));
    }

    @Test void theOfferedReactionsAreExactlyTheSixEarlierAppsKnowInAFixedOrder() {
        assertEquals(java.util.List.of("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F"), ChatEnvelope.REACTION_CHOICES);
        assertEquals(java.util.Set.copyOf(ChatEnvelope.REACTION_CHOICES), ChatEnvelope.REACTIONS);
        for (String emoji : ChatEnvelope.REACTION_CHOICES)
            assertDoesNotThrow(() -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(UUID.randomUUID(), emoji, null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(UUID.randomUUID(), "\uD83D\uDD25", null))));
    }

    @Test void aQuotedReferenceMayNameWhoWroteTheQuotedMessageAndIsStillValidWithoutIt() {
        UUID quoted = UUID.randomUUID();
        for (Boolean own : new Boolean[]{null, Boolean.TRUE, Boolean.FALSE})
            assertDoesNotThrow(() -> verify(withReference(new ChatEnvelope.Reference(quoted, "Earlier message", own))));
        assertNull(new ChatEnvelope.Reference(quoted, "Earlier message").ownMessage());
        assertEquals(new ChatEnvelope.Reference(quoted, "Earlier message", null), new ChatEnvelope.Reference(quoted, "Earlier message"));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(quoted, "a".repeat(161), Boolean.TRUE))));
        assertThrows(SecurityException.class, () -> verify(withReference(new ChatEnvelope.Reference(null, "Earlier message", Boolean.TRUE))));
    }

    @Test void aQuotedSnippetIsCutToTheLimitWithoutBreakingACharacterAndAShortOneIsKept() {
        assertEquals("Hello", ChatEnvelope.Reference.excerpt("  Hello \n"));
        assertEquals("a".repeat(160), ChatEnvelope.Reference.excerpt("a".repeat(160)));
        String cut = ChatEnvelope.Reference.excerpt("a".repeat(161));
        assertEquals("a".repeat(157) + "\u2026", cut);
        assertTrue(cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ChatEnvelope.MAX_SNIPPET_BYTES);
        // Two- and four-byte characters, so that the cut lands in the middle of one if it were made by bytes.
        for (String unit : new String[]{"\u00e9", "\u20ac", "\uD83D\uDE02"}) {
            String shortened = ChatEnvelope.Reference.excerpt(unit.repeat(200));
            assertTrue(shortened.endsWith("\u2026"));
            assertTrue(shortened.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ChatEnvelope.MAX_SNIPPET_BYTES);
            String body = shortened.substring(0, shortened.length() - 1);
            assertEquals(unit.repeat(body.length() / unit.length()), body);
            assertDoesNotThrow(() -> verify(withReference(new ChatEnvelope.Reference(UUID.randomUUID(), shortened, Boolean.TRUE))));
        }
        assertEquals("", ChatEnvelope.Reference.excerpt(null));
    }

    private ChatEnvelope withReference(ChatEnvelope.Reference reference) {
        return new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice, now.toEpochMilli(), deadline,
            ChatEnvelope.Expiry.HOUR_1, "hello", null, null, reference, null);
    }

    @Test void anOrdinaryMessageCannotAlsoCarryAControlAction() {
        ChatEnvelope envelope = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, "hello", null, null, null,
            new ChatEnvelope.Action(UUID.randomUUID(), null, null));
        assertThrows(SecurityException.class, () -> verify(envelope));
    }

    @Test void aControlMessageCannotAlsoCarryTextAnImageOrAReference() {
        ChatEnvelope.Action action = new ChatEnvelope.Action(UUID.randomUUID(), null, null);
        assertThrows(SecurityException.class, () -> verify(new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, "hello", null, ChatEnvelope.DELETE, null, action)));
        assertThrows(SecurityException.class, () -> verify(new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, null,
            new ChatEnvelope.Attachment(UUID.randomUUID(), new byte[32], new byte[12], "image/jpeg"), ChatEnvelope.DELETE, null, action)));
        assertThrows(SecurityException.class, () -> verify(new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, null, null, ChatEnvelope.DELETE,
            new ChatEnvelope.Reference(UUID.randomUUID(), "x"), action)));
    }

    @Test void anUnknownControlKindIsRefusedWhole() {
        assertThrows(SecurityException.class, () -> verify(control("SOMETHING_ELSE", new ChatEnvelope.Action(UUID.randomUUID(), null, null))));
    }

    @Test void aControlActionMustNameATarget() {
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.DELETE, new ChatEnvelope.Action(null, null, null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.DELETE, null)));
    }

    @Test void aReactionIsEitherNoEmojiOrExactlyOneOfTheFixedPalette() {
        UUID target = UUID.randomUUID();
        assertDoesNotThrow(() -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(target, null, null))));
        for (String emoji : ChatEnvelope.REACTIONS)
            assertDoesNotThrow(() -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(target, emoji, null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(target, "\uD83D\uDC79", null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.REACTION, new ChatEnvelope.Action(target, null, "note"))));
    }

    @Test void anEditCarriesOnlyReplacementTextWithinTheOrdinaryTextLimit() {
        UUID target = UUID.randomUUID();
        assertDoesNotThrow(() -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, null, "updated text"))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, null, null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, null, "   "))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, null, "a".repeat(16_385)))));
        assertDoesNotThrow(() -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, null, "a".repeat(16_384)))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.EDIT, new ChatEnvelope.Action(target, "\uD83D\uDC4D", "updated text"))));
    }

    @Test void aDeleteNamesOnlyATarget() {
        UUID target = UUID.randomUUID();
        assertDoesNotThrow(() -> verify(control(ChatEnvelope.DELETE, new ChatEnvelope.Action(target, null, null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.DELETE, new ChatEnvelope.Action(target, "\uD83D\uDC4D", null))));
        assertThrows(SecurityException.class, () -> verify(control(ChatEnvelope.DELETE, new ChatEnvelope.Action(target, null, "note"))));
    }

    @Test void aControlMessageStillHasToMatchItsExpectedContextAndDeadline() {
        ChatEnvelope envelope = control(ChatEnvelope.DELETE, new ChatEnvelope.Action(UUID.randomUUID(), null, null));
        assertDoesNotThrow(() -> verify(envelope));
        assertThrows(SecurityException.class, () -> envelope.verify(UUID.randomUUID(), sender, senderDevice, recipient, recipientDevice,
            ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(id, sender, senderDevice, recipient, recipientDevice,
            ChatEnvelope.Expiry.HOUR_1, deadline, null, now.plusSeconds(3600)));
    }

    @Test void aTwelveHourChoiceTravelsAsA24HourMessageAndEveryOtherChoiceTravelsAsItself() {
        assertEquals(ChatEnvelope.Expiry.HOURS_24, ChatEnvelope.Expiry.HOURS_12.wire());
        for (ChatEnvelope.Expiry other : ChatEnvelope.Expiry.values())
            if (other != ChatEnvelope.Expiry.HOURS_12) assertEquals(other, other.wire());
        // The four values every earlier app and the relay know are still all there, under the names they use.
        for (String name : new String[]{"VIEW_ONCE", "HOUR_1", "HOURS_6", "HOURS_24"}) assertDoesNotThrow(() -> ChatEnvelope.Expiry.valueOf(name));
        assertEquals(12 * 3_600_000L, ChatEnvelope.Expiry.HOURS_12.milliseconds);
    }

    @Test void aReceivedMessageIsATwelveHourOneOnlyWhenItIsA24HourMessageWithExactlyATwelveHourLife() {
        long sent = now.toEpochMilli(), twelve = ChatEnvelope.Expiry.HOURS_12.milliseconds;
        assertEquals(ChatEnvelope.Expiry.HOURS_12, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.HOURS_24, sent, sent + twelve));
        assertEquals(ChatEnvelope.Expiry.HOURS_24, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.HOURS_24, sent, sent + 24 * 3_600_000L));
        assertEquals(ChatEnvelope.Expiry.HOURS_24, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.HOURS_24, sent, sent + twelve - 1));
        assertEquals(ChatEnvelope.Expiry.HOURS_24, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.HOURS_24, sent, sent + twelve + 1));
        // Another class with a twelve-hour life is not a twelve-hour message (view-once keeps its own rules).
        assertEquals(ChatEnvelope.Expiry.VIEW_ONCE, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.VIEW_ONCE, sent, sent + twelve));
        assertEquals(ChatEnvelope.Expiry.HOURS_6, ChatEnvelope.Expiry.received(ChatEnvelope.Expiry.HOURS_6, sent, sent + twelve));
    }

    @Test void aTwelveHourMessageSentAsA24HourOneStillPassesTheSameChecksAnEarlierAppMakes() {
        long sent = now.toEpochMilli(), life = ChatEnvelope.Expiry.HOURS_12.milliseconds, end = sent + life;
        ChatEnvelope envelope = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice, sent, end,
            ChatEnvelope.Expiry.HOURS_12.wire(), "hello", null);
        assertDoesNotThrow(() -> envelope.verify(id, sender, senderDevice, recipient, recipientDevice,
            ChatEnvelope.Expiry.HOURS_24, end, null, now));
        // The deadline may be sooner than the class allows but never later.
        ChatEnvelope tooLong = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice, sent, sent + 25 * 3_600_000L,
            ChatEnvelope.Expiry.HOURS_24, "hello", null);
        assertThrows(SecurityException.class, () -> tooLong.verify(id, sender, senderDevice, recipient, recipientDevice,
            ChatEnvelope.Expiry.HOURS_24, sent + 25 * 3_600_000L, null, now));
    }
}
