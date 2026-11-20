package app.vanishr.android;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The small motions of the chat list and of moving between it and a chat. They are decoration: each starts after the screen it belongs to has
 * been laid out, never before it can be drawn, so nothing is made to wait for them, and none of them runs when the phone has animations
 * switched off (then nothing here touches a view, so nothing can be left half done).
 */
final class ListMotion {
    private ListMotion() { }

    static final long GLIDE_MILLIS = 220, POP_MILLIS = 180, SCREEN_MILLIS = 180, REVEAL_MILLIS = 180, FOLD_MILLIS = 150, FADE_MILLIS = 120;

    /** False when the phone's animation scale is 0 or battery saver has animations off. */
    static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }

    /** A view that is {@code fromDy} pixels away from where it now is, because the list changed, glides to its place. */
    static void glide(View view, float fromDy) {
        view.animate().cancel();
        view.setTranslationY(fromDy);
        view.animate().translationY(0).setDuration(GLIDE_MILLIS).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** A row that is new to the list fades in as it rises a little. */
    static void appear(View view, float fromDy) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(fromDy);
        view.animate().alpha(1f).translationY(0).setDuration(GLIDE_MILLIS).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** The bounce of a count that went up: a little smaller, a little larger, then its size. */
    static void pop(View view) {
        view.animate().cancel();
        view.setScaleX(.6f);
        view.setScaleY(.6f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(POP_MILLIS).setInterpolator(new OvershootInterpolator(2.4f)).start();
    }

    /** A screen that has just been put in place comes in from the side by a short slide, with a fade. */
    static void screenIn(View view, float fromDx) {
        view.animate().cancel();
        view.setAlpha(.35f);
        view.setTranslationX(fromDx);
        view.animate().alpha(1f).translationX(0).setDuration(SCREEN_MILLIS).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** A view that was gone grows to {@code height} while it fades in. */
    static void reveal(View view, int height, Runnable end) {
        stop(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = 0;
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.requestLayout();
        ValueAnimator animator = ValueAnimator.ofInt(0, height).setDuration(REVEAL_MILLIS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            params.height = value;
            view.setAlpha(height == 0 ? 1f : value / (float) height);
            view.requestLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (view.getTag() == animation) view.setTag(null);
                params.height = height; view.setAlpha(1f); view.requestLayout();
                if (end != null) end.run();
            }
        });
        view.setTag(animator);
        animator.start();
    }

    /** The other way: a view shrinks to nothing as it fades, then is gone and has its height again for the next time. */
    static void conceal(View view, int height, Runnable end) {
        stop(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ValueAnimator animator = ValueAnimator.ofInt(height, 0).setDuration(REVEAL_MILLIS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            params.height = value;
            view.setAlpha(height == 0 ? 0f : value / (float) height);
            view.requestLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (view.getTag() == animation) view.setTag(null);
                view.setVisibility(View.GONE); params.height = height; view.setAlpha(1f); view.requestLayout();
                if (end != null) end.run();
            }
        });
        view.setTag(animator);
        animator.start();
    }

    // A reveal and a conceal of one view must not run together: the one that ends last would decide what the view looks like, whichever was asked for last.
    private static void stop(View view) {
        if (view.getTag() instanceof ValueAnimator running) running.cancel();
    }
}
