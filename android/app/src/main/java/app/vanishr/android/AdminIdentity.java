package app.vanishr.android;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A statement, signed by the owner, of which device identity the official admin account has now. The admin's identity is pinned in the
 * app ({@link AdminOnboarding#OFFICIAL}); when the owner changes phone the identity changes, and a phone that only knows the old pin
 * refuses the new one. This file is how the pin moves without a new app version, and it can move nothing else: it names the device and
 * the identity of the same account on the same relay, never another account, a relay address, a setting or a person.
 *
 * It is signed with a key that is not the one that signs the over-the-air settings (those can never touch a trust decision) and that is
 * kept offline; the app carries only the public half, in res/raw/admin_keys.txt. Whoever holds that key can name a new admin identity,
 * but the relay still has to present exactly that identity for the admin account, so a stolen key alone is not enough, and the relay
 * alone, or the website alone, is not either.
 *
 * Only a certificate with a higher sequence than every one taken before is used, so an older one cannot be put back. There is no expiry:
 * an expiry would end the admin's identity for everyone on the day it was forgotten. A phone only asks for the file when the identity
 * the relay shows for the admin is not the pinned one (see MainActivity), so nothing is fetched while nothing has changed.
 */
final class AdminIdentity {
    static final String URL = AppUpdates.ORIGIN + "/ota/admin-identity.json";
    static final int MAX_ENVELOPE = 2_048, MAX_PAYLOAD = 1_024;
    // A counter, not a date: nothing sensible gets near this, and a stolen key cannot use up all of the numbers.
    static final long MAX_SEQUENCE = 1_000_000;
    // The sequence of the certificate whose identity is the built-in pin. Anything at or below it is already known to this build, so only
    // a higher one can change anything. `scripts\sign-admin-identity.ps1 -Check -AppRelease` stops a release where the two disagree.
    static final long BUILD_SEQUENCE_FLOOR = 3;
    private static final long MAX_NUMBER = 9_007_199_254_740_991L;
    // Keeps a signature made for this purpose from being valid for anything else the same key might sign.
    private static final byte[] DOMAIN = "vanishr-admin-identity-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    enum Reason { MALFORMED, SIZE, KEY, SIGNATURE, SCHEMA, FIELD, ACCOUNT }

    /** Carries only why a file was refused, never any of its content. */
    static final class Rejected extends IOException {
        final Reason reason;
        Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
    }

    /** What was certified: the device and the identity (as the same SHA-256 fingerprint the pin holds) of the admin account. */
    record Certificate(String keyId, long sequence, UUID userId, UUID deviceId, String fingerprint) {
        AdminOnboarding.Pin pin(String origin) { return new AdminOnboarding.Pin(origin, userId, deviceId, fingerprint); }
    }

    private AdminIdentity() { }

    /**
     * Checks the envelope {"v":1,"key":id,"payload":base64url,"sig":base64url}: the signature covers a fixed prefix plus the exact payload
     * bytes, and the payload must name {@code admin}, the account the app already pins. Anything unexpected refuses the whole file.
     */
    static Certificate verify(byte[] envelope, Map<String, PublicKey> trusted, UUID admin) throws Rejected {
        if (envelope.length == 0 || envelope.length > MAX_ENVELOPE) throw new Rejected(Reason.SIZE);
        long version = -1;
        String keyId = null, payloadText = null, signatureText = null;
        try (JsonReader reader = new JsonReader(new StringReader(new String(envelope, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            Set<String> seen = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) throw new Rejected(Reason.MALFORMED);
                switch (name) {
                    case "v" -> version = integer(reader);
                    case "key" -> keyId = string(reader);
                    case "payload" -> payloadText = string(reader);
                    case "sig" -> signatureText = string(reader);
                    default -> throw new Rejected(Reason.MALFORMED);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Rejected(Reason.MALFORMED);
        } catch (Rejected rejected) { throw rejected; }
        catch (IOException | RuntimeException malformed) { throw new Rejected(Reason.MALFORMED); }
        if (version != 1 || keyId == null || payloadText == null || signatureText == null || !keyId.matches("[A-Za-z0-9_-]{1,16}"))
            throw new Rejected(Reason.MALFORMED);
        PublicKey key = trusted.get(keyId);
        if (key == null) throw new Rejected(Reason.KEY);
        byte[] payload, signature;
        try {
            payload = Base64.getUrlDecoder().decode(payloadText);
            signature = Base64.getUrlDecoder().decode(signatureText);
        } catch (IllegalArgumentException malformed) { throw new Rejected(Reason.MALFORMED); }
        if (payload.length == 0 || payload.length > MAX_PAYLOAD) throw new Rejected(Reason.SIZE);
        if (signature.length < 8 || signature.length > 80) throw new Rejected(Reason.SIGNATURE);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(DOMAIN);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new Rejected(Reason.SIGNATURE);
        } catch (GeneralSecurityException failure) { throw new Rejected(Reason.SIGNATURE); }
        return payload(keyId, payload, admin);
    }

    private static Certificate payload(String keyId, byte[] bytes, UUID admin) throws Rejected {
        long schema = -1, sequence = -1;
        String user = null, device = null, fingerprint = null;
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            Set<String> seen = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) throw new Rejected(Reason.MALFORMED);
                switch (name) {
                    case "schema" -> schema = integer(reader);
                    case "sequence" -> sequence = integer(reader);
                    case "userId" -> user = string(reader);
                    case "deviceId" -> device = string(reader);
                    case "fingerprint" -> fingerprint = string(reader);
                    default -> throw new Rejected(Reason.MALFORMED);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Rejected(Reason.MALFORMED);
        } catch (Rejected rejected) { throw rejected; }
        catch (IOException | RuntimeException malformed) { throw new Rejected(Reason.MALFORMED); }
        if (schema != 1) throw new Rejected(Reason.SCHEMA);
        if (sequence < 1 || sequence > MAX_SEQUENCE || user == null || device == null || fingerprint == null
                || !user.matches(UUID_TEXT) || !device.matches(UUID_TEXT) || !fingerprint.matches("[0-9a-f]{64}"))
            throw new Rejected(Reason.FIELD);
        UUID account = UUID.fromString(user);
        if (!account.equals(admin)) throw new Rejected(Reason.ACCOUNT);
        return new Certificate(keyId, sequence, account, UUID.fromString(device), fingerprint);
    }

    /**
     * Taken only if it is above this build's floor, not below the highest number ever taken ({@code highWater}, which the store keeps even
     * when its copy of that certificate can no longer be verified, so that a damaged copy does not let an older certificate back in), and
     * above the stored certificate when there is a usable one. The same number as one that was taken before is allowed only when that copy
     * cannot be used, so that a damaged copy can be replaced by the certificate it was.
     */
    static boolean newer(Certificate candidate, Certificate stored, long highWater) {
        long sequence = candidate.sequence();
        return sequence > BUILD_SEQUENCE_FLOOR && sequence >= highWater && (stored == null || sequence > stored.sequence());
    }

    private static long integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new Rejected(Reason.FIELD);
        String literal = reader.nextString();
        if (!literal.matches("0|[1-9][0-9]{0,15}")) throw new Rejected(Reason.FIELD);
        long value = Long.parseLong(literal);
        if (value > MAX_NUMBER) throw new Rejected(Reason.FIELD);
        return value;
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new Rejected(Reason.FIELD);
        return reader.nextString();
    }
}
