package app.vanishr.android;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import com.google.firebase.messaging.*;

public final class PushService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        sendBroadcast(new Intent("app.vanishr.android.PUSH_REFRESH").setPackage(getPackageName()));
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        if (!isWakeSignal(message)) return;
        if (!getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("notifications", false)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        manager.notify("vanishr-new", 1, genericNotification(this));
        }

        static boolean isWakeSignal(RemoteMessage message) {
        return message.getData().size() == 1 && "new_message".equals(message.getData().get("event"))
            && message.getNotification() == null;
        }

        static Notification genericNotification(Context context) {
        PendingIntent intent = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, "messages").setSmallIcon(R.drawable.ic_lock_keyhole)
                .setContentTitle("Vanishr").setContentText("New message").setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(60_000).setOnlyAlertOnce(true).setContentIntent(intent).setAutoCancel(true).build();
    }
}