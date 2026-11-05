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
}
