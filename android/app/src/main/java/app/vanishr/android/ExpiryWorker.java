package app.vanishr.android;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public final class ExpiryWorker extends Worker {
    public ExpiryWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() {
        try { AndroidVault.expireContentKeys(System.currentTimeMillis()); return Result.success(); }
        catch (Exception failure) { return Result.retry(); }
    }

    public static void schedule(Context context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("erase-expired-content-keys", ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(ExpiryWorker.class, 15, TimeUnit.MINUTES).build());
    }
}