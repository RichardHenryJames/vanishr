package app.vanishr.android;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static app.vanishr.android.RemoteSettings.Reason.*;
import static org.junit.Assert.*;

public class RemoteSettingsTest {
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    // Always above what this build refuses as too old, whenever the floor is raised, so the tests do not depend on today's date.
    private static final long NOW = Math.max(1_800_000_000_000L, RemoteSettings.BUILD_ISSUED_FLOOR + 30 * DAY);
    // Restated here on purpose: this is the protocol, and the test should not just echo the implementation's own constant.
    private static final byte[] DOMAIN = "vanishr-ota-settings-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String BASIC_NOTICE = "\"id\":\"2026-10-a\",\"title\":\"Hello\",\"body\":\"Something to know.\",\"level\":\"info\"";

    private static final class Signer {
        final String id;
        final KeyPair pair;

        Signer(String id, String curve) throws Exception {
            this.id = id;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            pair = generator.generateKeyPair();
        }

        Signer(String id) throws Exception { this(id, "secp256r1"); }

        Map<String, PublicKey> trusted() { return Map.of(id, pair.getPublic()); }

        byte[] sign(byte[] payload, boolean withDomain) throws Exception {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            if (withDomain) signature.update(DOMAIN);
            signature.update(payload);
            return signature.sign();
        }

        byte[] envelope(String payload) throws Exception {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            return RemoteSettingsTest.envelope(id, bytes, sign(bytes, true));
        }
    }

    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    private static byte[] envelope(String key, byte[] payload, byte[] signature) {
        return ("{\"v\":1,\"key\":\"" + key + "\",\"payload\":\"" + b64(payload) + "\",\"sig\":\"" + b64(signature) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String payload(String extra) { return payload(1, NOW - HOUR, NOW + 10 * DAY, extra); }

    private static String payload(long sequence, long issued, long expires, String extra) {
        return "{\"schema\":1,\"sequence\":" + sequence + ",\"issuedAt\":" + issued + ",\"expiresAt\":" + expires + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static void rejected(RemoteSettings.Reason reason, byte[] envelope, Map<String, PublicKey> trusted) {
        RemoteSettings.Rejected failure = assertThrows(RemoteSettings.Rejected.class, () -> RemoteSettings.verify(envelope, trusted, NOW));
        assertEquals(reason, failure.reason);
    }

    private static void rejectedPayload(RemoteSettings.Reason reason, String payload) throws Exception {
        Signer signer = new Signer("k1");
        rejected(reason, signer.envelope(payload), signer.trusted());
    }

    private static RemoteSettings.Settings accepted(String payload) throws Exception {
        Signer signer = new Signer("k1");
        return RemoteSettings.verify(signer.envelope(payload), signer.trusted(), NOW);
    }

    // ---- a good file ----

    @Test public void aSignedFileIsAcceptedAndReadBackExactly() throws Exception {
        RemoteSettings.Settings settings = accepted(payload("\"features\":{\"images\":false,\"photos\":true,\"someFutureSwitch\":false},"
                + "\"notice\":{" + BASIC_NOTICE + ",\"path\":\"/security/\",\"build\":\"play\",\"minVersionCode\":40,\"maxVersionCode\":50},"
                + "\"update\":{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":40},"
                + "\"aFieldANewerAppUnderstands\":{\"nested\":[1,2,3]}"));
        assertEquals(1, settings.sequence());
        assertEquals("The settings know which key signed them", "k1", settings.keyId());
        assertEquals(NOW - HOUR, settings.issuedAt());
        assertEquals(NOW + 10 * DAY, settings.expiresAt());
        assertFalse(settings.enabled(RemoteSettings.Flag.IMAGES));
        assertTrue(settings.enabled(RemoteSettings.Flag.PHOTOS));
        assertTrue("A feature the file does not mention stays on", settings.enabled(RemoteSettings.Flag.GROUPS));
        assertEquals(new RemoteSettings.Notice("2026-10-a", "Hello", "Something to know.", false, "/security/", RemoteSettings.Build.PLAY, 40, 50), settings.notice());
        assertEquals(new RemoteSettings.Update(43, "0.6.2", 40), settings.update());
    }

    @Test public void aFileWithoutAnythingInItChangesNothing() throws Exception {
        RemoteSettings.Settings settings = accepted(payload("\"features\":{},\"notice\":null,\"update\":null"));
        for (RemoteSettings.Flag flag : RemoteSettings.Flag.values()) assertTrue(settings.enabled(flag));
        assertNull(settings.notice());
        assertNull(settings.update());
        RemoteSettings.Settings bare = accepted(payload(""));
        assertTrue(bare.present());
        assertFalse(RemoteSettings.Settings.NONE.present());
        for (RemoteSettings.Flag flag : RemoteSettings.Flag.values()) assertTrue(RemoteSettings.Settings.NONE.enabled(flag));
    }

    @Test public void aFeatureCanOnlyBeSwitchedOffAndOnlyByName() throws Exception {
        RemoteSettings.Settings settings = accepted(payload("\"features\":{\"groups\":false,\"notAFeatureThisAppHas\":false}"));
        assertFalse(settings.enabled(RemoteSettings.Flag.GROUPS));
        assertEquals(java.util.Set.of(RemoteSettings.Flag.GROUPS), settings.disabled());
        assertEquals(RemoteSettings.Flag.IMAGES, RemoteSettings.Flag.of("images"));
        assertNull(RemoteSettings.Flag.of("Images"));
        assertNull(RemoteSettings.Flag.of("notAFeatureThisAppHas"));
        // Naming a feature with true is not a way to touch it: only false does anything.
        RemoteSettings.Settings on = accepted(payload("\"features\":{\"images\":true,\"photos\":true,\"groups\":true}"));
        assertTrue(on.disabled().isEmpty());
        for (RemoteSettings.Flag flag : RemoteSettings.Flag.values()) assertTrue(flag.key, on.enabled(flag));
    }

    // ---- the signature ----

    @Test public void aChangedPayloadOrSignatureIsRefused() throws Exception {
        Signer signer = new Signer("k1");
        byte[] payload = payload("\"features\":{\"images\":true}").getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(payload, true);
        assertNotNull(RemoteSettings.verify(envelope("k1", payload, signature), signer.trusted(), NOW));
        byte[] changed = payload.clone();
        changed[changed.length - 3] ^= 1;
        rejected(SIGNATURE, envelope("k1", changed, signature), signer.trusted());
        byte[] other = signature.clone();
        other[other.length - 1] ^= 1;
        rejected(SIGNATURE, envelope("k1", payload, other), signer.trusted());
        rejected(SIGNATURE, envelope("k1", payload, new byte[40]), signer.trusted());
        rejected(SIGNATURE, envelope("k1", payload, new byte[3]), signer.trusted());
        rejected(SIGNATURE, envelope("k1", payload, new byte[200]), signer.trusted());
    }

    @Test public void aFileFromAnotherKeyOrNamingAnUnknownKeyIsRefused() throws Exception {
        Signer owner = new Signer("k1"), attacker = new Signer("k1");
        rejected(SIGNATURE, attacker.envelope(payload("")), owner.trusted());
        rejected(KEY, new Signer("k2").envelope(payload("")), owner.trusted());
        rejected(KEY, owner.envelope(payload("")), Map.of());
        rejected(MALFORMED, new Signer("k$").envelope(payload("")), owner.trusted());
        Signer rotated = new Signer("k2");
        Map<String, PublicKey> both = Map.of("k1", owner.pair.getPublic(), "k2", rotated.pair.getPublic());
        assertNotNull(RemoteSettings.verify(owner.envelope(payload("")), both, NOW));
        assertNotNull("Either listed key may sign", RemoteSettings.verify(rotated.envelope(payload("")), both, NOW));
        rejected(SIGNATURE, envelope("k1", payload("").getBytes(StandardCharsets.UTF_8), rotated.sign(payload("").getBytes(StandardCharsets.UTF_8), true)), both);
    }

    @Test public void theSignatureCoversAFixedPrefixSoItIsOnlyValidForSettings() throws Exception {
        Signer signer = new Signer("k1");
        byte[] payload = payload("").getBytes(StandardCharsets.UTF_8);
        rejected(SIGNATURE, envelope("k1", payload, signer.sign(payload, false)), signer.trusted());
        assertNotNull(RemoteSettings.verify(envelope("k1", payload, signer.sign(payload, true)), signer.trusted(), NOW));
    }

    // ---- time and order ----

    @Test public void expiredNotYetValidAndTooLongLivedFilesAreRefused() throws Exception {
        rejectedPayload(EXPIRED, payload(1, NOW - 5 * DAY, NOW - 1, ""));
        rejectedPayload(EXPIRED, payload(1, NOW - 5 * DAY, NOW, ""));
        rejectedPayload(NOT_YET_VALID, payload(1, NOW + RemoteSettings.CLOCK_SKEW + 1, NOW + 10 * DAY, ""));
        assertNotNull(accepted(payload(1, NOW + RemoteSettings.CLOCK_SKEW, NOW + 10 * DAY, "")));
        rejectedPayload(FIELD, payload(1, NOW - HOUR, NOW + RemoteSettings.MAX_VALIDITY, ""));
        assertNotNull(accepted(payload(1, NOW - HOUR, NOW - HOUR + RemoteSettings.MAX_VALIDITY, "")));
        rejectedPayload(FIELD, payload(1, NOW - HOUR, NOW - HOUR, ""));
        rejectedPayload(FIELD, payload(1, NOW - HOUR, NOW - 2 * HOUR, ""));
        rejectedPayload(FIELD, payload(1, 1_000, NOW + DAY, ""));
        rejectedPayload(FIELD, payload(1, NOW - HOUR, 4_102_444_800_001L, ""));
    }

    @Test public void aFileIsOnlyTakenOverOneWithALowerSequenceFromTheSameKeyAndNeverOverANewerIssueTime() throws Exception {
        RemoteSettings.Settings five = accepted(payload(5, NOW - HOUR, NOW + DAY, ""));
        assertTrue(RemoteSettings.newer(five, 4, 0));
        assertFalse("The same file again", RemoteSettings.newer(five, 5, 0));
        assertFalse("An older signed file put back", RemoteSettings.newer(five, 6, 0));
        assertTrue(RemoteSettings.newer(five, 0, 0));
        assertTrue("Issued at the very time of the newest file taken: the sequence decides", RemoteSettings.newer(five, 4, NOW - HOUR));
        assertFalse("Issued a millisecond before the newest file taken, by whichever key", RemoteSettings.newer(five, 4, NOW - HOUR + 1));
        RemoteSettings.Settings first = accepted(payload(1, NOW - HOUR, NOW + DAY, ""));
        assertTrue(RemoteSettings.newer(first, 0, 0));
        assertFalse(RemoteSettings.newer(RemoteSettings.Settings.NONE, 0, 0));
        // This build's own floor: nothing issued before it is ever taken, however high its sequence or however little was taken before.
        Signer signer = new Signer("k1");
        long floor = RemoteSettings.BUILD_ISSUED_FLOOR;
        RemoteSettings.Settings before = RemoteSettings.verify(signer.envelope(payload(9, floor - 1, floor + 10 * DAY, "")), signer.trusted(), floor);
        RemoteSettings.Settings at = RemoteSettings.verify(signer.envelope(payload(9, floor, floor + 10 * DAY, "")), signer.trusted(), floor);
        assertFalse("Issued a millisecond before this build's floor", RemoteSettings.newer(before, 0, 0));
        assertTrue("Issued at it", RemoteSettings.newer(at, 0, 0));
        assertTrue(RemoteSettings.newer(at, 0, floor - DAY));
        rejectedPayload(FIELD, payload(0, NOW - HOUR, NOW + DAY, ""));
        // A counter, not a date: the highest number there is cannot be exceeded, so no file can use all of the numbers up.
        assertNotNull(accepted(payload(RemoteSettings.MAX_SEQUENCE, NOW - HOUR, NOW + DAY, "")));
        rejectedPayload(FIELD, payload(RemoteSettings.MAX_SEQUENCE + 1, NOW - HOUR, NOW + DAY, ""));
        rejectedPayload(FIELD, payload(9_007_199_254_740_991L, NOW - HOUR, NOW + DAY, ""));
    }

    @Test public void theDailyCheckIsDueOnlyOncePerInterval() {
        assertTrue(RemoteSettings.due(NOW, 0));
        assertFalse(RemoteSettings.due(NOW, NOW - 1000));
        assertFalse(RemoteSettings.due(NOW, NOW - RemoteSettings.CHECK_INTERVAL + 1));
        assertTrue(RemoteSettings.due(NOW, NOW - RemoteSettings.CHECK_INTERVAL));
        assertTrue("A clock that moved back", RemoteSettings.due(NOW, NOW + 1000));
    }

    // ---- shape ----

    @Test public void malformedEnvelopesAreRefusedWhole() throws Exception {
        Signer signer = new Signer("k1");
        String payload = b64(payload("").getBytes(StandardCharsets.UTF_8));
        String signature = b64(signer.sign(payload("").getBytes(StandardCharsets.UTF_8), true));
        for (String text : List.of("", "{", "[]", "null", "{\"v\":1}", "not json",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\"}",
                "{\"v\":2,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":\"1\",\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\",\"extra\":1}",
                "{\"v\":1,\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"!!!\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"???\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"} trailing",
                "{\"v\":1,\"key\":\"k1\",\"payload\":7,\"sig\":\"" + signature + "\"}",
                // Not strict JSON: single quotes, bare names or values, comments and a second document are all refused.
                "{'v':1,'key':'k1','payload':'" + payload + "','sig':'" + signature + "'}",
                "{v:1,key:\"k1\",payload:\"" + payload + "\",sig:\"" + signature + "\"}",
                "{\"v\":1,\"key\":k1,\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,/* note */\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"} // note",
                "{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}{}")) {
            RemoteSettings.Rejected failure = assertThrows(text, RemoteSettings.Rejected.class,
                    () -> RemoteSettings.verify(text.getBytes(StandardCharsets.UTF_8), signer.trusted(), NOW));
            assertTrue(text, failure.reason == MALFORMED || failure.reason == SIZE || failure.reason == FIELD);
        }
        assertNotNull(RemoteSettings.verify(("{\"v\":1,\"key\":\"k1\",\"payload\":\"" + payload + "\",\"sig\":\"" + signature + "\"}\n")
                .getBytes(StandardCharsets.UTF_8), signer.trusted(), NOW));
    }

    @Test public void sizeLimitsApplyToTheEnvelopeAndThePayload() throws Exception {
        Signer signer = new Signer("k1");
        rejected(SIZE, new byte[0], signer.trusted());
        rejected(SIZE, new byte[RemoteSettings.MAX_ENVELOPE + 1], signer.trusted());
        String big = payload("\"notice\":{" + BASIC_NOTICE + "},\"padding\":\"" + "a".repeat(RemoteSettings.MAX_PAYLOAD) + "\"");
        rejected(SIZE, signer.envelope(big), signer.trusted());
        String fits = payload("\"padding\":\"" + "a".repeat(RemoteSettings.MAX_PAYLOAD - 200) + "\"");
        assertNotNull(RemoteSettings.verify(signer.envelope(fits), signer.trusted(), NOW));
    }

    @Test public void malformedPayloadsAreRefusedWhole() throws Exception {
        rejectedPayload(MALFORMED, "[]");
        rejectedPayload(MALFORMED, "7");
        rejectedPayload(MALFORMED, "{\"schema\":1,\"schema\":1,\"sequence\":1,\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY) + "}");
        rejectedPayload(MALFORMED, payload("") + " trailing");
        rejectedPayload(MALFORMED, payload("\"features\":{\"images\":true,\"images\":false}"));
        rejectedPayload(MALFORMED, payload("\"features\":[]"));
        rejectedPayload(SCHEMA, "{\"schema\":2,\"sequence\":1,\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY) + "}");
        rejectedPayload(SCHEMA, "{\"sequence\":1,\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY) + "}");
        for (String number : List.of("\"1\"", "1.0", "1e3", "-1", "true", "null", "9007199254740992", "123456789012345678"))
            rejectedPayload(FIELD, "{\"schema\":1,\"sequence\":" + number + ",\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY) + "}");
        // A leading zero is not a JSON number at all; whether the reader or the field check stops it does not matter.
        Signer leading = new Signer("k1");
        assertThrows(RemoteSettings.Rejected.class, () -> RemoteSettings.verify(leading.envelope(
                "{\"schema\":1,\"sequence\":01,\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY) + "}"), leading.trusted(), NOW));
        rejectedPayload(FIELD, payload("\"features\":{\"images\":\"false\"}"));
        rejectedPayload(FIELD, payload("\"features\":{\"images\":0}"));
        rejectedPayload(FIELD, payload("\"features\":{\"images\":null}"));
        // A signed payload still has to be strict JSON.
        String tail = "\"sequence\":1,\"issuedAt\":" + (NOW - HOUR) + ",\"expiresAt\":" + (NOW + DAY);
        rejectedPayload(MALFORMED, "{'schema':1," + tail.replace('"', '\'') + "}");
        rejectedPayload(MALFORMED, "{schema:1," + tail + "}");
        rejectedPayload(MALFORMED, "{\"schema\":1,/* note */" + tail + "}");
        rejectedPayload(MALFORMED, "{\"schema\":1," + tail + "} // note");
        rejectedPayload(MALFORMED, "{\"schema\":1," + tail + ",\"notice\":{\"id\":x}}");
    }

    // ---- the notice ----

    private static void badNotice(String fields) throws Exception { rejectedPayload(FIELD, payload("\"notice\":{" + fields + "}")); }

    @Test public void aNoticeIsPlainBoundedTextWithAnOptionalPathOnTheDownloadSite() throws Exception {
        assertNotNull(accepted(payload("\"notice\":{" + BASIC_NOTICE + "}")).notice());
        assertTrue(accepted(payload("\"notice\":{" + BASIC_NOTICE.replace("info", "important") + "}")).notice().important());
        assertNotNull(accepted(payload("\"notice\":{\"id\":\"" + "a".repeat(40) + "\",\"title\":\"" + "t".repeat(60) + "\",\"body\":\""
                + "b".repeat(399) + "x\",\"level\":\"info\"}")));
        assertNotNull("Line breaks are allowed in the body", accepted(payload("\"notice\":{\"id\":\"a\",\"title\":\"t\",\"body\":\"one\\ntwo\",\"level\":\"info\"}")));
        assertNotNull("Emoji and other languages are plain text", accepted(payload("\"notice\":{\"id\":\"a\",\"title\":\"\u0928\u092e\u0938\u094d\u0924\u0947 \ud83d\udc4b\","
                + "\"body\":\"\u0920\u0940\u0915 \u0939\u0948 \u2713 \ud83d\udc68\\u200d\ud83d\udc69\\u200d\ud83d\udc67\",\"level\":\"info\"}")));
        badNotice(BASIC_NOTICE.replace("\"id\":\"2026-10-a\"", "\"id\":\"" + "a".repeat(41) + "\""));
        badNotice(BASIC_NOTICE.replace("\"id\":\"2026-10-a\"", "\"id\":\"has space\""));
        badNotice(BASIC_NOTICE.replace("\"id\":\"2026-10-a\"", "\"id\":\"\""));
        badNotice(BASIC_NOTICE.replace("\"title\":\"Hello\"", "\"title\":\"" + "t".repeat(61) + "\""));
        badNotice(BASIC_NOTICE.replace("\"title\":\"Hello\"", "\"title\":\"\""));
        badNotice(BASIC_NOTICE.replace("\"title\":\"Hello\"", "\"title\":\"two\\nlines\""));
        badNotice(BASIC_NOTICE.replace("Something to know.", "b".repeat(401)));
        badNotice(BASIC_NOTICE.replace("Something to know.", "   "));
        badNotice(BASIC_NOTICE.replace("Something to know.", "tab\\there"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "nul\\u0000l"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "right-to-left \\u202e override"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "isolate \\u2066 here"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "mark \\u200f here"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "bom \\ufeff here"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "lone \\ud800 surrogate"));
        // Nothing invisible, nothing that ends a line or a paragraph, nothing that is not a character at all.
        for (String hidden : List.of("\\u200b", "\\u2060", "\\u00ad", "\\u061c", "\\u2061", "\\u2028", "\\u2029", "\\u0085", "\\u000b", "\\u000c",
                "\\udb40\\udc41", "\\ue000", "\\ufffe", "\\ufdd0", "\\ufffd", "\\udbbf\\udffe"))
            badNotice(BASIC_NOTICE.replace("Something to know.", "ab" + hidden + "cd"));
        badNotice(BASIC_NOTICE.replace("\"title\":\"Hello\"", "\"title\":\"He\\u200bllo\""));
        badNotice(BASIC_NOTICE.replace("\"title\":\"Hello\"", "\"title\":\"He\\u2028llo\""));
        // A short note, not a page: a few line breaks, however many characters are left.
        String lines = "l\\n".repeat(RemoteSettings.MAX_NEWLINES) + "l";
        assertNotNull(accepted(payload("\"notice\":{" + BASIC_NOTICE.replace("Something to know.", lines) + "}")));
        badNotice(BASIC_NOTICE.replace("Something to know.", lines + "\\nl"));
        badNotice(BASIC_NOTICE.replace("Something to know.", "x" + "\\n".repeat(398) + "y"));
        // These stay: the joiners that emoji and some scripts need, the emoji selector, and a character this phone's Android may not know yet.
        assertNotNull(accepted(payload("\"notice\":{" + BASIC_NOTICE.replace("Something to know.", "zwj \\u200d zwnj \\u200c vs16 \\u2764\\ufe0f") + "}")));
        assertNotNull("A newer character must not make an older phone refuse the whole file", accepted(payload("\"notice\":{"
                + BASIC_NOTICE.replace("Something to know.", "unassigned \\u0378 and \\ud83e\\udee8") + "}")));
        badNotice(BASIC_NOTICE.replace("\"level\":\"info\"", "\"level\":\"urgent\""));
        badNotice(BASIC_NOTICE.replace("\"level\":\"info\"", "\"level\":\"INFO\""));
        badNotice(BASIC_NOTICE + ",\"build\":\"everyone\"");
        badNotice(BASIC_NOTICE + ",\"minVersionCode\":50,\"maxVersionCode\":40");
        badNotice(BASIC_NOTICE + ",\"maxVersionCode\":2147483648");
        badNotice(BASIC_NOTICE + ",\"minVersionCode\":\"40\"");
        badNotice("\"id\":\"a\",\"title\":\"t\",\"body\":\"b\"");
        badNotice("\"id\":\"a\",\"title\":\"t\",\"level\":\"info\"");
        rejectedPayload(MALFORMED, payload("\"notice\":{" + BASIC_NOTICE + ",\"id\":\"b\"}"));
        rejectedPayload(MALFORMED, payload("\"notice\":[]"));
    }

    @Test public void aNoticeLinkCanOnlyBeAPageOnTheDownloadSite() throws Exception {
        for (String path : List.of("/", "/security", "/security/", "/android/", "/delete-account/", "/a/b-c_d/e", "/a/b/c/d/", "/" + "a".repeat(40)))
            assertEquals(path, accepted(payload("\"notice\":{" + BASIC_NOTICE + ",\"path\":\"" + path + "\"}")).notice().path());
        for (String path : List.of("", "security", "https://evil.example/", "//evil.example/", "/a//b", "/../x", "/a/../b", "/a/..", "/./x", "/a/.",
                "/x?y=1", "/x#y", "/a b", "/x\\\\y", "/%2e%2e/x", "/a;b", "/a/b/c/d/e/", "/" + "a".repeat(41), "/\\u0000", "/\u00e9",
                // A file is not a page: nothing with a dot, so no APK and no other download, and no dot segments or hidden names.
                "/vanishr-0.6.2.apk", "/vanishr-0.6.2-source.zip", "/updates.json", "/ota/settings.json", "/a.b", "/..hidden/ok", "/.well-known/x", "/a~b", "/a/b.html"))
            badNotice(BASIC_NOTICE + ",\"path\":\"" + path + "\"");
    }

    @Test public void aNoticeAppliesOnlyToTheBuildsAndVersionsItNames() throws Exception {
        RemoteSettings.Settings everyone = accepted(payload("\"notice\":{" + BASIC_NOTICE + "}"));
        assertNotNull(RemoteSettings.noticeFor(everyone, 43, true));
        assertNotNull(RemoteSettings.noticeFor(everyone, 43, false));
        RemoteSettings.Settings play = accepted(payload("\"notice\":{" + BASIC_NOTICE + ",\"build\":\"play\"}"));
        assertNotNull(RemoteSettings.noticeFor(play, 43, true));
        assertNull(RemoteSettings.noticeFor(play, 43, false));
        RemoteSettings.Settings direct = accepted(payload("\"notice\":{" + BASIC_NOTICE + ",\"build\":\"direct\"}"));
        assertNull(RemoteSettings.noticeFor(direct, 43, true));
        assertNotNull(RemoteSettings.noticeFor(direct, 43, false));
        RemoteSettings.Settings ranged = accepted(payload("\"notice\":{" + BASIC_NOTICE + ",\"minVersionCode\":40,\"maxVersionCode\":42}"));
        assertNull(RemoteSettings.noticeFor(ranged, 39, false));
        assertNotNull(RemoteSettings.noticeFor(ranged, 40, false));
        assertNotNull(RemoteSettings.noticeFor(ranged, 42, false));
        assertNull(RemoteSettings.noticeFor(ranged, 43, false));
        assertNull(RemoteSettings.noticeFor(accepted(payload("")), 43, false));
        assertNull(RemoteSettings.noticeFor(RemoteSettings.Settings.NONE, 43, false));
    }

    // ---- the update hint ----

    @Test public void aNewerVersionIsHintedAtOnlyOnGooglePlayBuilds() throws Exception {
        RemoteSettings.Settings settings = accepted(payload("\"update\":{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":41}"));
        assertEquals(RemoteSettings.Nudge.NONE, RemoteSettings.nudge(settings, 43, true));
        assertEquals(RemoteSettings.Nudge.NONE, RemoteSettings.nudge(settings, 99, true));
        assertEquals(RemoteSettings.Nudge.AVAILABLE, RemoteSettings.nudge(settings, 42, true));
        assertEquals(RemoteSettings.Nudge.AVAILABLE, RemoteSettings.nudge(settings, 41, true));
        assertEquals(RemoteSettings.Nudge.UNSUPPORTED, RemoteSettings.nudge(settings, 40, true));
        for (int installed : new int[]{1, 40, 42, 43}) assertEquals("The direct build has its own prompt", RemoteSettings.Nudge.NONE, RemoteSettings.nudge(settings, installed, false));
        assertEquals(RemoteSettings.Nudge.NONE, RemoteSettings.nudge(accepted(payload("")), 1, true));
        assertEquals(RemoteSettings.Nudge.NONE, RemoteSettings.nudge(RemoteSettings.Settings.NONE, 1, true));
    }

    @Test public void anUpdateHintIsValidated() throws Exception {
        for (String update : List.of("{\"latestVersionCode\":0,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"0.6\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"v0.6.2\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.02\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2-beta\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"" + "1".repeat(21) + ".0.0\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":44}",
                "{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\"}",
                "{\"latestVersionCode\":43,\"minSupportedVersionCode\":0}",
                "{\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":2147483648,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":0}",
                "{\"latestVersionCode\":\"43\",\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":0}"))
            rejectedPayload(FIELD, payload("\"update\":" + update));
        rejectedPayload(MALFORMED, payload("\"update\":{\"latestVersionCode\":43,\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":0}"));
        assertNotNull(accepted(payload("\"update\":{\"latestVersionCode\":43,\"latestVersionName\":\"0.6.2\",\"minSupportedVersionCode\":43}")));
    }

    // ---- the key list ----

    private static String line(String id, PublicKey key) { return id + " " + Base64.getEncoder().encodeToString(key.getEncoded()); }

    @Test public void theKeyListHoldsAtMostThreeDistinctP256Keys() throws Exception {
        Signer one = new Signer("k1"), two = new Signer("k2"), three = new Signer("k3"), four = new Signer("k4");
        Map<String, PublicKey> keys = RemoteSettings.keys("# comment\n\n" + line("k1", one.pair.getPublic()) + "\r\n" + line("k2", two.pair.getPublic()) + "\n");
        assertEquals(List.of("k1", "k2"), List.copyOf(keys.keySet()));
        assertEquals(one.pair.getPublic(), keys.get("k1"));
        assertTrue(RemoteSettings.keys("").isEmpty());
        assertEquals(3, RemoteSettings.keys(line("k1", one.pair.getPublic()) + "\n" + line("k2", two.pair.getPublic()) + "\n" + line("k3", three.pair.getPublic())).size());
        for (String text : List.of(
                line("k1", one.pair.getPublic()) + "\n" + line("k2", two.pair.getPublic()) + "\n" + line("k3", three.pair.getPublic()) + "\n" + line("k4", four.pair.getPublic()),
                line("k1", one.pair.getPublic()) + "\n" + line("k1", two.pair.getPublic()),
                "k1", "k1 !!!notbase64", "k1 " + Base64.getEncoder().encodeToString(new byte[40]), line("k$", one.pair.getPublic()),
                line("k1", one.pair.getPublic()) + " extra", line("a".repeat(17), one.pair.getPublic()),
                line("k1", new Signer("k1", "secp384r1").pair.getPublic()))) {
            assertThrows(text, GeneralSecurityException.class, () -> RemoteSettings.keys(text));
        }
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertThrows(GeneralSecurityException.class, () -> RemoteSettings.keys(line("k1", rsa.generateKeyPair().getPublic())));
    }

    // ---- the request ----

    private static OkHttpClient transport(AtomicInteger requests, int code, String type, byte[] body) {
        return transport(requests, code, () -> ResponseBody.create(body, MediaType.get(type)));
    }

    private static OkHttpClient transport(AtomicInteger requests, int code, Supplier<ResponseBody> body) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertEquals(RemoteSettings.URL, chain.request().url().toString());
            assertEquals("GET", chain.request().method());
            assertNull(chain.request().header("Authorization"));
            assertNull(chain.request().header("Cookie"));
            assertNull(chain.request().url().query());
            assertNull(chain.request().body());
            assertEquals("no-store", chain.request().header("Cache-Control"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                    .header("Location", "https://example.org/settings.json").body(body.get()).build();
        }).build();
    }

    /** A body that does not say how long it is, as a chunked response does. */
    private static Supplier<ResponseBody> unknownLength(int size) {
        return () -> new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return new Buffer().write(new byte[size]); }
        };
    }

    private static OkHttpClient clientOf(RemoteSettings settings) throws Exception {
        java.lang.reflect.Field field = RemoteSettings.class.getDeclaredField("client");
        field.setAccessible(true);
        return (OkHttpClient) field.get(settings);
    }

    private static void assertHardened(OkHttpClient client) {
        assertFalse("A redirect would send the phone to a host nobody chose", client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertFalse(client.retryOnConnectionFailure());
        assertSame("No cookies in either direction", CookieJar.NO_COOKIES, client.cookieJar());
        assertNull("Nothing is written to a cache", client.cache());
        assertEquals("Modern TLS only, never cleartext", List.of(ConnectionSpec.MODERN_TLS), client.connectionSpecs());
        assertEquals(3_000, client.connectTimeoutMillis());
        assertEquals(3_000, client.readTimeoutMillis());
        assertEquals(6_000, client.callTimeoutMillis());
    }

    @Test public void theClientNeverFollowsRedirectsKeepsCookiesOrCachesAndOnlySpeaksModernTls() throws Exception {
        try (RemoteSettings settings = new RemoteSettings()) { assertHardened(clientOf(settings)); }
        // Whatever transport it is handed, those settings are applied on top of it.
        CookieJar jar = new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) { }
            @Override public List<Cookie> loadForRequest(HttpUrl url) { return List.of(); }
        };
        Path cacheDirectory = Files.createTempDirectory("vanishr-settings-cache");
        try (Cache cache = new Cache(cacheDirectory.toFile(), 1024 * 1024)) {
            OkHttpClient generous = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true)
                    .connectTimeout(1, TimeUnit.MINUTES).readTimeout(1, TimeUnit.MINUTES).callTimeout(5, TimeUnit.MINUTES)
                    .cookieJar(jar).cache(cache).connectionSpecs(List.of(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)).build();
            try (RemoteSettings settings = new RemoteSettings(generous)) { assertHardened(clientOf(settings)); }
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(cacheDirectory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test public void theRequestIsOneBoundedAnonymousGetAndOnlyAJsonDocumentIsKept() throws Exception {
        assertEquals("https://vanishr-download.vercel.app/ota/settings.json", RemoteSettings.URL);
        byte[] body = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        try (RemoteSettings settings = new RemoteSettings(transport(requests, 200, "application/json; charset=utf-8", body))) {
            assertArrayEquals(body, settings.fetch());
            assertEquals(1, requests.get());
        }
        for (int code : new int[]{301, 302, 404, 500}) {
            try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), code, "application/json", body))) {
                assertThrows("A redirect is not followed and an error is not a document: " + code, IOException.class, settings::fetch);
            }
        }
        for (String type : List.of("text/html", "text/plain", "application/octet-stream", "application/xml")) {
            try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), 200, type, body))) {
                assertThrows(type, IOException.class, settings::fetch);
            }
        }
        try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), 200, "application/json", new byte[RemoteSettings.MAX_ENVELOPE + 1]))) {
            assertThrows(IOException.class, settings::fetch);
        }
        try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), 200, "application/json", new byte[RemoteSettings.MAX_ENVELOPE]))) {
            assertEquals(RemoteSettings.MAX_ENVELOPE, settings.fetch().length);
        }
        try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), 200, unknownLength(RemoteSettings.MAX_ENVELOPE + 1)))) {
            assertThrows("A body that does not say how long it is is still cut off at the limit", IOException.class, settings::fetch);
        }
        try (RemoteSettings settings = new RemoteSettings(transport(new AtomicInteger(), 200, unknownLength(RemoteSettings.MAX_ENVELOPE)))) {
            assertEquals(RemoteSettings.MAX_ENVELOPE, settings.fetch().length);
        }
        OkHttpClient offline = new OkHttpClient.Builder().addInterceptor(chain -> { throw new IOException("Offline"); }).build();
        try (RemoteSettings settings = new RemoteSettings(offline)) { assertThrows(IOException.class, settings::fetch); }
    }

    // ---- what is remembered between runs ----

    /** SharedPreferences held in memory, so that the store can be tested without a phone. */
    private static final class MemoryPreferences implements android.content.SharedPreferences {
        final Map<String, Object> values = new java.util.HashMap<>();
        @Override public Map<String, ?> getAll() { return new java.util.HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return values.get(key) instanceof String value ? value : fallback; }
        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback) { return fallback; }
        @Override public int getInt(String key, int fallback) { return values.get(key) instanceof Integer value ? value : fallback; }
        @Override public long getLong(String key, long fallback) { return values.get(key) instanceof Long value ? value : fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.get(key) instanceof Boolean value ? value : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new java.util.HashMap<>();
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor putStringSet(String key, java.util.Set<String> value) { pending.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public Editor remove(String key) { pending.put(key, null); return this; }
                @Override public Editor clear() { values.clear(); return this; }
                @Override public boolean commit() { pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); }); return true; }
                @Override public void apply() { commit(); }
            };
        }
    }

    /** What the daily check does with a file it has fetched and verified. */
    private static boolean take(RemoteSettingsStore store, Signer signer, long sequence, long issued) throws Exception {
        byte[] file = signer.envelope(payload(sequence, issued, NOW + 10 * DAY, ""));
        return store.accept(file, RemoteSettings.verify(file, signer.trusted(), NOW), NOW);
    }

    private static boolean take(RemoteSettingsStore store, Signer signer, long sequence) throws Exception {
        return take(store, signer, sequence, NOW - HOUR);
    }

    @Test public void theFloorBelongsToTheKeyThatSignedTheFileSoARetiredKeyCannotHoldBackItsSuccessor() throws Exception {
        Signer first = new Signer("k1"), second = new Signer("k2");
        RemoteSettingsStore store = new RemoteSettingsStore(new MemoryPreferences());
        assertTrue(take(store, first, 5));
        assertEquals(5, store.floor("k1"));
        assertEquals(0, store.floor("k2"));
        assertFalse("The same file again", take(store, first, 5));
        assertFalse("An older file of the same key", take(store, first, 4));
        assertEquals(5, store.floor("k1"));
        assertEquals("The check counts as done even when the file is not taken", NOW, store.lastCheck());
        // The highest number there is, signed by a key that is later retired, uses up that key's numbers and nobody else's.
        assertTrue(take(store, first, RemoteSettings.MAX_SEQUENCE));
        assertFalse(take(store, first, 6));
        assertTrue("A key that replaces it starts with its own floor", take(store, second, 1));
        assertEquals(1, store.floor("k2"));
        assertEquals(RemoteSettings.MAX_SEQUENCE, store.floor("k1"));
        assertFalse(take(store, second, 1));
        assertTrue(take(store, second, 2));
        Map<String, PublicKey> both = Map.of("k1", first.pair.getPublic(), "k2", second.pair.getPublic());
        assertEquals("k2", store.load(both, NOW).keyId());
        assertEquals(2, store.load(both, NOW).sequence());
    }

    @Test public void anOlderFileOfAnotherListedKeyCannotBePutBackOverANewerOne() throws Exception {
        Signer first = new Signer("k1"), second = new Signer("k2");
        Map<String, PublicKey> both = Map.of("k1", first.pair.getPublic(), "k2", second.pair.getPublic());
        RemoteSettingsStore store = new RemoteSettingsStore(new MemoryPreferences());
        assertTrue(take(store, first, 49, NOW - 5 * HOUR));
        assertEquals(NOW - 5 * HOUR, store.newestIssued());
        assertTrue("A newer file, of the other key", take(store, second, 51, NOW - 3 * HOUR));
        assertEquals(NOW - 3 * HOUR, store.newestIssued());
        // Number 50 has never been seen from the first key, so by its own floor it is new, but it is older than what was taken since.
        assertFalse("An old file of the first key, put back with a number it never had", take(store, first, 50, NOW - 4 * HOUR));
        assertEquals(49, store.floor("k1"));
        assertEquals(51, store.load(both, NOW).sequence());
        assertEquals("k2", store.load(both, NOW).keyId());
        assertTrue(take(store, first, 52, NOW - 2 * HOUR));
        assertEquals(NOW - 2 * HOUR, store.newestIssued());
        assertTrue("Issued at the very time of the newest: the sequence decides", take(store, first, 53, NOW - 2 * HOUR));
        assertFalse(take(store, first, 53, NOW - 2 * HOUR));
        assertEquals(53, store.load(both, NOW).sequence());
        assertEquals("A refused file leaves the newest issue time where it was", NOW - 2 * HOUR, store.newestIssued());
    }

    @Test public void aFileDatedInTheFutureCannotHoldBackTheFilesThatReallyFollowIt() throws Exception {
        Signer owner = new Signer("k1");
        RemoteSettingsStore store = new RemoteSettingsStore(new MemoryPreferences());
        assertTrue("As far ahead as the phone's clock allows", take(store, owner, 1, NOW + RemoteSettings.CLOCK_SKEW));
        assertEquals("The issue time kept is no later than the phone's own clock", NOW, store.newestIssued());
        assertTrue("so a file issued a moment after that one still counts", take(store, owner, 2, NOW + HOUR));
        assertEquals(NOW, store.newestIssued());
        assertFalse("while one issued before the phone's clock is still refused", take(store, owner, 3, NOW - 1));
        assertEquals(2, store.floor("k1"));
    }

    @Test public void theNewestIssueTimeNeverGoesBackwardEvenWhenThePhonesClockDoes() throws Exception {
        Signer owner = new Signer("k1");
        MemoryPreferences preferences = new MemoryPreferences();
        RemoteSettingsStore store = new RemoteSettingsStore(preferences);
        assertEquals(0, store.newestIssued());
        assertTrue(take(store, owner, 1, NOW - 2 * HOUR));
        assertEquals(NOW - 2 * HOUR, store.newestIssued());
        // The clock was put back after a file issued later than it now says had been taken: the time kept does not follow it down.
        preferences.values.put("issued-high", NOW + 5 * HOUR);
        assertFalse("Older than the time kept", take(store, owner, 2, NOW + 4 * HOUR));
        assertEquals(1, store.floor("k1"));
        assertTrue(take(store, owner, 3, NOW + 6 * HOUR));
        assertEquals(NOW + 5 * HOUR, store.newestIssued());
        preferences.values.put("issued-high", -5L);
        assertEquals("A negative value is no value", 0, store.newestIssued());
    }

    @Test public void aNoticeAndAnUpdateHintAreDismissedSeparately() {
        RemoteSettingsStore store = new RemoteSettingsStore(new MemoryPreferences());
        assertEquals("", store.dismissedNotice());
        assertEquals(0, store.dismissedUpdate());
        assertFalse(store.updateDismissed(44));
        store.dismissNotice("n1");
        assertEquals("n1", store.dismissedNotice());
        assertEquals("Dismissing the notice does not dismiss the hint", 0, store.dismissedUpdate());
        store.dismissUpdate(44);
        assertEquals("Dismissing the hint does not bring the notice back", "n1", store.dismissedNotice());
        assertEquals(44, store.dismissedUpdate());
        assertTrue(store.updateDismissed(44));
        assertFalse("A hint about a later version is shown", store.updateDismissed(45));
        assertFalse("and so is one about an earlier version, so that a mistyped hint can be corrected", store.updateDismissed(43));
        store.dismissUpdate(43);
        assertEquals("The correction, once dismissed, is what is remembered", 43, store.dismissedUpdate());
        assertTrue(store.updateDismissed(43));
        assertFalse(store.updateDismissed(44));
        store.dismissNotice("n2");
        assertEquals("n2", store.dismissedNotice());
        assertEquals(43, store.dismissedUpdate());
    }

    @Test public void aStoredFileIsCheckedAgainWheneverItIsLoaded() throws Exception {
        Signer owner = new Signer("k1"), stranger = new Signer("k1");
        MemoryPreferences preferences = new MemoryPreferences();
        RemoteSettingsStore store = new RemoteSettingsStore(preferences);
        assertEquals("Nothing stored", 0, store.load(owner.trusted(), NOW).sequence());
        assertTrue(take(store, owner, 3));
        assertEquals(3, store.load(owner.trusted(), NOW).sequence());
        assertEquals("Under another key it no longer counts", 0, store.load(stranger.trusted(), NOW).sequence());
        assertEquals("Once expired it no longer counts", 0, store.load(owner.trusted(), NOW + 11 * DAY).sequence());
        String envelope = (String) preferences.values.get("envelope");
        preferences.values.put("envelope", envelope.replace("\"v\":1", "\"v\":2"));
        assertEquals("A changed file on the phone is not used", 0, store.load(owner.trusted(), NOW).sequence());
        preferences.values.put("envelope", "not a settings file");
        assertEquals(0, store.load(owner.trusted(), NOW).sequence());
    }

    @Test public void aStoredFileIssuedBeforeThisBuildsFloorIsNotUsedAndTheNextCheckIsDueAtOnce() throws Exception {
        Signer owner = new Signer("k1");
        long floor = RemoteSettings.BUILD_ISSUED_FLOOR;
        MemoryPreferences preferences = new MemoryPreferences();
        RemoteSettingsStore store = new RemoteSettingsStore(preferences);
        preferences.values.put("last-check", floor);
        preferences.values.put("envelope", new String(owner.envelope(payload(4, floor - 1, floor + 10 * DAY, "")), StandardCharsets.UTF_8));
        assertEquals("Kept by an earlier version, issued just before this one's floor", 0, store.load(owner.trusted(), floor).sequence());
        assertEquals("and the check that would have been done tomorrow is due now", 0, store.lastCheck());
        assertTrue(RemoteSettings.due(floor, store.lastCheck()));
        assertFalse("The file is gone: no later version can use it either", preferences.values.containsKey("envelope"));
        preferences.values.put("last-check", floor);
        assertEquals("Loaded again, there is nothing to drop", 0, store.load(owner.trusted(), floor).sequence());
        assertEquals("so the check is not brought forward on every start", floor, store.lastCheck());
        preferences.values.put("envelope", new String(owner.envelope(payload(4, floor, floor + 10 * DAY, "")), StandardCharsets.UTF_8));
        assertEquals("Issued at the floor", 4, store.load(owner.trusted(), floor).sequence());
        assertEquals("A file that is used leaves the time of the last check alone", floor, store.lastCheck());
        preferences.values.put("envelope", "not a settings file");
        assertEquals(0, store.load(owner.trusted(), floor).sequence());
        assertEquals("As does one that is simply refused: only the floor brings the check forward", floor, store.lastCheck());
    }

    @Test public void aFailedCheckIsTriedAgainInAboutTwoHoursAndNotBefore() {
        RemoteSettingsStore store = new RemoteSettingsStore(new MemoryPreferences());
        assertTrue("Never checked", RemoteSettings.due(NOW, store.lastCheck()));
        store.failed(NOW);
        assertFalse(RemoteSettings.due(NOW + 1, store.lastCheck()));
        assertFalse(RemoteSettings.due(NOW + RemoteSettings.RETRY_INTERVAL - 1_000, store.lastCheck()));
        assertTrue(RemoteSettings.due(NOW + RemoteSettings.RETRY_INTERVAL, store.lastCheck()));
    }

    // ---- the files that are actually published ----

    private static Path repositoryFile(String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve(relative))) directory = directory.getParent();
        assertNotNull("Not found from the test's working directory: " + relative, directory);
        return directory.resolve(relative);
    }

    @Test public void theSharedRuleCasesAreAcceptedAndRefusedAsTheSignersTestsExpect() throws Exception {
        // The same table scripts/test-ota-settings.ps1 runs the signing tool's rules against: what the tool refuses must be refused here too,
        // and what it accepts must be accepted here, or a file it signs would be thrown away whole by every phone, switches included.
        JsonObject table = JsonParser.parseString(new String(Files.readAllBytes(repositoryFile("ota/rules-cases.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray cases = table.getAsJsonArray("cases");
        assertTrue("The cases were read", cases.size() >= 100);
        Signer signer = new Signer("k1");
        for (JsonElement element : cases) {
            JsonObject test = element.getAsJsonObject();
            String name = test.get("name").getAsString();
            StringBuilder fields = new StringBuilder();
            for (Map.Entry<String, JsonElement> part : test.getAsJsonObject("source").entrySet()) {
                if (fields.length() > 0) fields.append(',');
                fields.append(new JsonPrimitive(part.getKey())).append(':').append(part.getValue());
            }
            boolean accepted;
            try { RemoteSettings.verify(signer.envelope(payload(fields.toString())), signer.trusted(), NOW); accepted = true; }
            catch (RemoteSettings.Rejected rejected) { accepted = false; }
            assertEquals("Case '" + name + "'", test.get("app").getAsBoolean(), accepted);
            assertTrue("Case '" + name + "': the table lets the signing tool accept what the app refuses", !test.get("signer").getAsBoolean() || test.get("app").getAsBoolean());
        }
    }

    @Test public void theCommittedSettingsFileIsAcceptedUnderTheKeysTheAppEmbeds() throws Exception {
        Map<String, PublicKey> trusted = RemoteSettings.keys(new String(Files.readAllBytes(repositoryFile("android/app/src/main/res/raw/ota_keys.txt")), StandardCharsets.UTF_8));
        assertFalse("The app has to embed at least one key", trusted.isEmpty());
        byte[] file = Files.readAllBytes(repositoryFile("download/ota/settings.json"));
        // Checked as of an hour after it was issued, so that its own expiry date does not turn a later test run red.
        JsonObject envelope = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject payload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(envelope.get("payload").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject();
        long issued = payload.get("issuedAt").getAsLong();
        RemoteSettings.Settings settings = RemoteSettings.verify(file, trusted, issued + HOUR);
        assertTrue("Not issued before this build's floor", settings.issuedAt() >= RemoteSettings.BUILD_ISSUED_FLOOR);
        assertTrue(RemoteSettings.newer(settings, 0, 0));
        assertTrue("The file is as long-lived as the tool allows, not longer", settings.expiresAt() - settings.issuedAt() <= RemoteSettings.MAX_VALIDITY);
        // And the same bytes are refused once a key other than the app's signs something else.
        Signer stranger = new Signer(envelope.get("key").getAsString());
        rejected(SIGNATURE, file, stranger.trusted());
    }
}
