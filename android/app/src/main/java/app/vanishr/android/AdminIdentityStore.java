package app.vanishr.android;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers the newest admin identity certificate that was accepted. It is public and signed, so it is kept exactly as it came and is
 * checked again, signature included, every time it is loaded: a copy that was altered on the phone is simply not used. Nothing about the
 * person or the phone is stored here.
 *
 * Every screen of the app (and so every request that is in flight) shares this one process, and everything that has to be checked and
 * written together is done holding one lock, so two requests that overlap cannot put an older certificate over a newer one.
 */
final class AdminIdentityStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    AdminIdentityStore(SharedPreferences preferences) { this.preferences = preferences; }

    /** True when a certificate is stored at all, so that a phone that never needed one does no work for it. */
    boolean present() { return preferences.getString("envelope", null) != null; }

    /**
     * The highest number ever taken, at least the build's floor. It is kept next to the certificate and outlives it: a stored copy that can
     * no longer be verified (a key the app no longer lists, say) must not lower the bar for what is taken next.
     */
    long highWater() { return Math.max(AdminIdentity.BUILD_SEQUENCE_FLOOR, preferences.getLong("sequence", 0)); }

    /**
     * True, with the time of this request recorded, when none was recorded in the last {@code interval} milliseconds, however often the app
     * was restarted since. It is checked and recorded together, so that two screens cannot both be told yes, and it is recorded before the
     * request, so that a request that fails or is cut short counts too. A clock that went back counts as due; a record that cannot be written
     * counts as not due.
     */
    boolean reserveAttempt(long now, long interval) {
        synchronized (LOCK) {
            long last = preferences.getLong("last-attempt", 0);
            if (!(last <= 0 || now < last || now - last >= interval)) return false;
            return preferences.edit().putLong("last-attempt", now).commit();
        }
    }

    /**
     * The stored certificate if it still verifies, is above this build's floor and is the highest one ever taken, else null (the built-in pin
     * then stands alone, and the next certificate that is at least as high as the highest taken is followed).
     */
    AdminIdentity.Certificate load(Map<String, PublicKey> trusted, UUID admin) {
        synchronized (LOCK) {
            String stored = preferences.getString("envelope", null);
            if (stored == null) return null;
            try {
                AdminIdentity.Certificate certificate = AdminIdentity.verify(stored.getBytes(StandardCharsets.UTF_8), trusted, admin);
                if (certificate.sequence() <= AdminIdentity.BUILD_SEQUENCE_FLOOR) {
                    // Kept by an earlier version, and not above what this version already knows: it can never matter again, because a floor only rises.
                    preferences.edit().remove("envelope").apply();
                    return null;
                }
                // Older than the highest number taken: not the certificate that was taken last, so it is not the one to follow.
                return certificate.sequence() < highWater() ? null : certificate;
            } catch (AdminIdentity.Rejected rejected) { return null; }
        }
    }

    /**
     * Keeps the envelope and its number, unless a higher number was taken in the meantime (false then, and nothing changes); true only if both
     * were written. The caller has verified the envelope and checked {@link AdminIdentity#newer}, and this is the check that holds when two
     * requests did that at the same time.
     */
    boolean save(byte[] envelope, long sequence) {
        synchronized (LOCK) {
            long known = preferences.getLong("sequence", 0);
            if (sequence < known) return false;
            return preferences.edit().putString("envelope", new String(envelope, StandardCharsets.UTF_8)).putLong("sequence", sequence).commit();
        }
    }
}
