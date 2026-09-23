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
| `POST /devices` | ENROLL, account owner | `{deviceId,identityKey,replaceExisting}` -> device token, 201 | Same device/key with `replaceExisting:false` resumes without changing generation/prekeys; a different device/key needs explicit replacement; consumes enrollment token |
| `POST /devices/push` | DEVICE owner | `{token,routeHints?}` -> 204 | Provider token and routing capability in Redis, atomic 24h TTL; omitted/false capability retains legacy event-only pushes |
| `DELETE /devices/push` | DEVICE owner | Empty -> 204 | Removes push token and current routing reference immediately |
| `POST /notifications/resolve` | DEVICE owner, 20/min/device | `{reference}` -> `{userId,deviceId,conversationId,messageId,expiresAt}` | Recipient-bound opaque reference; at most one mapping/device, atomic <=5min/message-deadline TTL; 404 when missing, wrong, expired or superseded |
| `GET /users/{handle}` | DEVICE | `{userId,deviceId,identityKey}` | No new state; account lookup exposes chosen handle |
| `GET /users/id/{userId}` | DEVICE | Same public contact object | No new state |
| `GET /account/username` | DEVICE owner | `{userId,handle}` | Current account username |
| `PATCH /account/username` | DEVICE owner; 5/min/account | `{handle}` -> `{userId,handle}` | Updates only authenticated account; database uniqueness; no device/key changes |
| `GET /account/profile` | DEVICE owner | `{userId,handle,displayName}` | Display name is nullable until explicitly saved |
| `PATCH /account/profile` | DEVICE owner; 10/min/account | `{displayName}` -> profile | Updates only authenticated account; 1-40 characters, not unique |
| `GET /users/id/{userId}/profile` | DEVICE | `{userId,handle,displayName}` | Shared account metadata; private nicknames are never returned |
| `POST /keys` | DEVICE owner | `{keys:[PublicBundle,...]}` -> 204 | 1-32 per request, at most 256 available; 24h public-key TTL; cleanup every minute |
| `GET /keys` | DEVICE owner | `{remaining}` | None added |
| `POST /keys/{userId}/claim` | DEVICE, claim limit | Empty -> PublicBundle | Atomic consumption; 409 when exhausted; POST avoids cached/destructive GET |
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

## Online, typing and last seen

Only mutually listed direct contacts with matching pinned device/key identities
and live authenticated websocket connections receive status. Reconnecting cannot
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
match `[a-z0-9_]{3,32}` and are globally unique; an unavailable username returns
409 `account_unavailable`. Renaming does not alter account UUIDs, device generation,
public keys or queued ciphertext. The old username becomes available; clients must
keep existing contacts bound to immutable UUIDs rather than resolving that old name.
Display names are trimmed, bounded to 40 characters and reject control characters.
They are shared, server-visible metadata and may be duplicated. A private contact
name is strictly local client data and is not accepted by any relay endpoint.

PublicBundle:
`{registrationId,preKeyId,preKey,signedPreKeyId,signedPreKey,signedPreKeySignature,identityKey,kyberPreKeyId,kyberPreKey,kyberPreKeySignature}`.
Keys/signatures are Base64; registration ID 1-16380, positive key IDs, EC/identity
keys 33 bytes, EC signatures 64 bytes, Kyber public key 1569 bytes. One bundle
uses the same application key ID for all three prekeys. The recipient verifies
signatures in libsignal; the server deliberately does not load client crypto.

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

`accounts(id UUID PK, handle VARCHAR(32) UNIQUE, password_hash VARCHAR(100), google_subject VARCHAR(255) UNIQUE, display_name VARCHAR(40))`

`devices(id UUID PK, user_id UUID UNIQUE FK, identity_key VARCHAR(64), auth_version UUID, registered_at TIMESTAMPTZ)`

`prekeys(device_id UUID FK, id INTEGER, public_bundle JSONB, expires_at TIMESTAMPTZ, PK(device_id,id))`

Google authentication retains a stable Google subject-to-account mapping, not
Google email, name, photo, access tokens or refresh tokens. It does not merge
accounts with password accounts by email. `display_name` is set only by an explicit
authenticated profile edit; it is not imported from Google's token.

`private_groups(id UUID PK, owner_id UUID FK, revision BIGINT, epoch UUID, closed_at TIMESTAMPTZ)`

`group_members(group_id UUID FK, user_id UUID FK, device_id UUID, identity_key VARCHAR(64), state VARCHAR(8), invited_until BIGINT, PK(group_id,user_id))`

No message-content, attachment, media-key, private-key, profile, contact,
search, content-index or permanent receipt tables. The bounded display-name field
lives on `accounts`; no private contact-name mapping is stored. Deleting/replacing a device
cascades its public prekeys. Account erasure/recovery administration is not yet
an exposed product workflow; do not add a content-recovery API.

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

Runtime requires nonpersistent single-node Redis. Lua scripts atomically attach
media and enqueue/acknowledge delivery, never reset content lifetime on retries.
Memory pressure fails requests; it does not silently turn on disk persistence.
Receipts and identifiers are metadata, not encrypted message history.