package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The words of messages that were read lately, kept in memory so that a chat that is opened again, or a message that this phone has just
 * written or received, does not wait for the Keystore for words that are already in hand.
 *
 * <p>Every message has its own Keystore key, so reading its words costs a call to the Keystore: some milliseconds on a phone whose keys
 * are in the secure hardware and many more when they are in a separate chip, for every message of a chat that is opened. This keeps the
 * result for as long as the app is open, and nothing longer:
 * <ul>
 * <li>Memory only. Nothing here is written anywhere, and the owner of this object drops all of it when the app leaves the screen or the
 * store is closed.</li>
 * <li>Bounded: at most {@link #MOST_MESSAGES} messages and {@link #MOST_CHARACTERS} characters; the one read longest ago goes first.</li>
 * <li>Only for the sealed record the words came from. What is kept is checked against the stored record on every use, so a message that
 * was edited, replaced or removed is never shown from here, whatever else forgot to tell this object.</li>
 * <li>Never past a deadline: a message is not given back at or after the moment it expires, and goes then.</li>
 * <li>Nothing from a read that a removal overtook: a message that is erased can still be being decrypted on another thread, and that read
 * carries a {@link #ticket} taken before it began, which is refused if anything was removed since.</li>
 * </ul>
 * It holds words, which is content; it is not a way round the Keystore. The checks that decide whether a message may be shown (the
 * sender is not blocked, the deadline has not passed, the phone is unlocked, the store is open) are made by the caller before it asks
 * for anything here, exactly as when the words are decrypted.
 */
final class MessageWords {
    /** What a row of the chat draws for a text message: what it says, and what it quotes. */
    record Words(String text, ChatEnvelope.Reference reference) {
        int weight() {
            return (text == null ? 0 : text.length()) + (reference == null || reference.snippet() == null ? 0 : reference.snippet().length());
        }
    }

    /** More than a phone holds live at once (see ChatEngine), so this is a bound on memory and not a limit that is reached. */
    static final int MOST_MESSAGES = 320;
    /** Characters, counting the words and what they quote, so that a chat of long messages cannot hold much memory. */
    static final int MOST_CHARACTERS = 400_000;
    /** What a message that has only just been put here is spared from {@link #retainOnly}: its record may not be visible yet. */
    static final long GRACE_MILLIS = 5_000;

    private record Held(byte[] sealed, long expiresAt, Words words, int weight, long addedAt) { }

    private final LinkedHashMap<UUID, Held> held = new LinkedHashMap<>(64, 0.75f, true);
    private final int mostMessages, mostCharacters;
    private int characters;
    private long removals;

    MessageWords() { this(MOST_MESSAGES, MOST_CHARACTERS); }

    MessageWords(int mostMessages, int mostCharacters) {
        this.mostMessages = mostMessages; this.mostCharacters = mostCharacters;
    }

    /**
     * The words of a message, when they are here and are the words of {@code sealed}, the record that is stored for it now, and it has
     * not expired. Anything else is a miss, and drops what was held.
     */
    synchronized Words get(UUID id, byte[] sealed, long now) {
        Held found = held.get(id);
        if (found == null) return null;
        if (sealed == null || found.expiresAt() <= now || !Arrays.equals(found.sealed(), sealed)) { drop(id, found); return null; }
        return found.words();
    }

    /** {@code sealed} is not changed afterwards by the caller; it is what the words are checked against. */
    synchronized void put(UUID id, byte[] sealed, long expiresAt, Words words, long now) {
        if (id == null || sealed == null || words == null) throw new IllegalArgumentException("A message, its sealed record and its words are required");
        Held previous = held.remove(id);
        if (previous != null) characters -= previous.weight();
        // A message costs a little whatever it says, so that very many empty ones are bounded too.
        int weight = words.weight() + 64;
        held.put(id, new Held(sealed, expiresAt, words, weight, now));
        characters += weight;
        Iterator<Map.Entry<UUID, Held>> eldest = held.entrySet().iterator();
        while ((held.size() > mostMessages || characters > mostCharacters) && eldest.hasNext()) {
            Map.Entry<UUID, Held> oldest = eldest.next();
            if (oldest.getKey().equals(id) && held.size() == 1) break;
            characters -= oldest.getValue().weight();
            eldest.remove();
        }
    }

    /**
     * Taken before the words of a message are read. A read that is under way when its message is erased can still finish after the key is
     * gone, so a {@link #put(UUID, byte[], long, Words, long, long)} that carries the ticket is refused when anything has been removed, or
     * everything cleared, since: what it read must not be kept.
     */
    synchronized long ticket() { return removals; }

    /** Keeps the words unless something was removed since {@code ticket} was taken; false when they were not kept. */
    synchronized boolean put(UUID id, byte[] sealed, long expiresAt, Words words, long now, long ticket) {
        if (ticket != removals) return false;
        put(id, sealed, expiresAt, words, now);
        return true;
    }

    synchronized void remove(UUID id) {
        // Counted even when nothing is held for it: a read of this message may be about to put its words.
        removals++;
        Held found = held.get(id);
        if (found != null) drop(id, found);
    }

    /** Drops the messages whose deadline has come. */
    synchronized int dropExpired(long now) {
        int dropped = 0;
        for (Iterator<Map.Entry<UUID, Held>> each = held.entrySet().iterator(); each.hasNext(); ) {
            Map.Entry<UUID, Held> next = each.next();
            if (next.getValue().expiresAt() > now) continue;
            characters -= next.getValue().weight(); each.remove(); dropped++;
        }
        return dropped;
    }

    /**
     * Drops the messages that are not among the ones the store holds, apart from those that were put here a moment ago (their record may
     * be in a commit that this listing cannot see yet). Whatever removed a record without telling this object is made good by this.
     */
    synchronized int retainOnly(Set<UUID> stored, long now) {
        int dropped = 0;
        for (Iterator<Map.Entry<UUID, Held>> each = held.entrySet().iterator(); each.hasNext(); ) {
            Map.Entry<UUID, Held> next = each.next();
            if (stored.contains(next.getKey()) || now - next.getValue().addedAt() < GRACE_MILLIS) continue;
            characters -= next.getValue().weight(); each.remove(); dropped++;
        }
        return dropped;
    }

    synchronized void clear() { removals++; held.clear(); characters = 0; }

    synchronized int size() { return held.size(); }

    synchronized int characters() { return characters; }

    synchronized boolean holds(UUID id) { return held.containsKey(id); }

    private void drop(UUID id, Held found) { held.remove(id); characters -= found.weight(); }
}
