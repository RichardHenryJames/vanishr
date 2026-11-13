package app.vanishr.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;

/**
 * One row of a conversation. It owns the two gestures that belong to the whole row and not to the bubble in it, so that they work
 * wherever on the row they start: a drag to the right starts a reply (an own message, which sits at the far side of the screen, is
 * as easy to swipe as one that was received), and a press that stays put asks for the message actions, even on a photo that would
 * otherwise only open itself.
 *
 * <p>A drag that is mostly sideways is taken from the bubble whichever way it goes, so that it neither opens what it started on (a
 * view-once photo) nor ends as a long press; only the drag to the right does anything. A mostly vertical drag is left to the list's
 * own scrolling. Nothing is done while the finger is still down, and a gesture that is cancelled does nothing.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class SwipeRow extends LinearLayout {
    private enum Phase { IDLE, UNDECIDED, SWIPING, SWALLOWING, PRESSED, SCROLLING }

    private final View moved;
    private final Runnable reply;
    private final float trigger, limit, reach, disc;
    private final int slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable arrow;
    private final Runnable press = this::pressed;
    private Phase phase = Phase.IDLE;
    private float startX, startY, offset;
    private boolean crossed;

    /** {@code moved} is the child that follows the finger; a null {@code reply} leaves the row with the long press alone. */
    SwipeRow(Context context, View moved, Runnable reply) {
        super(context);
        this.moved = moved;
        this.reply = reply;
        float density = context.getResources().getDisplayMetrics().density;
        trigger = 48 * density; limit = 64 * density; reach = 30 * density; disc = 34 * density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        paint.setColor(Ui.LINE);
        arrow = ContextCompat.getDrawable(context, R.drawable.ic_reply).mutate();
        arrow.setTint(Ui.MUTED);
        setOrientation(HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        setSaveEnabled(false);
    }

    // The cue sits under the bubble, in the space the bubble leaves as it moves, and grows as the drag nears the mark.
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float shift = moved.getTranslationX();
        if (shift <= 0.5f) return;
        float progress = Math.min(1f, shift / trigger);
        float radius = disc * (0.55f + 0.45f * progress) / 2f;
        float x = moved.getLeft() + shift - reach, y = moved.getTop() + moved.getHeight() / 2f;
        paint.setAlpha(Math.round(255 * progress));
        canvas.drawCircle(x, y, radius, paint);
        int half = Math.round(radius * 0.52f);
        arrow.setBounds(Math.round(x) - half, Math.round(y) - half, Math.round(x) + half, Math.round(y) + half);
        arrow.setAlpha(Math.round(255 * progress));
        arrow.draw(canvas);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                follow(event);
                return phase == Phase.SWIPING || phase == Phase.SWALLOWING || phase == Phase.PRESSED;
            case MotionEvent.ACTION_POINTER_DOWN:
                abandon();
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // A press that has already been answered must not also be a tap on whatever is under the finger.
                boolean answered = phase == Phase.PRESSED;
                finish(event);
                return answered;
            }
            default:
                return false;
        }
    }

    // Reached for a touch that no child took (the empty part of the row) and for the rest of a gesture this row has taken over.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                follow(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                abandon();
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
        removeCallbacks(press);
        moved.animate().cancel();
        super.onDetachedFromWindow();
    }

    private void begin(MotionEvent event) {
        removeCallbacks(press);
        startX = event.getRawX(); startY = event.getRawY();
        offset = 0; crossed = false;
        phase = Phase.UNDECIDED;
        moved.animate().cancel();
        postDelayed(press, ViewConfiguration.getLongPressTimeout());
    }

    private void follow(MotionEvent event) {
        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
        if (phase == Phase.SWIPING) { drag(dx); return; }
        if (phase != Phase.UNDECIDED || (Math.abs(dx) < slop && Math.abs(dy) < slop)) return;
        removeCallbacks(press);
        // Mostly sideways is for this row; anything else is somebody scrolling, or a press that moved too far to be one.
        boolean sideways = Math.abs(dx) > 1.5f * Math.abs(dy);
        if (!sideways || reply == null) { phase = Phase.SCROLLING; return; }
        phase = dx > 0 ? Phase.SWIPING : Phase.SWALLOWING;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        setPressed(false);
        if (phase == Phase.SWIPING) drag(dx);
    }

    // Follows the finger up to the mark, then gives way slowly, so it is clear when letting go will act.
    private void drag(float distance) {
        float travelled = distance <= 0 ? 0 : distance <= trigger ? distance : trigger + (distance - trigger) * 0.3f;
        offset = Math.min(limit, travelled);
        moved.setTranslationX(offset);
        boolean beyond = offset >= trigger;
        if (beyond && !crossed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        crossed = beyond;
        invalidate();
    }

    private void pressed() {
        if (phase != Phase.UNDECIDED) return;
        phase = Phase.PRESSED;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        performLongClick();
    }

    private void abandon() {
        if (phase == Phase.UNDECIDED) { removeCallbacks(press); phase = Phase.SCROLLING; }
    }

    private void finish(MotionEvent event) {
        removeCallbacks(press);
        boolean swiping = phase == Phase.SWIPING;
        boolean fire = swiping && event.getActionMasked() == MotionEvent.ACTION_UP && offset >= trigger;
        phase = Phase.IDLE;
        if (!swiping) return;
        release();
        if (fire) reply.run();
    }

    private void release() {
        offset = 0; crossed = false;
        moved.animate().translationX(0).setDuration(170).setInterpolator(new DecelerateInterpolator())
                .setUpdateListener(animation -> invalidate()).withEndAction(this::invalidate).start();
    }
}
