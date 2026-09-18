package app.vanishr.android;

import android.content.ContentResolver;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import app.vanishr.crypto.ImageCipher;

import java.io.*;
import java.util.Arrays;

final class SafeImages {
    private SafeImages() { }

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