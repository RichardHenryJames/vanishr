package app.vanishr.android;

import app.vanishr.crypto.BackupCipher;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class AccountBackupTest {
    private final UUID owner = UUID.randomUUID();
    private static final String IDENTITY = Base64.getEncoder().encodeToString(new byte[33]);

    private static AccountBackup.Contact contact(String handle) {
        return new AccountBackup.Contact(UUID.randomUUID(), UUID.randomUUID(), IDENTITY, handle, "Name of " + handle, "Nick " + handle);
    }

    private static AccountBackup.Snapshot snapshot(List<AccountBackup.Contact> contacts) {
        return new AccountBackup.Snapshot(1, 1_700_000_000_000L, contacts);
    }

    private AccountBackup.Snapshot decode(String json) throws Exception {
        return AccountBackup.decode(json.getBytes(StandardCharsets.UTF_8), owner);
    }

    private void rejected(String json) {
        assertThrows(json, AccountBackup.Rejected.class, () -> decode(json));
    }

    private String json(String contact) { return "{\"version\":1,\"createdAt\":1,\"contacts\":[" + contact + "]}"; }

    private String valid() {
        return "{\"userId\":\"" + UUID.randomUUID() + "\",\"deviceId\":\"" + UUID.randomUUID() + "\",\"identityKey\":\"" + IDENTITY
                + "\",\"handle\":\"alice_1\",\"profileName\":\"Alice\",\"nickname\":\"Al\"}";
    }

    @Test public void snapshotsRoundTripAndNeverExposeContactsInStringForm() throws Exception {
        List<AccountBackup.Contact> contacts = List.of(contact("alice"), contact("bob"));
        AccountBackup.Snapshot original = snapshot(contacts);
        AccountBackup.Snapshot decoded = AccountBackup.decode(AccountBackup.encode(original), owner);
        assertEquals(original, decoded);
        assertEquals("Snapshot[redacted]", original.toString());
        assertEquals("Contact[redacted]", contacts.get(0).toString());
        assertFalse(contacts.toString().contains("alice"));
    }

    @Test public void optionalFieldsMayBeAbsent() throws Exception {
        AccountBackup.Contact bare = new AccountBackup.Contact(UUID.randomUUID(), UUID.randomUUID(), IDENTITY, "carol-3", null, null);
        AccountBackup.Snapshot decoded = AccountBackup.decode(AccountBackup.encode(snapshot(List.of(bare))), owner);
        assertEquals(bare, decoded.contacts().get(0));
        assertNull(decoded.contacts().get(0).nickname());
    }

    @Test public void anythingTheAppWouldNotHaveWrittenIsRejected() {
        rejected("");
        rejected("not json");
        rejected("[]");
        rejected("{}");
        rejected("{\"version\":1,\"createdAt\":1}");
        rejected("{\"version\":0,\"createdAt\":1,\"contacts\":[]}");
        rejected("{\"version\":2,\"createdAt\":1,\"contacts\":[]}");
        rejected("{\"version\":1,\"createdAt\":1,\"contacts\":[null]}");
        rejected("{\"version\":1,\"createdAt\":1,\"contacts\":[]");
        String id = UUID.randomUUID().toString(), device = UUID.randomUUID().toString();
        String prefix = "{\"userId\":\"" + id + "\",\"deviceId\":\"" + device + "\",";
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"UPPER\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"ab\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY.substring(1) + "\",\"handle\":\"dave\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY.substring(1) + "!\",\"handle\":\"dave\"}"));
        rejected(json(prefix + "\"handle\":\"dave\"}"));
        rejected(json("{\"deviceId\":\"" + device + "\",\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\"}"));
        rejected(json("{\"userId\":\"" + id + "\",\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\"}"));
        rejected(json("{\"userId\":\"not-a-uuid\",\"deviceId\":\"" + device + "\",\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"profileName\":\"\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"profileName\":\"" + "x".repeat(41) + "\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"profileName\":\"bad\\nname\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"nickname\":\"\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"nickname\":\" padded \"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"nickname\":\"" + "y".repeat(41) + "\"}"));
        rejected(json(prefix + "\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"dave\",\"nickname\":\"tab\\there\"}"));
    }

    @Test public void duplicatesTheOwnAccountAndOversizedListsAreRejected() throws Exception {
        String one = valid();
        rejected(json(one + "," + one));
        rejected(json("{\"userId\":\"" + owner + "\",\"deviceId\":\"" + UUID.randomUUID() + "\",\"identityKey\":\"" + IDENTITY + "\",\"handle\":\"me_myself\"}"));
        List<AccountBackup.Contact> contacts = new ArrayList<>();
        for (int index = 0; index < AccountBackup.MAX_CONTACTS; index++) contacts.add(contact("friend-" + index));
        assertEquals(AccountBackup.MAX_CONTACTS, AccountBackup.decode(AccountBackup.encode(snapshot(contacts)), owner).contacts().size());
        contacts.add(contact("one-too-many"));
        assertThrows(AccountBackup.Rejected.class, () -> AccountBackup.decode(AccountBackup.encode(snapshot(contacts)), owner));
    }

    @Test public void theLargestAllowedSnapshotFitsTheRelayLimitAfterEncryption() throws Exception {
        List<AccountBackup.Contact> contacts = new ArrayList<>();
        for (int index = 0; index < AccountBackup.MAX_CONTACTS; index++)
            contacts.add(new AccountBackup.Contact(UUID.randomUUID(), UUID.randomUUID(), IDENTITY, "a".repeat(32), "p".repeat(40), "n".repeat(40)));
        byte[] json = AccountBackup.encode(snapshot(contacts));
        assertTrue(json.length < 100_000);
        byte[] key = BackupCipher.newRecoveryKey();
        byte[] sealed = BackupCipher.seal(key, owner, json);
        assertTrue(sealed.length < BackupCipher.MAX_BLOB_BYTES);
        assertEquals(200, AccountBackup.decode(BackupCipher.open(key, owner, sealed), owner).contacts().size());
    }

    @Test public void aBackupOnlyOpensForItsOwnAccountAndKey() throws Exception {
        byte[] key = BackupCipher.newRecoveryKey();
        byte[] sealed = BackupCipher.seal(key, owner, AccountBackup.encode(snapshot(List.of(contact("erin")))));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, UUID.randomUUID(), sealed));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(BackupCipher.newRecoveryKey(), owner, sealed));
        assertEquals("erin", AccountBackup.decode(BackupCipher.open(key, owner, sealed), owner).contacts().get(0).handle());
    }

    @Test public void nicknamesFollowTheSameRulesAsLocalContactNames() {
        assertTrue(AccountBackup.validNickname("Mum"));
        assertTrue(AccountBackup.validNickname("x".repeat(40)));
        assertFalse(AccountBackup.validNickname(null));
        assertFalse(AccountBackup.validNickname(""));
        assertFalse(AccountBackup.validNickname(" Mum"));
        assertFalse(AccountBackup.validNickname("x".repeat(41)));
        assertFalse(AccountBackup.validNickname("a\u0007b"));
    }
}
