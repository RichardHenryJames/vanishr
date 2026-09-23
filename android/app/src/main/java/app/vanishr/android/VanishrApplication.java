package app.vanishr.android;

import android.app.Application;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public final class VanishrApplication extends Application implements androidx.work.Configuration.Provider {
    @androidx.annotation.NonNull
    @Override public androidx.work.Configuration getWorkManagerConfiguration() {
        return new androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build();
    }

    @Override public void onCreate() {
        super.onCreate();
        ExpiryWorker.schedule(this);
        if (pushConfigured() && FirebaseApp.getApps(this).isEmpty()) {
            FirebaseOptions options = new FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build();
            FirebaseApp.initializeApp(this, options);
        }
    }

    static boolean pushConfigured() {
        return !BuildConfig.FIREBASE_APP_ID.isEmpty() && !BuildConfig.FIREBASE_API_KEY.isEmpty()
                && !BuildConfig.FIREBASE_PROJECT_ID.isEmpty() && !BuildConfig.FIREBASE_SENDER_ID.isEmpty();
    }
}