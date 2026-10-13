package app.vanishr.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import app.vanishr.crypto.ImageCipher;
import app.vanishr.crypto.ProfileEnvelope;

import java.io.*;
import java.util.Arrays;

final class SafeImages {
    private SafeImages() { }

    /**
     * Writes a received photo, unchanged, into the shared Pictures/Vanishr folder through MediaStore. Android 10 and later
     * need no storage permission for a file the app creates. Nothing is written for a format the gallery cannot show.
     */
    @androidx.annotation.RequiresApi(29)
    static Uri saveToGallery(Context context, byte[] image) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        String type = bounds.outMimeType;
        String extension = "image/jpeg".equals(type) ? ".jpg" : "image/png".equals(type) ? ".png" : "image/webp".equals(type) ? ".webp" : null;
        if (extension == null) throw new IOException("Unsupported image format");
        ContentResolver resolver = context.getContentResolver();
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.ROOT));
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Vanishr_" + stamp + extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE, type);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vanishr");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (item == null) throw new IOException("Gallery unavailable");
        try {
            try (OutputStream output = resolver.openOutputStream(item, "w")) {
                if (output == null) throw new IOException("Gallery unavailable");
                output.write(image);
            }
            ContentValues finished = new ContentValues();
            finished.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, finished, null, null) != 1) throw new IOException("Gallery did not accept the photo");
        } catch (IOException | RuntimeException failure) {
            resolver.delete(item, null, null);
            throw failure;
        }
        return item;
    }

    static byte[] importImage(ContentResolver resolver, Uri uri) throws Exception {
        byte[] source;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Image unavailable");
            source = AndroidVault.boundedRead(input, 20 * 1024 * 1024);
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 40_000_000)
                throw new IOException("Unsupported image dimensions");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 1600 || bounds.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (bitmap == null) throw new IOException("Invalid image");
            try {
                int orientation = new ExifInterface(new ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                Matrix rotation = new Matrix();
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation.postRotate(90);
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation.postRotate(180);
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation.postRotate(270);
                Bitmap upright = rotation.isIdentity() ? bitmap : Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
                try { return encode(upright); }
                finally { if (upright != bitmap) upright.recycle(); }
            } finally { bitmap.recycle(); }
        } finally { Arrays.fill(source, (byte) 0); }
    }

    static byte[] encode(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes) || bytes.size() > ImageCipher.MAX_IMAGE_BYTES)
            throw new IOException("Image exceeds encrypted upload limit");
        return bytes.toByteArray();
    }

    static byte[] profilePhoto(byte[] source) throws IOException {
        Bitmap original = display(source);
        Bitmap square = null;
        Bitmap thumbnail = null;
        try {
            int side = Math.min(original.getWidth(), original.getHeight());
            square = Bitmap.createBitmap(original, (original.getWidth() - side) / 2, (original.getHeight() - side) / 2, side, side);
            thumbnail = Bitmap.createScaledBitmap(square, 256, 256, true);
            for (int quality = 85; quality >= 25; quality -= 15) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) throw new IOException("Profile photo encoding failed");
                if (bytes.size() <= ProfileEnvelope.MAX_PHOTO_BYTES) return bytes.toByteArray();
            }
            throw new IOException("Profile photo exceeds size limit");
        } finally {
            if (thumbnail != null && thumbnail != square && thumbnail != original) thumbnail.recycle();
            if (square != null && square != original) square.recycle();
            original.recycle();
        }
    }

    static Bitmap displayProfilePhoto(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > ProfileEnvelope.MAX_PHOTO_BYTES) throw new IOException("Invalid profile photo size");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!"image/jpeg".equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outWidth > 256 || bounds.outHeight != bounds.outWidth)
            throw new IOException("Invalid profile photo dimensions");
        return display(bytes);
    }

    static Bitmap display(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > ImageCipher.MAX_IMAGE_BYTES) throw new IOException("Invalid image size");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 4_000_000)
            throw new IOException("Invalid image dimensions");
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IOException("Invalid image");
        return bitmap;
    }
}