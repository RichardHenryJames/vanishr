package app.vanishr.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The button for a new chat: an icon with its label until the list is scrolled down, then the icon alone, and back with the label when the
 * list is scrolled up or is at its top. The label is only decoration; the button's description is always the same.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class ExtendedFab extends LinearLayout {
    private final TextView label;
    private final int labelWidth, collapsedPadding, startPadding, endPadding;
    private boolean extended = true;
    private float fraction = 1f;
    private ValueAnimator running;

    ExtendedFab(Context context, Ui design, int drawable, String text) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setSaveEnabled(false);
        setClickable(true);
        setFocusable(true);
        setBackground(design.feedback(Ui.PRIMARY, 18));
        setElevation(design.dp(6));
        setFilterTouchesWhenObscured(true);
        collapsedPadding = design.dp(16); startPadding = design.dp(18); endPadding = design.dp(22);
        ImageView icon = design.symbol(drawable, 24, Color.WHITE);
        addView(icon, new LayoutParams(design.dp(24), design.dp(24)));
        label = design.text(text, 15, 700, Color.WHITE);
        label.setSingleLine(true);
        label.setPadding(design.dp(10), 0, 0, 0);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        labelWidth = label.getMeasuredWidth();
        addView(label, new LayoutParams(labelWidth, LayoutParams.WRAP_CONTENT));
        apply(1f);
    }

    boolean extended() { return extended; }

    /** Shows the label ({@code true}) or the icon alone. Moves smoothly when the phone has animations on and {@code animate} is set. */
    void setExtended(boolean extend, boolean animate) {
        if (extended == extend) return;
        extended = extend;
        if (running != null) { running.cancel(); running = null; }
        float to = extend ? 1f : 0f;
        if (!animate || !ListMotion.enabled() || !isAttachedToWindow()) { apply(to); return; }
        running = ValueAnimator.ofFloat(fraction, to).setDuration(ListMotion.FOLD_MILLIS);
        running.addUpdateListener(animation -> apply((float) animation.getAnimatedValue()));
        running.start();
    }

    private void apply(float value) {
        fraction = value;
        LayoutParams params = (LayoutParams) label.getLayoutParams();
        params.width = Math.round(labelWidth * value);
        label.setLayoutParams(params);
        label.setAlpha(value);
        setPadding(Math.round(collapsedPadding + (startPadding - collapsedPadding) * value), 0,
            Math.round(collapsedPadding + (endPadding - collapsedPadding) * value), 0);
    }

    @Override protected void onDetachedFromWindow() {
        if (running != null) { running.cancel(); running = null; apply(extended ? 1f : 0f); }
        super.onDetachedFromWindow();
    }
}
