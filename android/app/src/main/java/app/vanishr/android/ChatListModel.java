package app.vanishr.android;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the chat list shows about a chat, worked out from the stored message records alone: whether something is new, of what kind, when the
 * newest message came, whether it was delivered, when the next one vanishes. Never from words: nothing here is decrypted, and the list
 * still shows no message text. Plain Java, so that the rules can be tested without a phone.
 */
final class ChatListModel {
    private ChatListModel() { }

    /** How many chats may be pinned at once. */
    static final int MAX_PINNED = 3;
    /** The countdown to the next message that vanishes is shown only in its last hour. */
    static final long COUNTDOWN_MILLIS = 3_600_000L;

    /** How far an outgoing message got, in the order of the chat's own receipt marks. A message that was not sent is the least far. */
    enum Delivery {
        FAILED, NONE, SENDING, SENT, DELIVERED, READ;

        private static final java.util.regex.Pattern GROUP = java.util.regex.Pattern.compile("(\\d{1,6}) delivered / (\\d{1,6}) of (\\d{1,6}) read");

        static Delivery of(String state) {
            String value = state == null ? "" : state;
            if (value.startsWith("Not sent")) return FAILED;
            // What a group message says about how many of its recipients it reached.
            java.util.regex.Matcher group = GROUP.matcher(value);
            if (group.matches()) {
                long delivered = Long.parseLong(group.group(1)), read = Long.parseLong(group.group(2)), recipients = Long.parseLong(group.group(3));
                if (recipients > 0 && read >= recipients) return READ;
                return delivered > 0 ? DELIVERED : SENT;
            }
            return switch (value) {
                case "READ" -> READ;
                case "DELIVERED" -> DELIVERED;
                case "QUEUED" -> SENT;
                case "PENDING" -> SENDING;
                default -> NONE;
            };
        }
    }

    /** The small picture in front of the second line. */
    enum Icon { NONE, PHOTO, VIEW_ONCE, SENDING, SENT, DELIVERED, READ, FAILED }

    enum Filter { ALL, UNREAD, GROUPS, MUTED }

    /** One live message reduced to what the list may know about it. */
    record Message(long createdAt, long expiresAt, boolean outgoing, boolean photo, boolean viewOnce, String state) { }

    /** What is known about one chat. {@code unread} counts incoming messages that are not read yet; the "newest" fields describe the newest live message. */
    record Summary(int live, long unread, long unreadPhotos, boolean newestUnreadPhoto, boolean newestUnreadViewOnce, long newestAt,
                   boolean newestOutgoing, boolean newestPhoto, boolean newestViewOnce, Delivery newestDelivery, long nextExpiryAt) {
        static final Summary EMPTY = new Summary(0, 0, 0, false, false, 0, false, false, false, Delivery.NONE, 0);
    }

    /** Builds a summary one message at a time, in any order and with the same result. */
    static final class Builder {
        private int live;
        private long unread, unreadPhotos;
        private Message newest, newestUnread;
        private long nextExpiryAt = Long.MAX_VALUE;

        Builder add(Message message) {
            live++;
            if (newest == null || newer(message, newest)) newest = message;
            if (!message.outgoing() && !"READ".equals(message.state())) {
                unread++;
                if (message.photo()) unreadPhotos++;
                if (newestUnread == null || newer(message, newestUnread)) newestUnread = message;
            }
            nextExpiryAt = Math.min(nextExpiryAt, message.expiresAt());
            return this;
        }

        Summary build() {
            if (live == 0) return Summary.EMPTY;
            return new Summary(live, unread, unreadPhotos, newestUnread != null && newestUnread.photo(), newestUnread != null && newestUnread.viewOnce(),
                    newest.createdAt(), newest.outgoing(), newest.photo(), newest.viewOnce(), Delivery.of(newest.state()), nextExpiryAt);
        }

        // Later wins; for the same moment the order is by what the message is, never by which was added first. Of two receipts for the same
        // moment (the photos of one album) the one that got the least far wins, so that a mark never promises more than all of them got.
        private static boolean newer(Message candidate, Message current) {
            if (candidate.createdAt() != current.createdAt()) return candidate.createdAt() > current.createdAt();
            if (candidate.viewOnce() != current.viewOnce()) return candidate.viewOnce();
            if (candidate.photo() != current.photo()) return candidate.photo();
            if (candidate.outgoing() != current.outgoing()) return candidate.outgoing();
            return Delivery.of(candidate.state()).compareTo(Delivery.of(current.state())) < 0;
        }
    }

    /** The second line of a row. {@code emphasis} is for what is new. */
    record Line(Icon icon, String text, boolean emphasis) { }

    static Line line(Summary summary) {
        if (summary.unread() > 0) {
            if (summary.unread() == 1) {
                if (summary.newestUnreadViewOnce()) return new Line(Icon.VIEW_ONCE, summary.newestUnreadPhoto() ? "New view-once photo" : "New view-once message", true);
                if (summary.newestUnreadPhoto()) return new Line(Icon.PHOTO, "New photo", true);
                return new Line(Icon.NONE, "New message", true);
            }
            if (summary.unreadPhotos() == summary.unread()) return new Line(Icon.PHOTO, summary.unread() + " new photos", true);
            return new Line(Icon.NONE, summary.unread() + " new messages", true);
        }
        if (summary.live() == 0) return new Line(Icon.NONE, "No messages", false);
        // A message of one's own that was not sent is said so: it is the one thing in the list that is waiting for the person.
        if (summary.newestOutgoing() && summary.newestDelivery() == Delivery.FAILED) return new Line(Icon.FAILED, "Not sent", true);
        String kind = summary.newestViewOnce() ? "View once" : summary.newestPhoto() ? "Photo" : "Message";
        Icon icon;
        if (summary.newestOutgoing()) {
            icon = switch (summary.newestDelivery()) {
                case READ -> Icon.READ;
                case DELIVERED -> Icon.DELIVERED;
                case SENT -> Icon.SENT;
                case SENDING -> Icon.SENDING;
                case FAILED, NONE -> Icon.NONE;
            };
        } else icon = summary.newestViewOnce() ? Icon.VIEW_ONCE : summary.newestPhoto() ? Icon.PHOTO : Icon.NONE;
        return new Line(icon, kind, false);
    }

    /** True for a chat that shows as unread: it has incoming messages not read yet, or the person marked it unread. */
    static boolean unread(Summary summary, boolean marked) {
        return summary.unread() > 0 || marked;
    }

    /** The time beside a name: the clock time today, "Yesterday", the weekday for the days before, then the date. Empty when there is no message. */
    static String time(long at, long now, ZoneId zone, Locale locale) {
        if (at <= 0) return "";
        LocalDate day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
        if (ago <= 0) return DateTimeFormatter.ofPattern("HH:mm", locale).format(java.time.Instant.ofEpochMilli(at).atZone(zone));
        if (ago == 1) return "Yesterday";
        if (ago < 7) { DayOfWeek weekday = day.getDayOfWeek(); return weekday.getDisplayName(TextStyle.SHORT, locale); }
        return DateTimeFormatter.ofPattern(day.getYear() == today.getYear() ? "d MMM" : "d MMM yyyy", locale).format(day);
    }

    /** "38 min" in the last hour before the next message of a chat vanishes, "<1 min" in its last minute, and nothing before or after that. */
    static String countdown(long nextExpiryAt, long now) {
        if (nextExpiryAt <= 0) return "";
        long left = nextExpiryAt - now;
        if (left <= 0 || left > COUNTDOWN_MILLIS) return "";
        if (left < 60_000) return "<1 min";
        return (left / 60_000) + " min";
    }

    /** What a screen reader says for {@link #countdown}: the same moment, in words. */
    static String countdownSpoken(long nextExpiryAt, long now) {
        if (countdown(nextExpiryAt, now).isEmpty()) return "";
        long left = nextExpiryAt - now;
        if (left < 60_000) return "The next message vanishes in under a minute";
        long minutes = left / 60_000;
        return "The next message vanishes in " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** One chat as the order needs to know it. */
    record Row(UUID id, boolean pinned, long newestAt) { }

    /**
     * The order of the list. Pinned chats come first. With details the others follow by the newest message, latest first, and chats with no
     * message last; without details the order given is kept (the plain list: groups, then contacts, each by name). Chats that tie keep the
     * order they were given in, so the list never shuffles by itself.
     */
    static List<Row> order(List<Row> rows, boolean details) {
        List<Row> sorted = new ArrayList<>(rows);
        Comparator<Row> pinnedFirst = Comparator.comparing(row -> !row.pinned());
        if (details) {
            Comparator<Row> latestFirst = Comparator.comparingLong((Row row) -> row.newestAt() <= 0 ? Long.MIN_VALUE : row.newestAt()).reversed();
            sorted.sort(pinnedFirst.thenComparing(latestFirst));
        } else sorted.sort(pinnedFirst);
        return sorted;
    }

    static boolean passes(Filter filter, boolean group, boolean unread, boolean muted) {
        return switch (filter) {
            case ALL -> true;
            case UNREAD -> unread;
            case GROUPS -> group;
            case MUTED -> muted;
        };
    }

    /** Whether a chat may be pinned when {@code pinned} are pinned now. A chat that is pinned already always may be (to stay so). */
    static boolean canPin(int pinned, boolean alreadyPinned) {
        return alreadyPinned || pinned < MAX_PINNED;
    }
}
