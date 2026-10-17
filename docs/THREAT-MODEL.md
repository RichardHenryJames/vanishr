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
Ordinary contacts require peer identity fingerprints verified over an independent
authenticated channel before sending or decrypting messages. The explicitly
approved official-admin onboarding exception is described below. A changed
identity still blocks the conversation.

Username lookup is discovery only. The user independently compares the displayed
SHA-256 fingerprint of the canonical account UUID and public identity key, then
confirms verification. The client re-fetches the username binding before pinning
it and rejects changed account/device/key values. No pasted identity code or
automatic trust-on-first-use is permitted for ordinary contacts.

Optional Google sign-in authenticates the account, never the peer or encryption keys.
It is disabled until a Web OAuth client ID and allowed Android client IDs are configured.
Google-signed ID tokens are verified by the official Google library; issuer, audience,
authorized party, expiry, issued-at time and a one-use five-minute relay nonce are checked.
Only Google's stable subject identifier is retained, not email, name, photo, access token
or refresh token. Google accounts are not automatically linked to password accounts.
Google compromise or account takeover can replace a device but cannot recover old
private keys; contacts must independently verify the replacement identity. Google
learns that the user signs into this app, which is an additional metadata tradeoff.

Signed release packaging requires validated Android Google client settings unless
the operator explicitly selects a Google-free build. Missing, ambiguous or
wrong-package/certificate configuration fails before building; a server
service-account credential is never accepted as client configuration. The opt-out
changes provider availability, not the account/peer trust rules or retained keys.

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

New Google account handles use two independently selected words from 512-word
English lists and a four-digit random number, generated with `SecureRandom`.
They are public, guessable identifiers, never authentication secrets or proof
of a person's identity. The Google subject/account UUID and any existing handle
remain unchanged on returning sign-in. PostgreSQL enforces handle and subject
uniqueness, with bounded conflict retries rather than stealing a colliding name
or silently returning another account. V11 accepts hyphens without rewriting
existing account records. Earlier clients must upgrade to understand these
handles across profile, contact and group validation.

Account type is server-controlled metadata attached to the immutable account UUID.
The PostgreSQL `accounts.user_type` column permits only `USER` or `ADMIN`, defaults
to `USER` for existing and new accounts, and is not accepted by any client write
API. An enrolled account may read its own current type via `GET /account/type`;
contact lookup/profile responses do not disclose it. Only an authenticated database
operator may manage the existing admin's role, never an automatic username rule.
Migration V6 permanently pins the sole existing admin's immutable UUID and adds
a unique partial index limiting `ADMIN` to one account. Multiple admins cause a
transactional migration failure, not an arbitrary selection or silent demotion.
With no existing admin, nobody is promoted; a trusted operator must explicitly
pin an inspected account and enable it in a bounded transaction.

Triggers reject a different account becoming admin and reject changing, deleting
or truncating the singleton pin. From V8, guarded account erasure can remove the
admin's account data while leaving only the permanent random UUID reservation.
That UUID cannot be moved, reinserted or reassigned after erasure. Revocation
without erasure leaves the original account eligible for re-enablement, but
deleting it does not make anyone else eligible.
The server reads role and pin together and fails closed on a mismatched admin,
even if a role-only database guard was bypassed. There is no client promotion,
transfer or automatic admin-recovery endpoint. Live release tests must not create
temporary admins on a shared relay.


## Automatic official-admin conversations

For accounts created after migration V7, registration atomically records a durable
relationship to the permanently pinned admin UUID. Existing accounts are not
backfilled. New users get the official admin in their chat list; the admin gets
a participant-scoped, paginated directory of new enrolled accounts. Registration
does not promote anyone or create a message. Email addresses are not retained.
The admin sees current shared names, usernames and public device identities,
not other conversations or private contact nicknames. This is a human admin
account, not an AI bot.

The signed app pins the official relay origin, admin account/device UUIDs and
SHA-256 public-identity fingerprint. The relay cannot replace that trust root.
Only this explicit onboarding path avoids the manual verification prompt. Both
participants persist the relay-authorized introduction during background sync;
message delivery is not required to make the relationship visible. The admin
directory returns newest enrollments first and remains bounded and paginated.
The admin's client must also possess the pinned local identity before it can
automatically establish these new conversations. On the admin side, the initial
new-user binding trusts authenticated relay enrollment, not independent proof
of a person's real-world identity. A compromised relay can lie about that first
new-user binding or omit introductions; it cannot impersonate the pinned admin
to the trusted client without the corresponding private key.

The client pins an introduced user's identity when opening or receiving that
conversation, then uses the existing official libsignal sessions and authenticated
message envelopes. Arbitrary senders outside this relationship remain untrusted.
Previously pinned keys/devices are never silently replaced. An admin identity
change requires a reviewed signed-app pin update and explicit re-onboarding,
not a username rule, remote configuration update or verification bypass.

From 0.5.8 this automatic chat also shares Online, Typing and Last seen status in
both directions, like any other direct chat (it was the one conversation that never
showed it). A client lists an automatic contact in its presence audience only while
the saved identity still passes the same pin checks (the official identity for a
new account, the local pinned identity for the admin), the contact is neither blocked
nor dismissed and the Signal identity is still pinned; any doubt shares nothing. The
relay's mutual-listing rule is unchanged, so an older client on either side shares
nothing. This is not independent verification: groups, private profile photos and
contact backups still require it. Consequences: every introduced account in the
admin's sharing list (up to 128 at a time, the open chat first) can see when the
official admin uses the app, so anyone who registers can observe that; and the
admin's initial binding of a new account is still relay-supplied, so a compromised
relay could introduce an attacker-controlled account that learns the admin's
status. The relay itself already observes this metadata.

From 0.5.5, the explicitly approved offline-first-message policy supports
libsignal's standard PQXDH last-resort prekeys. A recipient publishes a signed
EC/Kyber fallback without a one-time EC key. Only capable senders receive it,
and only after the one-time pool is exhausted/expired. Publication is bounded
to 30 days, normally rotates after about seven days, and is never renewed by
claims or retries. Private fallback material and bounded replay tuples remain
only until that key's original deadline plus the 24-hour delivery window.
Native libsignal checks signatures and establishes sessions; the client store
rejects a repeated signed-key/base-key tuple rather than consuming the fallback.
One-time keys remain preferred and continue to be deleted after use.

This changes the former one-use-only availability policy: reusable prekeys
provide less per-handshake forward-secrecy isolation until rotation/erasure.
The permanent admin pin, ordinary-contact independent verification, block rules
and encrypted-content deadlines are unchanged. The admin must install the
updated app in place and open it to publish its own public fallback. Missing or
expired keys, unavailable relay/storage, rate limits and the 1,024 retained
fallback-handshake bound still fail explicitly. This is not unlimited offline
availability, guaranteed immediate delivery, or a fix for device-specific ANRs.

Only one directory page of at most 64 contacts is cached in the owning account's
encrypted vault. The admin can browse further pages using More new accounts;
opened conversations remain saved normally. This avoids allocating encryption
sessions for every registration. Directory metadata and dismissals are durable
account/contact settings, not message payloads; they remain subject to the
existing protected-vault capacity and account-isolation boundaries. The relay
rechecks the current admin role for discovery, and pauses discovery when it is
revoked without transferring the permanent pin.

### Photo viewer and the admin's save option (0.6.0)

A photo opened from a chat fills the screen on black with a single close cross at the
top right (no sheet, padding or Done button). The window keeps `FLAG_SECURE` and hides
overlay windows, exactly as the sheets did, the photo closes at its message deadline and
when the app is backgrounded, and the decoded bitmap is released with it.

**One deliberate exception to "no plaintext copy outside the vault":** only the account
that passes the pinned official-admin check keeps the decrypted photo bytes while the viewer
is open. That check is `AdminOnboarding.verifiedAdmin()`: the signed-in user id, this
device's id and this phone's own identity key must all match the pin compiled into the
signed app, and any failure counts as "not the admin". The user id alone is not enough,
because the relay supplies it at sign-in (`admin()` compares only that id, so it is not used
to unlock saving; a regression test covers an account that carries the admin's id without
the pinned identity). The decision is made before the message is consumed and off the main
thread.
A long press anywhere on that photo opens a confirmation, and Save writes the unchanged
bytes into the shared gallery (`Pictures/Vanishr`, through MediaStore, Android 10 or later;
no storage permission and no extra manifest permission). This works for any photo the admin
can open, including one sent as view-once: the message is consumed when opened as before,
and the saved file is a separate, ordinary, **unencrypted gallery file that does not expire**,
is outside this app's protections, and is visible to any app with gallery access and to
Android backups of the gallery. Every other account's app wipes the decrypted bytes
immediately after decoding, as before, has no save gesture and has no save path. The bytes the
admin's viewer keeps are wiped when the viewer closes (cross, back, expiry, backgrounding or
sign-out); the copy handed to the writer is wiped after the write.

What this changes for other people: anyone who sends the admin a photo, including a
view-once photo, should assume the admin can keep it. It does not give the admin anything a
recipient with a modified client or a second camera did not already have (the existing
statement that view-once and expiry cannot stop a recipient keeping a copy still stands),
but it makes it one tap for the one account that new users are introduced to. The privacy and security pages and
`llms.txt` say so. The in-app policy summary said so in 0.6.0 and 0.6.1; after 0.6.1 the owner removed that
paragraph from the summary (it also said that new accounts are introduced to the admin and that the admin sees a
user's status; commit `c457e6c`), and 0.6.2 ships without it. That was the owner's decision and is kept; it means
the screen a user accepts no longer tells them that
the admin's app can keep a photo it is sent. The gate is a client-side
product rule, not a security boundary: a modified build can save anything it can view,
and a compromised relay cannot make an ordinary account save, because the check is the
local pinned identity, not a relay value.

Not claimed: that the saved file is protected, that an ordinary account cannot be modified
to save, or that physical-phone behavior and Android 9 (which has no scoped storage write
path here; the option explains it needs Android 10) were tested.

## Account erasure, blocks and safety reports

Account erasure requires a fresh account-only password/Google sign-in, never a
remembered device session. The account is marked DELETING before cleanup; failed
cleanup disables normal use and remains retryable. A saved Google account's
challenge includes its expected immutable UUID, preventing reauthentication
from silently registering a replacement after erasure.

Deletion uses the same bounded 30-second clock tolerance as Google challenge
preparation when validating the upper deadline of a freshly issued challenge
or account-only credential. Already expired values still fail. The locally
retained deletion credential is capped at the earlier of its original server
deadline and five minutes from validation; tolerance never extends that
credential, the server session, or a deletion recovery proof.
Returning from the Google picker must still match the selected immutable
account, relay origin and Google provider. Cancellation/failure retains an
account-bound retry prompt, not a deletion authorization. Retry requires the
typed confirmation and fresh sign-in again. Explicit deletion success appears
only after confirmed server deletion and owner-specific local cleanup.

The client commits a separate random 256-bit proof before sending deletion.
V9 stores only its digest, original UUID, state and a fixed <=24-hour deadline
in PostgreSQL. The receipt and account erasure commit together; responses are
held until database commit. Proof status/retry survive Redis reset and cannot
log in, choose another account or extend their lifetime. Unknown/expired proof
is not confirmation. On a confirmed result the client erases only that owner's
vault partition/content keys; interruption retains a cleanup marker and blocks
ordinary chat/sign-in until resolved. The minimal admin UUID reservation
survives erasure to prohibit admin replacement.

Reports are explicit metadata-only submissions: reason, reporter/target UUIDs
and optional accessible group/message IDs. No plaintext, images or free-text
evidence is copied from an encrypted conversation. The current pinned admin
can review up to 50 records at a time; submission is bounded to five per user
per day and a 10,000-record queue, with atomic <=30-day expiry. Account deletion
removes reports made by or targeting the deleted user; deleting the admin
clears the undeliverable queue. Report submission is not proof that effective
human moderation has occurred.

Other recipients' group payloads and consumed replay/receipt metadata can
remain until their original <=24-hour deadline. Unknown legacy detached
uploads can delay deletion until their original <=5-minute expiry rather than
deleting unrelated data. Cleanup failures never imply successful deletion.
The static email request resource does not collect passwords or tokens;
operator ownership checks and mailbox handling remain human responsibilities.

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
them and clears visible content. A received or sent photo is the exception: it opens
in the full-screen viewer described under
[Photo viewer and the admin's save option](#photo-viewer-and-the-admins-save-option-060),
with the same screenshot and overlay protection. Same-conversation redraws preserve an unsent
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
the temporary audience (up to 128 pinned direct contacts, including the automatic
official-admin chat), typing recipient and
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

Conversation refreshes read protected records and decrypt newly displayed timed
text on a read-only worker, not the UI thread and not the worker that syncs and
sends. The read acknowledgements for everything newly displayed are one encrypted
commit, and a message that vanished meanwhile is skipped, never recreated.
Receipt-only updates reuse the existing message views instead of
decrypting the history again. Retained views remain bounded by the existing
message limit; their text is cleared on expiry, conversation changes and
backgrounding. Asynchronous results are bound to the current engine and screen
generation and recheck absolute deadlines before display. View-once messages
still require explicit opening. No additional plaintext disk cache is created.

Presence-header identity checks also run on the read-only worker. The UI checks the
validated in-memory snapshot's original deadline and current connection state;
disconnects and replacement snapshots invalidate the label immediately. Typing
edits do not hold a lock across protected-storage or network operations. Realtime
wakes arriving during a sync request one coalesced follow-up instead of being
discarded; Signal and vault mutations remain serialized.

Profile-avatar reads and decoding also run on the read-only worker. Results
are bound to the current engine, screen, photo revision and individual view
request, with expiry rechecked before display. Backgrounding invalidates these
requests and clears bitmaps; secure-storage failures are surfaced through the
storage screen instead of escaping a UI timer callback.

Foreground cleanup immediately cancels that engine's in-flight relay requests
and rejects new requests on its retired connection, then queues vault closure
behind the current storage operation. It does not sign out, erase credentials,
replace identities or reset encrypted records. An explicit queued sign-out still
performs its local credential cleanup; network cancellation cannot prove remote
revocation. Independently established photo-sharing services use their own
transport and retain their existing consent/lifetime rules.

Vanishr has no separate app lock or repeated credential prompt. While Android
reports the phone unlocked, foreground startup opens the encrypted vault
automatically after any system-accepted unlock method, including fingerprint or
face. A configured screen lock is still required. Opening the vault and every
Keystore operation check both device security and current lock state; reads of
records already open in memory reuse a successful check for at most 250 ms (see
[Responsiveness and the open vault](#responsiveness-and-the-open-vault)). Backgrounding clears visible content
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

### Signed settings: changes without an update (0.6.2)

Owners often want to change something small without a new app version. Dynamic code is not an option for this
app: Google Play forbids a Play-installed app from downloading executable code, and a messenger whose client code
can be replaced from a website would make that website part of the trust base for every key and message. So the
channel that exists is **data only**, and deliberately narrow.

- **What it is.** One JSON file on the static download host, `/ota/settings.json`, in a signed envelope. The app reads
  fixed fields with fixed limits: a notice (title up to 60 characters, body up to 400 and at most six line breaks, plain
  text, an optional page on the download site, optional build and version-code targeting), off-switches for `images`,
  `photos` and `groups`, and an update hint (latest and minimum version code) that only Google Play builds act on, by
  showing a card with a link to the app's own Play listing. Unknown fields are ignored; any known field that is
  malformed refuses the whole file. The switches are narrow on purpose: `images` covers sending an image in a chat (the
  attachment menu, a queued send and the retry of an unsent one), `photos` covers opening a photo in a chat, whether it
  was received or sent by the user (including a view-once photo, which is not used up when it is refused), and `groups`
  covers creating a group. They do not cover profile photos or the administrator's remote-photo feature, and a send
  already under way when the settings change is not recalled.
- **What it cannot do.** No field is code, an address, a key, a message, a deadline, an identity decision or a
  server setting, and no field can switch anything *on* that the build does not already allow: a switch is on unless
  the file says `false`. The relay address, expiry times, verification and block rules are not reachable from it.
  Links open only `https://vanishr-download.vercel.app` + a page path: up to four segments of letters, digits, dashes
  and underscores, with no dot at all, so never a file such as the APK, and no query, fragment, `//` or dot segment.
  Text is shown as plain text (no markup, no auto-links). The text rules go by Unicode category: control characters,
  line and paragraph separators, "format" characters (zero-width and bidirectional marks, soft hyphens, tag
  characters; only the two joiners that emoji and some scripts need are kept), private-use and lone surrogate code
  points, noncharacters and the replacement character are refused, and so is a text that is blank. That makes the usual
  ways of hiding text impossible, but it does not make a notice incapable of looking odd: characters that Unicode
  files under letters, marks or spaces yet draw as nothing or as a blank (the braille blank U+2800, the Hangul
  fillers, variation selectors, the combining grapheme joiner U+034F, a body of nothing but no-break spaces) are not
  refused, because a list of lookalikes would never be complete. A notice is the owner's voice and is trusted exactly
  as far as the owner's website is: it can say something misleading in plain visible words, or look empty. It is shown
  in an area that takes at most 40% of the height that is left and scrolls inside it,
  so no notice, however long, can push the chat list out of reach (an emulator test builds a notice the verifier would
  refuse, with nearly 400 line breaks, to show that the layout holds on its own). The update hint can only open the Play
  listing and never blocks use.
- **Who can publish it.** The signature is ECDSA P-256 with SHA-256 over a fixed prefix (`vanishr-ota-settings-v1`
  plus a zero byte) and the exact payload bytes, checked against up to three public keys embedded in the app
  (`res/raw/ota_keys.txt`). The private key is created by `scripts/new-ota-key.ps1` under `.secrets/ota-signing`
  (git-ignored, never committed, packaged or uploaded, restricted to the current Windows user each time the script
  runs, with the result read back and checked; `-RestrictOnly` applies it to a folder that exists already). It is encrypted with a
  generated password that is stored next to it, as for the Android signing key: that keeps a stray copy of the key file
  alone useless, but anyone who can read the whole folder can sign, so the folder has to be protected and backed up as a
  whole. Someone who controls the website but not that key can serve an old valid file, serve nothing or break the
  format, so at worst they can delay or block a settings change; they cannot forge one. Someone who holds the key can
  show a notice, switch those three features off and make Play builds show an update card, which is the extent of the
  power. There is no hard block or forced update, and a notice cannot cover the chat list, so even a stolen key cannot
  lock people out of their chats.
- **Freshness.** Each file has a sequence number (1 to 1,000,000), an issue time and an expiry of at most 60 days. Two
  rules keep an older signed file from being put back. The app keeps the highest sequence it has accepted **for each
  signing key** and ignores anything not higher from that key. It also keeps the newest **issue time** it has accepted
  **across keys** and ignores a file issued earlier than that, so an old file of one listed key cannot be put back over
  a newer file of another (a per-key counter alone would allow that). `BUILD_ISSUED_FLOOR` is the issue time of the
  newest file that existed when the version was cut (`scripts/sign-ota-settings.ps1 -Check` prints the value to use):
  a fresh install takes nothing issued before it. Because the sequence floor belongs to the key, a key that has been
  retired, or that signed the highest number there is, can only block itself: a replacement key shipped in a later app
  version starts from its own sequence floor. The issue time that is kept is never later than the phone's own clock
  at that moment (a file may be dated up to 36 hours ahead of the clock and is then still taken), so a file dated in
  the future cannot make the phone refuse the files that really follow it, and a leaked key cannot hold back the
  owner's next files on the phones by future-dating one. The signing tool never dates a file earlier than the newest
  file it knows
  of (the committed one, or a live one that a listed key signed) or than the app's `BUILD_ISSUED_FLOOR`. When the
  computer's clock is behind that time by up to 36 hours, which is what a phone allows ahead of its own clock, the new
  file is dated at that time instead and the tool says so (so whoever holds the key can make the owner's next file be
  dated up to 36 hours ahead by publishing a file of their own so dated; a phone whose clock is right takes it
  at once, and one whose clock is behind real time by more than the difference takes it later); when it is behind by
  more, the tool stops, because from the
  files alone it cannot tell a clock that is wrong now from a file that was signed while a clock was fast (a file that a
  phone with a right clock refuses until real time is within 36 hours of its date), and `-IgnoreFutureIssueTime` is the
  owner's statement that the clock is right: it sets such a file aside, and the tool says until when to publish,
  because a phone takes the old file from that time on, holds a later time than the new file and then ignores it. This
  covers only the files the tool can see: a file signed on another computer is outside it. A phone whose clock is behind real time keeps a
  correspondingly lower issue time, so the protection against putting an older file of another key back is only as
  precise as phone clocks are (it matters only while two keys are listed and used close together). An expired file stops
  counting at once, with no network, and the app behaves as
  built, which is the safe state (a switch that was turned off comes back on when the file that turned it off
  expires, so a forgotten renewal re-enables the feature; `scripts/sign-ota-settings.ps1 -Check` reports days left and
  the release gate runs it). A file whose issue time is more than 36 hours ahead of the phone's clock is refused. The
  phone's clock decides expiry: a clock set forward makes valid settings look expired and be ignored, a clock set back
  keeps an expired file in force for as long as the clock is wrong; neither can make the app accept a file that was
  not signed. A phone whose clock is far ahead of real time when someone with a leaked key hands it a file keeps that
  clock time as its newest issue time and ignores the owner's files until real time reaches it or the app's data is
  cleared; that needs the leaked key and the phone's wrong clock together.
- **Rotation and loss.** Up to three public keys may be listed, so a new key can be shipped in an app version before
  the old one is removed. The signing tool counts on from the committed file even when the key that signed it is no
  longer listed, and from a live file only when a listed key signed it (anyone who can change the website could write
  any numbers there, so an unverified file's numbers are never counted); `-Sequence` chooses a number by hand if that is
  ever needed. There is no way to revoke a key
  remotely, because a remote revocation would itself be a remote control channel: if the key is lost or may have been
  copied, the remedy is a new key in a new app version (and, for Google Play, a new bundle), and phones that have not
  updated keep trusting the old key until they do.
- **The request.** One bounded HTTPS GET to the fixed host at most once a day while the app is in the foreground
  (a failure is retried after two hours), with no cookies, account tokens or identifiers, no redirects, a 12 KiB
  response limit and the same short timeouts as the update check. The host sees the source IP and request time,
  exactly as for the update check. Unlike the update check this request is also made by the Google Play build, which
  contacted the website only when the user opened one of its pages before; the privacy policy and the Play Data
  safety notes say so. The key list is read and the stored file checked on the update thread after the screen is up
  (measured at 34 to 42 ms of work in a cold process, which is why it is not on the main thread).
- **Storage.** The last accepted file is kept as it came in the app's private preferences together with the highest
  sequence per signing key, the newest issue time, the time of the last check, the id of the dismissed notice and the
  version code of the update hint that was dismissed (the two dismissals are independent; a hint about any other
  version is shown again, an earlier one included, so that a hint published with a mistyped version can be corrected).
  It is public data, holds nothing about the user and is verified
  again, signature and expiry included, on every load, so a file altered on the phone is not used. A stored file that an
  app update finds issued before its new floor is not used either, and the next check is then due at once instead of
  up to a day later. It is not backed up
  (Android backups are disabled).
- **Where it is not trusted.** The settings never decide anything the app must fail closed on. They are consulted
  only to show a card or to refuse to start sending an image, opening a photo or creating a group.

Not claimed: the key could be lost or stolen like any key; the owner's computer is the signing environment; nothing
here is an independent audit; and the behavior was exercised on an emulator with synthetic files only (see
[VERIFICATION.md](VERIFICATION.md#release-062--signed-settings-and-google-play-sign-in)).

### Responsiveness and the open vault

Before 0.5.9, opening the chat list or a chat could take seconds: every list row
rescanned every stored message, every vault read made two Android binder calls to
re-check the lock state, a read waited while another thread re-encrypted and wrote
the whole vault, and tapping the automatic admin chat queued behind the sync on the
same worker. These are bounded now without weakening the access rules:

- **Lock check.** Opening the vault and every Keystore operation (sealing, decrypting
  message content, writing the vault) check that the phone is secure and unlocked
  each time. Reads of the vault's records that are already decrypted in memory
  (contacts, message metadata, and the Signal state and session tokens the open vault
  holds) reuse a successful check for at most 250 ms. After the phone locks, those
  reads can still succeed for up to 250 ms; message text cannot be decrypted and
  nothing can be committed in that window, because those operations check again. This
  check never protected memory from code running inside the process.
- **Snapshot reads.** A read is served from an immutable copy of the last committed
  state and never waits for another thread's commit. A transaction sees its own
  writes; other threads see them only after they have been persisted. A failed
  transaction publishes nothing. Replaced and removed values are zeroed after
  in-flight readers finish, and closing the vault clears the snapshot under the same
  lock, so a read racing the close fails closed instead of seeing an empty vault.
- **Local-only taps.** A saved contact's chat, verified or the automatic admin chat,
  opens after the same identity, admin-pin and block checks as before, now run on the
  main thread against local records only. A changed identity, a blocked account and a
  deletion in progress still fail closed. Only a contact never saved on this phone
  needs the network introduction, which still runs on the sync worker.
- **Lanes.** Screen preparation (message text, avatars, the presence header) uses a
  read-only worker that has no network or Signal access. Signal sessions, sending and
  sync stay on one worker because libsignal state is not made for concurrent use.
  Because the read-only worker stores read receipts while the sync worker may be
  sending acknowledgements, the sync worker deletes a sent acknowledgement only if it
  is unchanged; a read receipt stored while "delivered" was on the wire is kept and
  sent on the next pass. Counters that tell the screen stored data changed (the
  profile-photo generation) are bumped only after the commit is published, through a
  vault hook that never fires for a rolled-back transaction.
- **Cooperative sync.** A user action that waits for the sync worker is counted; a
  running sync stops at its next step boundary, which is equivalent to a dropped
  connection there, and resumes behind the action from the stored state.

Unchanged: no plaintext message cache, no new logging, absolute deadlines rechecked
at display, and the same fail-closed behavior for expired content, failed
authentication, changed identities and unavailable secure storage.

Not claimed: the measurements in [VERIFICATION.md](VERIFICATION.md#release-059--startup-and-tap-responsiveness)
come from an emulator with software-backed Keystore. A phone whose Keystore is
hardware-backed (including StrongBox) encrypts and decrypts more slowly, and the vault
is still re-encrypted whole on each commit; that was not measured and is not addressed.

### Long chats, the message limit and the keyboard (0.6.1)

After 0.5.9 a busy chat still got slower the longer it ran. Opening it decrypted every
message one after another (each message has its own Keystore key, about 20 ms each on the
emulator), so a 100-message chat showed nothing for several seconds, and every refresh,
purge and sync pass parsed every stored message record again. The phone also stopped
working at 100 live messages, because the limit was 100 across all chats and a to-fro chat
reaches that within the hour. These are bounded now without changing what is protected:

- **Per-message keys are unchanged.** Every message still has its own hardware key, deleted
  at its deadline, so expiry still erases it cryptographically. Text is still decrypted
  only for the open chat, still held only in that chat's views, and still cleared on
  expiry, leaving the chat and backgrounding. Decryption happens in batches on the same
  background-priority lane, one message at a time (the reader adds no parallel Keystore
  operations of its own, which hardware-backed stores limit), and every message re-checks
  that the chat is still the one on screen, and every Keystore use that the phone is
  unlocked, before anything is decrypted.
- **Newest first, in batches.** The first 16 messages are shown before the rest are
  decrypted. Read receipts are recorded per batch before that batch is shown, so a
  message that is on screen has its receipt durable exactly as before.
- **A window of history.** Only the newest 100 messages are prepared when a chat opens;
  older ones are decrypted when the user scrolls up to them. Messages outside the window
  stay encrypted until then.
- **Parsed records.** A parsed message record (id, deadline, state, flags) is kept in
  memory while its stored bytes are exactly the same, and dropped when the record is
  removed or the engine closes. It never holds message content, so this is not a message
  cache.
- **Limit raised to 300, and a full phone makes room.** The limit is per phone, not per
  chat. When a phone holds 300 live messages, or its encrypted store passes 16 MB (photos are
  what make it large), it does not stop: before it stores the next message it removes the ones
  that would expire soonest, which loses the least of their lifetime, 15 at a time (or the
  earliest photos until 4 MB are free). Only a message already read, or one this phone sent that
  is no longer waiting to be sent, is ever removed this way; an unread message, an unopened
  view-once message and an unsent one are never taken. "Read" means what it always has: a chat
  that is opened marks the messages it shows (the newest 100) read as they are prepared, so a
  message that was shown but not yet scrolled to can be removed early. Removing the earliest
  deadlines first is what limits that: the newest messages of a chat that was just opened expire
  last, so the ones that go first are normally the oldest. The sender is told nothing.
  A removal commits all the records in one encrypted write and deletes the keys only after that
  write is durable. Done the other way round, a failed write would leave messages that are still
  listed but whose keys are gone, which a screen could only report as a failure; this way a
  message whose key is gone has already been removed, and a failure between the two steps leaves
  a key with nothing left to open, which is deleted at its message's deadline like any other.
  The "seen" marker that stops the relay handing a message over twice has to last as long as the
  relay could still do that. The relay takes a message out of the recipient's inbox when it
  accepts the first acknowledgement (`acknowledge.lua`), so once an acknowledgement has been
  accepted the marker is removed together with the message, and the markers of removed messages
  cannot pile up towards the vault's 4000-record limit. While an acknowledgement is still waiting
  to be sent, the marker stays until the message's deadline. So does it for an acknowledgement
  the relay refused: the relay also answers "not found" while the two accounts are blocked from
  each other and lists the message again once they are not, so a refused acknowledgement is no
  longer thrown away but kept as a "held" receipt (never sent again, removed at the message's
  deadline) for exactly that purpose.
  The cost is that in a very busy phone the oldest read messages can disappear a little before
  their timer; a message is never kept longer than its deadline. Only if every stored message is
  unread or unsent does the old behavior remain: a send is refused with an explanation and an
  incoming message stays at the relay, which keeps it until its deadline. 300 messages are at
  most about 900 vault records, well inside the vault's 4000-record bound, and the 16 MB soft
  limit keeps photo-heavy chats well clear of the vault's hard 32 MB limit, where every write
  would fail.
- **Keyboard.** The message list keeps its bottom edge in place when its height changes.
  This is layout only.

Unchanged: no plaintext message cache, no new logging, deadlines rechecked at display,
fail-closed behavior for expired content, failed authentication, changed identities and
unavailable secure storage, and the whole-vault commit.

Not claimed: the numbers in [VERIFICATION.md](VERIFICATION.md#release-061--long-chats-and-the-keyboard)
are from an emulator with a software Keystore. On a phone with hardware-backed or StrongBox
keys each Keystore operation is slower, so the first screen of a long chat is the part that
matters, and the commit of a large vault (many photos) is still the cost this release does not
remove.

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
  metadata, password verifiers, device identifiers, public prekeys, group
  membership, deletion state, block/intro metadata and, only for users who opt in, one
  opaque client-encrypted backup blob per account. No payload tables, plaintext private
  nicknames, group names, or content-decryption keys.
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
  cloud backups of messages, photos, private keys or sessions, analytics SDK, or remotely
  supplied executable UI. The only cloud backup is the opt-in, client-encrypted contacts
  backup (see "Account backup" below); it cannot restore a conversation or an identity.

## Threats and limitations

| Threat | Protection | Residual risk |
| --- | --- | --- |
| Database theft | Only account/profile metadata, password verifiers, public device material and, for opted-in users, one opaque encrypted backup blob | Usernames, chosen shared display names, public keys and password guessing remain exposed; use strong passwords. A stolen backup blob is sealed under a random 256-bit key (there is no passphrase to guess) and reveals only its size and update/expiry times |
| Redis/blob theft | Only Signal ciphertext or authenticated encrypted images, each with TTL | Recipient/sender IDs, lengths, timing and delivery relationships are visible |
| Backend compromise | Previously verified identities, client encryption and signed distribution keep content keys off-server | Server can deny/reorder delivery, lie about receipts, retain ciphertext, alter public keys before verification, and collect metadata |
| Network attacker | HTTPS/WSS, certificate validation, no cleartext fallback, E2EE | Traffic analysis and denial of service remain possible |
| Stolen locked phone | Android Keystore-wrapped private state, phone-unlocked access checks, encrypted local records, disabled backups | No separate app lock on an already-unlocked phone; OS exploits, weak screen lock, rooted device and forensic recovery are not defeated |
| Stolen session credential | One-hour access, single-use rotating 30-day renewal, hash-only server storage, current-device checks, logout/replacement revocation | A stolen renewal handle can extend unauthorized relay access while valid; neither token decrypts ciphertext or creates valid peer content |
| Malicious recipient | Local expiry and screenshot flags reduce accidental retention | Recipient can modify client, copy plaintext, screenshot on unsupported devices or photograph a screen; disappearing content is not DRM |
| Foreground-service notification | Persistent Android foreground-service notification with generic `Vanishr is active` title and End access action | Notification visibility does not prevent screenshots/cameras or a modified client from retaining received photos; Android/OEM notification behavior may vary |
| Replay/tampering | Signal ratchet replay detection and authentication, authenticated envelope IDs and deadlines, idempotent relay IDs | A server may still replay opaque delivery metadata; client rejects expired/duplicate envelopes |
| Identity substitution / MITM | Mandatory independent verification; pinned public identities; explicit reset on replacement | No key transparency service; verifying over this same relay is not verification |
| Push compromise | Generic notification and optional opaque hint; authenticated recipient resolution and verified local message required | Provider sees device token, timing and opaque reference; notifications can be suppressed, delayed or forged; hints alone grant no access |
| Accidental logging | No bodies, tokens, keys, user content, SQL parameters, access logs or crypto logging | Operators/APM/proxies can override configuration; deployment review is required |
| Expired data | Atomic expiry on every stored object; server TTL and client deadline checks; read deletion | Redis logical deletion does not prove physical RAM erasure; malicious operators, swap, dumps, snapshots and backups defeat retention promises |
| Device backups | Android backup/transfer disabled, no-backup directory, nonexportable vault key | OEM behavior and rooted-device tools need device testing; no iOS implementation yet |
| Account backup (opt-in) | Off by default; contacts and private nicknames only, never keys, sessions, messages, photos or credentials; AES-256-GCM under a random 256-bit recovery key the relay never sees; strict validation on restore; a contact is re-pinned only if the relay still reports exactly the identity the user verified; one blob per account, 90-day bounded retention, deleted on turn-off or account erasure; uploaded by a `dataSync` foreground service that never opens the vault | Anyone holding the recovery key plus the blob can read the contact list and nicknames (social graph); a lost key means no recovery; the key sits in the unlocked phone's protected vault, so a compromised unlocked phone can read it; a dishonest relay can delete the blob or serve an older once-verified copy and sees upload timing and size; restoring a contact whose identity changed still needs fresh verification; Play Console foreground-service and data-safety declarations must be updated before release |
| Supply-chain compromise | Pinned versions, signed client releases, separate release trust | Dependency pinning is not an audit; review advisories and verify release artifacts before production |

## Release gates

An independent security review, libsignal licensing review (AGPLv3; third-party use is
unsupported), dependency review, real-device secure-storage/backup/screenshot tests,
push-provider configuration, TLS provisioning, operational deletion verification,
and abuse/load testing are required before handling real private conversations.
No test can mathematically prove a malicious server will never keep ciphertext or
that arbitrary clients never upload plaintext; tests establish the trusted client's
encryption boundary and the shipped relay's behavior. Do not advertise stronger guarantees.