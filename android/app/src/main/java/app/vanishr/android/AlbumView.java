package app.vanishr.android;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The photos of one album drawn together as a square of tiles: two side by side, three as one tall tile and two small, four or more as
 * a square of four whose fourth tile says how many are behind it. Only the first four are ever drawn; the rest are reached by opening
 * the album. The same view draws an album that is still being sent, whose tiles have no message yet.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class AlbumView extends FrameLayout {
    private final List<PhotoTile> tiles = new ArrayList<>();
    private final int count;

    /** {@code members} are the messages of the album (null for one that is being sent); {@code count} is how many photos it has. */
    AlbumView(Context context, Ui design, List<ChatEngine.Entry> members, int count, int width, int gap) {
        super(context);
        this.count = count;
        setSaveEnabled(false);
        AlbumLayout.Plan plan = AlbumLayout.plan(count, width, gap);
        setLayoutParams(new android.view.ViewGroup.LayoutParams(plan.width(), plan.height()));
        for (AlbumLayout.Tile place : plan.tiles()) {
            PhotoTile tile = new PhotoTile(context, design, members == null ? null : members.get(place.index()), 6);
            tile.more(place.more());
            tile.setContentDescription(place.more() > 0 ? "Photo " + (place.index() + 1) + " of " + count + ", and " + place.more() + " more"
                    : "Photo " + (place.index() + 1) + " of " + count);
            LayoutParams params = new LayoutParams(place.width(), place.height(), Gravity.TOP | Gravity.START);
            params.leftMargin = place.left(); params.topMargin = place.top();
            addView(tile, params);
            tiles.add(tile);
        }
    }

    /** The tiles that are drawn, in the order of the album. */
    List<PhotoTile> tiles() { return tiles; }

    int count() { return count; }

    /** The photo under a point of the screen, or -1 when the point is in a gap or outside the album. */
    int indexAt(float rawX, float rawY) {
        int[] at = new int[2];
        for (int index = 0; index < tiles.size(); index++) {
            PhotoTile tile = tiles.get(index);
            tile.getLocationOnScreen(at);
            if (rawX >= at[0] && rawX < at[0] + tile.getWidth() && rawY >= at[1] && rawY < at[1] + tile.getHeight()) return index;
        }
        return -1;
    }
}
