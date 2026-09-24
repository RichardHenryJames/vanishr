package app.vanishr.android;

import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import java.util.*;

public final class RemotePhotosActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui design;
    private RemotePhotoSession session;
    private String sessionId;
    private GridView grid;
    private FrameLayout content;
    private TextView status;
    private PhotoAdapter adapter;
    private ZoomImage image;
    private long revision = -1;
    private boolean visible;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refresh();
            handler.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        design = new Ui(this);
        sessionId = getIntent().getStringExtra("session");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.SURFACE);
        root.setSaveEnabled(false); root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(design.dp(8), design.dp(8), design.dp(8), design.dp(8));
        toolbar.addView(design.icon(R.drawable.ic_arrow_left, "Back", this::back));
        TextView title = design.text("Photos", 18, 700, Ui.INK);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(design.icon(R.drawable.ic_x, "End photo access", this::finishSharing));
        root.addView(toolbar); root.addView(design.divider());
        status = design.text("Connecting...", 13, 500, Ui.MUTED); status.setGravity(Gravity.CENTER); status.setPadding(design.dp(16), design.dp(10), design.dp(16), design.dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new GridView(this); grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(design.dp(96)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(design.dp(3)); grid.setVerticalSpacing(design.dp(3)); grid.setPadding(design.dp(3), design.dp(3), design.dp(3), design.dp(3));
        grid.setSaveEnabled(false); grid.setClipToPadding(false); adapter = new PhotoAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> { if (session != null) session.open(id); });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (session != null && count > 0 && total > 0 && first + count >= total - 3) session.loadMore();
            }
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        image = new ZoomImage(); image.setVisibility(View.GONE); content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var system = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            root.setPadding(system.left, system.top, system.right, system.bottom); return insets;
        });
        setContentView(root);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
    }

    private void refresh() {
        RemotePhotoSession value = PhotoSharingService.current(sessionId);
        if (value == null) { if (!PhotoSharingService.busy()) status.setText("Photo access ended"); return; }
        if (value.owner()) { finish(); return; }
        session = value;
        if (!PhotoSharingService.phoneUnlocked(this)) { finishSharing(); return; }
        if (revision == value.revision()) return;
        revision = value.revision();
        status.setText(value.status()); status.setVisibility(value.status().isEmpty() ? View.GONE : View.VISIBLE);
        if (value.ended()) {
            image.show(null); adapter.clear(); grid.setVisibility(View.GONE); image.setVisibility(View.GONE); return;
        }
        boolean full = value.selected() > 0;
        grid.setVisibility(full ? View.GONE : View.VISIBLE); image.setVisibility(full ? View.VISIBLE : View.GONE);
        if (full) image.show(value.fullImage());
        else { image.show(null); adapter.items = value.photos(); adapter.notifyDataSetChanged(); }
    }

    private void back() { if (session != null && session.selected() > 0 && !session.ended()) session.gallery(); else finishSharing(); }
    private void finishSharing() { if (session != null) PhotoSharingService.endFor(session.userId(), session.peerId()); finish(); }
    @Override protected void onResume() { super.onResume(); visible = true; revision = -1; handler.post(update); }
    @Override protected void onPause() { visible = false; handler.removeCallbacks(update); image.show(null); adapter.clear(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); image.show(null); adapter.clear(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(new Bundle()); }

    private final class PhotoAdapter extends BaseAdapter {
        java.util.List<Long> items = java.util.List.of();
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position); }
        @Override public boolean hasStableIds() { return true; }
        void clear() { items = java.util.List.of(); notifyDataSetChanged(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ImageView thumbnail;
            if (recycled instanceof ImageView existing) thumbnail = existing;
            else {
                thumbnail = new androidx.appcompat.widget.AppCompatImageView(RemotePhotosActivity.this) {
                    @Override protected void onMeasure(int width, int height) { super.onMeasure(width, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width), MeasureSpec.EXACTLY)); }
                };
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP); thumbnail.setBackgroundColor(Ui.CANVAS);
                thumbnail.setLayoutParams(new AbsListView.LayoutParams(-1, design.dp(96))); thumbnail.setSaveEnabled(false); thumbnail.setFilterTouchesWhenObscured(true);
            }
            thumbnail.setImageDrawable(null);
            thumbnail.setContentDescription("Photo " + (position + 1));
            byte[] bytes = session == null ? null : session.thumbnail(items.get(position));
            if (bytes != null) {
                try { thumbnail.setImageBitmap(PhotoLibrary.decode(bytes, 320)); }
                catch (Exception ignored) { thumbnail.setImageResource(R.drawable.ic_image); }
                finally { Arrays.fill(bytes, (byte) 0); }
            } else thumbnail.setImageResource(R.drawable.ic_image);
            return thumbnail;
        }
    }

    private final class ZoomImage extends androidx.appcompat.widget.AppCompatImageView {
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector scaling;
        private Bitmap bitmap;
        private float previousX, previousY, zoom = 1;
        ZoomImage() {
            super(RemotePhotosActivity.this); setScaleType(ScaleType.MATRIX); setContentDescription("Full photo"); setSaveEnabled(false); setFilterTouchesWhenObscured(true);
            scaling = new ScaleGestureDetector(RemotePhotosActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float next = Math.max(1, Math.min(12, zoom * detector.getScaleFactor()));
                    transform.postScale(next / zoom, next / zoom, detector.getFocusX(), detector.getFocusY()); zoom = next; setImageMatrix(transform); return true;
                }
            });
        }
        void show(Bitmap value) { if (bitmap == value) return; bitmap = value; setImageBitmap(value); fit(); }
        private void fit() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
            transform.setRectToRect(new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()), new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
            zoom = 1; setImageMatrix(transform);
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { super.onSizeChanged(width, height, oldWidth, oldHeight); fit(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            scaling.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaling.isInProgress() && zoom > 1) {
                transform.postTranslate(event.getX() - previousX, event.getY() - previousY); setImageMatrix(transform);
            }
            previousX = event.getX(); previousY = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}