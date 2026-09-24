# Threat model and security decisions

Status: implementation in progress; not an audited or production-certified messenger.

## Trust boundaries

Vanishr is an Android-first, one-device-per-account messenger with direct chats and
invite-only private groups of up to 200 members including the owner. A separately
distributed, signed native client owns all private identity, session, and file keys.
The relay has no content-decryption API, private key, or client crypto dependency.
Do not serve executable client updates from the relay. No browser client is included:
a compromised origin could replace browser JavaScript and steal plaintext.

The separate public information/download website is not a chat client and has no
account login, content viewer, relay credentials or client private keys. Its
explicitly approved website-only Google Analytics loads only after visitor
consent, never in the Android app. It measures public page visits and allowlisted
download clicks, strips URL queries/fragments, disables advertising and honors
GPC/Do Not Track. Revocation stops collection and clears its analytics cookies.
Hosting still exposes network/request metadata; opted-in analytics adds Google's
website-measurement processing. This is not anonymity or a production security
claim. Public Google measurement/ownership IDs are not secrets. Only the audited
static distribution may be uploaded, preserving the signed app and update feed.

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

Device access tokens last one hour. Enrolled Google and password accounts also
receive a separate 256-bit renewal credential, stored only in the phone-unlocked
encrypted vault and as a digest-keyed record on the relay. Successful renewal
atomically replaces both tokens with a fresh one-hour access token and a renewal
deadline bounded to 30 days. The old access/renewal pair is retired, and every
renewal verifies the current account/device generation. Enrollment tokens cannot
renew. Token TTLs do not affect message/photo deadlines.

The phone commits a random replacement renewal handle before requesting rotation.
After a lost response, it may try that prepared handle once to recover the rotated
session; it never saves a password or Google ID token for this purpose. Pending
renewal state has the existing session deadline and is cleared on success,
rejection, explicit sign-out or expiry. Offline/network failure preserves the
encrypted remembered account; a rejected renewal fails closed without erasing
content. Backgrounding still clears UI/plaintext while ordinary reopening goes
directly to the retained account. Sign-out revokes the access/renewal pair and
device replacement invalidates both through generation checks. A stolen renewal
credential can authorize access until expiry/revocation; it does not decrypt
Signal content. Redis remains nonpersistent: a relay Redis reset or 30 days
without successful renewal requires sign-in again. Existing sessions may need
one sign-in after upgrading to obtain a renewal credential.

Expired or rejected remembered sessions clear saved credentials and their
websocket, not account keys, contacts or retained content. Google reauthentication
clears provider credential state and requests a fresh, account-only nonce without
binding the old device ID. After Google verification, the returned account UUID
must match the retained account; the client then enrolls the same device UUID and
public key using a short-lived enrollment token. Matching registration is
idempotent; a different registered device still needs explicit replacement.
The returned device session must match that account/device. Failed or interrupted
credential resets remain retryable without signing out. Enrollment lifetimes,
one-use nonce checks and message deadlines are not extended by recovery.

The unique username and optional, explicitly saved display name are account
metadata visible to the relay and authenticated lookup clients. Only the
authenticated owning account can edit them; no target account ID is accepted in
an update body. Username uniqueness is enforced by PostgreSQL, including concurrent
claims. Usernames may be reused after renaming, so existing contacts and sessions
remain bound to immutable account UUIDs and pinned keys. Names are not identity
proofs. Google names, email addresses and photos are not imported automatically.

Account type is server-controlled metadata attached to the immutable account UUID.
The PostgreSQL `accounts.user_type` column permits only `USER` or `ADMIN`, defaults
to `USER` for existing and new accounts, and is not accepted by any client write
API. An enrolled account may read its own current type via `GET /account/type`;
contact lookup/profile responses do not disclose it. Only an authenticated database
operator may change a type, targeting a previously inspected UUID and expected
current handle in a bounded transaction, never an automatic username rule.
Renaming or reusing a former handle cannot transfer the role. `ADMIN` permits
requesting Remote Photos, subject to the owner's explicit session approval below,
but grants no other message access, expiry exceptions or encryption-key access.
Future privileged features need explicit server-side
authorization checked against the current role; client UI or a cached role is
not an authorization boundary.

Private contact nicknames stay in the owner's encrypted local account partition
and are never sent to the relay or the contact. A shared profile refresh may update
the contact's current username and profile name, but never that private override
or the pinned device/key. Legacy local profile names stay local until explicitly
saved as a shared profile; no automatic publication occurs on upgrade.

The conversation menu's Profile opens only the selected contact's local display-name
editor with a non-editable username. My profile, reached from the Chats avatar, is
the separate owner-only account editor. There is no username update call in the
contact editor, and the server still derives update ownership from the session.

Profiles, attachments and other in-app dialogs use secure native bottom sheets.
Their windows retain screenshot and overlay protection; backgrounding dismisses
them and clears visible content. Same-conversation redraws preserve an unsent
composer draft only in memory. The draft is not persisted and is cleared when
the app backgrounds; switching conversations does not carry it to another peer.

## Online, typing and last-seen metadata

Direct-chat headers display the saved contact name, then Typing, Online or an
authorized offline Last seen value, in that order. Online means the official peer
app is foregrounded, authenticated and connected, not that our own relay request
succeeded. Last seen means the relay's most recent foreground heartbeat, not an
exact disconnect time or message-read time. Unknown or older-than-24-hour activity
has no label. Usernames remain read-only in the contact profile; groups are unchanged.

Foreground clients send presence metadata over authenticated TLS. The relay sees
the temporary audience (up to 128 pinned direct contacts), typing recipient and
timing, but never draft text, photos or private keys. Presence is not end-to-end
encrypted. Sharing requires both clients to list each other's exact account,
device and public-key identities. The reader needs a current device session and
live authenticated websocket; Online/Typing also requires those for the peer.
One-sided contacts, username lookup and group membership alone grant no access.

Each heartbeat atomically replaces a nonpersistent Redis online record with a
12-second TTL; typing lasts at most five seconds after the last keystroke. Clients
with the lastSeen capability also replace one latest-activity record, containing
the peer's pinned audience, device generation and server-observed time, with an
atomic 24-hour TTL. This is one timestamp, not an activity log. Reading it never
extends its lifetime. Access-token rotation/expiry does not erase this bounded
offline value; registered device-generation changes block it. Sign-out removes
it, and the heartbeat's atomic session check prevents a revoked request from
recreating it. Changing/removing contacts replaces the audience on the next
successful heartbeat; an empty audience or a legacy heartbeat removes the record.
Changes made without a connection cannot revoke an already shared value immediately.

Android keeps only in-memory response snapshots, each valid for at most 12 seconds
and never beyond the activity's original 24-hour deadline. It accounts for request
time with a monotonic clock and clears status on local disconnect, backgrounding,
failed requests or changed identities. Expiring Online is not converted into a
guessed Last seen. A received indicator may briefly outlast a lost connection or
removal until its short deadline. There is no persistent phone cache, permanent
audience database or background presence service. Older clients retain their
Online/Typing contract and do not publish last seen; both people must update for
the new feature. A malicious peer can misreport activity or retain previously
received metadata; status is not identity-verification or message-delivery proof.

## Owner-approved Remote Photos

Only an enrolled account whose current database role is `ADMIN` may request this
feature; both clients require an independently verified saved direct contact.
The owner receives one Allow/Don't allow prompt naming the contact and explaining
background and locked-phone access to photos Android permits this app to read,
including originals. Approval and service startup still require an unlocked phone.
Android's photo/notification permission prompts still apply. Partial photo access
is respected; no permanent sharing-enabled flag, silent approval, new-photo upload
job or automatic grant on app restart is added.

An accepted session runs in a non-exported Android data-sync foreground service
with a persistent private notification and an immutable End access action. The
user explicitly approved this notification-based background design. Closing the
owner's chat activity or locking that phone does not end an already accepted
owner session. A redacted lock-screen notification names the active feature and
retains End access without disclosing the contact. Viewer screen-off/lock still
ends access, as does an owner lock before acceptance. End access, sign-out, contact
removal, removal of the device screen lock, loss of required permission, rejected authentication,
photo-connection loss, role/device change, Android service timeout or the session
deadline ends it. A force-stopped or killed service does not automatically restart.
Android/OEM background limits cannot be bypassed or guaranteed.

The server checks the current admin role, participant identities/generations,
explicit owner approval and separate authenticated `/photo-events` connections
on session access and every exchange. These connections do not mark the owner
Online in chat. Requests expire logically after two minutes; all session records,
indexes and revocation/replay markers have atomic TTLs bounded to fifteen minutes.
Ciphertext packets expire within sixty seconds and never beyond the session.
Neither reading nor retrying extends these deadlines. The relay holds at most one
pending packet per direction and bounded retry digests, not a gallery archive.

Each photo session uses official libsignal with independently pinned existing
identities and fresh prekeys in separate in-memory stores. Chat ratchets are not
copied or advanced; the normal encrypted chat vault still closes on background.
This feature intentionally retains a temporary client identity copy and photo
session keys in memory while an approved owner service is active, including while
the owner phone is locked. This explicitly requested exception applies only to
the isolated, accepted photo session; it does not reopen the chat vault or relax
Android Keystore, device-unlock, or photo-permission requirements. Android/OEM
power management may pause or terminate transfer while locked; reboot/force-stop
does not restore sharing. The published 0.4.2 build predates this local lock-policy change.
No private key leaves its own device. Temporary stores are cleared on termination.
Encrypted application envelopes bind session, request, packet, sender/recipient
devices and original deadlines, preventing responses from another conversation
or session from being accepted.

MediaStore is read in twelve-item pages with no fixed gallery-count cap. The
phone creates thumbnails only for requested pages. A tap streams just that
original photo in bounded chunks through the opaque relay. The viewer retains a
bounded thumbnail cache and one original in memory, never a plaintext disk cache.
The current viewer limits an original to 64 MiB and decodes a display bitmap no
larger than 4096 pixels per edge; unsupported/oversized images show an error.
Stalled transfers end after sixty seconds without progress. The phone's source
files remain untouched. Like any authorized sharing, an admin can retain received
photos using another camera or modified client; revocation cannot erase copies
already received. Approval is therefore meaningful disclosure, not remote DRM.

## Private profile photos

An optional owner-chosen photo is a persistent profile setting in that account's
encrypted local vault, not a server profile field or a photo imported from Google.
The client re-encodes it as a square JPEG, at most 256 pixels and 32 KiB, without
source EXIF metadata. Choosing, replacing and removing it require an authenticated
account. A pending picker result is bound to that account; decoded previews and
avatars are cleared from memory when the app backgrounds.

Both parties must save and independently verify each other as direct contacts.
A saved contact sends a photo-free request over its pinned official Signal session.
The owner answers only requests from its own current verified contacts. Responses
authenticate sender/recipient account and device IDs, request ID, photo revision
and deadline. Unknown senders, unsolicited updates and changed device identities
cannot populate the trusted client's photo cache. Group membership alone grants
no photo permission. Username lookup and verification/search results never show
photos, including cached photos, and the relay exposes no public photo URL.

The separate ciphertext inbox and local requests, grants, outboxes, replay markers
and received-photo caches have bounded deadlines no longer than 24 hours. A client
requests refreshes after 12 hours while online. Encryption-ratchet changes and
encrypted outbox persistence commit together; retries keep ciphertext and deadline.
Crossing initial requests do not invalidate an authorized reply already in flight.
An unanswered request to a current mutual verified contact can be replaced after
at least one minute; this is a new bounded request, not an extension of an existing
packet or received photo's deadline. Confirmed empty-photo replies do not trigger
this retry, and unknown/changed identities still fail closed.
Removing a contact clears its photo state and sends an encrypted revocation when
possible. Removing the owner's photo sends a newer empty update to current grants.
An offline recipient can retain its previously authorized copy until its existing
deadline; a malicious recipient can keep a copy indefinitely. Removal is not
remote erasure or DRM. Older apps ignore the separate profile inbox and show
initials. The relay sees delivery relationships and ciphertext lengths, not photos.

## Group trust and membership

Direct-chat verification remains unchanged. Groups use an explicit verified-owner
model: each member independently verifies the owner's identity, and the owner
independently verifies every invitee. Joining requires explicit consent to trust
that owner to approve the other members. This is not pairwise independent
verification between every member, and it is not a claim of Signal private-group
protocol compatibility. A dishonest owner can approve unwanted participants.

The owner sends the canonical membership digest and encrypted group title over
official Signal sessions. Only owner-approved account/device/public-key triples
can receive sender-key distributions. Official libsignal GroupSessionBuilder and
GroupCipher own sender-key generation, signatures, encryption and replay checks.
Each membership revision has a new random encryption epoch. Senders re-fetch
membership before encryption; old-revision requests fail on the relay. Members
wait for owner approval after changes, so the owner must come online to complete
a join/removal rekey. A device/key change requires removal, independent verification
and reinvitation; it is never silently trusted.

The relay persists group IDs, owner, revision/epoch and account/device/public-key
membership only. Group titles, control contents, sender keys and message/image
content are client-encrypted. Membership and traffic patterns are visible to
the relay. Invitations expire after 24 hours. Groups are limited to 200 active
members plus pending invitations, and each account to 20 active groups/invitations.
Only the owner invites/removes/closes; other members can accept, decline or leave.
There is no public discovery, join link, admin promotion or owner transfer.

One ciphertext and one encrypted image are shared across recipient authorization
records, rather than storing 199 image copies. Each member has separate delivery,
read and view-once access. Timed shared payloads are deleted after every recipient
has persisted them; view-once payloads after every recipient has read/deleted
them, or the original deadline, whichever comes first. A member who read/deleted
their copy cannot fetch the shared image again. Removed members lose relay access
immediately; already delivered copies follow their original expiry. This does not
prevent a malicious former member from retaining copies or shared old keys.

Control and message writes use bounded TTLs atomically, and retries do not extend
them or resurrect acknowledged payloads. Group control deadlines are authenticated
inside the Signal payload as well as checked by the relay. Retired epoch state
is pruned after the bounded delivery window; current sender state remains in the
encrypted account partition. Group outboxes and content use the same protected
storage and account isolation as direct messages. Group membership changes can
cancel queued old-epoch sends; the UI reports these rather than resending under
new identities without confirmation.

Group capacity is a product bound, not a throughput promise. The dev/test host,
Redis no-eviction limit, 256 live receipts per group, 512 pending control packets
per recipient/group and existing API rate limits can apply backpressure. Initial
200-member session setup may take multiple foreground sync cycles and prekey
replenishment. Simultaneous 200-phone/OEM and sustained production-load testing
remain required; no VM size or paid services are increased for this feature.

## Client storage

Clear chat in a direct conversation removes only that account's local message
entries, protected content keys, encrypted outbox items and unstored UI sends.
It requires confirmation and keeps the contact, pinned identity, Signal ratchet,
private nickname and other conversations. Existing delivery/read acknowledgements
and replay markers retain their original bounded deadlines so clearing does not
resurrect received messages. It does not issue a remote deletion, reset expiry,
erase the other person's copies or revoke an independently approved photo session.
An upload already in progress may have reached the relay before it is cleared.

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
automatically after any system-accepted unlock method, including fingerprint or
face. A configured screen lock is still required and every vault access checks
both device security and current lock state. Backgrounding clears visible content
and closes the in-memory session. Anyone holding an already-unlocked phone can
open Vanishr: this is not a second authentication boundary.

For 0.3.9, the user explicitly approved a compatibility tradeoff on Android 14 and
below: new nonexportable Keystore AES keys do not use unlocked-device-required or
timed authentication restrictions. The trusted app enforces the unlocked-phone
boundary there; Keystore no longer independently enforces that boundary if the
app process is compromised. Android app isolation and filesystem encryption
remain relevant, but are not equivalent to Keystore authorization. New keys on
Android 15 and above retain unlocked-device-required, with no timed in-app
authentication. Key authorizations are fixed at creation, including after an OS
upgrade; the app's lock checks apply to every supported Android version.

Protected-record versions 1 and 2 migrate to version 3 keys using authenticated
decryption and encrypted atomic writes. Active and signed-out accounts retain
their identity, content state and original deadlines. Old keys are deleted only
after the replacement ciphertext commits. A currently blocked legacy key can
still require one phone PIN/pattern/password unlock to migrate; the app cannot
bypass an existing key's authorization or recover an invalidated key. Failures
preserve the affected encrypted data and keys, never reset an account.

Android 12-14 has documented Keystore bugs where some biometric unlocks do not
re-authorize unlocked-device-required keys. The version-specific policy avoids
that requirement on older Android rather than silently downgrading a key after
an error. Fresh installations perform an in-memory encrypted round trip before
opening storage for sign-in. A migration-specific error explains the one-time
legacy unlock; other authentication errors do not claim migration is required.
There is no plaintext-storage fallback or automatic data reset. Android can still
require a device credential after reboot or biometric lockout; Vanishr cannot
override the system lock screen. Physical/OEM biometric acceptance needs testing.

Google sign-in displays a progress state while the provider result is pending
and while exchanging it for the account session. Returning from the provider
does not briefly offer the login form before that exchange finishes. Failure
and cancellation restore actionable sign-in; background privacy cleanup and
the existing one-use challenge and identity checks still apply.

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

## Notification navigation

From 0.3.6, an absent notification preference defaults to enabled. Existing saved
Off values are not overwritten, including ambiguous legacy values that older
versions wrote during sign-out. Android notification permission remains required;
the app requests it once after an authenticated account is available, not at the
login screen. Denial is not repeatedly prompted on resume. An explicit enable
action can request permission again subject to Android's own restrictions.

Preference and active registration are separate. Before registration succeeds,
the receiver rejects wake alerts. Sign-out immediately clears active registration,
invalidates pending callbacks, disables Firebase auto-init and unregisters/deletes
the provider token without changing the preference. Explicit opt-out still stops
registration and delivery. Firebase auto-init is enabled only for an authenticated,
enabled account with notification permission. No account identifier or credential
is added to unencrypted preferences; the active flag is a delivery gate, not an
authentication credential. Network/storage failure never grants chat access.

FCM still receives no sender name, account/chat ID, message text, image, key or
access credential. Clients that opt into routing during push registration may
receive `event:new_message` plus a random 256-bit opaque reference. Older clients
retain the event-only payload. The provider observes the device token, timing and
opaque reference, not its conversation mapping. Notifications remain generic,
private on the lock screen, collapsed to one alert and bounded to 60 seconds.

The relay stores only the reference digest and routing metadata, one destination
per device, with an atomic TTL no longer than five minutes or the message's
original deadline. A later destination supersedes the previous one. Resolving
requires the current DEVICE session and the matching account/device; possession
of a reference alone grants no access. Opt-out and sign-out remove the mapping.
Routing references are hints, not encrypted-message authenticity proofs.

The immutable Android tap intent contains only the opaque reference and a bounded
local deadline. The app waits for phone-unlocked storage, resolves the hint over
authenticated HTTPS, syncs, and requires a matching unexpired, incoming, unread
local message plus the existing verified direct-contact or approved-group trust.
Expired, superseded, read, missing, changed-identity or wrong-account destinations
fall back to the chat list. No account is switched or trusted because of a push.
A notification opens the conversation, never view-once content or a photo viewer.
Network/provider failure does not weaken identity checks or extend content TTLs.

## Architecture chosen before implementation

- Java Spring Boot HTTPS relay; PostgreSQL holds accounts, editable username/display
  metadata, password verifiers, device identifiers, public prekeys and group
  membership metadata only. No payload tables, private nicknames, group names,
  or content-decryption keys.
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
- Realtime events contain only a generic wake-up signal. Opted-in push clients can
  additionally receive the bounded opaque routing reference described above,
  never a sender name, message, image, media key, or access token. Offline
  ciphertext disappears at its deadline.
- No calls, stories, reactions, searchable history, account recovery of keys,
  cloud content backups, analytics SDK, or remotely supplied executable UI.

## Threats and limitations

| Threat | Protection | Residual risk |
| --- | --- | --- |
| Database theft | Only account/profile metadata, password verifiers and public device material | Usernames, chosen shared display names, public keys and password guessing remain exposed; use strong passwords |
| Redis/blob theft | Only Signal ciphertext or authenticated encrypted images, each with TTL | Recipient/sender IDs, lengths, timing and delivery relationships are visible |
| Backend compromise | Previously verified identities, client encryption and signed distribution keep content keys off-server | Server can deny/reorder delivery, lie about receipts, retain ciphertext, alter public keys before verification, and collect metadata |
| Network attacker | HTTPS/WSS, certificate validation, no cleartext fallback, E2EE | Traffic analysis and denial of service remain possible |
| Stolen locked phone | Android Keystore-wrapped private state, phone-unlocked access checks, encrypted local records, disabled backups | No separate app lock on an already-unlocked phone; OS exploits, weak screen lock, rooted device and forensic recovery are not defeated |
| Stolen session credential | One-hour access, single-use rotating 30-day renewal, hash-only server storage, current-device checks, logout/replacement revocation | A stolen renewal handle can extend unauthorized relay access while valid; neither token decrypts ciphertext or creates valid peer content |
| Malicious recipient | Local expiry and screenshot flags reduce accidental retention | Recipient can modify client, copy plaintext, screenshot on unsupported devices or photograph a screen; disappearing content is not DRM |
| Replay/tampering | Signal ratchet replay detection and authentication, authenticated envelope IDs and deadlines, idempotent relay IDs | A server may still replay opaque delivery metadata; client rejects expired/duplicate envelopes |
| Identity substitution / MITM | Mandatory independent verification; pinned public identities; explicit reset on replacement | No key transparency service; verifying over this same relay is not verification |
| Push compromise | Generic notification and optional opaque hint; authenticated recipient resolution and verified local message required | Provider sees device token, timing and opaque reference; notifications can be suppressed, delayed or forged; hints alone grant no access |
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