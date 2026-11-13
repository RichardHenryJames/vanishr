package app.vanishr.crypto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ChatEnvelope(int version, UUID id, UUID senderId, UUID senderDeviceId,
                           UUID recipientId, UUID recipientDeviceId, long sentAt, long expiresAt,
                           Expiry expiry, String text, Attachment image, String kind, Reference reference, Action action) {
    /** Every field before {@code kind} keeps its exact 0.6.x meaning; an envelope with no kind is an ordinary message. */
    public ChatEnvelope(int version, UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId, UUID recipientDeviceId,
                        long sentAt, long expiresAt, Expiry expiry, String text, Attachment image) {
        this(version, id, senderId, senderDeviceId, recipientId, recipientDeviceId, sentAt, expiresAt, expiry, text, image, null, null, null);
    }

    public enum Expiry {
        VIEW_ONCE(24), HOUR_1(1), HOURS_6(6), HOURS_12(12), HOURS_24(24);
        public final long milliseconds;
        Expiry(int hours) { milliseconds = hours * 3_600_000L; }

        /**
         * What goes to the relay and into the encrypted envelope. {@link #HOURS_12} is a choice made on this phone only: the relay and
         * every earlier app know the other four values, and one that meets a fifth cannot read the message at all (and its whole sync
         * stops at it), so a twelve-hour message travels as a 24-hour one whose deadline is simply earlier. Both still accept that:
         * a deadline may be sooner than the class allows, never later.
         */
        public Expiry wire() { return this == HOURS_12 ? HOURS_24 : this; }

        /** The choice a received message stands for: a 24-hour message that was sent with a twelve-hour life is a twelve-hour one. */
        public static Expiry received(Expiry wire, long sentAt, long expiresAt) {
            return wire == HOURS_24 && expiresAt - sentAt == HOURS_12.milliseconds ? HOURS_12 : wire;
        }
    }

    public record Attachment(UUID id, byte[] key, byte[] nonce, String mimeType) { }

    /** What an ordinary message optionally quotes: a short, self-contained copy, not a live link, because the quoted message can
     * expire or be deleted before the reply is read. {@code ownMessage} says whether the quoted message was written by whoever sends
     * the reply, so the reader can name its author; replies from earlier apps leave it out, and earlier apps ignore it in ours. */
    public record Reference(UUID messageId, String snippet, Boolean ownMessage) {
        public Reference(UUID messageId, String snippet) { this(messageId, snippet, null); }

        /** The part of a message's text that can be quoted: all of it when it fits {@link #MAX_SNIPPET_BYTES}, else its beginning and an
         * ellipsis, never cut through a character. */
        public static String excerpt(String text) {
            String clean = text == null ? "" : text.strip();
            if (clean.getBytes(StandardCharsets.UTF_8).length <= MAX_SNIPPET_BYTES) return clean;
            StringBuilder kept = new StringBuilder();
            int bytes = 0;
            for (int index = 0; index < clean.length(); ) {
                int point = clean.codePointAt(index);
                int size = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > MAX_SNIPPET_BYTES - 3) break;
                kept.appendCodePoint(point); bytes += size; index += Character.charCount(point);
            }
            return kept.toString().stripTrailing() + "\u2026";
        }
    }

    public static final int MAX_SNIPPET_BYTES = 160;

    /** The payload of a control message ({@link #kind} non-null): which earlier message it is about, and, depending on the kind,
     * one reaction emoji (REACTION) or replacement text (EDIT). DELETE carries neither. */
    public record Action(UUID targetId, String emoji, String text) { }

    public static final String REACTION = "REACTION", EDIT = "EDIT", DELETE = "DELETE";
    private static final Set<String> KINDS = Set.of(REACTION, EDIT, DELETE);
    /** A fixed, small palette: exact string equality only, never arbitrary text, so a reaction can never carry hidden content. The list
     * is the order it is offered in; every earlier app knows exactly these six and refuses a message that carries any other. */
    public static final List<String> REACTION_CHOICES = List.of("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F");
    public static final Set<String> REACTIONS = Set.copyOf(REACTION_CHOICES);

    public void verify(UUID expectedId, UUID sender, UUID senderDevice, UUID recipient, UUID recipientDevice,
                       Expiry expectedExpiry, long expectedDeadline, UUID mediaId, Instant now) {
        if (version != 1 || !Objects.equals(id, expectedId) || !Objects.equals(senderId, sender)
                || !Objects.equals(senderDeviceId, senderDevice) || !Objects.equals(recipientId, recipient)
                || !Objects.equals(recipientDeviceId, recipientDevice) || expiry == null || expiry != expectedExpiry
                || expiresAt != expectedDeadline || sentAt > now.toEpochMilli() + 300_000
                || sentAt < now.toEpochMilli() - 86_400_000 || expiresAt <= now.toEpochMilli()
                || expiresAt > now.toEpochMilli() + expiry.milliseconds
                || expiresAt <= sentAt || expiresAt - sentAt > expiry.milliseconds)
            throw new SecurityException("Message context or deadline is invalid");
        if (kind == null) {
            if (action != null) throw new SecurityException("An ordinary message cannot carry a control action");
            if (reference != null && (reference.messageId() == null || reference.snippet() == null || reference.snippet().isBlank()
                    || reference.snippet().getBytes(StandardCharsets.UTF_8).length > MAX_SNIPPET_BYTES))
                throw new SecurityException("Invalid message reference");
            if (image == null) {
                if (mediaId != null || text == null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 16_384)
                    throw new SecurityException("Invalid encrypted text message");
            } else if (text != null || !Objects.equals(image.id(), mediaId) || image.id() == null
                    || image.key() == null || image.key().length != 32 || image.nonce() == null || image.nonce().length != 12
                    || !("image/jpeg".equals(image.mimeType()) || "image/png".equals(image.mimeType()))) {
                throw new SecurityException("Invalid encrypted image descriptor");
            }
            return;
        }
        if (text != null || image != null || reference != null || mediaId != null || !KINDS.contains(kind) || action == null || action.targetId() == null)
            throw new SecurityException("Invalid control message");
        switch (kind) {
            case REACTION -> { if (action.text() != null || action.emoji() != null && !REACTIONS.contains(action.emoji()))
                throw new SecurityException("Invalid reaction"); }
            case EDIT -> { if (action.emoji() != null || action.text() == null || action.text().isBlank()
                    || action.text().getBytes(StandardCharsets.UTF_8).length > 16_384) throw new SecurityException("Invalid edit"); }
            case DELETE -> { if (action.emoji() != null || action.text() != null) throw new SecurityException("Invalid delete"); }
        }
    }
}