# Threat model and security decisions

Status: implementation in progress; not an audited or production-certified messenger.

## Trust boundaries

Vanishr is an Android-first, one-device-per-account, one-to-one messenger. A separately
distributed, signed native client owns all private identity, session, and file keys.
The relay has no content-decryption API, private key, or client crypto dependency.
Do not serve executable client updates from the relay. No browser client is included:
a compromised origin could replace browser JavaScript and steal plaintext.

The endpoints, Android OS, libsignal native library, device random-number generator,
app signing/distribution system, and the user's identity-verification channel must
be trusted. TLS authenticates the relay; it does not authenticate a conversation peer.
Users must verify peer identity fingerprints over an independent authenticated channel
before either sending or decrypting messages. A changed identity blocks the conversation.

Username lookup is discovery only. The user independently compares the displayed
SHA-256 fingerprint of the canonical account UUID and public identity key, then
confirms verification. The client re-fetches the username binding before pinning
it and rejects changed account/device/key values. No pasted identity code or
automatic trust-on-first-use is required or permitted.

Optional Google sign-in authenticates the account, never the peer or encryption keys.
It is disabled until a Web OAuth client ID and allowed Android client IDs are configured.
Google-signed ID tokens are verified by the official Google library; issuer, audience,
authorized party, expiry, issued-at time and a one-use five-minute relay nonce are checked.
Only Google's stable subject identifier is retained, not email, name, photo, access token
or refresh token. Google accounts are not automatically linked to password accounts.
Google compromise or account takeover can replace a device but cannot recover old
private keys; contacts must independently verify the replacement identity. Google
learns that the user signs into this app, which is an additional metadata tradeoff.

The unique username and optional, explicitly saved display name are account
metadata visible to the relay and authenticated lookup clients. Only the
authenticated owning account can edit them; no target account ID is accepted in
an update body. Username uniqueness is enforced by PostgreSQL, including concurrent
claims. Usernames may be reused after renaming, so existing contacts and sessions
remain bound to immutable account UUIDs and pinned keys. Names are not identity
proofs. Google names, email addresses and photos are not imported automatically.

Private contact nicknames stay in the owner's encrypted local account partition
and are never sent to the relay or the contact. A shared profile refresh may update
the contact's current username and profile name, but never that private override
or the pinned device/key. Legacy local profile names stay local until explicitly
saved as a shared profile; no automatic publication occurs on upgrade.

The conversation menu's Profile opens only the selected contact's local display-name
editor with a non-editable username. My profile, reached from the Chats avatar, is
the separate owner-only account editor. There is no username update call in the
contact editor, and the server still derives update ownership from the session.

Send taps immediately show an in-memory Sending bubble, not a delivery receipt.
At most eight not-yet-stored sends are retained; each has an ID and absolute
deadline assigned at the tap. Retry keeps both unchanged. Peer verification,
Signal encryption and atomic protected outbox storage still precede upload.
The UI switches to the durable pending state after encrypted persistence without
waiting for the relay response. Failed preparation remains visibly Not sent with
retry/discard actions; it never overwrites a newer draft. Unstored sends are
cancelled and their UI payloads cleared on backgrounding, while existing encrypted
outbox records keep their normal retry and expiry behavior. No plaintext draft
cache, new logging or weaker identity check is introduced.

Vanishr has no separate app lock or repeated credential prompt. While Android
reports the phone unlocked, foreground startup opens the encrypted vault
automatically. New Keystore wrapping/content keys require an unlocked device,
not a five-minute authentication window; creating them requires a configured
phone screen lock. Backgrounding clears visible content and closes the in-memory
session. Anyone holding an already-unlocked phone can open Vanishr: this is an
explicit usability/security tradeoff, not a second authentication boundary.

Legacy wrapping and content records migrate to new keys using authenticated
decryption and encrypted atomic writes. Active and signed-out accounts retain
their identity, content state and original deadlines. Legacy keys are deleted
only after the replacement ciphertext commits. An old authentication-bound key
may require one normal phone PIN/pattern/password unlock before migration;
missing or invalidated keys fail closed and are not reset automatically.

Sign-out revokes its session, drops the locally
saved bearer token, and atomically moves its records into a separate encrypted
account partition before clearing them from memory. After server authentication,
only the matching account UUID, origin and provider can restore that
partition and its original device keys. The shared encrypted vault remains
bounded to 32 MiB and 4,000 records; it is never a plaintext cache or backup.

New content-key aliases include the owning account UUID so sender and recipient
copies of the same message on one phone cannot erase each other's keys. Older
single-account entries retain their original aliases for compatibility. Expiry
cleanup covers both active and signed-out account partitions, and content-key
expiry remains independent of login. Sign-out never resets deadlines or restores
consumed view-once content. Google credential-state clearing is coalesced and has
a bounded timeout; failed clearing must be retried before another Google login.
Pending enrollment never claims an active device ID. Offline sign-out cannot
prove immediate token revocation; server token and ciphertext TTLs still apply.
Local sign-out is not remote account deletion, physical flash erasure, or recovery
of keys erased by older versions, app-data clearing or uninstalling.

Update checks fetch at most 4 KiB of metadata from the fixed HTTPS static
download host, without account tokens, cookies, IDs or device identifiers.
The host still sees network metadata such as source IP and request timing.
Checks are limited to once per day automatically; My profile also offers a
manual check. Redirects, unexpected fields, incompatible versions and download
URLs outside the exact versioned host/path are rejected. Offline checks never
block chat. The prompt opens the browser; Android requires user installation
approval and verifies a compatible package signer. The manifest checksum is
published metadata, not a signature or an in-app verification of the downloaded
APK. A compromised host can suppress updates or serve misleading metadata;
the signing key and Android package manager remain the update trust boundary.
No downloaded scripts, DEX or other executable code replace the running client.

## Architecture chosen before implementation

- Java Spring Boot HTTPS relay; PostgreSQL holds accounts, editable username/display
  metadata, password verifiers, device identifiers and public prekeys only. No
  conversation or payload tables, private nicknames, or content-decryption keys.
- Official Signal libsignal on the client, not a hand-written key-agreement or ratchet.
- Random per-image authenticated-encryption keys are transported inside Signal messages.
- Memory-only Redis, with snapshots, AOF, replication/backups, and swap prohibited,
  holds bounded ciphertext and short-lived delivery/authentication metadata.
  Images are bounded and kept in this same ephemeral store for the MVP. This intentionally
  avoids persistent object-store versions and asynchronous lifecycle deletion.
- Every relay payload has a hard absolute expiration no later than 24 hours after
  acceptance; expiry starts at server acceptance, not delayed delivery. Client-authenticated
  timestamps independently limit local retention. Unattached uploads have a short TTL.
- Successful read deletes content immediately. Successful delivery means authenticated
  decryption and protected local persistence, not merely an HTTP download. Delivery/read
  status must not be treated as a cryptographic proof against a compromised server.
- Push and realtime events contain only a generic wake-up signal, never a sender name,
  message, image, media key, or access token. Offline ciphertext disappears at its deadline.
- No groups, calls, stories, reactions, searchable history, account recovery of keys,
  cloud content backups, analytics SDK, or remotely supplied executable UI.

## Threats and limitations

| Threat | Protection | Residual risk |
| --- | --- | --- |
| Database theft | Only account/profile metadata, password verifiers and public device material | Usernames, chosen shared display names, public keys and password guessing remain exposed; use strong passwords |
| Redis/blob theft | Only Signal ciphertext or authenticated encrypted images, each with TTL | Recipient/sender IDs, lengths, timing and delivery relationships are visible |
| Backend compromise | Previously verified identities, client encryption and signed distribution keep content keys off-server | Server can deny/reorder delivery, lie about receipts, retain ciphertext, alter public keys before verification, and collect metadata |
| Network attacker | HTTPS/WSS, certificate validation, no cleartext fallback, E2EE | Traffic analysis and denial of service remain possible |
| Stolen locked phone | Android Keystore-wrapped private state, phone-unlocked access checks, encrypted local records, disabled backups | No separate app lock on an already-unlocked phone; OS exploits, weak screen lock, rooted device and forensic recovery are not defeated |
| Stolen bearer token | Short expiry, hashed server-side token, device authorization, logout/replacement revocation | Thief can fetch/delete ciphertext, interfere with delivery and impersonate relay metadata until expiry; not decrypt or create valid peer content |
| Malicious recipient | Local expiry and screenshot flags reduce accidental retention | Recipient can modify client, copy plaintext, screenshot on unsupported devices or photograph a screen; disappearing content is not DRM |
| Replay/tampering | Signal ratchet replay detection and authentication, authenticated envelope IDs and deadlines, idempotent relay IDs | A server may still replay opaque delivery metadata; client rejects expired/duplicate envelopes |
| Identity substitution / MITM | Mandatory independent verification; pinned public identities; explicit reset on replacement | No key transparency service; verifying over this same relay is not verification |
| Push compromise | Generic notification, no content or credentials | Provider sees device token and timing; notification can be suppressed or forged |
| Accidental logging | No bodies, tokens, keys, user content, SQL parameters, access logs or crypto logging | Operators/APM/proxies can override configuration; deployment review is required |
| Expired data | Atomic expiry on every stored object; server TTL and client deadline checks; read deletion | Redis logical deletion does not prove physical RAM erasure; malicious operators, swap, dumps, snapshots and backups defeat retention promises |
| Device backups | Android backup/transfer disabled, no-backup directory, nonexportable vault key | OEM behavior and rooted-device tools need device testing; no iOS implementation yet |
| Supply-chain compromise | Pinned versions, signed client releases, separate release trust | Dependency pinning is not an audit; review advisories and verify release artifacts before production |

## Release gates

An independent security review, libsignal licensing review (AGPLv3; third-party use is
unsupported), dependency review, real-device secure-storage/backup/screenshot tests,
push-provider configuration, TLS provisioning, operational deletion verification,
and abuse/load testing are required before handling real private conversations.
No test can mathematically prove a malicious server will never keep ciphertext or
that arbitrary clients never upload plaintext; tests establish the trusted client's
encryption boundary and the shipped relay's behavior. Do not advertise stronger guarantees.