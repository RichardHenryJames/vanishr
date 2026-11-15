package app.vanishr.android;

import app.vanishr.crypto.ChatEnvelope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which messages of a chat are drawn as one. Photos that were sent together as an album (the 12 and 24 hour modes) share a row, as in
 * other messengers; everything else is a row of its own. It is worked out from the messages alone, so that it can be tested without a
 * screen, and it never hides a message: a photo that cannot be part of an album stays a row of its own.
 */
final class Albums {
    private Albums() { }

    /** The most photos that go in one album when sending: the relay accepts six uploads a minute from a phone, so a bigger one would stall. */
    static final int MAX_SENT = 6;

    /** One row of the chat: a message, or the photos of one album (two or more, in the order they were sent). */
    record Row(ChatEngine.Entry first, List<ChatEngine.Entry> members) {
        boolean album() { return members.size() > 1; }

        /** The first photo, as the row's receipt and reactions show it: in the lowest state any photo is in, with the reactions of all. */
        ChatEngine.Entry summary() {
            if (!album()) return first;
            ChatEngine.Entry lowest = first;
            String mine = null, theirs = null;
            long deadline = Long.MAX_VALUE;
            for (ChatEngine.Entry member : members) {
                if (rank(member.state()) < rank(lowest.state())) lowest = member;
                if (mine == null) mine = member.reactionMine();
                if (theirs == null) theirs = member.reactionTheirs();
                deadline = Math.min(deadline, member.expiresAt());
            }
            return new ChatEngine.Entry(first.id(), first.peerId(), deadline, first.expiry(), first.outgoing(), first.image(), lowest.state(), first.keyOwner(),
                    first.groupEpoch(), first.groupRevision(), first.senderId(), first.edited(), mine, theirs, first.photo());
        }

        /** The message a reply to the row quotes, and the one a reaction first goes to. */
        ChatEngine.Entry head() { return members.get(0); }

        boolean holds(UUID id) {
            for (ChatEngine.Entry member : members) if (member.id().equals(id)) return true;
            return false;
        }
    }

    private record Key(UUID album, boolean outgoing, UUID sender) { }

    private static Key key(ChatEngine.Entry entry) { return new Key(entry.album().id(), entry.outgoing(), entry.senderId()); }

    private static long created(ChatEngine.Entry entry) { return entry.expiresAt() - entry.expiry().milliseconds; }

    // How far a message has got; anything that is not a known state (not sent, held) counts as the lowest.
    private static int rank(String state) {
        return switch (state) { case "READ" -> 3; case "DELIVERED" -> 2; case "QUEUED" -> 1; default -> 0; };
    }

    private static final Comparator<ChatEngine.Entry> TILE_ORDER = Comparator.<ChatEngine.Entry>comparingInt(entry -> entry.album().index())
            .thenComparingLong(Albums::created).thenComparing(ChatEngine.Entry::id);
    private static final Comparator<ChatEngine.Entry> ANCHOR = Comparator.<ChatEngine.Entry>comparingLong(Albums::created)
            .thenComparingInt(entry -> entry.album().index()).thenComparing(ChatEngine.Entry::id);

    /**
     * The rows of {@code entries}, which are in the order of the chat. An album takes the place of the first of its photos that is
     * there, and is named by the one sent first, so that it keeps its row when a photo of it arrives late. Photos of one album from
     * different senders, or from me and from someone else, are different albums. With {@code together} false (photos are switched off)
     * nothing is joined.
     */
    static List<Row> rows(List<ChatEngine.Entry> entries, boolean together) {
        Map<Key, List<ChatEngine.Entry>> found = new HashMap<>();
        if (together) for (ChatEngine.Entry entry : entries) if (entry.album() != null) found.computeIfAbsent(key(entry), key -> new ArrayList<>()).add(entry);
        Map<Key, Row> albums = new HashMap<>();
        Set<UUID> joined = new HashSet<>();
        for (Map.Entry<Key, List<ChatEngine.Entry>> each : found.entrySet()) {
            List<ChatEngine.Entry> members = new ArrayList<>(each.getValue());
            members.sort(TILE_ORDER);
            // A sender cannot make an album that hides a photo: whatever does not fit is a row of its own.
            if (members.size() > ChatEnvelope.MAX_ALBUM) members = new ArrayList<>(members.subList(0, ChatEnvelope.MAX_ALBUM));
            if (members.size() < 2) continue;
            ChatEngine.Entry first = members.stream().min(ANCHOR).orElseThrow();
            for (ChatEngine.Entry member : members) joined.add(member.id());
            albums.put(each.getKey(), new Row(first, List.copyOf(members)));
        }
        List<Row> rows = new ArrayList<>(entries.size());
        Set<Key> placed = new HashSet<>();
        for (ChatEngine.Entry entry : entries) {
            if (!joined.contains(entry.id())) { rows.add(new Row(entry, List.of(entry))); continue; }
            Key key = key(entry);
            if (placed.add(key)) rows.add(albums.get(key));
        }
        return rows;
    }
}
