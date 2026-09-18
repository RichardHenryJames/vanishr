# Relay API and schema

All endpoints require HTTPS; WSS is used for events. JSON rejects unknown
properties. Binary values in JSON use standard Base64. UUIDs are canonical
strings; timestamps are Unix milliseconds. `Cache-Control: no-store` applies
to every response. No cookies, query-string bearer tokens, CORS browser client,
multipart uploads, plaintext content fields, or permanent media URLs.

Authentication header: `Authorization: Bearer <43-character random token>`.
`ENROLL` tokens last five minutes and can only enroll a device, inspect identity,
or log out. `DEVICE` tokens last 60 minutes; every request checks current device
generation. Server stores SHA-256 token digests, never raw bearer tokens.
Passwords are 16-64 characters, at most 72 UTF-8 bytes, hashed using BCrypt cost
12. Passwords are authentication secrets carried by TLS, not E2EE content.

Authentication attempts (`/auth/register`, `/auth/login`, `/auth/google`) share
a ten-per-minute source-IP budget. Google challenges have a separate ten-per-minute
budget. Authenticated `/auth/me` and `/auth/logout` do not consume either budget;
the overall IP/device limits still apply. Password login also retains its separate
five-per-minute username limit. A 429 response means retry later, not device loss.

## Endpoints

| Method / Path | Authorization | Input / response | Retention and deletion |
| --- | --- | --- | --- |
| `GET /health` | Public | `{status:"up"}` | None |
| `POST /auth/register` | Public, IP rate limit | `{handle,password}` -> token object, 201 | Account/BCrypt verifier persists; enrollment token 5 min |
| `POST /auth/login` | Public, IP + handle limits | `{handle,password,deviceId?}` -> token object | 5 min enrollment or 60 min device token |
| `POST /auth/google/challenge` | Public, IP rate limit; provider configuration required | `{deviceId?}` -> `{id,nonce,clientId,expiresAt}` | One-use challenge, 5 min |
| `POST /auth/google` | Public, IP rate limit; Google token verification | `{challengeId,idToken}` -> `{session,handle}` | Consumes challenge on the first attempt; normal enrollment/device token TTL |
| `GET /auth/me` | ENROLL or DEVICE | `{userId,deviceId}` | None added |
| `POST /auth/logout` | ENROLL or DEVICE | Empty -> 204 | Deletes current token and device push token; preserves registered device and queued ciphertext |
| `POST /devices` | ENROLL, account owner | `{deviceId,identityKey,replaceExisting}` -> device token, 201 | Same device/key with `replaceExisting:false` resumes without changing generation/prekeys; a different device/key needs explicit replacement; consumes enrollment token |
| `POST /devices/push` | DEVICE owner | `{token}` -> 204 | Provider token in Redis, 24h; refreshed only by client |
| `DELETE /devices/push` | DEVICE owner | Empty -> 204 | Removes push token immediately |
| `GET /users/{handle}` | DEVICE | `{userId,deviceId,identityKey}` | No new state; account lookup exposes chosen handle |
| `GET /users/id/{userId}` | DEVICE | Same public contact object | No new state |
| `GET /account/username` | DEVICE owner | `{userId,handle}` | Current account username |
| `PATCH /account/username` | DEVICE owner; 5/min/account | `{handle}` -> `{userId,handle}` | Updates only authenticated account; database uniqueness; no device/key changes |
| `GET /account/profile` | DEVICE owner | `{userId,handle,displayName}` | Display name is nullable until explicitly saved |
| `PATCH /account/profile` | DEVICE owner; 10/min/account | `{displayName}` -> profile | Updates only authenticated account; 1-40 characters, not unique |
| `GET /users/id/{userId}/profile` | DEVICE | `{userId,handle,displayName}` | Shared account metadata; private nicknames are never returned |
| `POST /keys` | DEVICE owner | `{keys:[PublicBundle,...]}` -> 204 | 1-32 per request, at most 64 available; 24h public-key TTL; cleanup every minute |
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

All message/media bodies crossing this boundary are ciphertext; public-key and
auth endpoints intentionally handle public/authentication material. The relay
cannot cryptographically prove an arbitrary malicious client submitted real
ciphertext. It has no plaintext message field or decrypt functionality, and
tests demonstrate the official client's encryption-before-upload path.

## Formats

Token: `{userId,deviceId,accessToken,expiresAt}`. Enrollment deviceId is null.
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

No message, conversation, attachment, media-key, private-key, profile, contact,
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

Runtime requires nonpersistent single-node Redis. Lua scripts atomically attach
media and enqueue/acknowledge delivery, never reset content lifetime on retries.
Memory pressure fails requests; it does not silently turn on disk persistence.
Receipts and identifiers are metadata, not encrypted message history.