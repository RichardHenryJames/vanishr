# Relay API and schema

All endpoints require HTTPS; WSS is used for events. JSON rejects unknown
properties. Binary values in JSON use standard Base64. UUIDs are canonical
strings; timestamps are Unix milliseconds. `Cache-Control: no-store` applies
to every response. No cookies, query-string bearer tokens, CORS browser client,
multipart uploads, plaintext content fields, or permanent media URLs.

Authentication header: `Authorization: Bearer <43-character random token>`.
`ENROLL` tokens last five minutes and can only enroll a device, inspect identity,
or log out. `DEVICE` access tokens last 60 minutes; every request checks current
device generation. Device sessions also have a rotating 30-day renewal token,
which cannot be used as an access bearer. Server stores SHA-256 token digests,
never raw bearer or renewal tokens. Rotation atomically retires the previous
access/renewal pair and creates both bounded replacements. Redis remains
nonpersistent, so a Redis reset requires sign-in again.
Passwords are 16-64 characters, at most 72 UTF-8 bytes, hashed using BCrypt cost
12. Passwords are authentication secrets carried by TLS, not E2EE content.

Authentication attempts (`/auth/register`, `/auth/login`, `/auth/google`) share
a ten-per-minute source-IP budget. Google challenges have a separate ten-per-minute
budget. Authenticated `/auth/me` and `/auth/logout` do not consume either budget;
the overall IP/device limits still apply. Password login also retains its separate
five-per-minute username limit. A 429 response means retry later, not device loss.
Renewal has a separate 30/minute source-IP and 6/minute credential-digest limit,
independent of login attempts. No access bearer is required or sent on renewal.

## Endpoints

| Method / Path | Authorization | Input / response | Retention and deletion |
| --- | --- | --- | --- |
| `GET /health` | Public | `{status:"up"}` | None |
| `POST /auth/register` | Public, IP rate limit | `{handle,password}` -> token object, 201 | Account/BCrypt verifier persists; enrollment token 5 min |
| `POST /auth/login` | Public, IP + handle limits | `{handle,password,deviceId?}` -> token object | 5 min enrollment or 60 min device token |
| `POST /auth/refresh` | Renewal credential, separate rate limits | `{refreshToken,nextRefreshToken?}` -> device token object | Single-use rotation, 60 min access + 30 day renewal; validates current account/device generation |
| `POST /auth/google/challenge` | Public, IP rate limit; provider configuration required | `{deviceId?}` -> `{id,nonce,clientId,expiresAt}` | One-use challenge, 5 min |
| `POST /auth/google` | Public, IP rate limit; Google token verification | `{challengeId,idToken}` -> `{session,handle}` | Consumes challenge on the first attempt; normal enrollment/device token TTL |
| `GET /auth/me` | ENROLL or DEVICE | `{userId,deviceId}` | None added |
| `POST /auth/logout` | ENROLL or DEVICE | Empty -> 204 | Atomically deletes current access/renewal pair, then removes device presence/last-seen and push records; preserves registered device and queued ciphertext |
| `DELETE /account` | Fresh ENROLL owner only; DEVICE rejected | `{confirmation:"DELETE",deletionProof?:43-char random handle}` -> 204 | Permanently erases the authenticated account after scoped cleanup; retryable failures never report completion |
| `POST /account/deletion/status` | Scoped deletion proof, not an ordinary bearer | `{deletionProof}` -> `{userId,state,expiresAt}` | `PENDING` or `DELETED`; fixed proof lifetime <=24h; status reads do not renew it |
| `POST /account/deletion/retry` | Scoped deletion proof | Same request/status response | May only retry the already-authorized deletion, never log in, enroll or select another account |
| `POST /devices` | ENROLL, account owner | `{deviceId,identityKey,replaceExisting}` -> device token, 201 | Same device/key with `replaceExisting:false` resumes without changing generation/prekeys; a different device/key needs explicit replacement; consumes enrollment token |
| `POST /devices/push` | DEVICE owner | `{token,routeHints?}` -> 204 | Provider token and routing capability in Redis, atomic 24h TTL; omitted/false capability retains legacy event-only pushes |
| `DELETE /devices/push` | DEVICE owner | Empty -> 204 | Removes push token and current routing reference immediately |
| `POST /notifications/resolve` | DEVICE owner, 20/min/device | `{reference}` -> `{userId,deviceId,conversationId,messageId,expiresAt}` | Recipient-bound opaque reference; at most one mapping/device, atomic <=5min/message-deadline TTL; 404 when missing, wrong, expired or superseded |
| `GET /users/{handle}` | DEVICE | `{userId,deviceId,identityKey}` | No new state; account lookup exposes chosen handle |
| `GET /users/id/{userId}` | DEVICE | Same public contact object | No new state |
| `GET /account/username` | DEVICE owner | `{userId,handle}` | Current account username |
| `PATCH /account/username` | DEVICE owner; 5/min/account | `{handle}` -> `{userId,handle}` | Updates only authenticated account; database uniqueness; no device/key changes |
| `GET /account/profile` | DEVICE owner | `{userId,handle,displayName}` | Display name is nullable until explicitly saved |
| `GET /account/type` | DEVICE owner | `{userId,userType}` | Current `USER` or `ADMIN` metadata for the authenticated account only; no client write endpoint or extra permissions |
| `GET /account/admin-contacts?after=UUID` | DEVICE owner | `{userId,admin,contacts,nextAfter}` | Official-admin introductions for this account only; at most 64 enrolled contacts per page |
| `GET /account/admin-contacts/{peerId}` | DEVICE owner | Same page shape, at most one contact | Resolve one authorized introduction before establishing an automatic direct chat |
| `PATCH /account/profile` | DEVICE owner; 10/min/account | `{displayName}` -> profile | Updates only authenticated account; 1-40 characters, not unique |
| `GET /account/blocks` | DEVICE owner | UUID array, at most 512 | Owner's durable block preferences only |
| `PUT /account/blocks/{peerId}` | DEVICE owner | Empty -> 204 | Blocks direct interaction in either direction, revokes affected queues/photo access; does not remove shared-group history |
| `DELETE /account/blocks/{peerId}` | DEVICE owner | Empty -> 204 | Removes only caller's block; never restores payloads or identity trust |
| `PUT /account/backup` | DEVICE owner, 6 writes/min/device, body 64 B-512 KiB | `application/octet-stream` client-encrypted blob -> `{exists,size,updatedAt,expiresAt}` | One blob per account; replaces the previous one. Expiry is set to 90 days from this write in the same statement (database CHECK enforces the bound); the minute cleanup removes expired rows; account erasure cascades. The relay checks size only and never interprets the bytes |
| `GET /account/backup` | DEVICE owner, 12 reads/min/device | Encrypted octet stream | 404 `not_found` when absent or expired; reads never renew expiry |
| `GET /account/backup/status` | DEVICE owner, shared read limit | `{exists,size,updatedAt,expiresAt}` | Size and timestamps only |
| `DELETE /account/backup` | DEVICE owner, shared write limit | Empty -> 204 | Idempotent immediate deletion |
| `POST /safety/reports` | DEVICE reporter | `{targetId,reason,messageId?,groupId?}` -> `{id,expiresAt}`, 201 | Metadata only; five reports per account per 24h; atomic TTL <=30 days |
| `GET /safety/reports` | Current ADMIN and permanent pin | At most 50 report records | Earliest expiry first; reads do not renew retention |
| `DELETE /safety/reports/{reportId}` | Current ADMIN and permanent pin | Empty -> 204 | Removes a reviewed report idempotently |
| `GET /users/id/{userId}/profile` | DEVICE | `{userId,handle,displayName}` | Shared account metadata; private nicknames are never returned |
| `POST /keys` | DEVICE owner | `{keys:[PublicBundle,...]}` -> 204 | 1-32 per request, at most 256 available; 24h public-key TTL; cleanup every minute |
| `GET /keys` | DEVICE owner | `{remaining,fallbackSupported,fallbackKeyId,fallbackExpiresAt}` | One-time count plus active fallback ID/deadline; zeroes if absent; old clients ignore added fields |
| `PUT /keys/fallback` | DEVICE owner, shared key-upload limit | `{key:PublicBundle,expiresAt}` -> 204 | One public fallback/device; fixed <=30-day TTL at creation; identical retries do not extend expiry; strictly newer IDs rotate |
| `POST /keys/{userId}/claim?fallback=true` | DEVICE, claim limit | Empty -> PublicBundle | Atomically consumes one-time keys first; opted-in senders may receive active fallback without consuming it; absent/false flag preserves legacy behavior; 409 if no usable key |
| `POST /messages` | DEVICE sender; active recipient | SendRequest -> Status, 201 | Atomic ciphertext + deadline; ID retries must match exactly; no lifetime extension |
| `GET /messages/pending` | DEVICE recipient only | Array of at most 50 Message records | Does not consume; expired IDs are pruned |
| `GET /messages/status?ids=id1,id2` | DEVICE sender only | Array of Status; at most 50 requested IDs | Receipts last only until original deadline; unauthorized IDs omitted |
| `POST /messages/{id}/delivered` | DEVICE recipient only | Empty -> Status | Removes inbox index; deletes timed payload/blob; view-once remains until read/expiry |
| `POST /messages/{id}/read` | DEVICE recipient only | Empty -> Status | Immediately deletes payload/blob; bounded receipt remains |
| `DELETE /messages/{id}` | DEVICE sender or recipient | Empty -> Status | Immediately deletes payload/blob; records DELETED until deadline |
| `PUT /media/{id}?recipientId=...&recipientDeviceId=...&expiresAt=...` | DEVICE sender; active recipient | `application/octet-stream` ciphertext -> `{id}`, 201 | Max 2 MiB + 16 bytes; detached TTL <=5 min; attachment sets message deadline |
| `GET /media/{id}` | DEVICE bound recipient, attached live message only | Encrypted octet stream | No public URL; inaccessible after message deletion or expiry |
| `DELETE /media/{id}` | DEVICE uploading sender, unattached only | Empty -> 204 | Deletes detached upload; attached media must be deleted via message |
| `GET /events` (WSS upgrade) | DEVICE, bearer header at handshake | Server sends `{event:"new_message"}` only | One socket per device; token/generation rechecked; no payload or sender in event |
| `POST /presence` | DEVICE, live WSS, 45/min/device | `{contacts:[{userId,deviceId,identityKey}],typingTo?,typingForMillis,lastSeen?}` -> `[{peer,onlineForMillis,typingForMillis,lastSeenAgoMillis?}]` | Mutual pinned identities; up to 128 unique non-self contacts; atomic 12s online TTL, typing <=5s; capable clients retain one latest activity/audience record for <=24h |

All message/media bodies crossing this boundary are ciphertext; public-key and
auth endpoints intentionally handle public/authentication material. The relay
cannot cryptographically prove an arbitrary malicious client submitted real
ciphertext. It has no plaintext message field or decrypt functionality, and
tests demonstrate the official client's encryption-before-upload path.

## Account type

Migration V5 adds `accounts.user_type`: non-null `USER` (default) or `ADMIN`,
enforced by a PostgreSQL check constraint. Existing accounts retain their UUIDs,
handles, authentication mappings and device keys. Username changes update only
`handle`, not `id` or `user_type`; former handles are not an identity history.

Migration V6 permits at most one `ADMIN` and permanently pins the existing sole
admin's UUID in the singleton `admin_identity` table. Multiple existing admins
make migration fail transactionally; no account is chosen or demoted automatically.
With no existing admin, the pin stays empty and nobody can acquire the role until
a trusted database operator explicitly pins an inspected existing UUID and enables
that account in one bounded transaction. Registration never initializes the pin.
Once pinned, it cannot be changed, deleted or truncated through normal SQL.
The pinned UUID cannot be changed. An operator may
revoke its role, but the pin remains and only that same account can be re-enabled.
From migration V8, authenticated account erasure may delete the admin's account
data while preserving the UUID-only reservation. That UUID cannot be reinserted
or reassigned, so erasure does not create a replacement-admin opportunity.

`GET /account/type` reads the current database role and permanent pin together for
the authenticated enrolled account. An `ADMIN` row without a matching pin fails
closed with 503 `admin_identity_unavailable`, including on privileged requests.
It has no target account parameter, and returns no other account fields.
No profile, registration, login or username payload can set a role. Contact lookup
does not expose it. There is no promotion, replacement-admin or role-transfer API.
Reusing the admin's old username does not confer any privilege.
No permission bypass is enabled by this metadata alone.
requires the addressed owner's authenticated client to accept each bounded session.
The current client does so automatically for eligible requests; this is not a
per-request human approval and remains a Play consent/permission review concern.

## Account erasure and safety

Deletion requires account-only password or Google reauthentication, producing
a new five-minute ENROLL token. A remembered/device session cannot authorize
deletion, and the body cannot select an account. Before sending, the trusted
client commits a separate 256-bit deletion proof to the account's encrypted
vault. The relay retains only its digest and scoped receipt with a fixed
24-hour deadline. Its status/retry endpoints permit confirmation or completion
of that operation only; possession grants no chat, profile or enrollment access.
An unknown/expired proof is not evidence that deletion succeeded.

An independently committed `DELETING` state disables ordinary account use
before cleanup. Cleanup failures return an error and preserve a retry path,
never a successful partial deletion. A fresh account-only login may authorize
retry for a still-existing DELETING account. The client instead prefers its
saved proof after an interrupted response so Google sign-in cannot silently
recreate an erased account. Confirmed server deletion precedes owner-specific
local key/partition erasure; other saved accounts remain intact. Pending local
cleanup is retried while unlocked without claiming that an arbitrary 401/404
means success.

Android accepts at most 30 seconds of phone/server clock difference at the upper
bound of a fresh deletion challenge/credential, consistently with Google
challenge preparation. Already expired credentials are rejected, and the
saved credential deadline is capped to its original expiry or five local
minutes, whichever is earlier. This is client-side validation only; the
relay's existing five-minute enrollment lifetime and proof TTL are unchanged.
The client offers explicit account-bound confirmation retry for failed
reauthentication, and uses the existing cleanup-only path if deletion is
already pending.

Cleanup removes the account's profile/auth mapping, devices, public prekeys,
block relationships and introductions, direct queued payloads and attributable
media, auth/renewal credentials, push/presence/routing records and membership.
Owned groups close; other groups rotate membership state. Other recipients'
already delivered/shared copies and consumed replay markers retain their
original bounded deadlines; this is not remote erasure of recipients' devices.
An unattributable legacy detached upload can return `503 legacy_media_pending`
until its original <=5-minute upload deadline, rather than deleting another
account's data. The immutable admin UUID reservation and short-lived deletion
receipt are distinct from retained account profile/auth data.

Reports accept only `SPAM`, `HARASSMENT`, `SEXUAL_CONTENT`, `CHILD_SAFETY`,
`IMPERSONATION`, `THREATS` or `OTHER`, never free text/images. Each record contains
`id,reporterId,targetId,reason,messageId,groupId,createdAt,expiresAt`. Optional
context must be accessible to the reporter; a group-only report can identify
current participants without claiming expired message evidence. Submission and
review require a currently enabled pinned admin, otherwise `503
safety_review_unavailable` is returned. The queue holds at most 10,000 reports.
Account erasure removes reports authored by or targeting that account; erasing
the admin clears the undeliverable queue. Human moderation remains an operator
duty; the queue and review button alone are not a completed safety process.

Blocking is enforced at the relay for old/modified clients too, including direct
message/media/key/profile/presence/photo routes, introductions and new group
invitations/controls. It does not erase existing shared-group copies. The trusted
client hides blocked senders' group content without downloading their photos.
Unblocking restores neither consumed ciphertext nor pinned direct-contact trust.
PostgreSQL coordination serializes cleanup with other relay requests; concurrent
operations can return `503 safety_operation_in_progress` and require retry.

## Official-admin introductions

The relationship is bilateral. After device enrollment, the new user receives
the pinned official administrator and the administrator receives the new user
on the next background directory sync, without requiring a message. Results are
newest-first and paginated; clients persist validated introductions so older
pages remain in the chat list.

Migration V7 atomically links newly inserted accounts to the permanent admin pin;
it does not backfill existing accounts or change account roles. Password and
Google registrations use the same database trigger. Repeated Google sign-in
does not create another link. Accounts without an enrolled device are not
returned to the admin until enrollment completes.

`admin` is `{userId,deviceId,identityKey}` for the current pinned admin.
Each contact is `{userId,deviceId,identityKey,handle,displayName}`. Ordinary
introduced users receive only that admin; the current admin receives only the
new accounts linked to it. Query parameters cannot select another caller.
The single-peer route returns an empty list for an unrelated account.
Missing/revoked admin roles disable discovery without transferring the pin.
Neither response contains email, passwords, tokens or private keys.

Pages are ordered by immutable peer UUID. `nextAfter`, when present, is the last
UUID in the current 64-entry page. Both routes share a 30-request/minute/device
rate limit. Clients show further pages explicitly rather than creating an
unbounded collection of local encryption sessions.

These are authenticated discovery responses, not independent identity proofs.
The signed official client additionally validates its compiled-in admin origin,
account/device IDs and public fingerprint. The admin's trusted client validates
its own pinned identity and treats new participants as relay-enrolled accounts.
It never overwrites an established peer identity automatically. Automatic
direct-chat trust does not satisfy independent-verification requirements for
group, profile-photo features. From 0.5.8 it does let the pair list each other for
presence; groups and profile photos still need independent verification.

| Method / Path | Contract |
| --- | --- |
| `GET /photo-events` (WSS) | Separate authenticated connection with the same generic wake event; does not replace `/events` or confer chat presence |
| `POST /remote-photos` | `{id,owner:{userId,deviceId,identityKey},key:PublicBundle}` -> session; ADMIN-only, three requests/min/device; requester photo connection and owner's initial chat connection required |
| `GET /remote-photos` | Up to four current sessions for the authenticated device |
| `GET /remote-photos/{id}` | Participant-only session; expired/revoked/disconnected returns 410 |
| `POST /remote-photos/{id}/accept` | `{requester:{userId,deviceId,identityKey}}` -> accepted session; addressed owner only, live photo connection required |
| `POST /remote-photos/{id}/exchange` | `{acknowledge?,packet?:{id,expiresAt,type,ciphertext}}` -> `{packet?:{id,senderId,senderDeviceId,expiresAt,type,ciphertext}}`; both current participants and approval required; ciphertext <=65536 bytes |
| `DELETE /remote-photos/{id}` | Either participant stops access and removes queued ciphertext |

Session: `{id,requester,owner,accepted,expiresAt,key}`. The public bundle identity
must match the requester's registered key; libsignal verifies its signatures on
the client. Session deadline is at most fifteen minutes, with only two minutes
to accept a request. The accepted deadline is never extended. Each packet is
bounded to sixty seconds and to the original session deadline. One packet per
direction and at most 10000 retry digests per sender/session bound relay memory;
identical retries do not redeliver acknowledged packets. There is no gallery-count
limit or full-gallery upload. A separate 600/min/device, 1200/min/IP exchange
budget prevents photo chunks from consuming normal chat request counters.

Normal chat may disconnect while the owner's foreground service maintains
its photo channel. Role, device generation, authentication and both photo
connections are rechecked, not cached as a permanent approval flag. Owner Android
photo/notification permissions, a configured secure screen lock and End access
remain mandatory. In the current client, automatic acceptance/startup require an
unlocked phone, but an already accepted owner session can continue while that
phone is locked. Viewer lock or owner lock before acceptance still ends access.
Normal chat vault access remains unlock-only; server authorization is unchanged.
Client metadata/thumbnails/original chunks use separate official Signal sessions,
not plaintext JSON photo paths or a custom encryption algorithm.

## Online, typing and last seen

Only mutually listed direct contacts with matching pinned device/key identities
and live authenticated websocket connections receive status. Mutual listing
includes the official admin and each introduced account from 0.5.8 (the relay
treats them like any other pair; older clients simply do not list each other).
Reconnecting cannot
revive an old heartbeat. Redis retains a session digest and connection reference,
not a bearer token. Online/typing durations are milliseconds remaining. Clients
subtract request time and keep status only in memory.

With `lastSeen:true`, a heartbeat also atomically replaces one latest-activity
record with a 24-hour TTL, pinned audience and current device generation. If an
authorized peer is offline, the response has zero online/typing durations and
`lastSeenAgoMillis` in `[0,86400000)`, measured by the server. Online responses have
no last-seen value. This relative age is not a client-supplied Unix timestamp.
Reading never refreshes a peer's record; expired, future-dated, changed-generation
and non-mutual records are omitted. An offline peer need not have a live access
token, but the reader still needs its own active device session and websocket.
Successful sign-out deletes the device's latest activity. The atomic heartbeat
checks that its session still exists before creating either record.

Omitted/false `lastSeen` preserves the legacy response contract (online peers
only) and removes the caller's last-seen record. Empty audiences also remove it;
nonempty audience changes replace prior sharing permissions on the next heartbeat.
Android 0.4.1 publishes/requests the capability, prefers Typing then Online then
Last seen, and displays relative minutes/hours. Response snapshots last at most
12 seconds and never past the original 24-hour deadline, using elapsed realtime
and conservatively accounting for the request duration. A local connection loss
or expired online snapshot does not imply the peer went offline.

`typingTo` must belong to `contacts` with `typingForMillis` from 1 through 5000;
null/absent typing requires zero. Empty audiences stop sharing on the next
heartbeat. Unknown fields, duplicate/self recipients, invalid key encoding,
oversized audiences and unenrolled callers fail closed. Presence does not wake
FCM, queue chat content or appear in account lookup. This temporary audience and
activity metadata is visible to the relay over TLS, not end-to-end encrypted.

## Notification routing

Legacy FCM data is `{event:"new_message"}`. A client that registers
`routeHints:true` may receive `{event:"new_message",reference:"..."}` for direct
or group messages. The reference is a 43-character Base64url encoding of 32
random bytes; it does not encode a message, sender, chat or account identifier.
The provider payload has no notification object, keeps a 60-second TTL and the
existing single `new_message` collapse key. Control/invitation wake events may
remain generic. Current routing metadata is available only through the
authenticated resolution endpoint, never through a public URL or the push data.

The relay stores the reference digest, not the raw value. Its destination expires
within five minutes and never after the triggering message. A newer reference
replaces the previous destination. The client treats the result as an untrusted
hint: it must sync and find a matching verified, unexpired incoming unread entry
before opening a chat. Resolution does not consume messages or view-once content.
Opt-out/sign-out remove the mapping; device replacement fails current-session
authorization. No new durable database table or plaintext content field is added.

## Private profile packets

These DEVICE-only endpoints relay Signal ciphertext separately from messages and
groups. They never return a profile photo or URL through username/account lookup.
The receiving client enforces mutual saved-contact verification before sharing.

| Method / Path | Contract |
| --- | --- |
| `POST /profile/packets` | `{id,recipientId,recipientDeviceId,expiresAt,type,ciphertext}`; type 2/3, ciphertext 32-65536 bytes, current recipient device, deadline at most 24h, 30 sends/minute/device |
| `GET /profile/packets` | Up to 16 packets addressed only to the authenticated device; at most 64 queued packets/device; sender account/device added by the relay |
| `DELETE /profile/packets/{id}` | Recipient-only acknowledgement; removes payload and inbox entry, retains a bounded retry tombstone until the original deadline |

Packet and inbox writes receive bounded TTLs atomically. Conflicting retries fail;
acknowledged packets cannot be resurrected, and retries cannot extend deadlines.
No plaintext profile image, private contact graph or new database table is added.

## Group endpoints

All routes below require a DEVICE session. The relay knows group membership but
does not receive the group name, plaintext messages or sender keys. Group IDs are
client-generated UUIDs; membership revisions/epochs are assigned by the relay and
authenticated by the owner over Signal. A revision mismatch returns 409
`group_changed`. A different enrolled key returns `group_identity_changed`.

| Method / Path | Contract |
| --- | --- |
| `GET /groups` | Current device's memberships/invitations; at most 40 records including bounded closed-group tombstones |
| `POST /groups` | `{id}`; creates owner membership, up to 20 groups/invitations per account |
| `GET /groups/{id}` | Member/invitee-only snapshot `{id,ownerId,revision,epoch,closed,members}` |
| `POST /groups/{id}/invitations` | Owner-only `{revision,members:[{userId,deviceId,identityKey}]}`; max 200 total including owner/invitees; invitations expire in 24h |
| `POST /groups/{id}/accept` | Invitee-only `{revision}`; activates membership and changes revision/epoch |
| `DELETE /groups/{id}/members/{userId}?revision=...` | Owner removes a member, or member declines/leaves; active removal changes revision/epoch |
| `DELETE /groups/{id}?revision=...` | Owner closes group; metadata tombstone removed within 24h plus cleanup interval |
| `POST /groups/{id}/keys` | `{revision,users:[UUID]}`; up to 32 members' one-time public bundles, unavailable bundles omitted |
| `POST /groups/{id}/controls` | `{revision,packets:[ControlSend]}`; up to 32 encrypted Signal controls, each recipient-bound and <=24h; only owner can address invited members |
| `GET /groups/{id}/controls` | Up to 64 controls addressed to the current member/invitee device |
| `DELETE /groups/{id}/controls/{packet}` | Recipient acknowledges control; TTL-bounded retry tombstone prevents resurrection |
| `POST /groups/{id}/messages` | `{id,epoch,revision,expiry,expiresAt,ciphertext,mediaId?,media?}`; optional encrypted image <=2 MiB+16 bytes, 3 MB request bound |
| `GET /groups/{id}/messages` | Up to 32 queued messages for the current active recipient; delivery removes an item from this list |
| `GET /groups/{id}/status` | Sender-only `{id,expiresAt,recipients,delivered,read,state}` aggregates, <=256 live receipts |
| `POST /groups/{id}/messages/{message}/{delivered\|read\|delete}` | Per-member acknowledgement; sender delete revokes all remaining relay copies |
| `GET /groups/{id}/messages/{message}/media` | Encrypted shared image; only an active original recipient whose copy has not been consumed |

ControlSend: `{id,recipientId,recipientDeviceId,epoch,revision,expiresAt,type,ciphertext}`.
Controls use Signal message type 2/3; ordinary group messages use official sender-key
ciphertext. Membership changes serialize with sends using a database row lock.
Queue capacity, rate-limit and memory-pressure responses do not enable persistence.
Read counts are relay metadata, not cryptographic evidence of viewing.

## Formats

Token: `{userId,deviceId,accessToken,expiresAt,refreshToken,refreshExpiresAt}`.
Enrollment deviceId and refreshToken are null, with refreshExpiresAt zero.
Renewal handles are 43-character Base64url encodings of 32 random bytes. The
Android client securely generates and commits nextRefreshToken before rotation;
after an interrupted response it can retry that prepared handle once. A reused
or colliding replacement is rejected. A client that omits the optional next
handle receives a server-generated replacement but must manage response loss.
Do not log or paste token responses into bug reports.

Username/profile update bodies contain no target user ID. Unknown fields are
rejected; ownership is derived from the authenticated session. Usernames must
match `[a-z0-9_-]{3,32}` and are globally unique; an unavailable username returns
409 `account_unavailable`. Renaming does not alter account UUIDs, device generation,
public keys or queued ciphertext. The old username becomes available; clients must
keep existing contacts bound to immutable UUIDs rather than resolving that old name.
Display names are trimmed, bounded to 40 characters and reject control characters.
They are shared, server-visible metadata and may be duplicated. A private contact
name is strictly local client data and is not accepted by any relay endpoint.

From relay schema V11 / client 0.5.3, first-time Google authentication generates
`word-word-dddd` handles: one independently random word from each of two
512-entry English lists, followed by a random integer from 1000 through 9999.
This gives 2,359,296,000 candidates. PostgreSQL uniqueness applies to Google and
password users alike. Conflicts retry within a bounded 16-attempt creation
transaction; concurrent requests for one Google subject return the same
persisted account. Exhaustion returns 503 `google_sign_in_unavailable`, not
someone else's account or an unpersisted handle. Returning accounts keep their
current handle, including legacy and user-renamed handles. No Google name/email
or account UUID is used to construct the readable handle.

V11 only broadens the username CHECK constraint to accept hyphens; it does not
rename data or change account UUIDs, keys, admin pins or roles. Clients before
0.5.3 do not support hyphenated handles consistently; update both participants,
especially the admin and group members, before relying on these names.

PublicBundle:
`{registrationId,preKeyId,preKey,signedPreKeyId,signedPreKey,signedPreKeySignature,identityKey,kyberPreKeyId,kyberPreKey,kyberPreKeySignature}`.
Keys/signatures are Base64; registration ID 1-16380, positive key IDs, EC/identity
keys 33 bytes, EC signatures 64 bytes, Kyber public key 1569 bytes. One bundle
uses the same application key ID for all three prekeys. The recipient verifies
signatures in libsignal; the server deliberately does not load client crypto.
An opted-in fallback response instead has `preKeyId:0` and null/absent `preKey`;
signed and Kyber IDs remain equal and positive. This shape is rejected by the
one-time upload endpoint. `PUT /keys/fallback` requires this shape, the enrolled
owner's identity and a future absolute deadline within 30 days. It cannot extend
an existing key's deadline or replace a newer ID with an older one
(`409 fallback_key_changed`). GET/claim operations do not renew retention.
Group key-batch claims also accept `?fallback=true`; existing membership,
revision, identity and block authorization still apply.

SendRequest:
`{id,recipientId,recipientDeviceId,expiry,expiresAt,type,ciphertext,mediaId?}`.
`type` is Signal 2 or 3; ciphertext 32-65536 bytes. Sender fields are derived from
authentication, never accepted from the request. The encrypted inner envelope
also binds IDs/devices/expiry/media to prevent relay metadata substitution.

Message adds `{senderId,senderDeviceId,createdAt}` to the routing/body fields.
Status is `{id,state,expiresAt}` where state is QUEUED/DELIVERED/READ/DELETED.
READ/DELETED cannot regress. Delivery/read receipts are not E2EE attestations.

Errors: `{error:"stable_code"}`, with no supplied value, raw exception, body or
secret. Relevant statuses: 400 invalid input, 401 invalid/expired token, 403 wrong
scope, 404 missing/unauthorized object, 405 unsupported method, 409 conflict or exhausted prekeys, 410
expired, 413 oversized, 426 TLS required, 429 rate limit, 503 unavailable.
HTTPS port refuses actual cleartext at the TLS layer; 426 is defense in depth.

## Minimum durable schema

`accounts(id UUID PK, handle VARCHAR(32) UNIQUE, password_hash VARCHAR(100), google_subject VARCHAR(255) UNIQUE, display_name VARCHAR(40), user_type VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (user_type IN ('USER','ADMIN')))`

V8 adds `accounts.deletion_state` constrained to `ACTIVE` or `DELETING`.

`admin_identity(singleton BOOLEAN PK DEFAULT TRUE CHECK (singleton), user_id UUID UNIQUE)`

V8 replaces the account foreign key with guards that allow verified erasure,
require an existing active account at initial pin creation, and forbid reuse or
reassignment of an erased reserved UUID.

`account_blocks(blocker_id UUID FK accounts(id) ON DELETE CASCADE, blocked_id UUID FK accounts(id) ON DELETE CASCADE, PK(blocker_id,blocked_id), CHECK (blocker_id <> blocked_id))`

`admin_introductions(user_id UUID PK FK accounts(id) ON DELETE CASCADE, admin_id UUID FK admin_identity(user_id), CHECK (user_id <> admin_id))`

Introductions are durable registration/contact metadata, not expiring message
payloads. The insert trigger never chooses a new admin, and existing account
rows are not backfilled.

The partial unique index on `accounts(user_type) WHERE user_type = 'ADMIN'`
rejects multiple admins, including concurrent writes. Triggers require every
admin role to match the permanent singleton pin and reject pin mutation/removal;
the restrictive foreign key prevents deleting or changing the pinned UUID.

`devices(id UUID PK, user_id UUID UNIQUE FK, identity_key VARCHAR(64), auth_version UUID, registered_at TIMESTAMPTZ)`

`prekeys(device_id UUID FK, id INTEGER, public_bundle JSONB, expires_at TIMESTAMPTZ, PK(device_id,id))`

`fallback_prekeys(device_id UUID PK/FK, id INTEGER, public_bundle JSONB, created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ)`

V12 adds only the public fallback table. Its deadline is checked against creation
time, expires within 30 days, and is deleted by the minute cleanup or the device's
cascading deletion. No account, device, admin pin or existing key is rewritten.

`account_backups(user_id UUID PK/FK accounts ON DELETE CASCADE, ciphertext BYTEA, updated_at TIMESTAMPTZ, expires_at TIMESTAMPTZ)`

V13 adds the opt-in account backup table. `ciphertext` is 64 bytes to 512 KiB and is
sealed on the client with a recovery key the relay never receives
([ENCRYPTION.md](ENCRYPTION.md#8-account-backup-opt-in)). Database CHECK constraints
bound the size and require `expires_at` to be after `updated_at` and at most 2,160 absolute
hours (90 days) later, so a daylight-saving session time zone cannot stretch the bound; the minute cleanup deletes expired rows and account erasure cascades. The table
has no plaintext metadata column, and the schema guard test pins its exact columns.
Deploy V13 before publishing an app version that offers backup; older clients never
call these routes. No existing row is rewritten.

Google authentication retains a stable Google subject-to-account mapping, not
Google email, name, photo, access tokens or refresh tokens. It does not merge
accounts with password accounts by email. `display_name` is set only by an explicit
authenticated profile edit; it is not imported from Google's token.

`private_groups(id UUID PK, owner_id UUID FK, revision BIGINT, epoch UUID, closed_at TIMESTAMPTZ)`

`group_members(group_id UUID FK, user_id UUID FK, device_id UUID, identity_key VARCHAR(64), state VARCHAR(8), invited_until BIGINT, PK(group_id,user_id))`

No message-content, attachment, media-key, private-key, profile, contact,
search, content-index or permanent receipt tables. The one stored user blob is the
opt-in client-encrypted `account_backups` row, which the relay cannot read. The bounded
display-name field lives on `accounts`; no private contact-name mapping is stored in
plaintext. Deleting/replacing a device cascades its public prekeys. Account erasure is
owner-only with fresh authentication. No message-content recovery API is provided.

## Ephemeral Redis keys

| Prefix | Data | Maximum TTL |
| --- | --- | --- |
| `m:` | Opaque encrypted message and delivery routing | Original deadline, <=24h |
| `b:` | Encrypted image and sender/recipient/message binding | Detached <=5min; attached original deadline |
| `r:` | IDs, policy, state, request digest; no content | Original deadline |
| `inbox:`, `outbox:` | Expiry-scored delivery/receipt IDs | <=24h; expired entries removed on access |
| `auth:` | Actor and device generation under token hash | 5/60min |
| `google:` | Device binding, nonce and deadline under a hashed challenge ID | 5min; consumed on the first sign-in attempt |
| `push:` | Provider device token | 24h |
| `rate:` | Hashed-IP/account or device counters | 60s |
| `gm:`, `gb:` | One shared group ciphertext/image | Original deadline, <=24h |
| `gr:`, `gi:` | Per-member states and expiry-scored group message IDs | Original deadline / <=24h index |
| `gc:`, `gcr:`, `gci:` | Recipient-bound encrypted Signal controls, retry digests and index | <=24h, bounded at creation |
| `rps:`, `rpsi:`, `rps-ended:` | Approved-photo session/public bundle, participant indexes and revocation marker | <=15min; pending approval logically <=2min |
| `rpq:`, `rpr:` | Single queued encrypted photo packet/direction and bounded retry digests | Packet <=60s; digests <=15min/session deadline |

Runtime requires nonpersistent single-node Redis. Lua scripts atomically attach
media and enqueue/acknowledge delivery, never reset content lifetime on retries.
Memory pressure fails requests; it does not silently turn on disk persistence.
Receipts and identifiers are metadata, not encrypted message history.