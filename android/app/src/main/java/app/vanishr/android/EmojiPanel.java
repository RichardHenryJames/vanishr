package app.vanishr.android;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The emoji that the composer offers in place of the keyboard: a grid of what is in the tab that is chosen, the tabs along the bottom
 * and a key that takes back the last one. It is made here, in code, from {@link Emoji}; nothing is downloaded and nothing is kept
 * but the list of what was used lately, which the caller owns and keeps in memory only.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater. The delete key takes touches itself, to go on
// taking letters back while it is held; a screen reader's tap reaches it as a click.
@android.annotation.SuppressLint({"ViewConstructor", "ClickableViewAccessibility"})
final class EmojiPanel extends LinearLayout {
    interface Listener {
        void picked(String emoji);
        void erase();
    }

    /** The most that are remembered as used lately. */
    static final int RECENT_MAX = 24;

    private final Ui design;
    private final Listener listener;
    private final List<String> recent;
    private final GridView grid;
    private final List<TextView> tabs = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final BaseAdapter adapter;
    private final Runnable repeat;
    private final ImageButton erase;
    private int tab = -1, requested = -1;

    private static List<List<String>> supported;

    // What this phone can draw: the offered emoji of every tab, filtered by the fonts the phone has, once, and each emoji in the first tab
    // that offers it.
    private static synchronized List<List<String>> supported() {
        if (supported != null) return supported;
        Paint paint = new Paint();
        Set<String> seen = new HashSet<>();
        List<List<String>> result = new ArrayList<>();
        for (Emoji.Category category : Emoji.CATEGORIES) {
            List<String> found = new ArrayList<>();
            for (String each : category.all()) if (seen.add(each) && paint.hasGlyph(each)) found.add(each);
            result.add(found);
        }
        supported = result;
        return result;
    }

    /** {@code recent} is shared with the caller, who clears it when the app is locked. */
    EmojiPanel(Context context, Ui design, List<String> recent, Listener listener) {
        super(context);
        this.design = design; this.recent = recent; this.listener = listener;
        setOrientation(VERTICAL);
        setSaveEnabled(false);
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);
        setContentDescription("Emoji");
        View line = new View(context);
        line.setBackgroundColor(Ui.LINE);
        addView(line, new LayoutParams(-1, 1));

        grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(design.dp(44));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSaveEnabled(false);
        grid.setPadding(design.dp(6), design.dp(4), design.dp(6), design.dp(4));
        grid.setClipToPadding(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public String getItem(int position) { return shown.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reused, ViewGroup parent) {
                TextView cell = reused instanceof TextView text ? text : cell(context);
                String emoji = shown.get(position);
                cell.setText(emoji);
                cell.setContentDescription(emoji);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> { if (position < shown.size()) pick(shown.get(position)); });
        addView(grid, new LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(design.background(Ui.CANVAS, 0, 0));
        addTab(bar, Emoji.RECENT_ICON, Emoji.RECENT_NAME, 0);
        for (int index = 0; index < Emoji.CATEGORIES.size(); index++) addTab(bar, Emoji.CATEGORIES.get(index).icon(), Emoji.CATEGORIES.get(index).name(), index + 1);
        erase = new ImageButton(context);
        erase.setImageResource(R.drawable.ic_delete);
        erase.setBackground(null);
        erase.setColorFilter(Ui.INK);
        erase.setContentDescription("Delete");
        erase.setSaveEnabled(false);
        repeat = new Runnable() {
            @Override public void run() { listener.erase(); erase.postDelayed(this, 70); }
        };
        // A press takes back one at once and, held, goes on taking them back; a screen reader's tap is a click.
        erase.setOnClickListener(view -> listener.erase());
        erase.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: view.setPressed(true); listener.erase(); view.postDelayed(repeat, 420); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: view.setPressed(false); view.removeCallbacks(repeat); return true;
                default: return true;
            }
        });
        bar.addView(erase, new LayoutParams(design.dp(48), design.dp(44)));
        addView(bar, new LayoutParams(-1, design.dp(44)));
    }

    /** The tab to open when the panel is first shown, when it is not the first one. */
    void opensAt(int index) { requested = index; }

    /**
     * Fills the panel, once, when it is first shown: which emoji the phone can draw is worked out then, and not when a chat is opened,
     * where it would be a delay that nobody asked for.
     */
    void load() {
        if (tab < 0) select(requested >= 0 ? requested : recent.isEmpty() ? 1 : 0);
    }

    private TextView cell(Context context) {
        TextView cell = new TextView(context);
        cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        cell.setGravity(Gravity.CENTER);
        cell.setIncludeFontPadding(false);
        cell.setTypeface(Typeface.DEFAULT);
        cell.setLayoutParams(new AbsListView.LayoutParams(-1, design.dp(46)));
        TypedArray ripple = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        cell.setBackground(ripple.getDrawable(0));
        ripple.recycle();
        return cell;
    }

    private void addTab(LinearLayout bar, String icon, String name, int index) {
        TextView button = new TextView(getContext());
        button.setText(icon);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(name);
        button.setSaveEnabled(false);
        button.setOnClickListener(view -> select(index));
        tabs.add(button);
        bar.addView(button, new LayoutParams(0, -1, 1));
    }

    private void pick(String emoji) {
        listener.picked(emoji);
    }

    /** Remembers {@code emoji} as the one used last, in memory only. */
    void used(String emoji) {
        recent.remove(emoji);
        recent.add(0, emoji);
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
    }

    /** Shows tab {@code index}: 0 is what was used lately (or what people use most, before anything has been), then the categories. */
    void select(int index) {
        if (index < 0 || index >= tabs.size()) return;
        tab = index;
        shown.clear();
        List<String> offered = index == 0 ? (recent.isEmpty() ? Emoji.FAVOURITES : recent) : supported().get(index - 1);
        if (index == 0) {
            Paint paint = new Paint();
            for (String each : offered) if (paint.hasGlyph(each)) shown.add(each);
        } else shown.addAll(offered);
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        for (int each = 0; each < tabs.size(); each++) {
            TextView button = tabs.get(each);
            button.setAlpha(each == index ? 1f : 0.5f);
            button.setBackground(each == index ? underline() : null);
        }
    }

    private LayerDrawable underline() {
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(Ui.ACCENT);
        LayerDrawable layers = new LayerDrawable(new GradientDrawable[]{bar});
        layers.setLayerInset(0, design.dp(10), design.dp(40), design.dp(10), 0);
        return layers;
    }

    int tab() { return tab; }
    GridView grid() { return grid; }
    List<String> shown() { return shown; }
    List<TextView> tabs() { return tabs; }
    ImageButton eraseKey() { return erase; }

    @Override protected void onDetachedFromWindow() {
        erase.removeCallbacks(repeat);
        super.onDetachedFromWindow();
    }
}
