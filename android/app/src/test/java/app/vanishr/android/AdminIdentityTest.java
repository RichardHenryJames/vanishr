package app.vanishr.android;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static app.vanishr.android.AdminIdentity.Reason.*;
import static org.junit.Assert.*;

public class AdminIdentityTest {
    private static final UUID ADMIN = UUID.fromString("98ad3c75-0127-422d-9a08-540edb9d3301");
    private static final String USER = "98ad3c75-0127-422d-9a08-540edb9d3301";
    private static final String DEVICE = "638b9ff1-8f90-428c-91b7-e1f8ce4d78a0";
    private static final String FINGERPRINT = "7b0c40727d9b7de86ec820ef52db3e8a0efea1644cace6c2e3ed64a441520ece";
    // Restated here on purpose: these are the protocol, and the test should not just echo the implementation's own constants.
    private static final byte[] DOMAIN = "vanishr-admin-identity-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SETTINGS_DOMAIN = "vanishr-ota-settings-v1\0".getBytes(StandardCharsets.US_ASCII);

    private static final class Signer {
        final String id;
        final KeyPair pair;

        Signer(String id) throws Exception {
            this.id = id;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        }

        Map<String, PublicKey> trusted() { return Map.of(id, pair.getPublic()); }

        byte[] sign(byte[] payload, byte[] domain) throws Exception {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            if (domain != null) signature.update(domain);
            signature.update(payload);
            return signature.sign();
        }

        byte[] envelope(String payload) throws Exception {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            return AdminIdentityTest.envelope(id, bytes, sign(bytes, DOMAIN));
        }
    }

    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    private static byte[] envelope(String key, byte[] payload, byte[] signature) {
        return ("{\"v\":1,\"key\":\"" + key + "\",\"payload\":\"" + b64(payload) + "\",\"sig\":\"" + b64(signature) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String payload(long sequence) { return payload(sequence, USER, DEVICE, FINGERPRINT); }

    private static String payload(long sequence, String user, String device, String fingerprint) {
        return "{\"schema\":1,\"sequence\":" + sequence + ",\"userId\":\"" + user + "\",\"deviceId\":\"" + device + "\",\"fingerprint\":\"" + fingerprint + "\"}";
    }

    private static void rejected(AdminIdentity.Reason reason, byte[] envelope, Map<String, PublicKey> trusted) {
        AdminIdentity.Rejected failure = assertThrows(AdminIdentity.Rejected.class, () -> AdminIdentity.verify(envelope, trusted, ADMIN));
        assertEquals(reason, failure.reason);
    }

    private static void rejectedPayload(AdminIdentity.Reason reason, String payload) throws Exception {
        Signer signer = new Signer("a1");
        rejected(reason, signer.envelope(payload), signer.trusted());
    }

    private static AdminIdentity.Certificate accepted(String payload) throws Exception {
        Signer signer = new Signer("a1");
        return AdminIdentity.verify(signer.envelope(payload), signer.trusted(), ADMIN);
    }

    // ---- a good certificate ----

    @Test public void aSignedCertificateIsAcceptedAndReadBackExactly() throws Exception {
        AdminIdentity.Certificate certificate = accepted(payload(2));
        assertEquals("a1", certificate.keyId());
        assertEquals(2, certificate.sequence());
        assertEquals(ADMIN, certificate.userId());
        assertEquals(UUID.fromString(DEVICE), certificate.deviceId());
        assertEquals(FINGERPRINT, certificate.fingerprint());
        AdminOnboarding.Pin pin = certificate.pin("https://relay.example/");
        assertEquals(new AdminOnboarding.Pin("https://relay.example/", ADMIN, UUID.fromString(DEVICE), FINGERPRINT), pin);
    }

    @Test public void theHighestAndLowestSequenceNumbersAreAcceptedAndNothingOutsideThem() throws Exception {
        assertEquals(1, accepted(payload(1)).sequence());
        assertEquals(AdminIdentity.MAX_SEQUENCE, accepted(payload(AdminIdentity.MAX_SEQUENCE)).sequence());
        for (String bad : new String[]{"0", "-1", "1000001", "1.5", "1e2", "\"2\"", "true", "null", "9007199254740992"})
            rejectedPayload(FIELD, payload(2).replace("\"sequence\":2", "\"sequence\":" + bad));
        // A leading zero is not JSON at all, and is refused whichever way the reader meets it.
        Signer signer = new Signer("a1");
        assertThrows(AdminIdentity.Rejected.class, () -> AdminIdentity.verify(
                signer.envelope(payload(2).replace("\"sequence\":2", "\"sequence\":02")), signer.trusted(), ADMIN));
    }

    // ---- what the certificate may say ----

    @Test public void aCertificateCanOnlyNameTheAccountTheAppPins() throws Exception {
        rejectedPayload(ACCOUNT, payload(2, "11111111-2222-3333-4444-555555555555", DEVICE, FINGERPRINT));
        // The same account written in a form the signer never produces is not accepted either: the text has to be the canonical one.
        rejectedPayload(FIELD, payload(2, USER.toUpperCase(java.util.Locale.ROOT), DEVICE, FINGERPRINT));
        rejectedPayload(FIELD, payload(2, "{" + USER + "}", DEVICE, FINGERPRINT));
        rejectedPayload(FIELD, payload(2, USER.replace("-", ""), DEVICE, FINGERPRINT));
        // And the app asks for the account it pins, not the one the certificate would like: another pinned account refuses this one.
        Signer signer = new Signer("a1");
        AdminIdentity.Rejected other = assertThrows(AdminIdentity.Rejected.class,
                () -> AdminIdentity.verify(signer.envelope(payload(2)), signer.trusted(), UUID.fromString("11111111-2222-3333-4444-555555555555")));
        assertEquals(ACCOUNT, other.reason);
    }

    @Test public void theDeviceAndTheFingerprintHaveToBeWrittenExactlyAsTheSignerWritesThem() throws Exception {
        rejectedPayload(FIELD, payload(2, USER, DEVICE.toUpperCase(java.util.Locale.ROOT), FINGERPRINT));
        rejectedPayload(FIELD, payload(2, USER, "638b9ff1-8f90-428c-91b7-e1f8ce4d78a", FINGERPRINT));
        rejectedPayload(FIELD, payload(2, USER, "not-a-device", FINGERPRINT));
        rejectedPayload(FIELD, payload(2, USER, DEVICE, FINGERPRINT.toUpperCase(java.util.Locale.ROOT)));
        rejectedPayload(FIELD, payload(2, USER, DEVICE, FINGERPRINT.substring(1)));
        rejectedPayload(FIELD, payload(2, USER, DEVICE, FINGERPRINT + "0"));
        rejectedPayload(FIELD, payload(2, USER, DEVICE, "g" + FINGERPRINT.substring(1)));
        rejectedPayload(FIELD, payload(2, USER, DEVICE, FINGERPRINT.substring(0, 32) + " " + FINGERPRINT.substring(33)));
    }

    @Test public void everyFieldMustBePresentWithItsOwnTypeAndTheSchemaMustBeOne() throws Exception {
        for (String name : new String[]{"sequence", "userId", "deviceId", "fingerprint"}) {
            String without = payload(2).replaceAll(",?\"" + name + "\":(\"[^\"]*\"|[0-9]+)", "").replace("{,", "{");
            rejectedPayload(FIELD, without);
        }
        rejectedPayload(SCHEMA, payload(2).replace("\"schema\":1", "\"schema\":2"));
        rejectedPayload(SCHEMA, payload(2).replace("\"schema\":1,", ""));
        rejectedPayload(FIELD, payload(2).replace("\"schema\":1", "\"schema\":\"1\""));
        rejectedPayload(FIELD, payload(2).replace("\"userId\":\"" + USER + "\"", "\"userId\":5"));
        rejectedPayload(FIELD, payload(2).replace("\"deviceId\":\"" + DEVICE + "\"", "\"deviceId\":null"));
        rejectedPayload(FIELD, payload(2).replace("\"fingerprint\":\"" + FINGERPRINT + "\"", "\"fingerprint\":[]"));
    }

    @Test public void aFieldThatIsNotKnownOrIsWrittenTwiceRefusesTheWholeCertificate() throws Exception {
        // Unlike the settings file, nothing is skipped: a certificate that says more than the app understands is not understood.
        rejectedPayload(MALFORMED, payload(2).replace("}", ",\"expiresAt\":1}"));
        rejectedPayload(MALFORMED, payload(2).replace("}", ",\"extra\":{\"nested\":[1,2]}}"));
        rejectedPayload(MALFORMED, payload(2).replace("\"sequence\":2,", "\"sequence\":2,\"sequence\":3,"));
        rejectedPayload(MALFORMED, payload(2).replace("\"userId\"", "\"userId\":\"" + USER + "\",\"userId\""));
        Signer signer = new Signer("a1");
        byte[] bytes = payload(2).getBytes(StandardCharsets.UTF_8);
        String good = new String(envelope("a1", bytes, signer.sign(bytes, DOMAIN)), StandardCharsets.UTF_8);
        rejected(MALFORMED, good.replace("}", ",\"extra\":1}").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, good.replace("\"v\":1,", "\"v\":1,\"v\":1,").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, good.replace("\"v\":1", "\"v\":2").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(FIELD, good.replace("\"v\":1", "\"v\":\"1\"").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, (good + " {}").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, (good + good).getBytes(StandardCharsets.UTF_8), signer.trusted());
    }

    @Test public void textThatIsNotStrictJsonIsRefusedInTheEnvelopeAndInThePayload() throws Exception {
        rejectedPayload(MALFORMED, payload(2).replace("{", "{/* a comment */"));
        rejectedPayload(MALFORMED, payload(2).replace("}", ",}"));
        rejectedPayload(MALFORMED, payload(2).replace("\"schema\"", "'schema'"));
        rejectedPayload(MALFORMED, payload(2).replace("\"schema\":1", "schema:1"));
        rejectedPayload(MALFORMED, payload(2).replace("\"sequence\":2", "\"sequence\":NaN"));
        rejectedPayload(MALFORMED, "[" + payload(2) + "]");
        rejectedPayload(MALFORMED, "null");
        rejectedPayload(MALFORMED, payload(2) + " {}");
        rejectedPayload(MALFORMED, payload(2) + payload(2));
        rejectedPayload(MALFORMED, payload(2) + " x");
        rejectedPayload(SIZE, "");
        Signer signer = new Signer("a1");
        byte[] bytes = payload(2).getBytes(StandardCharsets.UTF_8);
        String good = new String(envelope("a1", bytes, signer.sign(bytes, DOMAIN)), StandardCharsets.UTF_8);
        rejected(MALFORMED, good.replace("\"v\"", "'v'").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, good.replace("}", ",}").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, ("[" + good + "]").getBytes(StandardCharsets.UTF_8), signer.trusted());
        rejected(MALFORMED, "not json".getBytes(StandardCharsets.UTF_8), signer.trusted());
    }

    // ---- who signed it ----

    @Test public void theSignatureCoversThePayloadAndTheDomainAndTheKeyIsOneTheAppLists() throws Exception {
        Signer signer = new Signer("a1");
        byte[] bytes = payload(2).getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(bytes, DOMAIN);
        assertEquals(2, AdminIdentity.verify(envelope("a1", bytes, signature), signer.trusted(), ADMIN).sequence());

        byte[] altered = payload(3).getBytes(StandardCharsets.UTF_8);
        rejected(SIGNATURE, envelope("a1", altered, signature), signer.trusted());
        byte[] swapped = bytes.clone(); swapped[swapped.length - 3] ^= 1;
        rejected(SIGNATURE, envelope("a1", swapped, signature), signer.trusted());
        byte[] flipped = signature.clone(); flipped[flipped.length - 1] ^= 1;
        rejected(SIGNATURE, envelope("a1", bytes, flipped), signer.trusted());
        rejected(SIGNATURE, envelope("a1", bytes, new byte[]{1, 2, 3}), signer.trusted());
        rejected(SIGNATURE, envelope("a1", bytes, new byte[100]), signer.trusted());
        rejected(SIGNATURE, envelope("a1", bytes, signer.sign(bytes, null)), signer.trusted());
        // A settings file's signature, even from the very same key, cannot stand in for a certificate: the domain differs.
        rejected(SIGNATURE, envelope("a1", bytes, signer.sign(bytes, SETTINGS_DOMAIN)), signer.trusted());

        Signer stranger = new Signer("a1");
        rejected(SIGNATURE, stranger.envelope(payload(2)), signer.trusted());
        rejected(KEY, signer.envelope(payload(2)), Map.of("other", signer.pair.getPublic()));
        rejected(KEY, signer.envelope(payload(2)), Map.of());
        rejected(MALFORMED, envelope("a key with spaces", bytes, signature), signer.trusted());
        rejected(MALFORMED, envelope("", bytes, signature), signer.trusted());
        rejected(MALFORMED, envelope("a".repeat(17), bytes, signature), signer.trusted());
        rejected(MALFORMED, "{\"v\":1,\"key\":\"a1\",\"payload\":\"!!\",\"sig\":\"AAAA\"}".getBytes(StandardCharsets.UTF_8), signer.trusted());
    }

    @Test public void aSecondKeyListedBesideTheFirstCanSignToo() throws Exception {
        Signer first = new Signer("a1"), second = new Signer("a2");
        Map<String, PublicKey> both = Map.of("a1", first.pair.getPublic(), "a2", second.pair.getPublic());
        assertEquals("a1", AdminIdentity.verify(first.envelope(payload(2)), both, ADMIN).keyId());
        assertEquals("a2", AdminIdentity.verify(second.envelope(payload(3)), both, ADMIN).keyId());
        // But a key id is only ever matched with its own key.
        byte[] bytes = payload(2).getBytes(StandardCharsets.UTF_8);
        rejected(SIGNATURE, envelope("a1", bytes, second.sign(bytes, DOMAIN)), both);
    }

    @Test public void theSizeLimitsApplyToTheFileAndToThePayload() throws Exception {
        Signer signer = new Signer("a1");
        rejected(SIZE, new byte[0], signer.trusted());
        rejected(SIZE, new byte[AdminIdentity.MAX_ENVELOPE + 1], signer.trusted());
        StringBuilder spaces = new StringBuilder(payload(2));
        while (spaces.length() <= AdminIdentity.MAX_PAYLOAD) spaces.append(' ');
        byte[] big = spaces.toString().getBytes(StandardCharsets.UTF_8);
        rejected(SIZE, envelope("a1", big, signer.sign(big, DOMAIN)), signer.trusted());
        byte[] empty = new byte[0];
        rejected(SIZE, envelope("a1", empty, signer.sign(empty, DOMAIN)), signer.trusted());
        // The real thing is a small fraction of the limit.
        assertTrue(signer.envelope(payload(AdminIdentity.MAX_SEQUENCE)).length < AdminIdentity.MAX_ENVELOPE / 2);
    }

    @Test public void aKeyThatIsNotAP256KeyCannotBeListed() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String line = "a1 " + Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
        assertThrows(GeneralSecurityException.class, () -> RemoteSettings.keys(line));
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp384r1"));
        String other = "a1 " + Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded());
        assertThrows(GeneralSecurityException.class, () -> RemoteSettings.keys(other));
    }

    // ---- what is taken, and what is remembered between runs ----

    private static boolean taken(AdminIdentity.Certificate candidate, AdminIdentity.Certificate stored) {
        return AdminIdentity.newer(candidate, stored, Math.max(AdminIdentity.BUILD_SEQUENCE_FLOOR, stored == null ? 0 : stored.sequence()));
    }

    @Test public void onlyACertificateAboveTheBuildFloorAndAboveTheStoredOneIsTaken() throws Exception {
        long floor = AdminIdentity.BUILD_SEQUENCE_FLOOR;
        AdminIdentity.Certificate atFloor = accepted(payload(floor)), above = accepted(payload(floor + 1)), higher = accepted(payload(floor + 5));
        assertFalse("What this build already stands for changes nothing", taken(atFloor, null));
        assertTrue(taken(above, null));
        assertFalse("The same again", taken(above, above));
        assertFalse("An older one is never put back", taken(above, higher));
        assertTrue(taken(higher, above));
        if (floor > 1) assertFalse(taken(accepted(payload(floor - 1)), null));
        assertTrue("A key that replaces another is held to the same numbering", taken(higher, accepted(payload(floor + 1))));
    }

    @Test public void aStoredCopyThatCannotBeUsedDoesNotLowerTheBarForWhatIsTakenNext() throws Exception {
        long floor = AdminIdentity.BUILD_SEQUENCE_FLOOR;
        // The highest number ever taken was floor + 5, but the copy of it can no longer be verified, so there is nothing usable stored.
        long known = floor + 5;
        assertFalse("Older than anything taken before", AdminIdentity.newer(accepted(payload(floor + 2)), null, known));
        assertFalse(AdminIdentity.newer(accepted(payload(known - 1)), null, known));
        assertTrue("The certificate it was can replace the damaged copy", AdminIdentity.newer(accepted(payload(known)), null, known));
        assertTrue(AdminIdentity.newer(accepted(payload(known + 1)), null, known));
        assertFalse("and the build's own floor still holds", AdminIdentity.newer(accepted(payload(floor)), null, known));
        // With a usable copy the number of that copy is the bar.
        AdminIdentity.Certificate stored = accepted(payload(known));
        assertFalse(AdminIdentity.newer(accepted(payload(known)), stored, known));
        assertTrue(AdminIdentity.newer(accepted(payload(known + 1)), stored, known));
    }

    @Test public void theStoreKeepsWhatWasTakenChecksItAgainOnEveryLoadAndDropsWhatThisBuildCannotUse() throws Exception {
        Signer signer = new Signer("a1");
        MemoryPreferences preferences = new MemoryPreferences();
        AdminIdentityStore store = new AdminIdentityStore(preferences);
        assertFalse(store.present());
        assertNull(store.load(signer.trusted(), ADMIN));
        assertEquals("Nothing taken yet: the bar is the build's floor", AdminIdentity.BUILD_SEQUENCE_FLOOR, store.highWater());

        byte[] first = signer.envelope(payload(AdminIdentity.BUILD_SEQUENCE_FLOOR + 1));
        assertTrue(store.save(first, AdminIdentity.BUILD_SEQUENCE_FLOOR + 1));
        assertTrue(store.present());
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, store.load(signer.trusted(), ADMIN).sequence());
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, store.highWater());
        assertEquals("The bytes are kept exactly as they came", new String(first, StandardCharsets.UTF_8), preferences.values.get("envelope"));

        // Loaded under other keys, for another account, or after the copy was altered on the phone, it is not used, and the bar stays where it was.
        assertNull(store.load(new Signer("a1").trusted(), ADMIN));
        assertNull(store.load(signer.trusted(), UUID.fromString("11111111-2222-3333-4444-555555555555")));
        assertNull(store.load(Map.of(), ADMIN));
        preferences.values.put("envelope", "x" + new String(first, StandardCharsets.UTF_8));
        assertNull(store.load(signer.trusted(), ADMIN));
        preferences.values.put("envelope", "not a certificate");
        assertNull(store.load(signer.trusted(), ADMIN));
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, store.highWater());

        // The number only ever goes up: a lower one is refused and nothing changes, the same one is kept again, a higher one replaces it.
        assertFalse("A lower number is refused", store.save(first, AdminIdentity.BUILD_SEQUENCE_FLOOR));
        assertEquals("and nothing changed", "not a certificate", preferences.values.get("envelope"));
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, store.highWater());
        assertTrue("The same number again is kept", store.save(first, AdminIdentity.BUILD_SEQUENCE_FLOOR + 1));
        assertEquals(new String(first, StandardCharsets.UTF_8), preferences.values.get("envelope"));
        byte[] fifth = signer.envelope(payload(AdminIdentity.BUILD_SEQUENCE_FLOOR + 4));
        assertTrue(store.save(fifth, AdminIdentity.BUILD_SEQUENCE_FLOOR + 4));
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 4, store.highWater());
        assertEquals(AdminIdentity.BUILD_SEQUENCE_FLOOR + 4, store.load(signer.trusted(), ADMIN).sequence());

        // One that this build already stands for (or is below) can never matter again, and is dropped rather than checked on every start.
        AdminIdentityStore fresh = new AdminIdentityStore(new MemoryPreferences());
        assertTrue(fresh.save(signer.envelope(payload(AdminIdentity.BUILD_SEQUENCE_FLOOR)), AdminIdentity.BUILD_SEQUENCE_FLOOR));
        assertNull(fresh.load(signer.trusted(), ADMIN));
        assertFalse(fresh.present());
    }

    @Test public void aStoredCopyOlderThanTheHighestNumberTakenIsNotTheOneToFollow() throws Exception {
        Signer signer = new Signer("a1");
        MemoryPreferences preferences = new MemoryPreferences();
        AdminIdentityStore store = new AdminIdentityStore(preferences);
        long base = AdminIdentity.BUILD_SEQUENCE_FLOOR;
        byte[] three = signer.envelope(payload(base + 3)), five = signer.envelope(payload(base + 5));
        assertTrue(store.save(five, base + 5));
        assertEquals(base + 5, store.load(signer.trusted(), ADMIN).sequence());
        // However an older certificate came to be in the place of the newest one, it verifies but it is not what was taken last.
        preferences.values.put("envelope", new String(three, StandardCharsets.UTF_8));
        assertNull(store.load(signer.trusted(), ADMIN));
        assertEquals("and the bar stays where it was", base + 5, store.highWater());
        assertTrue("so the certificate that was taken last is followed again when it is shown", AdminIdentity.newer(AdminIdentity.verify(five, signer.trusted(), ADMIN), null, store.highWater()));
        assertFalse(AdminIdentity.newer(AdminIdentity.verify(three, signer.trusted(), ADMIN), null, store.highWater()));
    }

    @Test public void twoRequestsThatOverlapCannotPutAnOlderCertificateOverANewerOne() throws Exception {
        Signer signer = new Signer("a1");
        MemoryPreferences preferences = new MemoryPreferences();
        // Two screens (two stores over the same preferences) each checked a different certificate against what was stored at that time.
        AdminIdentityStore a = new AdminIdentityStore(preferences), b = new AdminIdentityStore(preferences);
        long base = AdminIdentity.BUILD_SEQUENCE_FLOOR;
        byte[] four = signer.envelope(payload(base + 4)), five = signer.envelope(payload(base + 5));
        assertTrue(AdminIdentity.newer(AdminIdentity.verify(four, signer.trusted(), ADMIN), a.load(signer.trusted(), ADMIN), a.highWater()));
        assertTrue(AdminIdentity.newer(AdminIdentity.verify(five, signer.trusted(), ADMIN), b.load(signer.trusted(), ADMIN), b.highWater()));
        assertTrue(b.save(five, base + 5));
        assertFalse("The older one arrives second and is refused", a.save(four, base + 4));
        assertEquals(base + 5, a.load(signer.trusted(), ADMIN).sequence());
        assertEquals(new String(five, StandardCharsets.UTF_8), preferences.values.get("envelope"));
        assertEquals(base + 5, b.highWater());
    }

    @Test public void theCheckThatDecidesWhetherToKeepACertificateNeedsTheStoredOneToBeVerifiedToo() throws Exception {
        Signer signer = new Signer("a1");
        MemoryPreferences preferences = new MemoryPreferences();
        AdminIdentityStore store = new AdminIdentityStore(preferences);
        long base = AdminIdentity.BUILD_SEQUENCE_FLOOR;
        byte[] three = signer.envelope(payload(base + 3));
        AdminIdentity.Certificate threeCertificate = AdminIdentity.verify(three, signer.trusted(), ADMIN);
        assertTrue(AdminIdentity.newer(threeCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
        assertTrue(store.save(three, threeCertificate.sequence()));
        AdminIdentity.Certificate twoCertificate = AdminIdentity.verify(signer.envelope(payload(base + 2)), signer.trusted(), ADMIN);
        assertFalse("A lower number after a higher one", AdminIdentity.newer(twoCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
        assertFalse("The same again", AdminIdentity.newer(threeCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
        // A stored copy that no longer verifies is not a usable certificate, but it does not make the bar lower: an older one is still refused,
        // the one it was can take its place, and a newer one is taken.
        preferences.values.put("envelope", "damaged");
        assertNull(store.load(signer.trusted(), ADMIN));
        assertFalse(AdminIdentity.newer(twoCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
        assertTrue(AdminIdentity.newer(threeCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
        AdminIdentity.Certificate fourCertificate = AdminIdentity.verify(signer.envelope(payload(base + 4)), signer.trusted(), ADMIN);
        assertTrue(AdminIdentity.newer(fourCertificate, store.load(signer.trusted(), ADMIN), store.highWater()));
    }

    @Test public void aRequestForACertificateIsRememberedAcrossRestartsAndDueAgainAfterTheInterval() {
        MemoryPreferences preferences = new MemoryPreferences();
        long interval = 600_000, start = 1_000_000_000_000L;
        AdminIdentityStore store = new AdminIdentityStore(preferences);
        assertTrue("Never asked", store.reserveAttempt(start, interval));
        assertFalse("Asked a moment ago", store.reserveAttempt(start, interval));
        assertFalse(store.reserveAttempt(start + interval - 1, interval));
        // A new store over the same preferences is what a restart of the app sees.
        AdminIdentityStore restarted = new AdminIdentityStore(preferences);
        assertFalse("Still not due after a restart", restarted.reserveAttempt(start + 1, interval));
        assertTrue(restarted.reserveAttempt(start + interval, interval));
        assertFalse("and that one counts too", restarted.reserveAttempt(start + interval + 1, interval));
        // A clock that went back must not hold the next request off for as long as it went back.
        assertTrue(restarted.reserveAttempt(start - 5_000, interval));
    }

    @Test public void screensThatAskAtTheSameMomentAreToldYesOnlyOnce() throws Exception {
        int screens = 8;
        for (int round = 0; round < 200; round++) {
            AdminIdentityStore store = new AdminIdentityStore(new MemoryPreferences());
            java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(screens);
            java.util.concurrent.atomic.AtomicInteger yes = new java.util.concurrent.atomic.AtomicInteger();
            Thread[] threads = new Thread[screens];
            for (int index = 0; index < screens; index++) {
                threads[index] = new Thread(() -> {
                    try { together.await(); } catch (Exception interrupted) { return; }
                    if (store.reserveAttempt(1_000_000_000_000L, 600_000)) yes.incrementAndGet();
                });
                threads[index].start();
            }
            for (Thread thread : threads) thread.join(10_000);
            assertEquals("Round " + round, 1, yes.get());
        }
    }

    // ---- the files that are actually published ----

    private static Path repositoryFile(String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve(relative))) directory = directory.getParent();
        assertNotNull("Not found from the test's working directory: " + relative, directory);
        return directory.resolve(relative);
    }

    @Test public void theCommittedCertificateIsAcceptedUnderTheKeysTheAppEmbeddedAndIsWhatTheBuildStandsFor() throws Exception {
        Map<String, PublicKey> trusted = RemoteSettings.keys(new String(Files.readAllBytes(repositoryFile("android/app/src/main/res/raw/admin_keys.txt")), StandardCharsets.UTF_8));
        assertFalse("The app has to embed at least one key", trusted.isEmpty());
        assertTrue("and at most three", trusted.size() <= 3);
        byte[] file = Files.readAllBytes(repositoryFile("download/ota/admin-identity.json"));
        AdminIdentity.Certificate certificate = AdminIdentity.verify(file, trusted, AdminOnboarding.OFFICIAL.userId());
        assertEquals("The build stands for exactly the certificate that is published", AdminIdentity.BUILD_SEQUENCE_FLOOR, certificate.sequence());
        assertEquals("and for the identity that certificate names", AdminOnboarding.OFFICIAL.deviceId(), certificate.deviceId());
        assertEquals(AdminOnboarding.OFFICIAL.fingerprint(), certificate.fingerprint());
        assertEquals(AdminOnboarding.OFFICIAL, certificate.pin(AdminOnboarding.OFFICIAL.origin()));
        assertFalse("so the published file adds nothing the build does not know", AdminIdentity.newer(certificate, null, AdminIdentity.BUILD_SEQUENCE_FLOOR));
        // The settings key does not certify identities: the two lists are different keys.
        Map<String, PublicKey> settingsKeys = RemoteSettings.keys(new String(Files.readAllBytes(repositoryFile("android/app/src/main/res/raw/ota_keys.txt")), StandardCharsets.UTF_8));
        for (Map.Entry<String, PublicKey> entry : settingsKeys.entrySet())
            for (PublicKey admin : trusted.values()) assertNotEquals(entry.getValue(), admin);
        // And the same bytes are refused once a key other than the app's signs something else.
        JsonObject envelope = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        rejected(SIGNATURE, file, new Signer(envelope.get("key").getAsString()).trusted());
    }

    @Test public void theBuiltInPinIsTheIdentityOfASinglePhoneOfTheOfficialAccount() {
        AdminOnboarding.Pin pin = AdminOnboarding.OFFICIAL;
        assertEquals(ADMIN, pin.userId());
        assertTrue(pin.origin().startsWith("https://") && pin.origin().endsWith("/"));
        assertTrue(pin.fingerprint().matches("[0-9a-f]{64}"));
    }

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
}
