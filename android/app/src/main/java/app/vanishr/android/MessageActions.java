package app.vanishr.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.function.Consumer;

/**
 * What a long press on a message opens: the chat dimmed, the message itself kept where it was, a row of reactions floating above it
 * and, below it, a card of what can be done with it. The reactions and the card are separate pieces, each lined up with the side the
 * message is on, and the whole group moves up or down as far as it must to stay on the screen.
 *
 * <p>The message shown here is a copy that the caller built; nothing in it opens or sends anything. Like every window that shows
 * message content it is hidden from screenshots, the recent-apps list and overlay windows.
 */
final class MessageActions extends Dialog {
    /** One line of the card: {@code separated} puts a wider break before it (the "more" line), {@code destructive} marks a delete. */
    record Item(int icon, String label, boolean destructive, boolean separated, Runnable action) {
        Item(int icon, String label, Runnable action) { this(icon, label, false, false, action); }
    }

    private static final int SCRIM = Color.argb(0x99, 0x0B, 0x14, 0x11);
    private static final long APPEAR = 170;

    private final Ui design;
    private final FrameLayout root;
    private final FrameLayout stage;
    private final LinearLayout bar;
    private final ScrollView card;
    private final LinearLayout list;
    private final Rect bounds;
    private final boolean outgoing;
    private boolean placing, placed;

    /**
     * @param bubble the copy of the message, which is as large as the original and is not itself in any window
     * @param bounds where the original is on the screen
     * @param reactions what can be reacted with, or null where a message cannot be reacted to (a group)
     * @param chosen the reaction already given, shown as picked and taken back when it is tapped again
     * @param react told the emoji picked, or null when the one that was given is taken back
     */
    MessageActions(Context context, Ui design, View bubble, Rect bounds, boolean outgoing, List<String> reactions, String chosen,
                   Consumer<String> react, List<Item> items) {
        super(context, R.style.Theme_Vanishr_MessageActions);
        this.design = design;
        this.bounds = new Rect(bounds);
        this.outgoing = outgoing;
        root = new FrameLayout(context);
        root.setSaveEnabled(false);
        View scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        stage = new FrameLayout(context);
        stage.setClipChildren(true);
        stage.setSaveEnabled(false);
        bubble.setClickable(false);
        stage.addView(bubble, new FrameLayout.LayoutParams(this.bounds.width(), this.bounds.height(), Gravity.TOP | Gravity.START));
        root.addView(stage, new FrameLayout.LayoutParams(this.bounds.width(), this.bounds.height(), Gravity.TOP | Gravity.START));
        if (reactions == null || reactions.isEmpty()) bar = null;
        else {
            bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(design.dp(6), design.dp(4), design.dp(6), design.dp(4));
            bar.setBackground(design.background(Ui.SURFACE, 30, 0));
            bar.setElevation(design.dp(8));
            bar.setSaveEnabled(false);
            for (String emoji : reactions) bar.addView(emoji(emoji, emoji.equals(chosen), react), new LinearLayout.LayoutParams(design.dp(44), design.dp(44)));
            root.addView(bar, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        }
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setSaveEnabled(false);
        for (int index = 0; index < items.size(); index++) addItem(context, items.get(index), index > 0);
        card = new ScrollView(context);
        card.setVerticalScrollBarEnabled(false);
        card.setOverScrollMode(View.OVER_SCROLL_NEVER);
        card.setSaveEnabled(false);
        card.setBackground(design.background(Ui.SURFACE, 14, 0));
        card.setClipToOutline(true);
        card.setElevation(design.dp(8));
        card.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(card, new FrameLayout.LayoutParams(design.dp(236), -2, Gravity.TOP | Gravity.START));
        // Hidden until it has a place, so that nothing is seen at the corner of the screen first.
        stage.setVisibility(View.INVISIBLE);
        if (bar != null) bar.setVisibility(View.INVISIBLE);
        card.setVisibility(View.INVISIBLE);
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true);
            window.setLayout(-1, -1);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // Placed once the layout pass is over, so that the sizes it sets are laid out in the next one.
            if (!placing && right - left > 0 && bottom - top > 0) { placing = true; root.post(this::place); }
        });
    }

    private TextView emoji(String value, boolean picked, Consumer<String> react) {
        TextView button = design.text(value, 26, 500, Ui.INK);
        // In dp, not sp: the button is a fixed size, and a larger system font would otherwise push the emoji out of it.
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26);
        button.setGravity(Gravity.CENTER);
        button.setBackground(picked ? design.background(Ui.TINT, 22, 0) : design.feedback(Color.TRANSPARENT, 22));
        button.setContentDescription(picked ? "Remove reaction " + value : "React " + value);
        button.setOnClickListener(view -> { dismiss(); react.accept(picked ? null : value); });
        button.setFilterTouchesWhenObscured(true);
        return button;
    }

    private void addItem(Context context, Item item, boolean divider) {
        if (divider) {
            View line = new View(context);
            line.setBackgroundColor(item.separated() ? Ui.CANVAS : Ui.LINE);
            list.addView(line, new LinearLayout.LayoutParams(-1, item.separated() ? design.dp(6) : design.dp(1)));
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(design.dp(18), 0, design.dp(16), 0);
        row.setMinimumHeight(design.dp(50));
        row.setBackground(design.feedback(Color.TRANSPARENT, 0));
        row.setFilterTouchesWhenObscured(true);
        row.setSaveEnabled(false);
        int color = item.destructive() ? Ui.ERROR : Ui.INK;
        TextView label = design.text(item.label(), 15, 500, color);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (item.icon() != 0) row.addView(design.symbol(item.icon(), 20, color));
        row.setContentDescription(item.label());
        row.setOnClickListener(view -> { dismiss(); item.action().run(); });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    // Lines the pieces up on the message once the window has a size: the reactions above it, the card below it, all kept on screen.
    private void place() {
        if (!root.isAttachedToWindow()) return;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        int width = root.getWidth(), height = root.getHeight();
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        Insets bars = insets == null ? Insets.NONE : insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        int gap = design.dp(8), margin = design.dp(12);
        int top = bars.top + gap, bottom = height - bars.bottom - gap, start = bars.left + margin, end = width - bars.right - margin;

        int barWidth = 0, barHeight = 0;
        if (bar != null) {
            bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            barWidth = bar.getMeasuredWidth(); barHeight = bar.getMeasuredHeight();
        }
        int cardWidth = ((FrameLayout.LayoutParams) card.getLayoutParams()).width;
        list.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int above = bar == null ? 0 : barHeight + gap;
        int smallest = design.dp(96);
        // The card scrolls only when even the shortest message would leave no room for it.
        int cardHeight = Math.min(list.getMeasuredHeight(), Math.max(design.dp(120), bottom - top - above - gap - smallest));
        int shown = Math.min(bounds.height(), Math.max(smallest, bottom - top - above - gap - cardHeight));
        int total = above + shown + gap + cardHeight;

        int bubbleLeft = bounds.left - origin[0], bubbleTop = bounds.top - origin[1];
        int groupTop = Math.max(top, Math.min(bubbleTop - above, bottom - total));
        int stageTop = groupTop + above;
        int bubbleRight = bubbleLeft + bounds.width();

        stage.getLayoutParams().height = shown;
        stage.setTranslationX(bubbleLeft);
        stage.setTranslationY(bubbleTop);
        card.getLayoutParams().height = cardHeight;
        card.setTranslationX(aligned(bubbleLeft, bubbleRight, cardWidth, start, end));
        card.setTranslationY(stageTop + shown + gap);
        if (bar != null) {
            bar.setTranslationX(aligned(bubbleLeft, bubbleRight, barWidth, start, end));
            bar.setTranslationY(groupTop);
        }
        root.requestLayout();
        stage.setVisibility(View.VISIBLE);
        appear(stageTop - bubbleTop, cardWidth, barWidth);
        placed = true;
    }

    // A piece of the group takes the edge of the message on the message's own side and stays inside the margins.
    private float aligned(int left, int right, int piece, int start, int end) {
        int wanted = outgoing ? right - piece : left;
        return Math.max(start, Math.min(wanted, end - piece));
    }

    private void appear(int shift, int cardWidth, int barWidth) {
        DecelerateInterpolator slow = new DecelerateInterpolator();
        if (shift != 0) stage.animate().translationYBy(shift).setDuration(APPEAR).setInterpolator(slow).start();
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f); card.setScaleX(0.86f); card.setScaleY(0.86f);
        card.setPivotX(outgoing ? cardWidth : 0); card.setPivotY(0);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(APPEAR).setInterpolator(slow).start();
        if (bar == null) return;
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(0f); bar.setScaleX(0.6f); bar.setScaleY(0.6f);
        bar.setPivotX(outgoing ? barWidth : 0); bar.setPivotY(bar.getMeasuredHeight());
        bar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(APPEAR).setInterpolator(new OvershootInterpolator(1.1f)).start();
        for (int index = 0; index < bar.getChildCount(); index++) {
            View button = bar.getChildAt(index);
            button.setScaleX(0.3f); button.setScaleY(0.3f);
            button.animate().scaleX(1f).scaleY(1f).setStartDelay(50L + 28L * index).setDuration(150).setInterpolator(new OvershootInterpolator(2f)).start();
        }
    }
}
