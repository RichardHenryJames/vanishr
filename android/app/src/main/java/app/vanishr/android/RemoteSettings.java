package app.vanishr.android;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import okhttp3.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Settings that can change without a new app version: a notice, a few switches that can only turn an existing feature off, and a
 * hint that a newer version is on Google Play. They are data. Nothing downloaded here is ever run, and nothing in it can reach a
 * key, a message, a deadline or a trust decision: the app only looks at the fixed fields below, with fixed limits.
 *
 * The file on the download website is signed with a key that is kept off the website; the app carries only the public half and
 * accepts nothing else, so whoever can change the website still cannot change these settings. A newer file must have been issued
 * later (than any file taken before) and have a higher sequence (than any taken before from the same key), so an older signed file
 * cannot be put back, and every file expires, after which the app behaves as if it had none.
 */
final class RemoteSettings implements AutoCloseable {
    static final String URL = AppUpdates.ORIGIN + "/ota/settings.json";
    static final int MAX_ENVELOPE = 12_288, MAX_PAYLOAD = 8_192;
    static final long CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24);
    static final long RETRY_INTERVAL = TimeUnit.HOURS.toMillis(2);
    static final long MAX_VALIDITY = TimeUnit.DAYS.toMillis(60);
    static final long CLOCK_SKEW = TimeUnit.HOURS.toMillis(36);
    // When the newest signed file that existed was issued, as of cutting this version. A file issued before it is refused, so that an older
    // signed file (a copy somebody kept) cannot be offered to a fresh install. `scripts\sign-ota-settings.ps1 -Check` prints the value to use.
    static final long BUILD_ISSUED_FLOOR = 1_792_218_486_339L;
    // A sequence is a counter, not a date: nothing sensible gets near this, and a mistake or a stolen key cannot use up all of the numbers.
    static final long MAX_SEQUENCE = 1_000_000;
    static final int MAX_NEWLINES = 6;
    private static final long MAX_NUMBER = 9_007_199_254_740_991L, EARLIEST = 1_600_000_000_000L, LATEST = 4_102_444_800_000L;
    // A page on the download site and nothing else: no dots (so no file such as the APK and no dot segments), no empty segment, no query.
    private static final java.util.regex.Pattern PAGE = java.util.regex.Pattern.compile("/|/[A-Za-z0-9_-]{1,40}(/[A-Za-z0-9_-]{1,40}){0,3}/?");
    // Keeps a signature made for these settings from being valid for anything else the same key might ever sign.
    private static final byte[] DOMAIN = "vanishr-ota-settings-v1\0".getBytes(StandardCharsets.US_ASCII);

    /** Features the owner can turn off. A feature stays on unless the signed settings name it with false. */
    enum Flag {
        IMAGES("images", "Sending images is turned off for now. Text messages still work."),
        PHOTOS("photos", "Opening photos is turned off for now."),
        GROUPS("groups", "Creating groups is turned off for now.");

        final String key, refusal;
        Flag(String key, String refusal) { this.key = key; this.refusal = refusal; }
        static Flag of(String key) {
            for (Flag flag : values()) if (flag.key.equals(key)) return flag;
            return null;
        }
    }

    enum Build {
        ALL, PLAY, DIRECT;
        boolean matches(boolean play) { return this == ALL || (this == PLAY) == play; }
    }

    enum Nudge { NONE, AVAILABLE, UNSUPPORTED }

    enum Reason { MALFORMED, SIZE, KEY, SIGNATURE, SCHEMA, FIELD, NOT_YET_VALID, EXPIRED }

    /** Carries only why a file was refused, never any of its content. */
    static final class Rejected extends IOException {
        final Reason reason;
        Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
    }

    record Notice(String id, String title, String body, boolean important, String path, Build build, int minVersionCode, int maxVersionCode) { }

    record Update(int latestVersionCode, String latestVersionName, int minSupportedVersionCode) { }

    /** keyId is the key that signed it: a sequence is only compared with earlier files of the same key, so a retired key cannot block its successor. */
    record Settings(String keyId, long sequence, long issuedAt, long expiresAt, Set<Flag> disabled, Notice notice, Update update) {
        static final Settings NONE = new Settings("", 0, 0, Long.MAX_VALUE, Set.of(), null, null);
        boolean enabled(Flag flag) { return !disabled.contains(flag); }
        boolean present() { return sequence > 0; }
    }

    private final OkHttpClient client;

    RemoteSettings() { this(new OkHttpClient()); }

    RemoteSettings(OkHttpClient transport) {
        client = transport.newBuilder().connectionSpecs(List.of(ConnectionSpec.MODERN_TLS))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build();
    }

    /** One bounded request to the fixed download host: no cookies, account data or identifiers, and no redirects. */
    byte[] fetch() throws IOException {
        Request request = new Request.Builder().url(URL).header("Accept", "application/json")
                .header("Cache-Control", "no-store").get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (response.code() != 200 || body == null || body.contentLength() > MAX_ENVELOPE
                    || body.contentType() == null || !body.contentType().type().equals("application")
                    || !body.contentType().subtype().equals("json")) throw new IOException("Settings unavailable");
            var source = body.source();
            source.request(MAX_ENVELOPE + 1L);
            if (source.getBuffer().size() > MAX_ENVELOPE) throw new IOException("Settings exceed the limit");
            return source.getBuffer().readByteArray();
        }
    }

    // ---- verification: pure functions, no Android classes ----

    /** The trusted public keys, one "keyId base64-of-X.509-SubjectPublicKeyInfo" per line; P-256 only, at most three. */
    static Map<String, PublicKey> keys(String text) throws GeneralSecurityException {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        KeyFactory factory = KeyFactory.getInstance("EC");
        for (String line : text.split("\\R")) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) continue;
            String[] parts = entry.split("\\s+");
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,16}") || keys.containsKey(parts[0]) || keys.size() >= 3)
                throw new GeneralSecurityException("Invalid settings key list");
            PublicKey key;
            try { key = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1]))); }
            catch (IllegalArgumentException malformed) { throw new GeneralSecurityException("Invalid settings key list"); }
            if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256)
                throw new GeneralSecurityException("Invalid settings key list");
            keys.put(parts[0], key);
        }
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Checks the envelope {"v":1,"key":id,"payload":base64url,"sig":base64url}: the signature covers a fixed prefix plus the exact
     * payload bytes, so nothing has to be re-encoded before it is checked. Anything unexpected refuses the whole file.
     */
    static Settings verify(byte[] envelope, Map<String, PublicKey> trusted, long now) throws Rejected {
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
        return payload(keyId, payload, now);
    }

    private static Settings payload(String keyId, byte[] bytes, long now) throws Rejected {
        long schema = -1, sequence = -1, issuedAt = -1, expiresAt = -1;
        Set<Flag> disabled = Set.of();
        Notice notice = null;
        Update update = null;
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
                    case "issuedAt" -> issuedAt = integer(reader);
                    case "expiresAt" -> expiresAt = integer(reader);
                    case "features" -> disabled = features(reader);
                    case "notice" -> { if (reader.peek() == JsonToken.NULL) reader.nextNull(); else notice = notice(reader); }
                    case "update" -> { if (reader.peek() == JsonToken.NULL) reader.nextNull(); else update = update(reader); }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Rejected(Reason.MALFORMED);
        } catch (Rejected rejected) { throw rejected; }
        catch (IOException | RuntimeException malformed) { throw new Rejected(Reason.MALFORMED); }
        if (schema != 1) throw new Rejected(Reason.SCHEMA);
        if (sequence < 1 || sequence > MAX_SEQUENCE || issuedAt < EARLIEST || expiresAt > LATEST || expiresAt <= issuedAt || expiresAt - issuedAt > MAX_VALIDITY)
            throw new Rejected(Reason.FIELD);
        if (issuedAt > now + CLOCK_SKEW) throw new Rejected(Reason.NOT_YET_VALID);
        if (expiresAt <= now) throw new Rejected(Reason.EXPIRED);
        return new Settings(keyId, sequence, issuedAt, expiresAt, disabled, notice, update);
    }

    private static Set<Flag> features(JsonReader reader) throws IOException {
        Set<String> seen = new HashSet<>();
        Set<Flag> disabled = EnumSet.noneOf(Flag.class);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seen.add(name) || seen.size() > 64) throw new Rejected(Reason.MALFORMED);
            Flag flag = Flag.of(name);
            if (flag == null) { reader.skipValue(); continue; }
            if (reader.peek() != JsonToken.BOOLEAN) throw new Rejected(Reason.FIELD);
            if (!reader.nextBoolean()) disabled.add(flag);
        }
        reader.endObject();
        return Collections.unmodifiableSet(disabled);
    }

    private static Notice notice(JsonReader reader) throws IOException {
        String id = null, title = null, body = null, level = null, path = null, build = "all";
        long min = 0, max = Integer.MAX_VALUE;
        Set<String> seen = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seen.add(name)) throw new Rejected(Reason.MALFORMED);
            switch (name) {
                case "id" -> id = string(reader);
                case "title" -> title = string(reader);
                case "body" -> body = string(reader);
                case "level" -> level = string(reader);
                case "path" -> path = string(reader);
                case "build" -> build = string(reader);
                case "minVersionCode" -> min = integer(reader);
                case "maxVersionCode" -> max = integer(reader);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (id == null || title == null || body == null || level == null || !id.matches("[A-Za-z0-9._-]{1,40}")
                || !(level.equals("info") || level.equals("important")) || min > max || max > Integer.MAX_VALUE
                || !text(title, 60, false) || !text(body, 400, true)
                || (path != null && !PAGE.matcher(path).matches()))
            throw new Rejected(Reason.FIELD);
        Build target = switch (build) {
            case "all" -> Build.ALL;
            case "play" -> Build.PLAY;
            case "direct" -> Build.DIRECT;
            default -> throw new Rejected(Reason.FIELD);
        };
        return new Notice(id, title, body, level.equals("important"), path, target, (int) min, (int) max);
    }

    private static Update update(JsonReader reader) throws IOException {
        long latest = -1, minimum = -1;
        String name = null;
        Set<String> seen = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!seen.add(field)) throw new Rejected(Reason.MALFORMED);
            switch (field) {
                case "latestVersionCode" -> latest = integer(reader);
                case "latestVersionName" -> name = string(reader);
                case "minSupportedVersionCode" -> minimum = integer(reader);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (latest < 1 || latest > Integer.MAX_VALUE || minimum < 0 || minimum > latest || name == null
                || name.length() > 20 || !name.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new Rejected(Reason.FIELD);
        return new Update((int) latest, name, (int) minimum);
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

    /**
     * Plain, visible text: no controls, no line or paragraph separators, nothing invisible or direction-changing (the "format"
     * characters: zero-width and bidirectional marks, soft hyphens, tag characters; only the two joiners that emoji and some scripts need
     * are kept), no private-use or lone surrogate code points, no noncharacters, and at most MAX_NEWLINES line breaks so that a notice
     * stays a short note. Whether a code point is assigned is deliberately not asked: that depends on the Unicode version of the phone's
     * Android, and one newer character in a notice would make older phones refuse the whole file, switches included.
     */
    static boolean text(String value, int maximum, boolean newlines) {
        if (value.isEmpty() || value.length() > maximum) return false;
        int breaks = 0;
        for (int index = 0; index < value.length(); ) {
            int point = value.codePointAt(index);
            index += Character.charCount(point);
            if (point == '\n') {
                if (!newlines || ++breaks > MAX_NEWLINES) return false;
                continue;
            }
            if (!visible(point)) return false;
        }
        return !value.isBlank();
    }

    private static boolean visible(int point) {
        if (point == 0x200C || point == 0x200D) return true;
        if (point == 0xFFFD || (point >= 0xFDD0 && point <= 0xFDEF) || (point & 0xFFFE) == 0xFFFE) return false;
        return switch (Character.getType(point)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.PRIVATE_USE, Character.SURROGATE -> false;
            default -> true;
        };
    }

    // ---- what the settings mean for this build ----

    static Notice noticeFor(Settings settings, int versionCode, boolean play) {
        Notice notice = settings.notice();
        if (notice == null || !notice.build().matches(play) || versionCode < notice.minVersionCode() || versionCode > notice.maxVersionCode())
            return null;
        return notice;
    }

    /** Only Google Play builds are nudged; the direct-download build has its own update prompt. */
    static Nudge nudge(Settings settings, int installedVersionCode, boolean play) {
        Update update = settings.update();
        if (!play || update == null) return Nudge.NONE;
        if (installedVersionCode < update.minSupportedVersionCode()) return Nudge.UNSUPPORTED;
        return installedVersionCode < update.latestVersionCode() ? Nudge.AVAILABLE : Nudge.NONE;
    }

    /**
     * A file is only taken if it was issued no earlier than any file taken before, whichever key signed it, and its sequence is above
     * every one taken before from the same key. So an older signed file can never be put back, not even one of another listed key.
     */
    static boolean newer(Settings candidate, long keyFloor, long newestIssued) {
        return candidate.sequence() > keyFloor && candidate.issuedAt() >= Math.max(newestIssued, BUILD_ISSUED_FLOOR);
    }

    static boolean due(long now, long lastCheck) {
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= CHECK_INTERVAL;
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
