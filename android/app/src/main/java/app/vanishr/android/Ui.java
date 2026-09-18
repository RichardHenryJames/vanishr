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
    static final int INK = Color.rgb(24, 39, 36);
    static final int PRIMARY = Color.rgb(20, 108, 88);
    static final int MUTED = Color.rgb(92, 108, 103);
    static final int CANVAS = Color.rgb(245, 248, 246);
    static final int SURFACE = Color.WHITE;
    static final int LINE = Color.rgb(220, 228, 224);
    static final int TINT = Color.rgb(221, 241, 233);
    static final int BLUE = Color.rgb(53, 92, 190);
    static final int BLUE_TINT = Color.rgb(232, 237, 250);
    static final int ERROR = Color.rgb(179, 60, 74);
    static final int ERROR_TINT = Color.rgb(253, 238, 240);
    static final int WARM = Color.rgb(168, 81, 53);
    private final Context context;
    private final Typeface face;
    record Field(TextInputLayout layout, TextInputEditText input) { }

    Ui(Context context) { this.context = context; this.face = context.getResources().getFont(R.font.manrope); }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    Typeface font(int weight) { return Typeface.create(face, weight, false); }

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
        return new RippleDrawable(ColorStateList.valueOf(0x14146C58), background(color, radius, 0), background(Color.WHITE, radius, 0));
    }

    MaterialButton button(String label, boolean primary, Runnable action) {
        MaterialButton button = new MaterialButton(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(font(700));
        button.setFontVariationSettings("'wght' 700");
        button.setLetterSpacing(0);
        button.setCornerRadius(dp(8));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(20), dp(10), dp(20), dp(10));
        button.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{LINE, TINT, primary ? PRIMARY : SURFACE}));
        ColorStateList foreground = new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{MUTED, primary ? Color.WHITE : PRIMARY});
        button.setTextColor(foreground);
        button.setIconTint(foreground);
        if (!primary) { button.setStrokeWidth(dp(1)); button.setStrokeColor(ColorStateList.valueOf(LINE)); }
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
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setBackground(feedback(Color.TRANSPARENT, 24));
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
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

    TextView avatar(String name, int size, int color) {
        String trimmed = name.strip();
        String initials = trimmed.isEmpty() ? "?" : trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        TextView avatar = text(initials, size >= 64 ? 25 : 18, 700, color);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(background(color == BLUE ? BLUE_TINT : TINT, size / 2, 0));
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return avatar;
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
        TextInputLayout wrapper = new TextInputLayout(context);
        wrapper.setHint(name);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        wrapper.setBoxCornerRadii(dp(8), dp(8), dp(8), dp(8));
        wrapper.setBoxBackgroundColor(SURFACE);
        wrapper.setBoxStrokeColor(PRIMARY);
        wrapper.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
        wrapper.setHintTextColor(ColorStateList.valueOf(PRIMARY));
        wrapper.setTypeface(font(500));
        wrapper.setSaveEnabled(false);
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setInputType(type);
        input.setTextSize(15);
        input.setTypeface(font(500));
        input.setFontVariationSettings("'wght' 500");
        input.setTextColor(INK);
        input.setLetterSpacing(0);
        input.setPadding(dp(16), dp(18), dp(16), dp(18));
        input.setMinHeight(dp(58));
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilterTouchesWhenObscured(true);
        if ((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) input.setSingleLine(true);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return new Field(wrapper, input);
    }

    void inlineSave(Field field, String name, Runnable action) {
        TextInputLayout wrapper = field.layout();
        wrapper.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        wrapper.setEndIconDrawable(R.drawable.ic_check);
        wrapper.setEndIconCheckable(false);
        wrapper.setEndIconContentDescription(name);
        wrapper.setEndIconTintList(ColorStateList.valueOf(PRIMARY));
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