package app.vanishr.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads one already-encrypted backup in a dataSync foreground service so it can finish if the user leaves the app.
 * The service never touches the vault or the recovery key; the result is recorded as non-secret timestamps only.
 */
public final class BackupService extends Service {
    static final String CHANNEL = "backup";
    static final String CANCEL = "app.vanishr.android.CANCEL_BACKUP";
    static final int NOTIFICATION = 42;
    private static AccountBackup.Prepared pending;
    private static volatile BackupService active;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile AccountBackup.Prepared running;

    static synchronized boolean busy() { return pending != null || active != null; }

    static synchronized void start(Context context, AccountBackup.Prepared prepared) {
        if (busy()) { prepared.close(); throw new IllegalStateException("A backup is already running"); }
        pending = prepared;
        try { ContextCompat.startForegroundService(context, new Intent(context, BackupService.class).setAction("START")); }
        catch (RuntimeException failure) { pending = null; prepared.close(); throw failure; }
    }

    static Notification notification(Context context) {
        PendingIntent cancel = PendingIntent.getService(context, 0, new Intent(context, BackupService.class).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = "Vanishr is active";
        Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_vanishr)
                .setContentTitle(title).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
        Notification.Builder builder = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_vanishr)
                .setContentTitle(title).setContentText("Uploading your encrypted backup").setPublicVersion(publicVersion)
                .setProgress(0, 0, true).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open).addAction(R.drawable.ic_x, "Cancel", cancel).setTimeoutAfter(300_000);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService() requires startForeground() promptly, even when there turns out to be nothing to do.
        Notification notification = notification(this);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, notification);
        if (intent != null && CANCEL.equals(intent.getAction())) { cancel(); return START_NOT_STICKY; }
        AccountBackup.Prepared prepared;
        synchronized (BackupService.class) {
            prepared = pending;
            if (prepared == null) { if (active == null) finish(); return START_NOT_STICKY; }
            pending = null;
            active = this;
        }
        running = prepared;
        worker.execute(() -> upload(prepared));
        return START_NOT_STICKY;
    }

    private void upload(AccountBackup.Prepared prepared) {
        UUID user = prepared.userId();
        try {
            prepared.upload();
            BackupState.succeeded(this, user, System.currentTimeMillis());
        } catch (Exception failure) {
            BackupState.failed(this, user);
        } finally {
            prepared.close();
            finish();
        }
    }

    private void cancel() {
        AccountBackup.Prepared current = running;
        if (current != null) current.cancel();
        else finish();
    }

    private void finish() {
        if (!stopping.compareAndSet(false, true)) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) { cancel(); finish(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        AccountBackup.Prepared current = running;
        if (current != null) current.cancel();
        worker.shutdown();
        synchronized (BackupService.class) {
            if (active == this) active = null;
            if (pending != null) { pending.close(); pending = null; }
        }
        super.onDestroy();
    }
}
