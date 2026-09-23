# Encryption and key management

## 1. Device identity generation

`SignalClient` invokes official `IdentityKeyPair.generate()` on the device.
libsignal supplies identity key generation and its native cryptographic RNG;
the app does not implement curve arithmetic or random seeding. The libsignal
registration identifier is generated using `KeyHelper.generateRegistrationId`.
No private key is uploaded, logged, placed in intents, or backed up.

Android Keystore cannot directly run all libsignal identity/Kyber/ratchet
operations. The app therefore wraps serialized private protocol state using a
nonexportable Keystore AES-256-GCM key. Unwrapped libsignal keys exist in client
memory while unlocked. This is not a claim that the Signal private key itself
never leaves secure hardware. StrongBox is requested where available, with
platform Keystore fallback; devices without a screen lock are rejected.

## 2. Public-key exchange and identity authentication

Device registration sends the 33-byte public identity only. Public prekey batches
contain registration ID, one-time EC prekey, signed EC prekey/signature, identity
public key, and Kyber-1024 public prekey/signature. PostgreSQL holds public data
only and consumes a bundle with one atomic `DELETE ... RETURNING` transaction.

Users authenticate full `user UUID:base64 public identity` codes independently,
for example in person or over an already authenticated channel. The contact UI
requires explicit verification and compares that code with the relay result.
Both sender and receiver pin before encrypting/decrypting. The protocol-store
trust callback rejects missing or changed identities in both directions.
Sharing a code through an unauthenticated channel is not verification. There
is no transparency log or protection from a user approving an attacker code.

## 3. Session establishment

The client passes the claimed bundle to libsignal `SessionBuilder.process`.
The selected release implements its supported Signal handshake including the
Kyber prekey input (PQXDH). Signature validation and key agreement happen inside
libsignal, not application code. `SessionCipher` handles subsequent encrypted
messages and replies. The API's address ordering is verified in runtime tests.

One-time EC and Kyber private prekeys are consumed on successful initial
decryption. There is no last-resort reusable Kyber fallback. Exhausted prekeys
block a new session until the recipient replenishes them. Test fixtures use
ephemeral in-memory stores only; the Android production path uses the vault.

## 4. Message-key derivation

Root, chain, ratchet, skipped-message and message keys are derived and managed
entirely by libsignal's protocol implementation. The app neither derives these
keys itself nor treats a single shared AES key as an E2EE chat session. Signal's
ratchet provides forward secrecy under its normal assumptions; runtime tests
check replay and authentication failures, not a new proof of the protocol.

The exact algorithms and evolving post-quantum behavior are tied to the pinned
libsignal release, not a hand-maintained description promising properties of
every Signal deployment. The Signal protocol is authenticated encryption, but
do not describe its message format as our image AES-GCM format.

## 5. Encryption and decryption

Text and attachment descriptors are serialized into a versioned inner envelope
and passed to `SessionCipher.encrypt` before the relay request is constructed.
The outer request contains routing identifiers, type, deadline and ciphertext.
The recipient selects only Signal message types 2/3, decrypts with libsignal,
then verifies all authenticated context fields and deadlines before displaying.
Tampered, replayed, expired, unknown-identity or wrong-context data fails closed.

Each image is normalized in memory to JPEG (maximum 2 MiB); the app does not
write a plaintext image cache. Imported EXIF/location metadata is dropped by
decode/re-encode. A random 256-bit per-file key and 96-bit nonce are generated
using JCA `KeyGenerator`/`SecureRandom`, and the image is encrypted with the
platform `AES/GCM/NoPadding` implementation and a 128-bit tag. Associated data
is `vanishr/image/v1/<random media UUID>`. This is the standard file encryption
primitive, not a custom session protocol. The key, nonce and MIME type travel
only in the Signal-authenticated encrypted envelope, never in upload metadata.

## 6. Rotation and local storage

Signal session keys ratchet as libsignal specifies. Prekeys are published in
batches of up to 32 and expire on the relay after 24 hours. Direct-only clients
target 16 keys and check on login/hourly sync. Group clients target 224 within
the server's 256-key bound and replenish incrementally every 30 seconds while
foregrounded, so large-group enrollment can take several sync cycles. Locally,
obsolete signed/private prekeys
are pruned after 48 hours, covering the maximum publication plus delivery
window. The long-term identity is stable until explicit device replacement.

Vault mutations, ratchet changes, outboxes and acknowledgement queues commit
using an encrypted `AtomicFile` in Android's no-backup directory. A failed
transaction restores the prior logical state. No logs, Java serialization,
plaintext database, device backup or token preferences are used for secrets.
Individual content records are additionally encrypted with separate Keystore
keys. Expiry/read deletes that key before deleting the encrypted record, so an
older vault-file copy does not retain the local content key. Deletion of flash
blocks, secure-element internals and earlier ratchet snapshots is not proven.

New vault and content keys use `setUserAuthenticationRequired(false)` and
set `setUnlockedDeviceRequired(true)` only when generated on Android 15 or later.
Android 14 and below use the explicitly approved compatibility policy: no extra
Keystore unlock restriction, avoiding Android 12-14 weak-biometric/profile bugs.
Every storage access requires `KeyguardManager.isDeviceSecure()` and rejects
`isDeviceLocked()`. On older Android this lock boundary is app-enforced, not an
independent Keystore authorization. Keys remain nonexportable AES-256-GCM with
random IVs and authenticated records. Existing keys keep their creation policy
after OS upgrades. There is no separate app credential prompt or authentication
timer. Android-accepted face/fingerprint unlock can open storage; the system may
still demand a device credential after reboot or biometric lockout. UI content
clears on pause and callbacks remain bound to the foreground screen generation.

Local protected-record version 3 uses `.phone.v3` key aliases; AES-GCM AAD remains
the logical alias. Version 1 (original alias) and version 2 (`.phone`) are read only
for migration. The vault root is re-encrypted atomically; active/parked content
records migrate in a vault transaction without viewing, consuming or resetting
expiry. Old keys are removed only after their replacement ciphertext commits.
Failed migration preserves the affected ciphertext and old key. A blocked old
key may require one recent phone PIN/pattern/password unlock before migration;
missing or invalidated legacy keys cannot be recovered by changing key policy.
The app never deletes and recreates an old key just to bypass its authorization.

View-once content keys are deleted before rendering; a crash at that point
loses the content intentionally. An outgoing view-once local copy is erased
after relay acceptance. Expired visible rows are removed on a 250 ms UI tick.
WorkManager also erases expired content keys, including while the vault is
closed; Android scheduling is not exact, so delayed erasure is possible while
powered off or restricted. Every access independently enforces the deadline.

## 7. Lost or replaced devices

Sign in using the account password and explicitly replace the previous device.
The new device creates a new identity and has no previous chat keys/content.
Device generation changes revoke old bearer sessions even if an identifier is
reused. Outstanding old-device delivery is inaccessible to the new identity
and expires normally. Contacts must remove the old pin/session and independently
verify the new identity. This does not recover messages or erase a lost phone.
No password-reset or private-key recovery service is implemented.

## Group encryption

SignalGroup delegates to the official libsignal GroupSessionBuilder and GroupCipher
sender-key APIs. Each sender has independent signing/chain state per group epoch.
SenderKeyDistributionMessage bytes travel only inside authenticated pairwise
Signal controls. GroupRoster uses an unambiguous, sorted binary representation
and SHA-256 digest to bind every account/device/public key; the digest is carried
inside the owner's encrypted Signal message, not trusted on its own from the relay.
GroupEnvelope binds group, epoch, revision, sender, message ID, media and absolute
expiry. No custom cipher, handshake or signature primitive replaces libsignal.

Membership changes create a fresh epoch with fresh sender keys. Old members cannot
decrypt the new epoch without its distributions; new members receive no earlier
epoch distributions. Old epoch state remains only for the bounded delivery window.
Current distribution state can be refreshed through new authenticated controls;
replayed packets do not reset ratchets. Content-key deletion and view-once rules
remain per local member copy. Images retain independent AES-GCM keys and one
shared encrypted blob; each recipient's access is revoked independently.

This uses a verified-owner group trust model, not all-pairs independent identity
verification or Signal's complete private-group service. Membership is visible
to the relay and owner compromise is a trust-boundary compromise. Direct chats
still require independently verified saved contacts; group-approved identities
alone cannot silently start a trusted direct conversation.

## Dependencies and review

Official library: https://github.com/signalapp/libsignal (AGPLv3; external use
unsupported). Protocol references: https://signal.org/docs/ . Pinning and using
an established implementation does not audit this integration. Obtain a legal
review and an independent security review before distribution or real use.