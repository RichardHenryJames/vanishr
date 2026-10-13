package app.vanishr.android;

import android.content.Context;
import android.widget.ScrollView;

/**
 * The message list of a conversation. When its own height changes, for example because the keyboard opens or closes, it keeps
 * the bottom of its content where it was, so the newest messages stay just above the composer instead of ending up behind it.
 */
final class ChatScroll extends ScrollView {
    ChatScroll(Context context) { super(context); }

    // Runs while the view is being laid out, before its own clamping, so the scroll position it still holds is the old one.
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldHeight > 0 && height != oldHeight && getChildCount() > 0) scrollTo(getScrollX(), getScrollY() + oldHeight - height);
    }
}
