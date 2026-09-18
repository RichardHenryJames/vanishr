package app.vanishr.relay;

import app.vanishr.crypto.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static app.vanishr.relay.RelayTypes.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"server.ssl.enabled=false", "TLS_KEYSTORE_PASSWORD=test-only", "DATABASE_PASSWORD=test-only", "REDIS_PASSWORD=", "spring.data.redis.ssl.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers
class RelayIntegrationTest {
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>("redis@sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d")
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction");
        @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse(
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.data.redis.host", redisContainer::getHost);
        properties.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate database;

    record Device(UUID userId, UUID deviceId, String token, SignalClient crypto) { }

    @BeforeEach void emptyEphemeralTestInfrastructure() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        database.update("DELETE FROM accounts");
    }

    private ResultActions request(MockHttpServletRequestBuilder request, Device device) throws Exception {
        request.secure(true);
        if (device != null) request.header("Authorization", "Bearer " + device.token());
        return http.perform(request);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsBytes(body));
    }

    private Device device(String handle) throws Exception {
        String registration = request(body(post("/auth/register"), Map.of("handle", handle, "password", "test-only-password-12345")), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        AuthService.Token bootstrap = json.readValue(registration, AuthService.Token.class);
        UUID deviceId = UUID.randomUUID();
        SignalClient crypto = new SignalClient(bootstrap.userId(), new TestVault());
        Device enrolling = new Device(bootstrap.userId(), deviceId, bootstrap.accessToken(), crypto);
        String registered = request(body(post("/devices"), new AccountDirectory.DeviceRequest(deviceId, crypto.publicIdentity(), false)), enrolling)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        AuthService.Token token = json.readValue(registered, AuthService.Token.class);
        Device device = new Device(token.userId(), deviceId, token.accessToken(), crypto);
        request(body(post("/keys"), Map.of("keys", List.of(crypto.generatePreKey(Instant.now())))), device).andExpect(status().isNoContent());
        return device;
    }

    private void verifyAndEstablish(Device sender, Device recipient) throws Exception {
        sender.crypto().verifyPeer(recipient.userId(), recipient.crypto().publicIdentity());
        recipient.crypto().verifyPeer(sender.userId(), sender.crypto().publicIdentity());
        String response = request(post("/keys/" + recipient.userId() + "/claim"), sender).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        sender.crypto().establish(recipient.userId(), json.readValue(response, PublicBundle.class), Instant.now());
    }

    private SendRequest encrypted(Device sender, Device recipient, byte[] plaintext, Expiry expiry, long deadline, UUID mediaId) throws Exception {
        SignalClient.Packet packet = sender.crypto().encrypt(recipient.userId(), plaintext, Instant.now());
        return new SendRequest(UUID.randomUUID(), recipient.userId(), recipient.deviceId(), expiry, deadline, packet.type(), packet.ciphertext(), mediaId);
    }

    private Message pending(Device recipient) throws Exception {
        String response = request(get("/messages/pending"), recipient).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Message[] messages = json.readValue(response, Message[].class);
        assertEquals(1, messages.length);
        return messages[0];
    }

    @Test void helloCrossesTheRelayOnlyAsCiphertextAndReadDeletesIt() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        verifyAndEstablish(alice, bob);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        SendRequest encrypted = encrypted(alice, bob, plaintext, Expiry.VIEW_ONCE, System.currentTimeMillis() + 60_000, null);
        String wire = json.writeValueAsString(encrypted);
        assertFalse(wire.contains("hello"));
        request(body(post("/messages"), encrypted), alice).andExpect(status().isCreated());
        String stored = redis.opsForValue().get("m:" + encrypted.id());
        assertNotNull(stored);
        assertFalse(stored.contains("hello"));
        assertFalse(stored.contains(Base64.getEncoder().encodeToString(plaintext)));
        assertTrue(redis.getExpire("m:" + encrypted.id()) > 0);

        SignalClient serverWithoutDeviceKeys = new SignalClient(UUID.randomUUID(), new TestVault());
        assertThrows(Exception.class, () -> serverWithoutDeviceKeys.decrypt(alice.userId(), new SignalClient.Packet(encrypted.type(), encrypted.ciphertext())));
        Message delivered = pending(bob);
        assertArrayEquals(plaintext, bob.crypto().decrypt(alice.userId(), new SignalClient.Packet(delivered.type(), delivered.ciphertext())));
        request(post("/messages/" + encrypted.id() + "/delivered"), bob).andExpect(status().isOk());
        assertTrue(Boolean.TRUE.equals(redis.hasKey("m:" + encrypted.id())));
        request(post("/messages/" + encrypted.id() + "/read"), bob).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READ"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + encrypted.id())));
        request(get("/messages/pending"), bob).andExpect(content().json("[]"));
        assertTrue(redis.getExpire("r:" + encrypted.id()) > 0);
    }

    @Test void imageIsEncryptedBeforeUploadAndItsKeyTravelsInsideSignal() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        Device stranger = device("stranger");
        verifyAndEstablish(alice, bob);
        UUID mediaId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 60_000;
        byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        ImageCipher.EncryptedImage sealed = ImageCipher.encrypt(mediaId, image);
        assertFalse(Arrays.equals(image, sealed.ciphertext()));
        request(put("/media/" + mediaId).param("recipientId", bob.userId().toString()).param("recipientDeviceId", bob.deviceId().toString())
                .param("expiresAt", Long.toString(deadline)).contentType("application/octet-stream").content(sealed.ciphertext()), alice).andExpect(status().isCreated());
        request(get("/media/" + mediaId), bob).andExpect(status().isNotFound());
        assertTrue(redis.getExpire("b:" + mediaId) > 0);
        byte[] descriptor = json.writeValueAsBytes(Map.of("id", mediaId, "key", sealed.key(), "nonce", sealed.nonce()));
        SendRequest encrypted = encrypted(alice, bob, descriptor, Expiry.VIEW_ONCE, deadline, mediaId);
        request(body(post("/messages"), encrypted), alice).andExpect(status().isCreated());
        String stored = redis.opsForValue().get("b:" + mediaId);
        assertFalse(stored.contains(Base64.getEncoder().encodeToString(sealed.key())));
        assertFalse(stored.contains(Base64.getEncoder().encodeToString(image)));
        request(get("/media/" + mediaId), stranger).andExpect(status().isNotFound());
        Message message = pending(bob);
        JsonNode decrypted = json.readTree(bob.crypto().decrypt(alice.userId(), new SignalClient.Packet(message.type(), message.ciphertext())));
        byte[] received = request(get("/media/" + mediaId), bob).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(image, ImageCipher.decrypt(UUID.fromString(decrypted.get("id").asText()), received,
                decrypted.get("key").binaryValue(), decrypted.get("nonce").binaryValue()));
        assertThrows(Exception.class, () -> ImageCipher.decrypt(mediaId, received, new byte[32], sealed.nonce()));
        request(post("/messages/" + message.id() + "/read"), bob).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + message.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("b:" + mediaId)));
        request(get("/media/" + mediaId), bob).andExpect(status().isNotFound());
    }

    @Test void deliveryDeletesTimedContentAndRetriesCannotResurrectItOrExtendTtl() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        Device stranger = device("stranger");
        verifyAndEstablish(alice, bob);
        redis.opsForZSet().add("inbox:" + bob.deviceId(), "expired-test-id", 1);
        redis.expire("inbox:" + bob.deviceId(), Duration.ofSeconds(60));
        redis.opsForZSet().add("outbox:" + alice.deviceId(), "expired-test-id", 1);
        redis.expire("outbox:" + alice.deviceId(), Duration.ofSeconds(60));
        SendRequest encrypted = encrypted(alice, bob, "hello".getBytes(StandardCharsets.UTF_8), Expiry.HOUR_1, System.currentTimeMillis() + 60_000, null);
        request(body(post("/messages"), encrypted), alice).andExpect(status().isCreated());
        assertNull(redis.opsForZSet().score("inbox:" + bob.deviceId(), "expired-test-id"));
        assertNull(redis.opsForZSet().score("outbox:" + alice.deviceId(), "expired-test-id"));
        request(post("/messages/" + encrypted.id() + "/read"), stranger).andExpect(status().isNotFound());
        request(delete("/messages/" + encrypted.id()), stranger).andExpect(status().isNotFound());
        request(post("/messages/" + encrypted.id() + "/delivered"), alice).andExpect(status().isNotFound());
        request(post("/messages/" + encrypted.id() + "/delivered"), bob).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + encrypted.id())));
        request(get("/messages/status").param("ids", encrypted.id().toString()), stranger).andExpect(content().json("[]"));
        request(get("/messages/status").param("ids", encrypted.id().toString()), alice).andExpect(jsonPath("$[0].state").value("DELIVERED"));
        long before = redis.getExpire("r:" + encrypted.id(), java.util.concurrent.TimeUnit.MILLISECONDS);
        request(body(post("/messages"), encrypted), alice).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("DELIVERED"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + encrypted.id())));
        assertTrue(redis.getExpire("r:" + encrypted.id(), java.util.concurrent.TimeUnit.MILLISECONDS) <= before);
        SendRequest conflicting = new SendRequest(encrypted.id(), bob.userId(), bob.deviceId(), encrypted.expiry(), encrypted.expiresAt() + 1,
                encrypted.type(), encrypted.ciphertext(), null);
        request(body(post("/messages"), conflicting), alice).andExpect(status().isConflict());
    }

    @Test void offlineMessageAndImageExpireWithoutAnyRecipientAcknowledgement() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        verifyAndEstablish(alice, bob);
        UUID mediaId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 900;
        ImageCipher.EncryptedImage image = ImageCipher.encrypt(mediaId, new byte[]{1, 2, 3});
        request(put("/media/" + mediaId).param("recipientId", bob.userId().toString()).param("recipientDeviceId", bob.deviceId().toString())
                .param("expiresAt", Long.toString(deadline)).contentType("application/octet-stream").content(image.ciphertext()), alice).andExpect(status().isCreated());
        SendRequest encrypted = encrypted(alice, bob, "hello".getBytes(StandardCharsets.UTF_8), Expiry.HOURS_24, deadline, mediaId);
        request(body(post("/messages"), encrypted), alice).andExpect(status().isCreated());
        await().atMost(Duration.ofSeconds(4)).until(() -> !Boolean.TRUE.equals(redis.hasKey("m:" + encrypted.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("b:" + mediaId)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("r:" + encrypted.id())));
    }

    @Test void authenticationTlsValidationAndTokenRevocationAreEnforced() throws Exception {
        request(get("/messages/pending"), null).andExpect(status().isUnauthorized());
        http.perform(get("/health")).andExpect(status().isUpgradeRequired());
        Device alice = device("alice");
        request(post("/messages").contentType("application/json").content("{\"plaintext\":\"hello\"}"), alice)
                .andExpect(status().isBadRequest()).andExpect(content().string("{\"error\":\"invalid_request\"}"));
        request(get("/auth/me"), alice).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        String session = redis.opsForValue().get(AuthService.tokenKey(alice.token()));
        assertNotNull(session);
        assertFalse(session.contains(alice.token()));
        request(post("/auth/logout"), alice).andExpect(status().isNoContent());
        request(get("/auth/me"), alice).andExpect(status().isUnauthorized());
    }

    @Test void replacementRevokesOldTokensEvenWhenTheDeviceIdIsReused() throws Exception {
        Device original = device("alice");
        String login = request(body(post("/auth/login"), Map.of("handle", "alice", "password", "test-only-password-12345")), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AuthService.Token token = json.readValue(login, AuthService.Token.class);
        Device enrolling = new Device(original.userId(), original.deviceId(), token.accessToken(), original.crypto());
        request(get("/messages/pending"), enrolling).andExpect(status().isForbidden());
        byte[] replacementIdentity = org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize();
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(original.deviceId(), replacementIdentity, true)), enrolling)
                .andExpect(status().isCreated());
        request(get("/auth/me"), original).andExpect(status().isUnauthorized());
    }

        @Test void usernameChangesAreUniqueOwnerScopedAndPreserveIdentity() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        UUID originalVersion = database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, alice.deviceId());
        request(body(patch("/account/username"), Map.of("handle", "renamed_alice")), null).andExpect(status().isUnauthorized());
        request(body(patch("/account/username"), Map.of("handle", "stolen_name", "userId", bob.userId())), alice)
            .andExpect(status().isBadRequest());
        request(body(patch("/account/username"), Map.of("handle", "bob")), alice).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("account_unavailable"));
        request(body(patch("/account/username"), Map.of("handle", "Invalid Name")), alice).andExpect(status().isBadRequest());
        request(body(patch("/account/username"), Map.of("handle", "renamed_alice")), alice).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(alice.userId().toString())).andExpect(jsonPath("$.handle").value("renamed_alice"));
        request(get("/account/username"), bob).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value("bob"));
        request(get("/users/alice"), bob).andExpect(status().isNotFound());
        request(get("/users/renamed_alice"), bob).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(alice.userId().toString()))
            .andExpect(jsonPath("$.deviceId").value(alice.deviceId().toString()))
            .andExpect(jsonPath("$.identityKey").value(Base64.getEncoder().encodeToString(alice.crypto().publicIdentity())));
        assertEquals(originalVersion, database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, alice.deviceId()));
        request(body(post("/auth/login"), Map.of("handle", "alice", "password", "test-only-password-12345")), null).andExpect(status().isUnauthorized());
        request(body(post("/auth/login"), Map.of("handle", "renamed_alice", "password", "test-only-password-12345", "deviceId", alice.deviceId())), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(alice.userId().toString()));
        }

    @Test void concurrentUsernameClaimsCannotAssignTheSameHandleToTwoAccounts() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
            for (Device owner : List.of(alice, bob)) attempts.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Concurrent rename fixture did not start");
                try { directory.rename(owner.userId(), new AccountDirectory.UsernameChange("same_handle")); return true; }
                catch (ApiException failure) {
                    assertEquals(org.springframework.http.HttpStatus.CONFLICT, failure.status);
                    return false;
                }
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int succeeded = 0;
            for (var attempt : attempts) if (attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded);
        }
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE handle = 'same_handle'", Integer.class));
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
    }

    @Test void renamedGoogleAccountKeepsItsSubjectMappingAndDevice() throws Exception {
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        AccountDirectory.GoogleAccount original = directory.googleAccount("google-rename-fixture");
        SignalClient crypto = new SignalClient(original.userId(), new TestVault());
        UUID deviceId = UUID.randomUUID();
        directory.registerDevice(original.userId(), new AccountDirectory.DeviceRequest(deviceId, crypto.publicIdentity(), false));
        directory.rename(original.userId(), new AccountDirectory.UsernameChange("google_new_handle"));
        AccountDirectory.GoogleAccount returning = directory.googleAccount("google-rename-fixture");
        assertEquals(original.userId(), returning.userId());
        assertEquals("google_new_handle", returning.handle());
        assertEquals(deviceId, directory.contact(returning.userId()).deviceId());
        assertEquals(Base64.getEncoder().encodeToString(crypto.publicIdentity()), directory.contact(returning.userId()).identityKey());
    }

    @Test void signOutRemainsAvailableWhenAuthenticationAttemptsAreRateLimited() throws Exception {
        Device signedIn = device("signed_in");
        for (int attempt = 0; attempt < 9; attempt++) {
            request(body(post("/auth/login"), Map.of("handle", "unknown_account_" + attempt, "password", "test-only-password-12345")), null)
                    .andExpect(status().isUnauthorized());
        }
        request(get("/auth/me"), signedIn).andExpect(status().isOk());
        request(post("/auth/logout"), signedIn).andExpect(status().isNoContent());
        request(get("/auth/me"), signedIn).andExpect(status().isUnauthorized());
        request(body(post("/auth/login"), Map.of("handle", "signed_in", "password", "test-only-password-12345")), null)
                .andExpect(status().isTooManyRequests());
        for (int attempt = 0; attempt < 10; attempt++) {
            request(body(post("/auth/google/challenge"), new GoogleAuth.Start(null)), null).andExpect(status().isServiceUnavailable());
        }
        request(body(post("/auth/google/challenge"), new GoogleAuth.Start(null)), null).andExpect(status().isTooManyRequests());
    }

        @Test void returningDeviceKeepsQueuedMessagesIdentityAndPrekeysAfterSignOut() throws Exception {
        Device sender = device("sender");
        Device recipient = device("recipient");
        verifyAndEstablish(sender, recipient);
        request(body(post("/keys"), Map.of("keys", List.of(recipient.crypto().generatePreKey(Instant.now())))), recipient)
            .andExpect(status().isNoContent());
        UUID originalVersion = database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, recipient.deviceId());
        int originalKeys = database.queryForObject("SELECT COUNT(*) FROM prekeys WHERE device_id = ?", Integer.class, recipient.deviceId());
        request(post("/auth/logout"), recipient).andExpect(status().isNoContent());
        request(get("/messages/pending"), recipient).andExpect(status().isUnauthorized());
        byte[] plaintext = "message while signed out".getBytes(StandardCharsets.UTF_8);
        SendRequest queued = encrypted(sender, recipient, plaintext, Expiry.HOUR_1, System.currentTimeMillis() + 60_000, null);
        request(body(post("/messages"), queued), sender).andExpect(status().isCreated());
        request(post("/auth/logout"), sender).andExpect(status().isNoContent());
        assertTrue(Boolean.TRUE.equals(redis.hasKey("m:" + queued.id())));
        long before = redis.getExpire("m:" + queued.id(), java.util.concurrent.TimeUnit.MILLISECONDS);

        String login = request(body(post("/auth/login"), Map.of("handle", "recipient", "password", "test-only-password-12345")), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AuthService.Token enrollment = json.readValue(login, AuthService.Token.class);
        Device enrolling = new Device(recipient.userId(), recipient.deviceId(), enrollment.accessToken(), recipient.crypto());
        byte[] changedIdentity = org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize();
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(recipient.deviceId(), changedIdentity, false)), enrolling)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("device_already_registered"));
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(UUID.randomUUID(), recipient.crypto().publicIdentity(), false)), enrolling)
            .andExpect(status().isConflict());
        String resumed = request(body(post("/devices"), new AccountDirectory.DeviceRequest(recipient.deviceId(), recipient.crypto().publicIdentity(), false)), enrolling)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        AuthService.Token session = json.readValue(resumed, AuthService.Token.class);
        Device returning = new Device(recipient.userId(), recipient.deviceId(), session.accessToken(), recipient.crypto());
        assertEquals(recipient.deviceId(), session.deviceId());
        assertEquals(originalVersion, database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, recipient.deviceId()));
        assertEquals(originalKeys, database.queryForObject("SELECT COUNT(*) FROM prekeys WHERE device_id = ?", Integer.class, recipient.deviceId()));
        assertTrue(redis.getExpire("m:" + queued.id(), java.util.concurrent.TimeUnit.MILLISECONDS) <= before);
        request(get("/auth/me"), enrolling).andExpect(status().isUnauthorized());
        Message message = pending(returning);
        assertEquals(queued.expiresAt(), message.expiresAt());
        assertArrayEquals(plaintext, returning.crypto().decrypt(sender.userId(), new SignalClient.Packet(message.type(), message.ciphertext())));
        request(post("/messages/" + message.id() + "/read"), returning).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + message.id())));
        }

    @Test void detachedUploadsAndEveryEphemeralKeyHaveBoundedTtls() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        UUID mediaId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 86_390_000;
        byte[] encryptedImage = ImageCipher.encrypt(mediaId, new byte[]{1, 2, 3}).ciphertext();
        request(put("/media/" + mediaId).param("recipientId", bob.userId().toString()).param("recipientDeviceId", bob.deviceId().toString())
                .param("expiresAt", Long.toString(deadline)).contentType("application/octet-stream").content(encryptedImage), alice)
                .andExpect(status().isCreated());
        assertTrue(redis.getExpire("b:" + mediaId) <= 300);
        for (String key : Objects.requireNonNull(redis.keys("*"))) {
            Long ttl = redis.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl >= 0 && ttl <= 86400, "Ephemeral key must have bounded expiry");
        }
    }

    @Test void productionRelayClassesHaveNoClientCryptoAndSchemaHasNoContentTables() throws Exception {
        try (var paths = Files.walk(Path.of("target/classes/app/vanishr/relay"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".class")).toList()) {
                String bytecode = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("org/signal/"), path.toString());
                assertFalse(bytecode.contains("app/vanishr/crypto/"), path.toString());
            }
        }
        List<String> tables = database.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name", String.class);
        assertEquals(List.of("accounts", "devices", "flyway_schema_history", "prekeys"), tables);
    }

    @Test void googleIdentityMappingIsStableSeparateAndDoesNotImportGoogleProfileData() throws Exception {
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        AccountDirectory.GoogleAccount first = directory.googleAccount("google-subject-fixture");
        AccountDirectory.GoogleAccount returning = directory.googleAccount("google-subject-fixture");
        assertEquals(first, returning);
        assertNotEquals(first.userId(), directory.googleAccount("other-google-subject-fixture").userId());
        assertNull(database.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, first.userId()));
        request(body(post("/auth/login"), Map.of("handle", first.handle(), "password", "test-only-password-12345")), null)
                .andExpect(status().isUnauthorized());
        request(body(post("/auth/google/challenge"), new GoogleAuth.Start(null)), null)
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"google_sign_in_unavailable\"}"));
        List<String> fields = database.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'accounts' AND table_schema = 'public' ORDER BY ordinal_position", String.class);
        assertEquals(List.of("id", "handle", "password_hash", "google_subject", "display_name"), fields);
        assertNull(database.queryForObject("SELECT display_name FROM accounts WHERE id = ?", String.class, first.userId()));
    }

        @Test void profileNamesAreOwnedByTheirAccountAndDoNotRequireUniqueness() throws Exception {
        Device alice = device("alice");
        Device bob = device("bob");
        request(get("/users/id/" + alice.userId() + "/profile"), null).andExpect(status().isUnauthorized());
        request(body(patch("/account/profile"), Map.of("displayName", "Alice Example", "userId", bob.userId())), alice)
            .andExpect(status().isBadRequest());
        request(body(patch("/account/profile"), Map.of("displayName", "  Alice Example  ")), alice).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Alice Example"));
        request(body(patch("/account/profile"), Map.of("displayName", "Alice Example")), bob).andExpect(status().isOk());
        request(body(patch("/account/profile"), Map.of("displayName", "Receiver Profile")), bob).andExpect(status().isOk());
        request(get("/users/id/" + alice.userId() + "/profile"), bob).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(alice.userId().toString())).andExpect(jsonPath("$.displayName").value("Alice Example"));
        request(get("/users/id/" + bob.userId() + "/profile"), alice).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Receiver Profile"));
        request(body(patch("/account/profile"), Map.of("displayName", "   ")), alice).andExpect(status().isBadRequest());
        request(body(patch("/account/profile"), Map.of("displayName", "bad\nname")), alice).andExpect(status().isBadRequest());
        request(body(patch("/account/profile"), Map.of("displayName", "x".repeat(41))), alice).andExpect(status().isBadRequest());
        request(body(patch("/users/id/" + bob.userId() + "/profile"), Map.of("displayName", "Not the owner")), alice)
            .andExpect(status().isMethodNotAllowed());
        request(get("/account/profile"), bob).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Receiver Profile"));
        }

    static final class TestVault implements SecureVault {
        private Map<String, byte[]> values = new HashMap<>();
        public byte[] get(String name) { return values.get(name); }
        public void put(String name, byte[] value) { values.put(name, value.clone()); }
        public void remove(String name) { values.remove(name); }
        public List<String> names(String prefix) { return values.keySet().stream().filter(name -> name.startsWith(prefix)).collect(Collectors.toList()); }
        public <Result> Result transaction(Operation<Result> operation) throws Exception {
            Map<String, byte[]> before = new HashMap<>(values);
            try { return operation.run(); }
            catch (Exception failure) { values = before; throw failure; }
        }
    }
}