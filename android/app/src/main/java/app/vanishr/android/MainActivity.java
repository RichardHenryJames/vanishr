package app.vanishr.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import app.vanishr.crypto.ChatEnvelope;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
    private static final int GALLERY = 11;
    private static final int CAMERA = 12;
    private static final int NOTIFICATIONS = 13;
    private static final int INK = Ui.INK;
    private static final int GREEN = Ui.PRIMARY;
    private static final int MUTED = Ui.MUTED;
    private Ui design;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService updateWork = Executors.newSingleThreadExecutor();
    private boolean checkingUpdates;
    private AppUpdates.Release availableUpdate;
    private volatile ChatEngine engine;
    private volatile boolean resumed;
    private int loadingGeneration = -1;
    private String storageError;
    private boolean phoneSecurityRequired;
    private boolean waitingForPhoneUnlock;
    private boolean busy;
    private boolean signingUp;
    private boolean deviceReplacementRequired;
    private volatile int screenGeneration;
    private LinearLayout root;
    private LinearLayout messages;
    private TextView connection;
    private TextView conversationName;
    private TextView conversationUsername;
    private EditText composer;
    private UUID selectedPeer;
    private ChatEnvelope.Expiry expiry = ChatEnvelope.Expiry.HOUR_1;
    private AlertDialog openDialog;
    private Bitmap displayedBitmap;
    private ScrollView messageScroll;
    private java.util.List<ChatEngine.Entry> shownEntries = java.util.List.of();
    private java.util.List<ChatEngine.Peer> shownPeers = java.util.List.of();
    private TextView expiryLabel;
    private TextView actionError;
    private EditText contactSearch;
    private LinearLayout contactRows;
    private boolean chatScreen;
    private final java.util.List<PendingSend> pendingSends = new ArrayList<>();
    private static final class PendingSend {
        final UUID id = UUID.randomUUID();
        final ChatEngine.Peer peer;
        final ChatEnvelope.Expiry expiry;
        final long createdAt = System.currentTimeMillis();
        String text;
        byte[] image;
        boolean failed;
        volatile boolean cancelled;
        View row;
        PendingSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry expiry) {
            this.peer = peer; this.text = text; this.image = image; this.expiry = expiry;
        }
        long expiresAt() { return createdAt + expiry.milliseconds; }
        synchronized byte[] copyImage() { return image == null ? null : image.clone(); }
        synchronized void clear() { cancelled = true; text = null; if (image != null) Arrays.fill(image, (byte) 0); image = null; row = null; }
    }
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickImage = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                selectedImage = uri;
                if (engine == null) load(); else importPendingImage();
            });
    private GoogleAttempt googleAttempt;
    private CancellationSignal googleCancellation;
    private GoogleSignIn.Failure googleFailure;
    private static final class GoogleAttempt {
        final String origin;
        final boolean replace;
        final GoogleSignIn.Challenge challenge;
        String idToken;
        GoogleAttempt(String origin, boolean replace, GoogleSignIn.Challenge challenge) { this.origin = origin; this.replace = replace; this.challenge = challenge; }
    }
    private final Map<UUID, TextView> unreadLabels = new HashMap<>();
    private Uri selectedImage;
    private byte[] cameraImage;
    private final BroadcastReceiver pushRefresh = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { registerPush(); }
    };
    private final BroadcastReceiver phoneUnlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) load();
        }
    };
    private final Runnable expiryTick = new Runnable() {
        @Override public void run() {
            resumeAfterPhoneUnlock();
            pendingSends.removeIf(pending -> {
                if (pending.expiresAt() > System.currentTimeMillis()) return false;
                if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                pending.clear();
                return true;
            });
            if (resumed && messages != null) {
                for (int index = messages.getChildCount() - 1; index >= 0; index--) {
                    View row = messages.getChildAt(index);
                    if (row.getTag() instanceof Long deadline && deadline <= System.currentTimeMillis()) messages.removeViewAt(index);
                }
            }
            if (!isDestroyed()) ui.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(null);
        design = new Ui(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (selectedPeer != null && engine != null) { selectedPeer = null; render(); }
                else { hideContent(); finish(); }
            }
        });
        ContextCompat.registerReceiver(this, pushRefresh, new IntentFilter("app.vanishr.android.PUSH_REFRESH"), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, phoneUnlocked, new IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        root = vertical();
        root.setBackgroundColor(Ui.CANVAS);
        root.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setSaveFromParentEnabled(false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var padding = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout() | androidx.core.view.WindowInsetsCompat.Type.ime());
            root.setPadding(dp(20) + padding.left, dp(8) + padding.top, dp(20) + padding.right, dp(8) + padding.bottom);
            return insets;
        });
        setContentView(root);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        work.scheduleWithFixedDelay(this::syncOnce, 2, 15, TimeUnit.SECONDS);
        ui.post(expiryTick);
        storageState();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setSaveEnabled(false); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView label(String value, int size, int color) {
        TextView text = design.text(value, size, 400, color);
        text.setPadding(0, dp(6), 0, dp(6));
        return text;
    }

    private Button command(String text, Runnable action) {
        return design.button(text, false, action);
    }

    private ImageButton icon(int image, String name, Runnable action) {
        return design.icon(image, name, action);
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setLetterSpacing(0);
        input.setTypeface(design.font(500));
        input.setInputType(type);
        input.setFontVariationSettings("'wght' 500");
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilterTouchesWhenObscured(true);
        return input;
    }

    private void title(String name, boolean back) {
        LinearLayout heading = horizontal();
        if (back) heading.addView(icon(android.R.drawable.ic_media_previous, "Back", () -> { selectedPeer = null; render(); }));
        TextView title = design.text(name, 26, 800, INK);
        title.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(64), 1));
        if (engine != null && engine.authenticated()) {
            TextView profile = design.avatar(engine.displayName(), 40, Ui.BLUE);
            profile.setContentDescription("Profile"); profile.setTooltipText("Profile");
            profile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            profile.setFocusable(true); profile.setFilterTouchesWhenObscured(true);
            profile.setOnClickListener(view -> accountDialog());
            LinearLayout.LayoutParams profileSize = new LinearLayout.LayoutParams(dp(48), dp(48));
            profileSize.setMarginEnd(dp(8)); heading.addView(profile, profileSize);
        }
        root.addView(heading);
    }

    private void storageState() {
        chatScreen = false;
        root.removeAllViews();
        title("vanishr", false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout center = vertical();
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(12), dp(24), dp(12), dp(24));
        if (storageError == null) center.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(40), dp(40)));
        else center.addView(design.symbol(R.drawable.ic_circle_alert, 40, Ui.ERROR));
        center.addView(design.spacer(20));
        TextView message = design.text(storageError == null ? "Opening your chats..." : storageError, 16, 500, MUTED);
        message.setGravity(Gravity.CENTER);
        center.addView(message, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(center);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (storageError != null) {
            if (phoneSecurityRequired) root.addView(command("Phone security", () -> startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))));
            else root.addView(command("Try again", this::load));
            root.addView(design.spacer(12));
        }
        MaterialButton legal = design.button("Licenses & source", false, this::legalNotices);
        legal.setStrokeWidth(0);
        legal.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        legal.setTextColor(MUTED);
        root.addView(legal);
        root.addView(design.spacer(8));
    }

    private void legalNotices() {
        try (java.io.InputStream input = getAssets().open("legal/NOTICES.txt")) {
            String notices = new String(AndroidVault.boundedRead(input, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            ScrollView scroll = new ScrollView(this);
            TextView text = label(notices, 13, INK);
            text.setPadding(dp(18), dp(8), dp(18), dp(8));
            scroll.addView(text);
            showDialog(new AlertDialog.Builder(this).setTitle("Licenses & source").setView(scroll)
                    .setNeutralButton("AGPL v3", (dialog, which) -> licenseText()).setNegativeButton("Close", null).create());
        } catch (Exception failure) { problem("License notices are unavailable in this build."); }
    }

    private void licenseText() {
        try (java.io.InputStream input = getAssets().open("legal/LICENSE.txt")) {
            String license = new String(AndroidVault.boundedRead(input, 100_000), java.nio.charset.StandardCharsets.UTF_8);
            ScrollView scroll = new ScrollView(this);
            TextView text = label(license, 13, INK);
            text.setPadding(dp(18), dp(8), dp(18), dp(8));
            scroll.addView(text);
            showDialog(new AlertDialog.Builder(this).setTitle("GNU AGPL v3").setView(scroll).setNegativeButton("Close", null).create());
        } catch (Exception failure) { problem("License text is unavailable in this build."); }
    }

    private void load() {
        if (!resumed || isDestroyed() || engine != null || loadingGeneration == screenGeneration) return;
        int generation = screenGeneration;
        loadingGeneration = generation;
        storageError = null; phoneSecurityRequired = false; waitingForPhoneUnlock = false; storageState();
        work.execute(() -> {
            AndroidVault vault = new AndroidVault(this);
            try {
                vault.unlock();
                ChatEngine loaded = new ChatEngine(vault);
                if (!resumed || generation != screenGeneration) { loaded.close(); return; }
                ui.post(() -> {
                    if (!resumed || isDestroyed() || generation != screenGeneration) { loaded.close(); return; }
                    loadingGeneration = -1;
                    engine = loaded;
                    if (loaded.authenticated()) loaded.connect(this::queueSync);
                    render();
                    if (googleAttempt != null && googleAttempt.idToken != null) completeGoogle();
                    if (selectedImage != null || cameraImage != null) importPendingImage();
                    registerPush();
                    queueSync();
                });
            } catch (Exception failure) {
                vault.close();
                ui.post(() -> {
                    if (!resumed || isDestroyed() || generation != screenGeneration) return;
                    loadingGeneration = -1;
                    storageFailure(failure);
                });
            }
        });
    }

    private void storageFailure(Exception failure) {
        waitingForPhoneUnlock = failure instanceof AndroidVault.PhoneLockedException;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (failure instanceof AndroidVault.PhoneLockedException && resumed && keyguard != null && !keyguard.isDeviceLocked()) {
            load(); return;
        }
        phoneSecurityRequired = failure instanceof AndroidVault.PhoneLockRequiredException;
        if (phoneSecurityRequired) storageError = "Set a screen lock in Android settings to protect your chats.";
        else if (failure instanceof AndroidVault.PhoneLockedException) storageError = "Unlock your phone to open your chats.";
        else if (failure instanceof android.security.keystore.UserNotAuthenticatedException)
            storageError = "Unlock your phone with its PIN, pattern or password, then reopen Vanishr to finish the storage update. Your chats have not been deleted.";
        else storageError = "Secure storage is unavailable. Your encrypted data has not been cleared.";
        storageState();
    }

    private void resumeAfterPhoneUnlock() {
        if (!resumed || !waitingForPhoneUnlock || engine != null) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && !keyguard.isDeviceLocked()) load();
    }

    private void render() {
        if (!resumed) return;
        ChatEngine current = engine;
        if (current == null) { storageState(); return; }
        root.removeAllViews();
        messages = null; messageScroll = null; composer = null; connection = null; conversationName = null; conversationUsername = null; actionError = null; contactRows = null; contactSearch = null;
        shownEntries = java.util.List.of();
        if (!current.authenticated()) { loginScreen(); maybePromptUpdate(false); return; }
        ChatEngine.Peer peer = current.peers().stream().filter(candidate -> candidate.userId().equals(selectedPeer)).findFirst().orElse(null);
        if (peer == null) contactsScreen(); else conversation(peer);
        maybePromptUpdate(false);
    }

    private void checkForUpdates(boolean manual) {
        if (!resumed || isDestroyed() || checkingUpdates) return;
        SharedPreferences preferences = getSharedPreferences("updates", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && !AppUpdates.due(now, preferences.getLong("last-check", 0))) { maybePromptUpdate(false); return; }
        checkingUpdates = true;
        preferences.edit().putLong("last-check", now).apply();
        updateWork.execute(() -> {
            try (AppUpdates updates = new AppUpdates()) {
                AppUpdates.Release release = updates.check(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT);
                ui.post(() -> {
                    if (isDestroyed()) return;
                    checkingUpdates = false;
                    availableUpdate = release;
                    if (manual && release == null && resumed) problem("Vanishr is up to date.");
                    else maybePromptUpdate(manual);
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    checkingUpdates = false;
                    if (manual && resumed) problem("Cannot check for updates right now. Try again later.");
                });
            }
        });
    }

    private void maybePromptUpdate(boolean manual) {
        AppUpdates.Release release = availableUpdate;
        if (release == null || !resumed || isDestroyed() || engine == null || busy
                || (openDialog != null && openDialog.isShowing()) || (!manual && selectedPeer != null)) return;
        SharedPreferences preferences = getSharedPreferences("updates", MODE_PRIVATE);
        if (!manual && !AppUpdates.shouldPrompt(release, preferences.getLong("dismissed-version", 0),
                preferences.getLong("dismissed-at", 0), System.currentTimeMillis())) return;
        AlertDialog prompt = new MaterialAlertDialogBuilder(this).setTitle("Update available")
                .setMessage("Vanishr " + release.versionName())
                .setNegativeButton("Later", (dialog, which) -> dismissUpdate(release))
                .setPositiveButton("Download", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl())).addCategory(Intent.CATEGORY_BROWSABLE));
                        dismissUpdate(release);
                    } catch (ActivityNotFoundException failure) { problem("No browser is available to download the update."); }
                }).create();
        prompt.setOnCancelListener(dialog -> dismissUpdate(release));
        showDialog(prompt);
    }

    private void dismissUpdate(AppUpdates.Release release) {
        getSharedPreferences("updates", MODE_PRIVATE).edit().putLong("dismissed-version", release.versionCode())
                .putLong("dismissed-at", System.currentTimeMillis()).apply();
        availableUpdate = null;
    }

    private void loginScreen() {
        chatScreen = false;
        title("vanishr", false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout form = vertical();
        ChatEngine.Account saved = engine.account();
        if (saved != null) signingUp = false;
        boolean passwordAccount = saved == null || !engine.usesGoogle();
        String configuredOrigin = BuildConfig.DEFAULT_RELAY_ORIGIN;
        if (configuredOrigin.isEmpty() && BuildConfig.DEBUG) configuredOrigin = "https://10.0.2.2:8443";
        String origin = saved == null ? configuredOrigin : saved.origin();
        form.addView(design.spacer(24));
        TextView heading = design.text(signingUp ? "Create your account" : "Welcome back.", 29, 800, INK);
        form.addView(heading);
        form.addView(design.spacer(20));
        MaterialButtonToggleGroup mode = new MaterialButtonToggleGroup(this);
        mode.setSingleSelection(true); mode.setSelectionRequired(true);
        MaterialButton signIn = design.button("Sign in", false, () -> { }); signIn.setId(View.generateViewId()); signIn.setCheckable(true);
        MaterialButton signUp = design.button("Sign up", false, () -> { }); signUp.setId(View.generateViewId()); signUp.setCheckable(true);
        mode.addView(signIn, new LinearLayout.LayoutParams(0, dp(48), 1));
        mode.addView(signUp, new LinearLayout.LayoutParams(0, dp(48), 1));
        signIn.setOnClickListener(view -> mode.check(signIn.getId()));
        signUp.setOnClickListener(view -> mode.check(signUp.getId()));
        mode.check(signingUp ? signUp.getId() : signIn.getId());
        if (saved == null) { form.addView(mode); form.addView(design.spacer(22)); }
        Ui.Field username = design.field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText handle = username.input();
        if (saved != null) handle.setText(saved.handle());
        Ui.Field secretField = design.field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText password = secretField.input();
        secretField.layout().setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        password.setTypeface(design.font(500));
        MaterialCheckBox replace = new MaterialCheckBox(this); replace.setText(R.string.replace_device);
        replace.setTextSize(13); replace.setTypeface(design.font(500)); replace.setTextColor(MUTED);
        if (passwordAccount) {
            form.addView(username.layout()); form.addView(design.spacer(14)); form.addView(secretField.layout());
            form.addView(design.spacer(10));
        }
        if (deviceReplacementRequired) form.addView(replace);
        actionError = design.text("", 13, 500, Ui.ERROR); actionError.setVisibility(View.GONE); form.addView(actionError);
        form.addView(design.spacer(14));
        MaterialButton continueButton = design.button(signingUp ? "Create account" : "Sign in", true, () -> {
            username.layout().setError(null); secretField.layout().setError(null);
            String accountHandle;
            try { accountHandle = ChatEngine.validUsername(handle.getText().toString()); }
            catch (IllegalArgumentException failure) { username.layout().setError("Use 3-32 lowercase letters, numbers or underscores"); return; }
            String secret = password.getText().toString();
            if (secret.length() < 16 || secret.length() > 64 || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                secretField.layout().setError("Use 16-64 characters, up to 72 UTF-8 bytes"); return;
            }
            boolean register = signingUp;
            boolean replaceExisting = replace.isChecked();
            ChatEngine current = engine;
            password.setText("");
            submit(() -> current.login(origin, accountHandle, secret, register, replaceExisting), () -> {
                signingUp = false;
                deviceReplacementRequired = false;
                engine.connect(this::queueSync);
                render();
                registerPush();
                queueSync();
            });
        });
        if (passwordAccount) form.addView(continueButton);
        mode.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            signingUp = checkedId == signUp.getId();
            continueButton.setText(signingUp ? "Create account" : "Sign in");
            heading.setText(signingUp ? "Create your account" : "Welcome back.");
            username.layout().setError(null); secretField.layout().setError(null);
            actionError.setText(""); actionError.setVisibility(View.GONE);
        });
        if (saved == null) {
            form.addView(design.spacer(18));
            LinearLayout separator = horizontal();
            separator.addView(design.divider(), new LinearLayout.LayoutParams(0, dp(1), 1));
            TextView or = design.text("or", 12, 500, MUTED); or.setPadding(dp(16), 0, dp(16), 0); separator.addView(or);
            separator.addView(design.divider(), new LinearLayout.LayoutParams(0, dp(1), 1)); form.addView(separator);
            form.addView(design.spacer(18));
        }
        if (saved == null || !passwordAccount) {
            MaterialButton google = design.button("Continue with Google", !passwordAccount, () -> googleLogin(origin, replace.isChecked()));
            form.addView(google);
        }
        if (saved != null) {
            form.addView(design.spacer(18));
            form.addView(command("Use another account", this::signOutDialog));
        }
        form.addView(design.spacer(16));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (googleFailure != null) { problem(googleFailure.message); googleFailure = null; }
    }

    private void contactsScreen() {
        chatScreen = false;
        title("vanishr", false);
        LinearLayout actions = horizontal();
        actions.addView(design.text("Chats", 32, 800, INK), new LinearLayout.LayoutParams(0, dp(60), 1));
        ImageButton add = icon(R.drawable.ic_plus, "Add contact", this::addContactDialog);
        add.setBackground(design.feedback(Ui.PRIMARY, 24)); add.setColorFilter(Color.WHITE); actions.addView(add);
        root.addView(actions);
        root.addView(design.spacer(12));
        contactSearch = field("Find a contact", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contactSearch.setSingleLine(true); contactSearch.setTextSize(14);
        contactSearch.setBackground(design.background(Ui.SURFACE, 8, Ui.LINE));
        contactSearch.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(contactSearch, new LinearLayout.LayoutParams(-1, dp(52))); root.addView(design.spacer(16));
        ScrollView scroll = new ScrollView(this);
        contactRows = vertical(); scroll.setFillViewport(true); scroll.addView(contactRows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        shownPeers = engine.peers(); renderContacts();
        contactSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { renderContacts(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        connection = label(statusText(), 12, MUTED); root.addView(connection);
    }

    private void googleLogin(String origin, boolean replace) {
        if (!resumed || engine == null || busy || googleAttempt != null) return;
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) { problem("Google sign-in is not configured for this build yet."); return; }
        if (engine.account() != null && !engine.usesGoogle()) { problem("This account uses a password. Google account linking is not enabled."); return; }
        if (getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("google-reset-pending", false)) {
            int generation = screenGeneration;
            busy = true;
            GoogleSignIn.clear(this, cleared -> {
                busy = false;
                if (cleared) getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", false).apply();
                if (!resumed || isDestroyed() || generation != screenGeneration) return;
                if (cleared) googleLogin(origin, replace);
                else problem("Google could not reset its sign-in session. Check Google Play services and try again.");
            });
            return;
        }
        googleFailure = null;
        if (actionError != null) { actionError.setText(""); actionError.setVisibility(View.GONE); }
        UUID device = engine.activeDeviceId();
        var prepared = new java.util.concurrent.atomic.AtomicReference<GoogleSignIn.Challenge>();
        submit(() -> prepared.set(GoogleSignIn.prepare(origin, device)), () -> {
            GoogleAttempt attempt = new GoogleAttempt(origin, replace, prepared.get());
            googleAttempt = attempt;
            googleCancellation = GoogleSignIn.request(this, attempt.challenge, new GoogleSignIn.Callback() {
                @Override public void token(String token) {
                    if (googleAttempt != attempt || isDestroyed()) return;
                    if (attempt.challenge.expiresAt() <= System.currentTimeMillis()) { googleFailed(GoogleSignIn.Failure.EXPIRED); return; }
                    attempt.idToken = token;
                    if (resumed) { if (engine == null) load(); else completeGoogle(); }
                }
                @Override public void failed(GoogleSignIn.Failure failure) {
                    if (googleAttempt == attempt && !isDestroyed()) googleFailed(failure);
                }
            });
            ui.postDelayed(() -> {
                if (googleAttempt == attempt) {
                    googleFailed(GoogleSignIn.Failure.EXPIRED);
                    if (googleCancellation != null) googleCancellation.cancel();
                }
            }, Math.max(1, attempt.challenge.expiresAt() - System.currentTimeMillis()));
        });
    }

    private void googleFailed(GoogleSignIn.Failure failure) {
        if (googleAttempt != null) googleAttempt.idToken = null;
        googleAttempt = null;
        googleFailure = failure;
        if (resumed) {
            problem(failure.message);
            if (engine != null) googleFailure = null;
        }
    }

    private void completeGoogle() {
        GoogleAttempt attempt = googleAttempt;
        ChatEngine current = engine;
        if (attempt == null || attempt.idToken == null || current == null || busy) return;
        googleAttempt = null;
        submit(() -> {
            try { current.loginGoogle(attempt.origin, attempt.challenge, attempt.idToken, attempt.replace); }
            finally { attempt.idToken = null; }
        }, () -> { deviceReplacementRequired = false; render(); engine.connect(this::queueSync); registerPush(); queueSync(); });
    }

    private void renderContacts() {
        if (contactRows == null || engine == null) return;
        contactRows.removeAllViews(); unreadLabels.clear();
        String filter = contactSearch == null ? "" : contactSearch.getText().toString().toLowerCase(Locale.ROOT);
        for (ChatEngine.Peer peer : engine.peers()) {
            if (!peer.name().toLowerCase(Locale.ROOT).contains(filter) && !peer.username().contains(filter)
                    && (peer.profileName() == null || !peer.profileName().toLowerCase(Locale.ROOT).contains(filter))) continue;
            LinearLayout row = horizontal();
            row.setMinimumHeight(dp(88)); row.setPadding(0, dp(12), 0, dp(12)); row.setBackground(design.feedback(Color.TRANSPARENT, 8));
            row.addView(design.avatar(peer.name(), 52, peer.userId().hashCode() % 2 == 0 ? Ui.PRIMARY : Ui.BLUE));
            LinearLayout identity = vertical(); identity.setPadding(dp(14), 0, dp(8), 0);
            identity.addView(design.text(peer.name(), 16, 700, INK));
            TextView subtext = design.text("@" + peer.username(), 12, 500, MUTED); subtext.setPadding(0, dp(6), 0, 0); identity.addView(subtext);
            row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            long unread = engine.entries(peer.userId()).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count();
            TextView badge = design.text(unread > 0 ? Long.toString(unread) : "", 12, 800, GREEN);
            badge.setGravity(Gravity.CENTER); badge.setMinWidth(dp(28)); unreadLabels.put(peer.userId(), badge); row.addView(badge);
            row.addView(design.symbol(R.drawable.ic_chevron_right, 18, MUTED));
            row.setOnClickListener(view -> { selectedPeer = peer.userId(); render(); });
            row.setOnLongClickListener(view -> { forgetDialog(peer); return true; });
            contactRows.addView(row); contactRows.addView(design.divider());
        }
        if (contactRows.getChildCount() == 0) {
            LinearLayout empty = vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(64), dp(20), dp(32));
            ImageView image = design.symbol(R.drawable.ic_message_circle, 64, Ui.BLUE); image.setPadding(dp(12), dp(12), dp(12), dp(12));
            image.setBackground(design.background(Ui.BLUE_TINT, 8, 0)); empty.addView(image); empty.addView(design.spacer(20));
            empty.addView(design.text(filter.isEmpty() ? "No conversations yet" : "No matching contacts", 20, 700, INK));
            if (filter.isEmpty()) { empty.addView(design.spacer(20)); empty.addView(design.button("Add a contact", true, this::addContactDialog)); }
            contactRows.addView(empty);
        }
    }

    private String statusText() {
        if (busy) return "Working";
        if (engine == null) return "Opening";
        if (engine.unverifiedIncoming) return "Identity verification required";
        return engine.online ? "Connected" : "Offline";
    }

    private void conversation(ChatEngine.Peer peer) {
        chatScreen = true;
        LinearLayout header = horizontal();
        header.addView(icon(R.drawable.ic_arrow_left, "Back", () -> { selectedPeer = null; render(); }));
        header.addView(design.avatar(peer.name(), 40, Ui.PRIMARY));
        LinearLayout identity = vertical(); identity.setPadding(dp(12), 0, dp(6), 0);
        conversationName = design.text(peer.name(), 17, 800, INK);
        identity.addView(conversationName);
        conversationUsername = design.text("@" + peer.username(), 11, 500, MUTED);
        conversationUsername.setPadding(0, dp(4), 0, 0); identity.addView(conversationUsername);
        connection = design.text(statusText(), 11, 500, MUTED); connection.setPadding(0, dp(4), 0, 0); identity.addView(connection);
        identity.setMinimumHeight(dp(68));
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(R.drawable.ic_ellipsis_vertical, "Conversation options", () -> conversationMenu(peer, header)));
        root.addView(header); root.addView(design.spacer(8)); root.addView(design.divider());
        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false);
        messages = vertical();
        messages.setPadding(0, dp(18), 0, dp(18));
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshMessages(peer);
        LinearLayout settings = horizontal();
        settings.addView(design.symbol(R.drawable.ic_shield_check, 15, GREEN));
        TextView verified = design.text("Verified", 11, 600, MUTED); verified.setPadding(dp(6), 0, 0, 0);
        settings.addView(verified, new LinearLayout.LayoutParams(0, -2, 1));
        expiryLabel = design.text(expiryName(expiry), 12, 700, GREEN);
        expiryLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_clock_3, 0, 0, 0);
        expiryLabel.setCompoundDrawablePadding(dp(6)); expiryLabel.setPadding(dp(8), dp(10), dp(8), dp(10));
        expiryLabel.setContentDescription("Message expiry: " + expiryName(expiry));
        expiryLabel.setBackground(design.feedback(Color.TRANSPARENT, 8));
        expiryLabel.setOnClickListener(view -> expiryDialog()); settings.addView(expiryLabel);
        root.addView(settings);
        LinearLayout input = horizontal();
        input.setGravity(Gravity.BOTTOM); input.setPadding(0, dp(6), 0, dp(4));
        input.addView(icon(R.drawable.ic_plus, "Attach", this::attachmentDialog));
        composer = field("Message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        composer.setMaxLines(4);
        composer.setTextSize(15); composer.setMinHeight(dp(50));
        composer.setPadding(dp(14), dp(12), dp(14), dp(12));
        composer.setBackground(design.background(Ui.SURFACE, 8, Ui.LINE));
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});
        input.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton send = icon(R.drawable.ic_arrow_up, "Send", () -> {
            String text = composer.getText().toString();
            if (text.trim().isEmpty()) return;
            if (enqueueSend(peer, text, null, expiry)) composer.setText("");
        });
        send.setBackground(design.feedback(Ui.PRIMARY, 24)); send.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48)); sendParams.setMarginStart(dp(8)); send.setLayoutParams(sendParams);
        input.addView(send);
        root.addView(input);
    }

    private boolean enqueueSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry selectedExpiry) {
        if (!resumed || engine == null || busy) return false;
        if (pendingSends.size() >= 8) { problem("Wait for a pending message before sending more."); return false; }
        PendingSend pending = new PendingSend(peer, text, image == null ? null : image.clone(), selectedExpiry);
        if (messages != null && peer.userId().equals(selectedPeer) && shownEntries.isEmpty()
                && pendingSends.stream().noneMatch(value -> value.peer.userId().equals(selectedPeer))) messages.removeAllViews();
        pendingSends.add(pending);
        appendPending(pending);
        if (messageScroll != null) messageScroll.post(() -> { if (messageScroll != null) messageScroll.fullScroll(View.FOCUS_DOWN); });
        dispatchSend(pending);
        return true;
    }

    private void dispatchSend(PendingSend pending) {
        if (pending.cancelled || pending.expiresAt() <= System.currentTimeMillis()) return;
        ChatEngine current = engine;
        int generation = screenGeneration;
        pending.failed = false;
        work.execute(() -> {
            byte[] image = null;
            try {
                if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                image = pending.copyImage();
                if (pending.cancelled) return;
                current.send(pending.peer, pending.text, image, pending.expiry, pending.id, pending.createdAt, () -> ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current) return;
                    pendingSends.remove(pending); pending.clear();
                    if (pending.peer.userId().equals(selectedPeer)) refreshMessages(pending.peer);
                }));
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current) return;
                    if (connection != null) connection.setText(statusText());
                    if (pending.peer.userId().equals(selectedPeer)) refreshMessages(pending.peer);
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                    pending.failed = true;
                    if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                    appendPending(pending);
                    showFailure(failure);
                });
            } finally { if (image != null) Arrays.fill(image, (byte) 0); }
        });
    }

    private void appendPending(PendingSend pending) {
        if (messages == null || !pending.peer.userId().equals(selectedPeer) || pending.cancelled) return;
        LinearLayout alignment = horizontal(); alignment.setGravity(Gravity.END); alignment.setTag(pending.expiresAt());
        LinearLayout bubble = vertical(); bubble.setPadding(dp(14), dp(10), dp(14), dp(8));
        bubble.setBackground(design.background(Ui.TINT, 8, 0));
        TextView body = label(pending.expiry == ChatEnvelope.Expiry.VIEW_ONCE ? "View-once message" : pending.image == null ? pending.text : "Photo", 16, INK);
        body.setMaxWidth(Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
        bubble.addView(body);
        LinearLayout state = horizontal(); state.setGravity(Gravity.END);
        state.addView(design.text(pending.failed ? "Not sent" : "Sending", 11, 500, pending.failed ? Ui.ERROR : MUTED));
        if (pending.failed) {
            state.addView(icon(R.drawable.ic_trash_2, "Discard unsent message", () -> {
                if (!pending.failed || pending.cancelled) return;
                if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                pendingSends.remove(pending); pending.clear();
                if (messages != null && messages.getChildCount() == 0) refreshMessages(pending.peer);
            }));
            state.addView(icon(R.drawable.ic_arrow_up, "Retry send", () -> {
                if (!pending.failed || busy || pending.cancelled) return;
                if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                dispatchSend(pending); appendPending(pending);
            }));
        } else {
            View gap = new View(this); state.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
            state.addView(design.symbol(R.drawable.ic_clock_3, 14, MUTED));
        }
        bubble.addView(state); alignment.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2); placement.setMargins(0, dp(4), 0, dp(4));
        pending.row = alignment; messages.addView(alignment, placement);
    }

    private String expiryName(ChatEnvelope.Expiry value) { return switch (value) { case VIEW_ONCE -> "View once"; case HOUR_1 -> "1 hour"; case HOURS_6 -> "6 hours"; case HOURS_24 -> "24 hours"; }; }

    private void expiryDialog() {
        String[] modes = {"View once", "1 hour", "6 hours", "24 hours"};
        showDialog(new MaterialAlertDialogBuilder(this).setTitle("Disappearing messages")
                .setSingleChoiceItems(modes, expiry.ordinal(), (dialog, which) -> {
                    expiry = ChatEnvelope.Expiry.values()[which];
                    if (expiryLabel != null) { expiryLabel.setText(expiryName(expiry)); expiryLabel.setContentDescription("Message expiry: " + expiryName(expiry)); }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create());
    }

    private void attachmentDialog() {
        LinearLayout choices = vertical(); choices.setPadding(dp(20), dp(8), dp(20), dp(20));
        MaterialButton gallery = design.button("Choose image", false, () -> { dismissContent(); gallery(); }); gallery.setIconResource(R.drawable.ic_image);
        MaterialButton camera = design.button("Take photo", false, () -> { dismissContent(); camera(); }); camera.setIconResource(R.drawable.ic_camera);
        choices.addView(gallery); choices.addView(design.spacer(12)); choices.addView(camera);
        showDialog(new MaterialAlertDialogBuilder(this).setTitle("Send an image").setView(choices).setNegativeButton("Cancel", null).create());
    }

    private void conversationMenu(ChatEngine.Peer peer, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add("Profile").setOnMenuItemClickListener(item -> { contactProfileDialog(peer); return true; });
        menu.getMenu().add("Contact identity").setOnMenuItemClickListener(item -> {
            showDialog(new MaterialAlertDialogBuilder(this).setTitle(peer.name()).setMessage(peer.userId() + ":\n" + peer.identityKey()).setPositiveButton("Close", null).create()); return true;
        });
        menu.getMenu().add("Disappearing messages").setOnMenuItemClickListener(item -> { expiryDialog(); return true; });
        menu.getMenu().add("Remove contact").setOnMenuItemClickListener(item -> { forgetDialog(peer); return true; });
        menu.show();
    }

    private void refreshMessages(ChatEngine.Peer peer) {
        if (!resumed || messages == null || engine == null) return;
        java.util.List<ChatEngine.Entry> entries = engine.entries(peer.userId());
        if (entries.equals(shownEntries) && messages.getChildCount() > 0) return;
        boolean atEnd = messageScroll == null || messages.getHeight() - messageScroll.getScrollY() - messageScroll.getHeight() < dp(100);
        int position = messageScroll == null ? 0 : messageScroll.getScrollY();
        shownEntries = entries;
        messages.removeAllViews();
        if (entries.isEmpty() && pendingSends.stream().noneMatch(pending -> pending.peer.userId().equals(peer.userId()))) {
            LinearLayout empty = vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(10), dp(48), dp(10), dp(36));
            empty.addView(design.symbol(R.drawable.ic_shield_check, 40, Ui.PRIMARY)); empty.addView(design.spacer(14));
            TextView greeting = design.text("Just the two of you", 18, 700, INK);
            greeting.setGravity(Gravity.CENTER);
            empty.addView(greeting, new LinearLayout.LayoutParams(-1, -2));
            messages.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        String previousDay = "";
        for (ChatEngine.Entry entry : entries) {
            long created = entry.expiresAt() - entry.expiry().milliseconds;
            String day = java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            if (!day.equals(previousDay)) {
                TextView date = design.text(day.equals(java.time.LocalDate.now().toString()) ? "Today" : java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(java.time.LocalDate.parse(day)), 11, 700, MUTED);
                date.setGravity(Gravity.CENTER); date.setPadding(0, dp(16), 0, dp(16)); messages.addView(date); previousDay = day;
            }
            LinearLayout alignment = horizontal(); alignment.setGravity(entry.outgoing() ? Gravity.END : Gravity.START); alignment.setTag(entry.expiresAt());
            LinearLayout row = vertical();
            row.setPadding(dp(14), dp(10), dp(14), dp(8));
            row.setBackground(design.background(entry.outgoing() ? Ui.TINT : Ui.SURFACE, 8, entry.outgoing() ? 0 : Ui.LINE));
            TextView body;
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) {
                body = label(entry.outgoing() ? "View-once message" : "View once", 16, GREEN);
                if (!entry.outgoing()) body.setOnClickListener(view -> viewContent(entry));
            } else if (entry.image()) {
                body = label("Photo", 16, GREEN);
                body.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_image, 0, 0, 0);
                body.setCompoundDrawablePadding(dp(8));
                body.setOnClickListener(view -> viewContent(entry));
            } else {
                try { body = label(engine.content(entry, false).envelope().text(), 16, INK); }
                catch (Exception failure) {
                    body = label("Content unavailable", 16, MUTED);
                    if (failure instanceof GeneralSecurityException) ui.post(() -> { hideContent(); storageFailure(failure); });
                }
            }
            body.setMaxWidth(Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) { body.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_eye, 0, 0, 0); body.setCompoundDrawablePadding(dp(8)); }
            row.addView(body);
            LinearLayout metadata = horizontal(); metadata.setGravity(Gravity.END);
            String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()));
            TextView timestamp = design.text(time + "  /  " + expiryName(entry.expiry()), 10, 500, MUTED); timestamp.setPadding(dp(4), dp(6), dp(5), 0); metadata.addView(timestamp);
            if (entry.outgoing()) {
                ImageView status = design.symbol(switch (entry.state()) { case "READ", "DELIVERED" -> R.drawable.ic_check_check; case "QUEUED" -> R.drawable.ic_check; default -> R.drawable.ic_clock_3; }, 15, entry.state().equals("READ") ? Ui.BLUE : MUTED);
                status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); status.setContentDescription(entry.state()); metadata.addView(status);
            }
            row.addView(metadata);
            row.setOnLongClickListener(view -> {
                showDialog(new MaterialAlertDialogBuilder(this).setTitle("Delete message?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> submit(() -> engine.erase(entry, "delete"), () -> refreshMessages(peer))).create());
                return true;
            });
            alignment.addView(row, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2); placement.setMargins(0, dp(4), 0, dp(4)); messages.addView(alignment, placement);
        }
        for (PendingSend pending : pendingSends) {
            if (entries.stream().noneMatch(entry -> entry.id().equals(pending.id))) appendPending(pending);
        }
        if (messageScroll != null) messageScroll.post(() -> { if (messageScroll != null) { if (atEnd) messageScroll.fullScroll(View.FOCUS_DOWN); else messageScroll.scrollTo(0, position); } });
    }

    private void addContactDialog() {
        if (engine == null || !engine.authenticated()) return;
        ChatEngine current = engine;
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        Ui.Field username = design.field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(33)});
        fields.addView(username.layout()); fields.addView(design.spacer(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Add contact").setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Find", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String handle = username.input().getText().toString().strip().toLowerCase(Locale.ROOT);
            if (!handle.matches("@?[a-z0-9_]{3,32}")) { username.layout().setError("Enter a username of 3-32 letters, numbers or underscores"); return; }
            username.layout().setError(null);
            var found = new java.util.concurrent.atomic.AtomicReference<ChatEngine.Peer>();
            submit(() -> found.set(current.findPeer(handle)), () -> { dialog.dismiss(); verifyContactDialog(found.get()); });
        }));
        showDialog(dialog);
    }

    private void verifyContactDialog(ChatEngine.Peer candidate) {
        if (engine == null || !engine.authenticated()) return;
        ChatEngine current = engine;
        String number;
        try { number = ChatEngine.safetyNumber(candidate.userId(), candidate.identityKey()); }
        catch (Exception failure) { problem("The contact identity is invalid."); return; }
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(16));
        panel.addView(design.text(candidate.profileName() == null ? candidate.username() : candidate.profileName(), 20, 700, INK));
        panel.addView(design.text("@" + candidate.username(), 13, 500, MUTED)); panel.addView(design.spacer(16));
        panel.addView(design.text("Safety number", 13, 600, MUTED));
        TextView safety = design.text(number, 13, 500, INK); safety.setTypeface(Typeface.MONOSPACE);
        safety.setContentDescription("Contact safety number"); safety.setPadding(0, dp(12), 0, dp(16)); panel.addView(safety);
        MaterialCheckBox verified = new MaterialCheckBox(this);
        verified.setText("I compared this with their Profile > Verify identity in person or through another trusted channel.");
        verified.setTextSize(13); verified.setTypeface(design.font(500)); panel.addView(verified);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Verify contact").setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Add contact", null).create();
        dialog.setOnShowListener(ignored -> {
            Button add = dialog.getButton(AlertDialog.BUTTON_POSITIVE); add.setEnabled(false);
            verified.setOnCheckedChangeListener((button, checked) -> add.setEnabled(checked));
            add.setOnClickListener(view -> {
                if (!verified.isChecked()) return;
                submit(() -> current.addPeer(candidate), () -> { dialog.dismiss(); render(); queueSync(); });
            });
        });
        showDialog(dialog);
    }

    private void contactProfileDialog(ChatEngine.Peer peer) {
        if (engine == null || !engine.authenticated()) return;
        ChatEngine current = engine;
        ChatEngine.Peer saved = current.peers().stream().filter(value -> value.userId().equals(peer.userId())).findFirst().orElse(null);
        if (saved == null) return;
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(16));
        LinearLayout profile = horizontal();
        profile.addView(design.avatar(saved.name(), 48, Ui.BLUE));
        LinearLayout details = vertical(); details.setPadding(dp(14), 0, 0, 0);
        details.addView(design.text(saved.profileName() == null ? saved.username() : saved.profileName(), 18, 700, INK));
        TextView username = design.text("@" + saved.username(), 13, 500, MUTED);
        username.setContentDescription("Contact username");
        details.addView(username);
        profile.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(profile);
        panel.addView(design.spacer(20));
        Ui.Field displayName = design.field("Display name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        displayName.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        displayName.input().setText(saved.name()); panel.addView(displayName.layout());
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Contact profile").setView(scroll)
                .setNegativeButton("Cancel", null).setNeutralButton("Use profile name", (ignored, which) ->
                        submit(() -> current.renameContact(saved, ""), () -> { dismissContent(); render(); }))
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = displayName.input().getText().toString().strip();
            if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) { displayName.layout().setError("Enter a name of 1-40 characters"); return; }
            displayName.layout().setError(null);
            submit(() -> current.renameContact(saved, value), () -> { dismissContent(); render(); });
        }));
        showDialog(dialog);
    }

    private void forgetDialog(ChatEngine.Peer peer) {
        showDialog(new MaterialAlertDialogBuilder(this).setTitle("Remove verified contact?")
                .setMessage("Local content and the pinned identity will be erased.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> submit(() -> engine.forget(peer), this::render)).create());
    }

    private void accountDialog() {
        if (engine == null || engine.account() == null) return;
        ChatEngine current = engine;
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(16));
        LinearLayout profile = horizontal(); profile.addView(design.avatar(current.displayName(), 52, Ui.BLUE));
        LinearLayout details = vertical(); details.setPadding(dp(14), 0, 0, 0);
        TextView profileName = design.text(current.displayName(), 18, 800, INK);
        TextView profileUsername = design.text("@" + current.account().handle(), 13, 500, MUTED);
        profileUsername.setPadding(0, dp(4), 0, 0);
        details.addView(profileName); details.addView(profileUsername);
        profile.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(profile);
        panel.addView(design.spacer(24));
        Ui.Field displayName = design.field("Display name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        displayName.input().setText(current.displayName());
        displayName.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        design.inlineSave(displayName, "Save name", () -> {
            String value = displayName.input().getText().toString().strip();
            if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) { displayName.layout().setError("Enter a name of 1-40 characters"); return; }
            saveProfileField(displayName, () -> current.renameProfile(value), () -> {
                profileName.setText(current.displayName()); displayName.input().setText(current.displayName());
                profile.removeViewAt(0); profile.addView(design.avatar(current.displayName(), 52, Ui.BLUE), 0);
            });
        });
        panel.addView(displayName.layout()); panel.addView(design.spacer(14));
        Ui.Field username = design.field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.layout().setPrefixText("@");
        username.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        username.input().setText(current.account().handle());
        design.inlineSave(username, "Save username", () -> {
            String handle;
            try { handle = ChatEngine.validUsername(username.input().getText().toString()); }
            catch (IllegalArgumentException failure) { username.layout().setError("Use 3-32 lowercase letters, numbers or underscores"); return; }
            saveProfileField(username, () -> current.renameUsername(handle), () -> {
                profileUsername.setText("@" + current.account().handle()); username.input().setText(current.account().handle());
            });
        });
        panel.addView(username.layout()); panel.addView(design.spacer(12));
        panel.addView(profileAction("Share username", R.drawable.ic_message_circle, () -> {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "@" + current.account().handle());
            startActivity(Intent.createChooser(share, "Vanishr username"));
        }));
        panel.addView(profileAction("Verify identity", R.drawable.ic_shield_check, this::identityDialog));
        panel.addView(design.spacer(8)); panel.addView(design.divider()); panel.addView(design.spacer(8));
        SwitchMaterial notifications = new SwitchMaterial(this);
        notifications.setTextSize(14); notifications.setTypeface(design.font(500)); notifications.setMinHeight(dp(56));
        notifications.setText(R.string.notifications);
        notifications.setEnabled(VanishrApplication.pushConfigured());
        notifications.setChecked(getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("notifications", false));
        notifications.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("notifications", enabled).apply();
            if (VanishrApplication.pushConfigured()) FirebaseMessaging.getInstance().setAutoInitEnabled(enabled);
            if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            else if (enabled) registerPush();
            if (!enabled && VanishrApplication.pushConfigured()) {
                FirebaseMessaging.getInstance().deleteToken();
                submit(() -> { if (engine != null) engine.disablePush(); }, () -> { });
            }
        });
        panel.addView(notifications);
        if (!VanishrApplication.pushConfigured()) { TextView unavailable = design.text("Not configured", 12, 500, MUTED); panel.addView(unavailable); }
        panel.addView(profileAction("Check for updates", R.drawable.ic_arrow_up, () -> { dismissContent(); checkForUpdates(true); }));
        panel.addView(profileAction("Licenses & source", R.drawable.ic_file_text, () -> { dismissContent(); legalNotices(); }));
        panel.addView(design.spacer(8)); panel.addView(design.divider()); panel.addView(design.spacer(8));
        MaterialButton signOut = profileAction("Sign out", R.drawable.ic_log_out, this::signOutDialog);
        signOut.setTextColor(Ui.ERROR); signOut.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(signOut);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("My profile").setView(scroll).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> { if (resumed && engine == current && selectedPeer == null) render(); });
        showDialog(dialog);
    }

    private MaterialButton profileAction(String name, int resource, Runnable action) {
        MaterialButton button = design.button(name, false, action);
        button.setStrokeWidth(0); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(INK); button.setTextSize(14); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setIconResource(resource); button.setIconSize(dp(20)); button.setIconPadding(dp(14));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_START); button.setPadding(0, dp(12), 0, dp(12));
        return button;
    }

    private void saveProfileField(Ui.Field field, Work action, Runnable completed) {
        if (busy) { field.layout().setError("Another change is still saving."); return; }
        field.layout().setError(null); field.layout().setHelperText("Saving..."); field.layout().setEnabled(false);
        submit(action, () -> {
            field.layout().setEnabled(true); completed.run(); field.layout().setHelperText("Saved");
        }, failure -> {
            field.layout().setEnabled(true); field.layout().setHelperText(null);
            if (failure instanceof GeneralSecurityException || failure instanceof SecurityException
                    || failure instanceof AndroidVault.PhoneLockedException || failure instanceof AndroidVault.PhoneLockRequiredException) showFailure(failure);
            else field.layout().setError(failure instanceof RelayApi.ApiFailure apiFailure ? apiFailure.userMessage() : "Could not save. Check your connection and try again.");
        });
    }

    private void identityDialog() {
        if (engine == null || engine.account() == null) return;
        String identity;
        try { identity = engine.safetyNumber(); }
        catch (Exception failure) { problem("Device identity is unavailable."); return; }
        dismissContent();
        TextView code = design.text(identity, 13, 500, INK);
        code.setContentDescription("Device safety number");
        code.setTypeface(Typeface.MONOSPACE); code.setPadding(dp(24), dp(12), dp(24), dp(20));
        showDialog(new MaterialAlertDialogBuilder(this).setTitle("Safety number").setView(code)
                .setPositiveButton("Close", null).create());
    }

    private void signOutDialog() {
        if (engine == null || busy) return;
        ChatEngine current = engine;
        dismissContent();
        showDialog(new MaterialAlertDialogBuilder(this).setTitle("Sign out of this device?")
            .setMessage("Your chats, contacts and device identity stay encrypted on this phone. Signing in to the same account restores them. Disappearing messages still expire on their original schedule.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out", (dialog, which) -> {
                    if (!resumed || busy || engine != current) return;
                    if (!getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("notifications", false).putBoolean("google-reset-pending", true).commit()) {
                        problem("Sign-out settings could not be saved. Try again."); return;
                    }
                    GoogleAttempt previous = googleAttempt; googleAttempt = null;
                    if (previous != null) previous.idToken = null;
                    if (googleCancellation != null) googleCancellation.cancel();
                    googleCancellation = null; googleFailure = null;
                    getSystemService(NotificationManager.class).cancelAll();
                    if (VanishrApplication.pushConfigured()) { FirebaseMessaging.getInstance().setAutoInitEnabled(false); FirebaseMessaging.getInstance().deleteToken(); }
                    GoogleSignIn.clear(this, cleared -> {
                        if (cleared) getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", false).apply();
                    });
                    selectedPeer = null; signingUp = false; deviceReplacementRequired = false; shownPeers = java.util.List.of();
                    selectedImage = null;
                    if (cameraImage != null) Arrays.fill(cameraImage, (byte) 0);
                    cameraImage = null;
                    submit(current::logout, () -> {
                        hideContent();
                        load();
                    });
                }).create());
    }

    private void registerPush() {
        if (engine == null || !engine.authenticated() || !VanishrApplication.pushConfigured()
                || !getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("notifications", false)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ChatEngine current = engine;
        int generation = screenGeneration;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (!resumed || engine != current || generation != screenGeneration || !current.authenticated()
                || !getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("notifications", false)) return;
            submit(() -> current.pushToken(token), () -> { });
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATIONS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) registerPush();
    }

    private void gallery() {
        pickImage.launch(new androidx.activity.result.PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private void camera() {
        try { startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), CAMERA); }
        catch (ActivityNotFoundException failure) { problem("No camera application is available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == GALLERY) selectedImage = data.getData();
        if (request == CAMERA && data.getExtras() != null) {
            Bitmap bitmap = (Bitmap) data.getExtras().get("data");
            if (bitmap != null) {
                try { cameraImage = SafeImages.encode(bitmap); } catch (Exception failure) { problem("Photo unavailable."); }
                finally { bitmap.recycle(); }
            }
        }
        if (engine == null) load(); else importPendingImage();
    }

    private void importPendingImage() {
        Uri uri = selectedImage; selectedImage = null;
        byte[] captured = cameraImage; cameraImage = null;
        submit(() -> {
            byte[] image = captured == null ? SafeImages.importImage(getContentResolver(), uri) : captured;
            ui.post(() -> {
                if (!resumed || engine == null || selectedPeer == null) { Arrays.fill(image, (byte) 0); return; }
                try { previewImage(image); }
                catch (Exception failure) { Arrays.fill(image, (byte) 0); problem("Image unavailable."); }
            });
        }, () -> { });
    }

    private void previewImage(byte[] image) throws Exception {
        displayedBitmap = SafeImages.display(image);
        ImageView view = new ImageView(this); view.setImageBitmap(displayedBitmap); view.setAdjustViewBounds(true); view.setMaxHeight(dp(360)); view.setContentDescription("Image preview");
        ChatEngine.Peer peer = engine.peers().stream().filter(candidate -> candidate.userId().equals(selectedPeer)).findFirst().orElseThrow();
        ChatEnvelope.Expiry selectedExpiry = expiry;
        openDialog = new MaterialAlertDialogBuilder(this).setTitle("Send photo").setView(view).setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> {
                    enqueueSend(peer, null, image, selectedExpiry);
                }).create();
        openDialog.setOnDismissListener(dialog -> { view.setImageDrawable(null); Arrays.fill(image, (byte) 0); clearBitmap(); });
        showDialog(openDialog);
    }

    private void viewContent(ChatEngine.Entry entry) {
        if (entry.expiry() != ChatEnvelope.Expiry.VIEW_ONCE) { openContent(entry); return; }
        if (!resumed || engine == null || entry.expiresAt() <= System.currentTimeMillis()) { problem("Message expired."); return; }
        int generation = screenGeneration;
        LinearLayout explanation = vertical();
        explanation.setPadding(dp(24), dp(12), dp(24), dp(8));
        explanation.addView(design.symbol(R.drawable.ic_eye, 40, Ui.BLUE));
        explanation.addView(design.spacer(18));
        explanation.addView(design.text("This message can only be opened once.", 16, 600, INK));
        explanation.addView(design.spacer(10));
        explanation.addView(design.text("You cannot reopen it after closing. Opening it also removes its saved copy from this device.", 14, 400, MUTED));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(explanation);
        AlertDialog confirmation = new MaterialAlertDialogBuilder(this).setTitle("View once").setView(scroll)
                .setNegativeButton("Not now", null).setPositiveButton("Open", (dialog, which) -> {
                    if (resumed && engine != null && generation == screenGeneration) openContent(entry);
                }).create();
        showDialog(confirmation);
        ui.postDelayed(() -> { if (confirmation.isShowing()) confirmation.dismiss(); }, Math.max(1, entry.expiresAt() - System.currentTimeMillis()));
    }

    private void openContent(ChatEngine.Entry entry) {
        int generation = screenGeneration;
        submit(() -> {
            ChatEngine.Content content = engine.content(entry, true);
            ui.post(() -> {
                if (!resumed || engine == null || generation != screenGeneration || System.currentTimeMillis() >= entry.expiresAt()) {
                    if (content.image() != null) Arrays.fill(content.image(), (byte) 0);
                    return;
                }
                try {
                    LinearLayout panel = vertical();
                    panel.setPadding(dp(24), dp(12), dp(24), dp(20));
                    if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) {
                        LinearLayout status = horizontal();
                        status.addView(design.symbol(R.drawable.ic_eye, 20, Ui.BLUE));
                        TextView note = design.text("View once", 13, 700, Ui.BLUE);
                        note.setPadding(dp(8), 0, 0, 0);
                        status.addView(note);
                        panel.addView(status);
                        panel.addView(design.spacer(20));
                    }
                    View view;
                    if (content.image() == null) view = design.text(content.envelope().text(), 18, 500, INK);
                    else {
                        displayedBitmap = SafeImages.display(content.image());
                        Arrays.fill(content.image(), (byte) 0);
                        ImageView image = new ImageView(this); image.setImageBitmap(displayedBitmap); image.setAdjustViewBounds(true); image.setMaxHeight(dp(400)); image.setContentDescription("Received photo"); view = image;
                    }
                    panel.addView(view, new LinearLayout.LayoutParams(-1, -2));
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(panel);
                    openDialog = new MaterialAlertDialogBuilder(this).setView(scroll).setPositiveButton("Done", null).create();
                    openDialog.setOnDismissListener(dialog -> { if (view instanceof TextView text) text.setText(""); if (view instanceof ImageView image) image.setImageDrawable(null); clearBitmap(); });
                    showDialog(openDialog);
                    AlertDialog current = openDialog;
                    ui.postDelayed(() -> { if (current.isShowing()) current.dismiss(); }, Math.max(1, entry.expiresAt() - System.currentTimeMillis()));
                    queueSync();
                } catch (Exception failure) { problem("Content unavailable."); }
            });
        }, this::render);
    }

    private void queueSync() { try { work.execute(this::syncOnce); } catch (RejectedExecutionException ignored) { } }

    private void syncOnce() {
        ChatEngine current = engine;
        if (!resumed || current == null) return;
        try { current.sync(); }
        catch (GeneralSecurityException | AndroidVault.PhoneLockedException failure) {
            ui.post(() -> { if (engine == current) { hideContent(); storageFailure(failure); } }); return;
        }
        catch (Exception failure) { current.online = false; }
        ui.post(() -> {
            if (!resumed || engine != current) return;
            if (!current.authenticated()) { if (chatScreen || contactRows != null) render(); }
            else if (selectedPeer == null) {
                if (connection != null) connection.setText(statusText());
                java.util.List<ChatEngine.Peer> peers = current.peers();
                if (!peers.equals(shownPeers)) { shownPeers = peers; renderContacts(); }
                for (ChatEngine.Peer peer : peers) {
                    TextView badge = unreadLabels.get(peer.userId());
                    if (badge != null) { long count = current.entries(peer.userId()).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count(); badge.setText(count == 0 ? "" : Long.toString(count)); }
                }
            }
            else {
                if (connection != null) connection.setText(statusText());
                for (ChatEngine.Peer peer : current.peers()) if (peer.userId().equals(selectedPeer)) {
                    if (conversationName != null) conversationName.setText(peer.name());
                    if (conversationUsername != null) conversationUsername.setText("@" + peer.username());
                    refreshMessages(peer);
                }
            }
        });
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void submit(Work action, Runnable completed) {
        submit(action, completed, this::showFailure);
    }

    private void submit(Work action, Runnable completed, java.util.function.Consumer<Exception> failed) {
        if (busy) return;
        busy = true;
        int generation = screenGeneration;
        if (connection != null) connection.setText(R.string.working);
        work.execute(() -> {
            try { action.run(); ui.post(() -> { busy = false; if (resumed && generation == screenGeneration) completed.run(); }); }
            catch (Exception failure) {
                ui.post(() -> {
                    busy = false;
                    if (!resumed || generation != screenGeneration) return;
                    failed.accept(failure);
                });
            }
        });
    }

    private void showFailure(Exception failure) {
        if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                || failure instanceof AndroidVault.PhoneLockRequiredException) { hideContent(); storageFailure(failure); }
        else if (failure instanceof SecurityException) problem("Identity verification or secure storage check failed.");
        else if (failure instanceof RelayApi.ApiFailure apiFailure) {
            if (apiFailure.status == 409 && apiFailure.code.equals("device_already_registered")) { deviceReplacementRequired = true; render(); }
            problem(apiFailure.userMessage());
        }
        else if (failure instanceof java.io.IOException) problem("Cannot connect right now. Check your connection and try again.");
        else if (failure instanceof IllegalArgumentException) problem("Check the entered details.");
        else problem("The request could not be completed. Your encrypted account data has not been cleared.");
        if (connection != null) connection.setText(statusText());
    }

    private void problem(String text) {
        if (actionError != null && !chatScreen) { actionError.setText(text); actionError.setVisibility(View.VISIBLE); actionError.announceForAccessibility(text); }
        else Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
    private void showDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
        openDialog = dialog;
        dialog.show();
        TextView heading = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (heading != null) { heading.setTypeface(design.font(700)); heading.setFontVariationSettings("'wght' 700"); heading.setTextSize(20); heading.setLetterSpacing(0); }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) { message.setTypeface(design.font(500)); message.setFontVariationSettings("'wght' 500"); message.setTextSize(15); message.setLetterSpacing(0); }
        for (int role : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(role);
            if (button != null) { button.setTypeface(design.font(700)); button.setFontVariationSettings("'wght' 700"); button.setAllCaps(false); button.setLetterSpacing(0); button.setMinHeight(dp(48)); }
        }
    }
    private void clearBitmap() { if (displayedBitmap != null) { displayedBitmap.recycle(); displayedBitmap = null; } }
    private void dismissContent() { if (openDialog != null) { openDialog.dismiss(); openDialog = null; } clearBitmap(); }

    private void hideContent() {
        screenGeneration++;
        dismissContent();
        for (PendingSend pending : pendingSends) pending.clear();
        pendingSends.clear();
        if (composer != null) composer.setText("");
        composer = null; messages = null;
        conversationName = null; conversationUsername = null;
        messageScroll = null; shownEntries = java.util.List.of(); contactRows = null; contactSearch = null; actionError = null; unreadLabels.clear();
        connection = null;
        ChatEngine closing = engine; engine = null;
        if (closing != null) work.execute(closing::close);
        storageError = null; phoneSecurityRequired = false; waitingForPhoneUnlock = false;
        storageState();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (engine == null) load();
        else { render(); if (googleAttempt != null && googleAttempt.idToken != null) completeGoogle(); }
        checkForUpdates(false);
    }
    @Override protected void onPause() { resumed = false; hideContent(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) resumeAfterPhoneUnlock();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(new Bundle()); }
    @Override protected void onDestroy() { dismissContent(); if (googleCancellation != null) googleCancellation.cancel(); if (googleAttempt != null) googleAttempt.idToken = null; googleAttempt = null; ui.removeCallbacksAndMessages(null); unregisterReceiver(pushRefresh); unregisterReceiver(phoneUnlocked); if (cameraImage != null) Arrays.fill(cameraImage, (byte) 0); work.shutdown(); updateWork.shutdownNow(); super.onDestroy(); }
}