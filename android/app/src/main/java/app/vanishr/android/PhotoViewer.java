package app.vanishr.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A photo shown edge to edge on black with one close button at the top right. When the caller passes the photo's bytes
 * (the official admin account only), a long press anywhere on the photo asks the caller to offer saving it. The bytes
 * and the bitmap are wiped when the viewer closes.
 *
 * <p>The photos of an album are paged through here: a swipe, or the arrows at the sides, asks the caller for the next one, and only
 * the photo that is shown is ever held. The count shown is "2 / 6".
 */
final class PhotoViewer extends Dialog {
    private Bitmap bitmap;
    private final ImageView image;
    private final FrameLayout root;
    private final Ui design;
    private byte[] savable;
    private boolean released;
    private TextView counter;
    private ImageButton previous, next;
    private int count = 1, index;
    private IntConsumer wanted;
    private boolean loading;
    private GestureDetector swipes;

    PhotoViewer(Context context, Bitmap bitmap, byte[] savable, Consumer<PhotoViewer> saveRequested) {
        super(context, R.style.Theme_Vanishr_PhotoViewer);
        this.bitmap = bitmap;
        this.savable = savable;
        design = new Ui(context);
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setSaveEnabled(false);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        image.setContentDescription("Received photo");
        image.setSaveEnabled(false);
        image.setFilterTouchesWhenObscured(true);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (savable != null) {
            image.setOnLongClickListener(view -> { saveRequested.accept(this); return true; });
            ViewCompat.addAccessibilityAction(image, "Save to gallery", (view, arguments) -> { saveRequested.accept(this); return true; });
        }
        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_x);
        close.setColorFilter(Color.WHITE);
        close.setBackground(design.background(0x99000000, 24, 0));
        close.setPadding(design.dp(12), design.dp(12), design.dp(12), design.dp(12));
        close.setContentDescription("Close photo");
        close.setTooltipText("Close photo");
        close.setFilterTouchesWhenObscured(true);
        close.setSaveEnabled(false);
        close.setOnClickListener(view -> dismiss());
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(design.dp(48), design.dp(48), Gravity.TOP | Gravity.END);
        placement.setMargins(design.dp(8), design.dp(8), design.dp(8), 0);
        root.addView(close, placement);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Both sides get their own inset: END is the left edge in right-to-left languages.
            placement.setMargins(design.dp(8) + bars.left, design.dp(8) + bars.top, design.dp(8) + bars.right, 0);
            close.setLayoutParams(placement);
            if (counter != null) {
                FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) counter.getLayoutParams();
                at.topMargin = design.dp(14) + bars.top;
                counter.setLayoutParams(at);
            }
            return insets;
        });
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true);
            window.setLayout(-1, -1);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
    }

    /**
     * Makes this one photo of {@code count}, the one at {@code index}. {@code wanted} is asked for the photo to move to, and answers
     * with {@link #show} (or {@link #failed}); nothing else is asked for until it has.
     */
    void pages(int count, int index, IntConsumer wanted) {
        this.count = Math.max(1, count); this.index = index; this.wanted = wanted;
        if (this.count < 2) return;
        counter = new TextView(getContext());
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(13);
        counter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        counter.setGravity(Gravity.CENTER);
        counter.setBackground(design.background(0x99000000, 16, 0));
        counter.setPadding(design.dp(14), design.dp(6), design.dp(14), design.dp(6));
        counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        at.topMargin = design.dp(14);
        root.addView(counter, at);
        previous = arrow(Gravity.START, "Previous photo", -1);
        previous.setRotation(180f);
        next = arrow(Gravity.END, "Next photo", 1);
        swipes = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
                if (first == null || last == null) return false;
                float across = last.getX() - first.getX(), down = last.getY() - first.getY();
                if (Math.abs(across) < design.dp(72) || Math.abs(across) < 1.5f * Math.abs(down) || Math.abs(velocityX) < design.dp(200)) return false;
                move(across < 0 ? 1 : -1);
                return true;
            }
        });
        update();
    }

    private ImageButton arrow(int side, String label, int step) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(R.drawable.ic_chevron_right);
        button.setColorFilter(Color.WHITE);
        button.setBackground(design.background(0x99000000, 24, 0));
        button.setPadding(design.dp(12), design.dp(12), design.dp(12), design.dp(12));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setFilterTouchesWhenObscured(true);
        button.setSaveEnabled(false);
        button.setOnClickListener(view -> move(step));
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(design.dp(48), design.dp(48), side | Gravity.CENTER_VERTICAL);
        at.setMargins(design.dp(8), 0, design.dp(8), 0);
        root.addView(button, at);
        return button;
    }

    private void move(int step) {
        int target = index + step;
        if (wanted == null || loading || target < 0 || target >= count || released) return;
        loading = true;
        wanted.accept(target);
    }

    private void update() {
        if (counter == null) return;
        counter.setText(String.format(Locale.getDefault(), "%d / %d", index + 1, count));
        counter.setContentDescription("Photo " + (index + 1) + " of " + count);
        previous.setVisibility(index > 0 ? View.VISIBLE : View.GONE);
        next.setVisibility(index < count - 1 ? View.VISIBLE : View.GONE);
    }

    /** Shows the photo at {@code page}; the one that was shown, and its bytes, are wiped. */
    void show(int page, Bitmap picture, byte[] saved) {
        if (released) { picture.recycle(); if (saved != null) Arrays.fill(saved, (byte) 0); return; }
        Bitmap old = bitmap; byte[] oldBytes = savable;
        bitmap = picture; savable = saved; index = page; loading = false;
        image.setImageBitmap(picture);
        if (old != null && !old.isRecycled()) old.recycle();
        if (oldBytes != null) Arrays.fill(oldBytes, (byte) 0);
        update();
    }

    /** The photo that was asked for could not be shown; the one on the screen stays. */
    void failed() { loading = false; }

    int index() { return index; }
    int count() { return count; }
    ImageView image() { return image; }
    boolean canSave() { return savable != null; }
    byte[] copyForSave() { return savable == null ? null : savable.clone(); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipes != null) swipes.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onStop() {
        release();
        super.onStop();
    }

    /** Idempotent; also runs when the viewer was never shown. */
    void release() {
        if (released) return;
        released = true;
        image.setImageDrawable(null);
        if (!bitmap.isRecycled()) bitmap.recycle();
        if (savable != null) { Arrays.fill(savable, (byte) 0); savable = null; }
    }
}
