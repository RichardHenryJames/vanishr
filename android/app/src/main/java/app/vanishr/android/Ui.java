package app.vanishr.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

final class Ui {
    // The colors in use, set by use() from Palette: the light look until configure() has been told what the phone and the person want. They
    // are read when a view is built, so a change of look is a new activity (see MainActivity.chooseTheme), never a repaint of views already made.
    static int INK, MUTED, CANVAS, SURFACE, LINE, OUTLINE, RIPPLE;
    /** PRIMARY is a fill that carries white; ACCENT is the same green as text or an icon on the page, the bubbles and the sheets. */
    static int PRIMARY, ACCENT, TINT;
    static int BLUE, BLUE_TINT, ERROR, ERROR_TINT, WARM, WARM_TINT;
    /** Fills that carry white, which the text colors above are not in the dark look. */
    static int MUTED_FILL, BLUE_FILL, ERROR_FILL, WARM_FILL;
    static int DOODLES, QUOTE_TINT, QUOTE_SURFACE, SNIPPET;
    static boolean dark;
    static { use(Palette.LIGHT); }
    private final Context context;
    private final Typeface face;
    private final android.util.SparseArray<Typeface> fonts = new android.util.SparseArray<>();
    record Field(TextInputLayout layout, TextInputEditText input, LinearLayout view) { }

    static void use(Palette.Colors colors) {
        dark = colors.dark();
        INK = colors.ink(); MUTED = colors.muted(); CANVAS = colors.canvas(); SURFACE = colors.surface(); LINE = colors.line();
        OUTLINE = colors.outline(); RIPPLE = colors.ripple();
        PRIMARY = colors.primary(); ACCENT = colors.accent(); TINT = colors.tint();
        BLUE = colors.blue(); BLUE_TINT = colors.blueTint(); ERROR = colors.error(); ERROR_TINT = colors.errorTint();
        WARM = colors.warm(); WARM_TINT = colors.warmTint();
        MUTED_FILL = colors.mutedFill(); BLUE_FILL = colors.blueFill(); ERROR_FILL = colors.errorFill(); WARM_FILL = colors.warmFill();
        DOODLES = colors.doodles(); QUOTE_TINT = colors.quoteTint(); QUOTE_SURFACE = colors.quoteSurface(); SNIPPET = colors.snippet();
    }

    /** What the person chose in the profile (until they choose, the phone decides). */
    static Palette.Choice choice(Context context) {
        return Palette.Choice.of(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("theme", null));
    }

    static void choose(Context context, Palette.Choice choice) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putString("theme", choice.stored()).apply();
    }

    /** Works out which look is in use for this screen and makes it the one that every view built from now on takes its colors from. */
    static void configure(Context context) {
        boolean phoneIsDark = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        use(Palette.colors(Palette.dark(choice(context), phoneIsDark)));
    }

    /** The theme of an activity or a sheet in the look that is in use. */
    static int theme() { return dark ? R.style.Theme_Vanishr_Dark : R.style.Theme_Vanishr_Light; }

    Ui(Context context) { this.context = context; this.face = context.getResources().getFont(R.font.manrope); }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    Typeface font(int weight) {
        Typeface result = fonts.get(weight);
        if (result == null) {
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setTypeface(face);
            paint.setFontVariationSettings("'wght' " + weight);
            result = paint.getTypeface();
            fonts.put(weight, result);
        }
        return result;
    }

    TextView text(String value, int size, int weight, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTypeface(font(weight));
        text.setFontVariationSettings("'wght' " + weight);
        text.setTextColor(color);
        text.setLetterSpacing(0);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(3), 1);
        text.setSaveEnabled(false);
        text.setTextIsSelectable(false);
        return text;
    }

    GradientDrawable background(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    RippleDrawable feedback(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), background(color, radius, 0), background(Color.WHITE, radius, 0));
    }

    MaterialButton button(String label, boolean primary, Runnable action) {
        MaterialButton button = new MaterialButton(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(font(700));
        button.setFontVariationSettings("'wght' 700");
        button.setLetterSpacing(0);
        button.setCornerRadius(dp(6));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(17), dp(10), dp(17), dp(10));
        button.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{LINE, TINT, primary ? PRIMARY : SURFACE}));
        ColorStateList foreground = new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{MUTED, primary ? Color.WHITE : ACCENT});
        button.setTextColor(foreground);
        button.setIconTint(foreground);
        if (!primary) { button.setStrokeWidth(dp(1)); button.setStrokeColor(ColorStateList.valueOf(OUTLINE)); }
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> action.run());
        button.setSaveEnabled(false);
        return button;
    }

    ImageButton icon(int resource, String name, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(switch (resource) {
            case android.R.drawable.ic_media_previous -> R.drawable.ic_arrow_left;
            case android.R.drawable.ic_menu_manage -> R.drawable.ic_settings_2;
            case android.R.drawable.ic_lock_lock -> R.drawable.ic_lock_keyhole;
            case android.R.drawable.ic_input_add -> R.drawable.ic_plus;
            case android.R.drawable.ic_menu_gallery -> R.drawable.ic_image;
            case android.R.drawable.ic_menu_camera -> R.drawable.ic_camera;
            case android.R.drawable.ic_menu_send -> R.drawable.ic_arrow_up;
            default -> resource;
        });
        button.setColorFilter(INK);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(feedback(Color.TRANSPARENT, 24));
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        button.setFilterTouchesWhenObscured(true);
        button.setSaveEnabled(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    ImageView symbol(int resource, int size, int color) {
        ImageView image = new ImageView(context);
        image.setImageResource(resource);
        image.setColorFilter(color);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    ImageView logo(int size) {
        ImageView image = new ImageView(context);
        image.setId(R.id.brand_logo);
        image.setImageResource(R.drawable.vanishr_logo);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(size), dp(size));
        layout.setMarginEnd(dp(8));
        image.setLayoutParams(layout);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setSaveEnabled(false);
        return image;
    }

    TextView avatar(String name, int size, int color) {
        String trimmed = name.strip();
        String initials = trimmed.isEmpty() ? "?" : trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        TextView avatar = text(initials, size >= 64 ? 25 : size <= 40 ? 13 : 16, 800, color);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(background(color == BLUE ? BLUE_TINT : color == ERROR ? ERROR_TINT : color == WARM ? WARM_TINT : TINT, size / 2, 0));
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return avatar;
    }

    int avatarColor(java.util.UUID id) {
        return switch (Math.floorMod(id.hashCode(), 4)) { case 0 -> ACCENT; case 1 -> BLUE; case 2 -> ERROR; default -> WARM; };
    }

    ImageView groupAvatar(int size, int color) {
        ImageView image = symbol(R.drawable.ic_users_round, size, color);
        int inset = Math.max(6, (size - 23) / 2);
        image.setPadding(dp(inset), dp(inset), dp(inset), dp(inset));
        image.setBackground(background(color == BLUE ? BLUE_TINT : TINT, 8, 0));
        return image;
    }

    View spacer(int height) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return spacer;
    }

    View divider() {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return divider;
    }

    Field field(String name, int type) {
        LinearLayout container = new LinearLayout(context); container.setOrientation(LinearLayout.VERTICAL); container.setSaveEnabled(false);
        TextView label = text(name, 11, 700, MUTED);
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-1, -2); labelSize.bottomMargin = dp(7);
        container.addView(label, labelSize);
        TextInputLayout wrapper = new TextInputLayout(context);
        wrapper.setHintEnabled(false);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        wrapper.setBoxCornerRadii(dp(6), dp(6), dp(6), dp(6));
        wrapper.setBoxBackgroundColor(SURFACE);
        wrapper.setBoxStrokeColor(ACCENT);
        wrapper.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
        wrapper.setHintTextColor(ColorStateList.valueOf(ACCENT));
        wrapper.setTypeface(font(500));
        wrapper.setSaveEnabled(false);
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setInputType(type);
        input.setTextSize(14); input.setHint(name); input.setId(View.generateViewId());
        input.setTypeface(font(500));
        input.setFontVariationSettings("'wght' 500");
        input.setTextColor(INK);
        input.setLetterSpacing(0);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMinHeight(dp(48));
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilterTouchesWhenObscured(true);
        if ((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) input.setSingleLine(true);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        container.addView(wrapper, new LinearLayout.LayoutParams(-1, -2)); label.setLabelFor(input.getId());
        return new Field(wrapper, input, container);
    }

    void inlineSave(Field field, String name, Runnable action) {
        TextInputLayout wrapper = field.layout();
        wrapper.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        wrapper.setEndIconDrawable(R.drawable.ic_check);
        wrapper.setEndIconCheckable(false);
        wrapper.setEndIconContentDescription(name);
        wrapper.setEndIconTintList(ColorStateList.valueOf(ACCENT));
        wrapper.setEndIconMinSize(dp(48));
        wrapper.setEndIconOnClickListener(view -> action.run());
        View button = wrapper.findViewById(com.google.android.material.R.id.text_input_end_icon);
        button.setTooltipText(name); button.setFilterTouchesWhenObscured(true); button.setSaveEnabled(false);
        field.input().addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                wrapper.setError(null); wrapper.setHelperText(null);
            }
            @Override public void afterTextChanged(android.text.Editable value) { }
        });
    }
}