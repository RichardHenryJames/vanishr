package app.vanishr.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A photo shown edge to edge on black with one close button at the top right. When the caller passes the photo's bytes
 * (the official admin account only), a long press anywhere on the photo asks the caller to offer saving it. The bytes
 * and the bitmap are wiped when the viewer closes.
 */
final class PhotoViewer extends Dialog {
    private final Bitmap bitmap;
    private final ImageView image;
    private byte[] savable;
    private boolean released;

    PhotoViewer(Context context, Bitmap bitmap, byte[] savable, Consumer<PhotoViewer> saveRequested) {
        super(context, R.style.Theme_Vanishr_PhotoViewer);
        this.bitmap = bitmap;
        this.savable = savable;
        Ui design = new Ui(context);
        FrameLayout root = new FrameLayout(context);
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

    ImageView image() { return image; }
    boolean canSave() { return savable != null; }
    byte[] copyForSave() { return savable == null ? null : savable.clone(); }

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
