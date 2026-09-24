package app.vanishr.android;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Size;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

final class PhotoLibrary {
    static final int PAGE_SIZE = 12;
    static final int CHUNK_SIZE = 16_384;
    static final int THUMBNAIL_BYTES = 12_000;
    static final int MAX_OPEN_BYTES = 64 * 1024 * 1024;
    record Page(List<Long> ids, long cursor, boolean more) { }
    private final Context context;
    PhotoLibrary(Context context) { this.context = context.getApplicationContext(); }

    static boolean permitted(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return true;
        return Build.VERSION.SDK_INT <= 32 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 34) return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        return new String[]{Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void requireAccess() { if (!permitted(context)) throw new SecurityException("Photo permission is unavailable"); }
    static Uri collection() { return Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Images.Media.EXTERNAL_CONTENT_URI; }
    private Uri image(long id) { if (id <= 0) throw new IllegalArgumentException("Invalid photo"); return ContentUris.withAppendedId(collection(), id); }

    Page page(long before) throws IOException {
        requireAccess();
        if (before <= 0) throw new IllegalArgumentException("Invalid gallery cursor");
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Images.Media._ID + " < ?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{Long.toString(before)});
        query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.Images.Media._ID + " DESC");
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE + 1);
        try (Cursor cursor = context.getContentResolver().query(collection(), new String[]{MediaStore.Images.Media._ID}, query, null)) {
            if (cursor == null) throw new IOException("Photo library is unavailable");
            List<Long> ids = new ArrayList<>();
            while (ids.size() < PAGE_SIZE && cursor.moveToNext()) ids.add(cursor.getLong(0));
            boolean more = cursor.moveToNext();
            return new Page(List.copyOf(ids), ids.isEmpty() ? before : ids.get(ids.size() - 1), more);
        }
    }

    byte[] thumbnail(long id) throws IOException {
        requireAccess();
        Bitmap bitmap = Build.VERSION.SDK_INT >= 29
                ? context.getContentResolver().loadThumbnail(image(id), new Size(160, 160), null)
                : ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), image(id)), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    double scale = Math.min(1, 160d / Math.max(info.getSize().getWidth(), info.getSize().getHeight()));
                    decoder.setTargetSize(Math.max(1, (int) (info.getSize().getWidth() * scale)), Math.max(1, (int) (info.getSize().getHeight() * scale)));
                });
        try {
            for (int quality : new int[]{75, 50, 25}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw new IOException("Photo preview is unavailable");
                if (output.size() <= THUMBNAIL_BYTES) return output.toByteArray();
            }
            throw new IOException("Photo preview is too large");
        } finally { bitmap.recycle(); }
    }

    Opened open(long id) throws IOException {
        requireAccess();
        String type = context.getContentResolver().getType(image(id));
        if (type == null || !type.startsWith("image/")) throw new IOException("Photo is unavailable");
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(image(id), "r");
        if (descriptor == null) throw new IOException("Photo is unavailable");
        long size = descriptor.getStatSize();
        if (size <= 0 || size > MAX_OPEN_BYTES) { descriptor.close(); throw new IOException("Photo cannot be opened in this viewer"); }
        return new Opened(descriptor, size);
    }

    static Bitmap decode(byte[] bytes, int maximumEdge) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            double scale = Math.min(1, (double) maximumEdge / Math.max(info.getSize().getWidth(), info.getSize().getHeight()));
            decoder.setTargetSize(Math.max(1, (int) (info.getSize().getWidth() * scale)), Math.max(1, (int) (info.getSize().getHeight() * scale)));
        });
    }

    static final class Opened implements AutoCloseable {
        final long size;
        private final InputStream input;
        private long position;
        Opened(ParcelFileDescriptor descriptor, long size) { this.size = size; input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); }
        long position() { return position; }
        byte[] next() throws IOException {
            byte[] bytes = new byte[(int) Math.min(CHUNK_SIZE, size - position)];
            int offset = 0;
            while (offset < bytes.length) {
                int received = input.read(bytes, offset, bytes.length - offset);
                if (received <= 0) { Arrays.fill(bytes, (byte) 0); throw new IOException("Photo changed during transfer"); }
                offset += received;
            }
            position += bytes.length;
            return bytes;
        }
        @Override public void close() throws IOException { input.close(); }
    }
}