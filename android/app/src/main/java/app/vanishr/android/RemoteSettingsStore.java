package app.vanishr.android;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;

/**
 * Remembers the last signed settings file that was accepted. The file is public and signed, so it is kept exactly as it came and is
 * checked again, signature and expiry included, every time it is loaded; a file that was altered on the phone is simply not used.
 * Nothing about the person or the phone is stored here: only that file, the highest sequence number seen for each signing key, the
 * newest issue time seen, the time of the last check and which notice and which update hint were dismissed.
 */
final class RemoteSettingsStore {
    private final SharedPreferences preferences;

    RemoteSettingsStore(SharedPreferences preferences) { this.preferences = preferences; }

    RemoteSettings.Settings load(Map<String, PublicKey> trusted, long now) {
        String stored = preferences.getString("envelope", null);
        if (stored == null) return RemoteSettings.Settings.NONE;
        try {
            RemoteSettings.Settings settings = RemoteSettings.verify(stored.getBytes(StandardCharsets.UTF_8), trusted, now);
            if (settings.issuedAt() >= RemoteSettings.BUILD_ISSUED_FLOOR) return settings;
            // Kept by an earlier version and issued before this version's floor: it can never be used by this version or a later one (a
            // floor only rises), so it is dropped, and the next check is due at once rather than up to a day from now, so that a newer
            // file is picked up without the wait. Dropping it is also what keeps that from happening on every start.
            preferences.edit().remove("envelope").putLong("last-check", 0).apply();
            return RemoteSettings.Settings.NONE;
        } catch (RemoteSettings.Rejected rejected) { return RemoteSettings.Settings.NONE; }
    }

    /**
     * Takes the file if it is newer than what was taken before (see {@link RemoteSettings#newer}); the time of the check is recorded
     * either way. The sequence floor belongs to the key: a key that has been retired, or that signed an absurdly high number, cannot
     * hold back the key that replaces it. The issue time is kept across keys, so that an old file of one key cannot be put back over
     * a newer file of another, but it is kept no later than this phone's own clock: a file dated in the future, which is allowed a
     * day and a half of clock difference, cannot make the phone refuse the files that really follow it.
     */
    boolean accept(byte[] envelope, RemoteSettings.Settings settings, long now) {
        boolean newer = RemoteSettings.newer(settings, floor(settings.keyId()), newestIssued());
        SharedPreferences.Editor edit = preferences.edit().putLong("last-check", now);
        if (newer) edit.putString("envelope", new String(envelope, StandardCharsets.UTF_8))
                .putLong("floor." + settings.keyId(), settings.sequence())
                .putLong("issued-high", Math.max(newestIssued(), Math.min(settings.issuedAt(), now)));
        return edit.commit() && newer;
    }

    /** A check that failed is tried again in two hours rather than tomorrow. */
    void failed(long now) {
        preferences.edit().putLong("last-check", now - RemoteSettings.CHECK_INTERVAL + RemoteSettings.RETRY_INTERVAL).apply();
    }

    long lastCheck() { return preferences.getLong("last-check", 0); }

    long floor(String keyId) { return Math.max(0, preferences.getLong("floor." + keyId, 0)); }

    long newestIssued() { return Math.max(0, preferences.getLong("issued-high", 0)); }

    String dismissedNotice() { return preferences.getString("dismissed-notice", ""); }

    void dismissNotice(String id) { preferences.edit().putString("dismissed-notice", id).apply(); }

    /**
     * The hint about exactly this version was dismissed. Equality, not "at or below": whatever the owner publishes next is shown
     * again, so a hint published with a mistyped version, once dismissed, does not silence the corrected one.
     */
    boolean updateDismissed(long latestVersionCode) { return latestVersionCode == dismissedUpdate(); }

    long dismissedUpdate() { return Math.max(0, preferences.getLong("dismissed-update", 0)); }

    void dismissUpdate(long latestVersionCode) { preferences.edit().putLong("dismissed-update", latestVersionCode).apply(); }
}
