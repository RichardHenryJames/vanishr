package app.vanishr.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.vanishr.relay.EphemeralRecords.id;

@Service
public class EphemeralCleanup {
    private record GroupDevice(UUID group, UUID device) { }
    private final EphemeralRecords records;
    private final StringRedisTemplate redis;
    private final JdbcTemplate database;
    private final AccountDirectory accounts;
    private final RemotePhotos photos;

    public EphemeralCleanup(EphemeralRecords records, StringRedisTemplate redis, JdbcTemplate database,
                            AccountDirectory accounts, RemotePhotos photos) {
        this.records = records; this.redis = redis; this.database = database; this.accounts = accounts; this.photos = photos;
    }

    private boolean pair(UUID sender, UUID recipient, UUID user, UUID peer) {
        return peer == null ? user.equals(sender) || user.equals(recipient)
                : user.equals(sender) && peer.equals(recipient) || peer.equals(sender) && user.equals(recipient);
    }

    private boolean pair(JsonNode value, UUID user, UUID peer) {
        return pair(id(value, "senderId"), id(value, "recipientId"), user, peer);
    }

    private void add(Set<UUID> devices, JsonNode value, String field) {
        UUID device = id(value, field);
        if (device != null) devices.add(device);
    }

    private Set<UUID> devices(UUID user) {
        Set<UUID> result = new HashSet<>(database.queryForList("""
                SELECT id FROM devices WHERE user_id = ? UNION SELECT device_id FROM group_members WHERE user_id = ?
                """, UUID.class, user, user));
        for (String prefix : List.of("auth:", "refresh:")) records.visit(prefix, (key, value) -> {
            JsonNode actor = value.path("actor");
            if (user.equals(id(actor, "userId"))) add(result, actor, "deviceId");
        });
        for (String prefix : List.of("r:", "m:", "pc:", "pcr:", "gc:", "gcr:", "gm:", "gr:", "b:")) records.visit(prefix, (key, value) -> {
            if (user.equals(id(value, "senderId"))) add(result, value, "senderDeviceId");
            if (user.equals(id(value, "recipientId"))) add(result, value, "recipientDeviceId");
        });
        for (String prefix : List.of("presence:", "last-seen:")) records.visit(prefix, (key, value) -> {
            JsonNode owner = value.path("owner");
            if (user.equals(id(owner, "userId"))) add(result, owner, "deviceId");
        });
        records.visit("rps:", (key, value) -> {
            for (String participant : List.of("owner", "requester")) {
                JsonNode member = value.path("session").path(participant);
                if (user.equals(id(member, "userId"))) add(result, member, "deviceId");
            }
        });
        result.removeIf(device -> accounts.deviceOwner(device).filter(owner -> !owner.equals(user)).isPresent());
        return result;
    }

    public void direct(UUID user, UUID peer) {
        direct(user, peer, devices(user), peer == null ? Set.of() : devices(peer));
    }

    private void direct(UUID user, UUID peer, Set<UUID> devices, Set<UUID> peerDevices) {
        for (String prefix : List.of("r:", "m:")) records.visit(prefix, (key, value) -> {
            if (!pair(value, user, peer)) return;
            UUID message = id(value, "id");
            UUID media = id(value, "mediaId");
            redis.delete("m:" + message);
            if (peer == null) redis.delete("r:" + message);
            else if (key.startsWith("r:")) {
                ObjectNode consumed = value.deepCopy();
                consumed.put("state", "DELETED");
                records.replace(key, value, consumed);
            }
            if (media != null) redis.delete("b:" + media);
            redis.opsForZSet().remove("inbox:" + id(value, "recipientDeviceId"), message.toString());
            redis.opsForZSet().remove("outbox:" + id(value, "senderDeviceId"), message.toString());
        });
        records.visit("b:", (key, value) -> {
            boolean legacy = id(value, "senderId") == null && id(value, "recipientId") == null;
            UUID sender = id(value, "senderDeviceId"), recipient = id(value, "recipientDeviceId");
            boolean legacyMatch = peer == null ? devices.contains(sender) || devices.contains(recipient)
                    : devices.contains(sender) && peerDevices.contains(recipient) || peerDevices.contains(sender) && devices.contains(recipient);
            if (pair(value, user, peer) || legacy && legacyMatch) { redis.delete(key); return; }
            if (legacy && peer == null) {
                UUID message = id(value, "messageId");
                JsonNode receipt = message == null ? null : records.get("r:" + message);
                if (receipt == null || id(receipt, "senderId") == null || id(receipt, "recipientId") == null)
                    throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "legacy_media_pending");
                if (pair(receipt, user, null)) redis.delete(key);
            }
        });
        for (String prefix : List.of("pc:", "gc:")) records.visit(prefix, (key, value) -> {
            if (!pair(value, user, peer)) return;
            String namespace = prefix.equals("pc:") ? id(value, "recipientDeviceId").toString() : id(value, "groupId").toString();
            String type = prefix.equals("pc:") ? "p" : "g";
            redis.delete(key);
            if (peer == null) redis.delete(type + "cr:" + namespace + ":" + id(value, "id"));
            String index = type + "ci:" + namespace + (type.equals("g") ? ":" + id(value, "recipientDeviceId") : "");
            redis.opsForZSet().remove(index, id(value, "id").toString());
        });
        records.visit("rps:", (key, value) -> {
            JsonNode session = value.path("session"), requester = session.path("requester"), owner = session.path("owner");
            if (pair(id(requester, "userId"), id(owner, "userId"), user, peer))
                photos.stop(new RelayTypes.Actor(id(requester, "userId"), id(requester, "deviceId")), id(session, "id"));
        });
        for (String prefix : List.of("presence:", "last-seen:")) records.visit(prefix, (key, value) -> {
            UUID owner = id(value.path("owner"), "userId");
            if (peer == null && user.equals(owner)) { redis.delete(key); return; }
            ObjectNode next = value.deepCopy();
            ArrayNode contacts = next.putArray("contacts");
            for (JsonNode contact : value.path("contacts")) {
                if (!pair(owner, id(contact, "userId"), user, peer)) contacts.add(contact);
            }
            if (pair(owner, id(value, "typingTo"), user, peer)) { next.putNull("typingTo"); next.put("typingUntil", 0); }
            if (!next.equals(value)) records.replace(key, value, next);
        });
        records.visit("notification:", (key, value) -> {
            JsonNode route = value.path("destination");
            if (pair(id(route, "userId"), id(route, "conversationId"), user, peer)) redis.delete(key);
        });
    }

    public void revokeGroupRecipient(UUID group, UUID device) {
        records.visit("gc:" + group + ":", (key, value) -> {
            if (device.equals(id(value, "recipientDeviceId"))) {
                redis.delete(List.of(key, "gcr:" + group + ":" + id(value, "id")));
            }
        });
        redis.delete("gci:" + group + ":" + device);
        records.visit("gr:" + group + ":", (key, value) -> {
            if (!value.path("recipients").has(device.toString())) return;
            ObjectNode next = value.deepCopy();
            ObjectNode recipients = (ObjectNode) next.path("recipients");
            recipients.remove(device.toString());
            boolean outstanding = false;
            for (JsonNode state : recipients) {
                if (state.asText().equals("QUEUED") || value.path("expiry").asText().equals("VIEW_ONCE") && state.asText().equals("DELIVERED"))
                    outstanding = true;
            }
            if (!outstanding) redis.delete(List.of("gm:" + group + ":" + id(value, "id"), "gb:" + group + ":" + id(value, "id")));
            records.replace(key, value, next);
        });
    }

    public void account(UUID user, Set<UUID> ownedGroups, boolean pinnedAdmin, String handle) {
        Set<UUID> devices = devices(user);
        direct(user, null, devices, Set.of());
        for (UUID group : ownedGroups) {
            for (String type : List.of("gm", "gr", "gb", "gc", "gcr", "gci"))
                records.keys(type + ":" + group + ":", redis::delete);
            redis.delete(List.of("gi:" + group, "rate:group-send:" + group, "rate:group-media:" + group));
        }
        Set<GroupDevice> recipients = new HashSet<>();
        records.visit("gr:", (key, value) -> {
            UUID group = id(value, "groupId");
            for (UUID device : devices) {
                if (value.path("recipients").has(device.toString())) recipients.add(new GroupDevice(group, device));
            }
        });
        recipients.forEach(recipient -> revokeGroupRecipient(recipient.group(), recipient.device()));
        for (String prefix : List.of("auth:", "refresh:")) records.visit(prefix, (key, value) -> {
            if (user.equals(id(value.path("actor"), "userId"))) {
                redis.delete(key);
                if (key.startsWith("refresh:")) redis.delete("rate:renew:" + key.substring("refresh:".length()));
            }
        });
        records.visit("google:", (key, value) -> {
            if (devices.contains(id(value, "deviceId")) || user.equals(id(value, "expectedUserId"))) redis.delete(key);
        });
        records.visit("pcr:", (key, value) -> {
            if (pair(value, user, null) || devices.contains(id(value, "recipient"))) redis.delete(key);
        });
        records.visit("gcr:", (key, value) -> {
            if (pair(value, user, null) || devices.contains(id(value, "recipient"))) redis.delete(key);
        });
        for (UUID device : devices) {
            for (String type : List.of("push", "notification", "presence", "last-seen", "inbox", "outbox", "pci", "rpsi"))
                redis.delete(type + ":" + device);
            records.keys("gci:", key -> { if (key.endsWith(":" + device)) redis.delete(key); });
        }
        records.visit("notification:", (key, value) -> {
            if (ownedGroups.contains(id(value.path("destination"), "conversationId"))) redis.delete(key);
        });
        records.visit("safety-report:", (key, value) -> {
            if (pinnedAdmin || user.equals(id(value, "reporterId")) || user.equals(id(value, "targetId"))) {
                redis.delete(key);
                redis.opsForZSet().remove("safety-reports", id(value, "id").toString());
            }
        });
        if (pinnedAdmin) redis.delete("safety-reports");
        records.keys("rate:", key -> {
            if (key.endsWith(":" + user) || devices.stream().anyMatch(device -> key.endsWith(":" + device))) redis.delete(key);
        });
        redis.delete("rate:login:" + RedisRelay.digest(handle.getBytes(StandardCharsets.US_ASCII)));
    }
}
