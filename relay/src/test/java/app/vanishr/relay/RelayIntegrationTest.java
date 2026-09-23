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
    @Autowired GroupDirectory groups;
    @Autowired GroupMessages groupMessages;
    @Autowired GenericNotifier notifications;
    @Autowired RealtimeHub realtime;

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

        private Presence.Peer presencePeer(Device device) {
        return new Presence.Peer(device.userId(), device.deviceId(), Base64.getEncoder().encodeToString(device.crypto().publicIdentity()));
        }

        private org.springframework.web.socket.WebSocketSession foreground(Device device) throws Exception {
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("sessionKey", AuthService.tokenKey(device.token())));
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
        String next = notifications.reference(new GenericNotifier.Destination(recipient.userId(), recipient.deviceId(), UUID.randomUUID(), UUID.randomUUID(),
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
            long limit = key.startsWith("refresh:") ? Duration.ofDays(30).toSeconds() : 86400;
            assertTrue(ttl >= 0 && ttl <= limit, "Ephemeral key must have bounded expiry");
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
        assertEquals(List.of("accounts", "devices", "flyway_schema_history", "group_members", "prekeys", "private_groups"), tables);
        assertEquals(List.of("id","owner_id","revision","epoch","closed_at"),database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='private_groups' ORDER BY ordinal_position",String.class));
        assertEquals(List.of("group_id","user_id","device_id","identity_key","state","invited_until"),database.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='group_members' ORDER BY ordinal_position",String.class));
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