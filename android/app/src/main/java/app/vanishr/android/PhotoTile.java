package app.vanishr.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;
import java.util.UUID;

/**
 * One photo in a chat: a rounded tile that shows a picture cropped to fit, or a quiet placeholder with a picture symbol until it has
 * been decoded (and for good when it cannot be). A tile in an album can carry a "+N" on a dark veil. The tile never holds more than
 * the one bitmap it is given, and lets go of it when it is cleared.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class PhotoTile extends FrameLayout {
    private final ImageView picture;
    private final ImageView symbol;
    private TextView more;
    private final ChatEngine.Entry entry;
    private boolean drawn;
    private boolean adaptive;

    /** {@code entry} is the message this tile shows (null for a photo that is still being sent). */
    PhotoTile(Context context, Ui design, ChatEngine.Entry entry, int radiusDp) {
        super(context);
        this.entry = entry;
        setBackground(design.background(Ui.TINT, radiusDp, 0));
        setClipToOutline(true);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setSaveEnabled(false);
        symbol = design.symbol(R.drawable.ic_image, 28, Ui.MUTED);
        addView(symbol, new LayoutParams(design.dp(28), design.dp(28), Gravity.CENTER));
        picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setSaveEnabled(false);
        picture.setVisibility(GONE);
        addView(picture, new LayoutParams(-1, -1));
        setContentDescription("Photo");
    }

    ChatEngine.Entry entry() { return entry; }
    boolean drawn() { return drawn; }
    /** A tile whose photo did not say how big it is, which takes the shape of the picture when it has one. */
    boolean adaptive() { return adaptive; }
    void adaptive(boolean value) { adaptive = value; }
    Bitmap bitmap() { return picture.getDrawable() instanceof android.graphics.drawable.BitmapDrawable drawable ? drawable.getBitmap() : null; }

    void size(int width, int height) {
        android.view.ViewGroup.LayoutParams current = getLayoutParams();
        if (current == null) current = new android.view.ViewGroup.LayoutParams(width, height);
        current.width = width; current.height = height;
        setLayoutParams(current);
    }

    void show(Bitmap bitmap) {
        picture.setImageBitmap(bitmap);
        picture.setVisibility(VISIBLE);
        symbol.setVisibility(GONE);
        drawn = true;
    }

    /** Lets go of the bitmap, so that the cache can recycle it. */
    void clear() {
        picture.setImageDrawable(null);
        picture.setVisibility(GONE);
        symbol.setVisibility(VISIBLE);
        drawn = false;
    }

    /** "+3" on the fourth tile of an album that has more than four; 0 removes it. */
    void more(int hidden) {
        if (hidden <= 0) { if (more != null) { removeView(more); more = null; } return; }
        if (more == null) {
            more = new TextView(getContext());
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Color.WHITE);
            more.setTypeface(Typeface.DEFAULT_BOLD);
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            more.setBackgroundColor(0x99182724);
            more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(more, new LayoutParams(-1, -1));
        }
        more.setText(String.format(Locale.getDefault(), "+%d", hidden));
    }

    CharSequence moreText() { return more == null ? null : more.getText(); }
}
