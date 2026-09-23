package app.vanishr.android;

import android.app.Activity;
import android.content.MutableContextWrapper;
import android.os.CancellationSignal;
import androidx.credentials.*;
import androidx.credentials.exceptions.*;
import androidx.core.content.ContextCompat;
import com.google.android.libraries.identity.googleid.*;

import java.util.Collections;
import java.util.UUID;

final class GoogleSignIn {
    private static java.util.concurrent.CompletableFuture<Boolean> clearingSession;
    record Challenge(String id, String nonce, String clientId, long expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    record Start(UUID deviceId) { }
    enum Failure {
        CANCELLED("Google sign-in cancelled."),
        NO_ACCOUNT("No Google account is available. Add one in your phone settings and try again."),
        UNAVAILABLE("Google sign-in is unavailable. Check Google Play services and try again."),
        INVALID_RESPONSE("Google returned an invalid sign-in response. Try again."),
        REJECTED("Google sign-in could not be verified. Continue with Google to try again."),
        EXPIRED("Google sign-in expired. Try again."),
        FAILED("Google sign-in could not finish. Try again.");

        final String message;
        Failure(String message) { this.message = message; }
    }
    interface Callback { void token(String token); void failed(Failure failure); }

    static Failure failureFor(GetCredentialException failure) {
        if (failure instanceof GetCredentialCancellationException) return Failure.CANCELLED;
        if (failure instanceof NoCredentialException) return Failure.NO_ACCOUNT;
        if (failure instanceof GetCredentialProviderConfigurationException || failure instanceof GetCredentialUnsupportedException) return Failure.UNAVAILABLE;
        return Failure.FAILED;
    }

    static Challenge prepare(String origin) throws Exception {
        try (RelayApi api = new RelayApi(origin, null)) {
            return prepare(api);
        }
    }

    static Challenge prepare(RelayApi api) throws Exception {
        Challenge challenge = api.call("POST", "/auth/google/challenge", new Start(null), Challenge.class);
        if (challenge == null || !BuildConfig.GOOGLE_WEB_CLIENT_ID.equals(challenge.clientId())
                || challenge.id() == null || !challenge.id().matches("[A-Za-z0-9_-]{43}")
                || challenge.nonce() == null || !challenge.nonce().matches("[A-Za-z0-9_-]{43}")
                || challenge.expiresAt() <= System.currentTimeMillis()
                || challenge.expiresAt() > System.currentTimeMillis() + 330_000)
            throw new SecurityException("Invalid Google challenge");
        return challenge;
    }

    static CancellationSignal request(Activity activity, Challenge challenge, Callback callback) {
        CancellationSignal cancellation = new CancellationSignal();
        try {
            GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(challenge.clientId()).setNonce(challenge.nonce()).build();
            GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
            CredentialManager.create(activity).getCredentialAsync(new MutableContextWrapper(activity), request, cancellation,
                ContextCompat.getMainExecutor(activity), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse response) {
                        try {
                            if (!(response.getCredential() instanceof CustomCredential credential)
                                    || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                                callback.failed(Failure.INVALID_RESPONSE); return;
                            }
                            callback.token(GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken());
                        } catch (Exception failure) { callback.failed(Failure.INVALID_RESPONSE); }
                    }
                    @Override public void onError(GetCredentialException failure) {
                        callback.failed(failureFor(failure));
                    }
                });
        } catch (RuntimeException failure) { callback.failed(Failure.UNAVAILABLE); }
        return cancellation;
    }

    static synchronized void clear(Activity activity, java.util.function.Consumer<Boolean> completed) {
        var executor = ContextCompat.getMainExecutor(activity.getApplicationContext());
        if (clearingSession != null && !clearingSession.isDone()) {
            clearingSession.thenAcceptAsync(completed, executor);
            return;
        }
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        clearingSession = result;
        result.thenAcceptAsync(completed, executor);
        var handler = new android.os.Handler(android.os.Looper.getMainLooper());
        var cancellation = new CancellationSignal();
        handler.postDelayed(() -> { if (result.complete(false)) cancellation.cancel(); }, 10_000);
        try {
            CredentialManager.create(activity.getApplicationContext()).clearCredentialStateAsync(new ClearCredentialStateRequest(), cancellation,
                    executor, new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override public void onResult(Void ignored) { result.complete(true); }
                        @Override public void onError(ClearCredentialException failure) { result.complete(false); }
                    });
        } catch (RuntimeException failure) { result.complete(false); }
    }
}