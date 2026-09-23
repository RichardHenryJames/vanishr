package app.vanishr.android;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.LinkedHashMap;
import java.util.Map;

final class SecureSheet extends BottomSheetDialog {
    private final Builder spec;
    private final Ui design;
    private final Map<Integer, Button> buttons = new LinkedHashMap<>();
    private ListView choices;
    private LinearLayout content;

    private SecureSheet(Builder spec) {
        super(spec.context, R.style.ThemeOverlay_Vanishr_BottomSheet);
        this.spec = spec;
        design = new Ui(getContext());
        setDismissWithAnimation(true);
        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        }
    }

    Button getButton(int role) { return buttons.get(role); }
    ListView getListView() { return choices; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(getContext()) {
            @Override protected void onMeasure(int width, int height) {
                int available = getResources().getDisplayMetrics().heightPixels - design.dp(40);
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
                if (insets != null) available -= insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout()).top;
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) available = Math.min(available, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(design.dp(120), available), MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false); scroll.setClipToPadding(false); scroll.setSaveEnabled(false);
        content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.SURFACE);
        content.setPadding(design.dp(22), design.dp(8), design.dp(22), design.dp(26));
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.setSaveEnabled(false); content.setSaveFromParentEnabled(false);
        View handle = new View(getContext()); handle.setBackground(design.background(Color.rgb(213, 221, 216), 2, 0));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(design.dp(30), design.dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL; handleSize.topMargin = design.dp(1); handleSize.bottomMargin = design.dp(12);
        content.addView(handle, handleSize);
        LinearLayout header = new LinearLayout(getContext()); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = design.text(spec.title == null ? "" : spec.title.toString(), 21, 800, Ui.INK);
        title.setId(androidx.appcompat.R.id.alertTitle); ViewCompat.setAccessibilityHeading(title, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = design.icon(R.drawable.ic_x, "Close dialog", this::cancel);
        header.addView(close, new LinearLayout.LayoutParams(design.dp(44), design.dp(44)));
        content.addView(header); content.addView(design.spacer(13));
        if (spec.message != null) {
            TextView message = design.text(spec.message.toString(), 13, 500, Ui.MUTED);
            message.setId(android.R.id.message); content.addView(message, new LinearLayout.LayoutParams(-1, -2));
        }
        if (spec.view != null) {
            View body = spec.view;
            if (body instanceof ScrollView container && container.getChildCount() == 1) {
                body = container.getChildAt(0); container.removeView(body);
            }
            if (body instanceof LinearLayout || body instanceof TextView) body.setPadding(0, body.getPaddingTop(), 0, body.getPaddingBottom());
            content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        }
        if (spec.items != null) addChoices();
        if (!spec.actions.isEmpty()) {
            content.addView(design.spacer(24));
            if (spec.actions.containsKey(BUTTON_NEUTRAL)) {
                Button neutral = action(BUTTON_NEUTRAL); neutral.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                MaterialButton textAction = (MaterialButton) neutral;
                textAction.setStrokeWidth(0); textAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
                textAction.setTextColor(Ui.PRIMARY); neutral.setPadding(0, 0, 0, 0);
                content.addView(neutral, new LinearLayout.LayoutParams(-1, -2));
                content.addView(design.spacer(8));
            }
            LinearLayout footer = new LinearLayout(getContext()); footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            for (int role : new int[]{BUTTON_NEGATIVE, BUTTON_POSITIVE}) {
                if (!spec.actions.containsKey(role)) continue;
                Button button = action(role);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2); size.setMarginStart(design.dp(10));
                footer.addView(button, size);
            }
            content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        FrameLayout sheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            android.graphics.drawable.GradientDrawable shape = design.background(Ui.SURFACE, 0, 0);
            float corner = design.dp(8); shape.setCornerRadii(new float[]{corner,corner,corner,corner,0,0,0,0});
            sheet.setBackground(shape); sheet.setClipToOutline(true);
        }
        getBehavior().setSkipCollapsed(true); getBehavior().setFitToContents(true); getBehavior().setHideable(true);
        getBehavior().setSaveFlags(BottomSheetBehavior.SAVE_NONE);
        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            var system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottom = Math.max(system.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            content.setPadding(design.dp(22) + system.left, design.dp(8), design.dp(22) + system.right, design.dp(26) + bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private Button action(int role) {
        Action action = spec.actions.get(role);
        boolean primary = role == BUTTON_POSITIVE && !action.label().equals("Close") && !action.label().equals("Done");
        MaterialButton button = design.button(action.label(), primary, () -> {
            setDismissWithAnimation(false);
            dismiss();
            if (action.listener() != null) action.listener().onClick(this, role);
        });
        if (primary) button.setIconResource(switch (action.label()) {
            case "Find" -> R.drawable.ic_search;
            case "Create" -> R.drawable.ic_plus;
            case "Add contact", "Invite" -> R.drawable.ic_user_round_plus;
            case "Send", "Download" -> R.drawable.ic_arrow_up;
            case "Delete", "Remove" -> R.drawable.ic_trash_2;
            case "Sign out", "Close group", "Leave group" -> R.drawable.ic_log_out;
            default -> R.drawable.ic_check;
        });
        button.setTextSize(12); button.setIconSize(design.dp(17)); button.setIconPadding(design.dp(7));
        button.setPadding(design.dp(17), design.dp(10), design.dp(17), design.dp(10));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setCornerRadius(design.dp(6));
        if (!primary) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE));
            button.setTextColor(Ui.INK); button.setStrokeWidth(design.dp(1)); button.setStrokeColor(android.content.res.ColorStateList.valueOf(Ui.LINE));
        }
        buttons.put(role, button); return button;
    }

    private void addChoices() {
        choices = new ListView(getContext()); choices.setSaveEnabled(false); choices.setDivider(null);
        choices.setBackgroundColor(Ui.SURFACE); choices.setChoiceMode(spec.checked == null ? ListView.CHOICE_MODE_SINGLE : ListView.CHOICE_MODE_MULTIPLE);
        int layout = spec.checked == null ? android.R.layout.simple_list_item_single_choice : android.R.layout.simple_list_item_multiple_choice;
        choices.setAdapter(new ArrayAdapter<CharSequence>(getContext(), layout, spec.items) {
            @NonNull @Override public View getView(int position, View recycled, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, recycled, parent);
                view.setTextSize(13); view.setTypeface(design.font(500)); view.setTextColor(Ui.INK); view.setLetterSpacing(0);
                view.setPadding(design.dp(4), design.dp(12), design.dp(4), design.dp(12)); view.setMinHeight(design.dp(52));
                view.setSingleLine(false); view.setSaveEnabled(false); view.setFilterTouchesWhenObscured(true); return view;
            }
        });
        if (spec.checked == null) choices.setItemChecked(spec.selected, true);
        else for (int index = 0; index < spec.checked.length; index++) choices.setItemChecked(index, spec.checked[index]);
        choices.setOnItemClickListener((parent, view, position, id) -> {
            if (spec.checked == null) spec.selection.onClick(this, position);
            else { spec.checked[position] = choices.isItemChecked(position); spec.multiple.onClick(this, position, spec.checked[position]); }
        });
        int maximum = Math.max(design.dp(156), getContext().getResources().getDisplayMetrics().heightPixels / 2);
        content.addView(choices, new LinearLayout.LayoutParams(-1, Math.min(maximum, design.dp(56) * spec.items.length)));
    }

    private record Action(String label, DialogInterface.OnClickListener listener) { }
    static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence[] items;
        private boolean[] checked;
        private int selected;
        private DialogInterface.OnClickListener selection;
        private DialogInterface.OnMultiChoiceClickListener multiple;
        private final Map<Integer, Action> actions = new LinkedHashMap<>();
        Builder(Context context) { this.context = context; }
        Builder setTitle(CharSequence value) { title = value; return this; }
        Builder setMessage(CharSequence value) { message = value; return this; }
        Builder setView(View value) { view = value; return this; }
        Builder setPositiveButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_POSITIVE, new Action(label, listener)); return this; }
        Builder setNegativeButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_NEGATIVE, new Action(label, listener)); return this; }
        Builder setNeutralButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_NEUTRAL, new Action(label, listener)); return this; }
        Builder setSingleChoiceItems(CharSequence[] values, int value, DialogInterface.OnClickListener listener) { items = values; selected = value; selection = listener; return this; }
        Builder setMultiChoiceItems(CharSequence[] values, boolean[] selected, DialogInterface.OnMultiChoiceClickListener listener) { items = values; checked = selected; multiple = listener; return this; }
        SecureSheet create() { return new SecureSheet(this); }
    }
}