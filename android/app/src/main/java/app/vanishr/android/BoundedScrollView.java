package app.vanishr.android;

import android.content.Context;
import android.widget.ScrollView;

/**
 * A scroll view that never takes more than a fraction of the height it is offered. What it holds can be as long as it likes, but it can
 * never push the rest of a screen out of reach: the part that does not fit scrolls.
 */
final class BoundedScrollView extends ScrollView {
    private final float fraction;

    BoundedScrollView(Context context, float fraction) {
        super(context);
        this.fraction = fraction;
        setFillViewport(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipToPadding(false);
    }

    @Override protected void onMeasure(int width, int height) {
        int basis = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? getResources().getDisplayMetrics().heightPixels : MeasureSpec.getSize(height);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(basis * fraction)), MeasureSpec.AT_MOST));
    }
}
