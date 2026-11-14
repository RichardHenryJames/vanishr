package app.vanishr.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * One chat in the list with the actions that sit behind it, the way a phone's chat list shows them. Pulling the row to the right
 * slides in the actions on its left, pulling it to the left slides in the ones on its right, and the row then <em>stays</em> where it
 * was let go, with nothing asked and nothing done, until one of the actions is touched. It goes back when it is pulled back or tapped
 * (a tap that closes a row does not also open the chat), when another row or the empty part of the list is touched, when the list is
 * scrolled and when Back is pressed. At most one row of a list is open.
 *
 * <p>A drag that is mostly sideways is taken from whatever the finger started on, and the chat under it neither opens nor ends as a
 * long press; a mostly vertical one is left to the list's own scrolling. Where the row comes to rest depends on how far and how fast
 * it was pulled ({@link #target}), so a short or slow pull falls back and a deliberate one stays.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class SwipeActions extends FrameLayout {
    /** What one button does. The label is also what a screen reader says for it; {@code color} fills the button, behind white. */
    record Action(int icon, String label, int color, Runnable run) { }

    static final int OPEN_LEFT = 1, CLOSED = 0, OPEN_RIGHT = -1;

    /**
     * The rows of one list. The list is drawn again from time to time (a sync, a search, a filter), and a row that is open has to
     * come back open, so what is open is remembered by key here and not only by the view.
     */
    static final class Group {
        private SwipeActions open, touching;
        private Object openKey;
        private int openSide = CLOSED;
        private boolean claimed;

        /** Called around the drawing of all the rows: an open row whose chat is no longer listed is forgotten. */
        void begin() { claimed = false; }
        void end() { if (!claimed) { openKey = null; openSide = CLOSED; } }

        /** True while a finger is on a row: the list is not drawn again under it. */
        boolean active() { return touching != null; }

        /** Puts the open row back, if there is one; true if there was one (so that Back has done its work). */
        boolean closeOpen() {
            SwipeActions row = open;
            openKey = null; openSide = CLOSED;
            if (row == null) return false;
            open = null;
            row.settle(0, true);
            return true;
        }

        private int restore(Object key) {
            if (openKey == null || !openKey.equals(key)) return CLOSED;
            claimed = true;
            return openSide;
        }

        private void opened(SwipeActions row, Object key, int side) {
            SwipeActions previous = open;
            open = row; openKey = key; openSide = side;
            if (previous != null && previous != row) previous.settle(0, true);
        }

        private void closed(SwipeActions row) {
            if (open != row) return;
            open = null; openKey = null; openSide = CLOSED;
        }

        private boolean elsewhere(SwipeActions row) { return open != null && open != row; }

        private void closeOthers(SwipeActions row) { if (elsewhere(row)) closeOpen(); }

        private void detached(SwipeActions row) {
            if (touching == row) touching = null;
            if (open == row) open = null;
        }
    }

    private enum Phase { IDLE, DRAGGING, TAP_TO_CLOSE, SWALLOWING, SCROLLING }

    private static final int BUTTON_DP = 76, OVERPULL_DP = 24;

    private final Group group;
    private final Object key;
    private final View row;
    private final LinearLayout leftPanel, rightPanel;
    private final float leftWidth, rightWidth, overpull, flingSpeed;
    private final int slop;
    private Phase phase = Phase.IDLE;
    private VelocityTracker velocity;
    private ValueAnimator settling;
    private float downX, downY, startOffset, offset;
    private boolean crossed;

    /** {@code row} is drawn over the actions and must be opaque; {@code key} says which chat this is, for {@link Group}. */
    // The sides are physical on purpose: the row is moved with translationX, so a pull to the right shows what is on the left in any language.
    @android.annotation.SuppressLint("RtlHardcoded")
    SwipeActions(Context context, Ui design, View row, List<Action> left, List<Action> right, Group group, Object key) {
        super(context);
        this.row = row; this.group = group; this.key = key;
        float density = context.getResources().getDisplayMetrics().density;
        int button = Math.round(BUTTON_DP * density);
        leftWidth = left.size() * button; rightWidth = right.size() * button;
        overpull = OVERPULL_DP * density;
        flingSpeed = 600 * density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        leftPanel = panel(design, left, button, false);
        rightPanel = panel(design, right, button, true);
        addView(leftPanel, new LayoutParams(Math.round(leftWidth + overpull), LayoutParams.MATCH_PARENT, Gravity.LEFT));
        addView(rightPanel, new LayoutParams(Math.round(rightWidth + overpull), LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setSaveEnabled(false);
        setFilterTouchesWhenObscured(true);
        int restored = group.restore(key);
        if (restored == OPEN_LEFT && leftWidth > 0) { group.opened(this, key, restored); apply(leftWidth); }
        else if (restored == OPEN_RIGHT && rightWidth > 0) { group.opened(this, key, restored); apply(-rightWidth); }
        else apply(0);
    }

    /** Where a row that is let go at {@code offset} (to the right is positive), moving at {@code speed} pixels a second, comes to rest. */
    static float target(float offset, float speed, float left, float right, float fling) {
        if (speed > fling) return offset < 0 || left <= 0 ? 0 : left;
        if (speed < -fling) return offset > 0 || right <= 0 ? 0 : -right;
        if (left > 0 && offset > left / 2) return left;
        if (right > 0 && offset < -right / 2) return -right;
        return 0;
    }

    /** Where the row is drawn for a finger that has moved it to {@code raw}: with the finger as far as the actions go, then slowly. */
    static float resisted(float raw, float left, float right, float pull) {
        if (raw > left) return left + Math.min(pull, (raw - left) * 0.3f);
        if (raw < -right) return -right - Math.min(pull, (-right - raw) * 0.3f);
        return raw;
    }

    private LinearLayout panel(Ui design, List<Action> actions, int button, boolean fillerFirst) {
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        panel.setVisibility(INVISIBLE);
        // The colour that shows where the row has been pulled past the buttons: the one next to the row. A layout, not a bare View, because
        // a View with match-parent height asks for all the height there is while the row's own height is still being worked out.
        FrameLayout filler = new FrameLayout(getContext());
        filler.setBackgroundColor(actions.get(fillerFirst ? 0 : actions.size() - 1).color());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(Math.round(overpull), LinearLayout.LayoutParams.MATCH_PARENT);
        if (fillerFirst) panel.addView(filler, gap);
        for (Action action : actions) panel.addView(button(design, action), new LinearLayout.LayoutParams(button, LinearLayout.LayoutParams.MATCH_PARENT));
        if (!fillerFirst) panel.addView(filler, gap);
        return panel;
    }

    private View button(Ui design, Action action) {
        LinearLayout button = new LinearLayout(getContext());
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(design.dp(4), 0, design.dp(4), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), new ColorDrawable(action.color()), null));
        button.addView(design.symbol(action.icon(), 20, Color.WHITE));
        TextView label = design.text(action.label(), 11, 700, Color.WHITE);
        label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, design.dp(5), 0, 0);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        button.setContentDescription(action.label());
        button.setClickable(true); button.setFocusable(true);
        button.setFilterTouchesWhenObscured(true);
        button.setSaveEnabled(false);
        // The row goes back first, so what the action shows (a question, a list drawn again) appears over a list at rest.
        button.setOnClickListener(view -> { settle(0, true); action.run().run(); });
        return button;
    }

    private void apply(float value) {
        offset = value;
        row.setTranslationX(value);
        leftPanel.setVisibility(value > 0 ? VISIBLE : INVISIBLE);
        rightPanel.setVisibility(value < 0 ? VISIBLE : INVISIBLE);
        // The buttons slide in with the row and stay put once they are all showing.
        leftPanel.setTranslationX(Math.min(0f, value - leftWidth));
        rightPanel.setTranslationX(Math.max(0f, value + rightWidth));
    }

    private void settle(float to, boolean animate) {
        if (settling != null) { settling.cancel(); settling = null; }
        if (to > 0) group.opened(this, key, OPEN_LEFT);
        else if (to < 0) group.opened(this, key, OPEN_RIGHT);
        else group.closed(this);
        if (!animate || offset == to) { apply(to); return; }
        float widest = Math.max(1f, Math.max(leftWidth, rightWidth));
        settling = ValueAnimator.ofFloat(offset, to);
        settling.setDuration(Math.round(120 + 100 * Math.min(1f, Math.abs(to - offset) / widest)));
        settling.setInterpolator(new DecelerateInterpolator());
        settling.addUpdateListener(animation -> apply((float) animation.getAnimatedValue()));
        settling.start();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                // The touch that puts another row back is not also a tap on this one.
                if (group.elsewhere(this)) { group.closeOpen(); phase = Phase.SWALLOWING; return true; }
                // A tap on a row that is open closes it and does not open the chat.
                if (offset != 0 && event.getX() >= offset && event.getX() <= getWidth() + offset) { phase = Phase.TAP_TO_CLOSE; return true; }
                return false;
            case MotionEvent.ACTION_MOVE:
                track(event);
                if (phase == Phase.IDLE) follow(event);
                return phase == Phase.DRAGGING;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (phase == Phase.IDLE) phase = Phase.SCROLLING;
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finish(event);
                return false;
            default:
                return false;
        }
    }

    // Reached for the rest of a gesture this row has taken over, and for a touch that no child took.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                track(event);
                if (phase == Phase.DRAGGING) drag(event.getRawX() - downX);
                else if (phase == Phase.IDLE || phase == Phase.TAP_TO_CLOSE) follow(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finish(event);
                return true;
            default:
                return true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (settling != null) { settling.cancel(); settling = null; }
        recycle();
        group.detached(this);
        super.onDetachedFromWindow();
    }

    private void begin(MotionEvent event) {
        recycle();
        track(event);
        if (settling != null) { settling.cancel(); settling = null; }
        downX = event.getRawX(); downY = event.getRawY();
        startOffset = offset;
        crossed = target(offset, 0, leftWidth, rightWidth, flingSpeed) != 0;
        phase = Phase.IDLE;
        group.touching = this;
    }

    private void follow(MotionEvent event) {
        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return;
        // Mostly sideways is for this row; anything else is somebody scrolling.
        if (Math.abs(dx) <= 1.5f * Math.abs(dy)) { phase = Phase.SCROLLING; return; }
        phase = Phase.DRAGGING;
        group.closeOthers(this);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        drag(dx);
    }

    private void drag(float dx) {
        float shown = resisted(startOffset + dx, leftWidth, rightWidth, overpull);
        boolean staying = target(shown, 0, leftWidth, rightWidth, flingSpeed) != 0;
        if (staying && !crossed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        crossed = staying;
        apply(shown);
    }

    private void finish(MotionEvent event) {
        Phase ended = phase;
        phase = Phase.IDLE;
        if (group.touching == this) group.touching = null;
        boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
        float speed = 0;
        if (velocity != null) {
            velocity.addMovement(event);
            velocity.computeCurrentVelocity(1000);
            speed = velocity.getXVelocity();
        }
        recycle();
        if (ended == Phase.DRAGGING) settle(target(offset, up ? speed : 0f, leftWidth, rightWidth, flingSpeed), true);
        else if (ended == Phase.TAP_TO_CLOSE && up) settle(0, true);
    }

    private void track(MotionEvent event) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
    }

    private void recycle() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
}
