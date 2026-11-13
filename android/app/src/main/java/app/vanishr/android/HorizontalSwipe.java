package app.vanishr.android;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * A horizontal drag on one view that moves it with the finger, in one direction only, and does something when it is let go past a
 * distance: the swipe on a chat in the list that asks to remove the contact. (A message in a conversation is swiped with
 * {@link SwipeRow}, which takes the drag from anywhere on its row.) A vertical
 * drag is left to the list's own scrolling, and the view's own click and long press keep working for a tap or a press that stays put.
 * A sideways drag the other way is swallowed, so that it neither opens what it started on nor ends as a long press.
 * Nothing is done while the finger is still down, and a gesture that is cancelled (the list took it) does nothing.
 */
final class HorizontalSwipe implements View.OnTouchListener {
    /** Called as the view moves, with how far it has gone as a fraction (0 to 1) of the distance that counts as done. */
    interface Progress { void changed(float fraction); }

    private final View target;
    private final int direction;
    private final float trigger, limit;
    private final Progress progress;
    private final Runnable done;
    private final int slop;
    private float startX, startY, offset;
    private boolean undecided, horizontal, wrongWay, crossed;

    /** {@code direction} 1 moves the view to the right, -1 to the left; {@code triggerPx} is the distance that counts as done. */
    HorizontalSwipe(View target, int direction, float triggerPx, float limitPx, Progress progress, Runnable done) {
        this.target = target;
        this.direction = direction >= 0 ? 1 : -1;
        this.trigger = Math.max(1f, triggerPx);
        this.limit = Math.max(limitPx, this.trigger);
        this.progress = progress;
        this.done = done;
        this.slop = ViewConfiguration.get(target.getContext()).getScaledTouchSlop();
    }

    // The view keeps its own click, which a swipe is not; screen-reader users get the same action from the accessibility action added where this is attached.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getRawX(); startY = event.getRawY();
                undecided = true; horizontal = false; wrongWay = false; crossed = false; offset = 0;
                target.animate().cancel();
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (wrongWay) return true;
                if (!undecided && !horizontal) return false;
                float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                if (undecided) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                    undecided = false;
                    // Mostly sideways is a swipe; the other way it is swallowed. Anything else is somebody scrolling.
                    boolean sideways = Math.abs(dx) > 1.5f * Math.abs(dy);
                    horizontal = sideways && direction * dx > 0;
                    wrongWay = sideways && !horizontal;
                    if (!horizontal && !wrongWay) return false;
                    if (horizontal && view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.cancelLongPress();
                    view.setPressed(false);
                    if (wrongWay) return true;
                }
                move(direction * dx);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean moved = horizontal, swallowed = wrongWay;
                boolean fire = moved && event.getActionMasked() == MotionEvent.ACTION_UP && offset >= trigger;
                undecided = false; horizontal = false; wrongWay = false;
                if (swallowed) return true;
                if (!moved) return false;
                release();
                if (fire) done.run();
                return true;
            }
            default:
                return false;
        }
    }

    // Follows the finger up to the distance that counts as done, then gives way slowly, so it is clear when letting go will act.
    private void move(float distance) {
        float travelled = distance <= 0 ? 0 : distance <= trigger ? distance : trigger + (distance - trigger) * 0.3f;
        offset = Math.min(limit, travelled);
        target.setTranslationX(direction * offset);
        progress.changed(Math.min(1f, offset / trigger));
        boolean beyond = offset >= trigger;
        if (beyond && !crossed) target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        crossed = beyond;
    }

    private void release() {
        offset = 0; crossed = false;
        target.animate().translationX(0).setDuration(170).setInterpolator(new DecelerateInterpolator())
                .setUpdateListener(animation -> progress.changed(Math.min(1f, Math.abs(target.getTranslationX()) / trigger)))
                .withEndAction(() -> progress.changed(0)).start();
    }
}
