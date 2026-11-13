package app.vanishr.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

/**
 * The quoted message of a reply, in a bubble and above the composer: an accent bar, who wrote it in bold, and the start of what was
 * said. The name is the reader's own ("You") or the contact's, in different colours, so it is clear at a glance whose words these are.
 */
// Built in code, never inflated from a layout, so it has no constructor for the inflater.
@android.annotation.SuppressLint("ViewConstructor")
final class QuoteBlock extends LinearLayout {
    static final String PHOTO = "Photo", VIEW_ONCE = "View-once message";
    // A shade darker than the bubble it sits in, as a quote is in a chat app.
    static final int ON_TINT = Color.rgb(205, 228, 218), ON_SURFACE = Color.rgb(238, 243, 240);
    private static final int SNIPPET = Color.rgb(64, 82, 76);

    private final Ui design;
    private final View bar;
    private final TextView name, snippet;

    /** {@code limit} is the widest the text may be, in pixels, so that a long quote wraps inside the screen. */
    QuoteBlock(Context context, Ui design, int ground, int maxLines, int limit) {
        super(context);
        this.design = design;
        setOrientation(HORIZONTAL);
        setSaveEnabled(false);
        setBackground(design.background(ground, 6, 0));
        setClipToOutline(true);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        bar = new View(context);
        addView(bar, new LayoutParams(design.dp(4), -1));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(VERTICAL);
        column.setPadding(design.dp(8), design.dp(6), design.dp(10), design.dp(6));
        name = design.text("", 12, 700, Ui.PRIMARY);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); name.setMaxWidth(limit);
        column.addView(name, new LayoutParams(-2, -2));
        snippet = design.text("", 13, 500, SNIPPET);
        snippet.setMaxLines(maxLines); snippet.setEllipsize(TextUtils.TruncateAt.END); snippet.setMaxWidth(limit);
        snippet.setCompoundDrawablePadding(design.dp(5));
        column.addView(snippet, new LayoutParams(-2, -2));
        addView(column, new LayoutParams(0, -2, 1));
    }

    /** {@code author} null leaves the name out (an older app's reply says nothing about who wrote what it quotes). */
    QuoteBlock show(String author, boolean mine, String text) {
        int accent = mine ? Ui.PRIMARY : Ui.BLUE;
        bar.setBackgroundColor(accent);
        name.setVisibility(author == null ? GONE : VISIBLE);
        name.setText(author == null ? "" : author);
        name.setTextColor(accent);
        snippet.setText(text);
        int icon = PHOTO.equals(text) ? R.drawable.ic_image : VIEW_ONCE.equals(text) ? R.drawable.ic_eye : 0;
        Drawable symbol = icon == 0 ? null : ContextCompat.getDrawable(getContext(), icon).mutate();
        if (symbol != null) { symbol.setTint(SNIPPET); symbol.setBounds(0, 0, design.dp(15), design.dp(15)); }
        snippet.setCompoundDrawablesRelative(symbol, null, null, null);
        return this;
    }
}
