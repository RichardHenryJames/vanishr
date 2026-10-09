package app.vanishr.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Non-secret backup timestamps. They live outside the vault because the upload service runs while the vault is closed.
 * No recovery key, snapshot digest or contact data is stored here. Writes are synchronous because the service stops
 * itself, and its process may be reclaimed, right after recording the result.
 */
@SuppressLint("ApplySharedPref")
final class BackupState {
    private BackupState() { }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("backup", Context.MODE_PRIVATE); }

    static long lastSuccess(Context context, UUID user) { return preferences(context).getLong(user + ".success", 0); }
    static long lastAttempt(Context context, UUID user) { return preferences(context).getLong(user + ".attempt", 0); }
    static boolean lastFailed(Context context, UUID user) { return preferences(context).getBoolean(user + ".failed", false); }

    static void attempted(Context context, UUID user, long now) {
        preferences(context).edit().putLong(user + ".attempt", now).putBoolean(user + ".failed", false).commit();
    }

    static void succeeded(Context context, UUID user, long now) {
        preferences(context).edit().putLong(user + ".success", now).putBoolean(user + ".failed", false).commit();
    }

    static void failed(Context context, UUID user) { preferences(context).edit().putBoolean(user + ".failed", true).commit(); }

    static void clear(Context context, UUID user) {
        preferences(context).edit().remove(user + ".success").remove(user + ".attempt").remove(user + ".failed").commit();
    }
}
