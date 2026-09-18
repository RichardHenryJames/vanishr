package app.vanishr.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

public final class ImageCipher {
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    public record EncryptedImage(byte[] ciphertext, byte[] key, byte[] nonce) { }

    private ImageCipher() { }

    private static byte[] context(UUID mediaId) {
        return ("vanishr/image/v1/" + mediaId).getBytes(StandardCharsets.US_ASCII);
    }

    public static EncryptedImage encrypt(UUID mediaId, byte[] image) throws GeneralSecurityException {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Invalid image size");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        byte[] key = generator.generateKey().getEncoded();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context(mediaId));
        return new EncryptedImage(cipher.doFinal(image), key, nonce);
    }

    public static byte[] decrypt(UUID mediaId, byte[] ciphertext, byte[] key, byte[] nonce) throws GeneralSecurityException {
        if (key.length != 32 || nonce.length != 12 || ciphertext.length < 17 || ciphertext.length > MAX_IMAGE_BYTES + 16)
            throw new IllegalArgumentException("Invalid encrypted image");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context(mediaId));
        return cipher.doFinal(ciphertext);
    }
}