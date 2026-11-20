package app.vanishr.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The bar under the logo while the app opens: a light that flows along a track, round after round, and never stops. It does not measure
 * anything (opening has no percentage worth showing), it only tells the eye that the app is alive.
 *
 * <p>It follows the clock frame by frame instead of using an animator, because a phone that has its animations switched off ends an
 * animator at once, and the bar would then stand still, which is what it must not do. It draws until it leaves the screen.
 */
final class FlowBar extends View {
    /** One round, from the light entering at one end to the next one entering. No pause between rounds. */
    static final long ROUND_MILLIS = 1_200;
    private static final float LIGHT = 0.55f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF bounds = new RectF();
    private final int lightColor;
    private int drawn;

    FlowBar(Context context) {
        super(context);
        track.setColor(Ui.LINE);
        lightColor = Ui.ACCENT;
        setSaveEnabled(false);
    }

    /** Where the light is, from 0 (about to come in) to 1 (gone), worked out from the clock at the moment it is asked. */
    static float position(long uptimeMillis) {
        return (uptimeMillis % ROUND_MILLIS) / (float) ROUND_MILLIS;
    }

    /** How many times it has been drawn, so a test can show that it goes on being drawn. */
    int drawn() { return drawn; }

    /** The colors it was made with, so a test can show that they are those of the look in use. */
    int trackColor() { return track.getColor(); }
    int lightColor() { return lightColor; }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float radius = height / 2f;
        bounds.set(0, 0, width, height);
        shape.reset();
        shape.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        float segment = width * LIGHT;
        int clear = lightColor & 0x00FFFFFF;
        light.setShader(new LinearGradient(0, 0, segment, 0, new int[]{clear, lightColor, clear}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawn++;
        canvas.drawPath(shape, track);
        float segment = getWidth() * LIGHT;
        float left = -segment + position(SystemClock.uptimeMillis()) * (getWidth() + segment);
        int saved = canvas.save();
        canvas.clipPath(shape);
        canvas.translate(left, 0);
        canvas.drawRect(0, 0, segment, getHeight(), light);
        canvas.restoreToCount(saved);
        postInvalidateOnAnimation();
    }
}
