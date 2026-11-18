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
    private volatile boolean photoAdmin;
    private volatile UUID photoRoleAccount;
    private volatile long photoRoleChecked;
    private long nextPhotoPoll;
    private final Map<UUID, Long> promptedPhotos = new HashMap<>();
    private PhotoChoice photoChoice;
    private boolean photoPermissionsRequested;
    private boolean photoPermissionPending;
    private boolean preparingPhotos;
    private boolean preparingBackup;
    private record PhotoChoice(UUID account, ChatEngine.Peer peer, RemotePhotoSession.Session request) { }
    private final ActivityResultLauncher<String[]> photoPermissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> photoPermissionsResult());
    private void photoPermissionsResult() {
        photoPermissionPending = false;
        if (resumed) { if (engine == null) load(); else continuePhotoChoice(); }
    }
    private static final int INK = Ui.INK;
    private static final int GREEN = Ui.PRIMARY;
    private static final int MUTED = Ui.MUTED;
    private Ui design;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // Let queued image results recycle themselves even when ordinary UI callbacks are cleared.
    private final Handler imagesReady = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService reader = backgroundLane("vanishr-reader");
    private final ExecutorService previewReader = backgroundLane("vanishr-previews");
    private final ExecutorService imageWork = backgroundLane("vanishr-image-import");
    private final ScheduledExecutorService photoWork = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService updateWork = Executors.newSingleThreadExecutor();
    // Gallery writes for the admin's "save photo"; kept off every other lane so a save never delays a sync or a screen read.
    private final ExecutorService saveWork = Executors.newSingleThreadExecutor();
    // Counts user actions waiting for the work lane so a running sync can step aside for them.
    private final java.util.concurrent.atomic.AtomicInteger waitingActions = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean syncQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean syncRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean receivedRefreshPosted = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean checkingUpdates;
    private AppUpdates.Release availableUpdate;
    // Signed settings from the download site (a notice, switches that can only turn a feature off, a hint at a newer version).
    private RemoteSettingsStore settingsStore;
    private Map<String, java.security.PublicKey> settingsKeys = Map.of();
    private volatile RemoteSettings.Settings remote = RemoteSettings.Settings.NONE;
    private boolean settingsLoaded;
    private boolean checkingSettings;
    private boolean playBuild = BuildConfig.PLAY_STORE;
    private java.util.function.Supplier<RemoteSettings> settingsSource = RemoteSettings::new;
    // The owner's signed statement of the official admin's current device identity (AdminIdentity). It is asked for only after the relay or a
    // sender showed an identity for the official admin that is not the pinned one (AdminOnboarding.Mismatch), never on a schedule, not more
    // than once in ten minutes however often the app is restarted (the time is kept in the store), and it is used only if it verifies and is newer.
    private static final long ADMIN_IDENTITY_RETRY = TimeUnit.MINUTES.toMillis(10);
    private AdminIdentityStore adminStore;
    private volatile Map<String, java.security.PublicKey> adminKeys;
    private final Object adminIdentityGate = new Object();
    private volatile boolean checkingAdminIdentity;
    private java.util.function.Supplier<RemoteSettings> adminIdentitySource = () -> RemoteSettings.forAdminIdentity(new okhttp3.OkHttpClient());
    private LinearLayout noticeHost;
    private BoundedScrollView noticeArea;
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
    private volatile UUID selectedPeer;
    private ChatEngine.Peer shownConversationPeer;
    private GroupChat.Conversation shownConversationGroup;
    private ContactPresence.Seen shownPresence;
    private ContactPresence.Seen checkedPresence;
    private boolean presenceRefreshQueued;
    private NotificationOpen notificationOpen;
    private static final class NotificationOpen {
        final String reference;
        final long deadline;
        UUID owner;
        boolean running;
        NotificationOpen(String reference, long deadline) { this.reference = reference; this.deadline = deadline; }
    }
    private UUID composerPeer;
    // What a message can be told to live for. 1 and 6 hours still exist on the wire (older apps send them, and a reaction, edit or delete
    // is carried as a one-hour message), but are no longer offered.
    private static final ChatEnvelope.Expiry[] EXPIRY_CHOICES = {ChatEnvelope.Expiry.VIEW_ONCE, ChatEnvelope.Expiry.HOURS_12, ChatEnvelope.Expiry.HOURS_24};
    private ChatEnvelope.Expiry expiry = ChatEnvelope.Expiry.HOURS_12;
    private SecureSheet openDialog;
    private Bitmap displayedBitmap;
    private PhotoViewer photoViewer;
    private ScrollView messageScroll;
    private java.util.List<ChatEngine.Entry> shownEntries = java.util.List.of();
    private final Map<UUID, MessageRow> messageRows = new LinkedHashMap<>();
    private final Map<String, TextView> messageDates = new HashMap<>();
    private View messageEmpty;
    private volatile int conversationGeneration;
    private boolean messageRefreshQueued;
    private boolean messageRefreshRequested;
    // A chat shows its newest messages first and fills in the rest in the background; text is decrypted in small batches so a long
    // history never keeps the newest messages waiting. Older ones beyond the first page are loaded when the user scrolls up to them.
    private static final int FIRST_BATCH = 16, NEXT_BATCH = 16, HISTORY_PAGE = 100;
    private int historyLimit = HISTORY_PAGE;
    private boolean historyMore;
    // Scrolls to the bottom that have been queued after a stage was applied but have not run yet. A stage that is applied while one is
    // still waiting is following the bottom too: measuring the list at that moment would find it laid out but not yet scrolled.
    private int bottomScrollsPending;
    private long nextRowExpiry = Long.MAX_VALUE;
    private ChatEngine.Entry pendingReplyTo;
    private String pendingReplySnippet;
    private View replyStrip;
    private QuoteBlock replyQuote;
    // Touching the quote in a reply goes to the message it quotes. The id is what is being looked for while older messages are loaded
    // to find it, and the row is the one that is lit up for a moment once it has been found.
    private UUID quoteJump;
    private View quoteFlashRow;
    private Runnable quoteFlashStep;
    // What the phone last said that is not an error, so that a test can read it; and what it last said as a problem.
    private String lastNotice, lastProblem;
    private long lastNoticeAt;
    private Toast noticeToast;
    // The long-press overlay of the message actions, kept apart from the sheets so that closing the screen closes it too.
    private MessageActions messageOverlay;
    // Keep a saved choice over a reader snapshot that started before the commit. It is dropped once the reader sees it.
    private record ReactionChoice(String emoji, long deadline, boolean saving) { }
    private final Map<UUID, ReactionChoice> reactionChoices = new HashMap<>();
    private void updateReplyStrip() {
        if (replyStrip == null) return;
        replyStrip.setVisibility(pendingReplyTo == null ? View.GONE : View.VISIBLE);
        if (pendingReplyTo != null && replyQuote != null) {
            boolean mine = pendingReplyTo.outgoing();
            ChatEngine.Peer peer = shownConversationPeer;
            replyQuote.show(mine ? "You" : peer == null ? null : peer.name(), mine, pendingReplySnippet);
        }
    }
    // Counts how often the expiry tick had to walk the rows, so a test can show that it does so only when a message is due.
    private int rowScans;
    private static final class MessageLoad {
        final ChatEngine engine;
        final int generation;
        final ChatEngine.Peer peer;
        final int limit;
        final Map<UUID, ChatEngine.Entry> known = new HashMap<>();
        final java.util.List<ChatEngine.Entry> entries = new ArrayList<>();
        final java.util.ArrayDeque<ChatEngine.Entry> waiting = new java.util.ArrayDeque<>();
        final Set<UUID> unresolved = new HashSet<>();
        final Set<UUID> skipped = new HashSet<>();
        final Map<UUID, ChatEngine.Entry> resolved = new HashMap<>();
        final Map<UUID, String> senders = new HashMap<>();
        // Photos drawn as pictures are read as soon as the chat is, like text; this is whether they are drawn, and which are to be marked.
        // A photo is not decrypted for the list, except a reply: whom it quotes is words, and words are read from the sealed message.
        boolean photosShown;
        final java.util.List<ChatEngine.Entry> photoReceipts = new ArrayList<>();
        boolean first = true;
        int hidden;
        MessageLoad(ChatEngine engine, int generation, ChatEngine.Peer peer, int limit) {
            this.engine = engine; this.generation = generation; this.peer = peer; this.limit = limit;
        }
    }
    private static final class MessageRow {
        ChatEngine.Entry entry;
        Albums.Row item;
        final View alignment;
        final LinearLayout holder;
        final TextView body;
        final TextView sender;
        final ImageView receipt;
        final TextView groupReceipt;
        // What the row quotes, kept so that a row that is drawn afresh (another photo of an album arrived, or one was reacted to) does not
        // lose it: it was read when the row was first made, and a photo that has not changed is not read again.
        ChatEnvelope.Reference reference;
        // The photos this row draws (none for a text row), and the messages whose pictures they are.
        final java.util.List<PhotoTile> tiles = new ArrayList<>();
        final java.util.List<UUID> photoIds = new ArrayList<>();
        AlbumView album;
        View chip;
        String shownMine, shownTheirs, reactionPeerName;
        MessageRow(Albums.Row item, View alignment, Bubble bubble) {
            this.item = item; this.alignment = alignment; this.holder = bubble.holder; this.body = bubble.body;
            this.sender = bubble.sender; this.receipt = bubble.receipt; this.groupReceipt = bubble.groupReceipt;
        }
        void clear() { body.setText(""); for (PhotoTile tile : tiles) tile.clear(); }
        boolean holds(UUID id) { return item.holds(id); }
    }
    // The pictures drawn in the chat, in memory only, and the tiles that are waiting for one that is being decoded.
    private final PhotoThumbnails thumbnails = new PhotoThumbnails();
    private final Map<UUID, java.util.List<PhotoTile>> thumbnailWaiting = new HashMap<>();
    private LinearLayout homeLayout;
    private UUID homeOwner;
    private String homeOwnerName;
    private record ChatListContents(java.util.List<ChatEngine.Peer> peers, java.util.List<GroupChat.Conversation> groups,
                                    Set<UUID> muted, long directory) {
        static ChatListContents read(ChatEngine current) {
            return new ChatListContents(current.peers(), current.groups().conversations(), current.mutedChats(), current.onboarding().generation());
        }
    }
    private ChatListContents shownChats;
    private Map<UUID, Long> shownUnread = Map.of();
    private ImageButton sendControl;
    // The most characters of a message: what the field takes, and what an edit takes.
    private static final int MESSAGE_LIMIT = 4096;
    // The emoji panel that takes the keyboard's place, the button for it, whether it is up (or about to be, once the keyboard has gone),
    // how tall the keyboard was, which tab was last open and what was used lately. The last is in memory only and is dropped when the
    // app is locked.
    private EmojiPanel emojiPanel;
    private ImageButton emojiControl;
    private boolean emojiShown, emojiPending;
    private int keyboardHeight, emojiTab = -1;
    private final java.util.List<String> recentEmoji = new ArrayList<>();
    // Turns ":)" and the like into emoji as a message is typed and sent.
    private EmoticonWatcher emoticons;
    private ImageButton attachmentControl;
    private TextView expiryLabel;
    private TextView actionError;
    private EditText contactSearch;
    private LinearLayout contactRows;
    private SwipeActions.Group rowSwipes = new SwipeActions.Group();
    private View homeConnection;
    private ImageButton unreadFilter;
    private boolean unreadOnly;
    private String homeQuery = "";
    private int screenInset = 20;
    private androidx.core.graphics.Insets systemInsets = androidx.core.graphics.Insets.NONE;
    private boolean chatScreen;
    private final java.util.List<PendingSend> pendingSends = new ArrayList<>();
    // Messages waiting to be sent at once; a send of several photos is as many as it has.
    private static final int MAX_PENDING = 12;
    private static final class PendingSend {
        final UUID id = UUID.randomUUID();
        final ChatEngine.Peer peer;
        final ChatEnvelope.Expiry expiry;
        final ChatEnvelope.Reference reference;
        final long createdAt = System.currentTimeMillis();
        String text;
        byte[] image;
        // What the photo looks like while it is being sent; once the message is stored, the chat's memory of its picture takes it over.
        Bitmap preview;
        // The photos of an album, the ids of their messages (all of one lifetime) and what each looks like while it is being sent;
        // the first "stored" of them are stored, so a retry goes on from there. Null for a message that is not an album.
        UUID album;
        final java.util.List<byte[]> pictures = new ArrayList<>();
        final java.util.List<UUID> members = new ArrayList<>();
        final java.util.List<Bitmap> previews = new ArrayList<>();
        // The messages as they were stored, in the order of the album, filled in as each one is; and how many are.
        ChatEngine.Entry[] durable;
        volatile int stored;
        TextView progress;
        boolean group;
        boolean failed;
        volatile boolean cancelled;
        View row;
        PendingSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry expiry, ChatEnvelope.Reference reference) {
            this.peer = peer; this.text = text; this.image = image; this.expiry = expiry; this.reference = reference;
        }
        PendingSend(ChatEngine.Peer peer, java.util.List<byte[]> pictures, ChatEnvelope.Expiry expiry, ChatEnvelope.Reference reference) {
            this(peer, null, null, expiry, reference);
            album = UUID.randomUUID();
            for (byte[] picture : pictures) { this.pictures.add(picture); members.add(UUID.randomUUID()); previews.add(null); }
            durable = new ChatEngine.Entry[pictures.size()];
        }
        long expiresAt() { return createdAt + expiry.milliseconds; }
        boolean carriesImage() { return image != null || !pictures.isEmpty(); }
        synchronized byte[] copyImage() { return image == null ? null : image.clone(); }
        synchronized byte[] copyPicture(int index) { return index < pictures.size() && pictures.get(index) != null ? pictures.get(index).clone() : null; }
        synchronized Bitmap takePreview() { Bitmap taken = preview; preview = null; return taken; }
        synchronized Bitmap takePreview(int index) { Bitmap taken = previews.get(index); previews.set(index, null); return taken; }
        synchronized void keep(int index, ChatEngine.Entry entry) { durable[index] = entry; stored = index + 1; }
        synchronized java.util.List<ChatEngine.Entry> kept() {
            java.util.List<ChatEngine.Entry> result = new ArrayList<>();
            if (durable != null) for (ChatEngine.Entry entry : durable) if (entry != null) result.add(entry);
            return result;
        }
        synchronized void clear() {
            cancelled = true; text = null; if (image != null) Arrays.fill(image, (byte) 0); image = null; row = null; progress = null;
            if (preview != null) { if (!preview.isRecycled()) preview.recycle(); preview = null; }
            for (byte[] picture : pictures) if (picture != null) Arrays.fill(picture, (byte) 0);
            pictures.clear();
            for (int index = 0; index < previews.size(); index++) {
                Bitmap shown = previews.get(index);
                if (shown != null && !shown.isRecycled()) shown.recycle();
                previews.set(index, null);
            }
        }
    }
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickImages = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(Albums.MAX_SENT), this::imagesChosen);
    private GoogleAttempt googleAttempt;
    private boolean completingGoogle;
    private CancellationSignal googleCancellation;
    private GoogleSignIn.Failure googleFailure;
    private UUID googleDeletionFailureAccount;
    private boolean accountDeletedNotice;
    private boolean loginPolicyApproved;
    private static final class GoogleAttempt {
        final String origin;
        final boolean replace;
        final GoogleSignIn.Challenge challenge;
        UUID deletingAccount;
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
        long request;
        PhotoAvatar(UUID userId, FrameLayout frame, TextView fallback, ImageView image) {
            this.userId = userId; this.frame = frame; this.fallback = fallback; this.image = image;
        }
        void clear() {
            request++;
            image.setImageDrawable(null); image.setVisibility(View.GONE); fallback.setVisibility(View.VISIBLE);
            if (bitmap != null) bitmap.recycle(); bitmap = null;
        }
    }
    private java.util.List<Uri> selectedImages = new ArrayList<>();
    private int imagesLeftOut;
    private Bitmap cameraImage;
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
            if (!reactionChoices.isEmpty()) {
                long now = System.currentTimeMillis();
                reactionChoices.values().removeIf(choice -> choice.deadline() <= now);
            }
            pendingSends.removeIf(pending -> {
                if (pending.expiresAt() > System.currentTimeMillis()) return false;
                if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                pending.clear();
                return true;
            });
            if (resumed && messages != null && System.currentTimeMillis() >= nextRowExpiry) {
                long now = System.currentTimeMillis();
                rowScans++;
                boolean[] album = {false};
                messageRows.values().removeIf(row -> {
                    if (row.entry.expiresAt() > now) return false;
                    if (row.item.album()) album[0] = true;
                    messages.removeView(row.alignment); row.clear();
                    // The picture of what has expired goes with it, and a decode still running for it is not kept. The rest of an album
                    // keeps its own pictures for when the album is drawn again.
                    for (ChatEngine.Entry member : row.item.members()) {
                        if (member.expiresAt() > now) continue;
                        thumbnails.remove(member.id()); thumbnailWaiting.remove(member.id());
                    }
                    return true;
                });
                long soonest = Long.MAX_VALUE;
                for (int index = messages.getChildCount() - 1; index >= 0; index--) {
                    View row = messages.getChildAt(index);
                    if (!(row.getTag() instanceof Long deadline)) continue;
                    if (deadline <= now) messages.removeViewAt(index); else soonest = Math.min(soonest, deadline);
                }
                nextRowExpiry = soonest;
                // An album goes when its first photo does; any that outlive it (it is the sender who sets the lifetimes) are drawn again.
                if (album[0] && shownConversationPeer != null) refreshMessages(shownConversationPeer);
            }
            if (!isDestroyed()) ui.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(null);
        design = new Ui(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if ((emojiShown || emojiPending) && emojiPanel != null && emojiPanel.isAttachedToWindow()) { hideEmoji(false); return; }
                if (selectedPeer == null && rowSwipes.closeOpen()) return;
                if (selectedPeer != null && engine != null) navigateChat(null);
                else { hideContent(); finish(); }
            }
        });
        ContextCompat.registerReceiver(this, pushRefresh, new IntentFilter("app.vanishr.android.PUSH_REFRESH"), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, phoneUnlocked, new IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        loadRemoteSettings();
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
            // How tall the keyboard is above the navigation bar, so that the emoji panel can be as tall; and the panel comes up
            // once the keyboard it is replacing has gone.
            int keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            if (keyboard > dp(120)) keyboardHeight = keyboard;
            if (emojiPending && !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) displayEmoji();
            return insets;
        });
        setContentView(root);
        notificationIntent(getIntent());
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        work.scheduleWithFixedDelay(this::queueSync, 2, 15, TimeUnit.SECONDS);
        work.scheduleWithFixedDelay(this::presenceOnce, 1, 3, TimeUnit.SECONDS);
        photoWork.scheduleWithFixedDelay(this::photoRequestsOnce, 2, 5, TimeUnit.SECONDS);
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
        if (engine != null && engine.account() != null && !userId.equals(engine.account().userId()) && engine.onboarding().official(userId)) {
            ImageView logo = design.logo(size); logo.setId(View.NO_ID);
            logo.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            return logo;
        }
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
        // The avatar was just bound at the current generation, so the next tick need not bind it again.
        if (shownPhotoGeneration < 0 && engine != null) shownPhotoGeneration = engine.photos().generation();
        return frame;
    }

    private void bindProfileAvatar(PhotoAvatar avatar) {
        avatar.clear();
        ChatEngine current = engine;
        if (!resumed || current == null || current.account() == null) return;
        boolean own = current.account().userId().equals(avatar.userId);
        long request = avatar.request;
        long photoGeneration = current.photos().generation();
        int generation = screenGeneration;
        previewReader.execute(() -> {
            byte[] photo = null;
            Bitmap decoded = null;
            try {
                if (!resumed || engine != current || screenGeneration != generation || current.photos().generation() != photoGeneration) return;
                ProfilePhotos.Cached cached = own ? null : current.photos().contactAvatar(avatar.userId);
                photo = own ? current.photos().own() : cached == null ? null : cached.photo();
                long deadline = own ? Long.MAX_VALUE : cached == null ? 0 : cached.expiresAt();
                if (photo != null && deadline > System.currentTimeMillis()) decoded = SafeImages.displayProfilePhoto(photo);
                Bitmap result = decoded;
                imagesReady.post(() -> {
                    if (!resumed || engine != current || screenGeneration != generation || avatar.request != request
                            || !retainsAvatar(avatar) || current.photos().generation() != photoGeneration
                            || deadline <= System.currentTimeMillis()) {
                        if (result != null) result.recycle();
                        return;
                    }
                    avatar.bitmap = result; avatar.expiresAt = deadline;
                    if (result != null) {
                        avatar.image.setImageBitmap(result); avatar.image.setVisibility(View.VISIBLE); avatar.fallback.setVisibility(View.GONE);
                    }
                });
                decoded = null;
            } catch (Exception failure) {
                imagesReady.post(() -> {
                    if (!resumed || engine != current || screenGeneration != generation || avatar.request != request
                            || !retainsAvatar(avatar)) return;
                    if (failure instanceof GeneralSecurityException || failure instanceof SecurityException) {
                        hideContent(); storageFailure(failure);
                    } else if (avatar.frame.isAttachedToWindow()) problem("Profile photo is unavailable. Try again from My profile.");
                });
            } finally {
                if (photo != null) Arrays.fill(photo, (byte) 0);
                if (decoded != null) decoded.recycle();
            }
        });
    }

    private void refreshProfileAvatars() {
        if (!resumed || engine == null) return;
        long current = engine.photos().generation();
        photoAvatars.removeIf(avatar -> {
            if (!retainsAvatar(avatar)) { avatar.clear(); return true; }
            if (shownPhotoGeneration != current) bindProfileAvatar(avatar);
            else if (avatar.bitmap != null && avatar.expiresAt <= System.currentTimeMillis()) avatar.clear();
            return false;
        });
        shownPhotoGeneration = current;
    }

    private boolean retainsAvatar(PhotoAvatar avatar) {
        return avatar.frame.isAttachedToWindow() || isHomeAvatar(avatar);
    }

    private boolean isHomeAvatar(PhotoAvatar avatar) {
        if (homeLayout == null) return false;
        for (View view = avatar.frame; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            if (view == homeLayout) return true;
        }
        return false;
    }

    private void clearProfileAvatars() {
        for (PhotoAvatar avatar : photoAvatars) avatar.clear();
        photoAvatars.clear(); shownPhotoGeneration = -1;
    }

    private void title(String name, boolean back) {
        LinearLayout heading = horizontal();
        if (back) heading.addView(icon(android.R.drawable.ic_media_previous, "Back", () -> navigateChat(null)));
        if ("vanishr".equals(name)) heading.addView(design.logo(36));
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
        clearHome();
        chatScreen = false;
        if (storageError == null && googleAttempt == null && !completingGoogle) {
            if (root.findViewById(R.id.startup_progress) != null) return;
            screenPadding(0);
            root.removeAllViews();
            LinearLayout center = vertical();
            center.setGravity(Gravity.CENTER);
            center.addView(design.logo(80), new LinearLayout.LayoutParams(dp(80), dp(80)));
            var progress = new com.google.android.material.progressindicator.LinearProgressIndicator(this);
            progress.setId(R.id.startup_progress);
            progress.setContentDescription(getString(R.string.opening_vanishr));
            progress.setTrackThickness(dp(4));
            progress.setTrackCornerRadius(dp(2));
            progress.setIndicatorColor(GREEN);
            progress.setTrackColor(Ui.LINE);
            progress.setIndeterminate(true);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(132), -2);
            size.topMargin = dp(18);
            center.addView(progress, size);
            root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }
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
        TextView message = design.text(storageError == null ? "Signing in with Google..." : storageError, 16, 500, MUTED);
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
        act(() -> {
            AndroidVault vault = new AndroidVault(this);
            try {
                vault.unlock();
                ChatEngine loaded = new ChatEngine(vault);
                applyStoredAdminIdentity(loaded);
                if (!resumed || generation != screenGeneration) { loaded.close(); return; }
                ui.post(() -> {
                    if (!resumed || isDestroyed() || generation != screenGeneration) { loaded.close(); return; }
                    loadingGeneration = -1;
                    engine = loaded;
                    render();
                    if (loaded.authenticated()) work.execute(() -> {
                        if (resumed && engine == loaded && generation == screenGeneration) loaded.connect(this::queueSync);
                    });
                    showGoogleDeletionFailure();
                    if (accountDeletedNotice && loaded.account() == null) {
                        accountDeletedNotice = false;
                        problem("Account deleted. This account's data has been removed from this phone.");
                    }
                    if (googleAttempt != null && googleAttempt.idToken != null) completeGoogle();
                    if (!selectedImages.isEmpty() || cameraImage != null) importPendingImage();
                    if (selectedProfilePhoto != null) importProfilePhoto();
                    registerPush();
                    queueSync();
                    continuePhotoChoice();
                    maybeBackup(loaded);
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
        render(false);
    }

    private void navigateChat(UUID peer) {
        if (peer != null) rowSwipes.closeOpen();
        selectedPeer = peer;
        render(true);
    }

    private void render(boolean keepHome) {
        if (!resumed) return;
        ChatEngine current = engine;
        if (current == null) { storageState(); return; }
        String draft = composer != null && Objects.equals(composerPeer, selectedPeer) ? composer.getText().toString() : "";
        resetConversation();
        ChatEngine.Account owner = current.account();
        if (!keepHome || owner == null || !Objects.equals(homeOwner, owner.userId())
                || !current.authenticated() || current.safety().deletionPending() || !current.safety().termsAccepted(PlayPolicy.VERSION)) clearHome();
        root.removeAllViews();
        messages = null; messageScroll = null; composer = null; connection = null; conversationName = null; conversationUsername = null; actionError = null;
        contactStatus = null;
        screenPadding(20);
        shownEntries = java.util.List.of();
        if (current.safety().deletionPending()) { pendingDeletionScreen(); return; }
        if (googleAttempt != null || completingGoogle) { storageState(); return; }
        if (!current.authenticated()) {
            current.presence().foreground(false);
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).apply();
            if (VanishrApplication.pushConfigured()) FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            notificationOpen = null; loginScreen(); maybePromptUpdate(false); return;
        }
        if (!current.safety().termsAccepted(PlayPolicy.VERSION)) { policyScreen(); return; }
        if (notificationOpen != null) { storageState(); openNotification(); return; }
        current.presence().foreground(true);
        ChatEngine.Peer peer = conversationPeer(selectedPeer);
        current.presence().conversation(peer != null && current.groups().get(peer.userId()) == null ? peer.userId() : null);
        if (peer == null) { selectedPeer = null; contactsScreen(); }
        else conversation(peer);
        if (composer != null && !draft.isEmpty()) {
            restoringDraft = true;
            try { composer.setText(draft); composer.setSelection(composer.length()); }
            finally { restoringDraft = false; }
        }
        maybePromptUpdate(false);
    }

    private void checkForUpdates(boolean manual) {
        if (BuildConfig.PLAY_STORE) {
            if (manual) openPublicPage(PlayPolicy.PLAY_URL);
            return;
        }
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
        if (BuildConfig.PLAY_STORE) return;
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

    // ---- signed settings from the download site ----

    /**
     * Reading the key list and checking the stored file cost about 40 ms the first time a process does it, so it runs on the update lane
     * (which has nothing else to do yet) and the screen is already up by then. Until it is done the app behaves as built.
     */
    private void loadRemoteSettings() {
        RemoteSettingsStore store = new RemoteSettingsStore(getSharedPreferences("remote-settings", MODE_PRIVATE));
        settingsStore = store;
        updateWork.execute(() -> {
            Map<String, java.security.PublicKey> keys;
            try (java.io.InputStream list = getResources().openRawResource(R.raw.ota_keys)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                for (int read; (read = list.read(chunk)) > 0; ) bytes.write(chunk, 0, read);
                keys = RemoteSettings.keys(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception failure) { keys = Map.of(); }
            // Without a key nothing can be accepted, and the app simply behaves as built.
            Map<String, java.security.PublicKey> trusted = keys;
            RemoteSettings.Settings stored = store.load(trusted, System.currentTimeMillis());
            ui.post(() -> {
                if (isDestroyed()) return;
                settingsKeys = trusted;
                remote = stored;
                settingsLoaded = true;
                renderNotice();
                checkRemoteSettings();
            });
        });
    }

    /** At most once a day, in the foreground, from the fixed download host; a failure is retried after two hours and never shown. */
    private void checkRemoteSettings() {
        if (!settingsLoaded || !resumed || isDestroyed() || checkingSettings || settingsStore == null || settingsKeys.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!RemoteSettings.due(now, settingsStore.lastCheck())) return;
        checkingSettings = true;
        RemoteSettingsStore store = settingsStore;
        Map<String, java.security.PublicKey> keys = settingsKeys;
        java.util.function.Supplier<RemoteSettings> source = settingsSource;
        updateWork.execute(() -> {
            long started = System.currentTimeMillis();
            try (RemoteSettings fetcher = source.get()) {
                byte[] envelope = fetcher.fetch();
                RemoteSettings.Settings next = RemoteSettings.verify(envelope, keys, started);
                boolean accepted = store.accept(envelope, next, started);
                ui.post(() -> {
                    checkingSettings = false;
                    if (isDestroyed() || !accepted) return;
                    remote = next;
                    renderNotice();
                });
            } catch (Exception failure) {
                store.failed(started);
                ui.post(() -> checkingSettings = false);
            }
        });
    }

    private AdminIdentityStore adminStore() {
        if (adminStore == null) adminStore = new AdminIdentityStore(getSharedPreferences("admin-identity", MODE_PRIVATE));
        return adminStore;
    }

    private Map<String, java.security.PublicKey> adminKeys() {
        Map<String, java.security.PublicKey> keys = adminKeys;
        if (keys != null) return keys;
        try (java.io.InputStream list = getResources().openRawResource(R.raw.admin_keys)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            for (int read; (read = list.read(chunk)) > 0; ) bytes.write(chunk, 0, read);
            keys = RemoteSettings.keys(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception failure) { keys = Map.of(); }
        // Without a key nothing can be accepted, and the built-in identity stands alone.
        adminKeys = keys;
        return keys;
    }

    /** On the lane that loads the engine: a certificate kept from an earlier run is checked again and, if it holds, takes the place of the built-in pin. */
    private void applyStoredAdminIdentity(ChatEngine target) {
        try {
            if (!adminStore().present()) return;
            Map<String, java.security.PublicKey> keys = adminKeys();
            if (keys.isEmpty()) return;
            AdminIdentity.Certificate stored = adminStore().load(keys, target.onboarding().pin().userId());
            if (stored != null) target.onboarding().adopt(stored);
        } catch (RuntimeException unavailable) { /* the built-in identity stands */ }
    }

    /**
     * The relay or a sender showed an identity for the official admin that is not the pinned one (AdminOnboarding.Mismatch). If the owner has
     * certified it, the signed certificate is on the download site: ask for it (not more often than every ten minutes, however often the app is
     * restarted, and only because of this), and use it only if it verifies and is newer than what is known. Anything else leaves things as they
     * are, which means the admin stays unverified.
     */
    private void requestAdminIdentity(ChatEngine current) {
        if (current == null || isDestroyed()) return;
        synchronized (adminIdentityGate) {
            if (checkingAdminIdentity) return;
            checkingAdminIdentity = true;
        }
        java.util.function.Supplier<RemoteSettings> source = adminIdentitySource;
        try {
            updateWork.execute(() -> {
                try {
                    AdminIdentityStore store = adminStore();
                    UUID admin = current.onboarding().pin().userId();
                    Map<String, java.security.PublicKey> keys = adminKeys();
                    // Another screen of this app may have fetched and kept the certificate already: this engine takes it from the store, without asking anybody.
                    AdminIdentity.Certificate kept = store.load(keys, admin);
                    AdminOnboarding.Pin pinned = current.onboarding().pin();
                    if (kept != null && (!pinned.deviceId().equals(kept.deviceId()) || !pinned.fingerprint().equals(kept.fingerprint()))) {
                        act(() -> current.onboarding().adopt(kept));
                        queueSync();
                        return;
                    }
                    if (!store.reserveAttempt(System.currentTimeMillis(), ADMIN_IDENTITY_RETRY)) return;
                    try (RemoteSettings fetcher = source.get()) {
                        byte[] envelope = fetcher.fetch();
                        AdminIdentity.Certificate certificate = AdminIdentity.verify(envelope, keys, admin);
                        if (AdminIdentity.newer(certificate, store.load(keys, admin), store.highWater()) && store.save(envelope, certificate.sequence())) {
                            act(() -> current.onboarding().adopt(certificate));
                            queueSync();
                        }
                    }
                } catch (Exception failure) { /* nothing changes, and it is asked again later */ }
                finally { checkingAdminIdentity = false; }
            });
        } catch (RuntimeException rejected) { checkingAdminIdentity = false; }
    }

    /** What is in force now: a file stops counting the moment it expires, with no network needed. */
    private RemoteSettings.Settings activeSettings() {
        RemoteSettings.Settings current = remote;
        return current.expiresAt() > System.currentTimeMillis() ? current : RemoteSettings.Settings.NONE;
    }

    private boolean allowed(RemoteSettings.Flag flag) {
        if (activeSettings().enabled(flag)) return true;
        problem(flag.refusal);
        return false;
    }

    // Whether photos are drawn as pictures. It is asked while drawing and while deciding what to decode, so unlike allowed() it says
    // nothing: allowed() is for what a person has just asked for, and a screen that is only being drawn must not raise a notice each time.
    private boolean photosOn() { return activeSettings().enabled(RemoteSettings.Flag.PHOTOS); }

    private void renderNotice() {
        LinearLayout host = noticeHost;
        if (host == null || settingsStore == null) return;
        host.removeAllViews();
        RemoteSettings.Settings active = activeSettings();
        RemoteSettings.Notice notice = RemoteSettings.noticeFor(active, BuildConfig.VERSION_CODE, playBuild);
        if (notice != null && !notice.id().equals(settingsStore.dismissedNotice())) {
            Runnable open = notice.path() == null ? null : () -> openPublicPage(AppUpdates.ORIGIN + notice.path());
            host.addView(noticeCard(notice.title(), notice.body(), notice.important(), open == null ? null : "Learn more", open,
                    () -> { settingsStore.dismissNotice(notice.id()); renderNotice(); }));
        }
        RemoteSettings.Nudge nudge = RemoteSettings.nudge(active, BuildConfig.VERSION_CODE, playBuild);
        if (nudge == RemoteSettings.Nudge.UNSUPPORTED) {
            host.addView(noticeCard("Please update Vanishr", "This version is no longer supported. Update it from Google Play.", true,
                    "Open Google Play", () -> openPublicPage(PlayPolicy.PLAY_URL), null));
        } else if (nudge == RemoteSettings.Nudge.AVAILABLE) {
            int latest = active.update().latestVersionCode();
            if (!settingsStore.updateDismissed(latest))
                host.addView(noticeCard("Update available", "Vanishr " + active.update().latestVersionName() + " is on Google Play.", false,
                        "Open Google Play", () -> openPublicPage(PlayPolicy.PLAY_URL), () -> { settingsStore.dismissUpdate(latest); renderNotice(); }));
        }
    }

    private View noticeCard(String title, String body, boolean strong, String actionLabel, Runnable action, Runnable dismiss) {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(12), dp(14), dp(4));
        card.setBackground(design.background(strong ? Ui.ERROR_TINT : Ui.TINT, 8, strong ? Ui.ERROR : Ui.PRIMARY));
        card.setContentDescription("Notice");
        card.addView(design.text(title, 13, 700, INK));
        TextView text = design.text(body, 12, 500, INK);
        text.setPadding(0, dp(5), 0, 0);
        card.addView(text);
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (action != null) actions.addView(noticeAction(actionLabel, action));
        if (dismiss != null) actions.addView(noticeAction("Dismiss", dismiss));
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(dp(17), 0, dp(17), dp(10));
        card.setLayoutParams(margin);
        return card;
    }

    private TextView noticeAction(String label, Runnable action) {
        TextView button = design.text(label, 12, 700, GREEN);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(44));
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setBackground(design.feedback(Color.TRANSPARENT, 8));
        button.setContentDescription(label);
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private String settingsStatus() {
        RemoteSettings.Settings active = activeSettings();
        return active.present() ? "Settings " + active.sequence() + ", valid until "
                + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(active.expiresAt())) : "Settings: as built";
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
            catch (IllegalArgumentException failure) { username.layout().setError(ChatEngine.USERNAME_HINT); return; }
            String secret = password.getText().toString();
            if (secret.length() < ChatEngine.MIN_PASSWORD || secret.length() > 64 || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                secretField.layout().setError("Use " + ChatEngine.MIN_PASSWORD + "-64 characters, up to 72 UTF-8 bytes"); return;
            }
            boolean register = signingUp;
            boolean replaceExisting = replace.isChecked();
            ChatEngine current = engine;
            password.setText("");
            ensurePolicies(() -> submit(() -> {
                current.login(origin, accountHandle, secret, register, replaceExisting);
                if (loginPolicyApproved) current.safety().acceptTerms(PlayPolicy.VERSION);
            }, () -> {
                signingUp = false;
                deviceReplacementRequired = false;
                engine.connect(this::queueSync);
                render();
                registerPush();
                queueSync();
            }));
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
            MaterialButton google = design.button("Continue with Google", !passwordAccount, () -> ensurePolicies(() -> googleLogin(origin, replace.isChecked())));
            form.addView(google);
        }
        form.addView(profileAction("Privacy policy", R.drawable.ic_file_text, () -> openPublicPage(PlayPolicy.PRIVACY_URL)));
        form.addView(profileAction("Terms & safety", R.drawable.ic_shield_check, () -> openPublicPage(PlayPolicy.TERMS_URL)));
        form.addView(profileAction("Request account deletion", R.drawable.ic_trash_2, () -> openPublicPage(PlayPolicy.DELETION_URL)));
        if (saved != null) {
            form.addView(design.spacer(18));
            form.addView(command("Use another account", this::signOutDialog));
        }
        form.addView(design.spacer(16));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (googleFailure != null && googleDeletionFailureAccount == null) { problem(googleFailure.message); googleFailure = null; }
    }

    private void contactsScreen() {
        chatScreen = false;
        screenPadding(0);
        String displayName = engine.displayName();
        if (homeLayout != null && !displayName.equals(homeOwnerName)) clearHome();
        if (homeLayout != null) {
            refreshProfileAvatars();
            root.addView(homeLayout, new LinearLayout.LayoutParams(-1, -1));
            updateContacts(ChatListContents.read(engine), engine.unreadCounts());
            renderNotice();
            homeStatus();
            return;
        }
        homeOwner = engine.account().userId();
        homeOwnerName = displayName;
        homeLayout = vertical();
        root.addView(homeLayout, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = horizontal(); toolbar.setPadding(dp(17), 0, dp(17), 0);
        toolbar.setContentDescription("Chats");
        LinearLayout brand = horizontal();
        brand.addView(design.logo(28));
        brand.addView(design.text("vanishr", 25, 800, INK));
        homeConnection = new View(this);
        LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(dp(6), dp(6)); indicator.setMarginStart(dp(9));
        brand.addView(homeConnection, indicator);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        toolbar.addView(icon(R.drawable.ic_users_round, "New group", this::newGroupDialog));
        ImageButton add = icon(R.drawable.ic_square_pen, "New conversation", this::newConversationDialog); add.setPadding(dp(10), dp(10), dp(10), dp(10));
        add.setBackground(design.feedback(Ui.PRIMARY, 7)); add.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams composeSize = new LinearLayout.LayoutParams(dp(40), dp(40)); composeSize.setMarginStart(dp(4)); toolbar.addView(add, composeSize);
        View profile = profileAvatar(homeOwner, displayName, 40, Ui.BLUE);
        profile.setContentDescription("My profile"); profile.setTooltipText("My profile"); profile.setFocusable(true);
        profile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); profile.setFilterTouchesWhenObscured(true);
        profile.setOnClickListener(view -> accountDialog());
        LinearLayout.LayoutParams profileSize = new LinearLayout.LayoutParams(dp(40), dp(40)); profileSize.setMarginStart(dp(7)); toolbar.addView(profile, profileSize);
        homeLayout.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));
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
        homeLayout.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(56)));
        noticeHost = vertical();
        // A notice can never take more than part of the space that is left, whatever it says: the chat list below it stays reachable.
        noticeArea = new BoundedScrollView(this, .4f);
        noticeArea.addView(noticeHost);
        homeLayout.addView(noticeArea, new LinearLayout.LayoutParams(-1, -2));
        renderNotice();
        ScrollView scroll = new ScrollView(this);
        rowSwipes = new SwipeActions.Group();
        closeRowsOnTouch(scroll);
        contactRows = vertical(); scroll.setFillViewport(true); scroll.addView(contactRows);
        homeLayout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderContacts();
        contactSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                homeQuery = value.toString(); clear.setVisibility(value.length() == 0 ? View.GONE : View.VISIBLE); renderContacts();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        homeStatus();
    }

    private void clearHome() {
        if (homeLayout == null) return;
        photoAvatars.removeIf(avatar -> {
            if (!isHomeAvatar(avatar)) return false;
            avatar.clear();
            return true;
        });
        homeLayout = null; homeOwner = null; homeOwnerName = null; shownChats = null; shownUnread = Map.of();
        contactRows = null; contactSearch = null; unreadLabels.clear();
        homeConnection = null; unreadFilter = null; noticeHost = null; noticeArea = null;
        rowSwipes = new SwipeActions.Group();
    }

    private void homeStatus() {
        if (homeConnection == null || homeLayout.getParent() != root) return;
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
        beginGoogleSignIn(origin, replace, null);
    }

    private void beginGoogleSignIn(String origin, boolean replace, UUID deletingAccount) {
        if (!resumed || engine == null || busy || googleAttempt != null) return;
        googleFailure = null;
        googleDeletionFailureAccount = null;
        if (actionError != null) { actionError.setText(""); actionError.setVisibility(View.GONE); }
        var prepared = new java.util.concurrent.atomic.AtomicReference<GoogleSignIn.Challenge>();
        UUID expectedAccount = deletingAccount != null ? deletingAccount : engine.account() == null ? null : engine.account().userId();
        submit(() -> prepared.set(GoogleSignIn.prepare(origin, expectedAccount)), () -> {
            GoogleAttempt attempt = new GoogleAttempt(origin, replace, prepared.get());
            attempt.deletingAccount = deletingAccount;
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
        }, failure -> {
            if (deletingAccount != null) deletionFailed(failure);
            else showFailure(failure);
        });
    }

    private void googleFailed(GoogleSignIn.Failure failure) {
        googleDeletionFailureAccount = googleAttempt == null ? null : googleAttempt.deletingAccount;
        if (googleAttempt != null) googleAttempt.idToken = null;
        googleAttempt = null;
        completingGoogle = false;
        googleFailure = failure;
        if (resumed) {
            render();
            showGoogleDeletionFailure();
            if (engine != null && googleFailure != null) { problem(failure.message); googleFailure = null; }
        }
    }

    private void showGoogleDeletionFailure() {
        if (!resumed || engine == null || googleFailure == null || googleDeletionFailureAccount == null) return;
        UUID owner = googleDeletionFailureAccount;
        GoogleSignIn.Failure failure = googleFailure;
        googleDeletionFailureAccount = null; googleFailure = null;
        if (engine.account() == null || !owner.equals(engine.account().userId())) {
            problem("Deletion confirmation was cancelled because the active account changed.");
            return;
        }
        deletionRetry(failure.message);
    }

    private void completeGoogle() {
        GoogleAttempt attempt = googleAttempt;
        ChatEngine current = engine;
        if (!resumed || attempt == null || attempt.idToken == null || current == null || busy) return;
        googleAttempt = null;
        completingGoogle = true;
        render();
        submit(() -> {
            try {
                if (attempt.deletingAccount != null) {
                    if (current.account() == null || !attempt.deletingAccount.equals(current.account().userId())
                            || !attempt.origin.equals(current.account().origin()) || !current.usesGoogle())
                        throw new SecurityException("The account selected for deletion changed");
                    current.safety().deleteWithGoogle(attempt.challenge, attempt.idToken);
                } else {
                    current.loginGoogle(attempt.origin, attempt.challenge, attempt.idToken, attempt.replace);
                    if (loginPolicyApproved) current.safety().acceptTerms(PlayPolicy.VERSION);
                }
            }
            finally { attempt.idToken = null; }
        }, () -> {
            completingGoogle = false;
            if (attempt.deletingAccount != null) { deletedAccount(); return; }
            deviceReplacementRequired = false; render(); engine.connect(this::queueSync); registerPush(); queueSync();
        }, failure -> {
            completingGoogle = false;
            if (attempt.deletingAccount != null) { render(); deletionFailed(failure); return; }
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
        renderContacts(ChatListContents.read(engine), engine.unreadCounts());
    }

    private void updateContacts(ChatListContents contents, Map<UUID, Long> unreadCounts) {
        if (contactRows == null || rowSwipes.active()) return;
        if (!contents.equals(shownChats) || (unreadOnly && !unreadCounts.keySet().equals(shownUnread.keySet()))) {
            renderContacts(contents, unreadCounts);
            return;
        }
        for (var unread : unreadLabels.entrySet()) {
            long count = unreadCounts.getOrDefault(unread.getKey(), 0L);
            if (count == shownUnread.getOrDefault(unread.getKey(), 0L)) continue;
            TextView badge = unread.getValue();
            badge.setText(Long.toString(count)); badge.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        }
        shownUnread = unreadCounts;
    }

    private void renderContacts(ChatListContents contents, Map<UUID, Long> unreadCounts) {
        rowSwipes.begin();
        contactRows.removeAllViews(); unreadLabels.clear();
        if (unreadFilter != null) {
            unreadFilter.setSelected(unreadOnly); unreadFilter.setColorFilter(unreadOnly ? GREEN : INK);
            unreadFilter.setBackground(design.feedback(unreadOnly ? Ui.TINT : Ui.SURFACE, 7));
            androidx.core.view.ViewCompat.setStateDescription(unreadFilter, unreadOnly ? "Unread only" : "All chats");
        }
        String filter = contactSearch == null ? "" : contactSearch.getText().toString().toLowerCase(Locale.ROOT);
        shownChats = contents; shownUnread = unreadCounts;
        for (GroupChat.Conversation group : contents.groups()) {
            if (!group.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
            long count = unreadCounts.getOrDefault(group.id(), 0L);
            if (unreadOnly && count == 0) continue;
            LinearLayout row=horizontal(); row.setMinimumHeight(dp(78)); row.setPadding(dp(18),dp(12),dp(18),dp(12)); row.setBackground(design.feedback(Color.TRANSPARENT,0));
            row.addView(design.groupAvatar(46, Ui.BLUE));
            LinearLayout details=vertical(); details.setPadding(dp(12),0,dp(8),0);
            TextView title=design.text(group.name(),14,700,INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); details.addView(title);
            LinearLayout subtitle=horizontal(); subtitle.setPadding(0,dp(5),0,0); subtitle.addView(design.symbol(R.drawable.ic_users_round,12,MUTED));
            TextView state=design.text(engine.groups().status(group),11,500,MUTED); state.setPadding(dp(5),0,0,0); state.setSingleLine(true); subtitle.addView(state); details.addView(subtitle);
            row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(unreadBadge(group.id(),count));
            row.addView(design.symbol(R.drawable.ic_chevron_right,16,MUTED));
            row.setOnClickListener(view -> {
                GroupChat.Conversation current=engine.groups().get(group.id());
                if (current==null) return;
                if (engine.groups().invited(current)) groupInvitationDialog(current);
                else navigateChat(group.id());
            });
            row.setOnLongClickListener(view -> { groupInfo(group.id()); return true; });
            addChatRow(row);
        }
        java.util.Set<UUID> reviewing = engine.onboarding().changedContacts();
        for (ChatEngine.Peer peer : contents.peers()) {
            if (!peer.name().toLowerCase(Locale.ROOT).contains(filter) && !peer.username().contains(filter)
                    && (peer.profileName() == null || !peer.profileName().toLowerCase(Locale.ROOT).contains(filter))) {
                continue;
            }
            long unread = unreadCounts.getOrDefault(peer.userId(), 0L);
            if (unreadOnly && unread == 0) {
                continue;
            }
            LinearLayout row = horizontal();
            boolean quiet = contents.muted().contains(peer.userId());
            // Opaque, so that the actions behind the row do not show through while it moves.
            row.setMinimumHeight(dp(78)); row.setPadding(dp(18), dp(12), dp(18), dp(12)); row.setBackground(design.feedback(Ui.CANVAS, 0));
            row.addView(profileAvatar(peer.userId(), peer.name(), 46, design.avatarColor(peer.userId())));
            LinearLayout identity = vertical(); identity.setPadding(dp(12), 0, dp(8), 0);
            TextView title=design.text(peer.name(),14,700,INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); identity.addView(title);
            if (reviewing.contains(peer.userId())) {
                TextView review = design.text("Identity changed. Tap to review", 11, 600, Ui.ERROR);
                review.setSingleLine(true); review.setEllipsize(android.text.TextUtils.TruncateAt.END); review.setPadding(0, dp(3), 0, 0); identity.addView(review);
            }
            row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            if (quiet) {
                LinearLayout.LayoutParams bellSize = new LinearLayout.LayoutParams(dp(14), dp(14)); bellSize.setMarginEnd(dp(8));
                row.addView(design.symbol(R.drawable.ic_bell_off, 14, MUTED), bellSize);
                androidx.core.view.ViewCompat.setStateDescription(row, "Muted");
            }
            row.addView(unreadBadge(peer.userId(), unread, quiet));
            row.addView(design.symbol(R.drawable.ic_chevron_right, 16, MUTED));
            row.setOnClickListener(view -> openContact(peer));
            row.setOnLongClickListener(view -> { forgetDialog(peer); return true; });
            addChatRow(chatActions(row, peer, quiet));
        }
        rowSwipes.end();
        if (engine.onboarding().admin()) {
            ChatEngine current = engine;
            if (current.onboarding().hasPrevious()) contactRows.addView(command("First new accounts", () ->
                    submit(current.onboarding()::first, this::render)));
            if (current.onboarding().hasNext()) contactRows.addView(command("More new accounts", () ->
                    submit(current.onboarding()::next, this::render)));
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
        if (engine.onboarding().problem() != null) {
            TextView warning = design.text(engine.onboarding().problem(), 12, 500, Ui.ERROR);
            warning.setPadding(dp(18), dp(12), dp(18), dp(12));
            contactRows.addView(warning, 0);
        }
    }

    // A saved contact is checked here against local state only, so the tap never waits for a sync or an upload on the work lane.
    private void openContact(ChatEngine.Peer peer) {
        ChatEngine current = engine;
        if (current == null || !resumed) return;
        // A contact the relay now introduces with a different identity is looked at first; the chat opens once it has been accepted.
        if (current.onboarding().changed(peer.userId()) != null) { reviewIdentityDialog(peer); return; }
        try {
            if (current.independentlyVerified(peer.userId()) || current.prepareSavedConversation(peer)) { navigateChat(peer.userId()); return; }
        } catch (Exception failure) { showFailure(failure); return; }
        submit(() -> current.prepareConversation(peer), () -> navigateChat(peer.userId()));
    }

    // The relay introduces a different identity for this saved contact, usually because they reinstalled Vanishr or changed phone. Nothing is
    // sent to it or read from it until the person has compared the new safety number with the one on the contact's own phone and accepted it.
    private void reviewIdentityDialog(ChatEngine.Peer peer) {
        ChatEngine current = engine;
        if (current == null || !resumed) return;
        ChatEngine.Peer reviewed = current.onboarding().changed(peer.userId());
        if (reviewed == null) { render(); return; }
        String number;
        try { number = ChatEngine.safetyNumber(reviewed.userId(), reviewed.identityKey()); }
        catch (Exception invalid) { showFailure(invalid); return; }
        boolean verifiedByHand = current.independentlyVerified(peer.userId());
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        panel.addView(design.text(peer.name() + " is using a new identity. This usually means they reinstalled Vanishr or changed phone. "
                + "Nothing is sent to or read from the new identity until you accept it.", 13, 500, INK));
        panel.addView(design.spacer(14));
        panel.addView(design.text("Their new safety number", 11, 700, MUTED));
        TextView code = design.text(number, 15, 700, INK); code.setPadding(0, dp(4), 0, 0); code.setContentDescription("New safety number " + number);
        panel.addView(code);
        panel.addView(design.spacer(14));
        panel.addView(design.text("Compare it with the number on their phone (Profile, Verify identity) before you accept."
                + (verifiedByHand ? " You verified their old identity yourself; once accepted, this contact is no longer marked as verified." : ""), 12, 500, MUTED));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        showDialog(new SecureSheet.Builder(this).setTitle("Identity changed").setView(scroll)
                .setNegativeButton("Not now", null)
                .setNeutralButton("Remove contact", (dialog, which) -> forgetDialog(peer))
                .setPositiveButton("Accept", (dialog, which) -> submit(
                        () -> current.onboarding().acceptChanged(peer.userId(), reviewed.identityKey()),
                        () -> { render(); queueSync(); },
                        failure -> {
                            if (failure instanceof AdminOnboarding.Failure) { problem(failure.getMessage()); render(); }
                            else showFailure(failure);
                        }))
                .create());
    }

    // A chat pulled to the right shows Mute and Clear chat; pulled to the left, Remove contact and Block user. The row stays where it was
    // pulled until one is touched, and each one that erases something asks first, exactly as the long press and the chat's menu do, so a
    // swipe erases nothing by itself. Screen-reader users get the same four as actions on the row.
    private View chatActions(LinearLayout row, ChatEngine.Peer peer, boolean muted) {
        String muteLabel = muted ? "Unmute" : "Mute";
        Runnable mute = () -> muteChat(peer, !muted);
        Runnable clear = () -> clearChatDialog(peer);
        Runnable remove = () -> forgetDialog(peer);
        Runnable block = () -> blockUserDialog(peer);
        SwipeActions holder = new SwipeActions(this, design, row,
                java.util.List.of(new SwipeActions.Action(muted ? R.drawable.ic_bell : R.drawable.ic_bell_off, muteLabel, Ui.BLUE, mute),
                        new SwipeActions.Action(R.drawable.ic_eraser, "Clear chat", Ui.MUTED, clear)),
                java.util.List.of(new SwipeActions.Action(R.drawable.ic_user_round_x, "Remove contact", Ui.WARM, remove),
                        new SwipeActions.Action(R.drawable.ic_ban, "Block user", Ui.ERROR, block)),
                rowSwipes, peer.userId());
        androidx.core.view.ViewCompat.addAccessibilityAction(row, muteLabel + " chat", (view, arguments) -> { mute.run(); return true; });
        androidx.core.view.ViewCompat.addAccessibilityAction(row, "Clear chat", (view, arguments) -> { clear.run(); return true; });
        androidx.core.view.ViewCompat.addAccessibilityAction(row, "Remove contact", (view, arguments) -> { remove.run(); return true; });
        androidx.core.view.ViewCompat.addAccessibilityAction(row, "Block user", (view, arguments) -> { block.run(); return true; });
        return holder;
    }

    // Muting is kept on this phone and changes how the list shows the chat's count. The phone's own alert says only "New message" and
    // does not know which chat it is for, so it cannot be silenced per chat; the message says so rather than let it seem to.
    private void muteChat(ChatEngine.Peer peer, boolean mute) {
        ChatEngine current = engine;
        if (current == null || !resumed) return;
        submit(() -> current.mute(peer.userId(), mute), () -> {
            render();
            Toast.makeText(this, mute ? "Muted. The count stays grey; the phone's alert still says only \u201cNew message\u201d." : "Unmuted", Toast.LENGTH_LONG).show();
        });
    }

    // Touching the list anywhere that is not a row, and scrolling it, puts an open row back.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void closeRowsOnTouch(ScrollView list) {
        list.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) rowSwipes.closeOpen();
            return false;
        });
    }

    private TextView unreadBadge(UUID id, long count) { return unreadBadge(id, count, false); }

    private TextView unreadBadge(UUID id, long count, boolean muted) {
        TextView badge=design.text(Long.toString(count),10,800,Color.WHITE); badge.setGravity(Gravity.CENTER);
        badge.setBackground(design.background(muted ? MUTED : GREEN,11,0)); badge.setPadding(dp(5),0,dp(5),0); badge.setMinWidth(dp(21));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(21)); size.setMarginEnd(dp(7)); badge.setLayoutParams(size);
        badge.setVisibility(count==0 ? View.GONE : View.VISIBLE); unreadLabels.put(id,badge); return badge;
    }

    private void addChatRow(View row) {
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
        shownConversationPeer = peer; shownConversationGroup = group;
        LinearLayout header = horizontal(); header.setMinimumHeight(dp(68)); header.setPadding(dp(10),dp(8),dp(10),dp(8)); header.setBackgroundColor(Ui.SURFACE);
        header.addView(icon(R.drawable.ic_arrow_left, "Back", () -> navigateChat(null)));
        View avatar=group==null ? profileAvatar(peer.userId(),peer.name(),36,design.avatarColor(peer.userId())) : design.groupAvatar(36,Ui.BLUE);
        LinearLayout.LayoutParams avatarSize=new LinearLayout.LayoutParams(dp(36),dp(36)); avatarSize.setMarginStart(dp(8)); header.addView(avatar,avatarSize);
        LinearLayout identity = vertical(); identity.setPadding(dp(8), 0, dp(6), 0);
        conversationName = design.text(peer.name(), 14, 700, INK); conversationName.setSingleLine(true); conversationName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // What the bottom label used to say about trust, without taking a row: a shield after the name for a contact this person verified
        // and for the official admin; the contact profile says it in words.
        String trustMark = group != null ? null : engine.independentlyVerified(peer.userId()) ? "verified" : engine.onboarding().official(peer.userId()) ? "official admin" : null;
        if (trustMark != null) {
            android.graphics.drawable.Drawable shield = ContextCompat.getDrawable(this, R.drawable.ic_shield_check).mutate();
            shield.setTint(GREEN); shield.setBounds(0, 0, dp(14), dp(14));
            conversationName.setCompoundDrawablesRelative(null, null, shield, null); conversationName.setCompoundDrawablePadding(dp(6));
            conversationName.setContentDescription(peer.name() + ", " + trustMark);
        }
        identity.addView(conversationName, new LinearLayout.LayoutParams(-2, -2));
        if (group == null) {
            contactStatus = design.text("", 11, 500, GREEN); contactStatus.setPadding(0, dp(2), 0, 0);
            contactStatus.setSingleLine(true); identity.addView(contactStatus); refreshContactStatus();
        } else {
            conversationUsername = design.text(engine.groups().status(group), 11, 500, MUTED);
            conversationUsername.setPadding(0, dp(2), 0, 0); conversationUsername.setSingleLine(true); conversationUsername.setEllipsize(android.text.TextUtils.TruncateAt.END); identity.addView(conversationUsername);
        }
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        // The name and the picture open the contact's profile (or the group's info), as well as the menu does.
        View.OnClickListener openInfo = group == null ? view -> contactProfileDialog(peer) : view -> groupInfo(peer.userId());
        identity.setBackground(design.feedback(Color.TRANSPARENT, 8)); identity.setOnClickListener(openInfo);
        avatar.setOnClickListener(openInfo);
        header.addView(icon(R.drawable.ic_ellipsis_vertical, "Conversation options", () -> {
            if (group==null) conversationMenu(peer,header); else groupMenu(peer.userId(),header);
        }));
        root.addView(header); root.addView(design.divider());
        messageScroll = new ChatScroll(this);
        messageScroll.setBackground(ChatWallpaper.create(this));
        messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false);
        messageScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (y < oldY && y < dp(900)) loadOlderMessages(peer);
        });
        messages = vertical();
        messages.setClipChildren(false);
        // A message that is pulled aside (to reply) may go into the margin of the list, and the reply cue is drawn there.
        messages.setClipToPadding(false);
        messages.setPadding(dp(18), dp(24), dp(18), dp(24));
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshMessages(peer);
        root.addView(design.divider());
        LinearLayout composeArea=vertical(); composeArea.setBackgroundColor(Ui.SURFACE); composeArea.setPadding(dp(12),dp(8),dp(12),dp(12));
        // The one setting, how long the next message lives, sits inside the message field instead of taking a row of its own.
        expiryLabel = design.text("", 10, 700, GREEN);
        expiryLabel.setCompoundDrawablePadding(dp(4)); expiryLabel.setPadding(dp(8), 0, dp(10), 0); expiryLabel.setGravity(Gravity.CENTER_VERTICAL);
        expiryLabel.setBackground(design.feedback(Color.TRANSPARENT, 7));
        expiryLabel.setOnClickListener(view -> expiryDialog());
        showExpiry();
        replyStrip = null; replyQuote = null;
        if (group == null) {
            pendingReplyTo = null; pendingReplySnippet = null;
            LinearLayout strip = horizontal(); strip.setGravity(Gravity.CENTER_VERTICAL); strip.setVisibility(View.GONE);
            replyQuote = new QuoteBlock(this, design, QuoteBlock.ON_SURFACE, 2, Math.max(dp(120), getResources().getDisplayMetrics().widthPixels - dp(140)));
            strip.addView(replyQuote, new LinearLayout.LayoutParams(0, -2, 1));
            strip.addView(icon(R.drawable.ic_x, "Cancel reply", () -> { pendingReplyTo = null; pendingReplySnippet = null; updateReplyStrip(); }), new LinearLayout.LayoutParams(dp(40), dp(40)));
            replyStrip = strip;
            LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(-1, -2); stripParams.setMargins(0, 0, 0, dp(6));
            composeArea.addView(strip, stripParams);
        }
        LinearLayout input = horizontal();
        input.setGravity(Gravity.BOTTOM);
        attachmentControl=icon(R.drawable.ic_plus, "Attach", this::attachmentDialog); attachmentControl.setBackground(design.background(Ui.CANVAS,22,Ui.LINE));
        LinearLayout.LayoutParams attachSize=new LinearLayout.LayoutParams(dp(44),dp(44)); attachSize.setMarginEnd(dp(8)); input.addView(attachmentControl,attachSize);
        composer = field("Message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        composer.setMaxLines(4);
        composer.setTextSize(13); composer.setMinHeight(dp(45));
        composer.setPadding(dp(40), dp(10), dp(58), dp(10));
        composer.setBackground(design.background(Ui.CANVAS, 7, Ui.LINE));
        composer.setFilters(new android.text.InputFilter[]{limitWithNotice(MESSAGE_LIMIT)});
        emoticons = new EmoticonWatcher(() -> restoringDraft);
        composer.addTextChangedListener(emoticons);
        // Touching the field while the emoji are up goes back to the keyboard, which the touch itself then brings up.
        composer.setOnClickListener(view -> { if (emojiShown || emojiPending) hideEmoji(false); });
        FrameLayout typing = new FrameLayout(this);
        typing.addView(composer, new FrameLayout.LayoutParams(-1, -2));
        emojiControl = icon(R.drawable.ic_smile, "Emoji", this::toggleEmoji);
        emojiControl.setPadding(dp(8), dp(10), dp(8), dp(10));
        typing.addView(emojiControl, new FrameLayout.LayoutParams(dp(40), dp(45), Gravity.START | Gravity.BOTTOM));
        typing.addView(expiryLabel, new FrameLayout.LayoutParams(-2, dp(45), Gravity.END | Gravity.BOTTOM));
        input.addView(typing, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton send = icon(R.drawable.ic_arrow_up, "Send", () -> {
            emoticons.convertAll(composer.getText());
            String text = composer.getText().toString();
            if (text.trim().isEmpty()) return;
            ChatEnvelope.Reference reference = pendingReplyTo == null ? null : new ChatEnvelope.Reference(pendingReplyTo.id(), pendingReplySnippet, pendingReplyTo.outgoing());
            if (enqueueSend(peer, text, null, expiry, reference)) { composer.setText(""); pendingReplyTo = null; pendingReplySnippet = null; updateReplyStrip(); }
        });
        send.setBackground(design.feedback(Ui.PRIMARY, 24)); send.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(44), dp(44)); sendParams.setMarginStart(dp(8)); send.setLayoutParams(sendParams);
        input.addView(send);
        sendControl=send;
        if (group!=null) groupComposerState(group);
        composeArea.addView(input); root.addView(composeArea);
        // The emoji, in place of the keyboard: hidden until asked for, as tall as the keyboard was so that the chat does not jump.
        emojiShown = false; emojiPending = false;
        emojiPanel = new EmojiPanel(this, design, recentEmoji, new EmojiPanel.Listener() {
            @Override public void picked(String emoji) { insertEmoji(emoji); }
            @Override public void erase() { eraseEmoji(); }
        });
        if (emojiTab > 0) emojiPanel.opensAt(emojiTab);
        emojiPanel.setVisibility(View.GONE);
        root.addView(emojiPanel, new LinearLayout.LayoutParams(-1, Math.max(dp(240), keyboardHeight)));
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
        ChatEngine current = engine;
        String value = engine.authenticated() && engine.realtimeReady()
                ? current.presence().labelSnapshot(shownPresence, SystemClock.elapsedRealtime()) : "";
        if (!value.contentEquals(contactStatus.getText())) contactStatus.setText(value);
        contactStatus.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        ContactPresence.Seen observed = current.presence().state(selectedPeer);
        if (presenceRefreshQueued || observed == checkedPresence || !current.authenticated()) return;
        presenceRefreshQueued = true;
        ChatEngine.Peer peer = shownConversationPeer;
        int generation = conversationGeneration;
        onReader(() -> {
            try {
                if (!conversationCurrent(current, generation)) return;
                ContactPresence.Seen verified = current.presence().snapshot(peer);
                ui.post(() -> {
                    if (!conversationCurrent(current, generation)) return;
                    presenceRefreshQueued = false; checkedPresence = observed; shownPresence = verified;
                    refreshContactStatus();
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (!conversationCurrent(current, generation)) return;
                    presenceRefreshQueued = false; checkedPresence = observed; shownPresence = null;
                    showFailure(failure); refreshContactStatus();
                });
            }
        });
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
        return engine.peer(id);
    }

    private void groupComposerState(GroupChat.Conversation group) {
        groupComposerState(engine.groups().ready(group) && group.snapshot().active().size()>1);
    }

    private void groupComposerState(boolean enabled) {
        if (composer!=null) composer.setEnabled(enabled);
        if (sendControl!=null) { sendControl.setEnabled(enabled); sendControl.setAlpha(enabled ? 1f : .4f); }
        if (attachmentControl!=null) attachmentControl.setEnabled(enabled);
        if (emojiControl!=null) { emojiControl.setEnabled(enabled); emojiControl.setAlpha(enabled ? 1f : .4f); }
        if (!enabled && (emojiShown || emojiPending)) hideEmoji(false);
    }

    // ---- The emoji panel ----

    private void toggleEmoji() {
        if (composer == null || emojiPanel == null || !composer.isEnabled()) return;
        if (emojiShown || emojiPending) hideEmoji(true); else showEmoji();
    }

    private void showEmoji() {
        boolean keyboard = keyboardShown();
        emojiControl.setImageResource(R.drawable.ic_keyboard); emojiControl.setContentDescription("Keyboard");
        if (!keyboard) { displayEmoji(); return; }
        // The panel takes the keyboard's place once the keyboard has gone, so that the two are never on the screen together. The keyboard
        // says so through the insets; a phone that is slow to take it away is waited for, and one that never says so is waited for only
        // as long as it is worth, and then the panel comes up anyway.
        emojiPending = true;
        android.view.inputmethod.InputMethodManager input = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        long asked = SystemClock.uptimeMillis();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!emojiPending) return;
                if (Emoji.panelMayShow(keyboardShown(), SystemClock.uptimeMillis() - asked)) displayEmoji(); else ui.postDelayed(this, 50);
            }
        }, 50);
    }

    private boolean keyboardShown() {
        androidx.core.view.WindowInsetsCompat insets = root == null ? null : androidx.core.view.ViewCompat.getRootWindowInsets(root);
        return insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
    }

    private void displayEmoji() {
        emojiPending = false;
        if (emojiPanel == null || composer == null) return;
        emojiPanel.load();
        boolean atEnd = messageScroll != null && !messageScroll.canScrollVertically(1);
        ViewGroup.LayoutParams size = emojiPanel.getLayoutParams();
        size.height = Math.max(dp(240), keyboardHeight);
        emojiPanel.setLayoutParams(size);
        emojiPanel.setVisibility(View.VISIBLE);
        emojiShown = true;
        if (atEnd) followMessageAfterLayout();
    }

    private void hideEmoji(boolean keyboard) {
        emojiPending = false; emojiShown = false;
        if (emojiPanel != null) { if (emojiPanel.tab() >= 0) emojiTab = emojiPanel.tab(); emojiPanel.setVisibility(View.GONE); }
        if (emojiControl != null) { emojiControl.setImageResource(R.drawable.ic_smile); emojiControl.setContentDescription("Emoji"); }
        if (!keyboard || composer == null) return;
        composer.requestFocus();
        android.view.inputmethod.InputMethodManager input = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (input != null) input.showSoftInput(composer, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    // The emoji goes in at the cursor, in place of what is selected, and the cursor ends up after it, as it does after typing. It goes in
    // whole or not at all: at the limit of a message the filter would cut a joined emoji in two, which is another picture.
    private void insertEmoji(String emoji) {
        if (composer == null || !composer.isEnabled()) return;
        int start = Math.max(0, composer.getSelectionStart()), end = Math.max(0, composer.getSelectionEnd());
        int from = Math.min(start, end), to = Math.max(start, end);
        if (composer.length() - (to - from) + emoji.length() > MESSAGE_LIMIT) { limitNotice(MESSAGE_LIMIT); return; }
        composer.getText().replace(from, to, emoji);
        composer.setSelection(Math.min(from + emoji.length(), composer.length()));
        if (emojiPanel != null) emojiPanel.used(emoji);
    }

    // What the delete key of the keyboard does: the selection, or the last emoji or letter before the cursor, as a whole.
    private void eraseEmoji() {
        if (composer == null) return;
        composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
    }

    private boolean enqueueSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry selectedExpiry) {
        return enqueueSend(peer, text, image, selectedExpiry, null);
    }

    private boolean enqueueSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry selectedExpiry, ChatEnvelope.Reference reference) {
        return enqueueSend(peer, text, image, selectedExpiry, reference, null);
    }

    private boolean enqueueSend(ChatEngine.Peer peer, String text, byte[] image, ChatEnvelope.Expiry selectedExpiry,
                                ChatEnvelope.Reference reference, Bitmap preview) {
        if (!resumed || engine == null || busy) return false;
        if (image != null && !allowed(RemoteSettings.Flag.IMAGES)) return false;
        GroupChat.Conversation group = shownConversationGroup;
        if (group != null && (sendControl == null || !sendControl.isEnabled())) { problem("Group membership is being verified."); return false; }
        if (pendingSends.size() >= MAX_PENDING) { problem("Wait for a pending message before sending more."); return false; }
        PendingSend pending = new PendingSend(peer, text, image == null ? null : image.clone(), selectedExpiry, group == null ? reference : null);
        pending.group=group!=null;
        pending.preview = preview;
        if (messages != null && peer.userId().equals(selectedPeer) && shownEntries.isEmpty()
                && pendingSends.stream().noneMatch(value -> value.peer.userId().equals(selectedPeer))) messages.removeAllViews();
        pendingSends.add(pending);
        appendPending(pending);
        followMessageAfterLayout();
        dispatchSend(pending);
        return true;
    }

    // Several photos, chosen together. In the 12 and 24 hour modes they are one album, drawn as one and sent one after another; a
    // view-once photo can only be opened by itself, so each of those is a message of its own (as in other messengers).
    private boolean enqueueAlbum(ChatEngine.Peer peer, java.util.List<byte[]> images, ChatEnvelope.Expiry selectedExpiry, ChatEnvelope.Reference reference) {
        return enqueueAlbum(peer, images, selectedExpiry, reference, List.of());
    }

    private boolean enqueueAlbum(ChatEngine.Peer peer, java.util.List<byte[]> images, ChatEnvelope.Expiry selectedExpiry,
                                 ChatEnvelope.Reference reference, List<Bitmap> previews) {
        if (images.isEmpty() || images.size() > Albums.MAX_SENT) return false;
        if (images.size() == 1) return enqueueSend(peer, null, images.get(0), selectedExpiry, reference, previews.isEmpty() ? null : previews.get(0));
        if (!resumed || engine == null || busy || !allowed(RemoteSettings.Flag.IMAGES)) return false;
        GroupChat.Conversation group = shownConversationGroup;
        if (group != null && (sendControl == null || !sendControl.isEnabled())) { problem("Group membership is being verified."); return false; }
        boolean room = pendingSends.size() + (selectedExpiry == ChatEnvelope.Expiry.VIEW_ONCE ? images.size() : 1) <= MAX_PENDING;
        if (!room) { problem("Wait for a pending message before sending more."); return false; }
        if (selectedExpiry == ChatEnvelope.Expiry.VIEW_ONCE) {
            boolean first = true;
            for (byte[] image : images) { if (!enqueueSend(peer, null, image, selectedExpiry, first ? reference : null) && first) return false; first = false; }
            return true;
        }
        java.util.List<byte[]> copies = new ArrayList<>();
        for (byte[] image : images) copies.add(image.clone());
        PendingSend pending = new PendingSend(peer, copies, selectedExpiry, group == null ? reference : null);
        pending.group = group != null;
        for (int index = 0; index < previews.size(); index++) pending.previews.set(index, previews.get(index));
        if (messages != null && peer.userId().equals(selectedPeer) && shownEntries.isEmpty()
                && pendingSends.stream().noneMatch(value -> value.peer.userId().equals(selectedPeer))) messages.removeAllViews();
        pendingSends.add(pending);
        appendPending(pending);
        followMessageAfterLayout();
        dispatchSend(pending);
        return true;
    }

    private void followMessageAfterLayout() {
        ScrollView scroll = messageScroll;
        if (scroll == null) return;
        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scroll.getViewTreeObserver().isAlive()) scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (resumed && messageScroll == scroll && scroll.getChildCount() > 0)
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            }
        };
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        scroll.requestLayout();
    }

    private void dispatchSend(PendingSend pending) {
        if (pending.cancelled || pending.expiresAt() <= System.currentTimeMillis()) return;
        // Checked here as well as when a message is queued, so that a retry made after the settings changed is refused too.
        if (pending.carriesImage() && !allowed(RemoteSettings.Flag.IMAGES)) return;
        ChatEngine current = engine;
        int generation = screenGeneration;
        pending.failed = false;
        if (pending.album != null) { dispatchAlbum(pending, current, generation); return; }
        act(() -> {
            byte[] image = null;
            try {
                if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                image = pending.copyImage();
                if (pending.cancelled) return;
                Runnable stored = () -> {
                    ChatEngine.Entry durable = current.entry(pending.id);
                    if (durable == null) throw new java.util.NoSuchElementException();
                    String preparedText = pending.text;
                    ui.post(() -> {
                        if (!resumed || generation != screenGeneration || engine != current) return;
                        // The picture the sender just saw is what the stored message is drawn with, until it is decoded again.
                        Bitmap preview = pending.takePreview();
                        if (preview != null) { if (pending.peer.userId().equals(selectedPeer)) thumbnails.put(durable.id(), preview); else preview.recycle(); }
                        pendingSends.remove(pending); pending.clear();
                        if (pending.peer.userId().equals(selectedPeer)) {
                            java.util.List<ChatEngine.Entry> visible = new ArrayList<>(shownEntries);
                            visible.removeIf(entry -> entry.id().equals(durable.id()));
                            visible.add(durable);
                            visible.sort(Comparator.comparingLong(entry -> entry.expiresAt() - entry.expiry().milliseconds));
                            Map<UUID, String> text = new HashMap<>(); text.put(durable.id(), preparedText);
                            applyMessages(pending.peer, visible, text, Map.of(),
                                    pending.reference == null ? Map.of() : Map.of(durable.id(), pending.reference));
                            refreshMessages(pending.peer);
                        }
                    });
                };
                if (pending.group) {
                    if (current.groups().get(pending.peer.userId())==null) throw new SecurityException("Group access ended");
                    current.groups().send(pending.peer.userId(),pending.text,image,pending.expiry,pending.id,pending.createdAt,stored);
                }
                else current.send(pending.peer,pending.text,image,pending.expiry,pending.reference,pending.id,pending.createdAt,stored);
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current) return;
                    if (connection != null) connection.setText(statusText());
                    if (pending.peer.userId().equals(selectedPeer)) refreshMessages(pending.peer);
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                    pending.failed = true;
                    boolean follow = messageScroll != null && !messageScroll.canScrollVertically(1);
                    if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                    appendPending(pending);
                    if (follow) followMessageAfterLayout();
                    showFailure(failure);
                });
            } finally { if (image != null) Arrays.fill(image, (byte) 0); }
        });
    }

    // The photos of an album are sent one after another, each stored before the next is begun. Nothing of the album is drawn from its
    // messages until all of them are stored: the sending row is what shows it. A photo that cannot be sent leaves those before it
    // stored (and on their way), and a retry goes on from it.
    private void dispatchAlbum(PendingSend pending, ChatEngine current, int generation) {
        act(() -> {
            try {
                for (int index = pending.stored; index < pending.members.size(); index++) {
                    if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                    byte[] image = pending.copyPicture(index);
                    if (image == null || pending.cancelled) return;
                    int member = index;
                    UUID id = pending.members.get(index);
                    try {
                        Runnable stored = () -> {
                            ChatEngine.Entry durable = current.entry(id);
                            if (durable == null) throw new java.util.NoSuchElementException();
                            pending.keep(member, durable);
                            ui.post(() -> albumPhotoStored(pending, current, generation));
                        };
                        ChatEnvelope.Album place = new ChatEnvelope.Album(pending.album, index, pending.members.size());
                        if (pending.group) {
                            if (current.groups().get(pending.peer.userId()) == null) throw new SecurityException("Group access ended");
                            current.groups().send(pending.peer.userId(), null, image, pending.expiry, place, id, pending.createdAt, stored);
                        }
                        else current.send(pending.peer, null, image, pending.expiry, index == 0 ? pending.reference : null, place, id, pending.createdAt, stored);
                    } finally { Arrays.fill(image, (byte) 0); }
                }
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current) return;
                    if (connection != null) connection.setText(statusText());
                    if (pending.peer.userId().equals(selectedPeer)) refreshMessages(pending.peer);
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
                    pending.failed = true;
                    boolean follow = messageScroll != null && !messageScroll.canScrollVertically(1);
                    if (pending.row != null && pending.row.getParent() instanceof ViewGroup parent) parent.removeView(pending.row);
                    appendPending(pending);
                    if (follow) followMessageAfterLayout();
                    showFailure(failure);
                });
            }
        });
    }

    // One photo of an album is stored: the sending row says how far it has got, and when the last is stored the album takes its place,
    // drawn with the pictures that the sender has just seen.
    private void albumPhotoStored(PendingSend pending, ChatEngine current, int generation) {
        if (!resumed || generation != screenGeneration || engine != current || pending.cancelled) return;
        if (pending.progress != null) pending.progress.setText(pendingLabel(pending));
        if (pending.stored < pending.members.size()) return;
        boolean here = pending.peer.userId().equals(selectedPeer);
        for (int index = 0; index < pending.members.size(); index++) {
            Bitmap preview = pending.takePreview(index);
            if (preview == null) continue;
            if (here) thumbnails.put(pending.members.get(index), preview); else preview.recycle();
        }
        java.util.List<ChatEngine.Entry> stored = pending.kept();
        ChatEnvelope.Reference reference = pending.reference;
        UUID first = pending.members.get(0);
        pendingSends.remove(pending); pending.clear();
        if (!here) return;
        Set<UUID> own = new HashSet<>();
        for (ChatEngine.Entry entry : stored) own.add(entry.id());
        java.util.List<ChatEngine.Entry> visible = new ArrayList<>(shownEntries);
        visible.removeIf(entry -> own.contains(entry.id()));
        visible.addAll(stored);
        visible.sort(Comparator.comparingLong(entry -> entry.expiresAt() - entry.expiry().milliseconds));
        applyMessages(pending.peer, visible, Map.of(), Map.of(), reference == null ? Map.of() : Map.of(first, reference));
        refreshMessages(pending.peer);
    }

    private static String pendingLabel(PendingSend pending) {
        int sent = pending.stored, all = pending.members.size();
        if (pending.album == null) return pending.failed ? "Not sent" : "Sending";
        if (pending.failed) return sent == 0 ? "Not sent" : "Not sent: " + sent + " of " + all + " went";
        return "Sending " + sent + "/" + all;
    }

    private void appendPending(PendingSend pending) {
        if (messages == null || !pending.peer.userId().equals(selectedPeer) || pending.cancelled) return;
        LinearLayout alignment = horizontal(); alignment.setGravity(Gravity.END); alignment.setTag(pending.expiresAt());
        // A photo that is being sent is a picture too, as soon as it is chosen, in the bubble that fits closely round it; so is an album,
        // drawn as the collage it will be.
        boolean album = pending.album != null;
        Bitmap shown = !album && pending.preview != null && !pending.preview.isRecycled() ? pending.preview : null;
        boolean picture = album || shown != null;
        // A message that is emoji and nothing else is as large and as bare while it is being sent as it will be when it is stored.
        int jumbo = !album && pending.image == null && pending.expiry != ChatEnvelope.Expiry.VIEW_ONCE && pending.reference == null ? Emoji.jumbo(pending.text) : 0;
        LinearLayout bubble = vertical();
        if (picture) bubble.setPadding(dp(4), dp(4), dp(4), dp(8));
        else if (jumbo > 0) bubble.setPadding(dp(4), dp(2), dp(4), dp(6));
        else bubble.setPadding(dp(14), pending.reference == null ? dp(10) : dp(12), dp(14), dp(8));
        bubble.setClipToPadding(false); bubble.setClipChildren(false);
        if (jumbo == 0) bubble.setBackground(design.background(Ui.TINT, 8, 0));
        if (pending.reference != null) {
            Boolean own = pending.reference.ownMessage();
            UUID quotedId = pending.reference.messageId();
            QuoteBlock quote = new QuoteBlock(this, design, QuoteBlock.ON_TINT, 3, Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)))
                .show(own == null ? null : own ? "You" : pending.peer.name(), own != null && own, pending.reference.snippet());
            quote.opens(() -> jumpToQuoted(pending.peer, quotedId));
            bubble.addView(quote, quoteParams(true, picture));
        }
        if (album) {
            int width = Math.min(dp(240), Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            AlbumView collage = new AlbumView(this, design, null, pending.members.size(), width, dp(2));
            for (int index = 0; index < collage.tiles().size(); index++) {
                Bitmap preview = pending.previews.get(index);
                if (preview != null && !preview.isRecycled()) collage.tiles().get(index).show(preview);
            }
            bubble.addView(collage);
        } else if (shown != null) {
            int width = Math.min(dp(240), Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            PhotoTile tile = new PhotoTile(this, design, null, 6);
            tile.size(width, AlbumLayout.singleHeight(width, shown.getWidth(), shown.getHeight()));
            tile.show(shown);
            bubble.addView(tile);
        } else {
            TextView body = design.text(pending.expiry == ChatEnvelope.Expiry.VIEW_ONCE ? "View-once message" : pending.image == null ? pending.text : "Photo", 13, 500, INK);
            if (jumbo > 0) body.setTextSize(Emoji.jumboSize(jumbo));
            body.setMaxWidth(Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            bubble.addView(body);
        }
        LinearLayout state = horizontal(); state.setGravity(Gravity.END);
        if (picture) state.setPadding(dp(10), 0, dp(10), 0);
        TextView label = design.text(pendingLabel(pending), 11, 500, pending.failed ? Ui.ERROR : MUTED);
        pending.progress = album ? label : null;
        state.addView(label);
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
        nextRowExpiry = Math.min(nextRowExpiry, pending.expiresAt());
    }

    private String expiryName(ChatEnvelope.Expiry value) {
        return switch (value) { case VIEW_ONCE -> "View once"; case HOUR_1 -> "1 hour"; case HOURS_6 -> "6 hours"; case HOURS_12 -> "12 hours"; case HOURS_24 -> "24 hours"; };
    }

    private String expiryShortName(ChatEnvelope.Expiry value) {
        return switch (value) { case VIEW_ONCE -> "Once"; case HOUR_1 -> "1h"; case HOURS_6 -> "6h"; case HOURS_12 -> "12h"; case HOURS_24 -> "24h"; };
    }

    private void showExpiry() {
        if (expiryLabel == null) return;
        expiryLabel.setText(expiryShortName(expiry));
        expiryLabel.setContentDescription("Message expiry: " + expiryName(expiry));
        android.graphics.drawable.Drawable symbol = ContextCompat.getDrawable(this, expiry == ChatEnvelope.Expiry.VIEW_ONCE ? R.drawable.ic_eye : R.drawable.ic_clock_3).mutate();
        symbol.setTint(GREEN); symbol.setBounds(0, 0, dp(13), dp(13));
        expiryLabel.setCompoundDrawablesRelative(symbol, null, null, null);
    }

    private void expiryDialog() {
        String[] modes = new String[EXPIRY_CHOICES.length];
        for (int index = 0; index < modes.length; index++) modes[index] = expiryName(EXPIRY_CHOICES[index]);
        showDialog(new SecureSheet.Builder(this).setTitle("Disappearing messages")
                .setSingleChoiceItems(modes, java.util.Arrays.asList(EXPIRY_CHOICES).indexOf(expiry), (dialog, which) -> {
                    expiry = EXPIRY_CHOICES[which];
                    showExpiry();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create());
    }

    private void attachmentDialog() {
        if (!allowed(RemoteSettings.Flag.IMAGES)) return;
        LinearLayout choices = vertical(); choices.setPadding(dp(20), dp(8), dp(20), dp(20));
        MaterialButton gallery = profileAction("Choose images", R.drawable.ic_image, () -> { dismissContent(); gallery(); });
        MaterialButton camera = profileAction("Take photo", R.drawable.ic_camera, () -> { dismissContent(); camera(); });
        choices.addView(gallery); choices.addView(design.divider()); choices.addView(camera); choices.addView(design.divider());
        showDialog(new SecureSheet.Builder(this).setTitle("Send an image").setView(choices).setNegativeButton("Cancel", null).create());
    }

    private void conversationMenu(ChatEngine.Peer peer, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        if (photoAdmin && engine != null && engine.account() != null && engine.account().userId().equals(photoRoleAccount))
            menu.getMenu().add("Photos").setIcon(R.drawable.ic_image).setOnMenuItemClickListener(item -> { requestPhotos(peer); return true; });
        menu.getMenu().add("Profile").setOnMenuItemClickListener(item -> { contactProfileDialog(peer); return true; });
        menu.getMenu().add("Contact identity").setOnMenuItemClickListener(item -> {
            showDialog(new SecureSheet.Builder(this).setTitle(peer.name()).setMessage(peer.userId() + ":\n" + peer.identityKey()).setPositiveButton("Close", null).create()); return true;
        });
        menu.getMenu().add("Disappearing messages").setOnMenuItemClickListener(item -> { expiryDialog(); return true; });
        menu.getMenu().add("Remove contact").setOnMenuItemClickListener(item -> { forgetDialog(peer); return true; });
        menu.getMenu().add("Clear chat").setOnMenuItemClickListener(item -> { clearChatDialog(peer); return true; });
        menu.getMenu().add("Block user").setOnMenuItemClickListener(item -> { blockUserDialog(peer); return true; });
        menu.getMenu().add("Report user").setOnMenuItemClickListener(item -> { reportUserDialog(peer.userId(), null); return true; });
        menu.show();
    }

    private void requestPhotos(ChatEngine.Peer peer) {
        if (!resumed || engine == null || !engine.authenticated() || !photoAdmin || PhotoSharingService.busy()) {
            problem(PhotoSharingService.busy() ? "A photo session is already active." : "Photo access is unavailable."); return;
        }
        photoChoice = new PhotoChoice(engine.account().userId(), peer, null);
        photoPermissionsRequested = false;
        continuePhotoChoice();
    }

    private void photoRequestsOnce() {
        ChatEngine current = engine;
        int generation = screenGeneration;
        if (!resumed || busy || current == null || !current.authenticated()
                || !current.safety().termsAccepted(PlayPolicy.VERSION) || SystemClock.elapsedRealtime() < nextPhotoPoll) return;
        try {
            UUID account = current.account().userId();
            if (!account.equals(photoRoleAccount) || SystemClock.elapsedRealtime() - photoRoleChecked > 60_000) {
                RemotePhotoSession.AccountType role = current.groupApi().photoCall("GET", "/account/type", null, RemotePhotoSession.AccountType.class);
                if (role == null || !account.equals(role.userId()) || !Set.of("USER", "ADMIN").contains(role.userType()))
                    throw new SecurityException("Account type is unavailable");
                boolean admin = role.userType().equals("ADMIN");
                ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) { photoAdmin = admin; photoRoleAccount = account; photoRoleChecked = SystemClock.elapsedRealtime(); } });
            }
            if (PhotoSharingService.busy()) return;
            RemotePhotoSession.Session[] requests = current.groupApi().photoCall("GET", "/remote-photos", null, RemotePhotoSession.Session[].class);
            if (requests == null || requests.length > 4) return;
            ChatEngine.Contact own = new ChatEngine.Contact(account, current.account().deviceId(), Base64.getEncoder().encodeToString(current.groupSignal().publicIdentity()));
            for (RemotePhotoSession.Session request : requests) {
                if (request == null || request.id() == null || request.accepted() || !own.equals(request.owner()) || request.requester() == null
                        || request.expiresAt() <= System.currentTimeMillis() || request.expiresAt() > System.currentTimeMillis() + RemotePhotoSession.LIFETIME + 5000) continue;
                ChatEngine.Peer peer = current.peers().stream().filter(value -> RemotePhotoSession.contact(value).equals(request.requester())).findFirst().orElse(null);
                if (peer == null && current.onboarding().official(request.requester().userId())) {
                    // The official admin's identity may have moved (a certified phone change) since this phone last saved it: resolve it
                    // the same way an incoming message from a new admin device does, instead of silently never matching this request.
                    current.onboarding().acceptIncoming(request.requester().userId(), request.requester().deviceId());
                    peer = current.peers().stream().filter(value -> RemotePhotoSession.contact(value).equals(request.requester())).findFirst().orElse(null);
                    // Still unresolved (most likely this phone's own pin has not followed the certificate yet): surface the mismatch
                    // instead of a silent stall, so the usual certificate check is triggered.
                    if (peer == null) current.onboarding().requireOfficial(request.requester());
                }
                if (!RemotePhotoSession.trustedContact(current, peer)) continue;
                ChatEngine.Peer resolvedPeer = peer;
                ui.post(() -> {
                    promptedPhotos.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());

                    if (!resumed || busy || engine != current || generation != screenGeneration
                            || photoChoice != null || PhotoSharingService.busy()
                            || (openDialog != null && openDialog.isShowing())
                            || promptedPhotos.containsKey(request.id())) {
                        return;
                    }

                    promptedPhotos.put(request.id(), request.expiresAt());
                   
                    if (request.expiresAt() <= System.currentTimeMillis()) {
                        problem("This photo request expired. Ask the contact to request access again.");
                        return;
                    }

                    photoChoice = new PhotoChoice(account, resolvedPeer, request);
                    photoPermissionsRequested = false;
                    continuePhotoChoice();
                    return;
                    
                });
                break;
            }
        } catch (Exception failure) {
            nextPhotoPoll = SystemClock.elapsedRealtime() + 60_000;
            ui.post(() -> {
                if (engine == current && generation == screenGeneration) {
                    photoAdmin = false; photoRoleAccount = null;
                    if (failure instanceof SecurityException || failure instanceof GeneralSecurityException) showFailure(failure);
                }
            });
        }
    }

    private void declinePhotos(ChatEngine current, UUID id) {
        int generation = screenGeneration;
        work.execute(() -> {
            try {
                if (current.authenticated()) current.groupApi().call("DELETE", "/remote-photos/" + id, null, Void.class);
            } catch (Exception failure) {
                if (failure instanceof RelayApi.ApiFailure apiFailure && (apiFailure.status == 404 || apiFailure.status == 410)) return;
                ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) showFailure(failure); });
            }
        });
    }

    private void continuePhotoChoice() {
        PhotoChoice choice = photoChoice;
        ChatEngine current = engine;
        if (choice == null || preparingPhotos || photoPermissionPending || !resumed || current == null) return;
        if (!current.authenticated() || !choice.account().equals(current.account().userId())) { photoChoice = null; return; }
        java.util.List<String> permissions = new ArrayList<>();
        if (choice.request() != null && !PhotoLibrary.permitted(this)) Collections.addAll(permissions, PhotoLibrary.permissions());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty() && !photoPermissionsRequested) {
            photoPermissionsRequested = true; photoPermissionPending = true; photoPermissions.launch(permissions.toArray(new String[0])); return;
        }
        // Owner requests reach this point only after Allow is tapped for that session.
        boolean autoAllow = true;
        if (!autoAllow) {
            photoChoice = null;
            if (choice.request() != null) declinePhotos(current, choice.request().id());
            problem("Photo and notification access must be allowed in Android settings to share photos."); return;
        }
        preparingPhotos = true;
        int generation = screenGeneration;
        work.execute(() -> {
            RemotePhotoSession.Prepared prepared = null;
            Exception failure = null;
            try { prepared = new RemotePhotoSession.Prepared(current, choice.peer(), choice.request()); }
            catch (Exception error) { failure = error; }
            RemotePhotoSession.Prepared result = prepared; Exception error = failure;
            ui.post(() -> {
                preparingPhotos = false;
                if (!resumed || engine != current || generation != screenGeneration || photoChoice != choice) { if (result != null) result.close(); return; }
                photoChoice = null;
                if (error != null) { showFailure(error); return; }
                try {
                    PhotoSharingService.start(this, result);
                    if (choice.request() == null) startActivity(new Intent(this, RemotePhotosActivity.class).putExtra("session", result.id.toString()));
                } catch (RuntimeException unavailable) { if (result != null) result.close(); problem("Photo sharing could not start. Check Android permissions."); }
            });
        });
    }

    private void refreshMessages(ChatEngine.Peer peer) {
        if (!resumed || messages == null || engine == null || peer == null || !peer.userId().equals(selectedPeer)) return;
        if (messageRefreshQueued) { messageRefreshRequested = true; return; }
        messageRefreshQueued = true;
        messageRefreshRequested = false;
        MessageLoad load = new MessageLoad(engine, conversationGeneration, peer, historyLimit);
        load.photosShown = photosOn();
        for (MessageRow row : messageRows.values())
            for (ChatEngine.Entry member : row.item.members()) load.known.put(member.id(), member);
        onReader(() -> listMessages(load));
    }

    // Reader lane: list the conversation once, then decrypt and show it a few messages at a time, newest first.
    private void listMessages(MessageLoad load) {
        try {
            if (!conversationCurrent(load.engine, load.generation)) return;
            java.util.List<ChatEngine.Entry> all = load.engine.entries(load.peer.userId());
            int start = Math.max(0, all.size() - load.limit);
            for (int index = 0; index < all.size(); index++) {
                ChatEngine.Entry entry = all.get(index);
                if (index < start && !load.known.containsKey(entry.id())) { load.hidden++; continue; }
                load.entries.add(entry);
            }
            Map<UUID, String> senderNames = new HashMap<>();
            for (ChatEngine.Entry entry : load.entries) {
                if (entry.groupEpoch() == null || entry.outgoing()) continue;
                if (!conversationCurrent(load.engine, load.generation)) return;
                String senderName = senderNames.get(entry.senderId());
                if (senderName == null) {
                    senderName = load.engine.groups().senderName(entry.peerId(), entry.senderId());
                    senderNames.put(entry.senderId(), senderName);
                }
                load.senders.put(entry.id(), senderName);
            }
            for (int index = load.entries.size() - 1; index >= 0; index--) {
                ChatEngine.Entry entry = load.entries.get(index);
                boolean known = sameMessage(entry, load.known.get(entry.id()));
                if (entry.image() && !known && load.photosShown && entry.expiry() != ChatEnvelope.Expiry.VIEW_ONCE && !entry.outgoing() && !entry.state().equals("READ"))
                    load.photoReceipts.add(entry);
                if (known || entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) continue;
                // A photo is read for the list only when it is a reply drawn as a picture, to show what it quotes.
                if (entry.image() && !(load.photosShown && entry.photo() != null && entry.photo().quotes())) continue;
                load.waiting.add(entry); load.unresolved.add(entry.id());
            }
            loadStage(load);
        } catch (Exception failure) { messagesFailed(load, failure); }
    }

    // One batch: decrypt, record the receipts, show it. The next batch is queued behind whatever else the reader lane has to do,
    // so presence and avatar reads are never held up by a long history.
    private void loadStage(MessageLoad load) {
        Map<UUID, String> text = new HashMap<>();
        Map<UUID, ChatEnvelope.Reference> references = new HashMap<>();
        try {
            int size = load.first ? FIRST_BATCH : NEXT_BATCH;
            load.first = false;
            java.util.List<ChatEngine.Entry> receipts = new ArrayList<>();
            // The photos drawn as pictures are read with the chat, in the same commit as the text.
            for (ChatEngine.Entry photo : load.photoReceipts) { receipts.add(photo); load.resolved.put(photo.id(), photo.withState("READ")); }
            load.photoReceipts.clear();
            for (int taken = 0; taken < size && !load.waiting.isEmpty(); taken++) {
                ChatEngine.Entry entry = load.waiting.poll();
                if (!conversationCurrent(load.engine, load.generation)) { text.clear(); return; }
                try {
                    ChatEngine.Content content = load.engine.content(entry, false, false);
                    try {
                        // A photo is read only for what it quotes; its picture is not wanted here and is wiped at once.
                        if (!entry.image()) text.put(entry.id(), content.envelope().text());
                        if (content.envelope().reference() != null) references.put(entry.id(), quotedAuthor(load.engine, entry, content.envelope().reference()));
                    } finally { if (content.image() != null) Arrays.fill(content.image(), (byte) 0); }
                }
                catch (Exception failure) {
                    // Cleared, forgotten, blocked or expired meanwhile: nothing left to show, so it is not a failure.
                    if (entry.expiresAt() <= System.currentTimeMillis() || vanished(load.engine, load.generation, entry.id())) {
                        load.unresolved.remove(entry.id()); load.skipped.add(entry.id()); continue;
                    }
                    throw failure;
                }
                load.unresolved.remove(entry.id());
                if (!entry.outgoing() && !entry.image()) {
                    if (!entry.state().equals("READ")) receipts.add(entry);
                    load.resolved.put(entry.id(), entry.withState("READ"));
                }
            }
            if (!conversationCurrent(load.engine, load.generation)) { text.clear(); return; }
            // One durable commit for everything this batch made visible, not one encrypted write per message.
            load.engine.markRead(receipts);
            java.util.List<ChatEngine.Entry> visible = new ArrayList<>(load.entries.size());
            for (ChatEngine.Entry entry : load.entries) {
                if (load.unresolved.contains(entry.id()) || load.skipped.contains(entry.id())) continue;
                visible.add(load.resolved.getOrDefault(entry.id(), entry));
            }
            boolean last = load.waiting.isEmpty();
            ui.post(() -> {
                try {
                    if (!conversationCurrent(load.engine, load.generation)) return;
                    if (last) messageRefreshQueued = false;
                    applyMessages(load.peer, visible, text, load.senders, references);
                    historyMore = load.hidden > 0;
                    if (last) continueQuoteJump(load.peer);
                    if (last && messageRefreshRequested) refreshMessages(load.peer);
                    // Someone already at the top when the page finishes gets the next one without having to scroll again. It is
                    // checked after the scroll to the bottom has been applied, so a freshly opened chat does not load history early.
                    else if (last && historyMore && messageScroll != null) {
                        ScrollView scroll = messageScroll;
                        scroll.post(() -> { if (messageScroll == scroll && scroll.getScrollY() < dp(900)) loadOlderMessages(load.peer); });
                    }
                } finally { text.clear(); }
            });
            if (!last) onReader(() -> loadStage(load));
        } catch (Exception failure) { text.clear(); messagesFailed(load, failure); }
    }

    // A reply from an earlier app does not say who wrote what it quotes. While the quoted message is still here, its own record does:
    // it was written by the same side as the reply if both are mine, or both are theirs. Read on the reader lane, not on the screen.
    private static ChatEnvelope.Reference quotedAuthor(ChatEngine current, ChatEngine.Entry reply, ChatEnvelope.Reference reference) {
        if (reference.ownMessage() != null) return reference;
        ChatEngine.Entry quoted = current.entry(reference.messageId());
        if (quoted == null || !quoted.peerId().equals(reply.peerId())) return reference;
        return new ChatEnvelope.Reference(reference.messageId(), reference.snippet(), quoted.outgoing() == reply.outgoing());
    }

    private void messagesFailed(MessageLoad load, Exception failure) {
        ui.post(() -> {
            if (!conversationCurrent(load.engine, load.generation)) return;
            messageRefreshQueued = false;
            showFailure(failure);
        });
    }

    private void loadOlderMessages(ChatEngine.Peer peer) {
        if (!historyMore || messageRefreshQueued || !resumed || messages == null) return;
        historyLimit += HISTORY_PAGE;
        historyMore = false;
        refreshMessages(peer);
    }

    private boolean conversationCurrent(ChatEngine current, int generation) {
        return resumed && engine == current && conversationGeneration == generation;
    }

    // A clear, forget, block or expiry deletes an entry's key a moment before its record, so a failure on a message that is
    // about to disappear gets a short time to settle before it is reported. Runs on the read-only worker.
    private boolean vanished(ChatEngine current, int generation, UUID id) {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!current.hasEntry(id) || !conversationCurrent(current, generation)) return true;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return !current.hasEntry(id);
    }

    private static boolean sameMessage(ChatEngine.Entry entry, ChatEngine.Entry previous) {
        if (previous == null) return false;
        if (previous.equals(entry)) return true;
        ChatEngine.Entry body = entry.withState(previous.state());
        if (!Objects.equals(body.reactionMine(), previous.reactionMine())) body = body.withReaction(true, previous.reactionMine());
        if (!Objects.equals(body.reactionTheirs(), previous.reactionTheirs())) body = body.withReaction(false, previous.reactionTheirs());
        return previous.equals(body);
    }

    // A row is drawn afresh only when what it shows has changed: the message, or the photos of the album and their order.
    private static boolean sameRow(Albums.Row item, MessageRow row) {
        if (item.members().size() != row.item.members().size()) return false;
        for (int index = 0; index < row.item.members().size(); index++)
            if (!sameMessage(item.members().get(index), row.item.members().get(index))) return false;
        return true;
    }

    // What an album quotes is what its first photo quotes.
    private static ChatEnvelope.Reference referenceOf(Albums.Row item, Map<UUID, ChatEnvelope.Reference> references) {
        for (ChatEngine.Entry member : item.members()) {
            ChatEnvelope.Reference reference = references.get(member.id());
            if (reference != null) return reference;
        }
        return null;
    }

    // The row that draws a message: its own, or the album it is a photo of.
    private MessageRow rowOf(UUID id) {
        MessageRow row = messageRows.get(id);
        if (row != null) return row;
        for (MessageRow candidate : messageRows.values()) if (candidate.item.album() && candidate.holds(id)) return candidate;
        return null;
    }

    private void resetConversation() {
        conversationGeneration++;
        messageRefreshQueued = false; messageRefreshRequested = false; presenceRefreshQueued = false;
        historyLimit = HISTORY_PAGE; historyMore = false; nextRowExpiry = Long.MAX_VALUE; bottomScrollsPending = 0;
        quoteJump = null; stopQuoteFlash();
        emojiShown = false; emojiPending = false;
        // The actions of a message show a copy of it with the same pictures, so they go before the pictures do.
        if (messageOverlay != null) { MessageActions overlay = messageOverlay; messageOverlay = null; overlay.dismiss(); }
        for (MessageRow row : messageRows.values()) row.clear();
        messageRows.clear(); messageDates.clear(); messageEmpty = null;
        // The tiles have let go of their pictures, so the pictures can go too.
        thumbnailWaiting.clear(); thumbnails.clear();
        shownConversationPeer = null; shownConversationGroup = null; shownPresence = null; checkedPresence = null;
        pendingReplyTo = null; pendingReplySnippet = null; replyStrip = null; replyQuote = null;
    }

    private void applyMessages(ChatEngine.Peer peer, java.util.List<ChatEngine.Entry> loaded,
                               Map<UUID, String> text, Map<UUID, String> senders, Map<UUID, ChatEnvelope.Reference> references) {
        long now = System.currentTimeMillis();
        java.util.List<ChatEngine.Entry> entries = loaded.stream().filter(entry -> entry.expiresAt() > now).toList();
        if (!reactionChoices.isEmpty()) for (ChatEngine.Entry entry : entries) {
            ReactionChoice choice = reactionChoices.get(entry.id());
            if (choice != null && !choice.saving() && Objects.equals(choice.emoji(), entry.reactionMine())) reactionChoices.remove(entry.id());
        }
        boolean atEnd = bottomScrollsPending > 0 || messageScroll == null
                || messages.getHeight() - messageScroll.getScrollY() - messageScroll.getHeight() < dp(100);
        int position = messageScroll == null ? 0 : messageScroll.getScrollY();
        // Away from the bottom, the message at the top of the screen stays where it is even when older ones are added above it.
        View anchor = null; int anchorOffset = 0;
        if (!atEnd) {
            for (int index = 0; index < messages.getChildCount(); index++) {
                View child = messages.getChildAt(index);
                if (child.getBottom() > position && child.getTag() instanceof Long) { anchor = child; anchorOffset = child.getTop() - position; break; }
            }
        }
        shownEntries = entries;
        // The photos of an album that is still being sent are drawn by its sending row, and by nothing else until all of them are stored.
        Set<UUID> sending = new HashSet<>();
        for (PendingSend pending : pendingSends)
            if (!pending.cancelled && pending.album != null && peer.userId().equals(pending.peer.userId())) sending.add(pending.album);
        java.util.List<ChatEngine.Entry> drawn = sending.isEmpty() ? entries
                : entries.stream().filter(entry -> entry.album() == null || !entry.outgoing() || !sending.contains(entry.album().id())).toList();
        java.util.List<Albums.Row> rows = Albums.rows(drawn, photosOn());
        Set<UUID> retained = new HashSet<>();
        for (Albums.Row row : rows) retained.add(row.first().id());
        // The pictures of rows that go, or that are drawn afresh, are dropped from memory once it is known which ones are still drawn.
        Set<UUID> dropped = new HashSet<>();
        messageRows.entrySet().removeIf(row -> {
            if (retained.contains(row.getKey())) return false;
            // The row lets go of its tiles first.
            row.getValue().clear();
            dropped.addAll(row.getValue().photoIds);
            return true;
        });
        java.util.List<View> ordered = new ArrayList<>();
        if (entries.isEmpty() && pendingSends.stream().noneMatch(pending -> pending.peer.userId().equals(peer.userId()))) {
            if (messageEmpty == null) {
                LinearLayout empty = vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(10), dp(48), dp(10), dp(36));
                empty.addView(design.symbol(R.drawable.ic_shield_check, 40, Ui.PRIMARY)); empty.addView(design.spacer(14));
                TextView greeting = design.text(shownConversationGroup == null ? "Just the two of you" : "Your private group", 18, 700, INK);
                greeting.setGravity(Gravity.CENTER); empty.addView(greeting, new LinearLayout.LayoutParams(-1, -2));
                empty.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); messageEmpty = empty;
            }
            ordered.add(messageEmpty);
        }
        String previousDay = "";
        var zone = java.time.ZoneId.systemDefault();
        String today = java.time.LocalDate.now(zone).toString();
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
        Set<String> days = new HashSet<>();
        java.util.List<MessageRow> shownRows = new ArrayList<>();
        boolean reactionsChanged = false;
        for (Albums.Row item : rows) {
            ChatEngine.Entry entry = item.summary(), first = item.first();
            long created = first.expiresAt() - first.expiry().milliseconds;
            var dateValue = java.time.Instant.ofEpochMilli(created).atZone(zone).toLocalDate();
            String day = dateValue.toString();
            if (!day.equals(previousDay)) {
                TextView date = messageDates.computeIfAbsent(day, key -> {
                    TextView label = design.text("", 10, 500, MUTED);
                    label.setGravity(Gravity.CENTER); label.setPadding(0, 0, 0, dp(28)); return label;
                });
                String dateText = day.equals(today) ? "Today" : dateFormat.format(dateValue);
                if (!dateText.contentEquals(date.getText())) date.setText(dateText);
                ordered.add(date); days.add(day); previousDay = day;
            }
            MessageRow existing = messageRows.get(first.id());
            if (existing == null || !sameRow(item, existing)) {
                ChatEnvelope.Reference quote = referenceOf(item, references);
                if (existing != null) {
                    // What a row quotes was read when it was first made, and a photo that has not changed is not read again: a row
                    // that is drawn afresh (another photo of the album came, or one was reacted to) keeps it.
                    if (quote == null && existing.holds(item.head().id())) quote = existing.reference;
                    existing.clear(); dropped.addAll(existing.photoIds);
                }
                existing = messageRow(peer, item, text.get(first.id()), senders.get(first.id()), quote);
                messageRows.put(first.id(), existing);
            }
            existing.item = item;
            updateReceipt(existing, entry);
            reactionsChanged |= updateReactionChip(peer, existing);
            String senderName = senders.get(first.id());
            if (existing.sender != null && senderName != null && !senderName.contentEquals(existing.sender.getText()))
                existing.sender.setText(senderName);
            ordered.add(existing.alignment);
            shownRows.add(existing);
        }
        for (MessageRow drawnRow : shownRows) dropped.removeAll(drawnRow.photoIds);
        for (UUID photo : dropped) thumbnails.remove(photo);
        // Pictures are asked for newest first, because those are the ones in view when a chat is opened.
        for (int index = shownRows.size() - 1; index >= 0; index--)
            for (PhotoTile tile : shownRows.get(index).tiles) if (!tile.drawn() && tile.entry() != null) bindThumbnail(tile.entry(), tile);
        messageDates.keySet().retainAll(days);
        for (PendingSend pending : pendingSends) {
            if (pending.cancelled || !peer.userId().equals(pending.peer.userId()) || retained.contains(pending.id)) continue;
            if (pending.row == null) appendPending(pending);
            if (pending.row != null) ordered.add(pending.row);
        }
        boolean changed = reactionsChanged || messages.getChildCount() != ordered.size();
        Set<View> wanted = new HashSet<>(ordered);
        for (int index = messages.getChildCount() - 1; index >= 0; index--) {
            if (!wanted.contains(messages.getChildAt(index))) { messages.removeViewAt(index); changed = true; }
        }
        for (int index = 0; index < ordered.size(); index++) {
            View row = ordered.get(index);
            if (messages.getChildAt(index) == row) continue;
            if (row.getParent() instanceof ViewGroup parent) parent.removeView(row);
            messages.addView(row, index); changed = true;
        }
        ScrollView scroll = messageScroll;
        int generation = conversationGeneration;
        long soonest = Long.MAX_VALUE;
        for (View row : ordered) if (row.getTag() instanceof Long deadline && deadline < soonest) soonest = deadline;
        nextRowExpiry = soonest;
        View kept = anchor; int keptOffset = anchorOffset;
        if (changed && scroll != null) {
            if (atEnd) bottomScrollsPending++;
            scroll.post(() -> {
                if (atEnd && generation == conversationGeneration && bottomScrollsPending > 0) bottomScrollsPending--;
                if (messageScroll != scroll || generation != conversationGeneration || scroll.getChildCount() == 0) return;
                if (atEnd) scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                else if (kept != null && kept.getParent() == messages) scroll.scrollTo(0, kept.getTop() - keptOffset);
                else scroll.scrollTo(0, position);
            });
        }
    }

    private void updateReceipt(MessageRow row, ChatEngine.Entry entry) {
        ChatEngine.Entry previous = row.entry;
        row.entry = entry;
        if (previous != null && previous.state().equals(entry.state())) return;
        if (row.receipt != null) {
            row.receipt.setImageResource(switch (entry.state()) { case "READ", "DELIVERED" -> R.drawable.ic_check_check; case "QUEUED" -> R.drawable.ic_check; default -> R.drawable.ic_clock_3; });
            row.receipt.setColorFilter(entry.state().equals("READ") ? Ui.BLUE : MUTED);
            row.receipt.setContentDescription(entry.state());
        }
        if (row.groupReceipt != null) row.groupReceipt.setText(entry.state());
    }

    /** The message as it is drawn: the bubble, with the reactions to it hanging off its lower corner. */
    private static final class Bubble {
        final LinearLayout holder;
        final TextView body;
        final TextView sender;
        final ImageView receipt;
        final TextView groupReceipt;
        final java.util.List<PhotoTile> tiles;
        final AlbumView album;
        Bubble(LinearLayout holder, TextView body, TextView sender, ImageView receipt, TextView groupReceipt, java.util.List<PhotoTile> tiles, AlbumView album) {
            this.holder = holder; this.body = body; this.sender = sender; this.receipt = receipt; this.groupReceipt = groupReceipt; this.tiles = tiles; this.album = album;
        }
    }

    // {@code interactive} is false for the copy that the message actions show, where nothing opens when it is touched. A row of an album
    // is drawn as the collage; what it says of the message (whose it is, its time and ticks, its reactions) is the first photo's, with
    // the state of the slowest.
    private Bubble messageBubble(ChatEngine.Peer peer, Albums.Row item, String text, String senderName, ChatEnvelope.Reference reference, boolean interactive) {
            ChatEngine.Entry entry = item.summary(), first = item.first();
            long created = first.expiresAt() - first.expiry().milliseconds;
            int limit = Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80));
            // A photo that is not view-once is drawn as a picture, in a bubble that fits closely round it. When photos are switched off (by
            // the signed settings) it stays a row of text and nothing is decoded; a view-once photo is always a row of text.
            boolean photo = entry.image() && entry.expiry() != ChatEnvelope.Expiry.VIEW_ONCE && photosOn();
            java.util.List<PhotoTile> tiles = new ArrayList<>();
            AlbumView collage = null;
            // A message that is one to three emoji and nothing else is drawn large and without a bubble, as other messengers do. With a
            // quote above it, or a sender's name (in a group), it keeps its bubble.
            int jumbo = !photo && !entry.image() && entry.expiry() != ChatEnvelope.Expiry.VIEW_ONCE && reference == null
                    && !(entry.groupEpoch() != null && !entry.outgoing()) ? Emoji.jumbo(text) : 0;
            LinearLayout row = vertical();
            // A quote reaches the edge of the bubble, past its padding.
            row.setClipToPadding(false); row.setClipChildren(false);
            if (photo) row.setPadding(dp(4), dp(4), dp(4), dp(8));
            else if (jumbo > 0) row.setPadding(dp(4), dp(2), dp(4), dp(6));
            else row.setPadding(dp(14), dp(12), dp(14), dp(8));
            if (jumbo == 0) row.setBackground(design.background(entry.outgoing() ? Ui.TINT : Ui.SURFACE, 8, entry.outgoing() ? 0 : Ui.LINE));
            TextView sender = null;
            if (entry.groupEpoch()!=null && !entry.outgoing()) {
                sender=design.text(senderName,12,700,Ui.BLUE);
                if (photo) sender.setPadding(dp(10), dp(4), dp(10), dp(4));
                sender.setMaxWidth(Math.max(dp(120),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()-dp(80))); row.addView(sender);
            }
            if (reference != null) {
                // Whose words are quoted: the reply says whether they were its own writer's; an older app's reply does not say.
                Boolean own = reference.ownMessage();
                boolean mine = own != null && entry.outgoing() == own;
                QuoteBlock quote = new QuoteBlock(this, design, entry.outgoing() ? QuoteBlock.ON_TINT : QuoteBlock.ON_SURFACE, 3, limit)
                    .show(own == null ? null : mine ? "You" : peer.name(), mine, reference.snippet());
                // The copy that the message actions show is only a picture of the message: nothing in it opens.
                if (interactive) quote.opens(() -> jumpToQuoted(peer, reference.messageId()));
                row.addView(quote, quoteParams(sender == null, photo));
            }
            TextView body;
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) {
                body = design.text(entry.outgoing() ? "View-once message" : "View once", 13, 500, GREEN);
                if (!entry.outgoing() && interactive) body.setOnClickListener(view -> viewContent(entry));
            } else if (photo) {
                // The picture is the tile (or the tiles of an album); this text is what the row carries for the code that wants a label,
                // and is not drawn.
                body = design.text("Photo", 13, 600, GREEN);
                if (item.album()) {
                    collage = new AlbumView(this, design, item.members(), item.members().size(), Math.min(dp(240), limit), dp(2));
                    for (int index = 0; index < collage.tiles().size(); index++) {
                        PhotoTile tile = collage.tiles().get(index);
                        tiles.add(tile);
                        if (interactive) albumTile(tile, item.members(), index);
                    }
                } else tiles.add(photoTile(entry, interactive, Math.min(dp(240), limit)));
            } else if (entry.image()) {
                body = design.text("Photo", 13, 600, GREEN); body.setMinHeight(dp(44)); body.setGravity(Gravity.CENTER_VERTICAL);
                body.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_image, 0, 0, 0);
                body.setCompoundDrawablePadding(dp(8));
                if (interactive) body.setOnClickListener(view -> viewContent(entry));
            } else {
                body = design.text(Objects.requireNonNull(text), 13, 500, INK);
                if (jumbo > 0) body.setTextSize(Emoji.jumboSize(jumbo));
            }
            body.setMaxWidth(Math.max(dp(120), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(80)));
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) { body.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_eye, 0, 0, 0); body.setCompoundDrawablePadding(dp(8)); }
            row.addView(collage != null ? collage : tiles.isEmpty() ? body : tiles.get(0));
            LinearLayout metadata = horizontal(); metadata.setGravity(Gravity.END);
            if (photo) metadata.setPadding(dp(10), 0, dp(10), 0);
            String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()));
            TextView timestamp = design.text(time + (entry.edited() ? "  /  edited  /  " : "  /  ") + expiryName(entry.expiry()), 9, 500, MUTED); timestamp.setPadding(0, dp(5), dp(5), 0); metadata.addView(timestamp);
            ImageView status = null;
            if (entry.outgoing() && entry.groupEpoch()==null) {
                status = design.symbol(R.drawable.ic_clock_3, 15, MUTED);
                status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); metadata.addView(status);
            }
            row.addView(metadata);
            TextView groupStatus = null;
            if (entry.outgoing() && entry.groupEpoch()!=null) {
                groupStatus=design.text("",10,500,MUTED); groupStatus.setGravity(Gravity.END);
                groupStatus.setPadding(photo ? dp(10) : 0,dp(4),photo ? dp(10) : 0,0); groupStatus.setMaxWidth(Math.max(dp(120),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()-dp(80))); row.addView(groupStatus);
            }
            LinearLayout holder = vertical(); holder.setClipChildren(false);
            holder.addView(row, new LinearLayout.LayoutParams(-2, -2));
            if (!interactive) hangReactionChip(holder, reactionChip(peer, entry), entry.outgoing());
            return new Bubble(holder, body, sender, status, groupStatus, tiles, collage);
    }

    // A photo of an album: touching it opens the album at that photo.
    private void albumTile(PhotoTile tile, java.util.List<ChatEngine.Entry> members, int index) {
        tile.setOnClickListener(view -> viewAlbum(members, index));
        androidx.core.view.ViewCompat.replaceAccessibilityAction(tile, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "Open photo", null);
    }

    // A photo of the chat: a tile that is as big as the photo says it is (a message from an earlier app does not say, and its tile is
    // worked out again when it has been decoded). Touching it opens the photo, as the text row did.
    private PhotoTile photoTile(ChatEngine.Entry entry, boolean interactive, int width) {
        ChatEngine.PhotoInfo info = entry.photo();
        boolean known = info != null && info.width() > 0 && info.height() > 0;
        PhotoTile tile = new PhotoTile(this, design, entry, 6);
        tile.size(width, AlbumLayout.singleHeight(width, known ? info.width() : 0, known ? info.height() : 0));
        tile.adaptive(!known);
        if (interactive) {
            tile.setOnClickListener(view -> viewContent(entry));
            androidx.core.view.ViewCompat.replaceAccessibilityAction(tile, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "Open photo", null);
        }
        return tile;
    }

    // The picture of a message: the one in memory when there is one, else decoded off the screen's thread, newest first because the
    // caller asks in that order, and shown in every tile that was waiting for it. Nothing is written anywhere, and a message that cannot
    // be read leaves its tile as it is.
    private void bindThumbnail(ChatEngine.Entry entry, PhotoTile tile) {
        // Nothing is decoded once photos are switched off, whatever the screen was built with.
        if (!photosOn()) return;
        Bitmap cached = thumbnails.get(entry.id());
        if (cached != null) { tile.show(cached); return; }
        java.util.List<PhotoTile> waiting = thumbnailWaiting.get(entry.id());
        if (waiting != null) { waiting.add(tile); return; }
        ChatEngine current = engine;
        if (current == null) return;
        waiting = new ArrayList<>(); waiting.add(tile);
        thumbnailWaiting.put(entry.id(), waiting);
        int generation = conversationGeneration;
        previewReader.execute(() -> {
            if (!conversationCurrent(current, generation)) return;
            Bitmap decoded = null;
            try {
                if (photosOn()) {
                    byte[] bytes = current.content(entry, false, false).image();
                    try { if (bytes != null) decoded = SafeImages.thumbnail(bytes, PhotoThumbnails.EDGE); }
                    finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
                }
            } catch (Exception failure) { decoded = null; }
            Bitmap result = decoded;
            imagesReady.post(() -> {
                if (!conversationCurrent(current, generation)) { if (result != null) result.recycle(); return; }
                java.util.List<PhotoTile> tiles = thumbnailWaiting.remove(entry.id());
                if (result == null) return;
                // Not kept for a message that has gone (deleted, expired, cleared) while it was being decoded, nor once photos are off.
                if (tiles == null || !photosOn()
                        || entry.expiresAt() <= System.currentTimeMillis() || !photoDrawn(entry.id())) { result.recycle(); return; }
                thumbnails.put(entry.id(), result);
                for (PhotoTile each : tiles) {
                    each.show(result);
                    if (each.adaptive()) adaptTile(each, result);
                }
            });
        });
    }

    // Whether a row of the chat draws this message's picture; a decode that finishes after its message has gone is not kept.
    private boolean photoDrawn(UUID id) {
        for (MessageRow row : messageRows.values()) if (row.photoIds.contains(id)) return true;
        return false;
    }

    // A photo whose size was not known gets the shape it really has, and the list stays where it was while that happens.
    private void adaptTile(PhotoTile tile, Bitmap bitmap) {
        int width = tile.getLayoutParams().width, height = AlbumLayout.singleHeight(width, bitmap.getWidth(), bitmap.getHeight());
        if (tile.getLayoutParams().height == height) return;
        ScrollView scroll = messageScroll;
        boolean atEnd = scroll != null && scroll.getChildCount() > 0 && scroll.getChildAt(0).getHeight() - scroll.getScrollY() - scroll.getHeight() < dp(100);
        tile.size(width, height);
        if (atEnd) scroll.post(() -> { if (messageScroll == scroll && scroll.getChildCount() > 0) scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); });
    }

    private MessageRow messageRow(ChatEngine.Peer peer, Albums.Row item, String text, String senderName, ChatEnvelope.Reference reference) {
            // Replying to an album replies to its first photo.
            ChatEngine.Entry entry = item.head();
            Bubble bubble = messageBubble(peer, item, text, senderName, reference, true);
            boolean oneToOne = entry.groupEpoch() == null;
            SwipeRow alignment = new SwipeRow(this, bubble.holder, oneToOne ? () -> replyTo(entry, text) : null);
            alignment.setGravity(entry.outgoing() ? Gravity.END : Gravity.START); alignment.setTag(item.summary().expiresAt());
            alignment.addView(bubble.holder, new LinearLayout.LayoutParams(-2, -2));
            MessageRow result = new MessageRow(item, alignment, bubble);
            // The row takes the press, wherever on it the finger is. In an album, the photo under the finger is the one the actions are
            // for. The reactions on a message open the same actions.
            alignment.setOnLongClickListener(view -> {
                ChatEngine.Entry target = entry;
                if (bubble.album != null) {
                    int under = bubble.album.indexAt(alignment.pressX(), alignment.pressY());
                    if (under >= 0 && under < result.item.members().size()) target = result.item.members().get(under);
                }
                messageActions(peer, result, target.id());
                return true;
            });
            if (oneToOne) androidx.core.view.ViewCompat.addAccessibilityAction(alignment, "Reply", (view, arguments) -> { replyTo(entry, text); return true; });
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2); placement.setMargins(0, 0, 0, dp(14)); alignment.setLayoutParams(placement);
            result.reference = reference;
            result.tiles.addAll(bubble.tiles);
            result.album = bubble.album;
            for (PhotoTile tile : bubble.tiles) if (tile.entry() != null) result.photoIds.add(tile.entry().id());
            updateReceipt(result, item.summary());
            return result;
    }

    // The photo of an album that a reaction is on, so that touching the reaction opens the actions for that photo.
    private static ChatEngine.Entry reacted(Albums.Row item) {
        for (ChatEngine.Entry member : item.members()) if (member.reactionMine() != null || member.reactionTheirs() != null) return member;
        return item.head();
    }

    private Albums.Row displayedReactions(Albums.Row item) {
        if (reactionChoices.isEmpty()) return item;
        List<ChatEngine.Entry> members = null;
        ChatEngine.Entry first = item.first();
        for (int index = 0; index < item.members().size(); index++) {
            ChatEngine.Entry entry = item.members().get(index);
            ReactionChoice choice = reactionChoices.get(entry.id());
            if (choice == null || choice.deadline() <= System.currentTimeMillis() || Objects.equals(choice.emoji(), entry.reactionMine())) continue;
            if (members == null) members = new ArrayList<>(item.members());
            ChatEngine.Entry reacted = entry.withReaction(true, choice.emoji());
            members.set(index, reacted);
            if (entry.id().equals(first.id())) first = reacted;
        }
        return members == null ? item : new Albums.Row(first, members);
    }

    private void hangReactionChip(LinearLayout holder, View chip, boolean outgoing) {
        if (chip == null) return;
        LinearLayout.LayoutParams hanging = new LinearLayout.LayoutParams(-2, -2);
        hanging.gravity = outgoing ? Gravity.START : Gravity.END;
        hanging.setMargins(dp(10), -dp(9), dp(10), 0);
        holder.addView(chip, hanging);
    }

    private boolean updateReactionChip(ChatEngine.Peer peer, MessageRow row) {
        ChatEngine.Entry entry = displayedReactions(row.item).summary();
        String mine = entry.reactionMine(), theirs = entry.reactionTheirs();
        if (Objects.equals(mine, row.shownMine) && Objects.equals(theirs, row.shownTheirs)
                && (theirs == null || peer.name().equals(row.reactionPeerName))) return false;
        row.shownMine = mine; row.shownTheirs = theirs; row.reactionPeerName = peer.name();
        if (row.chip != null) row.holder.removeView(row.chip);
        row.chip = reactionChip(peer, entry);
        if (row.chip != null) row.chip.setOnClickListener(view -> messageActions(peer, row, null));
        hangReactionChip(row.holder, row.chip, entry.outgoing());
        return true;
    }

    private void refreshReaction(ChatEngine.Peer peer, UUID message) {
        if (!peer.userId().equals(selectedPeer) || messageScroll == null) return;
        MessageRow row = rowOf(message);
        if (row == null) return;
        boolean atEnd = !messageScroll.canScrollVertically(1);
        if (updateReactionChip(peer, row) && atEnd) followMessageAfterLayout();
    }

    private void reactToMessage(ChatEngine.Peer peer, ChatEngine.Entry entry, String emoji) {
        ChatEngine current = engine;
        if (!resumed || current == null || !peer.userId().equals(selectedPeer) || entry.expiresAt() <= System.currentTimeMillis()) return;
        ReactionChoice previous = reactionChoices.get(entry.id());
        if (previous != null && previous.saving()) { notice("Still saving your reaction. Try again in a moment."); return; }
        ReactionChoice choice = new ReactionChoice(emoji, entry.expiresAt(), true);
        reactionChoices.put(entry.id(), choice);
        refreshReaction(peer, entry.id());
        int generation = screenGeneration;
        act(() -> {
            if (!resumed || engine != current || generation != screenGeneration) return;
            try {
                current.react(entry, emoji, stored -> ui.post(() -> {
                    if (!resumed || engine != current || generation != screenGeneration || reactionChoices.get(entry.id()) != choice) return;
                    reactionChoices.put(entry.id(), new ReactionChoice(stored.reactionMine(), stored.expiresAt(), false));
                }));
            } catch (Exception failure) {
                ui.post(() -> {
                    if (!resumed || engine != current || generation != screenGeneration) return;
                    if (reactionChoices.get(entry.id()) == choice) {
                        if (previous == null) reactionChoices.remove(entry.id());
                        else reactionChoices.put(entry.id(), previous);
                        refreshReaction(peer, entry.id());
                        refreshMessages(peer);
                    }
                    showFailure(failure);
                });
            }
        });
    }

    // A quote is pulled out to the edge of the bubble it is in, as in a chat app, with a little room under it. It fills the bubble, and
    // like everything else in the bubble it counts toward how wide the bubble is.
    private LinearLayout.LayoutParams quoteParams(boolean first) {
        LinearLayout.LayoutParams quoted = new LinearLayout.LayoutParams(-1, -2);
        quoted.setMargins(-dp(10), first ? -dp(8) : 0, -dp(10), dp(6));
        return quoted;
    }

    // In the bubble of a photo, which has hardly any padding, the quote simply fills the width above the picture.
    private LinearLayout.LayoutParams quoteParams(boolean first, boolean tight) {
        if (!tight) return quoteParams(first);
        LinearLayout.LayoutParams quoted = new LinearLayout.LayoutParams(-1, -2);
        quoted.setMargins(0, 0, 0, dp(4));
        return quoted;
    }

    // The reactions to a message, as a small pill (one emoji for each reaction, and how many when there are two).
    private View reactionChip(ChatEngine.Peer peer, ChatEngine.Entry entry) {
        String mine = entry.reactionMine(), theirs = entry.reactionTheirs();
        if (mine == null && theirs == null) return null;
        int total = (mine == null ? 0 : 1) + (theirs == null ? 0 : 1);
        String symbols = mine == null ? theirs : theirs == null || theirs.equals(mine) ? mine : mine + theirs;
        LinearLayout chip = horizontal();
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        chip.setBackground(design.background(Ui.SURFACE, 13, Ui.LINE));
        chip.addView(design.text(symbols, 14, 500, INK));
        if (total > 1) {
            TextView count = design.text(Integer.toString(total), 11, 700, MUTED);
            count.setPadding(dp(4), 0, 0, 0);
            chip.addView(count);
        }
        chip.setContentDescription("Reactions: " + (mine == null ? "" : "you " + mine) + (mine != null && theirs != null ? ", " : "")
                + (theirs == null ? "" : peer.name() + " " + theirs));
        return chip;
    }

    private void replyTo(ChatEngine.Entry entry, String text) {
        pendingReplyTo = entry;
        pendingReplySnippet = entry.image() ? QuoteBlock.PHOTO : entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE ? QuoteBlock.VIEW_ONCE : ChatEnvelope.Reference.excerpt(text);
        updateReplyStrip();
        if (composer != null) composer.requestFocus();
    }

    // Touching the quote in a reply goes to the message it quotes and lights it up for a moment. A message older than the ones that
    // are loaded is looked for a page at a time; one that has expired, been deleted or been cleared is said to be gone.
    private void jumpToQuoted(ChatEngine.Peer peer, UUID quotedId) {
        MessageRow row = rowOf(quotedId);
        if (row != null && row.alignment.getParent() == messages) { quoteJump = null; showQuoted(row); return; }
        ChatEngine current = engine; int generation = conversationGeneration;
        if (current == null) return;
        onReader(() -> {
            ChatEngine.Entry original = current.entry(quotedId);
            // Only a message of this conversation: the id came from the other side and must not lead anywhere else.
            boolean here = original != null && original.groupEpoch() == null && original.peerId().equals(peer.userId())
                && original.expiresAt() > System.currentTimeMillis();
            ui.post(() -> {
                if (!conversationCurrent(current, generation)) return;
                if (!here) { quoteJump = null; notice(QUOTE_GONE); return; }
                quoteJump = quotedId;
                continueQuoteJump(peer);
            });
        });
    }

    private static final String QUOTE_GONE = "That message is no longer in this chat.";

    // Run after every load of the conversation: the message is shown if it is there now, otherwise the next older page is asked for.
    private void continueQuoteJump(ChatEngine.Peer peer) {
        UUID wanted = quoteJump;
        if (wanted == null) return;
        MessageRow row = rowOf(wanted);
        if (row != null && row.alignment.getParent() == messages) { quoteJump = null; showQuoted(row); }
        else if (historyMore) loadOlderMessages(peer);
        else if (!messageRefreshQueued) { quoteJump = null; notice(QUOTE_GONE); }
    }

    // Scrolls so that the message is in the middle of the screen (or at its top when it is taller than the screen); a message that
    // is on the screen already stays where it is.
    private void showQuoted(MessageRow row) {
        ScrollView scroll = messageScroll;
        if (scroll == null) return;
        View view = row.alignment;
        // After the layout of the page that has just been added, and after the scroll that keeps the reader's place.
        scroll.post(() -> {
            if (messageScroll != scroll || view.getParent() != messages) return;
            int top = view.getTop(), height = view.getHeight(), shown = scroll.getHeight(), at = scroll.getScrollY();
            if (top < at || top + height > at + shown) {
                int target = height >= shown - dp(48) ? top - dp(24) : top - (shown - height) / 2;
                scroll.smoothScrollTo(0, Math.max(0, Math.min(target, messages.getHeight() - shown)));
            }
            flashRow(view);
        });
    }

    // A tint of the app's colour over the whole row of the message (the bubble too, as in other messengers) that comes up quickly,
    // stays a moment and fades. It follows the clock frame by frame instead of using an animator, because a phone that has its
    // animations switched off would end an animator at once, and the message would never be seen to light up.
    private static final long QUOTE_FLASH_MS = 2_000;
    private void flashRow(View row) {
        stopQuoteFlash();
        android.graphics.drawable.ColorDrawable band = new android.graphics.drawable.ColorDrawable(Ui.PRIMARY);
        band.setAlpha(0);
        row.setForeground(band);
        quoteFlashRow = row;
        long start = android.os.SystemClock.uptimeMillis();
        Runnable step = new Runnable() {
            @Override public void run() {
                if (quoteFlashRow != row) return;
                float time = Math.min(1f, (android.os.SystemClock.uptimeMillis() - start) / (float) QUOTE_FLASH_MS);
                float level = time < 0.08f ? time / 0.08f : time < 0.65f ? 1f : 1f - (time - 0.65f) / 0.35f;
                band.setAlpha(Math.round(44 * level));
                if (time >= 1f) stopQuoteFlash(); else row.postOnAnimation(this);
            }
        };
        quoteFlashStep = step;
        row.postOnAnimation(step);
    }

    private void stopQuoteFlash() {
        View row = quoteFlashRow;
        quoteFlashRow = null;
        if (row != null) { row.removeCallbacks(quoteFlashStep); row.setForeground(null); }
        quoteFlashStep = null;
    }

    // A word on the screen that is not an error. The latest is replaced, not queued behind, and the same one is not repeated at once.
    private void notice(String text) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean repeated = text.equals(lastNotice) && now - lastNoticeAt < 1_500;
        lastNotice = text; lastNoticeAt = now;
        if (repeated) return;
        if (noticeToast != null) noticeToast.cancel();
        noticeToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        noticeToast.show();
    }

    // Text past the limit is not accepted, and the phone says so instead of dropping it without a word.
    private android.text.InputFilter limitWithNotice(int limit) {
        android.text.InputFilter.LengthFilter length = new android.text.InputFilter.LengthFilter(limit);
        return (source, start, end, dest, dstart, dend) -> {
            CharSequence kept = length.filter(source, start, end, dest, dstart, dend);
            if (kept != null) limitNotice(limit);
            return kept;
        };
    }

    private void limitNotice(int limit) {
        notice("A message can have up to " + String.format(Locale.getDefault(), "%,d", limit) + " characters.");
    }

    // The long press on a message: the chat dims, the message stays where it was with the reactions floating above it, and below it
    // comes what can be done with it. What is offered depends on the message and on whose it is.
    private void messageActions(ChatEngine.Peer peer, MessageRow row, UUID target) {
        View original = row.holder;
        if (!resumed || engine == null || busy || !original.isAttachedToWindow() || original.getWidth() == 0 || original.getHeight() == 0) return;
        if (messageOverlay != null && messageOverlay.isShowing()) return;
        Albums.Row item = displayedReactions(row.item);
        ChatEngine.Entry entry = target == null ? reacted(item) : item.members().stream().filter(member -> member.id().equals(target)).findFirst().orElse(item.head());
        if (entry.expiresAt() <= System.currentTimeMillis()) return;
        String text = row.body.getText().toString(), senderName = row.sender == null ? null : row.sender.getText().toString();
        ChatEnvelope.Reference reference = row.reference;
        boolean oneToOne = entry.groupEpoch() == null;
        boolean viewOnce = entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE;
        boolean textual = !entry.image() && !viewOnce;
        java.util.List<MessageActions.Item> items = new ArrayList<>();
        if (oneToOne) items.add(new MessageActions.Item(R.drawable.ic_reply, "Reply", () -> replyTo(entry, text)));
        if (!viewOnce) items.add(new MessageActions.Item(R.drawable.ic_forward, "Forward", () -> forwardDialog(entry, text)));
        if (textual) items.add(new MessageActions.Item(R.drawable.ic_copy, "Copy", () -> copyMessage(text)));
        if (oneToOne && entry.outgoing() && textual) items.add(new MessageActions.Item(R.drawable.ic_square_pen, "Edit", () -> editDialog(peer, entry, text)));
        items.add(new MessageActions.Item(R.drawable.ic_info, "Info", () -> infoDialog(peer, entry)));
        items.add(new MessageActions.Item(R.drawable.ic_trash_2, "Delete", true, false, () -> deleteDialog(peer, entry)));
        if (!entry.outgoing()) items.add(new MessageActions.Item(R.drawable.ic_ellipsis, "More\u2026", false, true, () -> moreDialog(entry)));
        int[] at = new int[2];
        original.getLocationOnScreen(at);
        Rect bounds = new Rect(at[0], at[1], at[0] + original.getWidth(), at[1] + original.getHeight());
        // The copy is the whole message, an album as the album; what is offered is for the photo that was pressed.
        Bubble copy = messageBubble(peer, item, text, senderName, reference, false);
        for (PhotoTile tile : copy.tiles) if (tile.entry() != null) bindThumbnail(tile.entry(), tile);
        updateReceipt(new MessageRow(item, copy.holder, copy), item.summary());
        MessageActions overlay = new MessageActions(this, design, copy.holder, bounds, entry.outgoing(),
                oneToOne ? ChatEnvelope.REACTION_CHOICES : null, entry.reactionMine(),
                emoji -> reactToMessage(peer, entry, emoji), items);
        original.setAlpha(0f);
        overlay.setOnDismissListener(dialog -> { original.setAlpha(1f); if (messageOverlay == overlay) messageOverlay = null; });
        messageOverlay = overlay;
        overlay.show();
        ui.postDelayed(() -> { if (overlay.isShowing()) overlay.dismiss(); }, Math.max(1, entry.expiresAt() - System.currentTimeMillis()));
    }

    // The text leaves the encrypted store for the clipboard, which other apps and keyboards can read; where Android knows how, it is
    // marked sensitive so that it is hidden from clipboard previews. Nothing here clears it later.
    private void copyMessage(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (text == null || clipboard == null) return;
        ClipData clip = ClipData.newPlainText("Message", text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle sensitive = new PersistableBundle();
            sensitive.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(sensitive);
        }
        clipboard.setPrimaryClip(clip);
        // From Android 13 the system confirms a copy itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void infoDialog(ChatEngine.Peer peer, ChatEngine.Entry entry) {
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT).withLocale(Locale.getDefault());
        java.util.function.LongFunction<String> when = millis -> format.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()));
        boolean viewOnce = entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE;
        LinearLayout panel = vertical(); panel.setPadding(dp(2), dp(2), dp(2), dp(8));
        infoLine(panel, entry.outgoing() ? "Sent" : "Received", when.apply(entry.expiresAt() - entry.expiry().milliseconds));
        if (entry.outgoing()) infoLine(panel, "Status", switch (entry.state()) {
            case "READ" -> "Read"; case "DELIVERED" -> "Delivered"; case "QUEUED" -> "Sent"; default -> "Sending";
        });
        infoLine(panel, "Disappears", viewOnce ? "When it is opened, or at the latest " + when.apply(entry.expiresAt()) : when.apply(entry.expiresAt()));
        if (entry.edited()) infoLine(panel, "Edited", "Yes");
        if (entry.reactionMine() != null) infoLine(panel, "Your reaction", entry.reactionMine());
        if (entry.reactionTheirs() != null) infoLine(panel, peer.name() + "'s reaction", entry.reactionTheirs());
        showDialog(new SecureSheet.Builder(this).setTitle("Message info").setView(panel).setPositiveButton("Close", null).create());
    }

    private void infoLine(LinearLayout panel, String label, String value) {
        panel.addView(design.text(label, 12, 600, MUTED));
        TextView shown = design.text(value, 15, 500, INK);
        shown.setPadding(0, dp(3), 0, dp(14));
        panel.addView(shown);
    }

    // Delete for everyone is offered only for a message of mine that is still alive in a one-to-one chat; for me it always is.
    private void deleteDialog(ChatEngine.Peer peer, ChatEngine.Entry entry) {
        boolean everyone = entry.groupEpoch() == null && entry.outgoing() && entry.expiresAt() > System.currentTimeMillis();
        SecureSheet[] sheet = new SecureSheet[1];
        LinearLayout actions = vertical();
        if (everyone) {
            actions.addView(profileAction("Delete for everyone", R.drawable.ic_trash_2, () -> {
                sheet[0].dismiss(); submit(() -> engine.deleteForEveryone(entry), () -> refreshMessages(peer));
            }, Ui.ERROR));
            actions.addView(design.divider());
        }
        actions.addView(profileAction("Delete for me", R.drawable.ic_trash_2, () -> {
            sheet[0].dismiss(); submit(() -> engine.erase(entry, "delete"), () -> refreshMessages(peer));
        }, Ui.ERROR));
        sheet[0] = new SecureSheet.Builder(this).setTitle("Delete message?")
                .setMessage(everyone ? "Delete for everyone removes it from both phones. Delete for me removes it from this phone only."
                        : "This removes it from this phone only.")
                .setView(actions).setNegativeButton("Cancel", null).create();
        showDialog(sheet[0]);
    }

    private void moreDialog(ChatEngine.Entry entry) {
        SecureSheet[] sheet = new SecureSheet[1];
        LinearLayout actions = vertical();
        actions.addView(profileAction("Report", R.drawable.ic_circle_alert, () -> {
            sheet[0].dismiss(); reportUserDialog(entry.groupEpoch() == null ? entry.peerId() : entry.senderId(), entry);
        }));
        sheet[0] = new SecureSheet.Builder(this).setTitle("More").setView(actions).setNegativeButton("Cancel", null).create();
        showDialog(sheet[0]);
    }

    // Forwarding sends a new message, with the lifetime now chosen for new messages, to each conversation picked. A view-once message
    // is never forwarded: it can be opened once, here, and nowhere else.
    private void forwardDialog(ChatEngine.Entry entry, String text) {
        ChatEngine current = engine;
        if (!resumed || current == null || busy || entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) return;
        if (entry.image() && !(allowed(RemoteSettings.Flag.IMAGES) && allowed(RemoteSettings.Flag.PHOTOS))) return;
        int generation = screenGeneration;
        onReader(() -> {
            try {
                java.util.List<ChatEngine.Peer> peers = current.peers();
                java.util.List<GroupChat.Conversation> groups = new ArrayList<>();
                for (GroupChat.Conversation group : current.groups().conversations())
                    if (!current.groups().invited(group) && current.groups().ready(group) && group.snapshot().active().size() > 1) groups.add(group);
                ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) forwardSheet(entry, text, peers, groups); });
            } catch (Exception failure) {
                ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) showFailure(failure); });
            }
        });
    }

    private void forwardSheet(ChatEngine.Entry entry, String text, java.util.List<ChatEngine.Peer> peers, java.util.List<GroupChat.Conversation> groups) {
        if (peers.isEmpty() && groups.isEmpty()) { problem("There is nobody to forward to yet."); return; }
        CharSequence[] names = new CharSequence[peers.size() + groups.size()];
        for (int index = 0; index < peers.size(); index++) names[index] = peers.get(index).name();
        for (int index = 0; index < groups.size(); index++) names[peers.size() + index] = groups.get(index).name();
        boolean[] chosen = new boolean[names.length];
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Forward to")
                .setMultiChoiceItems(names, chosen, (dialog, which, checked) -> {
                    boolean any = false;
                    for (boolean value : chosen) any |= value;
                    ((SecureSheet) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forward", (dialog, which) -> forward(entry, text, peers, groups, chosen)).create();
        sheet.setOnShowListener(ignored -> sheet.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        showDialog(sheet);
    }

    private void forward(ChatEngine.Entry entry, String text, java.util.List<ChatEngine.Peer> peers, java.util.List<GroupChat.Conversation> groups, boolean[] chosen) {
        ChatEngine current = engine;
        ChatEnvelope.Expiry lifetime = expiry;
        java.util.List<ChatEngine.Peer> toPeers = new ArrayList<>();
        java.util.List<GroupChat.Conversation> toGroups = new ArrayList<>();
        for (int index = 0; index < peers.size(); index++) if (chosen[index]) toPeers.add(peers.get(index));
        for (int index = 0; index < groups.size(); index++) if (chosen[peers.size() + index]) toGroups.add(groups.get(index));
        if (current == null || toPeers.isEmpty() && toGroups.isEmpty()) return;
        int[] failed = {0};
        submit(() -> {
            byte[] image = null;
            try {
                if (entry.image()) image = current.content(entry, false, false).image();
                String forwarded = image == null ? text : null;
                for (ChatEngine.Peer peer : toPeers) {
                    try { current.send(peer, forwarded, image, lifetime, null, UUID.randomUUID(), System.currentTimeMillis(), () -> { }); }
                    catch (Exception failure) { failed[0] += keepGoing(failure); }
                }
                for (GroupChat.Conversation group : toGroups) {
                    try { current.groups().send(group.id(), forwarded, image, lifetime, UUID.randomUUID(), System.currentTimeMillis(), () -> { }); }
                    catch (Exception failure) { failed[0] += keepGoing(failure); }
                }
            } finally { if (image != null) Arrays.fill(image, (byte) 0); }
        }, () -> {
            int total = toPeers.size() + toGroups.size();
            if (failed[0] == 0) Toast.makeText(this, "Forwarded", Toast.LENGTH_SHORT).show();
            else problem(failed[0] == total ? "It could not be forwarded. Check the connection and the contact's identity, then try again."
                    : "Forwarded, but " + failed[0] + " of " + total + " could not be reached.");
            ChatEngine.Peer open = selectedPeer == null ? null : conversationPeer(selectedPeer);
            if (open != null) refreshMessages(open);
        });
    }

    // One conversation that cannot be reached does not stop the others, but a locked or closing store stops all of them.
    private static int keepGoing(Exception failure) throws Exception {
        if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                || failure instanceof AndroidVault.PhoneLockRequiredException || failure instanceof AccountSafety.DeletionPendingException) throw failure;
        return 1;
    }

    private void editDialog(ChatEngine.Peer peer, ChatEngine.Entry entry, String currentText) {
        Ui.Field field = design.field("Message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.input().setText(currentText); field.input().setMaxLines(6);
        field.input().setFilters(new android.text.InputFilter[]{limitWithNotice(MESSAGE_LIMIT)});
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0); fields.addView(field.view());
        SecureSheet dialog = new SecureSheet.Builder(this).setTitle("Edit message").setView(fields).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String updated = field.input().getText().toString();
            if (updated.trim().isEmpty()) { field.layout().setError("Message cannot be empty"); return; }
            dialog.dismiss();
            submit(() -> engine.editMessage(entry, updated), () -> refreshMessages(peer));
        }));
        showDialog(dialog);
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
            String handle;
            try { handle = ChatEngine.validUsername(username.input().getText().toString()); }
            catch (IllegalArgumentException failure) { username.layout().setError(ChatEngine.USERNAME_HINT); return; }
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
        if (!allowed(RemoteSettings.Flag.GROUPS)) return;
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
                if (!allowed(RemoteSettings.Flag.GROUPS)) return;
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
        List<ChatEngine.Peer> candidates=current.peers().stream().filter(peer -> current.independentlyVerified(peer.userId()) && group.snapshot().members().stream().noneMatch(member -> member.userId().equals(peer.userId()))).toList();
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
        // What the label under the chat used to say, in words: how this contact came to be trusted.
        boolean trusted = current.independentlyVerified(saved.userId()) || current.onboarding().official(saved.userId());
        TextView trust = design.text(current.independentlyVerified(saved.userId()) ? "Verified by you"
                : current.onboarding().official(saved.userId()) ? "Official admin" : "Account enrolled, not verified by you", 11, 600, trusted ? GREEN : MUTED);
        trust.setPadding(0, dp(6), 0, 0); details.addView(trust);
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

    private void clearChatDialog(ChatEngine.Peer peer) {
        ChatEngine current = engine;
        int generation = screenGeneration;
        if (current == null || busy || !resumed) return;
        showDialog(new SecureSheet.Builder(this).setTitle("Clear chat?")
                .setMessage("Remove this chat's messages and unsent items from this phone? The contact stays, and the other person's copies are unchanged.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (dialog, which) -> {
                    if (!resumed || engine != current || generation != screenGeneration || busy) return;
                    pendingSends.removeIf(pending -> {
                        if (!pending.peer.userId().equals(peer.userId())) return false;
                        pending.clear(); return true;
                    });
                    if (peer.userId().equals(composerPeer) && composer != null) composer.setText("");
                    submit(() -> current.clearChat(peer.userId()), this::render);
                }).create());
    }

    private void forgetDialog(ChatEngine.Peer peer) {
        showDialog(new SecureSheet.Builder(this).setTitle("Remove contact?")
                .setMessage("Remove " + peer.name() + "? Their messages on this phone and the saved identity will be erased.")
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
            catch (IllegalArgumentException failure) { username.layout().setError(ChatEngine.USERNAME_HINT); return; }
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
        panel.addView(profileAction("Backup & restore", R.drawable.ic_lock_keyhole, this::backupDialog));
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
        panel.addView(profileAction(BuildConfig.PLAY_STORE ? "Open Google Play" : "Check for updates", R.drawable.ic_arrow_up, () -> { dismissContent(); checkForUpdates(true); }));
        panel.addView(profileAction("Blocked accounts", R.drawable.ic_eye_off, this::blockedAccounts));
        if (photoAdmin && current.account().userId().equals(photoRoleAccount))
            panel.addView(profileAction("Safety reports", R.drawable.ic_shield_check, this::safetyReports));
        panel.addView(profileAction("Privacy policy", R.drawable.ic_file_text, () -> openPublicPage(PlayPolicy.PRIVACY_URL)));
        panel.addView(profileAction("Terms & safety", R.drawable.ic_shield_check, () -> openPublicPage(PlayPolicy.TERMS_URL)));
        panel.addView(profileAction("Support", R.drawable.ic_message_circle, this::contactSupport));
        panel.addView(profileAction("Licenses & source", R.drawable.ic_file_text, () -> { dismissContent(); legalNotices(); }));
        TextView settingsInfo = design.text(settingsStatus(), 11, 500, MUTED);
        settingsInfo.setPadding(dp(16), dp(10), 0, 0); settingsInfo.setContentDescription("Remote settings status");
        panel.addView(settingsInfo);
        panel.addView(design.spacer(8)); panel.addView(design.divider()); panel.addView(design.spacer(8));
        MaterialButton signOut = profileAction("Sign out", R.drawable.ic_log_out, this::signOutDialog);
        signOut.setTextColor(Ui.ERROR); signOut.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(signOut);
        MaterialButton delete = profileAction("Delete account", R.drawable.ic_trash_2, this::deleteAccountDialog);
        delete.setTextColor(Ui.ERROR); delete.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(delete);
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
        if (!resumed || engine == null || selectedProfilePhoto == null) return;
        Uri uri = selectedProfilePhoto; selectedProfilePhoto = null;
        UUID owner = profilePhotoOwner; profilePhotoOwner = null;
        ChatEngine current = engine;
        if (!current.authenticated() || !current.account().userId().equals(owner)) return;
        int generation = screenGeneration;
        imageWork.execute(() -> {
            if (!resumed || engine != current || generation != screenGeneration) return;
            byte[] photo = null;
            try {
                byte[] source = SafeImages.importImage(getContentResolver(), uri);
                try { photo = SafeImages.profilePhoto(source); } finally { Arrays.fill(source, (byte) 0); }
                Bitmap preview = SafeImages.displayProfilePhoto(photo);
                byte[] prepared = photo;
                imagesReady.post(() -> {
                    if (!resumed || engine != current || generation != screenGeneration) {
                        Arrays.fill(prepared, (byte) 0); preview.recycle(); return;
                    }
                    try { previewProfilePhoto(prepared, preview, owner); }
                    catch (RuntimeException failure) {
                        Arrays.fill(prepared, (byte) 0); preview.recycle(); problem("Photo unavailable.");
                    }
                });
                photo = null;
            } catch (Exception failure) {
                ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) problem("Photo unavailable."); });
            } finally { if (photo != null) Arrays.fill(photo, (byte) 0); }
        });
    }

    private void previewProfilePhoto(byte[] photo, Bitmap previewBitmap, UUID owner) {
        if (engine == null || !engine.authenticated() || !engine.account().userId().equals(owner)) {
            Arrays.fill(photo, (byte) 0); previewBitmap.recycle(); return;
        }
        ChatEngine current = engine;
        dismissContent();
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

    private MaterialButton profileAction(String name, int resource, Runnable action) { return profileAction(name, resource, action, INK); }

    // {@code tone} colours the text and the icon: red for a delete.
    private MaterialButton profileAction(String name, int resource, Runnable action, int tone) {
        MaterialButton button = design.button(name, false, action);
        button.setStrokeWidth(0); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(tone); button.setTextSize(13); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setMinHeight(dp(52));
        if (tone != INK) button.setIconTint(android.content.res.ColorStateList.valueOf(tone));
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

    private void backupDialog() {
        ChatEngine current = engine;
        if (current == null || current.account() == null || !current.authenticated() || busy) return;
        boolean enabled;
        try { enabled = current.backup().enabled(); }
        catch (RuntimeException unavailable) { problem("Backup settings are unavailable."); return; }
        dismissContent();
        LinearLayout panel = vertical(); panel.setPadding(dp(22), dp(8), dp(22), dp(12));
        if (!enabled) {
            panel.addView(design.text("Back up your verified contacts and the private nicknames you gave them, so you can restore them on a new phone. "
                + "The backup is encrypted on this phone with a recovery key that only you hold. Vanishr cannot read the backup or recover the key. "
                + "Messages, photos, private keys and sign-in details are never backed up.", 13, 500, INK));
            panel.addView(design.spacer(10));
            panel.addView(profileAction("Turn on backup", R.drawable.ic_lock_keyhole, () -> beginBackup(current)));
            panel.addView(profileAction("Restore from backup", R.drawable.ic_arrow_up, () -> restoreDialog(current)));
        } else {
            panel.addView(design.text(backupSummary(current.account().userId()), 13, 500, INK));
            panel.addView(design.spacer(10));
            panel.addView(profileAction("Back up now", R.drawable.ic_arrow_up, () -> { dismissContent(); startBackup(current, true); }));
            panel.addView(profileAction("Show recovery key", R.drawable.ic_key_round, () -> recoveryKeySheet(current, false)));
            MaterialButton off = profileAction("Turn off and delete backup", R.drawable.ic_trash_2, () -> disableBackupDialog(current));
            off.setTextColor(Ui.ERROR); off.setIconTint(android.content.res.ColorStateList.valueOf(Ui.ERROR)); panel.addView(off);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        showDialog(new SecureSheet.Builder(this).setTitle("Backup & restore").setView(scroll).setNegativeButton("Close", null).create());
    }

    private String backupSummary(UUID owner) {
        long success = BackupState.lastSuccess(this, owner), now = System.currentTimeMillis();
        boolean failed = BackupState.lastFailed(this, owner);
        if (BackupService.busy()) return "Backing up now. You can leave Vanishr; the upload finishes in the background.";
        if (success == 0) return failed ? "Backup is on, but the first upload did not finish. Vanishr will try again when you open it."
            : "Backup is on. The first upload is waiting to run.";
        String when = now - success < 60_000 ? "just now" : android.text.format.DateUtils.getRelativeTimeSpanString(
            success, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase(Locale.getDefault());
        return "Backup is on. Last uploaded " + when + "." + (failed ? " The latest attempt failed and will be retried." : "");
    }

    private void beginBackup(ChatEngine current) {
        var status = new java.util.concurrent.atomic.AtomicReference<AccountBackup.Status>();
        submit(() -> status.set(current.backup().serverStatus()), () -> {
            if (engine != current) return;
            if (!status.get().exists()) { enableBackup(current); return; }
            showDialog(new SecureSheet.Builder(this).setTitle("Replace the existing backup?")
                .setMessage("A backup already exists for this account. Turning on backup creates a new recovery key and replaces it. "
                    + "To bring those contacts to this phone, choose Restore instead.")
                .setNegativeButton("Cancel", null).setNeutralButton("Restore instead", (dialog, which) -> restoreDialog(current))
                .setPositiveButton("Replace", (dialog, which) -> enableBackup(current)).create());
        });
    }

    // The key is stored before it is shown, so leaving the app to copy it never loses it; it can be shown again later.
    private void enableBackup(ChatEngine current) {
        if (busy) return;
        byte[] key = app.vanishr.crypto.BackupCipher.newRecoveryKey();
        submit(() -> current.backup().enable(key), () -> {
            Arrays.fill(key, (byte) 0);
            if (engine != current) return;
            recoveryKeySheet(current, true);
            startBackup(current, false);
        }, failure -> { Arrays.fill(key, (byte) 0); showFailure(failure); });
    }

    private void recoveryKeySheet(ChatEngine current, boolean first) {
        String text;
        try { text = current.backup().recoveryKey(); }
        catch (RuntimeException failure) { problem("The recovery key is unavailable."); return; }
        dismissContent();
        LinearLayout content = vertical(); content.setPadding(dp(22), dp(8), dp(22), dp(12));
        content.addView(design.text((first ? "Backup is on. " : "") + "Write this key down or keep it in a password manager. You need it to restore your contacts on a new phone. "
            + "Vanishr cannot recover it for you. Keep it private: it unlocks your backup. You can show it again from Backup & restore.", 13, 500, INK));
        content.addView(design.spacer(14));
        TextView code = design.text(text, 14, 700, INK);
        code.setTypeface(Typeface.MONOSPACE); code.setContentDescription("Recovery key");
        content.addView(code);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        showDialog(new SecureSheet.Builder(this).setTitle("Your recovery key").setView(scroll).setPositiveButton("Done", null).create());
    }

    private void disableBackupDialog(ChatEngine current) {
        dismissContent();
        showDialog(new SecureSheet.Builder(this).setTitle("Turn off backup?")
            .setMessage("The encrypted backup is deleted from the Vanishr server and this phone forgets the recovery key. "
                + "Your contacts on this phone are not changed. You will not be able to restore from the deleted backup.")
            .setNegativeButton("Cancel", null).setPositiveButton("Turn off", (dialog, which) -> {
                if (BackupService.busy()) { problem("A backup is still uploading. Try again in a moment."); return; }
                submit(current.backup()::disable, this::backupDialog);
            }).create());
    }

    private void restoreDialog(ChatEngine current) {
        if (current == null || !current.authenticated() || busy) return;
        dismissContent();
        LinearLayout content = vertical(); content.setPadding(dp(22), dp(8), dp(22), dp(12));
        content.addView(design.text("Enter the recovery key you saved when backup was turned on. A contact is restored only when its current identity matches "
            + "the one you verified before; anything else must be verified again. Keep Vanishr open until this finishes.", 13, 500, INK));
        content.addView(design.spacer(12));
        Ui.Field key = design.field("Recovery key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.input().setTypeface(Typeface.MONOSPACE);
        content.addView(key.view());
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Restore from backup").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Restore", null).create();
        sheet.setOnShowListener(ignored -> sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy || engine != current) return;
            String text = key.input().getText().toString();
            if (text.isBlank()) { key.layout().setError("Enter your recovery key"); return; }
            key.input().setText(""); sheet.dismiss();
            var result = new java.util.concurrent.atomic.AtomicReference<AccountBackup.Result>();
            submit(() -> result.set(current.backup().restore(text)), () -> {
                if (engine != current) return;
                render(); restoreFinished(result.get()); queueSync(); startBackup(current, false);
            }, failure -> restoreFailed(current, failure));
        }));
        showDialog(sheet);
    }

    private void restoreFinished(AccountBackup.Result result) {
        StringBuilder text = new StringBuilder(result.restored() + (result.restored() == 1 ? " contact restored." : " contacts restored."));
        if (result.present() > 0) text.append(' ').append(result.present()).append(" already on this phone.");
        if (result.unrestored() > 0) text.append(' ').append(result.unrestored()).append(result.unrestored() == 1 ? " contact" : " contacts")
            .append(" could not be restored because the account changed, was blocked or no longer exists. Add and verify them again if you still need them.");
        text.append("\n\nBackup is on again with the same recovery key.");
        showDialog(new SecureSheet.Builder(this).setTitle("Restore finished").setMessage(text.toString()).setPositiveButton("Done", null).create());
    }

    private void restoreFailed(ChatEngine current, Exception failure) {
        if (failure instanceof AccountBackup.Rejected rejected && engine == current) {
            showDialog(new SecureSheet.Builder(this).setTitle("Backup not restored").setMessage(rejected.getMessage())
                .setNegativeButton("Close", null).setPositiveButton("Try again", (dialog, which) -> restoreDialog(current)).create());
        } else showFailure(failure);
    }

    private void backupFailed(Exception failure) {
        if (failure instanceof AccountBackup.Rejected rejected) problem(rejected.getMessage());
        else showFailure(failure);
    }

    // The foreground service may only be started while the app is visible, so every backup begins here.
    private void startBackup(ChatEngine current, boolean manual) {
        if (!resumed || engine != current || BackupService.busy() || preparingBackup) return;
        preparingBackup = true;
        int generation = screenGeneration;
        if (manual) Toast.makeText(this, "Preparing your backup...", Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            AccountBackup.Prepared prepared = null;
            Exception failure = null;
            try { prepared = current.backup().prepare(); }
            catch (Exception error) { failure = error; }
            AccountBackup.Prepared result = prepared;
            Exception error = failure;
            ui.post(() -> {
                preparingBackup = false;
                if (!resumed || engine != current || generation != screenGeneration) { if (result != null) result.close(); return; }
                if (error != null) { if (manual) backupFailed(error); return; }
                try {
                    BackupService.start(this, result);
                    if (manual) Toast.makeText(this, "Backup started. It continues in the background.", Toast.LENGTH_LONG).show();
                } catch (RuntimeException unavailable) {
                    if (current.account() != null) BackupState.failed(this, current.account().userId());
                    if (manual) problem("Backup could not start right now. Try again from Backup & restore.");
                }
            });
        });
    }

    private void maybeBackup(ChatEngine current) {
        if (!resumed || engine != current || busy || preparingBackup || BackupService.busy() || !current.authenticated()
                || current.safety().deletionPending() || !current.safety().termsAccepted(PlayPolicy.VERSION)) return;
        try { if (current.backup().due(System.currentTimeMillis())) startBackup(current, false); }
        catch (RuntimeException unavailable) { /* The vault closed or the phone locked; try again on the next open. */ }
    }

    private void openPublicPage(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException failure) { problem("No browser is available. Open " + url + " on another device."); }
    }

    private void contactSupport() {
        Intent email = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", PlayPolicy.SUPPORT, null))
                .putExtra(Intent.EXTRA_SUBJECT, "Vanishr support");
        try { startActivity(email); }
        catch (ActivityNotFoundException failure) { problem("Email " + PlayPolicy.SUPPORT + ". Never include passwords, sign-in tokens or private keys."); }
    }

    private LinearLayout policyDetails() {
        LinearLayout content = vertical();
        TextView summary = design.text(PlayPolicy.SUMMARY, 13, 500, INK);
        content.addView(summary);
        content.addView(profileAction("Privacy policy", R.drawable.ic_file_text, () -> openPublicPage(PlayPolicy.PRIVACY_URL)));
        content.addView(profileAction("Terms & safety", R.drawable.ic_shield_check, () -> openPublicPage(PlayPolicy.TERMS_URL)));
        return content;
    }

    private void ensurePolicies(Runnable action) {
        ChatEngine current = engine;
        if (current == null || busy) return;
        if (loginPolicyApproved || current.account() != null && current.safety().termsAccepted(PlayPolicy.VERSION)) {
            action.run(); return;
        }
        LinearLayout content = policyDetails(); content.setPadding(dp(22), dp(8), dp(22), dp(12));
        MaterialCheckBox agree = new MaterialCheckBox(this);
        agree.setText("I agree to the Terms and have read the Privacy policy.");
        content.addView(agree);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Before using Vanishr").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create();
        sheet.setOnShowListener(ignored -> {
            sheet.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            agree.setOnCheckedChangeListener((button, checked) -> sheet.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(checked));
            sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!resumed || engine != current || !agree.isChecked() || busy) return;
                loginPolicyApproved = true;
                sheet.dismiss(); action.run();
            });
        });
        showDialog(sheet);
    }

    private void policyScreen() {
        chatScreen = false;
        engine.presence().foreground(false);
        title("vanishr", false);
        LinearLayout content = policyDetails();
        MaterialCheckBox agree = new MaterialCheckBox(this);
        agree.setText("I agree to the Terms and have read the Privacy policy.");
        content.addView(agree);
        ChatEngine current = engine;
        MaterialButton proceed = design.button("Agree and continue", true, () -> {
            if (!agree.isChecked() || engine != current) return;
            submit(() -> current.safety().acceptTerms(PlayPolicy.VERSION), () -> { render(); queueSync(); registerPush(); });
        });
        proceed.setEnabled(false);
        agree.setOnCheckedChangeListener((button, checked) -> proceed.setEnabled(checked));
        content.addView(proceed);
        content.addView(profileAction("Request account deletion", R.drawable.ic_trash_2, this::deleteAccountDialog));
        content.addView(profileAction("Sign out", R.drawable.ic_log_out, this::signOutDialog));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void blockUserDialog(ChatEngine.Peer peer) {
        ChatEngine current = engine;
        if (current == null || !current.authenticated()) return;
        showDialog(new SecureSheet.Builder(this).setTitle("Block " + peer.name() + "?")
            .setMessage("This stops direct contact and ends photo access. Local direct-chat content and the saved identity will be removed. Unblocking does not restore them. Shared group history on other devices cannot be erased.")
            .setNegativeButton("Cancel", null).setPositiveButton("Block", (dialog, which) -> {
                if (engine != current) return;
                submit(() -> current.safety().block(peer), () -> { selectedPeer = null; render(); queueSync(); });
            }).create());
    }

    private void reportUserDialog(UUID targetId, ChatEngine.Entry entry) {
        ChatEngine current = engine;
        if (current == null || !current.authenticated() || targetId == null) { problem("This report is unavailable."); return; }
        String[] labels = {"Spam", "Harassment", "Sexual content", "Child safety", "Impersonation", "Threats", "Other"};
        showDialog(new SecureSheet.Builder(this).setTitle(entry == null ? "Report user" : "Report message")
            .setSingleChoiceItems(labels, -1, (dialog, selected) -> {
                AccountSafety.Reason reason = AccountSafety.Reason.values()[selected];
                showDialog(new SecureSheet.Builder(this).setTitle("Send safety report?")
                    .setMessage("The moderator receives your account ID, the reported account, the selected reason and any message/group IDs. No chat text or photos are uploaded. Reports are retained for up to 30 days for safety review.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Send report", (confirmation, which) ->
                        submit(() -> current.safety().report(targetId, reason, entry), () ->
                            problem("Report submitted for review. You can also block this user or contact " + PlayPolicy.SUPPORT + "."))).create());
            }).setNegativeButton("Cancel", null).create());
    }

    private void blockedAccounts() {
        ChatEngine current = engine;
        if (current == null || !current.authenticated()) return;
        dismissContent();
        submit(current.safety()::refresh, () -> {
            java.util.List<UUID> blocked = current.safety().blockedUsers();
            if (blocked.isEmpty()) { problem("No blocked accounts."); return; }
            String[] names = blocked.stream().map(id -> "Account " + id.toString().substring(0, 8)).toArray(String[]::new);
            showDialog(new SecureSheet.Builder(this).setTitle("Blocked accounts")
                .setSingleChoiceItems(names, -1, (dialog, which) -> showDialog(new SecureSheet.Builder(this).setTitle("Unblock account?")
                    .setMessage("This permits future contact but does not restore messages or verify the account. Add and verify it again if you want to chat.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Unblock", (choice, button) ->
                        submit(() -> current.safety().unblock(blocked.get(which)), () -> { render(); blockedAccounts(); })).create()))
                .setNegativeButton("Close", null).create());
        });
    }

    private void safetyReports() {
        ChatEngine current = engine;
        if (current == null || !current.authenticated()) return;
        dismissContent();
        var reports = new java.util.concurrent.atomic.AtomicReference<AccountSafety.Report[]>();
        submit(() -> reports.set(current.safety().reports()), () -> {
            AccountSafety.Report[] values = reports.get();
            if (values.length == 0) { problem("No pending safety reports."); return; }
            String[] labels = Arrays.stream(values).map(report -> report.reason().name().replace('_', ' ')
                + " - " + report.targetId().toString().substring(0, 8)).toArray(String[]::new);
            showDialog(new SecureSheet.Builder(this).setTitle("Safety reports")
                .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                    AccountSafety.Report report = values[which];
                    showDialog(new SecureSheet.Builder(this).setTitle("Safety report")
                        .setMessage("Reason: " + report.reason().name().replace('_', ' ') + "\nReported account: " + report.targetId()
                            + "\nReporter: " + report.reporterId() + "\nReport: " + report.id()
                            + "\n\nNo plaintext evidence is collected. Contact the reporter privately if needed, and follow the published safety policy. Mark reviewed only after appropriate action.")
                        .setNegativeButton("Close", null).setPositiveButton("Mark reviewed", (choice, button) ->
                            submit(() -> current.safety().resolveReport(report.id()), this::safetyReports)).create());
                }).setNegativeButton("Close", null).create());
        });
    }

    private void deleteAccountDialog() {
        ChatEngine current = engine;
        if (current == null || current.account() == null || busy) return;
        UUID deleting = current.account().userId();
        dismissContent();
        LinearLayout content = vertical(); content.setPadding(dp(22), dp(8), dp(22), dp(12));
        content.addView(design.text("Permanently delete this account, its server identity and this phone's encrypted account data. "
            + "Groups you own will close. Other people's delivered copies cannot be erased. Safety reports you submitted or that target you are removed. "
            + "This cannot be undone. You must sign in again to confirm ownership.", 13, 500, INK));
        if (current.onboarding().admin()) content.addView(design.text(
            "This is the sole admin account. Deleting it also disables administration and automatic admin chats. The permanent reservation will not transfer to anyone else.",
            13, 700, Ui.ERROR));
        Ui.Field confirmation = design.field("Type DELETE to confirm", InputType.TYPE_CLASS_TEXT);
        content.addView(confirmation.view());
        Ui.Field password = design.field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        boolean google = current.usesGoogle();
        if (!google) content.addView(password.view());
        content.addView(profileAction("Deletion policy and help", R.drawable.ic_file_text, () -> openPublicPage(PlayPolicy.DELETION_URL)));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Delete account").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton(google ? "Confirm with Google" : "Delete permanently", null).create();
        sheet.setOnShowListener(ignored -> sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy || engine != current || current.account() == null || !deleting.equals(current.account().userId())) return;
            if (!"DELETE".contentEquals(confirmation.input().getText())) { confirmation.layout().setError("Type DELETE exactly"); return; }
            String secret = password.input().getText().toString();
            if (!google && (secret.length() < ChatEngine.MIN_PASSWORD || secret.length() > 64)) { password.layout().setError("Enter your account password"); return; }
            password.input().setText(""); sheet.dismiss();
            if (google) beginGoogleSignIn(current.account().origin(), false, deleting);
            else submit(() -> current.safety().deleteWithPassword(secret), this::deletedAccount, this::deletionFailed);
        }));
        showDialog(sheet);
    }

    private void deletionFailed(Exception failure) {
        if (failure instanceof AccountSafety.DeletionPendingException pending) {
            render(); problem(pending.getMessage()); return;
        }
        if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                || failure instanceof AndroidVault.PhoneLockRequiredException) { showFailure(failure); return; }
        if (failure instanceof AccountSafety.DeletionAuthorizationException authorization) {
            deletionRetry(authorization.userMessage()); return;
        }
        if (failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status == 401) {
            deletionRetry("Deletion was not authorized. Check your password or choose the Google account used to create this Vanishr account."); return;
        }
        if (failure instanceof RelayApi.ApiFailure apiFailure) { deletionRetry(apiFailure.userMessage()); return; }
        if (failure instanceof java.io.IOException) {
            deletionRetry("Cannot connect right now. Check your connection, then try confirming deletion again."); return;
        }
        deletionRetry("Account deletion could not be completed safely. Your encrypted account data has not been cleared. Try again or contact " + PlayPolicy.SUPPORT + ".");
    }

    private void deletionRetry(String message) {
        ChatEngine current = engine;
        if (!resumed || current == null || current.account() == null) { problem(message); return; }
        if (current.safety().deletionPending()) { render(); problem(message); return; }
        UUID owner = current.account().userId();
        showDialog(new SecureSheet.Builder(this).setTitle("Account not deleted")
                .setMessage(message + "\n\nKeep the app installed. Retrying requires a new confirmation; it does not clear your chats.")
                .setNegativeButton("Close", null).setPositiveButton("Try again", (dialog, which) -> {
                    if (engine == current && current.account() != null && owner.equals(current.account().userId()))
                        deleteAccountDialog();
                }).create());
    }

    private void pendingDeletionScreen() {
        chatScreen = false;
        pendingSends.forEach(PendingSend::clear); pendingSends.clear();
        selectedPeer = null; notificationOpen = null;
        clearProfileAvatars(); dismissContent();
        root.removeAllViews();
        screenPadding(20);
        title("vanishr", false);
        ChatEngine current = engine;
        LinearLayout content = vertical();
        content.addView(design.text("Account deletion is pending", 22, 700, INK));
        content.addView(design.spacer(18));
        content.addView(design.text("The request may have reached the server. Chats and sign-in stay disabled until its result is confirmed. "
            + "Keep the app installed and your phone unlocked while retrying. No other saved account is deleted.", 13, 500, MUTED));
        content.addView(design.spacer(18));
        content.addView(design.button("Retry deletion cleanup", true, () ->
            submit(current.safety()::retryPendingDeletion, this::deletedAccount, this::deletionFailed)));
        content.addView(profileAction("Deletion policy and help", R.drawable.ic_file_text, () -> openPublicPage(PlayPolicy.DELETION_URL)));
        content.addView(profileAction("Contact support", R.drawable.ic_message_circle, this::contactSupport));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void deletedAccount() {
        accountDeletedNotice = true;
        screenGeneration++;
        loginPolicyApproved = false;
        selectedPeer = null; signingUp = false; notificationOpen = null; photoChoice = null;
        pushRegistrationRunning = false; pushRegisteredAccount = null;
        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("push-active", false).putBoolean("google-reset-pending", true).apply();
        getSystemService(NotificationManager.class).cancelAll();
        if (VanishrApplication.pushConfigured()) {
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            FirebaseMessaging.getInstance().deleteToken();
        }
        GoogleSignIn.clear(this, cleared -> {
            if (cleared) getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("google-reset-pending", false).apply();
        });
        hideContent(); load();
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
                    selectedPeer = null; signingUp = false; deviceReplacementRequired = false;
                    loginPolicyApproved = false;
                    selectedImages = new ArrayList<>();
                    if (cameraImage != null) cameraImage.recycle();
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
        if (!resumed || engine == null || !engine.authenticated()
                || !engine.safety().termsAccepted(PlayPolicy.VERSION) || !VanishrApplication.pushConfigured()) return;
        if (PushService.claimPermissionPrompt(this, explicit)) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        updateNotificationStatus();
    }

    private void registerPush() {
        if (!resumed || engine == null || !engine.authenticated() || !VanishrApplication.pushConfigured()
                || !engine.safety().termsAccepted(PlayPolicy.VERSION)
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
        pickImages.launch(new androidx.activity.result.PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    // The system picker keeps to the number asked for; the older way of choosing files may not, and what is left out is said so.
    private void imagesChosen(java.util.List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        selectedImages = new ArrayList<>(uris.subList(0, Math.min(uris.size(), Albums.MAX_SENT)));
        imagesLeftOut = uris.size() - selectedImages.size();
        if (engine == null) load(); else importPendingImage();
    }

    private void camera() {
        try { startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), CAMERA); }
        catch (ActivityNotFoundException failure) { problem("No camera application is available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == GALLERY && data.getData() != null) selectedImages = new ArrayList<>(java.util.List.of(data.getData()));
        if (request == CAMERA && data.getExtras() != null) {
            Bitmap bitmap = (Bitmap) data.getExtras().get("data");
            if (bitmap != null) {
                if (cameraImage != null && cameraImage != bitmap) cameraImage.recycle();
                cameraImage = bitmap;
            }
        }
        if (engine == null) load(); else importPendingImage();
    }

    // The photos that were chosen (or the one that was taken) are made safe to send one at a time. One that cannot be used does not stop
    // the others; one photo alone that cannot is reported as it always was. Several are previewed together, one is previewed whole.
    private void importPendingImage() {
        ChatEngine current = engine;
        if (!resumed || current == null) return;
        java.util.List<Uri> uris = selectedImages; selectedImages = new ArrayList<>();
        int leftOut = imagesLeftOut; imagesLeftOut = 0;
        Bitmap captured = cameraImage; cameraImage = null;
        ChatEngine.Peer peer = shownConversationPeer;
        if (peer == null) { if (captured != null) captured.recycle(); return; }
        if (captured == null && uris.isEmpty()) return;
        int generation = conversationGeneration;
        imageWork.execute(() -> {
            java.util.List<byte[]> images = new ArrayList<>();
            java.util.List<Bitmap> strip = new ArrayList<>();
            int unusable = 0;
            boolean posted = false;
            try {
                if (!conversationCurrent(current, generation)) return;
                if (captured != null) images.add(SafeImages.encode(captured));
                else for (Uri uri : uris) {
                    if (!conversationCurrent(current, generation)) return;
                    try { images.add(SafeImages.importImage(getContentResolver(), uri)); }
                    catch (Exception failure) { if (uris.size() == 1) throw failure; unusable++; }
                }
                int edge = images.size() == 1 ? PhotoThumbnails.EDGE : 320;
                for (byte[] image : images) {
                    if (!conversationCurrent(current, generation)) return;
                    strip.add(SafeImages.thumbnail(image, edge));
                }
                int skipped = unusable;
                posted = imagesReady.post(() -> {
                    if (!conversationCurrent(current, generation) || images.isEmpty()) {
                        discardPreview(images, strip);
                        if (conversationCurrent(current, generation)) problem("Image unavailable.");
                        return;
                    }
                    try {
                        if (images.size() == 1) previewImage(peer, images.get(0), strip.get(0));
                        else previewImages(peer, images, strip);
                    } catch (RuntimeException failure) {
                        discardPreview(images, strip); problem("Image unavailable."); return;
                    }
                    String said = skipped == 0 ? "" : skipped == 1 ? "One photo could not be used." : skipped + " photos could not be used.";
                    if (leftOut > 0) said = (said.isEmpty() ? "" : said + " ") + "Only " + Albums.MAX_SENT + " photos go at once.";
                    if (!said.isEmpty()) notice(said);
                });
            } catch (Exception failure) {
                ui.post(() -> { if (conversationCurrent(current, generation)) problem("Image unavailable."); });
            } finally {
                if (captured != null) captured.recycle();
                if (!posted) discardPreview(images, strip);
            }
        });
    }

    private static void discardPreview(List<byte[]> images, List<Bitmap> previews) {
        for (byte[] image : images) Arrays.fill(image, (byte) 0);
        for (Bitmap preview : previews) preview.recycle();
    }

    private void previewImage(ChatEngine.Peer peer, byte[] image, Bitmap preview) {
        ImageView view = new ImageView(this); view.setImageBitmap(preview); view.setAdjustViewBounds(true); view.setMaxHeight(dp(360)); view.setContentDescription("Image preview");
        ChatEnvelope.Expiry selectedExpiry = expiry;
        boolean[] transferred = {false};
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Send photo").setView(view).setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> {
                    if (transferred[0] || !peer.userId().equals(selectedPeer)) return;
                    boolean keep = selectedExpiry != ChatEnvelope.Expiry.VIEW_ONCE && photosOn();
                    ChatEnvelope.Reference reference = pendingReplyTo == null ? null : new ChatEnvelope.Reference(pendingReplyTo.id(), pendingReplySnippet, pendingReplyTo.outgoing());
                    if (enqueueSend(peer, null, image, selectedExpiry, reference, keep ? preview : null)) {
                        transferred[0] = keep;
                        if (keep) view.setImageDrawable(null);
                        pendingReplyTo = null; pendingReplySnippet = null; updateReplyStrip();
                    }
                }).create();
        sheet.setOnDismissListener(dialog -> {
            view.setImageDrawable(null); Arrays.fill(image, (byte) 0);
            if (!transferred[0]) preview.recycle();
        });
        showDialog(sheet);
    }

    // Several photos at once: what is going to be sent, in a row that can be scrolled, and what the lifetime chosen means for them.
    private void previewImages(ChatEngine.Peer peer, java.util.List<byte[]> images, java.util.List<Bitmap> strip) {
        ChatEnvelope.Expiry selectedExpiry = expiry;
        LinearLayout panel = vertical();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pictures = horizontal();
        java.util.List<ImageView> shown = new ArrayList<>();
        for (int index = 0; index < strip.size(); index++) {
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setImageBitmap(strip.get(index));
            picture.setContentDescription("Photo " + (index + 1) + " of " + strip.size());
            picture.setBackground(design.background(Ui.TINT, 6, 0));
            picture.setClipToOutline(true);
            picture.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(104), dp(104)); at.setMarginEnd(dp(8));
            pictures.addView(picture, at); shown.add(picture);
        }
        scroll.addView(pictures);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(design.spacer(12));
        panel.addView(design.text(selectedExpiry == ChatEnvelope.Expiry.VIEW_ONCE ? "Each photo is sent on its own and can be opened once."
                : "They arrive together, as one album.", 13, 500, MUTED));
        boolean[] transferred = {false};
        SecureSheet sheet = new SecureSheet.Builder(this).setTitle("Send " + images.size() + " photos").setView(panel).setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> {
                    if (transferred[0] || !peer.userId().equals(selectedPeer)) return;
                    boolean keep = selectedExpiry != ChatEnvelope.Expiry.VIEW_ONCE && photosOn();
                    ChatEnvelope.Reference reference = pendingReplyTo == null ? null : new ChatEnvelope.Reference(pendingReplyTo.id(), pendingReplySnippet, pendingReplyTo.outgoing());
                    if (enqueueAlbum(peer, images, selectedExpiry, reference, keep ? strip : List.of())) {
                        transferred[0] = keep;
                        if (keep) for (ImageView picture : shown) picture.setImageDrawable(null);
                        pendingReplyTo = null; pendingReplySnippet = null; updateReplyStrip();
                    }
                }).create();
        sheet.setOnDismissListener(dialog -> {
            for (ImageView picture : shown) picture.setImageDrawable(null);
            discardPreview(images, transferred[0] ? List.of() : strip);
        });
        showDialog(sheet);
    }

    private void viewContent(ChatEngine.Entry entry) {
        if (entry.image() && !allowed(RemoteSettings.Flag.PHOTOS)) return;
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

    // An album opens at the photo that was touched, and its photos are paged through from there.
    private void viewAlbum(java.util.List<ChatEngine.Entry> members, int index) {
        if (!allowed(RemoteSettings.Flag.PHOTOS) || index < 0 || index >= members.size()) return;
        openContent(members.get(index), members, index);
    }

    private void openContent(ChatEngine.Entry entry) { openContent(entry, null, 0); }

    private void openContent(ChatEngine.Entry entry, java.util.List<ChatEngine.Entry> album, int index) {
        // At the point of action, before a view-once message is consumed: the confirmation may have been up while the settings changed.
        if (entry.image() && !allowed(RemoteSettings.Flag.PHOTOS)) return;
        ChatEngine loaded = engine;
        if (!resumed || loaded == null || !entry.peerId().equals(selectedPeer)) return;
        int generation = conversationGeneration;
        submit(() -> {
            if (!conversationCurrent(loaded, generation) || (entry.image() && !photosOn())) return;
            // Decided before the message is consumed, and off the main thread because it reads the identity key from the vault.
            boolean admin = entry.image() && loaded.onboarding().verifiedAdmin();
            ChatEngine.Content content = loaded.content(entry, true);
            byte[] image = content.image();
            if (image != null) {
                Bitmap bitmap = null;
                try { bitmap = SafeImages.display(image); }
                catch (java.io.IOException | RuntimeException failure) {
                    ui.post(() -> { if (conversationCurrent(loaded, generation)) problem("Content unavailable."); });
                    return;
                }
                finally { if (!admin || bitmap == null) Arrays.fill(image, (byte) 0); }
                Bitmap ready = bitmap;
                byte[] savable = admin ? image : null;
                imagesReady.post(() -> {
                    if (!conversationCurrent(loaded, generation) || !photosOn() || System.currentTimeMillis() >= entry.expiresAt()) {
                        ready.recycle(); if (savable != null) Arrays.fill(savable, (byte) 0); return;
                    }
                    try { showPhoto(entry, ready, savable, album, index); queueSync(); }
                    catch (RuntimeException failure) { problem("Content unavailable."); }
                });
                return;
            }
            ui.post(() -> {
                if (!conversationCurrent(loaded, generation) || System.currentTimeMillis() >= entry.expiresAt()) return;
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
                    View view = design.text(content.envelope().text(), 18, 500, INK);
                    panel.addView(view, new LinearLayout.LayoutParams(-1, -2));
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(panel);
                    openDialog = new SecureSheet.Builder(this).setView(scroll).setPositiveButton("Done", null).create();
                    openDialog.setOnDismissListener(dialog -> { if (view instanceof TextView text) text.setText(""); clearBitmap(); });
                    showDialog(openDialog);
                    SecureSheet current = openDialog;
                    ui.postDelayed(() -> { if (current.isShowing()) current.dismiss(); }, Math.max(1, entry.expiresAt() - System.currentTimeMillis()));
                    queueSync();
                } catch (Exception failure) { problem("Content unavailable."); }
            });
        }, () -> { if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE && conversationCurrent(loaded, generation)) refreshMessages(shownConversationPeer); });
    }

    // The full-size picture is prepared off the UI thread; only the verified admin retains the JPEG for explicit save.
    private void showPhoto(ChatEngine.Entry entry, Bitmap bitmap, byte[] savable, java.util.List<ChatEngine.Entry> album, int index) {
        closePhoto();
        PhotoViewer viewer = null;
        try {
            viewer = new PhotoViewer(this, bitmap, savable, this::confirmPhotoSave);
            PhotoViewer shown = viewer;
            photoViewer = shown;
            shown.setOnDismissListener(dialog -> { if (photoViewer == shown) photoViewer = null; });
            long deadline = entry.expiresAt();
            if (album != null && album.size() > 1) {
                shown.pages(album.size(), index, page -> albumPhoto(shown, album, page));
                for (ChatEngine.Entry member : album) deadline = Math.min(deadline, member.expiresAt());
            }
            shown.show();
            ui.postDelayed(() -> { if (shown.isShowing()) shown.dismiss(); }, Math.max(1, deadline - System.currentTimeMillis()));
        } catch (RuntimeException failure) {
            // The window could not be shown (for example the activity is finishing): nothing may be left decoded or kept.
            photoViewer = null;
            if (viewer != null) viewer.release();
            else bitmap.recycle();
            if (savable != null) Arrays.fill(savable, (byte) 0);
            throw failure;
        }
    }

    // The next photo of an album: decrypted and decoded off the screen's thread, then shown in the viewer that is open. Only the photo
    // that is shown is held (its bytes too only for the official admin account, as for any photo), and a photo that has expired is
    // never shown.
    private void albumPhoto(PhotoViewer viewer, java.util.List<ChatEngine.Entry> album, int page) {
        ChatEngine current = engine;
        int generation = conversationGeneration;
        ChatEngine.Entry entry = album.get(page);
        if (current == null) { viewer.failed(); return; }
        // Paging is a person's action, so a switch that came while the album was open is said, and no further photo is decoded.
        if (!allowed(RemoteSettings.Flag.PHOTOS)) { viewer.failed(); return; }
        act(() -> {
            if (!conversationCurrent(current, generation)) return;
            Bitmap bitmap = null; byte[] bytes = null; boolean admin = false;
            try {
                admin = current.onboarding().verifiedAdmin();
                bytes = current.content(entry, true).image();
                if (bytes != null) bitmap = SafeImages.display(bytes);
            } catch (Exception failure) {
                if (bitmap != null) bitmap.recycle();
                bitmap = null;
            }
            byte[] kept = admin && bitmap != null ? bytes : null;
            if (kept == null && bytes != null) Arrays.fill(bytes, (byte) 0);
            Bitmap decoded = bitmap;
            imagesReady.post(() -> {
                boolean usable = decoded != null && conversationCurrent(current, generation) && photosOn() && photoViewer == viewer
                        && viewer.isShowing() && System.currentTimeMillis() < entry.expiresAt();
                if (!usable) {
                    if (decoded != null) decoded.recycle();
                    if (kept != null) Arrays.fill(kept, (byte) 0);
                    if (photoViewer == viewer) { viewer.failed(); if (decoded == null) problem("Photo unavailable."); }
                    return;
                }
                viewer.show(page, decoded, kept);
            });
        });
    }

    private void closePhoto() {
        PhotoViewer viewer = photoViewer; photoViewer = null;
        if (viewer != null) { viewer.dismiss(); viewer.release(); }
    }

    private void confirmPhotoSave(PhotoViewer viewer) {
        if (!resumed || engine == null || photoViewer != viewer || !viewer.canSave() || !engine.onboarding().admin()) return;
        if (Build.VERSION.SDK_INT < 29) { problem("Saving photos needs Android 10 or later."); return; }
        showDialog(new SecureSheet.Builder(this).setTitle("Save photo")
                .setMessage("Save this photo to your gallery? The saved copy is not encrypted and stays after the message expires.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> savePhoto(viewer)).create());
    }

    @androidx.annotation.RequiresApi(29)
    private void saveToGallery(byte[] copy) {
        saveWork.execute(() -> {
            boolean saved;
            try { SafeImages.saveToGallery(this, copy); saved = true; }
            catch (Exception failure) { saved = false; }
            finally { Arrays.fill(copy, (byte) 0); }
            boolean result = saved;
            ui.post(() -> { if (!isDestroyed()) problem(result ? "Saved to your gallery." : "The photo could not be saved."); });
        });
    }

    private void savePhoto(PhotoViewer viewer) {
        if (Build.VERSION.SDK_INT < 29) { problem("Saving photos needs Android 10 or later."); return; }
        byte[] copy = viewer.copyForSave();
        if (copy == null) { problem("This photo is no longer available."); return; }
        saveToGallery(copy);
    }

    private void queueSync() {
        syncRequested.set(true);
        if (!syncQueued.compareAndSet(false,true)) return;
        try {
            work.execute(() -> {
                syncRequested.set(false);
                try { syncOnce(); }
                finally {
                    syncQueued.set(false);
                    if (syncRequested.get() && resumed && engine != null) queueSync();
                }
            });
        } catch (RejectedExecutionException failure) {
            syncQueued.set(false);
            if (!work.isShutdown()) throw failure;
        }
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
        act(() -> {
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

    // A message that has just been stored is shown in the open chat at once; the rest of the sync pass carries on behind it.
    private void showReceived(ChatEngine current, int generation, UUID peer) {
        if (peer == null || !peer.equals(selectedPeer) || !receivedRefreshPosted.compareAndSet(false, true)) return;
        ui.post(() -> {
            receivedRefreshPosted.set(false);
            ChatEngine.Peer open = shownConversationPeer;
            if (resumed && engine == current && generation == screenGeneration && !busy && open != null && peer.equals(open.userId()))
                refreshMessages(open);
        });
    }

    private void syncOnce() {
        int generation = screenGeneration;
        ChatEngine current = engine;
        if (!resumed || current == null || !current.safety().termsAccepted(PlayPolicy.VERSION)) return;
        boolean completed = true;
        try { completed = current.sync(() -> waitingActions.get() > 0, peer -> showReceived(current, generation, peer)); }
        catch (GeneralSecurityException | AndroidVault.PhoneLockedException failure) {
            ui.post(() -> { if (engine == current && generation == screenGeneration && !busy) { hideContent(); storageFailure(failure); } }); return;
        }
        catch (AdminOnboarding.Failure failure) {
            current.onboarding().blocked(failure); current.unverifiedIncoming = true;
            if (failure instanceof AdminOnboarding.Mismatch) requestAdminIdentity(current);
        }
        catch (Exception failure) { current.online = false; }
        // A user action is waiting: it runs first and the sync resumes right behind it from the stored state.
        if (!completed) { syncRequested.set(true); return; }
        int conversation = conversationGeneration;
        UUID selected = selectedPeer;
        ChatListContents contents;
        Map<UUID, Long> unreadCounts = new HashMap<>();
        GroupChat.Conversation selectedGroup;
        ChatEngine.Peer selectedContact;
        String groupStatus;
        boolean groupCanSend;
        try {
            contents = current.authenticated() ? ChatListContents.read(current)
                    : new ChatListContents(java.util.List.of(), java.util.List.of(), Set.of(), 0);
            if (current.authenticated()) unreadCounts.putAll(current.unreadCounts());
            selectedGroup = contents.groups().stream().filter(group -> group.id().equals(selected) && !current.groups().invited(group)).findFirst().orElse(null);
            selectedContact = selectedGroup == null ? contents.peers().stream().filter(peer -> peer.userId().equals(selected)).findFirst().orElse(null)
                    : new ChatEngine.Peer(selectedGroup.id(), selectedGroup.snapshot().epoch(), "", selectedGroup.name());
            groupStatus = selectedGroup == null ? "" : current.groups().status(selectedGroup);
            groupCanSend = selectedGroup != null && current.groups().ready(selectedGroup) && selectedGroup.snapshot().active().size() > 1;
        } catch (Exception failure) {
            ui.post(() -> { if (resumed && engine == current && generation == screenGeneration) showFailure(failure); });
            return;
        }
        ui.post(() -> {
            if (!resumed || engine != current || generation != screenGeneration || busy) return;
            if (notificationOpen != null) { render(); return; }
            homeStatus();
            if (!current.authenticated()) { if (chatScreen || contactRows != null) { dismissContent(); render(); } }
            else if (conversation != conversationGeneration) return;
            else if (selectedPeer == null) {
                if (connection != null) connection.setText(statusText());
                updateContacts(contents, unreadCounts);
            }
            else {
                if (!Objects.equals(selected, selectedPeer)) return;
                if (selectedContact == null) {
                    UUID removed=selectedPeer;
                    pendingSends.removeIf(pending -> {
                        if (!pending.peer.userId().equals(removed)) return false;
                        pending.clear(); return true;
                    });
                    dismissContent(); selectedPeer=null; render(); return;
                }
                if (connection != null) connection.setText(statusText());
                shownConversationPeer = selectedContact; shownConversationGroup = selectedGroup;
                if (conversationName != null && !selectedContact.name().contentEquals(conversationName.getText()))
                    conversationName.setText(selectedContact.name());
                if (selectedGroup != null) {
                    if (conversationUsername != null && !groupStatus.contentEquals(conversationUsername.getText()))
                        conversationUsername.setText(groupStatus);
                    groupComposerState(groupCanSend);
                }
                // The shown status stays until the re-check below replaces or removes it, so the header does not blink on every pass.
                checkedPresence = null; refreshContactStatus();
                refreshMessages(selectedContact);
            }
        });
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }

    // A user action goes ahead of the next background step; a sync in progress yields to it at its next safe point.
    private void act(Runnable task) {
        waitingActions.incrementAndGet();
        try { work.execute(() -> { waitingActions.decrementAndGet(); task.run(); }); }
        catch (RuntimeException rejected) { waitingActions.decrementAndGet(); throw rejected; }
    }

    // Read-only preparation for the screen; it neither waits for nor delays the work lane.
    private void onReader(Runnable task) {
        try { reader.execute(task); }
        catch (RejectedExecutionException closed) { if (!reader.isShutdown()) throw closed; }
    }

    private static ExecutorService backgroundLane(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, name));
    }

    private void submit(Work action, Runnable completed) {
        submit(action, completed, this::showFailure);
    }

    private void submit(Work action, Runnable completed, java.util.function.Consumer<Exception> failed) {
        if (busy) return;
        busy = true;
        int generation = screenGeneration;
        if (connection != null) connection.setText(R.string.working);
        homeStatus();
        act(() -> {
            try { action.run(); ui.post(() -> {
                if (!resumed || generation != screenGeneration) return;
                busy = false; completed.run();
            }); }
            catch (Exception failure) {
                ui.post(() -> {
                    if (!resumed || generation != screenGeneration) return;
                    busy = false;
                    failed.accept(failure);
                });
            }
        });
    }

    private void showFailure(Exception failure) {
        if (failure instanceof AccountSafety.DeletionPendingException) { deletionFailed(failure); }
        else if (failure instanceof GeneralSecurityException || failure instanceof AndroidVault.PhoneLockedException
                || failure instanceof AndroidVault.PhoneLockRequiredException) { hideContent(); storageFailure(failure); }
        else if (failure instanceof ChatEngine.CapacityReached)
            problem("This phone is holding the most unread messages it can. Open your chats to read them, then try again.");
        else if (failure instanceof SecurityException) {
            if (failure instanceof AdminOnboarding.Mismatch && engine != null) requestAdminIdentity(engine);
            problem("Identity verification or secure storage check failed.");
        }
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
        lastProblem = text;
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
    private void dismissContent() {
        closePhoto();
        if (messageOverlay != null) { MessageActions overlay = messageOverlay; messageOverlay = null; overlay.dismiss(); }
        if (openDialog != null) { openDialog.setDismissWithAnimation(false); openDialog.dismiss(); openDialog = null; }
        clearBitmap();
    }

    private void hideContent() {
        photoAdmin = false; photoRoleAccount = null;
        if (!photoPermissionPending) photoChoice = null;
        screenGeneration++;
        resetConversation();
        pushRegistrationRunning = false; pushRegistrationFailed = false; pushRegisteredAccount = null; notificationStatus = null;
        completingGoogle = false;
        busy = false;
        reactionChoices.clear();
        clearHome();
        clearProfileAvatars();
        dismissContent();
        for (PendingSend pending : pendingSends) pending.clear();
        pendingSends.clear();
        if (composer != null) composer.setText("");
        composer = null; messages = null; emoticons = null;
        emojiPanel = null; emojiControl = null; emojiShown = false; emojiPending = false; emojiTab = -1; recentEmoji.clear();
        quoteJump = null; stopQuoteFlash();
        composerPeer=null;
        sendControl=null; attachmentControl=null;
        homeQuery=""; unreadOnly=false;
        conversationName = null; conversationUsername = null;
        contactStatus = null;
        messageScroll = null; shownEntries = java.util.List.of(); actionError = null;
        connection = null;
        ChatEngine closing = engine; engine = null;
        if (closing != null) { closing.cancelPendingRequests(); work.execute(closing::close); }
        storageError = null; phoneSecurityRequired = false; waitingForPhoneUnlock = false;
        storageState();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (engine == null) load();
        else { render(); showGoogleDeletionFailure(); if (googleAttempt != null && googleAttempt.idToken != null) completeGoogle(); }
        checkForUpdates(false);
        checkRemoteSettings();
        continuePhotoChoice();
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
    @Override protected void onDestroy() { clearProfileAvatars(); selectedProfilePhoto = null; profilePhotoOwner = null; dismissContent(); if (googleCancellation != null) googleCancellation.cancel(); if (googleAttempt != null) googleAttempt.idToken = null; googleAttempt = null; ui.removeCallbacksAndMessages(null); unregisterReceiver(pushRefresh); unregisterReceiver(phoneUnlocked); if (cameraImage != null) cameraImage.recycle(); work.shutdown(); reader.shutdownNow(); previewReader.shutdownNow(); imageWork.shutdown(); saveWork.shutdown(); photoWork.shutdownNow(); updateWork.shutdownNow(); super.onDestroy(); }
}