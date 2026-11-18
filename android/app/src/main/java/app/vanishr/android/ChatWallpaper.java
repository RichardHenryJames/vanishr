package app.vanishr.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

final class ChatWallpaper {
    private static Bitmap tile;

    private ChatWallpaper() { }

    // One small tile, shared by every chat. Android draws the repeating shader behind the scroll viewport.
    static Drawable create(Context context) {
        if (tile == null) {
            int edge = 144;
            Bitmap rendered = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
            rendered.setDensity(DisplayMetrics.DENSITY_DEFAULT);
            Canvas canvas = new Canvas(rendered);
            canvas.drawColor(Ui.CANVAS);
            Drawable doodles = context.getDrawable(R.drawable.chat_doodles);
            doodles.setBounds(0, 0, edge, edge);
            doodles.draw(canvas);
            rendered.setHasAlpha(false);
            tile = rendered;
        }
        BitmapDrawable wallpaper = new BitmapDrawable(context.getResources(), tile);
        wallpaper.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        wallpaper.setFilterBitmap(true);
        return wallpaper;
    }
}
