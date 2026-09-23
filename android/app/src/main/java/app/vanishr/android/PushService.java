package app.vanishr.android;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import com.google.firebase.messaging.*;

public final class PushService extends FirebaseMessagingService {
    static final String OPEN_NOTIFICATION = "app.vanishr.android.OPEN_NOTIFICATION";
    static final String REFERENCE = "notification-reference";
    static final String DEADLINE = "notification-deadline";
    static final long ROUTE_LIFETIME = 300_000L;

    static boolean notificationsEnabled(Context context) {
        return context.getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("notifications", true);
    }

    static boolean notificationsActive(Context context) {
        return notificationsEnabled(context) && context.getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("push-active", false);
    }

    static boolean claimPermissionPrompt(Context context, boolean explicit) {
        var preferences = context.getSharedPreferences("preferences", MODE_PRIVATE);
        if (!notificationsEnabled(context) || !explicit && preferences.getBoolean("notification-permission-requested", false)) return false;
        return preferences.edit().putBoolean("notification-permission-requested", true).commit();
    }

    @Override public void onNewToken(String token) {
        sendBroadcast(new Intent("app.vanishr.android.PUSH_REFRESH").setPackage(getPackageName()));
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        if (!isWakeSignal(message)) return;
        if (!notificationsActive(this)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        manager.notify("vanishr-new", 1, genericNotification(this, message.getData().get("reference")));
        }

        static boolean isWakeSignal(RemoteMessage message) {
        return (message.getData().size() == 1 || message.getData().size() == 2 && validReference(message.getData().get("reference")))
            && "new_message".equals(message.getData().get("event"))
            && message.getNotification() == null;
        }

    static boolean validReference(String reference) { return reference != null && reference.matches("[A-Za-z0-9_-]{43}"); }

    static Intent notificationIntent(Context context, String reference) {
        if (reference != null && !validReference(reference)) throw new IllegalArgumentException("Invalid notification reference");
        Intent intent = new Intent(context, MainActivity.class).setAction(OPEN_NOTIFICATION)
                .setData(new android.net.Uri.Builder().scheme("vanishr-notification").authority("open").appendPath(reference == null ? "generic" : reference).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (reference != null) intent.putExtra(REFERENCE, reference).putExtra(DEADLINE, System.currentTimeMillis() + ROUTE_LIFETIME);
        return intent;
    }

        static Notification genericNotification(Context context) {
        return genericNotification(context, null);
    }

    static Notification genericNotification(Context context, String reference) {
        PendingIntent intent = PendingIntent.getActivity(context, 0, notificationIntent(context, reference), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, "messages").setSmallIcon(R.drawable.ic_lock_keyhole)
                .setContentTitle("Vanishr").setContentText("New message").setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(60_000).setOnlyAlertOnce(true).setContentIntent(intent).setAutoCancel(true).build();
    }
}