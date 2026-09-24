package app.vanishr.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhotoSharingService extends Service {
    static final String CHANNEL = "photo-sharing";
    static final String END = "app.vanishr.android.END_PHOTO_ACCESS";
    static final int NOTIFICATION = 41;
    private static RemotePhotoSession.Prepared pending;
    private static volatile PhotoSharingService active;
    private static volatile RemotePhotoSession latest;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private RemotePhotoSession session;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (session == null || !session.approvedOwner()) end("Photo access ended when the phone locked");
        }
    };

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static boolean phoneUnlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure() && !keyguard.isDeviceLocked();
    }
    static boolean phonePermitsSession(Context context, RemotePhotoSession session) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure() && (!keyguard.isDeviceLocked() || session.approvedOwner());
    }
    static synchronized boolean busy() { return pending != null || active != null; }
    static RemotePhotoSession current(String id) { RemotePhotoSession value = latest; return value != null && value.id().toString().equals(id) ? value : null; }

    static synchronized void start(Context context, RemotePhotoSession.Prepared prepared) {
        if (busy() || !phoneUnlocked(context) || !notificationsAllowed(context) || prepared.owner && !PhotoLibrary.permitted(context)) {
            prepared.close(); throw new IllegalStateException("Photo sharing needs an unlocked phone and notification/photo permission");
        }
        pending = prepared;
        try { ContextCompat.startForegroundService(context, new Intent(context, PhotoSharingService.class).setAction("START").putExtra("session", prepared.id.toString())); }
        catch (RuntimeException failure) { pending = null; prepared.close(); throw failure; }
    }

    static void endFor(UUID account, UUID peer) {
        PhotoSharingService service = active;
        if (service != null && service.session != null && service.session.userId().equals(account)
                && (peer == null || service.session.peerId().equals(peer))) service.end("Photo access ended");
        synchronized (PhotoSharingService.class) {
            if (pending != null && pending.own.userId().equals(account) && (peer == null || pending.peer.userId().equals(peer))) { pending.close(); pending = null; }
        }
    }

    static Intent endIntent(Context context, UUID id) {
        return new Intent(context, PhotoSharingService.class).setAction(END)
                .setData(new Uri.Builder().scheme("vanishr-photos").authority("end").appendPath(id.toString()).build());
    }

    static Notification notification(Context context, RemotePhotoSession session) {
        PendingIntent end = PendingIntent.getService(context, 0, endIntent(context, session.id()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = session.owner() ? new Intent(context, MainActivity.class)
            : new Intent(context, RemotePhotosActivity.class).putExtra("session", session.id().toString());
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = "Vanishr is active";
        Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_image)
            .setContentTitle(title).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(content).setDeleteIntent(end).addAction(R.drawable.ic_x, "End access", end).build();
        Notification.Builder builder = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_image)
            .setContentTitle(title).setPublicVersion(publicVersion)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setDeleteIntent(end)
            .setContentIntent(content)
                .addAction(R.drawable.ic_x, "End access", end).setTimeoutAfter(RemotePhotoSession.LIFETIME);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Photo sharing", NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && END.equals(intent.getAction())) {
            if (session != null && intent.getData() != null && session.id().toString().equals(intent.getData().getLastPathSegment())) end("Photo access ended");
            else if (session == null) stopSelf();
            return START_NOT_STICKY;
        }
        RemotePhotoSession.Prepared prepared;
        synchronized (PhotoSharingService.class) {
            prepared = pending;
            if (intent == null || prepared == null || !prepared.id.toString().equals(intent.getStringExtra("session")) || active != null) { stopSelf(); return START_NOT_STICKY; }
            pending = null;
            session = new RemotePhotoSession(this, prepared); latest = session; active = this;
        }
        try {
            if (!notificationsAllowed(this) || !phoneUnlocked(this) || session.owner() && !PhotoLibrary.permitted(this)) throw new SecurityException("Sharing unavailable");
            Notification notification = notification(this, session);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification);
            session.connect();
            long connectedBy = SystemClock.elapsedRealtime() + 15_000;
            worker.scheduleWithFixedDelay(() -> {
                if (stopping.get()) return;
                try {
                    if (!phonePermitsSession(this, session) || !notificationsAllowed(this) || session.owner() && !PhotoLibrary.permitted(this)) { end("Photo access ended"); return; }
                    if (SystemClock.elapsedRealtime() > connectedBy && session.status().equals("Connecting...")) { end("The other phone is unavailable"); return; }
                    session.tick();
                } catch (RelayApi.ApiFailure failure) { end(failure.status == 410 ? "Photo access ended" : failure.userMessage()); }
                catch (Exception failure) { end("Photo access ended. The connection or photo permission may be unavailable."); }
            }, 0, 200, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) { end("Photo sharing could not start. Check Android permissions."); }
        return START_NOT_STICKY;
    }

    void end(String reason) {
        if (!stopping.compareAndSet(false, true)) return;
        if (session != null) {
            session.requestStop();
            new Thread(() -> { try { session.cancelTransport(); } catch (RuntimeException ignored) { } }, "vanishr-photo-cancel").start();
        }
        worker.execute(() -> {
            try { if (session != null) session.end(reason); }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        });
        worker.shutdown();
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) { end("Android ended photo sharing"); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        unregisterReceiver(screenOff);
        end("Photo access ended");
        synchronized (PhotoSharingService.class) { if (active == this) active = null; }
        super.onDestroy();
    }
}