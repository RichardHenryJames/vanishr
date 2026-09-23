package app.vanishr.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.*;

public record GroupRoster(UUID groupId, UUID ownerId, UUID epoch, long revision, List<Member> members) {
    public record Member(UUID userId, UUID deviceId, String identityKey) { }

    public GroupRoster {
        if (groupId == null || ownerId == null || epoch == null || revision < 1 || members == null || members.isEmpty() || members.size() > 200)
            throw new SecurityException("Invalid group membership");
        Set<UUID> users=new HashSet<>(); Set<UUID> devices=new HashSet<>();
        for (Member member : members) {
            if (member == null || member.userId()==null || member.deviceId()==null || member.identityKey()==null
                    || !users.add(member.userId()) || !devices.add(member.deviceId())) throw new SecurityException("Duplicate or missing group identity");
            byte[] key;
            try { key=Base64.getDecoder().decode(member.identityKey()); }
            catch (IllegalArgumentException failure) { throw new SecurityException("Invalid group identity"); }
            if (key.length!=33 || !Base64.getEncoder().encodeToString(key).equals(member.identityKey())) throw new SecurityException("Invalid group identity");
        }
        if (!users.contains(ownerId)) throw new SecurityException("Group owner is missing");
        members=members.stream().sorted(Comparator.comparing(member -> member.userId().toString())).toList();
    }

    public Member member(UUID userId) {
        return members.stream().filter(member -> member.userId().equals(userId)).findFirst()
                .orElseThrow(() -> new SecurityException("Sender is not an approved member"));
    }

    public String digest() {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (DataOutputStream output=new DataOutputStream(bytes)) {
                output.writeUTF("vanishr/group-roster/v1"); output.writeUTF(groupId.toString()); output.writeUTF(ownerId.toString());
                output.writeUTF(epoch.toString()); output.writeLong(revision); output.writeInt(members.size());
                for (Member member : members) {
                    output.writeUTF(member.userId().toString()); output.writeUTF(member.deviceId().toString());
                    output.write(Base64.getDecoder().decode(member.identityKey()));
                }
            }
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Group membership digest unavailable"); }
    }
}