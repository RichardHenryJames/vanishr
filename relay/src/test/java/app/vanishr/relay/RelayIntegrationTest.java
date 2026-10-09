package app.vanishr.relay;

import app.vanishr.crypto.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.mockito.Mockito.*;
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
    @Autowired PlatformTransactionManager transactions;
    @Autowired AuthService authentication;
    @Autowired GroupDirectory groups;
    @Autowired GroupMessages groupMessages;
    @Autowired GenericNotifier notifications;
    @Autowired RealtimeHub realtime;

    record Device(UUID userId, UUID deviceId, String token, SignalClient crypto) { }

    @BeforeEach void emptyEphemeralTestInfrastructure() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        // Only the disposable Testcontainers database resets its permanent admin pin.
        database.execute("ALTER TABLE admin_identity DISABLE TRIGGER admin_identity_immutable");
        database.execute("ALTER TABLE accounts DISABLE TRIGGER accounts_admin_truncate");
        try { database.execute("TRUNCATE TABLE accounts, admin_identity, account_deletion_receipts CASCADE"); }
        finally {
            database.execute("ALTER TABLE accounts ENABLE TRIGGER accounts_admin_truncate");
            database.execute("ALTER TABLE admin_identity ENABLE TRIGGER admin_identity_immutable");
        }
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
        return device(handle, "127.0.0.1");
    }

    private Device device(String handle, String source) throws Exception {
        String registration = request(body(post("/auth/register").with(request -> { request.setRemoteAddr(source); return request; }),
                Map.of("handle", handle, "password", "test-only-password-12345")), null)
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

    private void assignAdmin(Device admin) {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            assertEquals(1, database.update("INSERT INTO admin_identity (user_id) VALUES (?)", admin.userId()));
            assertEquals(1, database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", admin.userId()));
        });
    }

    private void verifyAndEstablish(Device sender, Device recipient) throws Exception {
        sender.crypto().verifyPeer(recipient.userId(), recipient.crypto().publicIdentity());
        recipient.crypto().verifyPeer(sender.userId(), sender.crypto().publicIdentity());
        String response = request(post("/keys/" + recipient.userId() + "/claim"), sender).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        sender.crypto().establish(recipient.userId(), json.readValue(response, PublicBundle.class), Instant.now());
    }

    @Test void fallbackLetsTwentyNewAccountsMessageTheOfflineAdminAfterOneTimeKeysExpire() throws Exception {
        Device admin = device("fallback_admin");
        assignAdmin(admin);
        Instant now = Instant.now();
        PublicBundle fallback = admin.crypto().generateFallbackPreKey(now, now.plusSeconds(30L * 86400 - 300));
        long deadline = now.plusSeconds(30L * 86400 - 300).toEpochMilli();
        request(body(put("/keys/fallback"), Map.of("key", fallback, "expiresAt", deadline)), admin).andExpect(status().isNoContent());
        request(get("/keys"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.fallbackSupported").value(true))
                .andExpect(jsonPath("$.fallbackKeyId").value(fallback.kyberPreKeyId()))
                .andExpect(jsonPath("$.fallbackExpiresAt").value(deadline));
        database.update("UPDATE prekeys SET expires_at = now() - interval '1 second' WHERE device_id = ?", admin.deviceId());
        for (int index = 0; index < 20; index++) {
            Device newcomer = device("new_fallback_" + index, "192.0.2." + (index + 1));
            request(get("/account/admin-contacts"), newcomer).andExpect(status().isOk())
                    .andExpect(jsonPath("$.contacts[0].userId").value(admin.userId().toString()));
            request(post("/keys/" + admin.userId() + "/claim"), newcomer).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("prekeys_unavailable"));
            String claimed = request(post("/keys/" + admin.userId() + "/claim").param("fallback", "true"), newcomer)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.preKeyId").value(0))
                    .andReturn().getResponse().getContentAsString();
            newcomer.crypto().verifyPeer(admin.userId(), admin.crypto().publicIdentity());
            admin.crypto().verifyPeer(newcomer.userId(), newcomer.crypto().publicIdentity());
            newcomer.crypto().establish(admin.userId(), json.readValue(claimed, PublicBundle.class), Instant.now());
            byte[] plaintext = ("new account " + index).getBytes(StandardCharsets.UTF_8);
            SendRequest sent = encrypted(newcomer, admin, plaintext, Expiry.HOUR_1, System.currentTimeMillis() + 3_599_000, null);
            request(body(post("/messages"), sent), newcomer).andExpect(status().isCreated());
            assertArrayEquals(plaintext, admin.crypto().decrypt(newcomer.userId(), new SignalClient.Packet(sent.type(), sent.ciphertext())));
        }
        request(get("/messages/pending"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        assertEquals(deadline, database.queryForObject("SELECT expires_at FROM fallback_prekeys WHERE device_id = ?",
                java.sql.Timestamp.class, admin.deviceId()).getTime());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM fallback_prekeys WHERE device_id = ?", Integer.class, admin.deviceId()));
    }

    @Test void fallbackPrefersOneTimeKeysAndKeepsPublicationRetriesBoundedAndOwnerScoped() throws Exception {
        Device recipient = device("fallback_recipient"), sender = device("fallback_sender");
        Instant now = Instant.now();
        long deadline = now.plusSeconds(3600).toEpochMilli();
        PublicBundle fallback = recipient.crypto().generateFallbackPreKey(now, Instant.ofEpochMilli(deadline));
        var upload = Map.of("key", fallback, "expiresAt", deadline);
        request(body(put("/keys/fallback"), upload), null).andExpect(status().isUnauthorized());
        request(body(put("/keys/fallback"), upload), sender).andExpect(status().isBadRequest());
        request(body(post("/keys"), Map.of("keys", List.of(fallback))), recipient).andExpect(status().isBadRequest());
        request(body(put("/keys/fallback"), upload), recipient).andExpect(status().isNoContent());
        request(body(put("/keys/fallback"), upload), recipient).andExpect(status().isNoContent());
        request(body(put("/keys/fallback"), Map.of("key", fallback, "expiresAt", deadline + 1000)), recipient)
                .andExpect(status().isConflict());
        request(post("/keys/" + recipient.userId() + "/claim").param("fallback", "true"), sender)
                .andExpect(status().isOk()).andExpect(jsonPath("$.preKeyId").value(org.hamcrest.Matchers.greaterThan(0)));
        request(post("/keys/" + recipient.userId() + "/claim").param("fallback", "true"), sender)
                .andExpect(status().isOk()).andExpect(jsonPath("$.preKeyId").value(0));
        PublicBundle rotated = recipient.crypto().generateFallbackPreKey(now, Instant.ofEpochMilli(deadline));
        request(body(put("/keys/fallback"), Map.of("key", rotated, "expiresAt", deadline)), recipient).andExpect(status().isNoContent());
        request(body(put("/keys/fallback"), upload), recipient).andExpect(status().isConflict());
        request(body(put("/keys/fallback"), Map.of("key", rotated, "expiresAt", now.plusSeconds(31L * 86400).toEpochMilli())), recipient)
                .andExpect(status().isBadRequest());
        database.update("UPDATE fallback_prekeys SET created_at = now() - interval '2 days', expires_at = now() - interval '1 second' WHERE device_id = ?", recipient.deviceId());
        request(post("/keys/" + recipient.userId() + "/claim").param("fallback", "true"), sender)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prekeys_unavailable"));
        database.update("DELETE FROM devices WHERE id = ?", recipient.deviceId());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM fallback_prekeys", Integer.class));
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

        private Presence.Peer presencePeer(Device device) {
        return new Presence.Peer(device.userId(), device.deviceId(), Base64.getEncoder().encodeToString(device.crypto().publicIdentity()));
        }

        private org.springframework.web.socket.WebSocketSession foreground(Device device) throws Exception {
        return foreground(device, false);
        }

        private org.springframework.web.socket.WebSocketSession foreground(Device device, boolean photos) throws Exception {
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("sessionKey", AuthService.tokenKey(device.token()), "photoChannel", photos));
        when(session.getPrincipal()).thenReturn(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new Actor(device.userId(), device.deviceId()), null));
        realtime.afterConnectionEstablished(session);
        return session;
        }

        @Test void presenceRequiresMutualPinnedContactsAndNeverSharesTypingWithAnotherContact() throws Exception {
        Device first = device("presence_first"), second = device("presence_second"), outsider = device("presence_outsider");
        foreground(first); foreground(second); foreground(outsider);
        Presence.Update firstUpdate = new Presence.Update(List.of(presencePeer(second)), null, 0);
        request(body(post("/presence"), firstUpdate), null).andExpect(status().isUnauthorized());
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        Presence.Update secondUpdate = new Presence.Update(List.of(presencePeer(first), presencePeer(outsider)), first.userId(), 5000);
        request(body(post("/presence"), secondUpdate), second).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].peer.userId").value(first.userId().toString())).andExpect(jsonPath("$[0].typingForMillis").value(0));
        String response = request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Presence.Status[] states = json.readValue(response, Presence.Status[].class);
        assertEquals(1, states.length); assertEquals(presencePeer(second), states[0].peer());
        assertTrue(states[0].onlineForMillis() > 0 && states[0].onlineForMillis() <= Presence.LIFETIME);
        assertTrue(states[0].typingForMillis() > 0 && states[0].typingForMillis() <= 5000);
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(first)), null, 0)), outsider)
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(second)), null, 0)), outsider)
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].typingForMillis").value(0));
        long ttl = redis.getExpire("presence:" + second.deviceId(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= Presence.LIFETIME);
        request(get("/users/presence_second"), outsider).andExpect(status().isOk()).andExpect(jsonPath("$.online").doesNotExist());
        request(get("/messages/pending"), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        }

        @Test void presenceDisappearsAfterDisconnectExpiryRevocationOrIdentityChange() throws Exception {
        Device first = device("presence_reader"), second = device("presence_writer");
        foreground(first);
        var connection = foreground(second);
        Presence.Update firstUpdate = new Presence.Update(List.of(presencePeer(second)), null, 0);
        Presence.Update secondUpdate = new Presence.Update(List.of(presencePeer(first)), null, 0);
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk());
        request(body(post("/presence"), secondUpdate), second).andExpect(status().isOk());
        realtime.afterConnectionClosed(connection, org.springframework.web.socket.CloseStatus.NORMAL);
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        foreground(second);
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/presence"), secondUpdate), second).andExpect(status().isOk());
        String key = "presence:" + second.deviceId();
        var state = json.readValue(redis.opsForValue().get(key), Presence.State.class);
        redis.opsForValue().set(key, json.writeValueAsString(new Presence.State(state.owner(), state.contacts(), state.connectionId(),
            state.sessionKey(), null, 0, System.currentTimeMillis() - 1)), Duration.ofSeconds(5));
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/presence"), secondUpdate), second).andExpect(status().isOk());
        Presence.Peer wrongIdentity = new Presence.Peer(second.userId(), second.deviceId(), "A".repeat(44));
        request(body(post("/presence"), new Presence.Update(List.of(wrongIdentity), null, 0)), first)
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        request(post("/auth/logout"), second).andExpect(status().isNoContent());
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        }

        @Test void presenceRejectsInvalidAudiencesTypingAndUnenrolledDevices() throws Exception {
        Device owner = device("presence_bounds"), peer = device("presence_contact");
        foreground(owner); foreground(peer);
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(owner)), null, 0)), owner).andExpect(status().isBadRequest());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer), presencePeer(peer)), null, 0)), owner).andExpect(status().isBadRequest());
        request(body(post("/presence"), new Presence.Update(Collections.nCopies(129, presencePeer(peer)), null, 0)), owner).andExpect(status().isBadRequest());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer)), peer.userId(), 5001)), owner).andExpect(status().isBadRequest());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer)), UUID.randomUUID(), 5000)), owner).andExpect(status().isBadRequest());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer)), null, 100)), owner).andExpect(status().isBadRequest());
        String registration = request(body(post("/auth/register"), Map.of("handle", "presence_enroll", "password", "test-only-password-12345")), null)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        AuthService.Token bootstrap = json.readValue(registration, AuthService.Token.class);
        Device unenrolled = new Device(bootstrap.userId(), null, bootstrap.accessToken(), null);
        request(body(post("/presence"), new Presence.Update(List.of(), null, 0)), unenrolled).andExpect(status().isForbidden());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("presence:" + owner.deviceId())));
        }

    @Test void lastSeenIsBoundedAndVisibleOnlyToMutualCapableContacts() throws Exception {
        Device first = device("seen_reader"), second = device("seen_writer"), stranger = device("seen_stranger");
        foreground(first); foreground(stranger);
        var connection = foreground(second);
        Presence.Update firstUpdate = new Presence.Update(List.of(presencePeer(second)), null, 0, true);
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(first)), first.userId(), 5000, true)), second)
                .andExpect(status().isOk());
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(jsonPath("$[0].lastSeenAgoMillis").doesNotExist());
        String key = "last-seen:" + second.deviceId();
        String stored = redis.opsForValue().get(key);
        long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= Presence.LAST_SEEN_LIFETIME);
        assertFalse(stored.contains("typing")); assertFalse(stored.contains(second.token()));
        realtime.afterConnectionClosed(connection, org.springframework.web.socket.CloseStatus.NORMAL);
        String response = request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Presence.Status[] statuses = json.readValue(response, Presence.Status[].class);
        assertEquals(1, statuses.length); assertEquals(presencePeer(second), statuses[0].peer());
        assertEquals(0, statuses[0].onlineForMillis()); assertEquals(0, statuses[0].typingForMillis());
        assertNotNull(statuses[0].lastSeenAgoMillis()); assertTrue(statuses[0].lastSeenAgoMillis() >= 0);
        assertEquals(stored, redis.opsForValue().get(key));
        assertTrue(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS) <= ttl);
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(second)), null, 0)), first)
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/presence"), firstUpdate), stranger).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/users/seen_writer"), stranger).andExpect(status().isOk()).andExpect(jsonPath("$.lastSeenAgoMillis").doesNotExist());
        connection = foreground(second);
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(stranger)), null, 0, true)), second).andExpect(status().isOk());
        realtime.afterConnectionClosed(connection, org.springframework.web.socket.CloseStatus.NORMAL);
        request(body(post("/presence"), firstUpdate), first).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void lastSeenClearsOnAudienceRemovalOptOutAndLogout() throws Exception {
        Device first = device("seen_owner"), second = device("seen_contact");
        foreground(first); foreground(second);
        Presence.Update share = new Presence.Update(List.of(presencePeer(second)), null, 0, true);
        String key = "last-seen:" + first.deviceId();
        request(body(post("/presence"), share), first).andExpect(status().isOk());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(post("/presence"), new Presence.Update(List.of(), null, 0, true)), first).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(post("/presence"), share), first).andExpect(status().isOk());
        request(body(post("/presence"), new Presence.Update(share.contacts(), null, 0)), first).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(post("/presence"), share), first).andExpect(status().isOk());
        request(post("/auth/logout"), first).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(first)), null, 0, true)), second)
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void lastSeenRejectsExpiredFutureAndChangedDeviceRecords() throws Exception {
        Device first = device("seen_check"), second = device("seen_stored");
        foreground(first);
        var connection = foreground(second);
        Presence.Update reader = new Presence.Update(List.of(presencePeer(second)), null, 0, true);
        request(body(post("/presence"), reader), first).andExpect(status().isOk());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(first)), null, 0, true)), second).andExpect(status().isOk());
        realtime.afterConnectionClosed(connection, org.springframework.web.socket.CloseStatus.NORMAL);
        String key = "last-seen:" + second.deviceId();
        String stored = redis.opsForValue().get(key);
        Presence.LastSeen original = json.readValue(stored, Presence.LastSeen.class);
        for (long observed : List.of(System.currentTimeMillis() - Presence.LAST_SEEN_LIFETIME - 1, System.currentTimeMillis() + 60_000)) {
            redis.opsForValue().set(key, json.writeValueAsString(new Presence.LastSeen(original.owner(), original.contacts(),
                    original.deviceVersion(), observed, observed + Presence.LAST_SEEN_LIFETIME)), Duration.ofSeconds(5));
            request(body(post("/presence"), reader), first).andExpect(status().isOk()).andExpect(content().json("[]"));
        }
        redis.opsForValue().set(key, stored, Duration.ofSeconds(30));
        Presence.Peer wrongIdentity = new Presence.Peer(second.userId(), second.deviceId(), "A".repeat(44));
        request(body(post("/presence"), new Presence.Update(List.of(wrongIdentity), null, 0, true)), first)
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        database.update("UPDATE devices SET auth_version=? WHERE id=?", UUID.randomUUID(), second.deviceId());
        request(body(post("/presence"), reader), first).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

        @Test void profilePacketsAreRecipientOnlyExpireAndNeverAppearInSearchOrChat() throws Exception {
        Device owner=device("photo_owner"); Device recipient=device("photo_recipient"); Device stranger=device("photo_stranger");
        verifyAndEstablish(owner,recipient);
        UUID id=UUID.randomUUID(); long deadline=System.currentTimeMillis()+60_000;
        ChatEnvelope context=new ChatEnvelope(1,id,owner.userId(),owner.deviceId(),recipient.userId(),recipient.deviceId(),
            System.currentTimeMillis(),deadline,ChatEnvelope.Expiry.HOURS_24,"profile-photo",null);
        byte[] photo="synthetic-private-photo".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext=json.writeValueAsBytes(new ProfileEnvelope(1,ProfileEnvelope.Action.UPDATE,UUID.randomUUID(),1,photo,context));
        SignalClient.Packet encrypted=owner.crypto().encrypt(recipient.userId(),plaintext,Instant.now());
        ProfileMessages.Send packet=new ProfileMessages.Send(id,recipient.userId(),recipient.deviceId(),deadline,encrypted.type(),encrypted.ciphertext());
        request(body(post("/profile/packets"),packet),null).andExpect(status().isUnauthorized());
        request(body(post("/profile/packets"),packet),owner).andExpect(status().isNoContent());
        String key="pc:"+recipient.deviceId()+":"+id;
        long ttl=redis.getExpire(key,java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl>0 && ttl<=60_000);
        assertFalse(redis.opsForValue().get(key).contains("synthetic-private-photo"));
        request(get("/profile/packets"),stranger).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/profile/packets"),owner).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/messages/pending"),recipient).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/users/photo_owner"),stranger).andExpect(status().isOk()).andExpect(jsonPath("$.photo").doesNotExist());
        request(get("/users/id/"+owner.userId()+"/profile"),stranger).andExpect(status().isOk())
            .andExpect(jsonPath("$.photo").doesNotExist()).andExpect(jsonPath("$.photoUrl").doesNotExist());
        String response=request(get("/profile/packets"),recipient).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ProfileMessages.Packet received=json.readValue(response,ProfileMessages.Packet[].class)[0];
        assertArrayEquals(plaintext,recipient.crypto().decrypt(owner.userId(),new SignalClient.Packet(received.type(),received.ciphertext())));
        request(delete("/profile/packets/"+id),stranger).andExpect(status().isNotFound());
        request(body(post("/profile/packets"),packet),owner).andExpect(status().isNoContent());
        assertTrue(redis.getExpire(key,java.util.concurrent.TimeUnit.MILLISECONDS)<=ttl);
        request(body(post("/profile/packets"),new ProfileMessages.Send(id,recipient.userId(),recipient.deviceId(),deadline+1,
            encrypted.type(),encrypted.ciphertext())),owner).andExpect(status().isConflict());
        request(delete("/profile/packets/"+id),recipient).andExpect(status().isNoContent());
        request(body(post("/profile/packets"),packet),owner).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        request(get("/profile/packets"),recipient).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/profile/packets"),new ProfileMessages.Send(UUID.randomUUID(),recipient.userId(),recipient.deviceId(),
            System.currentTimeMillis()+86_401_000,encrypted.type(),encrypted.ciphertext())),owner).andExpect(status().isBadRequest());
        request(body(post("/profile/packets"),new ProfileMessages.Send(UUID.randomUUID(),recipient.userId(),recipient.deviceId(),
            deadline,encrypted.type(),new byte[65_537])),owner).andExpect(status().isBadRequest());
        }

        @Test void groupMembershipIsInviteOnlyOwnerControlledAndRotatesOnJoinAndRemoval() throws Exception {
        Device owner = device("group_owner"); Device member = device("group_member"); Device stranger = device("group_stranger");
        Actor owning = new Actor(owner.userId(), owner.deviceId()); Actor invited = new Actor(member.userId(), member.deviceId());
        UUID id = UUID.randomUUID();
        GroupDirectory.Snapshot created = groups.create(owning, id);
        assertEquals(1, created.members().size());
        AccountDirectory.Contact contact = new AccountDirectory.Contact(member.userId(), member.deviceId(), Base64.getEncoder().encodeToString(member.crypto().publicIdentity()));
        GroupDirectory.Snapshot invitation = groups.invite(owning, id, created.revision(), List.of(contact));
        assertEquals("INVITED", invitation.member(member.userId()).state());
        assertTrue(invitation.member(member.userId()).invitedUntil() > System.currentTimeMillis());
        assertThrows(ApiException.class, () -> groups.withGroup(invited, id, created.revision(), false, group -> group));
        assertThrows(ApiException.class, () -> groups.accept(new Actor(stranger.userId(), stranger.deviceId()), id, created.revision()));
        GroupDirectory.Snapshot joined = groups.accept(invited, id, created.revision());
        assertEquals(2, joined.active().size()); assertNotEquals(created.epoch(), joined.epoch());
        assertThrows(ApiException.class, () -> groups.invite(invited, id, joined.revision(), List.of(contact)));
        assertThrows(ApiException.class, () -> groups.remove(invited, id, joined.revision(), owner.userId()));
        groups.remove(owning, id, joined.revision(), member.userId());
        GroupDirectory.Snapshot removed = groups.list(owning).get(0);
        assertNotEquals(joined.epoch(), removed.epoch());
        assertTrue(groups.list(invited).isEmpty());
        assertThrows(ApiException.class, () -> groups.withGroup(owning, id, joined.revision(), false, group -> group));
        groups.close(owning, id, removed.revision());
        assertTrue(groups.list(owning).get(0).closed());
    }

    @Test void groupCapacityCountsOwnerAndInvitationsAndCannotExceed200() throws Exception {
        Device owner = device("large_group_owner"); Actor actor = new Actor(owner.userId(), owner.deviceId());
        GroupDirectory.Snapshot group = groups.create(actor, UUID.randomUUID());
        List<AccountDirectory.Contact> invitees = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            UUID userId = UUID.randomUUID(); UUID deviceId = UUID.randomUUID();
            database.update("INSERT INTO accounts(id,handle,password_hash) VALUES (?,?,?)", userId, "group_member_" + index, "unused-test-hash");
            String identity = Base64.getEncoder().encodeToString(owner.crypto().publicIdentity());
            database.update("INSERT INTO devices(id,user_id,identity_key,auth_version) VALUES (?,?,?,?)", deviceId, userId, identity, UUID.randomUUID());
            invitees.add(new AccountDirectory.Contact(userId, deviceId, identity));
        }
        GroupDirectory.Snapshot full = groups.invite(actor, group.id(), group.revision(), invitees.subList(0, 199));
        assertEquals(200, full.members().size());
        assertThrows(ApiException.class, () -> groups.invite(actor, group.id(), group.revision(), List.of(invitees.get(199))));
        assertEquals(200, groups.list(actor).get(0).members().size());
        database.update("UPDATE group_members SET invited_until=1 WHERE group_id=? AND user_id=?", group.id(), invitees.get(0).userId());
        assertEquals(200, groups.invite(actor, group.id(), group.revision(), List.of(invitees.get(199))).members().size());
    }

        @Test void groupCiphertextAndPhotoAreSharedButAcknowledgementsAndAccessArePerMember() throws Exception {
        Device owner=device("shared_owner"); Device first=device("shared_first"); Device second=device("shared_second"); Device outsider=device("shared_outsider");
        Actor actor=new Actor(owner.userId(),owner.deviceId());
        GroupDirectory.Snapshot group=groups.create(actor,UUID.randomUUID());
        groups.invite(actor,group.id(),group.revision(),List.of(
            new AccountDirectory.Contact(first.userId(),first.deviceId(),Base64.getEncoder().encodeToString(first.crypto().publicIdentity())),
            new AccountDirectory.Contact(second.userId(),second.deviceId(),Base64.getEncoder().encodeToString(second.crypto().publicIdentity()))));
        group=groups.accept(new Actor(first.userId(),first.deviceId()),group.id(),group.revision());
        group=groups.accept(new Actor(second.userId(),second.deviceId()),group.id(),group.revision());
        String path="/groups/"+group.id();
        SignalGroup sender=new SignalGroup(new TestVault(),group.id(),group.epoch(),owner.userId());
        SignalGroup receiver=new SignalGroup(new TestVault(),group.id(),group.epoch(),first.userId());
        receiver.accept(owner.userId(),sender.distribution());
        byte[] plaintext="group-only content".getBytes(StandardCharsets.UTF_8);
        UUID mediaId=UUID.randomUUID(); ImageCipher.EncryptedImage image=ImageCipher.encrypt(mediaId,new byte[]{1,2,3});
        GroupMessages.Send message=new GroupMessages.Send(UUID.randomUUID(),group.epoch(),group.revision(),Expiry.VIEW_ONCE,
            System.currentTimeMillis()+60_000,sender.encrypt(plaintext),mediaId,image.ciphertext());
        request(body(post(path+"/messages"),message),owner).andExpect(status().isCreated()).andExpect(jsonPath("$.recipients").value(2));
        assertFalse(redis.opsForValue().get("gm:"+group.id()+":"+message.id()).contains("group-only content"));
        assertTrue(redis.getExpire("gm:"+group.id()+":"+message.id())>0);
        request(get(path+"/messages"),outsider).andExpect(status().isNotFound());
        String pending=request(get(path+"/messages"),first).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        GroupMessages.Message received=json.readValue(pending,GroupMessages.Message[].class)[0];
        assertArrayEquals(plaintext,receiver.decrypt(owner.userId(),received.ciphertext()));
        byte[] downloaded=request(get(path+"/messages/"+message.id()+"/media"),first).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[]{1,2,3},ImageCipher.decrypt(mediaId,downloaded,image.key(),image.nonce()));
        request(post(path+"/messages/"+message.id()+"/read"),first).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(1));
        request(get(path+"/messages/"+message.id()+"/media"),first).andExpect(status().isNotFound());
        request(get(path+"/messages/"+message.id()+"/media"),second).andExpect(status().isOk());
        request(post(path+"/messages/"+message.id()+"/read"),second).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READ"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("gm:"+group.id()+":"+message.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("gb:"+group.id()+":"+message.id())));
        long ttl=redis.getExpire("gr:"+group.id()+":"+message.id(),java.util.concurrent.TimeUnit.MILLISECONDS);
        request(body(post(path+"/messages"),message),owner).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("READ"));
        assertTrue(redis.getExpire("gr:"+group.id()+":"+message.id(),java.util.concurrent.TimeUnit.MILLISECONDS)<=ttl);
        assertFalse(Boolean.TRUE.equals(redis.hasKey("gm:"+group.id()+":"+message.id())));
        groups.remove(actor,group.id(),group.revision(),first.userId());
        request(body(post(path+"/messages"),message),owner).andExpect(status().isConflict());
        request(get(path+"/messages"),first).andExpect(status().isNotFound());
        }

        @Test void groupFanoutReaches199RecipientsWithOneCiphertextAndOnePhoto() throws Exception {
            UUID groupId=UUID.randomUUID(); UUID epoch=UUID.randomUUID(); UUID owner=UUID.randomUUID(); UUID ownerDevice=UUID.randomUUID();
            SignalGroup sender=new SignalGroup(new TestVault(),groupId,epoch,owner);
            byte[] distribution=sender.distribution(); byte[] plaintext="group fanout".getBytes(StandardCharsets.UTF_8);
            List<GroupDirectory.Member> members=new ArrayList<>();
            String identity=Base64.getEncoder().encodeToString(org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize());
            members.add(new GroupDirectory.Member(owner,ownerDevice,identity,"owner",null,"ACTIVE",0));
            for (int index=1;index<200;index++) members.add(new GroupDirectory.Member(UUID.randomUUID(),UUID.randomUUID(),identity,"member_"+index,null,"ACTIVE",0));
            GroupDirectory.Snapshot group=new GroupDirectory.Snapshot(groupId,owner,1,epoch,false,members);
            UUID mediaId=UUID.randomUUID(); ImageCipher.EncryptedImage image=ImageCipher.encrypt(mediaId,new byte[2*1024*1024]);
            GroupMessages.Send message=new GroupMessages.Send(UUID.randomUUID(),epoch,1,Expiry.VIEW_ONCE,System.currentTimeMillis()+60_000,sender.encrypt(plaintext),mediaId,image.ciphertext());
            assertEquals(199,groupMessages.send(new Actor(owner,ownerDevice),group,message).recipients());
            assertEquals(1,Objects.requireNonNull(redis.keys("gb:"+groupId+":*")).size());
            assertEquals(1,Objects.requireNonNull(redis.keys("gm:"+groupId+":*")).size());
            for (GroupDirectory.Member member : members.subList(1,200)) {
                Actor actor=new Actor(member.userId(),member.deviceId());
                SignalGroup recipient=new SignalGroup(new TestVault(),groupId,epoch,member.userId()); recipient.accept(owner,distribution);
                assertArrayEquals(plaintext,recipient.decrypt(owner,groupMessages.pending(actor,groupId).get(0).ciphertext()));
                groupMessages.acknowledge(actor,groupId,message.id(),"DELIVERED");
                assertTrue(groupMessages.pending(actor,groupId).isEmpty(),"Persisted view-once items must not starve later messages");
                groupMessages.acknowledge(actor,groupId,message.id(),"READ");
            }
            assertFalse(Boolean.TRUE.equals(redis.hasKey("gm:"+groupId+":"+message.id())));
            assertFalse(Boolean.TRUE.equals(redis.hasKey("gb:"+groupId+":"+message.id())));
            assertEquals(199,groupMessages.statuses(new Actor(owner,ownerDevice),groupId).get(0).read());
            assertTrue(redis.getExpire("gr:"+groupId+":"+message.id())>0);
        }

        @Test void groupControlsAreRecipientBoundBoundedAndCannotBeResurrected() throws Exception {
            Device owner=device("control_owner"); Device member=device("control_member"); Device outsider=device("control_outsider");
            Actor owning=new Actor(owner.userId(),owner.deviceId()); Actor receiving=new Actor(member.userId(),member.deviceId());
            GroupDirectory.Snapshot group=groups.create(owning,UUID.randomUUID());
            groups.invite(owning,group.id(),group.revision(),List.of(new AccountDirectory.Contact(member.userId(),member.deviceId(),Base64.getEncoder().encodeToString(member.crypto().publicIdentity()))));
            group=groups.list(owning).get(0);
            GroupMessages.ControlSend packet=new GroupMessages.ControlSend(UUID.randomUUID(),member.userId(),member.deviceId(),group.epoch(),group.revision(),System.currentTimeMillis()+60_000,3,new byte[64]);
            groupMessages.control(owning,group,packet);
            assertEquals(1,groupMessages.controls(receiving,group.id()).size());
            assertTrue(groupMessages.controls(new Actor(outsider.userId(),outsider.deviceId()),group.id()).isEmpty());
            UUID groupId=group.id();
            assertThrows(ApiException.class,() -> groupMessages.acknowledgeControl(new Actor(outsider.userId(),outsider.deviceId()),groupId,packet.id()));
            long ttl=redis.getExpire("gcr:"+groupId+":"+packet.id(),java.util.concurrent.TimeUnit.MILLISECONDS);
            groupMessages.acknowledgeControl(receiving,groupId,packet.id()); groupMessages.control(owning,group,packet);
            assertTrue(groupMessages.controls(receiving,groupId).isEmpty());
            assertTrue(redis.getExpire("gcr:"+groupId+":"+packet.id(),java.util.concurrent.TimeUnit.MILLISECONDS)<=ttl);
            request(get("/groups/"+groupId+"/messages"),member).andExpect(status().isNotFound());
            request(post("/groups/"+groupId+"/messages").contentType("application/json").content("{}"),owner).andExpect(status().isBadRequest());
            GroupDirectory.Snapshot joined=groups.accept(receiving,groupId,group.revision());
            database.update("UPDATE devices SET identity_key=? WHERE id=?",Base64.getEncoder().encodeToString(org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize()),member.deviceId());
            assertThrows(ApiException.class,() -> groups.requireCurrentDevices(joined));
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

        @Test void notificationReferencesAreRecipientBoundExpiringAndNeverPublicChatIdentifiers() throws Exception {
        Device sender = device("notification_sender");
        Device recipient = device("notification_recipient");
        UUID messageId = UUID.randomUUID();
        String reference = notifications.reference(new GenericNotifier.Destination(recipient.userId(), recipient.deviceId(), sender.userId(), messageId,
            System.currentTimeMillis() + 3_600_000));
        assertTrue(reference.matches("[A-Za-z0-9_-]{43}"));
        String key = "notification:" + recipient.deviceId();
        assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 300);
        String stored = redis.opsForValue().get(key);
        assertNotNull(stored); assertFalse(stored.contains(reference));
        var body = new RelayController.NotificationReference(reference);
        request(body(post("/notifications/resolve"), body), null).andExpect(status().isUnauthorized());
        request(body(post("/notifications/resolve"), body), sender).andExpect(status().isNotFound());
        request(body(post("/notifications/resolve"), body), recipient).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").value(recipient.userId().toString()))
            .andExpect(jsonPath("$.deviceId").value(recipient.deviceId().toString()))
            .andExpect(jsonPath("$.conversationId").value(sender.userId().toString()))
            .andExpect(jsonPath("$.messageId").value(messageId.toString()));
        assertThrows(ApiException.class, () -> notifications.resolve(new Actor(sender.userId(), recipient.deviceId()), reference));
        request(body(post("/notifications/resolve"), Map.of("reference", reference, "userId", recipient.userId())), recipient).andExpect(status().isBadRequest());
        request(body(post("/notifications/resolve"), Map.of("reference", sender.userId().toString())), recipient).andExpect(status().isBadRequest());
        String next = notifications.reference(new GenericNotifier.Destination(recipient.userId(), recipient.deviceId(), sender.userId(), UUID.randomUUID(),
            System.currentTimeMillis() + 3_600_000));
        assertNotEquals(reference, next);
        request(body(post("/notifications/resolve"), body), recipient).andExpect(status().isNotFound());
        request(body(post("/notifications/resolve"), Map.of("reference", next)), recipient).andExpect(status().isOk());
        redis.expire(key, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(post("/notifications/resolve"), Map.of("reference", next)), recipient).andExpect(status().isNotFound());
        assertNull(notifications.reference(new GenericNotifier.Destination(recipient.userId(), recipient.deviceId(), sender.userId(), messageId,
            System.currentTimeMillis() - 1)));
        String signedOut = notifications.reference(new GenericNotifier.Destination(recipient.userId(), recipient.deviceId(), sender.userId(), messageId,
            System.currentTimeMillis() + 60_000));
        assertNotNull(signedOut);
        request(post("/auth/logout"), recipient).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
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

        @Test void rememberedDeviceSessionRenewsWithoutCredentialsAndSignOutRevokesIt() throws Exception {
        Device original = device("remembered");
        String login = request(body(post("/auth/login"), Map.of("handle", "remembered", "password", "test-only-password-12345", "deviceId", original.deviceId())), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(login);
        assertTrue(session.hasNonNull("refreshToken"), "A remembered device needs a separate renewal credential");
        String refresh = session.path("refreshToken").asText();
        assertTrue(refresh.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(session.path("accessToken").asText(), refresh);
        long now = System.currentTimeMillis();
        assertTrue(session.path("expiresAt").asLong() <= now + Duration.ofHours(1).toMillis());
        assertTrue(session.path("refreshExpiresAt").asLong() > now + Duration.ofDays(29).toMillis());
        assertTrue(session.path("refreshExpiresAt").asLong() <= now + Duration.ofDays(30).toMillis());
        String storedRefresh = redis.opsForValue().get(AuthService.refreshKey(refresh));
        assertNotNull(storedRefresh);
        assertFalse(storedRefresh.contains(refresh));
        assertFalse(storedRefresh.contains(session.path("accessToken").asText()));
        assertTrue(redis.getExpire(AuthService.refreshKey(refresh)) <= Duration.ofDays(30).toSeconds());
        redis.delete(AuthService.tokenKey(session.path("accessToken").asText()));

        String response = request(body(post("/auth/refresh"), Map.of("refreshToken", refresh)), null)
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        JsonNode renewed = json.readTree(response);
        assertEquals(original.userId().toString(), renewed.path("userId").asText());
        assertEquals(original.deviceId().toString(), renewed.path("deviceId").asText());
        assertNotEquals(refresh, renewed.path("refreshToken").asText());
        Device returning = new Device(original.userId(), original.deviceId(), renewed.path("accessToken").asText(), original.crypto());
        request(get("/auth/me"), returning).andExpect(status().isOk());
        request(get("/auth/me"), new Device(original.userId(), original.deviceId(), refresh, original.crypto())).andExpect(status().isUnauthorized());
        request(body(post("/auth/refresh"), Map.of("refreshToken", refresh)), null).andExpect(status().isUnauthorized());
        request(post("/auth/logout"), returning).andExpect(status().isNoContent());
        request(get("/auth/me"), returning).andExpect(status().isUnauthorized());
        request(body(post("/auth/refresh"), Map.of("refreshToken", renewed.path("refreshToken").asText())), null)
            .andExpect(status().isUnauthorized());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM devices WHERE id = ?", Integer.class, original.deviceId()));
        }

        @Test void renewalNeverAcceptsEnrollmentExpiredCredentialsOrReplacedDevices() throws Exception {
        String registration = request(body(post("/auth/register"), Map.of("handle", "bootstrap", "password", "test-only-password-12345")), null)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode bootstrap = json.readTree(registration);
        assertFalse(bootstrap.hasNonNull("refreshToken"));
        request(body(post("/auth/refresh"), Map.of("refreshToken", bootstrap.path("accessToken").asText())), null).andExpect(status().isUnauthorized());

        Device original = device("renewal_owner");
        String login = request(body(post("/auth/login"), Map.of("handle", "renewal_owner", "password", "test-only-password-12345", "deviceId", original.deviceId())), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(login);
        String refresh = session.path("refreshToken").asText();
        redis.expire(AuthService.refreshKey(refresh), Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(AuthService.refreshKey(refresh))));
        request(body(post("/auth/refresh"), Map.of("refreshToken", refresh)), null).andExpect(status().isUnauthorized());

        String renewedLogin = request(body(post("/auth/login"), Map.of("handle", "renewal_owner", "password", "test-only-password-12345", "deviceId", original.deviceId())), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode remembered = json.readTree(renewedLogin);
        String enrollment = request(body(post("/auth/login"), Map.of("handle", "renewal_owner", "password", "test-only-password-12345")), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Device replacing = new Device(original.userId(), original.deviceId(), json.readTree(enrollment).path("accessToken").asText(), original.crypto());
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(original.deviceId(), org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize(), true)), replacing)
            .andExpect(status().isCreated());
        request(body(post("/auth/refresh"), Map.of("refreshToken", remembered.path("refreshToken").asText())), null).andExpect(status().isUnauthorized());
        request(get("/auth/me"), new Device(original.userId(), original.deviceId(), remembered.path("accessToken").asText(), original.crypto())).andExpect(status().isUnauthorized());
        }

            @Test void precommittedReplacementRecoversAfterLostRenewalResponseWithoutStoringRawTokens() throws Exception {
            Device original = device("lost_response");
            String login = request(body(post("/auth/login"), Map.of("handle", "lost_response", "password", "test-only-password-12345", "deviceId", original.deviceId())), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String previous = json.readTree(login).path("refreshToken").asText();
            String replacement = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.SecureRandom.getSeed(32));
            request(body(post("/auth/refresh"), Map.of("refreshToken", previous, "nextRefreshToken", previous)), null).andExpect(status().isBadRequest());
            request(body(post("/auth/refresh"), Map.of("refreshToken", previous, "nextRefreshToken", replacement)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value(replacement));
            request(body(post("/auth/refresh"), Map.of("refreshToken", previous, "nextRefreshToken", replacement)), null).andExpect(status().isUnauthorized());
            String stored = redis.opsForValue().get(AuthService.refreshKey(replacement));
            assertNotNull(stored); assertFalse(stored.contains(previous)); assertFalse(stored.contains(replacement));
            String recovered = request(body(post("/auth/refresh"), Map.of("refreshToken", replacement)), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode session = json.readTree(recovered);
            assertEquals(original.userId().toString(), session.path("userId").asText());
            Device returning = new Device(original.userId(), original.deviceId(), session.path("accessToken").asText(), original.crypto());
            request(get("/auth/me"), returning).andExpect(status().isOk());
            request(post("/auth/logout"), returning).andExpect(status().isNoContent());
            request(body(post("/auth/refresh"), Map.of("refreshToken", replacement)), null).andExpect(status().isUnauthorized());
            request(body(post("/auth/refresh"), Map.of("refreshToken", session.path("refreshToken").asText())), null).andExpect(status().isUnauthorized());
            }

            @Test void concurrentRenewalHasOnlyOneWinnerAndRetiresThePreviousAccessToken() throws Exception {
        Device original = device("concurrent_renewal");
        String login = request(body(post("/auth/login"), Map.of("handle", "concurrent_renewal", "password", "test-only-password-12345", "deviceId", original.deviceId())), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(login);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Integer>> attempts = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) attempts.add(executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return request(body(post("/auth/refresh"), Map.of("refreshToken", session.path("refreshToken").asText())), null)
                .andReturn().getResponse().getStatus();
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            for (var attempt : attempts) statuses.add(attempt.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        Collections.sort(statuses); assertEquals(List.of(200, 401), statuses);
        request(get("/auth/me"), new Device(original.userId(), original.deviceId(), session.path("accessToken").asText(), original.crypto())).andExpect(status().isUnauthorized());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(AuthService.refreshKey(session.path("refreshToken").asText()))));
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

    private RemotePhotos.Request photoRequest(UUID id, Device admin, Device owner) throws Exception {
        return new RemotePhotos.Request(id, presencePeer(owner), json.convertValue(admin.crypto().generatePreKey(Instant.now()), AccountDirectory.PreKey.class));
    }

                @Test void remotePhotosRequireAdminAndTheAddressedOwnersApproval() throws Exception {
                Device admin = device("photos_admin"), owner = device("photos_owner"), outsider = device("photos_outsider");
                foreground(admin); foreground(owner); foreground(outsider);
                foreground(admin, true); foreground(owner, true);
                UUID id = UUID.randomUUID();
                RemotePhotos.Request start = photoRequest(id, admin, owner);
                request(body(post("/remote-photos"), start), null).andExpect(status().isUnauthorized());
                request(body(post("/remote-photos"), start), admin).andExpect(status().isForbidden());
                assignAdmin(admin);
                String encoded = request(body(post("/remote-photos"), start), admin).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accepted").value(false)).andReturn().getResponse().getContentAsString();
                RemotePhotos.Session pending = json.readValue(encoded, RemotePhotos.Session.class);
                request(get("/remote-photos"), owner).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
                request(get("/remote-photos"), outsider).andExpect(status().isOk()).andExpect(content().json("[]"));
                request(get("/remote-photos/" + id), outsider).andExpect(status().isNotFound());
                request(body(post("/remote-photos/" + id + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), admin)
                    .andExpect(status().isForbidden());
                request(body(post("/remote-photos/" + id + "/accept"), new RemotePhotos.Approval(presencePeer(outsider))), owner)
                    .andExpect(status().isConflict());
                request(body(post("/remote-photos/" + id + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
                    .andExpect(jsonPath("$.expiresAt").value(pending.expiresAt()));
                long ttl = redis.getExpire("rps:" + id, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertTrue(ttl > 0 && ttl <= RemotePhotos.LIFETIME);
                request(body(post("/remote-photos"), start), admin).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.expiresAt").value(pending.expiresAt()));
                request(delete("/remote-photos/" + id), owner).andExpect(status().isNoContent());
                request(get("/remote-photos/" + id), admin).andExpect(status().isGone());
                }

                    @Test void remotePhotosExchangeNeedsConsentAndNeverResurrectsAcknowledgedOrRevokedPhotos() throws Exception {
                    Device admin = device("photos_transfer_admin"), owner = device("photos_transfer_owner"), outsider = device("photos_transfer_outside");
                    assignAdmin(admin);
                    foreground(admin); foreground(owner); foreground(outsider);
                    foreground(admin, true); foreground(owner, true);
                    UUID session = UUID.randomUUID();
                    RemotePhotos.Request start = photoRequest(session, admin, owner);
                    request(body(post("/remote-photos"), start), admin).andExpect(status().isCreated());
                    RemotePhotos.Send packet = new RemotePhotos.Send(UUID.randomUUID(), System.currentTimeMillis() + 50_000, 2, new byte[128]);
                    RemotePhotos.Exchange send = new RemotePhotos.Exchange(null, packet);
                    String route = "/remote-photos/" + session + "/exchange";
                    request(body(post(route), send), admin).andExpect(status().isForbidden());
                    request(body(post("/remote-photos/" + session + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner).andExpect(status().isOk());
                    request(body(post(route), send), outsider).andExpect(status().isNotFound());
                    request(body(post(route), send), admin).andExpect(status().isOk());
                    request(body(post(route), send), admin).andExpect(status().isOk());
                    RemotePhotos.Exchange poll = new RemotePhotos.Exchange(null, null);
                    request(body(post(route), poll), owner).andExpect(status().isOk())
                        .andExpect(jsonPath("$.packet.id").value(packet.id().toString()))
                        .andExpect(jsonPath("$.packet.senderId").value(admin.userId().toString()));
                    long ttl = redis.getExpire("rpq:" + session + ":" + owner.deviceId(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    assertTrue(ttl > 0 && ttl <= RemotePhotos.PACKET_LIFETIME);
                    request(body(post(route), new RemotePhotos.Exchange(packet.id(), null)), owner).andExpect(status().isOk())
                        .andExpect(jsonPath("$.packet").doesNotExist());
                    request(body(post(route), send), admin).andExpect(status().isOk());
                    request(body(post(route), poll), owner).andExpect(status().isOk()).andExpect(jsonPath("$.packet").doesNotExist());
                    RemotePhotos.Send next = new RemotePhotos.Send(UUID.randomUUID(), packet.expiresAt(), 2, new byte[128]);
                    request(body(post(route), new RemotePhotos.Exchange(null, next)), admin).andExpect(status().isOk());
                    request(body(post(route), new RemotePhotos.Exchange(next.id(), null)), owner).andExpect(status().isOk());
                    request(body(post(route), send), admin).andExpect(status().isOk());
                    request(body(post(route), poll), owner).andExpect(status().isOk()).andExpect(jsonPath("$.packet").doesNotExist());
                    request(body(post(route), new RemotePhotos.Exchange(null, new RemotePhotos.Send(packet.id(), packet.expiresAt(), 3, new byte[128]))), admin)
                        .andExpect(status().isConflict());
                    request(delete("/remote-photos/" + session), owner).andExpect(status().isNoContent());
                    request(body(post(route), send), admin).andExpect(status().isGone());
                    request(body(post("/remote-photos"), start), admin).andExpect(status().isGone());
                    assertFalse(Boolean.TRUE.equals(redis.hasKey("rpq:" + session + ":" + owner.deviceId())));
                    }

                    @Test void remotePhotosEndAfterDisconnectRoleRevocationAndIdentityReplacement() throws Exception {
                Device admin = device("photo_live_admin"), owner = device("photo_live_owner");
                assignAdmin(admin);
                foreground(admin);
                foreground(admin, true);
                request(body(post("/remote-photos"), photoRequest(UUID.randomUUID(), admin, owner)), admin)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("photo_peer_offline"));
                var ownerConnection = foreground(owner);
                UUID id = UUID.randomUUID();
                request(body(post("/remote-photos"), photoRequest(id, admin, owner)), admin).andExpect(status().isCreated());
                realtime.afterConnectionClosed(ownerConnection, org.springframework.web.socket.CloseStatus.NORMAL);
                request(get("/remote-photos/" + id), admin).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false));
                ownerConnection = foreground(owner);
                var photoConnection = foreground(owner, true);
                request(body(post("/remote-photos/" + id + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner).andExpect(status().isOk());
                realtime.afterConnectionClosed(ownerConnection, org.springframework.web.socket.CloseStatus.NORMAL);
                request(get("/remote-photos/" + id), admin).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
                assertNull(realtime.connection(new Actor(owner.userId(), owner.deviceId())));
                realtime.afterConnectionClosed(photoConnection, org.springframework.web.socket.CloseStatus.NORMAL);
                request(get("/remote-photos/" + id), admin).andExpect(status().isGone());
                assertFalse(Boolean.TRUE.equals(redis.hasKey("rps:" + id)));
                foreground(owner);
                UUID next = UUID.randomUUID();
                request(body(post("/remote-photos"), photoRequest(next, admin, owner)), admin).andExpect(status().isCreated());
                database.update("UPDATE accounts SET user_type='USER' WHERE id=?", admin.userId());
                request(get("/remote-photos/" + next), owner).andExpect(status().isGone());
                assertFalse(Boolean.TRUE.equals(redis.hasKey("rps:" + next)));
                }

    @Test void remotePhotosRecheckRoleAndDeviceAfterOwnerApproval() throws Exception {
        Device admin = device("photo_role_admin"), owner = device("photo_role_owner");
        assignAdmin(admin);
        foreground(admin, true); foreground(owner); foreground(owner, true);
        UUID first = UUID.randomUUID();
        request(body(post("/remote-photos"), photoRequest(first, admin, owner)), admin).andExpect(status().isCreated());
        request(body(post("/remote-photos/" + first + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner).andExpect(status().isOk());
        database.update("UPDATE accounts SET user_type='USER' WHERE id=?", admin.userId());
        request(body(post("/remote-photos/" + first + "/exchange"), new RemotePhotos.Exchange(null, null)), owner).andExpect(status().isGone());
        database.update("UPDATE accounts SET user_type='ADMIN' WHERE id=?", admin.userId());
        UUID second = UUID.randomUUID();
        request(body(post("/remote-photos"), photoRequest(second, admin, owner)), admin).andExpect(status().isCreated());
        request(body(post("/remote-photos/" + second + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner).andExpect(status().isOk());
        database.update("UPDATE devices SET auth_version=? WHERE id=?", UUID.randomUUID(), owner.deviceId());
        request(get("/remote-photos/" + second), admin).andExpect(status().isGone());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("rps:" + second)));
    }

    @Test void remotePhotosExpiryCannotBeExtendedOrPrekeysSubstituted() throws Exception {
        Device admin = device("photo_expiry_admin"), owner = device("photo_expiry_owner");
        assignAdmin(admin);
        foreground(admin, true); foreground(owner); foreground(owner, true);
        RemotePhotos.Request wrongKey = photoRequest(UUID.randomUUID(), owner, owner);
        request(body(post("/remote-photos"), wrongKey), admin).andExpect(status().isConflict());
        for (boolean accepted : List.of(false, true)) {
            UUID id = UUID.randomUUID();
            request(body(post("/remote-photos"), photoRequest(id, admin, owner)), admin).andExpect(status().isCreated());
            if (accepted) request(body(post("/remote-photos/" + id + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), owner).andExpect(status().isOk());
            RemotePhotos.Stored before = json.readValue(redis.opsForValue().get("rps:" + id), RemotePhotos.Stored.class);
            long deadline = accepted ? System.currentTimeMillis() - 1 : System.currentTimeMillis() + RemotePhotos.LIFETIME - RemotePhotos.REQUEST_LIFETIME - 1;
            RemotePhotos.Session expired = new RemotePhotos.Session(id, before.session().requester(), before.session().owner(), accepted, deadline, before.session().key());
            redis.opsForValue().set("rps:" + id, json.writeValueAsString(new RemotePhotos.Stored(expired, before.requesterVersion(), before.ownerVersion(),
                    before.requesterConnection(), before.ownerConnection())), Duration.ofSeconds(5));
            request(get("/remote-photos/" + id), admin).andExpect(status().isGone());
        }
    }

                @Test void accountTypesMigrationPreservesExistingAccounts() throws Exception {
            var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("account_type_upgrade_fixture").defaultSchema("account_type_upgrade_fixture")
                .locations("classpath:db/migration");
            try {
                configuration.target("4").load().migrate();
                UUID passwordAccount = UUID.randomUUID(), googleAccount = UUID.randomUUID();
                database.update("INSERT INTO account_type_upgrade_fixture.accounts(id, handle, password_hash, display_name) VALUES (?, ?, ?, ?)",
                    passwordAccount, "existing_password", "synthetic-password-verifier", "Existing Name");
                database.update("INSERT INTO account_type_upgrade_fixture.accounts(id, handle, google_subject) VALUES (?, ?, ?)",
                    googleAccount, "existing_google", "synthetic-google-subject");
                var before = database.queryForList("SELECT id, handle, password_hash, google_subject, display_name FROM account_type_upgrade_fixture.accounts ORDER BY handle");
                configuration.target("5").load().migrate();
                assertEquals(before, database.queryForList("SELECT id, handle, password_hash, google_subject, display_name FROM account_type_upgrade_fixture.accounts ORDER BY handle"));
                assertEquals(List.of("USER", "USER"), database.queryForList("SELECT user_type FROM account_type_upgrade_fixture.accounts ORDER BY handle", String.class));
                assertEquals(0, configuration.load().migrate().migrationsExecuted);
            } finally { database.execute("DROP SCHEMA IF EXISTS account_type_upgrade_fixture CASCADE"); }
            }

            @Test void accountTypesDefaultToUserAndRequireAnEnrolledOwner() throws Exception {
        Device regular = device("type_regular");
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        AccountDirectory.GoogleAccount google = directory.googleAccount("google-user-type-fixture");
        assertEquals(AccountDirectory.UserType.USER, directory.accountType(google.userId()).userType());
        request(get("/account/type"), null).andExpect(status().isUnauthorized());
        request(get("/account/type"), regular).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(regular.userId().toString()))
            .andExpect(jsonPath("$.userType").value("USER"))
            .andExpect(header().string("Cache-Control", "no-store"));
        String registration = request(body(post("/auth/register"), Map.of("handle", "type_enroll", "password", "test-only-password-12345")), null)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        AuthService.Token bootstrap = json.readValue(registration, AuthService.Token.class);
        request(get("/account/type"), new Device(bootstrap.userId(), null, bootstrap.accessToken(), null))
            .andExpect(status().isForbidden());
        assertEquals(AccountDirectory.UserType.USER, directory.accountType(bootstrap.userId()).userType());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = 'UNKNOWN' WHERE id = ?", regular.userId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = NULL WHERE id = ?", regular.userId()));
        }

        @Test void accountTypesStayWithUuidAcrossRenameWithoutGrantingOtherAccountAccess() throws Exception {
        Device owner = device("type_owner"), other = device("type_other");
        UUID originalVersion = database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, owner.deviceId());
        assignAdmin(owner);
        request(get("/account/type"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("ADMIN"));
        request(body(patch("/account/username"), Map.of("handle", "type_renamed")), owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(owner.userId().toString()));
        request(get("/account/type"), owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(owner.userId().toString())).andExpect(jsonPath("$.userType").value("ADMIN"));
        request(get("/account/type").param("userId", owner.userId().toString()), other).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(other.userId().toString())).andExpect(jsonPath("$.userType").value("USER"));
        request(get("/users/type_renamed"), other).andExpect(status().isOk()).andExpect(jsonPath("$.userType").doesNotExist());
        request(get("/users/id/" + owner.userId() + "/profile"), other).andExpect(status().isOk()).andExpect(jsonPath("$.userType").doesNotExist());
        request(body(patch("/account/username"), Map.of("handle", "type_owner")), other).andExpect(status().isOk());
        request(get("/account/type"), other).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
        verifyAndEstablish(owner, other);
        request(body(post("/messages"), encrypted(owner, other, "role-boundary-fixture".getBytes(StandardCharsets.UTF_8),
            Expiry.HOUR_1, System.currentTimeMillis() + 120_000, null)), owner).andExpect(status().isCreated());
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", other.userId()));
        request(get("/messages/pending"), owner).andExpect(status().isOk()).andExpect(content().json("[]"));
        assertEquals(originalVersion, database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, owner.deviceId()));
        assertEquals(1, database.update("UPDATE accounts SET user_type = 'USER' WHERE id = ?", owner.userId()));
        request(get("/account/type"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
        }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void singleAdminMigrationPreservesIdentityOrRejectsAmbiguousAdmins(int adminCount) {
        var configuration = org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("single_admin_upgrade_fixture").defaultSchema("single_admin_upgrade_fixture")
            .locations("classpath:db/migration");
        try {
            configuration.target("5").load().migrate();
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            database.update("""
                INSERT INTO single_admin_upgrade_fixture.accounts(id, handle, password_hash, display_name, user_type)
                VALUES (?, 'existing_admin', 'synthetic-password-verifier', 'Existing Name', ?)
                """, first, adminCount > 0 ? "ADMIN" : "USER");
            database.update("""
                INSERT INTO single_admin_upgrade_fixture.accounts(id, handle, google_subject, user_type)
                VALUES (?, 'existing_google', 'synthetic-google-subject', ?)
                """, second, adminCount > 1 ? "ADMIN" : "USER");
            database.update("""
                INSERT INTO single_admin_upgrade_fixture.devices(id, user_id, identity_key, auth_version)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), first, Base64.getEncoder().encodeToString(new byte[33]), UUID.randomUUID());
            var accountsBefore = database.queryForList("SELECT * FROM single_admin_upgrade_fixture.accounts ORDER BY id");
            var devicesBefore = database.queryForList("SELECT * FROM single_admin_upgrade_fixture.devices ORDER BY id");
            if (adminCount > 1) {
                assertThrows(org.flywaydb.core.api.FlywayException.class, () -> configuration.target("6").load().migrate());
                assertTrue(Boolean.TRUE.equals(database.queryForObject(
                    "SELECT to_regclass('single_admin_upgrade_fixture.admin_identity') IS NULL", Boolean.class)));
            } else {
                assertEquals(1, configuration.target("6").load().migrate().migrationsExecuted);
                assertEquals(adminCount, database.queryForObject("SELECT COUNT(*) FROM single_admin_upgrade_fixture.admin_identity", Integer.class));
                if (adminCount == 1) {
                    assertEquals(first, database.queryForObject("SELECT user_id FROM single_admin_upgrade_fixture.admin_identity", UUID.class));
                    assertEquals(1, database.update("UPDATE single_admin_upgrade_fixture.accounts SET user_type = 'ADMIN' WHERE id = ?", first));
                }
                assertThrows(DataIntegrityViolationException.class,
                    () -> database.update("UPDATE single_admin_upgrade_fixture.accounts SET user_type = 'ADMIN' WHERE id = ?", second));
                assertEquals(0, configuration.load().migrate().migrationsExecuted);
            }
            assertEquals(accountsBefore, database.queryForList("SELECT * FROM single_admin_upgrade_fixture.accounts ORDER BY id"));
            assertEquals(devicesBefore, database.queryForList("SELECT * FROM single_admin_upgrade_fixture.devices ORDER BY id"));
        } finally { database.execute("DROP SCHEMA IF EXISTS single_admin_upgrade_fixture CASCADE"); }
    }

    @Test void adminIntroductionsAreAutomaticOnlyForNewAccountsAndVisibleOnlyToTheirParticipants() throws Exception {
        Device admin = device("intro_admin"), existing = device("intro_existing");
        assignAdmin(admin);
        Device fresh = device("intro_fresh"), other = device("intro_other");
        request(get("/account/admin-contacts"), null).andExpect(status().isUnauthorized());
        request(get("/account/admin-contacts"), existing).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts").isEmpty()).andExpect(jsonPath("$.admin").doesNotExist());
        request(get("/account/admin-contacts").param("userId", admin.userId().toString()), fresh).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").value(fresh.userId().toString()))
            .andExpect(jsonPath("$.contacts.length()").value(1))
            .andExpect(jsonPath("$.contacts[0].userId").value(admin.userId().toString()))
            .andExpect(jsonPath("$.admin.deviceId").value(admin.deviceId().toString()));
        request(get("/account/admin-contacts/" + other.userId()), fresh).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts").isEmpty());
        request(get("/account/admin-contacts/" + fresh.userId()), existing).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts").isEmpty());
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.contacts.length()").value(2));
        Device newest = device("intro_newest");
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].userId").value(newest.userId().toString()));
        request(body(patch("/account/username"), Map.of("handle", "intro_renamed")), admin).andExpect(status().isOk());
        request(get("/account/admin-contacts"), fresh).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].userId").value(admin.userId().toString()))
            .andExpect(jsonPath("$.contacts[0].handle").value("intro_renamed"));
        request(body(post("/account/admin-contacts"), Map.of("adminId", other.userId())), fresh).andExpect(status().isMethodNotAllowed());
        request(body(post("/auth/register"), Map.of("handle", "intro_injected", "password", "test-only-password-12345", "adminId", other.userId())), null)
            .andExpect(status().isBadRequest());
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
            "INSERT INTO admin_introductions(user_id, admin_id) VALUES (?, ?)", existing.userId(), other.userId()));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_type = 'ADMIN'", Integer.class));
        assertEquals("USER", database.queryForObject("SELECT user_type FROM accounts WHERE id = ?", String.class, fresh.userId()));
        assertEquals(3, database.queryForObject("SELECT COUNT(*) FROM admin_introductions", Integer.class));
    }

    @Test void adminIntroductionsWaitForEnrollmentIncludeGoogleOnceAndPauseOnRoleRevocation() throws Exception {
        Device admin = device("intro_google_admin");
        assignAdmin(admin);
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        AccountDirectory.GoogleAccount google = directory.googleAccount("google-introduction-fixture");
        assertEquals(google, directory.googleAccount("google-introduction-fixture"));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM admin_introductions", Integer.class));
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.contacts").isEmpty());
        AuthService.Token enrollment = authentication.issue(new Actor(google.userId(), null));
        Device enrolling = new Device(google.userId(), UUID.randomUUID(), enrollment.accessToken(), new SignalClient(google.userId(), new TestVault()));
        request(get("/account/admin-contacts"), enrolling).andExpect(status().isForbidden());
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(enrolling.deviceId(), enrolling.crypto().publicIdentity(), false)), enrolling)
            .andExpect(status().isCreated());
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].userId").value(google.userId().toString()));
        database.update("UPDATE accounts SET user_type = 'USER' WHERE id = ?", admin.userId());
        Device deferred = device("intro_deferred");
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.contacts").isEmpty());
        request(get("/account/admin-contacts"), deferred).andExpect(status().isOk()).andExpect(jsonPath("$.contacts").isEmpty());
        database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", admin.userId());
        request(get("/account/admin-contacts"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.contacts.length()").value(2));
        request(get("/account/admin-contacts"), deferred).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].userId").value(admin.userId().toString()));
    }

    @Test void adminIntroductionsArePaginatedWithoutOmittingOrDuplicatingAccounts() throws Exception {
        Device admin = device("intro_paging_admin");
        assignAdmin(admin);
        Set<UUID> expected = new HashSet<>();
        for (int index = 0; index < 65; index++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            database.update("INSERT INTO accounts(id, handle, password_hash) VALUES (?, ?, 'synthetic')", id, "intro_page_" + index);
            database.update("INSERT INTO devices(id, user_id, identity_key, auth_version) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), id, Base64.getEncoder().encodeToString(new byte[33]), UUID.randomUUID());
        }
        String firstJson = request(get("/account/admin-contacts"), admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AccountDirectory.Introductions first = json.readValue(firstJson, AccountDirectory.Introductions.class);
        assertEquals(64, first.contacts().size()); assertNotNull(first.nextAfter());
        assertEquals(first.contacts().getLast().userId(), first.nextAfter());
        String lastJson = request(get("/account/admin-contacts").param("after", first.nextAfter().toString()), admin)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AccountDirectory.Introductions last = json.readValue(lastJson, AccountDirectory.Introductions.class);
        assertEquals(1, last.contacts().size()); assertNull(last.nextAfter());
        Set<UUID> actual = first.contacts().stream().map(AccountDirectory.Introduction::userId).collect(Collectors.toSet());
        assertTrue(actual.add(last.contacts().getFirst().userId()));
        assertEquals(expected, actual);
        request(get("/account/admin-contacts").param("after", "invalid"), admin).andExpect(status().isBadRequest());
        request(get("/account/admin-contacts/" + last.contacts().getFirst().userId()), admin).andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts.length()").value(1));
    }

    @Test void adminIntroductionsMigrationDoesNotBackfillOrChangeExistingAccounts() {
        var configuration = org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("admin_introduction_upgrade_fixture").defaultSchema("admin_introduction_upgrade_fixture")
            .locations("classpath:db/migration");
        try {
            configuration.target("5").load().migrate();
            UUID admin = UUID.randomUUID(), existing = UUID.randomUUID(), fresh = UUID.randomUUID();
            database.update("INSERT INTO admin_introduction_upgrade_fixture.accounts(id, handle, password_hash, user_type) VALUES (?, 'prior_admin', 'synthetic', 'ADMIN')", admin);
            database.update("INSERT INTO admin_introduction_upgrade_fixture.accounts(id, handle, password_hash) VALUES (?, 'prior_user', 'synthetic')", existing);
            configuration.target("6").load().migrate();
            var before = database.queryForList("SELECT * FROM admin_introduction_upgrade_fixture.accounts ORDER BY id");
            assertEquals(1, configuration.target("7").load().migrate().migrationsExecuted);
            assertEquals(before, database.queryForList("SELECT * FROM admin_introduction_upgrade_fixture.accounts ORDER BY id"));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM admin_introduction_upgrade_fixture.admin_introductions", Integer.class));
            database.update("INSERT INTO admin_introduction_upgrade_fixture.accounts(id, handle, password_hash) VALUES (?, 'new_user', 'synthetic')", fresh);
            assertEquals(admin, database.queryForObject("SELECT admin_id FROM admin_introduction_upgrade_fixture.admin_introductions WHERE user_id = ?", UUID.class, fresh));
            assertEquals(0, configuration.load().migrate().migrationsExecuted);
        } finally { database.execute("DROP SCHEMA IF EXISTS admin_introduction_upgrade_fixture CASCADE"); }
    }

    @Test void singleAdminPinCannotBeReplacedEvenAfterRoleRevocation() throws Exception {
        Device admin = device("permanent_admin"), other = device("permanent_other");
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", other.userId()));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("INSERT INTO accounts(id, handle, password_hash, user_type) VALUES (?, 'inserted_admin', 'synthetic', 'ADMIN')", UUID.randomUUID()));
        assignAdmin(admin);
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", other.userId()));
        assertEquals(1, database.update("UPDATE accounts SET user_type = 'USER' WHERE id = ?", admin.userId()));
        request(get("/account/type"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", other.userId()));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("INSERT INTO admin_identity(user_id) VALUES (?)", other.userId()));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("INSERT INTO admin_identity(singleton, user_id) VALUES (FALSE, ?)", other.userId()));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE admin_identity SET user_id = ?", other.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update("DELETE FROM admin_identity"));
        var restrictedTruncate = assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
            () -> database.execute("TRUNCATE TABLE admin_identity"));
        assertEquals("0A000", restrictedTruncate.getSQLException().getSQLState());
        assertThrows(DataIntegrityViolationException.class, () -> database.execute("TRUNCATE TABLE admin_identity CASCADE"));
        assertThrows(DataIntegrityViolationException.class, () -> database.execute("TRUNCATE TABLE accounts CASCADE"));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("DELETE FROM accounts WHERE id = ?", admin.userId()));
        assertThrows(DataIntegrityViolationException.class,
            () -> database.update("UPDATE accounts SET id = ? WHERE id = ?", UUID.randomUUID(), admin.userId()));
        assertEquals(admin.userId(), database.queryForObject("SELECT user_id FROM admin_identity", UUID.class));
        assertEquals(1, database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", admin.userId()));
        request(get("/account/type"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("ADMIN"));
        request(get("/account/type"), other).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_type = 'ADMIN'", Integer.class));
    }

    @Test void singleAdminConcurrentInitializationHasOnlyOneWinner() throws Exception {
        List<Device> candidates = List.of(device("admin_candidate_one"), device("admin_candidate_two"));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
            for (Device candidate : candidates) attempts.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Concurrent admin fixture did not start");
                try { assignAdmin(candidate); return true; }
                catch (DataIntegrityViolationException rejected) { return false; }
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int succeeded = 0;
            for (var attempt : attempts) if (attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded);
        }
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM admin_identity", Integer.class));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_type = 'ADMIN'", Integer.class));
        UUID pinned = database.queryForObject("SELECT user_id FROM admin_identity", UUID.class);
        for (Device candidate : candidates) request(get("/account/type"), candidate).andExpect(status().isOk())
            .andExpect(jsonPath("$.userType").value(candidate.userId().equals(pinned) ? "ADMIN" : "USER"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleAdminAuthorizationRejectsUnpinnedRoleEvenIfDatabaseGuardWasBypassed(boolean pinExists) throws Exception {
        Device owner = device("pinned_owner"), impostor = device("unpinned_impostor");
        if (pinExists) database.update("INSERT INTO admin_identity(user_id) VALUES (?)", owner.userId());
        database.execute("ALTER TABLE accounts DISABLE TRIGGER accounts_admin_identity");
        try { database.update("UPDATE accounts SET user_type = 'ADMIN' WHERE id = ?", impostor.userId()); }
        finally { database.execute("ALTER TABLE accounts ENABLE TRIGGER accounts_admin_identity"); }
        request(get("/account/type"), impostor).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("admin_identity_unavailable"));
        UUID session = UUID.randomUUID();
        request(body(post("/remote-photos"), photoRequest(session, impostor, owner)), impostor)
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("admin_identity_unavailable"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("rps:" + session)));
        request(get("/account/type"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
    }

        @Test void accountTypesCannotBeAssignedThroughClientRequests() throws Exception {
        Device owner = device("type_unchanged");
        request(body(post("/auth/register"), Map.of("handle", "type_injected", "password", "test-only-password-12345", "userType", "ADMIN")), null)
            .andExpect(status().isBadRequest());
        request(body(patch("/account/profile"), Map.of("displayName", "Example", "userType", "ADMIN")), owner)
            .andExpect(status().isBadRequest());
        request(body(patch("/account/username"), Map.of("handle", "type_changed", "userType", "ADMIN")), owner)
            .andExpect(status().isBadRequest());
        request(body(post("/auth/login"), Map.of("handle", "type_unchanged", "password", "test-only-password-12345", "userType", "ADMIN")), null)
            .andExpect(status().isBadRequest());
        request(body(post("/devices"), Map.of("deviceId", owner.deviceId(), "identityKey", owner.crypto().publicIdentity(), "userType", "ADMIN")), owner)
            .andExpect(status().isForbidden());
        String signIn = request(body(post("/auth/login"), new AuthService.Login("type_unchanged", "test-only-password-12345", null)), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AuthService.Token enrollment = json.readValue(signIn, AuthService.Token.class);
        Device enrolling = new Device(enrollment.userId(), null, enrollment.accessToken(), null);
        request(body(post("/devices"), Map.of("deviceId", owner.deviceId(), "identityKey", owner.crypto().publicIdentity(), "userType", "ADMIN")), enrolling)
            .andExpect(status().isBadRequest());
        request(body(patch("/account/type"), Map.of("userType", "ADMIN")), owner).andExpect(status().isMethodNotAllowed());
        request(body(post("/account/type"), Map.of("userType", "ADMIN")), owner).andExpect(status().isMethodNotAllowed());
        request(body(put("/account/type"), Map.of("userType", "ADMIN")), owner).andExpect(status().isMethodNotAllowed());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM admin_identity", Integer.class));
        request(get("/account/type"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
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

    @Test void googleUsernamesRetryCollisionsWithoutTakingAnExistingAccountOrItsRole() throws Exception {
        Device admin = device("quiet-otter-4827");
        assignAdmin(admin);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC(),
                () -> attempts.getAndIncrement() == 0 ? "quiet-otter-4827" : "calm-panda-1934");
        AccountDirectory.GoogleAccount created = new TransactionTemplate(transactions).execute(
                transaction -> directory.googleAccount("readable-collision-fixture"));
        assertNotNull(created);
        assertEquals("calm-panda-1934", created.handle());
        assertEquals(2, attempts.get());
        assertNotEquals(admin.userId(), created.userId());
        assertEquals("ADMIN", database.queryForObject("SELECT user_type FROM accounts WHERE id = ?", String.class, admin.userId()));
        assertEquals("USER", database.queryForObject("SELECT user_type FROM accounts WHERE id = ?", String.class, created.userId()));
        assertEquals(admin.userId(), database.queryForObject("SELECT admin_id FROM admin_introductions WHERE user_id = ?", UUID.class, created.userId()));
        assertNull(database.queryForObject("SELECT google_subject FROM accounts WHERE id = ?", String.class, admin.userId()));
        assertNull(database.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, created.userId()));
        assertEquals(created, directory.googleAccount("readable-collision-fixture"));
        assertEquals(2, attempts.get(), "Returning users must not generate another username");
    }

    @Test void googleUsernamesConcurrentSignupsForOneSubjectCreateOnlyOneAccount() throws Exception {
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC(), () -> "gentle-fox-2048");
        var ready = new java.util.concurrent.CountDownLatch(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        Set<AccountDirectory.GoogleAccount> results = new HashSet<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            List<java.util.concurrent.Future<AccountDirectory.GoogleAccount>> calls = new ArrayList<>();
            for (int index = 0; index < 6; index++) calls.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return new TransactionTemplate(transactions).execute(transaction -> directory.googleAccount("same-readable-subject"));
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var call : calls) results.add(call.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, results.size());
        assertEquals("gentle-fox-2048", results.iterator().next().handle());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
    }

    @Test void googleUsernamesConcurrentCollisionsAcrossSubjectsCreateDistinctAccounts() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        Set<UUID> users = new HashSet<>();
        Set<String> handles = new HashSet<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            List<java.util.concurrent.Future<AccountDirectory.GoogleAccount>> calls = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                int number = index;
                calls.add(executor.submit(() -> {
                    var attempt = new java.util.concurrent.atomic.AtomicInteger();
                    AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC(),
                            () -> attempt.getAndIncrement() == 0 ? "quiet-otter-4827" : "bright-panda-" + (1000 + number));
                    ready.countDown();
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return new TransactionTemplate(transactions).execute(
                            transaction -> directory.googleAccount("different-readable-subject-" + number));
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var call : calls) {
                AccountDirectory.GoogleAccount result = call.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(users.add(result.userId()));
                assertTrue(handles.add(result.handle()));
            }
        }
        assertEquals(6, users.size());
        assertTrue(handles.contains("quiet-otter-4827"));
        assertEquals(6, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_type = 'USER'", Integer.class));
    }

    @Test void googleUsernamesExhaustionIsBoundedAndCannotReturnAnotherPersonsAccount() {
        database.update("INSERT INTO accounts(id,handle,password_hash) VALUES (?,'quiet-otter-4827','synthetic')", UUID.randomUUID());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC(),
                () -> { attempts.incrementAndGet(); return "quiet-otter-4827"; });
        ApiException failure = assertThrows(ApiException.class, () -> new TransactionTemplate(transactions).execute(
                transaction -> directory.googleAccount("exhausted-readable-fixture")));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, failure.status);
        assertEquals("google_sign_in_unavailable", failure.getMessage());
        assertEquals(16, attempts.get());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE google_subject IS NOT NULL", Integer.class));
    }

    @Test void googleUsernamesPreserveLegacyNamesAndStillAllowUserChosenRenames() {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO accounts(id,handle,google_subject) VALUES (?,'g_0123456789abcdef0123456789ab','legacy-readable-fixture')", id);
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC(),
                () -> { throw new AssertionError("An existing account must not allocate a username"); });
        AccountDirectory.GoogleAccount original = directory.googleAccount("legacy-readable-fixture");
        assertEquals(id, original.userId());
        assertEquals("g_0123456789abcdef0123456789ab", original.handle());
        directory.rename(id, new AccountDirectory.UsernameChange("my-chosen-name"));
        assertEquals(new AccountDirectory.GoogleAccount(id, "my-chosen-name"), directory.googleAccount("legacy-readable-fixture"));
        assertEquals("my-chosen-name", directory.existingGoogleAccount("legacy-readable-fixture", id).handle());
        assertThrows(ApiException.class, () -> directory.existingGoogleAccount("legacy-readable-fixture", UUID.randomUUID()));
        assertThrows(ApiException.class, () -> directory.googleAccount("invalid subject"));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
    }

    @Test void hyphenatedUsernamesWorkForRegistrationLoginLookupAndRename() throws Exception {
        Device owner = device("quiet-otter-4827");
        Device reader = device("reader_old_name");
        request(get("/users/quiet-otter-4827"), reader).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.userId().toString()));
        request(body(post("/auth/login"), new AuthService.Login("quiet-otter-4827", "test-only-password-12345", owner.deviceId())), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(owner.userId().toString()));
        request(body(patch("/account/username"), Map.of("handle", "gentle-panda-2098")), owner).andExpect(status().isOk());
        request(get("/users/gentle-panda-2098"), reader).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(owner.deviceId().toString()));
        request(get("/users/id/" + owner.userId() + "/profile"), reader).andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("gentle-panda-2098"));
        request(body(patch("/account/username"), Map.of("handle", "gentle-panda-2098")), reader).andExpect(status().isConflict());
        request(body(patch("/account/username"), Map.of("handle", "invalid.name")), reader).andExpect(status().isBadRequest());
        request(body(patch("/account/username"), Map.of("handle", "invalid name")), reader).andExpect(status().isBadRequest());
        request(get("/account/type"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("USER"));
        assertEquals("reader_old_name", database.queryForObject("SELECT handle FROM accounts WHERE id = ?", String.class, reader.userId()));
    }

    @Test void hyphenatedUsernameMigrationPreservesAccountsDevicesAndAdminPin() {
        var configuration = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("username_upgrade_fixture").defaultSchema("username_upgrade_fixture").locations("classpath:db/migration");
        try {
            configuration.target("10").load().migrate();
            UUID admin = UUID.randomUUID(), user = UUID.randomUUID(), device = UUID.randomUUID();
            database.update("INSERT INTO username_upgrade_fixture.accounts(id,handle,password_hash) VALUES (?,'legacy_admin','synthetic')", admin);
            database.update("INSERT INTO username_upgrade_fixture.admin_identity(user_id) VALUES (?)", admin);
            database.update("UPDATE username_upgrade_fixture.accounts SET user_type='ADMIN' WHERE id=?", admin);
            database.update("INSERT INTO username_upgrade_fixture.accounts(id,handle,google_subject) VALUES (?,'g_legacy_username','legacy-google-subject')", user);
            database.update("INSERT INTO username_upgrade_fixture.devices(id,user_id,identity_key,auth_version) VALUES (?,?,?,?)",
                    device, user, Base64.getEncoder().encodeToString(new byte[33]), UUID.randomUUID());
            var accounts = database.queryForList("SELECT * FROM username_upgrade_fixture.accounts ORDER BY id");
            var devices = database.queryForList("SELECT * FROM username_upgrade_fixture.devices");
            var links = database.queryForList("SELECT * FROM username_upgrade_fixture.admin_introductions");
            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "UPDATE username_upgrade_fixture.accounts SET handle='quiet-otter-4827' WHERE id=?", user));
            assertEquals(1, configuration.target("11").load().migrate().migrationsExecuted);
            assertEquals(accounts, database.queryForList("SELECT * FROM username_upgrade_fixture.accounts ORDER BY id"));
            assertEquals(devices, database.queryForList("SELECT * FROM username_upgrade_fixture.devices"));
            assertEquals(links, database.queryForList("SELECT * FROM username_upgrade_fixture.admin_introductions"));
            assertEquals(admin, database.queryForObject("SELECT user_id FROM username_upgrade_fixture.admin_identity", UUID.class));
            assertEquals(1, database.update("UPDATE username_upgrade_fixture.accounts SET handle='quiet-otter-4827' WHERE id=?", user));
            for (String invalid : List.of("ab", "x".repeat(33), "quiet otter", "quiet.otter", "Quiet-otter", "quiet/otter"))
                assertThrows(DataIntegrityViolationException.class, () -> database.update(
                        "UPDATE username_upgrade_fixture.accounts SET handle=? WHERE id=?", invalid, user));
            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "UPDATE username_upgrade_fixture.accounts SET handle='legacy_admin' WHERE id=?", user));
            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "UPDATE username_upgrade_fixture.accounts SET user_type='ADMIN' WHERE id=?", user));
            assertEquals(0, configuration.load().migrate().migrationsExecuted);
        } finally { database.execute("DROP SCHEMA IF EXISTS username_upgrade_fixture CASCADE"); }
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
            long limit = key.startsWith("refresh:") ? Duration.ofDays(30).toSeconds() : 86400;
            assertTrue(ttl >= 0 && ttl <= limit, "Ephemeral key must have bounded expiry");
        }
    }

    @Test void productionRelayClassesHaveNoClientCryptoAndSchemaHasNoPlaintextContentTables() throws Exception {
        try (var paths = Files.walk(Path.of("target/classes/app/vanishr/relay"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".class")).toList()) {
                String bytecode = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("org/signal/"), path.toString());
                assertFalse(bytecode.contains("app/vanishr/crypto/"), path.toString());
            }
        }
        List<String> tables = database.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name", String.class);
        assertEquals(List.of("account_backups", "account_blocks", "account_deletion_receipts", "accounts", "admin_identity", "admin_introductions", "devices", "fallback_prekeys", "flyway_schema_history", "group_members", "prekeys", "private_groups"), tables);
        // The only stored user blob is one client-encrypted backup per account with no plaintext metadata column.
        assertEquals(List.of("user_id", "ciphertext", "updated_at", "expires_at"), database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='account_backups' ORDER BY ordinal_position", String.class));
        assertEquals(List.of("singleton", "user_id"), database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_identity' ORDER BY ordinal_position", String.class));
        assertEquals(List.of("user_id", "admin_id", "introduced_at"), database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_introductions' ORDER BY ordinal_position", String.class));
        assertEquals(List.of("id","owner_id","revision","epoch","closed_at"),database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='private_groups' ORDER BY ordinal_position",String.class));
        assertEquals(List.of("group_id","user_id","device_id","identity_key","state","invited_until"),database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='group_members' ORDER BY ordinal_position",String.class));
    }

    @Test void googleIdentityMappingIsStableSeparateAndDoesNotImportGoogleProfileData() throws Exception {
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        AccountDirectory.GoogleAccount first = directory.googleAccount("google-subject-fixture");
        assertTrue(first.handle().matches("[a-z]+-[a-z]+-[1-9][0-9]{3}"));
        AccountDirectory.GoogleAccount returning = directory.googleAccount("google-subject-fixture");
        assertEquals(first, returning);
        assertNotEquals(first.userId(), directory.googleAccount("other-google-subject-fixture").userId());
        assertNull(database.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, first.userId()));
        request(body(post("/auth/login"), Map.of("handle", first.handle(), "password", "test-only-password-12345")), null)
                .andExpect(status().isUnauthorized());
        request(body(post("/auth/google/challenge"), new GoogleAuth.Start(null)), null)
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"google_sign_in_unavailable\"}"));
        List<String> fields = database.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'accounts' AND table_schema = 'public' ORDER BY ordinal_position", String.class);
        assertEquals(List.of("id", "handle", "password_hash", "google_subject", "display_name", "user_type", "deletion_state"), fields);
        assertEquals(AccountDirectory.UserType.USER, directory.accountType(first.userId()).userType());
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

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        new java.security.SecureRandom().nextBytes(value);
        return value;
    }

    private MockHttpServletRequestBuilder backup(byte[] ciphertext) {
        return put("/account/backup").contentType("application/octet-stream").content(ciphertext);
    }

    @Test void encryptedBackupIsOwnerScopedOpaqueReplaceableAndDeletable() throws Exception {
        Device alice = device("backup_alice"), bob = device("backup_bob");
        request(backup(randomBytes(128)), null).andExpect(status().isUnauthorized());
        request(get("/account/backup"), null).andExpect(status().isUnauthorized());
        request(get("/account/backup"), alice).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        request(get("/account/backup/status"), alice).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.size").value(0));

        byte[] first = randomBytes(1000);
        long before = System.currentTimeMillis();
        request(backup(first), alice).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.size").value(1000))
                .andExpect(jsonPath("$.updatedAt").value(org.hamcrest.Matchers.greaterThan(before - 600_000)))
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.greaterThan(before + Duration.ofDays(89).toMillis())));
        request(get("/account/backup"), alice).andExpect(status().isOk()).andExpect(content().contentType("application/octet-stream"))
                .andExpect(content().bytes(first));
        assertEquals(Duration.ofDays(90).toSeconds(), database.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - updated_at))::bigint FROM account_backups WHERE user_id = ?", Long.class, alice.userId()));

        request(get("/account/backup"), bob).andExpect(status().isNotFound());
        request(get("/account/backup/status"), bob).andExpect(jsonPath("$.exists").value(false));
        byte[] replacement = randomBytes(64);
        request(backup(replacement), alice).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(64));
        request(get("/account/backup"), alice).andExpect(content().bytes(replacement));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));

        request(delete("/account/backup"), bob).andExpect(status().isNoContent());
        request(get("/account/backup"), alice).andExpect(status().isOk());
        request(delete("/account/backup"), alice).andExpect(status().isNoContent());
        request(delete("/account/backup"), alice).andExpect(status().isNoContent());
        request(get("/account/backup"), alice).andExpect(status().isNotFound());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));
    }

    @Test void encryptedBackupNeedsADeviceAndBoundsItsSizeBeforeStorage() throws Exception {
        Device owner = device("backup_bounds");
        Device enrolling = enrollment(owner, recentLogin("backup_bounds", null));
        request(backup(randomBytes(128)), enrolling).andExpect(status().isForbidden());
        request(get("/account/backup"), enrolling).andExpect(status().isForbidden());
        request(backup(randomBytes(RelayPolicy.MIN_BACKUP_BYTES - 1)), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_backup"));
        request(backup(new byte[0]), owner).andExpect(status().isBadRequest());
        request(backup(randomBytes(RelayPolicy.MAX_BACKUP_BYTES + 1)), owner).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("request_too_large"));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));
        byte[] maximum = randomBytes(RelayPolicy.MAX_BACKUP_BYTES);
        request(backup(randomBytes(RelayPolicy.MIN_BACKUP_BYTES)), owner).andExpect(status().isOk());
        request(backup(maximum), owner).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(RelayPolicy.MAX_BACKUP_BYTES));
        request(get("/account/backup"), owner).andExpect(status().isOk()).andExpect(content().bytes(maximum));
        request(body(post("/account/backup"), Map.of("ciphertext", "AAAA")), owner).andExpect(status().isMethodNotAllowed());
    }

    @Test void encryptedBackupHasBoundedRetentionIsPurgedAndErasedWithTheAccount() throws Exception {
        Device owner = device("backup_expiry");
        request(backup(randomBytes(256)), owner).andExpect(status().isOk());
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "UPDATE account_backups SET expires_at = updated_at + interval '91 days' WHERE user_id = ?", owner.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "UPDATE account_backups SET expires_at = updated_at WHERE user_id = ?", owner.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "UPDATE account_backups SET ciphertext = '\\x00'::bytea WHERE user_id = ?", owner.userId()));

        // The 90-day bound is absolute hours, so a daylight-saving session time zone cannot stretch or shrink it.
        for (String zone : List.of("UTC", "America/Los_Angeles", "Asia/Kolkata")) {
            var session = new org.springframework.jdbc.datasource.SingleConnectionDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
            try {
                JdbcTemplate zoned = new JdbcTemplate(session);
                zoned.execute("SET TIME ZONE '" + zone + "'");
                String start = "timestamptz '2026-10-09 12:00:00+00'";
                assertEquals(1, zoned.update("UPDATE account_backups SET updated_at = " + start + ", expires_at = " + start
                        + " + interval '2160 hours' WHERE user_id = ?", owner.userId()), zone);
                assertThrows(DataIntegrityViolationException.class, () -> zoned.update("UPDATE account_backups SET updated_at = " + start
                        + ", expires_at = " + start + " + interval '2161 hours' WHERE user_id = ?", owner.userId()), zone);
                new AccountBackups(zoned).put(owner.userId(), randomBytes(128));
                assertEquals(Duration.ofDays(90).toSeconds(), zoned.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM (expires_at - updated_at))::bigint FROM account_backups WHERE user_id = ?", Long.class, owner.userId()), zone);
            } finally { session.destroy(); }
        }

        database.update("UPDATE account_backups SET updated_at = now() - interval '2184 hours', expires_at = now() - interval '24 hours' WHERE user_id = ?", owner.userId());
        request(get("/account/backup"), owner).andExpect(status().isNotFound());
        request(get("/account/backup/status"), owner).andExpect(jsonPath("$.exists").value(false));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));
        new AccountBackups(database).purgeExpired();
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));

        request(backup(randomBytes(256)), owner).andExpect(status().isOk());
        Device confirming = enrollment(owner, recentLogin("backup_expiry", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isNoContent());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_backups", Integer.class));
    }

    @Test void encryptedBackupWritesAreRateLimitedPerDeviceAndNeverRevealStoredBytesInErrors() throws Exception {
        Device owner = device("backup_rate");
        for (int attempt = 0; attempt < 6; attempt++) request(backup(randomBytes(64)), owner).andExpect(status().isOk());
        String failure = request(backup(randomBytes(64)), owner).andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        assertEquals("{\"error\":\"rate_limited\"}", failure);
        request(get("/account/backup/status"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true));
    }

    private AuthService.Token recentLogin(String handle, UUID device) throws Exception {
        return json.readValue(request(body(post("/auth/login"),
                new AuthService.Login(handle, "test-only-password-12345", device)), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), AuthService.Token.class);
    }

    private Device enrollment(Device owner, AuthService.Token session) {
        return new Device(owner.userId(), null, session.accessToken(), owner.crypto());
    }

    private SendRequest opaqueSend(Device sender, Device recipient, UUID media) {
        return new SendRequest(UUID.randomUUID(), recipient.userId(), recipient.deviceId(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 3_500_000, 2, new byte[128], media);
    }

    private Actor actor(Device device) { return new Actor(device.userId(), device.deviceId()); }

    private AccountDirectory.Contact contact(Device device) {
        return new AccountDirectory.Contact(device.userId(), device.deviceId(), Base64.getEncoder().encodeToString(device.crypto().publicIdentity()));
    }

    private GroupDirectory.Snapshot sharedGroup(Device owner, Device... members) {
        GroupDirectory.Snapshot group = groups.create(actor(owner), UUID.randomUUID());
        group = groups.invite(actor(owner), group.id(), group.revision(), Arrays.stream(members).map(this::contact).toList());
        for (Device member : members) group = groups.accept(actor(member), group.id(), group.revision());
        return group;
    }

    @Test void playSafetyDeletionRequiresRecentEnrollmentAndNeverResurrectsAnAccount() throws Exception {
        Device owner = device("erase_owner"), other = device("erase_other");
        AuthService.Token recent = recentLogin("erase_owner", null);
        Device confirming = enrollment(owner, recent);
        assertNull(recent.deviceId());
        assertTrue(redis.getExpire(AuthService.tokenKey(recent.accessToken())) > 0);
        assertTrue(redis.getExpire(AuthService.tokenKey(recent.accessToken())) <= 300);
        String staleEnrollment = redis.opsForValue().get(AuthService.tokenKey(recent.accessToken()));
        AuthService.Token renewable = recentLogin("erase_owner", owner.deviceId());
        String staleAccess = redis.opsForValue().get(AuthService.tokenKey(renewable.accessToken()));
        String staleRefresh = redis.opsForValue().get(AuthService.refreshKey(renewable.refreshToken()));
        Map<String, String> confirmation = Map.of("confirmation", "DELETE");
        request(body(delete("/account"), confirmation), null).andExpect(status().isUnauthorized());
        request(body(delete("/account"), confirmation), owner).andExpect(status().isForbidden());
        request(body(delete("/account"), Map.of("confirmation", "delete")), confirming).andExpect(status().isBadRequest());
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "userId", other.userId())), confirming)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        AuthService.Token expired = recentLogin("erase_owner", null);
        redis.expire(AuthService.tokenKey(expired.accessToken()), Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(AuthService.tokenKey(expired.accessToken()))));
        request(body(delete("/account"), confirmation), enrollment(owner, expired)).andExpect(status().isUnauthorized());
        request(body(delete("/account"), confirmation), confirming).andExpect(status().isNoContent());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, owner.userId()));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM devices WHERE user_id = ?", Integer.class, owner.userId()));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM prekeys WHERE device_id = ?", Integer.class, owner.deviceId()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(AuthService.tokenKey(renewable.accessToken()))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(AuthService.refreshKey(renewable.refreshToken()))));
        request(get("/auth/me"), other).andExpect(status().isOk());
        request(get("/users/id/" + owner.userId()), other).andExpect(status().isNotFound());
        request(get("/keys"), other).andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(1));
        redis.opsForValue().set(AuthService.tokenKey(recent.accessToken()), staleEnrollment, Duration.ofMinutes(5));
        redis.opsForValue().set(AuthService.tokenKey(renewable.accessToken()), staleAccess, Duration.ofHours(1));
        redis.opsForValue().set(AuthService.refreshKey(renewable.refreshToken()), staleRefresh, Duration.ofDays(30));
        request(get("/auth/me"), confirming).andExpect(status().isUnauthorized());
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(owner.deviceId(), owner.crypto().publicIdentity(), true)), confirming)
                .andExpect(status().isUnauthorized());
        request(body(delete("/account"), confirmation), confirming).andExpect(status().isUnauthorized());
        request(body(post("/auth/refresh"), new AuthService.Refresh(renewable.refreshToken(), null)), null).andExpect(status().isUnauthorized());
        request(get("/auth/me"), new Device(owner.userId(), owner.deviceId(), renewable.accessToken(), owner.crypto())).andExpect(status().isUnauthorized());
        Device reusedHandle = device("erase_owner");
        assertNotEquals(owner.userId(), reusedHandle.userId());
        request(body(delete("/account"), confirmation), confirming).andExpect(status().isUnauthorized());
        request(get("/auth/me"), reusedHandle).andExpect(status().isOk());
    }

    @Test void playSafetyDeletionFailuresStayDisabledAndRetryAfterRedisAndDatabaseFailures() throws Exception {
        Device owner = device("erase_retry"), other = device("erase_retry_other");
        Device confirming = enrollment(owner, recentLogin("erase_retry", null));
        String broken = "r:" + UUID.randomUUID();
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "redis.call('LPUSH', KEYS[1], 'test-only'); redis.call('EXPIRE', KEYS[1], 60); return 1", Long.class), List.of(broken));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming)
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("service_unavailable"));
        assertEquals("DELETING", database.queryForObject("SELECT deletion_state FROM accounts WHERE id = ?", String.class, owner.userId()));
        request(get("/auth/me"), owner).andExpect(status().isUnauthorized());
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(owner.deviceId(), owner.crypto().publicIdentity(), false)), confirming)
                .andExpect(status().isUnauthorized());
        request(get("/users/id/" + owner.userId()), other).andExpect(status().isNotFound());
        redis.delete(broken);
        database.execute("""
                CREATE FUNCTION test_erasure_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic_cleanup_failure' USING ERRCODE = '23514'; END; $$;
                CREATE TRIGGER test_erasure_failure BEFORE DELETE ON accounts FOR EACH ROW EXECUTE FUNCTION test_erasure_failure();
                """);
        try {
            request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isServiceUnavailable());
            assertEquals("DELETING", database.queryForObject("SELECT deletion_state FROM accounts WHERE id = ?", String.class, owner.userId()));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(AuthService.tokenKey(confirming.token()))));
        } finally {
            database.execute("DROP TRIGGER test_erasure_failure ON accounts");
            database.execute("DROP FUNCTION test_erasure_failure()");
        }
        Device retry = enrollment(owner, recentLogin("erase_retry", null));
        request(get("/auth/me"), retry).andExpect(status().isUnauthorized());
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), retry).andExpect(status().isNoContent());
        request(get("/auth/me"), other).andExpect(status().isOk());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, owner.userId()));
    }

    @Test void playSafetyAdminDeletionKeepsOnlyThePermanentReservationAndPreventsTransfer() throws Exception {
        Device admin = device("erased_admin");
        assignAdmin(admin);
        Device other = device("erased_admin_other");
        Device reported = device("erased_admin_reported");
        request(body(post("/safety/reports"), new SafetyReports.Submission(reported.userId(), SafetyReports.Reason.SPAM, null, null)), other)
                .andExpect(status().isCreated());
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM admin_introductions", Integer.class));
        Device confirming = enrollment(admin, recentLogin("erased_admin", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isNoContent());
        assertEquals(admin.userId(), database.queryForObject("SELECT user_id FROM admin_identity", UUID.class));
        assertEquals(List.of("singleton", "user_id"), database.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_identity' ORDER BY ordinal_position", String.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM admin_introductions", Integer.class));
        assertTrue(Objects.requireNonNull(redis.keys("safety-report*")).isEmpty());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, admin.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update("UPDATE accounts SET user_type='ADMIN' WHERE id=?", other.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update("DELETE FROM admin_identity"));
        assertThrows(DataIntegrityViolationException.class, () -> database.update("UPDATE admin_identity SET user_id=?", other.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "INSERT INTO accounts(id,handle,password_hash) VALUES (?,'resurrected_pin','synthetic')", admin.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "UPDATE accounts SET id=? WHERE id=?", admin.userId(), other.userId()));
        device("erased_admin");
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_type='ADMIN'", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM admin_introductions", Integer.class));
        request(get("/account/admin-contacts"), other).andExpect(status().isOk()).andExpect(jsonPath("$.contacts.length()").value(0));
        request(body(post("/safety/reports"), new SafetyReports.Submission(reported.userId(), SafetyReports.Reason.SPAM, null, null)), other)
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("safety_review_unavailable"));
        request(body(post("/safety/reports"), new SafetyReports.Submission(other.userId(), SafetyReports.Reason.OTHER, null, null)), confirming)
                .andExpect(status().isUnauthorized());
    }

    @Test void playSafetyDeletionCleansRoutingAndMembershipWithoutDeletingOtherMembersCopies() throws Exception {
        Device admin = device("cleanup_admin");
        assignAdmin(admin);
        Device victim = device("cleanup_victim"), peer = device("cleanup_peer"), other = device("cleanup_other");
        var victimChat = foreground(victim);
        var victimPhotos = foreground(victim, true);
        foreground(admin); foreground(admin, true);
        UUID photo = UUID.randomUUID();
        request(body(post("/remote-photos"), photoRequest(photo, admin, victim)), admin).andExpect(status().isCreated());
        request(body(post("/remote-photos/" + photo + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), victim).andExpect(status().isOk());
        request(body(post("/remote-photos/" + photo + "/exchange"), new RemotePhotos.Exchange(null,
                new RemotePhotos.Send(UUID.randomUUID(), System.currentTimeMillis() + 50_000, 2, new byte[128]))), admin).andExpect(status().isOk());

        GroupDirectory.Snapshot shared = sharedGroup(other, victim, peer);
        GroupDirectory.Snapshot owned = sharedGroup(victim, peer);
        GroupMessages.Send fromPeer = new GroupMessages.Send(UUID.randomUUID(), shared.epoch(), shared.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 3_500_000, new byte[128], UUID.randomUUID(), new byte[32]);
        GroupMessages.Send fromVictim = new GroupMessages.Send(UUID.randomUUID(), shared.epoch(), shared.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 3_500_000, new byte[128], UUID.randomUUID(), new byte[32]);
        request(body(post("/groups/" + shared.id() + "/messages"), fromPeer), peer).andExpect(status().isCreated());
        request(body(post("/groups/" + shared.id() + "/messages"), fromVictim), victim).andExpect(status().isCreated());
        GroupMessages.Send ownedMessage = new GroupMessages.Send(UUID.randomUUID(), owned.epoch(), owned.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 3_500_000, new byte[128], UUID.randomUUID(), new byte[32]);
        request(body(post("/groups/" + owned.id() + "/messages"), ownedMessage), victim).andExpect(status().isCreated());
        String sharedPayload = redis.opsForValue().get("gm:" + shared.id() + ":" + fromVictim.id());
        long sharedTtl = redis.getExpire("gm:" + shared.id() + ":" + fromVictim.id(), java.util.concurrent.TimeUnit.MILLISECONDS);

        SendRequest queued = opaqueSend(peer, victim, UUID.randomUUID());
        request(put("/media/" + queued.mediaId()).param("recipientId", victim.userId().toString())
                .param("recipientDeviceId", victim.deviceId().toString()).param("expiresAt", Long.toString(queued.expiresAt()))
                .contentType("application/octet-stream").content(new byte[32]), peer).andExpect(status().isCreated());
        request(body(post("/messages"), queued), peer).andExpect(status().isCreated());
        SendRequest outgoing = opaqueSend(victim, peer, null), untouched = opaqueSend(peer, other, null);
        request(body(post("/messages"), outgoing), victim).andExpect(status().isCreated());
        request(body(post("/messages"), untouched), peer).andExpect(status().isCreated());
        UUID detached = UUID.randomUUID();
        request(put("/media/" + detached).param("recipientId", peer.userId().toString()).param("recipientDeviceId", peer.deviceId().toString())
                .param("expiresAt", Long.toString(queued.expiresAt())).contentType("application/octet-stream").content(new byte[32]), victim)
                .andExpect(status().isCreated());
        UUID profile = UUID.randomUUID();
        request(body(post("/profile/packets"), new ProfileMessages.Send(profile, peer.userId(), peer.deviceId(), queued.expiresAt(), 2, new byte[128])), victim)
                .andExpect(status().isNoContent());
        request(body(post("/devices/push"), new RelayController.PushToken("synthetic-push-token-for-erasure", true)), victim).andExpect(status().isNoContent());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer)), null, 0, true)), victim).andExpect(status().isOk());
        assertNotNull(notifications.reference(new GenericNotifier.Destination(peer.userId(), peer.deviceId(), victim.userId(), outgoing.id(), outgoing.expiresAt())));
        String otherRoute = notifications.reference(new GenericNotifier.Destination(other.userId(), other.deviceId(), peer.userId(), untouched.id(), untouched.expiresAt()));
        database.update("INSERT INTO account_blocks(blocker_id,blocked_id) VALUES (?,?)", victim.userId(), other.userId());
        request(body(post("/safety/reports"), new SafetyReports.Submission(peer.userId(), SafetyReports.Reason.SPAM, null, null)), victim)
                .andExpect(status().isCreated());
        request(body(post("/safety/reports"), new SafetyReports.Submission(admin.userId(), SafetyReports.Reason.OTHER, null, null)), peer)
                .andExpect(status().isCreated());

        Device confirming = enrollment(victim, recentLogin("cleanup_victim", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isNoContent());
        verify(victimChat).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        verify(victimPhotos).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        for (String key : List.of("m:" + queued.id(), "r:" + queued.id(), "b:" + queued.mediaId(), "b:" + detached,
                "m:" + outgoing.id(), "r:" + outgoing.id(), "pc:" + peer.deviceId() + ":" + profile, "pcr:" + peer.deviceId() + ":" + profile,
                "rps:" + photo, "rpq:" + photo + ":" + victim.deviceId(), "presence:" + victim.deviceId(), "last-seen:" + victim.deviceId(),
                "push:" + victim.deviceId(), "notification:" + peer.deviceId(), "inbox:" + victim.deviceId(), "outbox:" + victim.deviceId(),
                AuthService.tokenKey(victim.token()), "gm:" + owned.id() + ":" + ownedMessage.id())) assertFalse(Boolean.TRUE.equals(redis.hasKey(key)), key);
        request(body(post("/remote-photos/" + photo + "/exchange"), new RemotePhotos.Exchange(null, null)), admin).andExpect(status().isGone());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM group_members WHERE user_id = ?", Integer.class, victim.userId()));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM private_groups WHERE id = ?", Integer.class, owned.id()));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM admin_introductions WHERE user_id = ?", Integer.class, victim.userId()));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_blocks WHERE blocker_id = ? OR blocked_id = ?", Integer.class, victim.userId(), victim.userId()));
        request(get("/groups/" + shared.id()), peer).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(shared.revision() + 1)).andExpect(jsonPath("$.members.length()").value(2));
        assertNotEquals(shared.epoch(), database.queryForObject("SELECT epoch FROM private_groups WHERE id = ?", UUID.class, shared.id()));
        assertFalse(json.readTree(redis.opsForValue().get("gr:" + shared.id() + ":" + fromPeer.id())).get("recipients").has(victim.deviceId().toString()));
        assertEquals(sharedPayload, redis.opsForValue().get("gm:" + shared.id() + ":" + fromVictim.id()));
        assertTrue(redis.getExpire("gm:" + shared.id() + ":" + fromVictim.id(), java.util.concurrent.TimeUnit.MILLISECONDS) <= sharedTtl);
        request(get("/groups/" + shared.id() + "/messages/" + fromVictim.id() + "/media"), peer).andExpect(status().isOk());
        request(body(post("/groups/" + shared.id() + "/messages"), fromPeer), peer).andExpect(status().isConflict());
        request(get("/messages/pending"), other).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(untouched.id().toString()));
        request(body(post("/notifications/resolve"), new RelayController.NotificationReference(otherRoute)), other).andExpect(status().isOk());
        request(get("/safety/reports"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reporterId").value(peer.userId().toString()));
    }

    @Test void playSafetyBlocksRejectOldClientBypassesAndPurgeQueuedDirectContent() throws Exception {
        Device blocker = device("block_owner"), peer = device("block_peer"), other = device("block_other");
        foreground(blocker); foreground(peer);
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(blocker)), blocker.userId(), 5000, true)), peer).andExpect(status().isOk());
        request(body(post("/presence"), new Presence.Update(List.of(presencePeer(peer)), null, 0, true)), blocker)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        SendRequest before = opaqueSend(peer, blocker, UUID.randomUUID());
        request(put("/media/" + before.mediaId()).param("recipientId", blocker.userId().toString())
                .param("recipientDeviceId", blocker.deviceId().toString()).param("expiresAt", Long.toString(before.expiresAt()))
                .contentType("application/octet-stream").content(new byte[32]), peer).andExpect(status().isCreated());
        request(body(post("/messages"), before), peer).andExpect(status().isCreated());
        request(get("/media/" + before.mediaId()), blocker).andExpect(status().isOk());
        ProfileMessages.Send profile = new ProfileMessages.Send(UUID.randomUUID(), blocker.userId(), blocker.deviceId(), before.expiresAt(), 2, new byte[128]);
        request(body(post("/profile/packets"), profile), peer).andExpect(status().isNoContent());
        String reference = notifications.reference(new GenericNotifier.Destination(blocker.userId(), blocker.deviceId(), peer.userId(), before.id(), before.expiresAt()));
        long receiptTtl = redis.getExpire("r:" + before.id(), java.util.concurrent.TimeUnit.MILLISECONDS);
        request(put("/account/blocks/" + peer.userId()), null).andExpect(status().isUnauthorized());
        request(put("/account/blocks/" + peer.userId()), enrollment(blocker, recentLogin("block_owner", null))).andExpect(status().isForbidden());
        request(put("/account/blocks/" + blocker.userId()), blocker).andExpect(status().isBadRequest());
        request(put("/account/blocks/" + UUID.randomUUID()), blocker).andExpect(status().isNotFound());
        request(put("/account/blocks/" + peer.userId()), blocker).andExpect(status().isNoContent());
        request(put("/account/blocks/" + peer.userId()), blocker).andExpect(status().isNoContent());
        request(get("/account/blocks"), blocker).andExpect(status().isOk()).andExpect(content().json("[\"" + peer.userId() + "\"]"));
        request(get("/account/blocks"), peer).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(delete("/account/blocks/" + blocker.userId()), peer).andExpect(status().isNoContent());
        request(get("/account/blocks"), blocker).andExpect(jsonPath("$.length()").value(1));
        for (Device sender : List.of(blocker, peer)) {
            Device recipient = sender == blocker ? peer : blocker;
            request(body(post("/messages"), opaqueSend(sender, recipient, null)), sender).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
            request(post("/keys/" + recipient.userId() + "/claim"), sender).andExpect(status().isNotFound());
            request(get("/users/id/" + recipient.userId()), sender).andExpect(status().isNotFound());
            request(get("/users/id/" + recipient.userId() + "/profile"), sender).andExpect(status().isNotFound());
            request(body(post("/profile/packets"), new ProfileMessages.Send(UUID.randomUUID(), recipient.userId(), recipient.deviceId(),
                    before.expiresAt(), 2, new byte[128])), sender).andExpect(status().isNotFound());
            request(put("/media/" + UUID.randomUUID()).param("recipientId", recipient.userId().toString()).param("recipientDeviceId", recipient.deviceId().toString())
                    .param("expiresAt", Long.toString(before.expiresAt())).contentType("application/octet-stream").content(new byte[32]), sender)
                    .andExpect(status().isNotFound());
            request(body(post("/presence"), new Presence.Update(List.of(presencePeer(recipient)), recipient.userId(), 5000, true)), sender)
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
        }
        request(get("/users/block_peer"), blocker).andExpect(status().isNotFound());
        request(get("/messages/pending"), blocker).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/profile/packets"), blocker).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(get("/media/" + before.mediaId()), blocker).andExpect(status().isNotFound());
        request(post("/messages/" + before.id() + "/read"), blocker).andExpect(status().isNotFound());
        request(get("/messages/status").param("ids", before.id().toString()), peer).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(body(post("/notifications/resolve"), new RelayController.NotificationReference(reference)), blocker).andExpect(status().isNotFound());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("m:" + before.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("b:" + before.mediaId())));
        assertTrue(redis.getExpire("r:" + before.id(), java.util.concurrent.TimeUnit.MILLISECONDS) <= receiptTtl);
        assertEquals("DELETED", json.readTree(redis.opsForValue().get("r:" + before.id())).get("state").asText());
        request(body(post("/messages"), opaqueSend(peer, other, null)), peer).andExpect(status().isCreated());
        request(put("/account/blocks/" + blocker.userId()), peer).andExpect(status().isNoContent());
        request(delete("/account/blocks/" + peer.userId()), blocker).andExpect(status().isNoContent());
        request(post("/keys/" + peer.userId() + "/claim"), blocker).andExpect(status().isNotFound());
        request(delete("/account/blocks/" + blocker.userId()), peer).andExpect(status().isNoContent());
        request(body(post("/messages"), before), peer).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("DELETED"));
        request(body(post("/profile/packets"), profile), peer).andExpect(status().isNoContent());
        request(get("/messages/pending"), blocker).andExpect(content().json("[]"));
        request(get("/profile/packets"), blocker).andExpect(content().json("[]"));
        request(body(post("/messages"), opaqueSend(peer, blocker, null)), peer).andExpect(status().isCreated());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM prekeys WHERE device_id = ?", Integer.class, peer.deviceId()));
    }

    @Test void playSafetyBlocksEndRemotePhotosAndPreventIntroductionsAndNewGroupConnections() throws Exception {
        Device admin = device("block_admin");
        assignAdmin(admin);
        Device user = device("block_new_user"), other = device("block_group_other");
        foreground(admin); foreground(admin, true); foreground(user); foreground(user, true);
        GroupDirectory.Snapshot shared = sharedGroup(admin, user, other);
        GroupDirectory.Snapshot pending = groups.create(actor(admin), UUID.randomUUID());
        groups.invite(actor(admin), pending.id(), pending.revision(), List.of(contact(user)));
        UUID session = UUID.randomUUID();
        request(body(post("/remote-photos"), photoRequest(session, admin, user)), admin).andExpect(status().isCreated());
        request(body(post("/remote-photos/" + session + "/accept"), new RemotePhotos.Approval(presencePeer(admin))), user).andExpect(status().isOk());
        request(body(post("/remote-photos/" + session + "/exchange"), new RemotePhotos.Exchange(null,
                new RemotePhotos.Send(UUID.randomUUID(), System.currentTimeMillis() + 50_000, 2, new byte[128]))), admin).andExpect(status().isOk());
        GroupMessages.ControlSend control = new GroupMessages.ControlSend(UUID.randomUUID(), user.userId(), user.deviceId(),
                shared.epoch(), shared.revision(), System.currentTimeMillis() + 60_000, 2, new byte[128]);
        request(body(post("/groups/" + shared.id() + "/controls"), new GroupController.Controls(shared.revision(), List.of(control))), admin)
                .andExpect(status().isNoContent());
        request(put("/account/blocks/" + admin.userId()), user).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("rps:" + session)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("rpq:" + session + ":" + user.deviceId())));
        assertTrue(redis.getExpire("rps-ended:" + session) > 0 && redis.getExpire("rps-ended:" + session) <= 900);
        request(body(post("/remote-photos/" + session + "/exchange"), new RemotePhotos.Exchange(null, null)), admin).andExpect(status().isGone());
        request(body(post("/remote-photos"), photoRequest(UUID.randomUUID(), admin, user)), admin).andExpect(status().isNotFound());
        request(get("/account/admin-contacts"), user).andExpect(status().isOk()).andExpect(jsonPath("$.admin").doesNotExist()).andExpect(jsonPath("$.contacts.length()").value(0));
        request(get("/account/admin-contacts/" + user.userId()), admin).andExpect(status().isOk()).andExpect(jsonPath("$.contacts.length()").value(0));
        request(body(post("/groups/" + pending.id() + "/accept"), new GroupController.Revision(pending.revision())), user).andExpect(status().isNotFound());
        request(body(post("/groups/" + pending.id() + "/invitations"), new GroupController.Invite(pending.revision(), List.of(contact(user)))), admin)
                .andExpect(status().isNotFound());
        GroupDirectory.Snapshot thirdParty = groups.create(actor(other), UUID.randomUUID());
        request(body(post("/groups/" + thirdParty.id() + "/invitations"), new GroupController.Invite(thirdParty.revision(), List.of(contact(user), contact(admin)))), other)
                .andExpect(status().isNotFound());
        request(body(post("/groups/" + shared.id() + "/keys"), new GroupController.Claims(shared.revision(), List.of(user.userId()))), admin)
                .andExpect(status().isNotFound());
        request(body(post("/groups/" + shared.id() + "/controls"), new GroupController.Controls(shared.revision(), List.of(control))), admin)
                .andExpect(status().isNotFound());
        request(get("/groups/" + shared.id() + "/controls"), user).andExpect(status().isOk()).andExpect(content().json("[]"));
        GroupMessages.Send groupMessage = new GroupMessages.Send(UUID.randomUUID(), shared.epoch(), shared.revision(), Expiry.HOUR_1,
                System.currentTimeMillis() + 60_000, new byte[128], null, null);
        request(body(post("/groups/" + shared.id() + "/messages"), groupMessage), admin).andExpect(status().isCreated());
        request(get("/groups/" + shared.id() + "/messages"), user).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(groupMessage.id().toString()));
        request(delete("/account/blocks/" + admin.userId()), user).andExpect(status().isNoContent());
        request(get("/account/admin-contacts"), user).andExpect(jsonPath("$.contacts.length()").value(0));
        request(body(post("/remote-photos"), photoRequest(session, admin, user)), admin).andExpect(status().isGone());
    }

    @Test void playSafetyReportsValidateAccessibleContextAndRecheckThePinnedAdmin() throws Exception {
        Device admin = device("report_admin");
        assignAdmin(admin);
        Device reporter = device("report_author"), target = device("report_target"), outsider = device("report_outsider");
        SendRequest message = opaqueSend(target, reporter, null);
        request(body(post("/messages"), message), target).andExpect(status().isCreated());
        SafetyReports.Submission submission = new SafetyReports.Submission(target.userId(), SafetyReports.Reason.HARASSMENT, message.id(), null);
        request(body(post("/safety/reports"), submission), null).andExpect(status().isUnauthorized());
        request(body(post("/safety/reports"), submission), enrollment(reporter, recentLogin("report_author", null))).andExpect(status().isForbidden());
        request(body(post("/safety/reports"), submission), outsider).andExpect(status().isNotFound());
        request(body(post("/safety/reports"), new SafetyReports.Submission(outsider.userId(), SafetyReports.Reason.OTHER, message.id(), null)), reporter)
                .andExpect(status().isNotFound());
        request(body(post("/safety/reports"), new SafetyReports.Submission(reporter.userId(), SafetyReports.Reason.OTHER, null, null)), reporter)
                .andExpect(status().isBadRequest());
        request(body(post("/safety/reports"), new SafetyReports.Submission(UUID.randomUUID(), SafetyReports.Reason.OTHER, null, null)), reporter)
                .andExpect(status().isNotFound());
        for (String field : List.of("text", "plaintext", "photo", "reporterId", "expiresAt")) {
            request(body(post("/safety/reports"), Map.of("targetId", target.userId(), "reason", "SPAM", field, "unaccepted-test-only")), reporter)
                    .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}"));
        }
        request(body(post("/safety/reports"), Map.of("targetId", target.userId(), "reason", "NOT_A_REASON")), reporter)
                .andExpect(status().isBadRequest());
        request(body(post("/safety/reports"), Map.of("targetId", target.userId(), "reason", 0)), reporter)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        JsonNode accepted = json.readTree(request(body(post("/safety/reports"), submission), reporter)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals(Set.of("id", "expiresAt"), json.convertValue(accepted, Map.class).keySet());
        UUID id = UUID.fromString(accepted.get("id").asText());
        long ttl = redis.getExpire("safety-report:" + id, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= SafetyReports.LIFETIME);
        JsonNode stored = json.readTree(redis.opsForValue().get("safety-report:" + id));
        assertEquals(reporter.userId().toString(), stored.get("reporterId").asText());
        assertEquals(target.userId().toString(), stored.get("targetId").asText());
        assertEquals(SafetyReports.LIFETIME, stored.get("expiresAt").asLong() - stored.get("createdAt").asLong());
        request(get("/safety/reports"), reporter).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("admin_required"));
        request(get("/safety/reports"), null).andExpect(status().isUnauthorized());
        request(delete("/safety/reports/" + id), reporter).andExpect(status().isForbidden());
        request(delete("/safety/reports/" + id), outsider).andExpect(status().isForbidden());
        request(get("/safety/reports"), admin).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].reason").value("HARASSMENT")).andExpect(jsonPath("$[0].messageId").value(message.id().toString()));
        assertTrue(redis.getExpire("safety-report:" + id, java.util.concurrent.TimeUnit.MILLISECONDS) <= ttl);
        GroupDirectory.Snapshot group = sharedGroup(target, reporter);
        GroupMessages.Send groupMessage = new GroupMessages.Send(UUID.randomUUID(), group.epoch(), group.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 60_000, new byte[128], null, null);
        request(body(post("/groups/" + group.id() + "/messages"), groupMessage), target).andExpect(status().isCreated());
        SafetyReports.Submission groupReport = new SafetyReports.Submission(target.userId(), SafetyReports.Reason.THREATS, groupMessage.id(), group.id());
        request(body(post("/safety/reports"), groupReport), outsider).andExpect(status().isNotFound());
        request(body(post("/safety/reports"), groupReport), reporter).andExpect(status().isCreated());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.OTHER, null, group.id())), reporter)
                .andExpect(status().isCreated());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.OTHER, UUID.randomUUID(), group.id())), reporter)
                .andExpect(status().isNotFound());
        request(post("/messages/" + message.id() + "/read"), reporter).andExpect(status().isOk());
        request(body(post("/safety/reports"), submission), reporter).andExpect(status().isNotFound());
        request(put("/account/blocks/" + target.userId()), reporter).andExpect(status().isNoContent());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.CHILD_SAFETY, null, null)), reporter)
                .andExpect(status().isCreated());
        database.update("UPDATE accounts SET user_type='USER' WHERE id=?", admin.userId());
        request(get("/safety/reports"), admin).andExpect(status().isForbidden());
        request(delete("/safety/reports/" + id), admin).andExpect(status().isForbidden());
        database.execute("ALTER TABLE accounts DISABLE TRIGGER accounts_admin_identity");
        try { database.update("UPDATE accounts SET user_type='ADMIN' WHERE id=?", outsider.userId()); }
        finally { database.execute("ALTER TABLE accounts ENABLE TRIGGER accounts_admin_identity"); }
        request(get("/safety/reports"), outsider).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("admin_identity_unavailable"));
        request(delete("/safety/reports/" + id), outsider).andExpect(status().isServiceUnavailable());
        database.update("UPDATE accounts SET user_type='USER' WHERE id=?", outsider.userId());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.SPAM, null, null)), reporter)
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("safety_review_unavailable"));
        database.update("UPDATE accounts SET user_type='ADMIN' WHERE id=?", admin.userId());
        request(delete("/safety/reports/" + id), admin).andExpect(status().isNoContent());
        request(delete("/safety/reports/" + id), admin).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("safety-report:" + id)));
        assertNull(redis.opsForZSet().score("safety-reports", id.toString()));
    }

    @Test void playSafetyGroupOnlyReportsRemainParticipantAuthorizedAfterExpiryAndViewOnceRead() throws Exception {
        Device admin = device("group_report_admin");
        assignAdmin(admin);
        Device target = device("group_report_target"), reporter = device("group_report_member");
        Device outsider = device("group_report_outsider"), invited = device("group_report_invited");
        GroupDirectory.Snapshot group = sharedGroup(target, reporter);
        groups.invite(actor(target), group.id(), group.revision(), List.of(contact(invited)));
        String path = "/groups/" + group.id();
        Map<String, Object> groupOnly = Map.of("targetId", target.userId(), "reason", "HARASSMENT", "groupId", group.id());

        request(body(post("/safety/reports"), groupOnly), outsider).andExpect(status().isNotFound());
        request(body(post("/safety/reports"), groupOnly), invited).andExpect(status().isNotFound());
        request(body(post("/safety/reports"), new SafetyReports.Submission(invited.userId(), SafetyReports.Reason.OTHER, null, group.id())), reporter)
                .andExpect(status().isNotFound());
        request(body(post("/safety/reports"), new SafetyReports.Submission(outsider.userId(), SafetyReports.Reason.OTHER, null, group.id())), reporter)
                .andExpect(status().isNotFound());

        GroupMessages.Send viewOnce = new GroupMessages.Send(UUID.randomUUID(), group.epoch(), group.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 60_000, new byte[128], UUID.randomUUID(), new byte[32]);
        request(body(post(path + "/messages"), viewOnce), target).andExpect(status().isCreated());
        request(post(path + "/messages/" + viewOnce.id() + "/read"), reporter).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("gm:" + group.id() + ":" + viewOnce.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("gb:" + group.id() + ":" + viewOnce.id())));
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.HARASSMENT, viewOnce.id(), group.id())), reporter)
                .andExpect(status().isNotFound());
        request(body(post("/safety/reports"), groupOnly), reporter).andExpect(status().isCreated());

        GroupMessages.Send expiring = new GroupMessages.Send(UUID.randomUUID(), group.epoch(), group.revision(), Expiry.HOUR_1,
                System.currentTimeMillis() + 2_000, new byte[128], null, null);
        request(body(post(path + "/messages"), expiring), target).andExpect(status().isCreated());
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey("gm:" + group.id() + ":" + expiring.id())));
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.HARASSMENT, expiring.id(), group.id())), reporter)
                .andExpect(status().isNotFound());
        request(body(post("/safety/reports"), groupOnly), reporter).andExpect(status().isCreated());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.OTHER, UUID.randomUUID(), group.id())), reporter)
                .andExpect(status().isNotFound());

        SafetyReports.Report[] reports = json.readValue(request(get("/safety/reports"), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), SafetyReports.Report[].class);
        assertEquals(2, reports.length);
        for (SafetyReports.Report report : reports) {
            assertEquals(group.id(), report.groupId());
            assertEquals(reporter.userId(), report.reporterId());
            assertEquals(target.userId(), report.targetId());
            assertNull(report.messageId());
            assertFalse(redis.opsForValue().get("safety-report:" + report.id()).contains(viewOnce.id().toString()));
            assertFalse(redis.opsForValue().get("safety-report:" + report.id()).contains(expiring.id().toString()));
        }
        groups.remove(actor(target), group.id(), group.revision(), reporter.userId());
        request(body(post("/safety/reports"), groupOnly), reporter).andExpect(status().isNotFound());
        GroupDirectory.Snapshot closed = sharedGroup(target, reporter);
        groups.close(actor(target), closed.id(), closed.revision());
        request(body(post("/safety/reports"), new SafetyReports.Submission(target.userId(), SafetyReports.Reason.OTHER, null, closed.id())), reporter)
                .andExpect(status().isNotFound());
    }

    @Test void playSafetyReportsHaveAtomicThirtyDayTtlsRateLimitsAndBoundedReviewPages() throws Exception {
        Device admin = device("rate_report_admin");
        assignAdmin(admin);
        Device reporter = device("rate_report_author"), target = device("rate_report_target");
        SafetyReports.Submission submission = new SafetyReports.Submission(target.userId(), SafetyReports.Reason.SPAM, null, null);
        for (int index = 0; index < 5; index++) request(body(post("/safety/reports"), submission), reporter).andExpect(status().isCreated());
        request(body(post("/safety/reports"), submission), reporter).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("rate_limited"));
        assertTrue(redis.getExpire("rate:safety:" + reporter.userId()) > 0 && redis.getExpire("rate:safety:" + reporter.userId()) <= 86400);
        request(body(post("/safety/reports"), new SafetyReports.Submission(reporter.userId(), SafetyReports.Reason.IMPERSONATION, null, null)), target)
                .andExpect(status().isCreated());
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<String>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("redis/safety-report.lua"));
        script.setResultType(String.class);
        long now = System.currentTimeMillis();
        for (int index = 0; index < 49; index++) {
            SafetyReports.Report report = new SafetyReports.Report(UUID.randomUUID(), UUID.randomUUID(), target.userId(), SafetyReports.Reason.OTHER,
                    null, null, now, now + SafetyReports.LIFETIME);
            assertEquals("OK", redis.execute(script, List.of("safety-report:" + report.id(), "safety-reports"),
                    json.writeValueAsString(report), Long.toString(now), Long.toString(report.expiresAt()), report.id().toString()));
        }
        SafetyReports.Report tooLong = new SafetyReports.Report(UUID.randomUUID(), reporter.userId(), target.userId(), SafetyReports.Reason.OTHER,
                null, null, now, now + SafetyReports.LIFETIME + 1);
        assertEquals("EXPIRED", redis.execute(script, List.of("safety-report:" + tooLong.id(), "safety-reports"),
                json.writeValueAsString(tooLong), Long.toString(now), Long.toString(tooLong.expiresAt()), tooLong.id().toString()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("safety-report:" + tooLong.id())));
        for (String key : Objects.requireNonNull(redis.keys("*"))) {
            Long lifetime = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
            long maximum = key.startsWith("refresh:") || key.startsWith("safety-report") ? SafetyReports.LIFETIME : Duration.ofDays(1).toMillis();
            assertNotNull(lifetime); assertTrue(lifetime > 0 && lifetime <= maximum, "TTL must remain atomically bounded");
        }
        SafetyReports.Report[] first = json.readValue(request(get("/safety/reports"), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), SafetyReports.Report[].class);
        assertEquals(50, first.length);
        for (SafetyReports.Report report : first) request(delete("/safety/reports/" + report.id()), admin).andExpect(status().isNoContent());
        request(get("/safety/reports"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        String expiring = Objects.requireNonNull(redis.keys("safety-report:*")).iterator().next();
        redis.expire(expiring, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(expiring)));
        request(get("/safety/reports"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                for index = 1, 10000 do redis.call('ZADD', KEYS[1], ARGV[1], 'synthetic-' .. index) end
                redis.call('PEXPIREAT', KEYS[1], ARGV[1]); return 1
                """, Long.class), List.of("safety-reports"), Long.toString(System.currentTimeMillis() + SafetyReports.LIFETIME));
        request(body(post("/safety/reports"), new SafetyReports.Submission(reporter.userId(), SafetyReports.Reason.OTHER, null, null)), target)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("safety_queue_full"));
    }

    @Test void playSafetyBlocksAreBoundedAndV8PreservesExistingRolesAndIntroductions() throws Exception {
        Device blocker = device("block_limit_owner"), target = device("block_limit_target");
        assertThrows(DataIntegrityViolationException.class, () -> database.update("INSERT INTO admin_identity(user_id) VALUES (?)", UUID.randomUUID()));
        UUID unregistered = UUID.randomUUID();
        database.update("INSERT INTO accounts(id,handle,password_hash) VALUES (?,'not_enrolled','synthetic')", unregistered);
        request(put("/account/blocks/" + unregistered), blocker).andExpect(status().isNotFound());
        for (int index = 0; index < BlockDirectory.MAX_BLOCKS; index++) {
            UUID user = UUID.randomUUID();
            database.update("INSERT INTO accounts(id,handle,password_hash) VALUES (?,?,?)", user, "limit_" + index, "synthetic");
            database.update("INSERT INTO account_blocks(blocker_id,blocked_id) VALUES (?,?)", blocker.userId(), user);
        }
        request(get("/account/blocks"), blocker).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(BlockDirectory.MAX_BLOCKS));
        request(put("/account/blocks/" + target.userId()), blocker).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("block_capacity"));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "INSERT INTO account_blocks(blocker_id,blocked_id) VALUES (?,?)", blocker.userId(), blocker.userId()));
        var configuration = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("safety_upgrade_fixture").defaultSchema("safety_upgrade_fixture").locations("classpath:db/migration");
        try {
            configuration.target("7").load().migrate();
            UUID admin = UUID.randomUUID(), user = UUID.randomUUID();
            database.update("INSERT INTO safety_upgrade_fixture.accounts(id,handle,password_hash) VALUES (?,'old_admin','synthetic')", admin);
            database.update("INSERT INTO safety_upgrade_fixture.admin_identity(user_id) VALUES (?)", admin);
            database.update("UPDATE safety_upgrade_fixture.accounts SET user_type='ADMIN' WHERE id=?", admin);
            database.update("INSERT INTO safety_upgrade_fixture.accounts(id,handle,password_hash) VALUES (?,'old_user','synthetic')", user);
            var links = database.queryForList("SELECT * FROM safety_upgrade_fixture.admin_introductions");
            var roles = database.queryForList("SELECT id,handle,user_type FROM safety_upgrade_fixture.accounts ORDER BY id");
            assertEquals(1, configuration.target("8").load().migrate().migrationsExecuted);
            assertEquals(roles, database.queryForList("SELECT id,handle,user_type FROM safety_upgrade_fixture.accounts ORDER BY id"));
            assertEquals(links, database.queryForList("SELECT * FROM safety_upgrade_fixture.admin_introductions"));
            assertEquals(List.of("ACTIVE", "ACTIVE"), database.queryForList("SELECT deletion_state FROM safety_upgrade_fixture.accounts ORDER BY id", String.class));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM safety_upgrade_fixture.account_blocks", Integer.class));
            assertEquals(admin, database.queryForObject("SELECT user_id FROM safety_upgrade_fixture.admin_identity", UUID.class));
            assertThrows(DataIntegrityViolationException.class, () -> database.update("DELETE FROM safety_upgrade_fixture.accounts WHERE id=?", admin));
            assertEquals(0, configuration.load().migrate().migrationsExecuted);
        } finally { database.execute("DROP SCHEMA IF EXISTS safety_upgrade_fixture CASCADE"); }
    }

    @Test void playSafetyGoogleErasureRemovesMappingAndOrphanUploadsFromReplacedDevices() throws Exception {
        Device peer = device("google_erase_peer");
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        when(verifier.enabled()).thenReturn(true);
        when(verifier.clientId()).thenReturn("synthetic-client");
        when(verifier.verify(anyString(), anyString())).thenReturn("synthetic-erasure-subject");
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        GoogleAuth google = new GoogleAuth(verifier, directory, authentication, redis, json, Clock.systemUTC());
        GoogleAuth.Challenge challenge = google.challenge(new GoogleAuth.Start(null));
        AuthService.Token first = google.signIn(new GoogleAuth.SignIn(challenge.id(), "synthetic-id-token".repeat(8))).session();
        SignalClient crypto = new SignalClient(first.userId(), new TestVault());
        UUID original = UUID.randomUUID();
        Device enrolling = new Device(first.userId(), null, first.accessToken(), crypto);
        AuthService.Token deviceSession = json.readValue(request(body(post("/devices"), new AccountDirectory.DeviceRequest(original, crypto.publicIdentity(), false)), enrolling)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), AuthService.Token.class);
        Device old = new Device(first.userId(), original, deviceSession.accessToken(), crypto);
        UUID media = UUID.randomUUID();
        request(put("/media/" + media).param("recipientId", peer.userId().toString()).param("recipientDeviceId", peer.deviceId().toString())
                .param("expiresAt", Long.toString(System.currentTimeMillis() + 60_000)).contentType("application/octet-stream").content(new byte[32]), old)
                .andExpect(status().isCreated());
        ProfileMessages.Send profile = new ProfileMessages.Send(UUID.randomUUID(), old.userId(), old.deviceId(),
                System.currentTimeMillis() + 60_000, 2, new byte[128]);
        request(body(post("/profile/packets"), profile), peer).andExpect(status().isNoContent());
        request(delete("/profile/packets/" + profile.id()), old).andExpect(status().isNoContent());
        request(post("/auth/logout"), old).andExpect(status().isNoContent());
        GoogleAuth.Challenge oldPending = google.challenge(new GoogleAuth.Start(original));
        challenge = google.challenge(new GoogleAuth.Start(null));
        AuthService.Token replace = google.signIn(new GoogleAuth.SignIn(challenge.id(), "synthetic-id-token".repeat(8))).session();
        UUID replacement = UUID.randomUUID();
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(replacement, crypto.publicIdentity(), true)), enrollment(old, replace))
                .andExpect(status().isCreated());
        challenge = google.challenge(new GoogleAuth.Start(null));
        AuthService.Token deletion = google.signIn(new GoogleAuth.SignIn(challenge.id(), "synthetic-id-token".repeat(8))).session();
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), enrollment(old, deletion)).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("b:" + media)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("pcr:" + original + ":" + profile.id())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(GoogleAuth.key(oldPending.id()))));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE google_subject='synthetic-erasure-subject'", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM devices WHERE user_id=?", Integer.class, first.userId()));
        assertNotEquals(first.userId(), directory.googleAccount("synthetic-erasure-subject").userId());
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(replacement, crypto.publicIdentity(), true)), enrollment(old, deletion))
                .andExpect(status().isUnauthorized());
    }

    @Test void playSafetyCleanupIsSerializedWithOtherRelayRequestsThroughPostgres() throws Exception {
        Device owner = device("gate_erase_owner"), peer = device("gate_erase_peer");
        Device confirming = enrollment(owner, recentLogin("gate_erase_owner", null));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                assertEquals(Boolean.TRUE, database.queryForObject("SELECT pg_try_advisory_xact_lock_shared(861904231)", Boolean.class));
                var attempt = executor.submit(() -> {
                    request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.error").value("safety_operation_in_progress"));
                    request(put("/account/blocks/" + peer.userId()), owner).andExpect(status().isServiceUnavailable());
                    return true;
                });
                try { assertTrue(attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new AssertionError("Concurrent safety request failed", failure); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Concurrent test interrupted", failure); }
                assertEquals("ACTIVE", database.queryForObject("SELECT deletion_state FROM accounts WHERE id=?", String.class, owner.userId()));
            });
        }
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isNoContent());
    }

    @Test void playSafetyFailedBlockCleanupStillDeniesAccessAndCanBeRetried() throws Exception {
        Device blocker = device("failed_blocker"), peer = device("failed_block_peer");
        String broken = "r:" + UUID.randomUUID();
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "redis.call('LPUSH', KEYS[1], 'test-only'); redis.call('EXPIRE', KEYS[1], 60); return 1", Long.class), List.of(broken));
        request(put("/account/blocks/" + peer.userId()), blocker).andExpect(status().isServiceUnavailable());
        request(get("/account/blocks"), blocker).andExpect(status().isOk()).andExpect(content().json("[\"" + peer.userId() + "\"]"));
        request(body(post("/messages"), opaqueSend(peer, blocker, null)), peer).andExpect(status().isNotFound());
        request(get("/account/blocks"), peer).andExpect(content().json("[]"));
        redis.delete(broken);
        request(put("/account/blocks/" + peer.userId()), blocker).andExpect(status().isNoContent());
        request(get("/auth/me"), blocker).andExpect(status().isOk());
    }

    @Test void playSafetyMediaOwnershipSurvivesDeviceIdentifierReuse() throws Exception {
        Device first = device("old_media_owner"), peer = device("media_peer"), second = device("new_media_owner");
        GroupDirectory.Snapshot group = sharedGroup(peer, first, second);
        GroupMessages.Send sharedCopy = new GroupMessages.Send(UUID.randomUUID(), group.epoch(), group.revision(), Expiry.VIEW_ONCE,
                System.currentTimeMillis() + 60_000, new byte[128], UUID.randomUUID(), new byte[32]);
        request(body(post("/groups/" + group.id() + "/messages"), sharedCopy), first).andExpect(status().isCreated());
        UUID media = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 60_000;
        request(put("/media/" + media).param("recipientId", peer.userId().toString()).param("recipientDeviceId", peer.deviceId().toString())
                .param("expiresAt", Long.toString(deadline)).contentType("application/octet-stream").content(new byte[32]), first).andExpect(status().isCreated());
        Device replacingFirst = enrollment(first, recentLogin("old_media_owner", null));
        request(body(post("/devices"), new AccountDirectory.DeviceRequest(UUID.randomUUID(), first.crypto().publicIdentity(), true)), replacingFirst).andExpect(status().isCreated());
        Device replacingSecond = enrollment(second, recentLogin("new_media_owner", null));
        AuthService.Token reused = json.readValue(request(body(post("/devices"), new AccountDirectory.DeviceRequest(first.deviceId(), second.crypto().publicIdentity(), true)), replacingSecond)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), AuthService.Token.class);
        Device intruder = new Device(second.userId(), first.deviceId(), reused.accessToken(), second.crypto());
        groups.remove(actor(peer), group.id(), group.revision(), second.userId());
        GroupDirectory.Snapshot removed = groups.list(actor(peer)).getFirst();
        groups.invite(actor(peer), group.id(), removed.revision(), List.of(contact(intruder)));
        groups.accept(actor(intruder), group.id(), removed.revision());
        request(put("/media/" + media).param("recipientId", peer.userId().toString()).param("recipientDeviceId", peer.deviceId().toString())
                .param("expiresAt", Long.toString(deadline)).contentType("application/octet-stream").content(new byte[32]), intruder).andExpect(status().isNotFound());
        SendRequest replay = new SendRequest(UUID.randomUUID(), peer.userId(), peer.deviceId(), Expiry.VIEW_ONCE, deadline, 2, new byte[128], media);
        request(body(post("/messages"), replay), intruder).andExpect(status().isNotFound());
        request(delete("/media/" + media), intruder).andExpect(status().isNotFound());
        assertTrue(Boolean.TRUE.equals(redis.hasKey("b:" + media)));
        Device erasing = enrollment(first, recentLogin("old_media_owner", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), erasing).andExpect(status().isNoContent());
        request(get("/auth/me"), intruder).andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("b:" + media)));
        request(get("/groups/" + group.id() + "/status"), intruder).andExpect(status().isOk()).andExpect(content().json("[]"));
        request(post("/groups/" + group.id() + "/messages/" + sharedCopy.id() + "/delete"), intruder).andExpect(status().isNotFound());
        request(get("/groups/" + group.id() + "/messages/" + sharedCopy.id() + "/media"), peer).andExpect(status().isOk());
    }

    @Test void playSafetyLegacyUnattributedUploadsDelayErasureWithoutDeletingOthersData() throws Exception {
        Device owner = device("legacy_erase_owner"), sender = device("legacy_upload_sender"), recipient = device("legacy_upload_recipient");
        UUID media = UUID.randomUUID();
        request(put("/media/" + media).param("recipientId", recipient.userId().toString()).param("recipientDeviceId", recipient.deviceId().toString())
                .param("expiresAt", Long.toString(System.currentTimeMillis() + 60_000)).contentType("application/octet-stream").content(new byte[32]), sender)
                .andExpect(status().isCreated());
        String key = "b:" + media;
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(redis.opsForValue().get(key));
        legacy.remove(List.of("senderId", "recipientId"));
        redis.opsForValue().set(key, json.writeValueAsString(legacy), Duration.ofSeconds(60));
        Device confirming = enrollment(owner, recentLogin("legacy_erase_owner", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("legacy_media_pending"));
        assertEquals("DELETING", database.queryForObject("SELECT deletion_state FROM accounts WHERE id=?", String.class, owner.userId()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
        request(get("/auth/me"), owner).andExpect(status().isUnauthorized());
        request(get("/auth/me"), sender).andExpect(status().isOk());
        redis.expire(key, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        request(body(delete("/account"), Map.of("confirmation", "DELETE")), confirming).andExpect(status().isNoContent());
    }

    private String deletionProof() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private DeletionReceipts.Status deletionStatus(String proof) throws Exception {
        return json.readValue(request(body(post("/account/deletion/status"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), DeletionReceipts.Status.class);
    }

    @Test void playSafetyDeletionProofConfirmsCommitAfterLostResponseAndRedisReset() throws Exception {
        Device owner = device("proof_lost_response"), other = device("proof_other_owner");
        String proof = deletionProof();
        Map<String, String> confirmation = Map.of("confirmation", "DELETE", "deletionProof", proof);
        request(body(delete("/account"), confirmation), owner).andExpect(status().isForbidden());
        request(body(post("/account/deletion/status"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("deletion_proof_unavailable"));
        Device enrolling = enrollment(owner, recentLogin("proof_lost_response", null));
        request(body(delete("/account"), confirmation), enrolling).andExpect(status().isNoContent());
        request(get("/auth/me"), owner).andExpect(status().isUnauthorized());
        request(body(delete("/account"), confirmation), enrolling).andExpect(status().isUnauthorized());
        DeletionReceipts.Status confirmed = deletionStatus(proof);
        assertEquals(owner.userId(), confirmed.userId());
        assertEquals(DeletionReceipts.State.DELETED, confirmed.state());
        assertTrue(confirmed.expiresAt() > System.currentTimeMillis());
        assertTrue(confirmed.expiresAt() <= System.currentTimeMillis() + DeletionReceipts.LIFETIME);
        Map<String, Object> stored = database.queryForMap("SELECT * FROM account_deletion_receipts WHERE user_id=?", owner.userId());
        assertEquals(Set.of("proof_digest", "user_id", "state", "created_at", "expires_at"), stored.keySet());
        assertEquals(DeletionReceipts.digest(proof), stored.get("proof_digest"));
        assertFalse(stored.toString().contains(proof));
        assertEquals(DeletionReceipts.LIFETIME, ((java.sql.Timestamp) stored.get("expires_at")).getTime()
                - ((java.sql.Timestamp) stored.get("created_at")).getTime());
        request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(owner.userId().toString()))
                .andExpect(jsonPath("$.state").value("DELETED")).andExpect(jsonPath("$.expiresAt").value(confirmed.expiresAt()));
        assertEquals(confirmed, deletionStatus(proof));
        Device proofIsNotAnAccessToken = new Device(owner.userId(), owner.deviceId(), proof, owner.crypto());
        request(get("/auth/me"), proofIsNotAnAccessToken).andExpect(status().isUnauthorized());
        request(body(post("/auth/refresh"), new AuthService.Refresh(proof, null)), null).andExpect(status().isUnauthorized());
        request(get("/auth/me"), other).andExpect(status().isOk());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?", Integer.class, owner.userId()));
        // The client retained its proof but lost the DELETE response; confirmation survives Redis loss.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        assertEquals(confirmed, deletionStatus(proof));
        request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DELETED"));
    }

    @Test void playSafetyDeletionProofRetriesRollbackWithoutReauthenticationOrCrossAccountAuthority() throws Exception {
        Device owner = device("proof_retry_owner"), other = device("proof_retry_other");
        assignAdmin(owner);
        String proof = deletionProof();
        Device enrolling = enrollment(owner, recentLogin("proof_retry_owner", null));
        database.execute("""
                CREATE FUNCTION test_deletion_receipt_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.state = 'DELETED' THEN RAISE EXCEPTION 'synthetic_commit_failure' USING ERRCODE = '23514'; END IF;
                    RETURN NEW;
                END; $$;
                CREATE CONSTRAINT TRIGGER test_deletion_receipt_commit AFTER UPDATE ON account_deletion_receipts
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_deletion_receipt_commit();
                """);
        try {
            request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", proof)), enrolling)
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("service_unavailable"));
        } finally {
            database.execute("DROP TRIGGER test_deletion_receipt_commit ON account_deletion_receipts");
            database.execute("DROP FUNCTION test_deletion_receipt_commit()");
        }
        DeletionReceipts.Status pending = deletionStatus(proof);
        assertEquals(DeletionReceipts.State.PENDING, pending.state());
        assertEquals(owner.userId(), pending.userId());
        assertEquals("DELETING", database.queryForObject("SELECT deletion_state FROM accounts WHERE id=?", String.class, owner.userId()));
        request(get("/auth/me"), owner).andExpect(status().isUnauthorized());
        request(get("/auth/me"), enrolling).andExpect(status().isUnauthorized());
        Device otherEnrollment = enrollment(other, recentLogin("proof_retry_other", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", proof)), otherEnrollment)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("deletion_proof_conflict"));
        assertEquals("ACTIVE", database.queryForObject("SELECT deletion_state FROM accounts WHERE id=?", String.class, other.userId()));
        Device reauthenticated = enrollment(owner, recentLogin("proof_retry_owner", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", deletionProof())), reauthenticated)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("deletion_proof_conflict"));
        assertEquals(pending, deletionStatus(proof));
        request(body(post("/account/deletion/retry"), Map.of("deletionProof", proof, "userId", other.userId())), null)
                .andExpect(status().isBadRequest());
        database.update("UPDATE accounts SET deletion_state='ACTIVE' WHERE id=?", owner.userId());
        request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("account_cleanup_failed"));
        assertEquals(pending, deletionStatus(proof));
        database.update("UPDATE accounts SET deletion_state='DELETING' WHERE id=?", owner.userId());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                assertEquals(Boolean.TRUE, database.queryForObject("SELECT pg_try_advisory_xact_lock_shared(861904231)", Boolean.class));
                var attempt = executor.submit(() -> {
                    request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("safety_operation_in_progress"));
                    return true;
                });
                try { assertTrue(attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new AssertionError("Concurrent retry failed", failure); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Concurrent retry interrupted", failure); }
            });
        }
        assertEquals(pending, deletionStatus(proof));
        request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DELETED"))
                .andExpect(jsonPath("$.userId").value(owner.userId().toString())).andExpect(jsonPath("$.expiresAt").value(pending.expiresAt()));
        assertEquals(DeletionReceipts.State.DELETED, deletionStatus(proof).state());
        request(get("/auth/me"), other).andExpect(status().isOk());
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", proof)), otherEnrollment)
                .andExpect(status().isConflict());
        assertEquals(owner.userId(), database.queryForObject("SELECT user_id FROM admin_identity", UUID.class));
        assertThrows(DataIntegrityViolationException.class, () -> database.update("UPDATE accounts SET user_type='ADMIN' WHERE id=?", other.userId()));
    }

    @Test void playSafetyDeletionProofExpiryUnknownValuesAndRateLimitsNeverImplySuccess() throws Exception {
        Device owner = device("proof_expiry_owner");
        String proof = deletionProof();
        for (String operation : List.of("status", "retry")) {
            request(body(post("/account/deletion/" + operation), new DeletionReceipts.Proof(proof)), null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("deletion_proof_unavailable"));
            request(body(post("/account/deletion/" + operation), new DeletionReceipts.Proof("invalid")), null).andExpect(status().isBadRequest());
            request(body(post("/account/deletion/" + operation), Map.of("deletionProof", proof, "userId", owner.userId())), null)
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_deletion_receipts", Integer.class));
        assertEquals("ACTIVE", database.queryForObject("SELECT deletion_state FROM accounts WHERE id=?", String.class, owner.userId()));
        Device enrolling = enrollment(owner, recentLogin("proof_expiry_owner", null));
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", "invalid")), enrolling).andExpect(status().isBadRequest());
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", proof)), enrolling).andExpect(status().isNoContent());
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
                "UPDATE account_deletion_receipts SET expires_at=created_at+interval '25 hours' WHERE user_id=?", owner.userId()));
        redis.delete("rate:deletion-status:" + DeletionReceipts.digest(proof));
        redis.delete("rate:deletion-retry:" + DeletionReceipts.digest(proof));
        for (int index = 0; index < 30; index++) assertEquals(DeletionReceipts.State.DELETED, deletionStatus(proof).state());
        request(body(post("/account/deletion/status"), new DeletionReceipts.Proof(proof)), null).andExpect(status().isTooManyRequests());
        for (int index = 0; index < 5; index++) request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DELETED"));
        request(body(post("/account/deletion/retry"), new DeletionReceipts.Proof(proof)), null).andExpect(status().isTooManyRequests());
        assertTrue(redis.getExpire("rate:deletion-status:" + DeletionReceipts.digest(proof)) > 0);
        assertTrue(redis.getExpire("rate:deletion-retry:" + DeletionReceipts.digest(proof)) <= 60);
        database.update("UPDATE account_deletion_receipts SET created_at=now()-interval '2 days', expires_at=now()-interval '1 day' WHERE user_id=?", owner.userId());
        redis.delete("rate:deletion-status:" + DeletionReceipts.digest(proof));
        redis.delete("rate:deletion-retry:" + DeletionReceipts.digest(proof));
        for (String operation : List.of("status", "retry")) request(body(post("/account/deletion/" + operation), new DeletionReceipts.Proof(proof)), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("deletion_proof_unavailable"));
        new DeletionReceipts(database).expire();
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM account_deletion_receipts", Integer.class));
        request(body(post("/account/deletion/status"), new DeletionReceipts.Proof(proof)), null).andExpect(status().isNotFound());
    }

    @Test void playSafetyGoogleReauthenticationForSavedAccountNeverRecreatesAnErasedIdentity() throws Exception {
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        when(verifier.enabled()).thenReturn(true);
        when(verifier.clientId()).thenReturn("synthetic-client");
        when(verifier.verify(anyString(), anyString())).thenReturn("synthetic-existing-only-subject");
        AccountDirectory directory = new AccountDirectory(database, json, Clock.systemUTC());
        GoogleAuth google = new GoogleAuth(verifier, directory, authentication, redis, json, Clock.systemUTC());
        GoogleAuth.Challenge initial = google.challenge(new GoogleAuth.Start(null));
        AuthService.Token original = google.signIn(new GoogleAuth.SignIn(initial.id(), "synthetic-id-token".repeat(8))).session();
        GoogleAuth.Challenge existing = google.challenge(new GoogleAuth.Start(null, original.userId()));
        AuthService.Token reauthenticated = google.signIn(new GoogleAuth.SignIn(existing.id(), "synthetic-id-token".repeat(8))).session();
        assertEquals(original.userId(), reauthenticated.userId());
        GoogleAuth.Challenge wrong = google.challenge(new GoogleAuth.Start(null, UUID.randomUUID()));
        ApiException mismatch = assertThrows(ApiException.class, () -> google.signIn(new GoogleAuth.SignIn(wrong.id(), "synthetic-id-token".repeat(8))));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, mismatch.status);
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
        GoogleAuth.Challenge outstanding = google.challenge(new GoogleAuth.Start(null, original.userId()));
        String proof = deletionProof();
        Device enrolling = new Device(original.userId(), null, reauthenticated.accessToken(), null);
        request(body(delete("/account"), Map.of("confirmation", "DELETE", "deletionProof", proof)), enrolling).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(GoogleAuth.key(outstanding.id()))));
        DeletionReceipts.Status confirmed = deletionStatus(proof);
        assertEquals(original.userId(), confirmed.userId());
        assertEquals(DeletionReceipts.State.DELETED, confirmed.state());
        GoogleAuth.Challenge erased = google.challenge(new GoogleAuth.Start(null, original.userId()));
        ApiException missing = assertThrows(ApiException.class, () -> google.signIn(new GoogleAuth.SignIn(erased.id(), "synthetic-id-token".repeat(8))));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, missing.status);
        assertEquals("authentication_failed", missing.getMessage());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM accounts WHERE google_subject='synthetic-existing-only-subject'", Integer.class));
        assertEquals(confirmed, deletionStatus(proof));
        GoogleAuth.Challenge explicitSignup = google.challenge(new GoogleAuth.Start(null));
        AuthService.Token newAccount = google.signIn(new GoogleAuth.SignIn(explicitSignup.id(), "synthetic-id-token".repeat(8))).session();
        assertNotEquals(original.userId(), newAccount.userId());
        GoogleAuth.Challenge oldPartition = google.challenge(new GoogleAuth.Start(null, original.userId()));
        assertThrows(ApiException.class, () -> google.signIn(new GoogleAuth.SignIn(oldPartition.id(), "synthetic-id-token".repeat(8))));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
        assertEquals(AccountDirectory.UserType.USER, directory.accountType(newAccount.userId()).userType());
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