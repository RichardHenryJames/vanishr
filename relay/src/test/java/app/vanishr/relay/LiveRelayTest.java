package app.vanishr.relay;

import app.vanishr.crypto.ImageCipher;
import app.vanishr.crypto.PublicBundle;
import app.vanishr.crypto.SignalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.vanishr.relay.RelayTypes.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "vanishr.liveOrigin", matches = "https://.+")
class LiveRelayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final URI origin = URI.create(System.getProperty("vanishr.liveOrigin"));

    private record LiveDevice(UUID userId, UUID deviceId, String token, SignalClient crypto) {
        @Override public String toString() { return "LiveDevice[redacted]"; }
    }

    private record LiveAccount(String handle, String password, LiveDevice device) {
        @Override public String toString() { return "LiveAccount[redacted]"; }
    }

    private HttpClient client() throws Exception {
        assertEquals("https", origin.getScheme());
        assertNull(origin.getUserInfo());
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        String caFile = System.getProperty("vanishr.liveCa");
        if (caFile != null) {
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
            roots.load(null);
            try (var input = Files.newInputStream(Path.of(caFile))) {
                roots.setCertificateEntry("test-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(roots);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            builder.sslContext(tls);
        }
        return builder.build();
    }

    private byte[] exchange(HttpClient client, String method, String path, String token,
                            byte[] body, String contentType, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expectedStatus, response.statusCode(), method + " " + path);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return response.body();
    }

    private byte[] call(HttpClient client, String method, String path, String token, Object body, int status) throws Exception {
        return exchange(client, method, path, token, body == null ? null : json.writeValueAsBytes(body), "application/json", status);
    }

    private LiveDevice enroll(HttpClient client) throws Exception {
        return enroll(client, "probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), UUID.randomUUID().toString());
    }

    private LiveAccount enrollAccount(HttpClient client) throws Exception {
        String handle = "probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String password = UUID.randomUUID().toString();
        return new LiveAccount(handle, password, enroll(client, handle, password));
    }

    private LiveDevice enroll(HttpClient client, String handle, String password) throws Exception {
        AuthService.Token enrollment = json.readValue(call(client, "POST", "/auth/register", null,
                Map.of("handle", handle, "password", password), 201), AuthService.Token.class);
        UUID deviceId = UUID.randomUUID();
        SignalClient crypto = new SignalClient(enrollment.userId(), new RelayIntegrationTest.TestVault());
        AuthService.Token registered = json.readValue(call(client, "POST", "/devices", enrollment.accessToken(),
                new AccountDirectory.DeviceRequest(deviceId, crypto.publicIdentity(), false), 201), AuthService.Token.class);
        call(client, "POST", "/keys", registered.accessToken(), Map.of("keys", List.of(crypto.generatePreKey(Instant.now()))), 204);
        return new LiveDevice(enrollment.userId(), deviceId, registered.accessToken(), crypto);
    }

    private SendRequest send(HttpClient client, LiveDevice sender, LiveDevice recipient, byte[] content,
                             UUID mediaId, long deadline) throws Exception {
        SignalClient.Packet packet = sender.crypto().encrypt(recipient.userId(), content, Instant.now());
        SendRequest request = new SendRequest(UUID.randomUUID(), recipient.userId(), recipient.deviceId(), Expiry.VIEW_ONCE,
                deadline, packet.type(), packet.ciphertext(), mediaId);
        call(client, "POST", "/messages", sender.token(), request, 201);
        return request;
    }

    private byte[] receive(HttpClient client, LiveDevice sender, LiveDevice recipient, UUID id) throws Exception {
        Message[] pending = json.readValue(call(client, "GET", "/messages/pending", recipient.token(), null, 200), Message[].class);
        Message message = Arrays.stream(pending).filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
        return recipient.crypto().decrypt(sender.userId(), new SignalClient.Packet(message.type(), message.ciphertext()));
    }

    @Test void realHttpsCarriesSignalTextAndMaximumEncryptedBlobsAndDeletesThemOnRead() throws Exception {
        try (HttpClient client = client()) {
            call(client, "GET", "/health", null, null, 200);
            call(client, "GET", "/messages/pending", null, null, 401);
            LiveDevice alice = enroll(client);
            LiveDevice bob = enroll(client);
            Throwable primaryFailure = null;
            try {
                alice.crypto().verifyPeer(bob.userId(), bob.crypto().publicIdentity());
                bob.crypto().verifyPeer(alice.userId(), alice.crypto().publicIdentity());
                PublicBundle bundle = json.readValue(call(client, "POST", "/keys/" + bob.userId() + "/claim", alice.token(), null, 200), PublicBundle.class);
                alice.crypto().establish(bob.userId(), bundle, Instant.now());
                SendRequest hello = send(client, alice, bob, "hello".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis() + 300_000);
                assertEquals("hello", new String(receive(client, alice, bob, hello.id()), StandardCharsets.UTF_8));
                call(client, "POST", "/messages/" + hello.id() + "/read", bob.token(), null, 200);
                byte[] plaintextBlob = new byte[ImageCipher.MAX_IMAGE_BYTES];
                new SecureRandom().nextBytes(plaintextBlob);
                try {
                    for (int iteration = 0; iteration < 3; iteration++) {
                        UUID mediaId = UUID.randomUUID();
                        long deadline = System.currentTimeMillis() + 300_000;
                        ImageCipher.EncryptedImage encrypted = ImageCipher.encrypt(mediaId, plaintextBlob);
                        String uploadPath = "/media/" + mediaId + "?recipientId=" + bob.userId() + "&recipientDeviceId=" + bob.deviceId() + "&expiresAt=" + deadline;
                        exchange(client, "PUT", uploadPath, alice.token(), encrypted.ciphertext(), "application/octet-stream", 201);
                        byte[] descriptor = json.writeValueAsBytes(Map.of("id", mediaId, "key", encrypted.key(), "nonce", encrypted.nonce()));
                        SendRequest message = send(client, alice, bob, descriptor, mediaId, deadline);
                        JsonNode receivedKey = json.readTree(receive(client, alice, bob, message.id()));
                        call(client, "GET", "/media/" + mediaId, alice.token(), null, 404);
                        byte[] downloaded = exchange(client, "GET", "/media/" + mediaId, bob.token(), null, null, 200);
                        assertArrayEquals(plaintextBlob, ImageCipher.decrypt(mediaId, downloaded, receivedKey.get("key").binaryValue(), receivedKey.get("nonce").binaryValue()));
                        call(client, "POST", "/messages/" + message.id() + "/delivered", bob.token(), null, 200);
                        call(client, "POST", "/messages/" + message.id() + "/read", bob.token(), null, 200);
                        call(client, "GET", "/media/" + mediaId, bob.token(), null, 404);
                        Arrays.fill(encrypted.key(), (byte) 0);
                        Arrays.fill(descriptor, (byte) 0);
                    }
                } finally { Arrays.fill(plaintextBlob, (byte) 0); }
                Message[] pending = json.readValue(call(client, "GET", "/messages/pending", bob.token(), null, 200), Message[].class);
                assertEquals(0, pending.length);
            } catch (Exception | AssertionError failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                for (LiveDevice device : List.of(alice, bob)) {
                    try { call(client, "POST", "/auth/logout", device.token(), null, 204); }
                    catch (Exception | AssertionError cleanupFailure) {
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                        else throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private void erase(HttpClient client, LiveAccount account) throws Exception {
        AuthService.Token confirming = json.readValue(call(client, "POST", "/auth/login", null,
                Map.of("handle", account.handle(), "password", account.password()), 200), AuthService.Token.class);
        call(client, "DELETE", "/account", confirming.accessToken(), Map.of("confirmation", "DELETE"), 204);
        call(client, "GET", "/account/backup/status", account.device().token(), null, 401);
    }

    @Test void realHttpsStoresOneOpaqueBoundedBackupPerOwnerAndErasesItWithTheAccount() throws Exception {
        try (HttpClient client = client()) {
            LiveAccount owner = enrollAccount(client);
            LiveAccount other = enrollAccount(client);
            String ownerToken = owner.device().token(), otherToken = other.device().token();
            Throwable primaryFailure = null;
            try {
                call(client, "GET", "/account/backup/status", null, null, 401);
                assertFalse(json.readTree(call(client, "GET", "/account/backup/status", ownerToken, null, 200)).get("exists").asBoolean());
                call(client, "GET", "/account/backup", ownerToken, null, 404);

                byte[] sealed = randomBytes(2_048);
                JsonNode stored = json.readTree(exchange(client, "PUT", "/account/backup", ownerToken, sealed, "application/octet-stream", 200));
                assertTrue(stored.get("exists").asBoolean());
                assertEquals(sealed.length, stored.get("size").asInt());
                assertEquals(RelayPolicy.BACKUP_RETENTION_HOURS * 3_600_000L, stored.get("expiresAt").asLong() - stored.get("updatedAt").asLong());
                assertArrayEquals(sealed, exchange(client, "GET", "/account/backup", ownerToken, null, null, 200));

                call(client, "GET", "/account/backup", otherToken, null, 404);
                assertFalse(json.readTree(call(client, "GET", "/account/backup/status", otherToken, null, 200)).get("exists").asBoolean());

                exchange(client, "PUT", "/account/backup", ownerToken, randomBytes(RelayPolicy.MIN_BACKUP_BYTES - 1), "application/octet-stream", 400);
                exchange(client, "PUT", "/account/backup", ownerToken, randomBytes(RelayPolicy.MAX_BACKUP_BYTES + 1), "application/octet-stream", 413);
                assertArrayEquals(sealed, exchange(client, "GET", "/account/backup", ownerToken, null, null, 200));
                byte[] maximum = randomBytes(RelayPolicy.MAX_BACKUP_BYTES);
                exchange(client, "PUT", "/account/backup", ownerToken, maximum, "application/octet-stream", 200);
                assertArrayEquals(maximum, exchange(client, "GET", "/account/backup", ownerToken, null, null, 200));

                call(client, "DELETE", "/account/backup", ownerToken, null, 204);
                call(client, "GET", "/account/backup", ownerToken, null, 404);
                exchange(client, "PUT", "/account/backup", otherToken, sealed, "application/octet-stream", 200);
                call(client, "GET", "/account/backup", ownerToken, null, 404);
            } catch (Exception | AssertionError failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                for (LiveAccount account : List.of(owner, other)) {
                    try { erase(client, account); }
                    catch (Exception | AssertionError cleanupFailure) {
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                        else throw cleanupFailure;
                    }
                }
            }
        }
    }
}