package app.vanishr.android;

import android.graphics.Bitmap;
import android.util.LruCache;

import java.util.UUID;

/**
 * The pictures a chat draws, kept in memory and nowhere else. A picture is decrypted and decoded when its message is drawn, is dropped
 * with its message, and all of them are dropped when the screen is hidden; nothing is ever written to a file or a cache directory. The
 * cache is bounded in bytes, and a picture that is pushed out is simply decoded again when it is needed. A picture that is pushed out or
 * removed is not recycled, because something on the screen may still be drawing it (a tile, or the copy of a message that its actions
 * show): it is let go, and the runtime frees it once nothing draws it. Only {@link #clear}, which the screen calls after it has let go
 * of everything it draws, recycles what is left.
 */
final class PhotoThumbnails {
    /** The longer side of a decoded thumbnail, in pixels, and the most memory all of them may take. */
    static final int EDGE = 640, BYTES = 24 << 20;

    private final LruCache<UUID, Bitmap> cache = new LruCache<>(BYTES) {
        @Override protected int sizeOf(UUID key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    Bitmap get(UUID id) {
        Bitmap bitmap = cache.get(id);
        return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
    }

    void put(UUID id, Bitmap bitmap) { cache.put(id, bitmap); }

    /** Forgets one message's picture. It is not recycled: whatever is still drawing it keeps it until it lets go. */
    void remove(UUID id) { cache.remove(id); }

    int size() { return cache.snapshot().size(); }

    void clear() {
        java.util.List<Bitmap> held = new java.util.ArrayList<>(cache.snapshot().values());
        cache.evictAll();
        for (Bitmap bitmap : held) if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
