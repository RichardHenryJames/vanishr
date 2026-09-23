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
    private final java.util.concurrent.atomic.AtomicBoolean syncQueued = new java.util.concurrent.atomic.AtomicBoolean();
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
    private boolean pushRegistrationRunning;
    private boolean pushRegistrationFailed;
    private UUID pushRegisteredAccount;
    private TextView notificationStatus;
    private volatile int screenGeneration;
    private LinearLayout root;
    private LinearLayout messages;
    private TextView connection;
    private TextView conversationName;
    private TextView conversationUsername;
    private TextView contactStatus;
    private EditText composer;
    private boolean restoringDraft;
    private UUID selectedPeer;
    private NotificationOpen notificationOpen;
    private static final class NotificationOpen {
        final String reference;
        final long deadline;
        UUID owner;
        boolean running;
        NotificationOpen(String reference, long deadline) { this.reference = reference; this.deadline = deadline; }
    }
    private UUID composerPeer;
    private ChatEnvelope.Expiry expiry = ChatEnvelope.Expiry.HOUR_1;
    private SecureSheet openDialog;
    private Bitmap displayedBitmap;
    private ScrollView messageScroll;
    private java.util.List<ChatEngine.Entry> shownEntries = java.util.List.of();
    private java.util.List<ChatEngine.Peer> shownPeers = java.util.List.of();
    private java.util.List<GroupChat.Conversation> shownGroups = java.util.List.of();
    private ImageButton sendControl;
    private ImageButton attachmentControl;
    private TextView expiryLabel;
    private TextView actionError;
    private EditText contactSearch;
    private LinearLayout contactRows;
    private View homeConnection;
    private ImageButton unreadFilter;
    private boolean unreadOnly;
    private String homeQuery = "";
    private int screenInset = 20;
    private androidx.core.graphics.Insets systemInsets = androidx.core.graphics.Insets.NONE;
    private boolean chatScreen;
    private final java.util.List<PendingSend> pendingSends = new ArrayList<>();
    private static final class PendingSend {
        final UUID id = UUID.randomUUID();
        final ChatEngine.Peer peer;
        final ChatEnvelope.Expiry expiry;
        final long createdAt = System.currentTimeMillis();
        String text;
        byte[] image;
        boolean group;
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
    private boolean completingGoogle;
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
    private final java.util.List<PhotoAvatar> photoAvatars = new ArrayList<>();
    private long shownPhotoGeneration = -1;
    private Uri selectedProfilePhoto;
    private UUID profilePhotoOwner;
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickProfilePhoto = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) { profilePhotoOwner = null; return; }
                selectedProfilePhoto = uri;
                if (engine == null) load(); else importProfilePhoto();
            });
    private static final class PhotoAvatar {
        final UUID userId;
        final FrameLayout frame;
        final TextView fallback;
        final ImageView image;
        Bitmap bitmap;
        long expiresAt;
        PhotoAvatar(UUID userId, FrameLayout frame, TextView fallback, ImageView image) {
            this.userId = userId; this.frame = frame; this.fallback = fallback; this.image = image;
        }
        void clear() {
            image.setImageDrawable(null); image.setVisibility(View.GONE); fallback.setVisibility(View.VISIBLE);
            if (bitmap != null) bitmap.recycle(); bitmap = null;
        }
    }
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
            refreshProfileAvatars();
            refreshContactStatus();
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
                systemInsets = padding;
                screenPadding(screenInset);
            return insets;
        });
        setContentView(root);
        notificationIntent(getIntent());
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        work.scheduleWithFixedDelay(this::syncOnce, 2, 15, TimeUnit.SECONDS);
        work.scheduleWithFixedDelay(this::presenceOnce, 1, 3, TimeUnit.SECONDS);
        ui.post(expiryTick);
        storageState();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void screenPadding(int horizontal) {
        screenInset = horizontal;
        int vertical = horizontal == 0 ? 0 : dp(8);
        root.setPadding(dp(horizontal) + systemInsets.left, vertical + systemInsets.top,
                dp(horizontal) + systemInsets.right, vertical + systemInsets.bottom);
    }
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

    private View profileAvatar(UUID userId, String name, int size, int color) {
        FrameLayout frame = new FrameLayout(this); frame.setSaveEnabled(false);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        TextView fallback = design.avatar(name, size, color);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(design.background(Ui.SURFACE, size / 2, 0)); image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); image.setSaveEnabled(false);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        PhotoAvatar avatar = new PhotoAvatar(userId, frame, fallback, image);
        photoAvatars.add(avatar); bindProfileAvatar(avatar);
        return frame;
    }

    private void bindProfileAvatar(PhotoAvatar avatar) {
        avatar.clear();
        if (engine == null || engine.account() == null) return;
        boolean own = engine.account().userId().equals(avatar.userId);
        byte[] photo = own ? engine.photos().own() : engine.photos().contact(avatar.userId);
        if (photo == null) return;
        try {
            avatar.bitmap = SafeImages.displayProfilePhoto(photo);
            avatar.expiresAt = own ? Long.MAX_VALUE : engine.photos().expiresAt(avatar.userId);
            avatar.image.setImageBitmap(avatar.bitmap); avatar.image.setVisibility(View.VISIBLE); avatar.fallback.setVisibility(View.GONE);
        } catch (java.io.IOException failure) { avatar.clear(); }
        finally { Arrays.fill(photo, (byte) 0); }
    }

    private void refreshProfileAvatars() {
        if (!resumed || engine == null) return;
        long current = engine.photos().generation();
        photoAvatars.removeIf(avatar -> {
            if (!avatar.frame.isAttachedToWindow()) { avatar.clear(); return true; }
            if (shownPhotoGeneration != current) bindProfileAvatar(avatar);
            else if (avatar.bitmap != null && avatar.expiresAt <= System.currentTimeMillis()) avatar.clear();
            return false;
        });
        shownPhotoGeneration = current;
    }

    private void clearProfileAvatars() {
        for (PhotoAvatar avatar : photoAvatars) avatar.clear();
        photoAvatars.clear(); shownPhotoGeneration = -1;
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
        screenPadding(20);
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
        String progress = googleAttempt != null || completingGoogle ? "Signing in with Google..." : notificationOpen != null ? "Opening chat..." : "Opening your chats...";
        TextView message = design.text(storageError == null ? progress : storageError, 16, 500, MUTED);
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
            showDialog(new SecureSheet.Builder(this).setTitle("Licenses & source").setView(scroll)
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
            showDialog(new SecureSheet.Builder(this).setTitle("GNU AGPL v3").setView(scroll).setNegativeButton("Close", null).create());
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
                    if (selectedProfilePhoto != null) importProfilePhoto();
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
        else if (failure instanceof AndroidVault.MigrationUnlockRequiredException)
            storageError = "One-time secure storage upgrade: unlock your phone with its PIN, pattern or password, then reopen Vanishr. Later opens can use any phone unlock method. Your encrypted data has not been cleared.";
        else if (failure instanceof android.security.keystore.UserNotAuthenticatedException)
            storageError = "Android could not open protected storage. Unlock your phone normally and try again. Your encrypted data has not been cleared.";
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
        String draft = composer != null && Objects.equals(composerPeer, selectedPeer) ? composer.getText().toString() : "";
        root.removeAllViews();
        messages = null; messageScroll = null; composer = null; connection = null; conversationName = null; conversationUsername = null; actionError = null; contactRows = null; contactSearch = null;
        contactStatus = null;
        homeConnection = null; unreadFilter = null;
        screenPadding(20);
        shownEntries = java.util.List.of();
        if (googleAttempt != null || completingGoogle) { storageState(); return; }
        if (!current.authenticated()) {
            current.presence().foreground(false);
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).apply();
            if (VanishrApplication.pushConfigured()) FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            notificationOpen = null; loginScreen(); maybePromptUpdate(false); return;
        }
        if (notificationOpen != null) { storageState(); openNotification(); return; }
        current.presence().foreground(true);
        ChatEngine.Peer peer = conversationPeer(selectedPeer);
        current.presence().conversation(peer != null && current.groups().get(peer.userId()) == null ? peer.userId() : null);
        if (peer == null) contactsScreen(); else conversation(peer);
        if (composer != null && !draft.isEmpty()) {
            restoringDraft = true;
            try { composer.setText(draft); composer.setSelection(composer.length()); }
            finally { restoringDraft = false; }
        }
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
            || googleAttempt != null || completingGoogle
                || (openDialog != null && openDialog.isShowing()) || (!manual && selectedPeer != null)) return;
        SharedPreferences preferences = getSharedPreferences("updates", MODE_PRIVATE);
        if (!manual && !AppUpdates.shouldPrompt(release, preferences.getLong("dismissed-version", 0),
                preferences.getLong("dismissed-at", 0), System.currentTimeMillis())) return;
        SecureSheet prompt = new SecureSheet.Builder(this).setTitle("Update available")
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
            form.addView(username.view()); form.addView(design.spacer(14)); form.addView(secretField.view());
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
        screenPadding(0);
        LinearLayout toolbar = horizontal(); toolbar.setPadding(dp(17), 0, dp(17), 0);
        toolbar.setContentDescription("Chats");
        LinearLayout brand = horizontal();
        brand.addView(design.text("vanishr", 25, 800, INK));
        homeConnection = new View(this);
        LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(dp(6), dp(6)); indicator.setMarginStart(dp(9));
        brand.addView(homeConnection, indicator);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        toolbar.addView(icon(R.drawable.ic_users_round, "New group", this::newGroupDialog));
        ImageButton add = icon(R.drawable.ic_square_pen, "New conversation", this::newConversationDialog); add.setPadding(dp(10), dp(10), dp(10), dp(10));
        add.setBackground(design.feedback(Ui.PRIMARY, 7)); add.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams composeSize = new LinearLayout.LayoutParams(dp(40), dp(40)); composeSize.setMarginStart(dp(4)); toolbar.addView(add, composeSize);
        View profile = profileAvatar(engine.account().userId(), engine.displayName(), 40, Ui.BLUE);
        profile.setContentDescription("My profile"); profile.setTooltipText("My profile"); profile.setFocusable(true);
        profile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); profile.setFilterTouchesWhenObscured(true);
        profile.setOnClickListener(view -> accountDialog());
        LinearLayout.LayoutParams profileSize = new LinearLayout.LayoutParams(dp(40), dp(40)); profileSize.setMarginStart(dp(7)); toolbar.addView(profile, profileSize);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));
        LinearLayout searchRow = horizontal(); searchRow.setGravity(Gravity.TOP); searchRow.setPadding(dp(17), dp(2), dp(17), dp(10));
        LinearLayout searchBox = horizontal(); searchBox.setPadding(dp(12), 0, dp(4), 0); searchBox.setBackground(design.background(Ui.SURFACE, 7, Ui.LINE));
        searchBox.addView(design.symbol(R.drawable.ic_search, 18, MUTED));
        contactSearch = field("Search chats", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contactSearch.setSingleLine(true); contactSearch.setTextSize(13); contactSearch.setBackgroundColor(Color.TRANSPARENT);
        contactSearch.setPadding(dp(9), 0, 0, 0); contactSearch.setText(homeQuery);
        searchBox.addView(contactSearch, new LinearLayout.LayoutParams(0, -1, 1));
        ImageButton clear = icon(R.drawable.ic_x, "Clear search", () -> contactSearch.setText(""));
        clear.setPadding(dp(8), dp(10), dp(8), dp(10)); clear.setVisibility(homeQuery.isEmpty() ? View.GONE : View.VISIBLE);
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(32), -1));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(44), 1));
        unreadFilter = icon(R.drawable.ic_list_filter, "Unread chats", () -> { unreadOnly = !unreadOnly; renderContacts(); });
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(dp(44), dp(44)); filterSize.setMarginStart(dp(7)); searchRow.addView(unreadFilter, filterSize);
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(56)));
        ScrollView scroll = new ScrollView(this);
        contactRows = vertical(); scroll.setFillViewport(true); scroll.addView(contactRows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        shownPeers = engine.peers(); shownGroups=engine.groups().conversations(); renderContacts();
        contactSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                homeQuery = value.toString(); clear.setVisibility(value.length() == 0 ? View.GONE : View.VISIBLE); renderContacts();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        homeStatus();
    }

    private void homeStatus() {
        if (homeConnection == null) return;
        homeConnection.setBackground(design.background(engine != null && engine.online && !busy ? Color.rgb(57, 164, 130) : MUTED, 3, 0));
        homeConnection.setContentDescription(statusText()); homeConnection.setTooltipText(statusText());
    }

    private void newConversationDialog() {
        LinearLayout options = vertical(); options.setPadding(dp(22), 0, dp(22), dp(8));
        options.addView(profileAction("Add contact", R.drawable.ic_user_round_plus, () -> { dismissContent(); addContactDialog(); }));
        options.addView(profileAction("New group", R.drawable.ic_users_round, () -> { dismissContent(); newGroupDialog(); }));
        showDialog(new SecureSheet.Builder(this).setTitle("New conversation").setView(options).setNegativeButton("Close", null).create());
    }

    private void googleLogin(String origin, boolean replace) {
        if (!resumed || engine == null || busy || googleAttempt != null) return;
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) { problem("Google sign-in is not configured for this build yet."); return; }
        if (engine.account() != null && !engine.usesGoogle()) { problem("This account uses a password. Google account linking is not enabled."); return; }
        googleFailure = null;
        if (actionError != null) { actionError.setText(""); actionError.setVisibility(View.GONE); }
        if ((engine.usesGoogle() && !engine.authenticated())
                || getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("google-reset-pending", false)) {
            resetGoogleSignIn(origin, replace);
            return;
        }
        beginGoogleSignIn(origin, replace);
    }

    private void resetGoogleSignIn(String origin, boolean replace) {
        int generation = screenGeneration;
        if (!getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", true).commit()) {
            problem("Google sign-in could not be prepared. Try again."); return;
        }
        busy = true;
        GoogleSignIn.clear(this, cleared -> {
            busy = false;
            if (cleared) getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", false).apply();
            if (!resumed || isDestroyed() || generation != screenGeneration) return;
            if (cleared) beginGoogleSignIn(origin, replace);
            else problem("Google could not reset its sign-in session. Check Google Play services and try again.");
        });
    }

    private void beginGoogleSignIn(String origin, boolean replace) {
        if (!resumed || engine == null || busy || googleAttempt != null) return;
        googleFailure = null;
        if (actionError != null) { actionError.setText(""); actionError.setVisibility(View.GONE); }
        var prepared = new java.util.concurrent.atomic.AtomicReference<GoogleSignIn.Challenge>();
        submit(() -> prepared.set(GoogleSignIn.prepare(origin)), () -> {
            GoogleAttempt attempt = new GoogleAttempt(origin, replace, prepared.get());
            googleAttempt = attempt;
            render();
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
        completingGoogle = false;
        googleFailure = failure;
        if (resumed) {
            render();
            if (engine != null && googleFailure != null) { problem(failure.message); googleFailure = null; }
        }
    }

    private void completeGoogle() {
        GoogleAttempt attempt = googleAttempt;
        ChatEngine current = engine;
        if (!resumed || attempt == null || attempt.idToken == null || current == null || busy) return;
        googleAttempt = null;
        completingGoogle = true;
        render();
        submit(() -> {
            try { current.loginGoogle(attempt.origin, attempt.challenge, attempt.idToken, attempt.replace); }
            finally { attempt.idToken = null; }
        }, () -> { completingGoogle = false; deviceReplacementRequired = false; render(); engine.connect(this::queueSync); registerPush(); queueSync(); }, failure -> {
            completingGoogle = false;
            if (failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status == 401) {
                getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", true).apply();
                submit(current::invalidateToken, () -> {
                    if (engine != current) return;
                    googleFailed(GoogleSignIn.Failure.REJECTED);
                });
            } else { render(); showFailure(failure); }
        });
    }

    private void renderContacts() {
        if (contactRows == null || engine == null) return;
        contactRows.removeAllViews(); unreadLabels.clear();
        if (unreadFilter != null) {
            unreadFilter.setSelected(unreadOnly); unreadFilter.setColorFilter(unreadOnly ? GREEN : INK);
            unreadFilter.setBackground(design.feedback(unreadOnly ? Ui.TINT : Ui.SURFACE, 7));
            androidx.core.view.ViewCompat.setStateDescription(unreadFilter, unreadOnly ? "Unread only" : "All chats");
        }
        String filter = contactSearch == null ? "" : contactSearch.getText().toString().toLowerCase(Locale.ROOT);
        for (GroupChat.Conversation group : engine.groups().conversations()) {
            if (!group.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (unreadOnly && engine.entries(group.id()).stream().noneMatch(entry -> !entry.outgoing() && !entry.state().equals("READ"))) continue;
            LinearLayout row=horizontal(); row.setMinimumHeight(dp(78)); row.setPadding(dp(18),dp(12),dp(18),dp(12)); row.setBackground(design.feedback(Color.TRANSPARENT,0));
            row.addView(design.groupAvatar(46, Ui.BLUE));
            LinearLayout details=vertical(); details.setPadding(dp(12),0,dp(8),0);
            TextView title=design.text(group.name(),14,700,INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); details.addView(title);
            LinearLayout subtitle=horizontal(); subtitle.setPadding(0,dp(5),0,0); subtitle.addView(design.symbol(R.drawable.ic_users_round,12,MUTED));
            TextView state=design.text(engine.groups().status(group),11,500,MUTED); state.setPadding(dp(5),0,0,0); state.setSingleLine(true); subtitle.addView(state); details.addView(subtitle);
            row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            long count=engine.entries(group.id()).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count();
            row.addView(unreadBadge(group.id(),count));
            row.addView(design.symbol(R.drawable.ic_chevron_right,16,MUTED));
            row.setOnClickListener(view -> {
                GroupChat.Conversation current=engine.groups().get(group.id());
                if (current==null) return;
                if (engine.groups().invited(current)) groupInvitationDialog(current);
                else { selectedPeer=group.id(); render(); }
            });
            row.setOnLongClickListener(view -> { groupInfo(group.id()); return true; });
            addChatRow(row);
        }
        for (ChatEngine.Peer peer : engine.peers()) {
            if (!peer.name().toLowerCase(Locale.ROOT).contains(filter) && !peer.username().contains(filter)
                    && (peer.profileName() == null || !peer.profileName().toLowerCase(Locale.ROOT).contains(filter))) {
                continue;
            }
            if (unreadOnly && engine.entries(peer.userId()).stream().noneMatch(entry -> !entry.outgoing() && !entry.state().equals("READ"))) {
                continue;
            }
            LinearLayout row = horizontal();
            row.setMinimumHeight(dp(78)); row.setPadding(dp(18), dp(12), dp(18), dp(12)); row.setBackground(design.feedback(Color.TRANSPARENT, 0));
            row.addView(profileAvatar(peer.userId(), peer.name(), 46, design.avatarColor(peer.userId())));
            LinearLayout identity = vertical(); identity.setPadding(dp(12), 0, dp(8), 0);
            TextView title=design.text(peer.name(),14,700,INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); identity.addView(title);
            row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            long unread = engine.entries(peer.userId()).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count();
            row.addView(unreadBadge(peer.userId(),unread));
            row.addView(design.symbol(R.drawable.ic_chevron_right, 16, MUTED));
            row.setOnClickListener(view -> { selectedPeer = peer.userId(); render(); });
            row.setOnLongClickListener(view -> { forgetDialog(peer); return true; });
            addChatRow(row);
        }
        if (contactRows.getChildCount() == 0) {
            LinearLayout empty = vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(64), dp(20), dp(32));
            ImageView image = design.symbol(R.drawable.ic_message_circle, 64, Ui.BLUE); image.setPadding(dp(12), dp(12), dp(12), dp(12));
            image.setBackground(design.background(Ui.BLUE_TINT, 8, 0)); empty.addView(image); empty.addView(design.spacer(20));
            TextView message = design.text(unreadOnly && filter.isEmpty() ? "All caught up" : filter.isEmpty() ? "No conversations yet" : "No matching conversations", 18, 700, INK);
            message.setGravity(Gravity.CENTER);
            empty.addView(message, new LinearLayout.LayoutParams(-1, -2));
            if (filter.isEmpty() && !unreadOnly) { empty.addView(design.spacer(20)); empty.addView(design.button("Add a contact", true, this::addContactDialog)); }
            contactRows.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private TextView unreadBadge(UUID id, long count) {
        TextView badge=design.text(Long.toString(count),10,800,Color.WHITE); badge.setGravity(Gravity.CENTER);
        badge.setBackground(design.background(GREEN,11,0)); badge.setPadding(dp(5),0,dp(5),0); badge.setMinWidth(dp(21));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(21)); size.setMarginEnd(dp(7)); badge.setLayoutParams(size);
        badge.setVisibility(count==0 ? View.GONE : View.VISIBLE); unreadLabels.put(id,badge); return badge;
    }

    private void addChatRow(LinearLayout row) {
        contactRows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,dp(1)); size.setMarginStart(dp(78)); size.setMarginEnd(dp(18)); contactRows.addView(design.divider(),size);
    }

    private String statusText() {
        if (busy) return "Working";
        if (engine == null) return "Opening";
        if (engine.unverifiedIncoming) return "Identity verification required";
        return engine.online ? "Connected" : "Offline";
    }

    private void conversation(ChatEngine.Peer peer) {
        chatScreen = true;
        screenPadding(0); composerPeer=peer.userId();
        GroupChat.Conversation group=engine.groups().get(peer.userId());
        LinearLayout header = horizontal(); header.setMinimumHeight(dp(68)); header.setPadding(dp(10),dp(8),dp(10),dp(8)); header.setBackgroundColor(Ui.SURFACE);
        header.addView(icon(R.drawable.ic_arrow_left, "Back", () -> { selectedPeer = null; render(); }));
        View avatar=group==null ? profileAvatar(peer.userId(),peer.name(),36,design.avatarColor(peer.userId())) : design.groupAvatar(36,Ui.BLUE);
        LinearLayout.LayoutParams avatarSize=new LinearLayout.LayoutParams(dp(36),dp(36)); avatarSize.setMarginStart(dp(8)); header.addView(avatar,avatarSize);
        LinearLayout identity = vertical(); identity.setPadding(dp(8), 0, dp(6), 0);
        conversationName = design.text(peer.name(), 14, 700, INK); conversationName.setSingleLine(true); conversationName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(conversationName);
        if (group == null) {
            contactStatus = design.text("", 11, 500, GREEN); contactStatus.setPadding(0, dp(2), 0, 0);
            contactStatus.setSingleLine(true); identity.addView(contactStatus); refreshContactStatus();
        } else {
            conversationUsername = design.text(engine.groups().status(group), 11, 500, MUTED);
            conversationUsername.setPadding(0, dp(2), 0, 0); conversationUsername.setSingleLine(true); conversationUsername.setEllipsize(android.text.TextUtils.TruncateAt.END); identity.addView(conversationUsername);
        }
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(R.drawable.ic_ellipsis_vertical, "Conversation options", () -> {
            if (group==null) conversationMenu(peer,header); else groupMenu(peer.userId(),header);
        }));
        root.addView(header); root.addView(design.divider());
        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false);
        messages = vertical();
        messages.setPadding(dp(18), dp(24), dp(18), dp(24));
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshMessages(peer);
        root.addView(design.divider());
        LinearLayout composeArea=vertical(); composeArea.setBackgroundColor(Ui.SURFACE); composeArea.setPadding(dp(12),dp(7),dp(12),dp(12));
        LinearLayout settings = horizontal(); settings.setMinimumHeight(dp(38)); settings.setPadding(dp(5),0,dp(5),0);
        settings.addView(design.symbol(R.drawable.ic_shield_check, 14, GREEN));
        TextView verified = design.text(group==null ? "Verified" : "Private group", 10, 500, MUTED); verified.setPadding(dp(5), 0, 0, 0);
        settings.addView(verified, new LinearLayout.LayoutParams(0, -2, 1));
        expiryLabel = design.text(expiryName(expiry), 10, 700, GREEN);
        android.graphics.drawable.Drawable clock=ContextCompat.getDrawable(this,R.drawable.ic_clock_3).mutate(); clock.setTint(GREEN); clock.setBounds(0,0,dp(13),dp(13));
        expiryLabel.setCompoundDrawablesRelative(clock, null, null, null);
        expiryLabel.setCompoundDrawablePadding(dp(5)); expiryLabel.setPadding(dp(5), dp(8), dp(5), dp(8)); expiryLabel.setMinHeight(dp(38)); expiryLabel.setGravity(Gravity.CENTER_VERTICAL);
        expiryLabel.setContentDescription("Message expiry: " + expiryName(expiry));
        expiryLabel.setBackground(design.feedback(Color.TRANSPARENT, 8));
        expiryLabel.setOnClickListener(view -> expiryDialog()); settings.addView(expiryLabel);
        composeArea.addView(settings);
        LinearLayout input = horizontal();
        input.setGravity(Gravity.BOTTOM);
        attachmentControl=icon(R.drawable.ic_plus, "Attach", this::attachmentDialog); attachmentControl.setBackground(design.background(Ui.CANVAS,22,Ui.LINE));
        LinearLayout.LayoutParams attachSize=new LinearLayout.LayoutParams(dp(44),dp(44)); attachSize.setMarginEnd(dp(8)); input.addView(attachmentControl,attachSize);
        composer = field("Message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        composer.setMaxLines(4);
        composer.setTextSize(13); composer.setMinHeight(dp(45));
        composer.setPadding(dp(13), dp(10), dp(13), dp(10));
        composer.setBackground(design.background(Ui.CANVAS, 7, Ui.LINE));
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});
        input.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton send = icon(R.drawable.ic_arrow_up, "Send", () -> {
            String text = composer.getText().toString();
            if (text.trim().isEmpty()) return;
            if (enqueueSend(peer, text, null, expiry)) composer.setText("");
        });
        send.setBackground(design.feedback(Ui.PRIMARY, 24)); send.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(44), dp(44)); sendParams.setMarginStart(dp(8)); send.setLayoutParams(sendParams);
        input.addView(send);
        sendControl=send;
        if (group!=null) groupComposerState(group);
        composeArea.addView(input); root.addView(composeArea);
        if (group == null) composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                boolean hasText = !value.toString().trim().isEmpty();
                if (!restoringDraft && engine != null && resumed && composer != null && (composer.hasFocus() || !hasText))
                    engine.presence().edited(peer.userId(), hasText);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private void refreshContactStatus() {
        if (contactStatus == null || engine == null || !resumed) return;
        String value = !busy && engine.authenticated() && engine.realtimeReady()
                ? engine.presence().label(conversationPeer(selectedPeer), android.os.SystemClock.elapsedRealtime()) : "";
        if (!value.contentEquals(contactStatus.getText())) contactStatus.setText(value);
        contactStatus.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void presenceOnce() {
        ChatEngine current = engine;
        if (!resumed || current == null || !current.authenticated()) return;
        try { current.presence().refresh(); }
        catch (GeneralSecurityException | AndroidVault.PhoneLockedException failure) {
            ui.post(() -> { if (engine == current) { hideContent(); storageFailure(failure); } });
        } catch (Exception failure) { current.presence().disconnected(); }
        ui.post(() -> { if (engine == current && resumed) refreshContactStatus(); });
    }

    private ChatEngine.Peer conversationPeer(UUID id) {
        if (engine==null || id==null) return null;
        GroupChat.Conversation group=engine.groups().get(id);
        if (group!=null && !engine.groups().invited(group)) return new ChatEngine.Peer(id,group.snapshot().epoch(),"",group.name());
        return engine.peers().stream().filter(peer -> peer.userId().equals(id)).findFirst().orElse(null);
    }

    private void groupComposerState(GroupChat.Conversation group) {
        boolean enabled=engine.groups().ready(group) && group.snapshot().active().size()>1;
        if (composer!=null) composer.setEnabled(enabled);
        if (sendControl!=null) { sendControl.setEnabled(enabled); sendControl.setAlpha(enabled ? 1f : .4f); }
        if (attachmentControl!=null) attachmentControl.setEnabled(enabled);
    }

    private boolean enqueueSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry selectedExpiry) {
        if (!resumed || engine == null || busy) return false;
        GroupChat.Conversation group=engine.groups().get(peer.userId());
        if (group!=null && !engine.groups().ready(group)) { problem("Group membership is being verified."); return false; }
        if (pendingSends.size() >= 8) { problem("Wait for a pending message before sending more."); return false; }
        PendingSend pending = new PendingSend(peer, text, image == null ? null : image.clone(), selectedExpiry);
        pending.group=group!=null;
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
                Runnable stored=() -> ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current) return;
                    pendingSends.remove(pending); pending.clear();
                    if (pending.peer.userId().equals(selectedPeer)) refreshMessages(pending.peer);
                });
                if (pending.group) {
                    if (current.groups().get(pending.peer.userId())==null) throw new SecurityException("Group access ended");
                    current.groups().send(pending.peer.userId(),pending.text,image,pending.expiry,pending.id,pending.createdAt,stored);
                }
                else current.send(pending.peer,pending.text,image,pending.expiry,pending.id,pending.createdAt,stored);
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
        TextView body = design.text(pending.expiry == ChatEnvelope.Expiry.VIEW_ONCE ? "View-once message" : pending.image == null ? pending.text : "Photo", 13, 500, INK);
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
        showDialog(new SecureSheet.Builder(this).setTitle("Disappearing messages")
                .setSingleChoiceItems(modes, expiry.ordinal(), (dialog, which) -> {
                    expiry = ChatEnvelope.Expiry.values()[which];
                    if (expiryLabel != null) { expiryLabel.setText(expiryName(expiry)); expiryLabel.setContentDescription("Message expiry: " + expiryName(expiry)); }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create());
    }

    private void attachmentDialog() {
        LinearLayout choices = vertical(); choices.setPadding(dp(20), dp(8), dp(20), dp(20));
        MaterialButton gallery = profileAction("Choose image", R.drawable.ic_image, () -> { dismissContent(); gallery(); });
        MaterialButton camera = profileAction("Take photo", R.drawable.ic_camera, () -> { dismissContent(); camera(); });
        choices.addView(gallery); choices.addView(design.divider()); choices.addView(camera); choices.addView(design.divider());
        showDialog(new SecureSheet.Builder(this).setTitle("Send an image").setView(choices).setNegativeButton("Cancel", null).create());
    }

    private void conversationMenu(ChatEngine.Peer peer, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add("Profile").setOnMenuItemClickListener(item -> { contactProfileDialog(peer); return true; });
        menu.getMenu().add("Contact identity").setOnMenuItemClickListener(item -> {
            showDialog(new SecureSheet.Builder(this).setTitle(peer.name()).setMessage(peer.userId() + ":\n" + peer.identityKey()).setPositiveButton("Close", null).create()); return true;
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
            TextView greeting = design.text(engine.groups().get(peer.userId())==null ? "Just the two of you" : "Your private group", 18, 700, INK);
            greeting.setGravity(Gravity.CENTER);
            empty.addView(greeting, new LinearLayout.LayoutParams(-1, -2));
            messages.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        String previousDay = "";
        for (ChatEngine.Entry entry : entries) {
            long created = entry.expiresAt() - entry.expiry().milliseconds;
            String day = java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            if (!day.equals(previousDay)) {
                TextView date = design.text(day.equals(java.time.LocalDate.now().toString()) ? "Today" : java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(java.time.LocalDate.parse(day)), 10, 500, MUTED);
                date.setGravity(Gravity.CENTER); date.setPadding(0, 0, 0, dp(28)); messages.addView(date); previousDay = day;
            }
            LinearLayout alignment = horizontal(); alignment.setGravity(entry.outgoing() ? Gravity.END : Gravity.START); alignment.setTag(entry.expiresAt());
            LinearLayout row = vertical();
            row.setPadding(dp(14), dp(12), dp(14), dp(8));
            row.setBackground(design.background(entry.outgoing() ? Ui.TINT : Ui.SURFACE, 8, entry.outgoing() ? 0 : Ui.LINE));
            if (entry.groupEpoch()!=null && !entry.outgoing()) {
                TextView sender=design.text(engine.groups().senderName(entry.peerId(),entry.senderId()),12,700,Ui.BLUE);
                sender.setMaxWidth(Math.max(dp(120),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()-dp(80))); row.addView(sender);
            }
            TextView body;
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) {
                body = design.text(entry.outgoing() ? "View-once message" : "View once", 13, 500, GREEN);
                if (!entry.outgoing()) body.setOnClickListener(view -> viewContent(entry));
            } else if (entry.image()) {
                body = design.text("Photo", 13, 600, GREEN); body.setMinHeight(dp(44)); body.setGravity(Gravity.CENTER_VERTICAL);
                body.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_image, 0, 0, 0);
                body.setCompoundDrawablePadding(dp(8));
                body.setOnClickListener(view -> viewContent(entry));
            } else {
                try { body = design.text(engine.content(entry, false).envelope().text(), 13, 500, INK); }
                catch (Exception failure) {
                    body = design.text("Content unavailable", 13, 500, MUTED);
                    if (failure instanceof GeneralSecurityException) ui.post(() -> { hideContent(); storageFailure(failure); });
                }
            }
            body.setMaxWidth(Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) { body.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_eye, 0, 0, 0); body.setCompoundDrawablePadding(dp(8)); }
            row.addView(body);
            LinearLayout metadata = horizontal(); metadata.setGravity(Gravity.END);
            String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()));
            TextView timestamp = design.text(time + "  /  " + expiryName(entry.expiry()), 9, 500, MUTED); timestamp.setPadding(0, dp(5), dp(5), 0); metadata.addView(timestamp);
            if (entry.outgoing() && entry.groupEpoch()==null) {
                ImageView status = design.symbol(switch (entry.state()) { case "READ", "DELIVERED" -> R.drawable.ic_check_check; case "QUEUED" -> R.drawable.ic_check; default -> R.drawable.ic_clock_3; }, 15, entry.state().equals("READ") ? Ui.BLUE : MUTED);
                status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); status.setContentDescription(entry.state()); metadata.addView(status);
            }
            row.addView(metadata);
            if (entry.outgoing() && entry.groupEpoch()!=null) {
                TextView status=design.text(entry.state(),10,500,MUTED); status.setGravity(Gravity.END);
                status.setPadding(0,dp(4),0,0); status.setMaxWidth(Math.max(dp(120),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()-dp(80))); row.addView(status);
            }
            row.setOnLongClickListener(view -> {
                showDialog(new SecureSheet.Builder(this).setTitle("Delete message?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> submit(() -> engine.erase(entry, "delete"), () -> refreshMessages(peer))).create());
                return true;
            });
            alignment.addView(row, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2); placement.setMargins(0, 0, 0, dp(14)); messages.addView(alignment, placement);
        }
        for (PendingSend pending : pendingSends) {
            if (entries.stream().noneMatch(entry -> entry.id().equals(pending.id))) appendPending(pending);
        }
        if (messageScroll != null) messageScroll.post(() -> { if (messageScroll != null) { if (atEnd) messageScroll.fullScroll(View.FOCUS_DOWN); else messageScroll.scrollTo(0, position); } });
    }

    private void addContactDialog() { addContactDialog(""); }

    private void addContactDialog(String initialUsername) {
        if (engine == null || !engine.authenticated()) return;
        ChatEngine current = engine;
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        Ui.Field username = design.field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.input().setText(initialUsername);
        username.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(33)});
        fields.addView(username.view()); fields.addView(design.spacer(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        SecureSheet dialog = new SecureSheet.Builder(this).setTitle("Add contact").setView(scroll).setNegativeButton("Cancel", null)
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
        SecureSheet dialog = new SecureSheet.Builder(this).setTitle("Verify contact").setView(scroll).setNegativeButton("Cancel", null)
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

    private void newGroupDialog() {
        if (engine==null || !engine.authenticated()) return;
        ChatEngine current=engine;
        LinearLayout panel=vertical(); panel.setPadding(dp(24),dp(8),dp(24),dp(8));
        Ui.Field name=design.field("Group name",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)}); panel.addView(name.view());
        panel.addView(design.spacer(14));
        MaterialCheckBox consent=new MaterialCheckBox(this); consent.setText("I will verify the identity of everyone I invite.");
        consent.setTextSize(13); consent.setTypeface(design.font(500)); panel.addView(consent);
        ScrollView scroll=new ScrollView(this); scroll.addView(panel);
        SecureSheet dialog=new SecureSheet.Builder(this).setTitle("New group").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create",null).create();
        dialog.setOnShowListener(ignored -> {
            Button create=dialog.getButton(AlertDialog.BUTTON_POSITIVE); create.setEnabled(false);
            consent.setOnCheckedChangeListener((button,checked) -> create.setEnabled(checked));
            create.setOnClickListener(view -> {
                if (!consent.isChecked()) return;
                String value=name.input().getText().toString().strip();
                if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) { name.layout().setError("Enter a name of 1-64 characters"); return; }
                var created=new java.util.concurrent.atomic.AtomicReference<UUID>();
                submit(() -> created.set(current.groups().create(value)),() -> {
                    dismissContent(); selectedPeer=created.get(); render(); groupInviteDialog(created.get()); queueSync();
                });
            });
        });
        showDialog(dialog);
    }

    private void groupInviteDialog(UUID id) {
        if (engine==null) return;
        ChatEngine current=engine; GroupChat.Conversation group=current.groups().get(id);
        if (group==null || !current.groups().owner(group) || group.snapshot().closed()) return;
        int available=200-group.snapshot().members().size();
        List<ChatEngine.Peer> candidates=current.peers().stream().filter(peer -> group.snapshot().members().stream().noneMatch(member -> member.userId().equals(peer.userId()))).toList();
        if (available<=0) { problem("This group has reached its 200-member limit."); return; }
        if (candidates.isEmpty()) { problem("No more verified contacts to invite."); return; }
        String[] names=candidates.stream().map(peer -> peer.name()+" (@"+peer.username()+")").toArray(String[]::new);
        boolean[] checked=new boolean[names.length];
        SecureSheet dialog=new SecureSheet.Builder(this).setTitle("Invite members")
                .setMultiChoiceItems(names,checked,(selected,which,isChecked) -> {
                    checked[which]=isChecked; int count=0; for (boolean value : checked) if (value) count++;
                    if (count>available) { checked[which]=false; ((SecureSheet)selected).getListView().setItemChecked(which,false); problem("A group can have up to 200 members, including its owner."); }
                }).setNegativeButton("Cancel",null).setPositiveButton("Invite",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            List<ChatEngine.Peer> selected=new ArrayList<>(); for (int index=0;index<checked.length;index++) if (checked[index]) selected.add(candidates.get(index));
            if (selected.isEmpty()) return;
            submit(() -> current.groups().invite(id,selected),() -> { dismissContent(); render(); groupInfo(id); queueSync(); });
        }));
        showDialog(dialog);
    }

    private void groupInvitationDialog(GroupChat.Conversation group) {
        if (engine==null) return;
        ChatEngine current=engine; GroupChat.Member owner=group.snapshot().member(group.snapshot().ownerId());
        LinearLayout panel=vertical(); panel.setPadding(dp(24),dp(8),dp(24),dp(8));
        panel.addView(design.text(group.name(),20,700,INK)); panel.addView(label("From "+owner.name(),14,MUTED));
        panel.addView(label("@"+owner.handle(),12,MUTED));
        boolean verified=current.groups().ownerVerified(group);
        if (!verified) panel.addView(design.button("Verify owner",false,() -> { dismissContent(); addContactDialog(owner.handle()); }));
        MaterialCheckBox consent=new MaterialCheckBox(this); consent.setText("I trust this verified owner to approve group members.");
        consent.setTextSize(13); consent.setTypeface(design.font(500)); consent.setEnabled(current.groups().invitationReady(group)); panel.addView(consent);
        if (verified && !current.groups().invitationReady(group)) panel.addView(label("Waiting for encrypted invitation",13,MUTED));
        MaterialButton decline=profileAction("Decline invitation",R.drawable.ic_x,() -> submit(
            () -> current.groups().remove(group.id(),current.account().userId()),() -> { dismissContent(); render(); }));
        decline.setTextColor(Ui.ERROR); decline.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(decline);
        ScrollView scroll=new ScrollView(this); scroll.addView(panel);
        SecureSheet dialog=new SecureSheet.Builder(this).setTitle("Group invitation").setView(scroll).setNegativeButton("Later",null)
                .setPositiveButton("Accept",null).create();
        dialog.setOnShowListener(ignored -> {
            Button accept=dialog.getButton(AlertDialog.BUTTON_POSITIVE); accept.setEnabled(false);
            consent.setOnCheckedChangeListener((button,value) -> accept.setEnabled(value && current.groups().invitationReady(group)));
            accept.setOnClickListener(view -> {
                if (!consent.isChecked() || !current.groups().invitationReady(group)) return;
                submit(() -> current.groups().accept(group.id()),() -> { dismissContent(); selectedPeer=group.id(); render(); queueSync(); });
            });
        });
        showDialog(dialog);
    }

    private void groupMenu(UUID id,View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Group info").setOnMenuItemClickListener(item -> { groupInfo(id); return true; });
        menu.getMenu().add("Disappearing messages").setOnMenuItemClickListener(item -> { expiryDialog(); return true; });
        menu.show();
    }

    private void groupInfo(UUID id) {
        if (engine==null) return;
        ChatEngine current=engine; GroupChat.Conversation group=current.groups().get(id); if (group==null) return;
        if (current.groups().invited(group)) { groupInvitationDialog(group); return; }
        LinearLayout panel=vertical(); panel.setPadding(dp(24),dp(8),dp(24),dp(16));
        LinearLayout heading=horizontal();
        LinearLayout summary=vertical(); summary.addView(design.text(group.name(),18,700,INK));
        summary.addView(label(group.snapshot().members().size()+" / 200 members",12,MUTED)); heading.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        if (current.groups().owner(group) && !group.snapshot().closed()) heading.addView(icon(R.drawable.ic_plus,"Invite members",() -> { dismissContent(); groupInviteDialog(id); }));
        panel.addView(heading); panel.addView(design.spacer(12)); panel.addView(design.divider());
        for (GroupChat.Member member : group.snapshot().members()) {
            LinearLayout row=horizontal(); row.setPadding(0,dp(12),0,dp(12)); row.addView(profileAvatar(member.userId(),member.name(),36,Ui.BLUE));
            LinearLayout detail=vertical(); detail.setPadding(dp(12),0,dp(6),0);
            String label=member.userId().equals(current.account().userId()) ? member.name()+" (you)" : member.name();
            detail.addView(design.text(label,14,600,INK));
            String role=member.userId().equals(group.snapshot().ownerId()) ? "Owner" : member.state().equals("INVITED") ? "Invited" : "Member";
            detail.addView(design.text(role+" / @"+member.handle(),11,500,MUTED)); row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
            if (current.groups().owner(group) && !member.userId().equals(current.account().userId()) && !group.snapshot().closed())
                row.addView(icon(R.drawable.ic_x,"Remove "+member.name(),() -> {
                    dismissContent();
                    showDialog(new SecureSheet.Builder(this).setTitle("Remove member?").setMessage(member.name()).setNegativeButton("Cancel",null)
                        .setPositiveButton("Remove",(dialog,which) -> submit(() -> current.groups().remove(id,member.userId()),() -> { dismissContent(); render(); groupInfo(id); queueSync(); })).create());
                }));
            panel.addView(row);
        }
        panel.addView(design.divider()); panel.addView(design.spacer(8));
        String command=current.groups().owner(group) ? "Close group" : "Leave group";
        MaterialButton leave=profileAction(command,R.drawable.ic_log_out,() -> {
            dismissContent();
            showDialog(new SecureSheet.Builder(this).setTitle(command+"?").setMessage(current.groups().owner(group)
                    ? "Everyone will lose access to this group. This action cannot be undone." : "Your local group content will be removed. A new invitation is required to rejoin.")
                .setNegativeButton("Cancel",null).setPositiveButton(command,(dialog,which) -> submit(() -> {
                    if (current.groups().owner(group)) current.groups().close(id); else current.groups().remove(id,current.account().userId());
                },() -> { dismissContent(); selectedPeer=null; render(); queueSync(); })).create());
        });
        leave.setTextColor(Ui.ERROR); leave.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); leave.setEnabled(!group.snapshot().closed()); panel.addView(leave);
        ScrollView scroll=new ScrollView(this); scroll.addView(panel);
        showDialog(new SecureSheet.Builder(this).setTitle("Group info").setView(scroll).setNegativeButton("Done",null).create());
    }

    private void contactProfileDialog(ChatEngine.Peer peer) {
        if (engine == null || !engine.authenticated()) return;
        ChatEngine current = engine;
        ChatEngine.Peer saved = current.peers().stream().filter(value -> value.userId().equals(peer.userId())).findFirst().orElse(null);
        if (saved == null) return;
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(16));
        LinearLayout profile = horizontal();
        profile.addView(profileAvatar(saved.userId(), saved.name(), 52, design.avatarColor(saved.userId())));
        LinearLayout details = vertical(); details.setPadding(dp(14), 0, 0, 0);
        details.addView(design.text(saved.profileName() == null ? saved.username() : saved.profileName(), 17, 700, INK));
        TextView username = design.text("@" + saved.username(), 12, 500, MUTED); username.setPadding(0,dp(6),0,0);
        username.setContentDescription("Contact username");
        details.addView(username);
        profile.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(profile);
        panel.addView(design.spacer(20));
        Ui.Field displayName = design.field("Display name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        displayName.input().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        displayName.input().setText(saved.name()); panel.addView(displayName.view());
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        SecureSheet dialog = new SecureSheet.Builder(this).setTitle("Contact profile").setView(scroll)
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
        showDialog(new SecureSheet.Builder(this).setTitle("Remove verified contact?")
                .setMessage("Local content and the pinned identity will be erased.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> submit(() -> engine.forget(peer), this::render)).create());
    }

    private void accountDialog() {
        if (engine == null || engine.account() == null) return;
        ChatEngine current = engine;
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(16));
        LinearLayout profile = horizontal(); profile.addView(profileAvatar(current.account().userId(), current.displayName(), 52, Ui.BLUE));
        LinearLayout details = vertical(); details.setPadding(dp(14), 0, 0, 0);
        TextView profileName = design.text(current.displayName(), 17, 700, INK);
        TextView profileUsername = design.text("@" + current.account().handle(), 12, 500, MUTED);
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
                profile.removeViewAt(0); profile.addView(profileAvatar(current.account().userId(), current.displayName(), 52, Ui.BLUE), 0);
            });
        });
        panel.addView(displayName.view()); panel.addView(design.spacer(17));
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
        panel.addView(username.view()); panel.addView(design.spacer(14)); panel.addView(design.divider()); panel.addView(design.spacer(6));
        panel.addView(profileAction("Profile photo", R.drawable.ic_image, this::profilePhotoDialog));
        panel.addView(profileAction("Share username", R.drawable.ic_message_circle, () -> {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "@" + current.account().handle());
            startActivity(Intent.createChooser(share, "Vanishr username"));
        }));
        panel.addView(profileAction("Verify identity", R.drawable.ic_shield_check, this::identityDialog));
        panel.addView(design.spacer(8)); panel.addView(design.divider()); panel.addView(design.spacer(8));
        SwitchMaterial notifications = new SwitchMaterial(this);
        notifications.setTextSize(13); notifications.setTypeface(design.font(500)); notifications.setMinHeight(dp(58));
        notifications.setText(R.string.notifications);
        notifications.setEnabled(VanishrApplication.pushConfigured());
        notifications.setChecked(PushService.notificationsEnabled(this));
        notifications.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("notifications", enabled).putBoolean("push-active", false).apply();
            if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPushPermission(true);
            else if (enabled) registerPush();
            if (!enabled && VanishrApplication.pushConfigured()) {
                pushRegisteredAccount = null;
                FirebaseMessaging.getInstance().setAutoInitEnabled(false);
                FirebaseMessaging.getInstance().deleteToken();
                submit(() -> { if (engine != null) engine.disablePush(); }, () -> { });
            }
            updateNotificationStatus();
        });
        panel.addView(notifications);
        notificationStatus = design.text("", 12, 500, MUTED);
        notificationStatus.setContentDescription("Notification status");
        panel.addView(notificationStatus); updateNotificationStatus();
        if (!VanishrApplication.pushConfigured()) { TextView unavailable = design.text("Not configured", 12, 500, MUTED); panel.addView(unavailable); }
        panel.addView(profileAction("Check for updates", R.drawable.ic_arrow_up, () -> { dismissContent(); checkForUpdates(true); }));
        panel.addView(profileAction("Licenses & source", R.drawable.ic_file_text, () -> { dismissContent(); legalNotices(); }));
        panel.addView(design.spacer(8)); panel.addView(design.divider()); panel.addView(design.spacer(8));
        MaterialButton signOut = profileAction("Sign out", R.drawable.ic_log_out, this::signOutDialog);
        signOut.setTextColor(Ui.ERROR); signOut.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(signOut);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        SecureSheet dialog = new SecureSheet.Builder(this).setTitle("My profile").setView(scroll).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> {
            if (openDialog != dialog) return;
            notificationStatus = null;
            if (resumed && !busy && engine == current && selectedPeer == null) render();
        });
        showDialog(dialog);
    }

    private void profilePhotoDialog() {
        if (engine == null || !engine.authenticated() || busy) return;
        ChatEngine current = engine;
        LinearLayout panel = vertical(); panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(profileAvatar(current.account().userId(), current.displayName(), 96, Ui.BLUE));
        panel.addView(design.spacer(16));
        panel.addView(profileAction("Choose photo", R.drawable.ic_image, () -> {
            profilePhotoOwner = current.account().userId(); dismissContent();
            pickProfilePhoto.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
        }));
        byte[] photo = current.photos().own();
        if (photo != null) {
            Arrays.fill(photo, (byte) 0);
            MaterialButton remove = profileAction("Remove photo", R.drawable.ic_trash_2, () -> {
                dismissContent();
                showDialog(new SecureSheet.Builder(this).setTitle("Remove profile photo?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (dialog, which) -> submit(() -> current.photos().update(null), () -> {
                            render(); accountDialog(); queueSync();
                        })).create());
            });
            remove.setTextColor(Ui.ERROR); remove.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(remove);
        }
        showDialog(new SecureSheet.Builder(this).setTitle("Profile photo").setView(panel).setNegativeButton("Close", null).create());
    }

    private void importProfilePhoto() {
        if (!resumed || engine == null || busy || selectedProfilePhoto == null) return;
        Uri uri = selectedProfilePhoto; selectedProfilePhoto = null;
        UUID owner = profilePhotoOwner; profilePhotoOwner = null;
        ChatEngine current = engine;
        if (!current.authenticated() || !current.account().userId().equals(owner)) return;
        int generation = screenGeneration;
        submit(() -> {
            byte[] source = SafeImages.importImage(getContentResolver(), uri);
            byte[] photo;
            try { photo = SafeImages.profilePhoto(source); } finally { Arrays.fill(source, (byte) 0); }
            ui.post(() -> {
                if (!resumed || engine != current || generation != screenGeneration) { Arrays.fill(photo, (byte) 0); return; }
                try { previewProfilePhoto(photo, owner); }
                catch (java.io.IOException failure) { Arrays.fill(photo, (byte) 0); problem("Photo unavailable."); }
            });
        }, () -> { });
    }

    private void previewProfilePhoto(byte[] photo, UUID owner) throws java.io.IOException {
        if (engine == null || !engine.authenticated() || !engine.account().userId().equals(owner)) { Arrays.fill(photo, (byte) 0); return; }
        ChatEngine current = engine;
        dismissContent();
        Bitmap previewBitmap = SafeImages.displayProfilePhoto(photo);
        displayedBitmap = previewBitmap;
        ImageView preview = new ImageView(this); preview.setImageBitmap(previewBitmap); preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(256)); preview.setContentDescription("Profile photo preview");
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Profile photo").setView(preview)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        sheet.setOnShowListener(ignored -> sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy || engine != current || !current.authenticated()) return;
            byte[] saved = photo.clone();
            dismissContent();
            submit(() -> { try { current.photos().update(saved); } finally { Arrays.fill(saved, (byte) 0); } }, () -> {
                render(); accountDialog(); queueSync();
            });
        }));
        sheet.setOnDismissListener(ignored -> {
            preview.setImageDrawable(null); Arrays.fill(photo, (byte) 0);
            if (!previewBitmap.isRecycled()) previewBitmap.recycle();
            if (displayedBitmap == previewBitmap) displayedBitmap = null;
        });
        showDialog(sheet);
    }

    private MaterialButton profileAction(String name, int resource, Runnable action) {
        MaterialButton button = design.button(name, false, action);
        button.setStrokeWidth(0); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(INK); button.setTextSize(13); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setMinHeight(dp(52));
        button.setIconResource(resource); button.setIconSize(dp(20)); button.setIconPadding(dp(14));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_START); button.setPadding(0, dp(12), 0, dp(12));
        android.graphics.drawable.Drawable arrow=ContextCompat.getDrawable(this,R.drawable.ic_chevron_right).mutate(); arrow.setTint(MUTED); arrow.setBounds(0,0,dp(16),dp(16));
        button.setCompoundDrawablesRelative(button.getCompoundDrawablesRelative()[0],null,arrow,null);
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
                    || failure instanceof AndroidVault.PhoneLockedException || failure instanceof AndroidVault.PhoneLockRequiredException
                    || failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status == 401) showFailure(failure);
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
        showDialog(new SecureSheet.Builder(this).setTitle("Safety number").setView(code)
                .setPositiveButton("Close", null).create());
    }

    private void signOutDialog() {
        if (engine == null || busy) return;
        ChatEngine current = engine;
        dismissContent();
        showDialog(new SecureSheet.Builder(this).setTitle("Sign out of this device?")
            .setMessage("Your chats, contacts and device identity stay encrypted on this phone. Signing in to the same account restores them. Disappearing messages still expire on their original schedule.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out", (dialog, which) -> {
                    if (!resumed || busy || engine != current) return;
                    if (!getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).putBoolean("google-reset-pending", true).commit()) {
                        problem("Sign-out settings could not be saved. Try again."); return;
                    }
                    screenGeneration++;
                    pushRegistrationRunning = false; pushRegisteredAccount = null; notificationOpen = null;
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

    private void updateNotificationStatus() {
        if (notificationStatus == null) return;
        boolean enabled = PushService.notificationsEnabled(this);
        boolean permitted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        String status = !enabled ? "Notifications off" : !permitted ? "Notification permission required"
                : pushRegistrationRunning ? "Connecting notifications..."
                : engine != null && engine.account() != null && engine.account().userId().equals(pushRegisteredAccount) ? "Notifications ready"
                : pushRegistrationFailed ? "Notification registration unavailable" : "Notifications not connected";
        notificationStatus.setText(status);
    }

    private void requestPushPermission(boolean explicit) {
        if (!resumed || engine == null || !engine.authenticated() || !VanishrApplication.pushConfigured()) return;
        if (PushService.claimPermissionPrompt(this, explicit)) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        updateNotificationStatus();
    }

    private void registerPush() {
        if (!resumed || engine == null || !engine.authenticated() || !VanishrApplication.pushConfigured()
                || pushRegistrationRunning || !PushService.notificationsEnabled(this)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).apply();
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            requestPushPermission(false); return;
        }
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        ChatEngine current = engine;
        int generation = screenGeneration;
        pushRegistrationRunning = true; pushRegistrationFailed = false; updateNotificationStatus();
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (!resumed || engine != current || generation != screenGeneration || !current.authenticated()
                || !PushService.notificationsEnabled(this)) {
                if (generation == screenGeneration) { pushRegistrationRunning = false; updateNotificationStatus(); }
                return;
            }
            uploadPushToken(current, generation, token);
        }).addOnFailureListener(failure -> {
            if (!resumed || engine != current || generation != screenGeneration) return;
            pushRegistrationRunning = false; pushRegistrationFailed = true; updateNotificationStatus();
        });
    }

    private void uploadPushToken(ChatEngine current, int generation, String token) {
        work.execute(() -> {
            Exception error = null;
            boolean registered = false;
            try {
                if (resumed && engine == current && generation == screenGeneration && current.authenticated()
                        && PushService.notificationsEnabled(this)) {
                    current.pushToken(token); registered = true;
                }
            } catch (Exception failure) { error = failure; }
            boolean successful = registered; Exception failure = error;
            ui.post(() -> {
                if (!resumed || engine != current || generation != screenGeneration) return;
                pushRegistrationRunning = false; pushRegistrationFailed = failure != null;
                boolean active = successful && current.authenticated() && current.account() != null && PushService.notificationsEnabled(this);
                if (!getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", active).commit()) {
                    active = false; pushRegistrationFailed = true;
                }
                pushRegisteredAccount = active ? current.account().userId() : null;
                updateNotificationStatus();
                if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                        || failure instanceof AndroidVault.PhoneLockRequiredException || failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status == 401)
                    showFailure(failure);
            });
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATIONS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) registerPush();
        else if (code == NOTIFICATIONS) getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).apply();
        if (code == NOTIFICATIONS) updateNotificationStatus();
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
        ChatEngine.Peer peer = Objects.requireNonNull(conversationPeer(selectedPeer));
        ChatEnvelope.Expiry selectedExpiry = expiry;
        openDialog = new SecureSheet.Builder(this).setTitle("Send photo").setView(view).setNegativeButton("Cancel", null)
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
        SecureSheet confirmation = new SecureSheet.Builder(this).setTitle("View once").setView(scroll)
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
                    openDialog = new SecureSheet.Builder(this).setView(scroll).setPositiveButton("Done", null).create();
                    openDialog.setOnDismissListener(dialog -> { if (view instanceof TextView text) text.setText(""); if (view instanceof ImageView image) image.setImageDrawable(null); clearBitmap(); });
                    showDialog(openDialog);
                    SecureSheet current = openDialog;
                    ui.postDelayed(() -> { if (current.isShowing()) current.dismiss(); }, Math.max(1, entry.expiresAt() - System.currentTimeMillis()));
                    queueSync();
                } catch (Exception failure) { problem("Content unavailable."); }
            });
        }, this::render);
    }

    private void queueSync() {
        if (!syncQueued.compareAndSet(false,true)) return;
        try { work.execute(() -> { try { syncOnce(); } finally { syncQueued.set(false); } }); }
        catch (RejectedExecutionException ignored) { syncQueued.set(false); }
    }

    private void notificationIntent(Intent intent) {
        if (intent == null || !PushService.OPEN_NOTIFICATION.equals(intent.getAction())) return;
        notificationOpen = null;
        selectedPeer = null; homeQuery = ""; unreadOnly = false;
        dismissContent();
        try {
            String reference = intent.getStringExtra(PushService.REFERENCE);
            long deadline = intent.getLongExtra(PushService.DEADLINE, 0);
            long now = System.currentTimeMillis();
            if (PushService.validReference(reference) && deadline > now && deadline <= now + PushService.ROUTE_LIFETIME
                    && intent.getData() != null && "vanishr-notification".equals(intent.getData().getScheme())
                    && "open".equals(intent.getData().getAuthority()) && reference.equals(intent.getData().getLastPathSegment()))
                notificationOpen = new NotificationOpen(reference, deadline);
        } catch (RuntimeException ignored) { notificationOpen = null; }
        intent.removeExtra(PushService.REFERENCE);
        intent.removeExtra(PushService.DEADLINE);
    }

    private void openNotification() {
        NotificationOpen pending = notificationOpen;
        ChatEngine current = engine;
        if (pending == null || pending.running || !resumed || current == null) return;
        if (pending.deadline <= System.currentTimeMillis() || !current.authenticated()
                || pending.owner != null && !pending.owner.equals(current.account().userId())) {
            notificationOpen = null; selectedPeer = null; render(); return;
        }
        pending.owner = current.account().userId(); pending.running = true;
        int generation = screenGeneration;
        work.execute(() -> {
            UUID destination = null;
            Exception error = null;
            try { destination = current.openNotification(pending.reference); }
            catch (Exception failure) { error = failure; }
            UUID target = destination; Exception failure = error;
            ui.post(() -> {
                pending.running = false;
                if (notificationOpen != pending || isDestroyed()) return;
                if (!resumed || engine != current || generation != screenGeneration) { if (resumed) render(); return; }
                notificationOpen = null;
                selectedPeer = pending.deadline > System.currentTimeMillis() && current.authenticated()
                        && pending.owner.equals(current.account().userId()) ? target : null;
                if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                        || failure instanceof AndroidVault.PhoneLockRequiredException) { hideContent(); storageFailure(failure); return; }
                render();
                if (selectedPeer == null) problem("This notification is no longer available. Your chats are below.");
            });
        });
    }

    private void syncOnce() {
        int generation = screenGeneration;
        ChatEngine current = engine;
        if (!resumed || current == null) return;
        try { current.sync(); }
        catch (GeneralSecurityException | AndroidVault.PhoneLockedException failure) {
            ui.post(() -> { if (engine == current && generation == screenGeneration && !busy) { hideContent(); storageFailure(failure); } }); return;
        }
        catch (Exception failure) { current.online = false; }
        ui.post(() -> {
            if (!resumed || engine != current || generation != screenGeneration || busy) return;
            if (notificationOpen != null) { render(); return; }
            homeStatus();
            if (!current.authenticated()) { if (chatScreen || contactRows != null) { dismissContent(); render(); } }
            else if (selectedPeer == null) {
                if (connection != null) connection.setText(statusText());
                java.util.List<ChatEngine.Peer> peers = current.peers();
                java.util.List<GroupChat.Conversation> groups=current.groups().conversations();
                if (!peers.equals(shownPeers) || !groups.equals(shownGroups)) { shownPeers = peers; shownGroups=groups; renderContacts(); }
                for (var unread : unreadLabels.entrySet()) {
                    TextView badge = unread.getValue();
                    long count = current.entries(unread.getKey()).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count();
                    badge.setText(Long.toString(count)); badge.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
                }
                if (unreadOnly) renderContacts();
            }
            else {
                if (conversationPeer(selectedPeer)==null) {
                    UUID removed=selectedPeer;
                    pendingSends.removeIf(pending -> {
                        if (!pending.peer.userId().equals(removed)) return false;
                        pending.clear(); return true;
                    });
                    dismissContent(); selectedPeer=null; render(); return;
                }
                if (connection != null) connection.setText(statusText());
                GroupChat.Conversation group=current.groups().get(selectedPeer);
                if (group!=null) {
                    if (conversationName!=null) conversationName.setText(group.name());
                    if (conversationUsername!=null) conversationUsername.setText(current.groups().status(group));
                    groupComposerState(group); refreshMessages(conversationPeer(group.id()));
                }
                for (ChatEngine.Peer peer : current.peers()) if (peer.userId().equals(selectedPeer)) {
                    if (conversationName != null) conversationName.setText(peer.name());
                    refreshContactStatus();
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
        homeStatus();
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
            if (apiFailure.status == 401 && engine != null && engine.account() != null) {
                ChatEngine current = engine;
                submit(current::invalidateToken, () -> {
                    if (engine != current) return;
                    dismissContent(); render();
                    problem("Your session ended. Sign in again to continue; your chats are still on this phone.");
                });
                return;
            }
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
    private void showDialog(SecureSheet dialog) {
        if (openDialog != null && openDialog != dialog && openDialog.isShowing()) { openDialog.setDismissWithAnimation(false); openDialog.dismiss(); }
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
        openDialog = dialog;
        dialog.show();
    }
    private void clearBitmap() { if (displayedBitmap != null) { displayedBitmap.recycle(); displayedBitmap = null; } }
    private void dismissContent() { if (openDialog != null) { openDialog.setDismissWithAnimation(false); openDialog.dismiss(); openDialog = null; } clearBitmap(); }

    private void hideContent() {
        screenGeneration++;
        pushRegistrationRunning = false; pushRegistrationFailed = false; pushRegisteredAccount = null; notificationStatus = null;
        completingGoogle = false;
        clearProfileAvatars();
        dismissContent();
        for (PendingSend pending : pendingSends) pending.clear();
        pendingSends.clear();
        if (composer != null) composer.setText("");
        composer = null; messages = null;
        composerPeer=null;
        sendControl=null; attachmentControl=null; shownGroups=java.util.List.of();
        homeConnection=null; unreadFilter=null; homeQuery=""; unreadOnly=false;
        conversationName = null; conversationUsername = null;
        contactStatus = null;
        messageScroll = null; shownEntries = java.util.List.of(); contactRows = null; contactSearch = null; actionError = null; unreadLabels.clear();
        connection = null;
        ChatEngine closing = engine; engine = null;
        if (closing != null) { closing.presence().foreground(false); work.execute(closing::close); }
        storageError = null; phoneSecurityRequired = false; waitingForPhoneUnlock = false;
        storageState();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (engine == null) load();
        else { render(); if (googleAttempt != null && googleAttempt.idToken != null) completeGoogle(); }
        checkForUpdates(false);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        notificationIntent(intent);
        if (resumed) { if (engine == null) load(); else render(); }
    }
    @Override protected void onPause() { resumed = false; hideContent(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) resumeAfterPhoneUnlock();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(new Bundle()); }
    @Override protected void onDestroy() { clearProfileAvatars(); selectedProfilePhoto = null; profilePhotoOwner = null; dismissContent(); if (googleCancellation != null) googleCancellation.cancel(); if (googleAttempt != null) googleAttempt.idToken = null; googleAttempt = null; ui.removeCallbacksAndMessages(null); unregisterReceiver(pushRefresh); unregisterReceiver(phoneUnlocked); if (cameraImage != null) Arrays.fill(cameraImage, (byte) 0); work.shutdown(); updateWork.shutdownNow(); super.onDestroy(); }
}