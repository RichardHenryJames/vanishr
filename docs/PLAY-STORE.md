# Google Play readiness: candidate 0.4.7

Status: **DRAFT / NOT READY TO SUBMIT**. Reviewed against the working tree and
official Google guidance on 2 October 2026. The candidate is **0.4.7, code 28**;
the current public development release remains **0.4.6, code 27** until the
0.4.7 artifacts and matching schema-10 relay are published. A locally built
bundle, public policy page, passing test or completed checklist is not Google
Play approval, legal certification or an independent security audit.

### Implementation and publication update

The currently published development release is **0.4.6/code 27** on the public
download site, with the matching schema-9 relay and
app/privacy/deletion/Terms pages. Candidate **0.4.7/code 28** adds bilateral
official-admin chat-list enrollment and requires the schema-10 relay.
The final Play-specific AAB passed signing and official bundle validation;
54 targeted Android tests, 42 relay/provider tests, six unit tests and live
synthetic-account safety checks passed. Its external APK updater is excluded.
Public verification covered all 30 served files, six pages, eleven redirects
and nine protected/missing paths. See [verification](VERIFICATION.md#release-046--play-candidate).

The remaining unchecked operator and Console gates below are still real.
**Automatic Remote Photos is unchanged and remains a submission blocker/risk;
this update is not Play approval or a certification that all policy gates pass.**
Nothing was uploaded to Play Console. The website publication checks below
are now demonstrated; the remaining declaration, account, signer enrollment,
moderation/mail handling and consent decisions belong to the account holder.

This document records concrete release gates and a Data safety **draft**, not
pre-approved Play Console answers. Reconcile it with the final client, relay,
merged release manifest, dependency inventory and operator practices before
submission. The account holder makes and signs the Console declarations.

## Public resources

| Purpose | Intended public URL |
| --- | --- |
| App and website Privacy policy | <https://vanishr-download.vercel.app/privacy/> |
| Account and associated-data deletion request | <https://vanishr-download.vercel.app/delete-account/> |
| Terms / community and acceptable-use standards | <https://vanishr-download.vercel.app/terms/> |
| Child-safety standards and escalation | <https://vanishr-download.vercel.app/terms/#child-safety> |
| Support, privacy, deletion and child-safety contact | <mailto:richardhenryjames64@gmail.com> |

The email above was supplied by the account holder. It is not evidence that the
mailbox is continuously monitored or that an independent moderation team exists.
Do not invent a company/legal entity, postal address, response SLA, age decision,
COPPA certification, GDPR-compliance claim or security-audit badge.

- [x] Static app-specific privacy, deletion and Terms templates exist under
  [download](../download/), with desktop/mobile/footer links, sitemap entries,
  canonical URLs and machine-readable discovery in [llms.txt](../download/llms.txt).
- [x] The deletion resource contains a real mailto request plus a copyable address
  and subject; no app reinstall, login, password, token or upload is needed to
  **request** deletion.
- [x] The [renderer](../scripts/render-website.ps1) includes both new routes in its
  page allowlist, checks canonical URLs and sitemap coverage, and generates CSP
  hashes for page JSON-LD. Existing consent-only analytics behavior is unchanged.
- [x] Candidate source defines policy version `2026-10-02`, the public support
  email and the Privacy/Terms/deletion URLs in
  [PlayPolicy](../android/app/src/main/java/app/vanishr/android/PlayPolicy.java).
  [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java)
  gates initial sign-in and chat access on explicit Terms acceptance and wires
  policy, deletion and support entry points. The summary describes automatic
  Remote Photos and the human admin; it does not change per-session behavior.
  This records source integration, not signed-build/runtime verification.
- [x] Publish only an audited static distribution after explicit approval.
  The 31-file bundle was audited and published; verification evidence is linked above.
- [x] Check each deployed URL anonymously, on mobile and without JavaScript:
  HTTPS, HTTP 200, no login/geoblock, readable HTML (not a PDF), correct canonical,
  working mailto/copyable email, and working `/index.html` redirects.
- [ ] Put the privacy and deletion URLs in their separate Play Console fields.
  Verify the candidate's sign-in/policy and My profile links, acceptance,
  cancellation and account-switching behavior in the final signed build. Keep
  the policy version consistent with the published Terms; source wiring alone
  does not demonstrate deployed URL availability or effective informed consent.
- [ ] Do not remove the renderer's older-release notice while the feed still
  advertises 0.4.5. The candidate controls require the matching updated relay;
  a website policy must not imply those APIs are already live.

## Blocking issue: automatic Remote Photos

**UNRESOLVED: prominent disclosure, informed consent, broad-photo permission and
foreground-service eligibility. Google may reject this implementation.**

The user expressly requested retaining the existing automatic approvals.
[MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java)
currently sets `autoAllow = true`; the older per-session Allow dialog is not the
active branch. Do not describe the feature as default-off, as requiring the
owner's Allow tap for each request, or as having an owner Auto-allow toggle.
Do not silently change that behavior as part of policy or packaging work.

The actual path is:

1. The requester must be the sole currently authorized `ADMIN`; both clients
   require an independently verified saved direct contact. Official admin
   onboarding alone is **not** independent photo verification.
2. Required Android photo and notification permissions must exist, and the
   owner's phone must be unlocked when sharing starts.
3. Eligible requests can automatically start. Android permission is necessary
   but is not a substitute for informed consent to this use.
4. [PhotoSharingService](../android/app/src/main/java/app/vanishr/android/PhotoSharingService.java)
   runs as `dataSync`, with an ongoing **Vanishr is active** notification and
   **End access** action. Maximum session duration is 15 minutes.
5. An active owner session **may continue after backgrounding or owner phone
   lock**. The owner-side lock exception keeps temporary photo-session keys in
   memory; it does not reopen the normal encrypted chat vault. Ending access,
   permission loss, sign-out, contact removal, connection loss, viewer lock,
   Android termination or the deadline can end the session.

The website describes this openly. Do not hide the notification, misstate lock
behavior, omit the automatic request path from review materials, or describe
independent contact verification as gallery-sharing consent. A general Terms
checkbox, privacy page, Android permission dialog or foreground notification
does **not** establish that Google's separate prominent-disclosure and consent
requirements have been met.

- [ ] The account holder must reconsider the consent model or establish a
  genuinely eligible, informed, approved core use case with Google. Retaining
  automation is a product constraint, **not** a policy exemption.
- [ ] Resolve the gap before submission; do not attest that this gate passed
  merely because a disclosure screen has been added.
- [ ] Obtain legal/privacy and Play-policy review appropriate to the actual
  audience and jurisdictions. Do not claim an outcome in advance.

### Photo permission declaration draft

Evidence: [manifest](../android/app/src/main/AndroidManifest.xml),
[PhotoLibrary](../android/app/src/main/java/app/vanishr/android/PhotoLibrary.java),
[RemotePhotoSession](../android/app/src/main/java/app/vanishr/android/RemotePhotoSession.java)
and [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java).

> Vanishr's Remote Photos feature lets the sole eligible administrator browse
> the photos Android makes available on an independently verified contact's
> phone, using paged thumbnails and on-demand encrypted original transfers.
> It currently requests READ_MEDIA_IMAGES for broad image-library browsing,
> supports Android selected-photo access, and uses READ_EXTERNAL_STORAGE only
> on Android 12L and earlier. Android permission and unlocked startup are
> required. Eligible requests are automatically accepted; an active session
> can continue while the owner app is backgrounded or the phone is locked,
> with an ongoing notification and End access, for at most 15 minutes.

This is an explanation of the existing implementation, **not an eligibility
claim**. Broad browsing is essential to the feature as currently described;
Google decides whether it is an eligible **core app** function and whether a
minimum-scope alternative is sufficient. Ordinary attachments and selecting a
profile photo already use `PickVisualMedia` and do **not** need broad gallery
access. Do not justify `READ_MEDIA_IMAGES` by those picker-only flows. Do not
declare video access or camera access that the merged manifest does not request.

- [ ] Provide an accurate listing explanation and demo of why the Android
  photo picker does or does not meet the claimed core function.
- [ ] Show full/selected/denied access, permission revocation, automatic start,
  background/locked continuation, End access and expiry on supported devices.
- [ ] Complete the Console photo/video permission declaration for the actual
  merged manifest. If Google rejects the purpose or consent model, obtain an
  explicit product decision; do not relabel the feature to bypass review.

### Foreground-service declaration draft

`FOREGROUND_SERVICE_DATA_SYNC` / `dataSync` currently covers the network transfer
of requested photos and thumbnails, not chat presence, an invisible monitor or
a continuous gallery backup. Deferral prevents the requester from immediately
browsing an active session; interruption ends or interrupts that bounded
transfer. The service is not automatically restarted after force-stop.

- [ ] Describe automatic initiation honestly when selecting the Console use
  case; do not call every owner session owner-initiated.
- [ ] Provide Google's requested video showing every step, required permissions,
  notification and End access, background/lock behavior and the 15-minute limit.
- [ ] Explain why the transfer needs a foreground service rather than deferred
  work, and assess the user-initiation/user-benefit requirements. Declaration of
  a technically valid service type is not approval of this use.
- [ ] Validate current Android/OEM foreground-service and notification behavior.
  Screenshots and demonstration evidence still need the operator; none is
  fabricated by this document.

## Account deletion and safety contract gates

The following is the **0.4.6 implemented contract**, verified with the matching
schema-9 relay. The earlier 0.4.5 client does not expose its controls. Read the
[API](API.md), relay implementation and integrated client tests.

- [ ] `DELETE /account` accepts only fresh, at-most-five-minute `ENROLL`
  authorization after account-only password/Google reauthentication and exact confirmation
  `DELETE`. The owner is derived from authentication; a request cannot select
  another account. A normal `DEVICE`/remembered token is insufficient.
- [ ] Before the initial deletion request, the client must atomically persist
  the random `deletionProof` and original account UUID in that account's
  encrypted storage, then include the proof in the request. Do not log, email,
  display in a public form or put the proof in a URL. Legacy proof-less server
  compatibility does not provide lost-response recovery for the candidate.
- [ ] Complete durable account/profile/credential, device/public-key,
  membership and association cleanup. Invalidate the deleted account's relay
  access. The permanent admin reservation must preserve **only** its immutable
  random UUID to prevent a replacement admin, not the deleted admin's username,
  password verifier, Google subject or public device profile. The bounded
  deletion receipt below is a separate, explicitly disclosed retention record.
- [ ] The requesting client removes only that account's encrypted local
  partition after an explicit `DELETED` response matching the originally saved
  UUID. `DELETE` returning 204 alone, failure, lost responses, arbitrary 401/404,
  or wrong-account/provider proof must not authorize local cleanup or erase
  other accounts. Persisted proof is needed to recover after server completion
  when the account no longer exists.
- [ ] Verify failed-cleanup recovery from persistent `DELETING` state. Prior
  credentials may already be revoked; normal account/device use and enrollment
  are rejected. `POST /account/deletion/status` and
  `POST /account/deletion/retry` take the saved proof without a bearer token
  and return `userId`, `state` and `expiresAt`. A continuation proof cannot
  start deleting an active or different account. Keep local data for `PENDING`,
  missing/expired proof, UUID mismatch or unconfirmed completion. Do not use
  a normal Google signup to recreate an erased account as a recovery shortcut.
  Concurrent cleanup can return `503 safety_operation_in_progress`; legacy
  unattributable uploads can return `503 legacy_media_pending` until their
  original upload TTL (at most five minutes) expires. Neither authentication
  nor a continuation proof bypasses that guard or turns pending into success.
- [ ] Reconcile and test the
  [V9 deletion receipt](../relay/src/main/resources/db/migration/V9__bounded_account_deletion_receipts.sql)
  and [DeletionReceipts](../relay/src/main/java/app/vanishr/relay/DeletionReceipts.java):
  only the proof's SHA-256 digest, original random account UUID,
  `PENDING`/`DELETED` state, creation time and expiry time are stored. No
  plaintext proof, name, login credential, Google subject or content is kept
  in the receipt. `DELETED` commits atomically with SQL account erasure; the
  receipt deliberately survives account deletion and Redis resets.
  Its fixed lifetime is 24 hours from creation, never extended by status/retry.
  Logical expiry is immediate; the scheduled purge runs every minute while
  the service is running, so interruption may delay physical row removal.
  Validate lost-success-response, restart/Redis-reset and expiry recovery
  without turning an unavailable receipt into successful deletion.
- [ ] Handle `404 deletion_proof_unavailable`, `409 deletion_proof_conflict`
  and `429 rate_limited` explicitly. Status is limited to 30 requests/minute
  per proof; retry to five/minute per proof. None confirms account erasure.
- [ ] For **all saved-account Google reauthentication**, bind `expectedUserId`
  into the one-use Google challenge and require the freshly verified subject
  to map to that exact existing UUID. A missing/mismatched account must return
  `401 authentication_failed` without creating an account. Ordinary Google
  signup without `expectedUserId` remains a separate, unchanged flow.
- [ ] Document/test queued ciphertext cleanup and read denial under the existing
  absolute lifetimes, no more than 24 hours from original acceptance. Shared
  already-delivered copies follow original expiry; deletion cannot erase a
  malicious recipient's copies or remotely wipe an offline installation.
- [ ] Confirm in-app **Block user**, **Report user** and **Report message/content**
  are reachable and work end to end. A block stops new direct interaction; do
  not claim it erases past copies or removes all shared-group context. Reconcile
  exact group behavior with the relay owner before stronger public wording.
- [ ] Reports are optional metadata only: random report ID, reporter/target
  account IDs, selected reason, timestamps/deadline and optional message/group
  IDs. No plaintext, images, free-text evidence or screenshots are uploaded.
  Admin review has an absolute 30-day maximum from submission; reads/retries
  must not refresh it. Completed account deletion removes reports submitted
  by **or targeting** that account, without a residual safety-report retention
  exception. Deleting the pinned admin clears the entire undeliverable report
  queue. Verify these removals before claiming deletion is complete.
- [ ] When no currently enabled pinned admin exists, `POST /safety/reports`
  returns `503 safety_review_unavailable`. The client must not show a successful
  submission or imply moderation exists. Keep the published email contact
  available without claiming it is continuously staffed.
- [ ] Human admin review, restriction/removal and escalation procedures actually
  exist. A report queue or a Mark reviewed button is not proof of effective
  moderation. Establish triage, handling of admin-related complaints and lawful
  child-safety escalation; no guaranteed SLA is currently specified.

### External deletion and support operation

- [ ] Confirm control of `richardhenryjames64@gmail.com` and actually monitor
  requests. No live email or account action was performed by this task.
- [ ] Dry-run an email deletion request for a **synthetic isolated** account
  without reinstalling. Request only current username and account UUID if known,
  not a password/token, deletion proof, identity document or private conversation.
- [ ] Establish safe password/provider ownership verification by the operator
  before deletion. Knowledge of a username/UUID or sender email is not proof;
  Google email/name/photo are not stored and usernames can be reused. A published
  mailto path is not an implemented web authentication workflow.
- [ ] Reply with next steps and completion or failure information. Do not make
  the user re-download merely to submit a request and do not promise an
  unimplemented response/completion time.
- [ ] Set and document the actual mailbox/verification record retention and
  provider deletion process. No automatic support-mail expiry exists in app
  code; the public policy says the app's 24-hour and 30-day TTLs do not apply
  to email. Do not invent a fixed provider/backup purge deadline.
- [ ] Keep privacy/deletion/abuse requests out of public GitHub issues.
  Do not accept CSAM uploads as report evidence.

## Data safety draft: inspect, classify, then answer

Do **not** submit "no data collected" because message content is encrypted.
Google's definitions of collection, sharing, ephemeral processing, service
providers and user-initiated transfers must be applied to each actual flow.
Random IDs are not automatically anonymous. Include SDK behavior and all
versions/configurations distributed through Play, not just one test run.

| Data / likely Console category | Actual path and purpose | Required / optional and retention | Draft decision / work remaining |
| --- | --- | --- | --- |
| Personal info: User IDs | Username, random account UUID and Google subject mapping in [AccountDirectory](../relay/src/main/java/app/vanishr/relay/AccountDirectory.java); authentication, lookup, account management and routing | An account identifier is required; Google subject is only for Google accounts. Account/profile records last until deletion; UUID-only admin reservation and the bounded deletion receipt below are explicit exceptions | Collection: yes for account identifiers. Use app functionality/account management; assess security purposes and every sharing recipient |
| Personal info: Name | Optional shared `displayName`, chosen by the user; no import of Google name | Optional; visible in authenticated lookup and new-registration admin directory; until changed/deleted | Collection: yes when supplied. Distinguish shared name from a private local nickname |
| Authentication data | Password verifier and account credentials in [AuthService](../relay/src/main/java/app/vanishr/relay/AuthService.java); ID-token validation in [GoogleAuth](../relay/src/main/java/app/vanishr/relay/GoogleAuth.java) | Password or Google authentication; enrollment/challenge 5 minutes, access 1 hour, rotating renewal up to 30 days; durable verifier/subject until deletion | Map to Console categories and security/account purposes. Password is transiently processed over TLS; do not call it "never sent." No Google access/refresh token, email, name or photo retained |
| Device or other IDs | Random device UUID, public identity/prekeys, session routing, FCM token / Firebase installation data | Device registration required; push conditional on enabled/configured notifications and permission. Relay FCM registration 24 hours per registration; provider retention separate | Collection: yes for app/device identifiers. Review exact pinned Firebase SDK disclosure and installation/network data; do not omit because there is no advertising ID |
| App activity / interactions and relationship metadata | Direct routing, group membership, presence audiences, Online/Typing/Last seen in [Presence](../relay/src/main/java/app/vanishr/relay/Presence.java), [GroupDirectory](../relay/src/main/java/app/vanishr/relay/GroupDirectory.java) | Groups/presence used as features; membership durable, online 12 seconds, typing 5 seconds, latest activity/audience 24 hours | Assess app interactions / other actions / User IDs and contact relationships. Not app analytics, but still personal metadata sent off device |
| Messages: other in-app messages | Client-encrypted direct/group content in [ChatEngine](../android/app/src/main/java/app/vanishr/android/ChatEngine.java) and [GroupChat](../android/app/src/main/java/app/vanishr/android/GroupChat.java); opaque relay delivery | User chooses to send; view once or 1/6/24 hours; relay deadline no more than 24 hours | Review the exact end-to-end-encryption exception, not a blanket "No." Admin is an actual recipient of its own conversations. Other routing/account data is not exempt because content is encrypted |
| Photos and videos: Photos | Picker attachments, mutual independently verified [ProfilePhotos](../android/app/src/main/java/app/vanishr/android/ProfilePhotos.java), and automatic Remote Photos | Photo use is feature-dependent; owner's chosen avatar durable locally, received copies/requests at most 24 hours; remote session at most 15 minutes, packets at most 60 seconds | Review collection and sharing separately, including the developer-operated admin as recipient. Do not assume automatic Remote Photos qualifies as an owner-initiated transfer or mark all photo processing exempt. Prominent-consent/core-use gate remains unresolved |
| Contacts / social relationships | Manually added account identifiers and relay-visible delivery/membership graph; no phone address-book upload | Contacts/groups are chosen features; local nicknames stay encrypted on the client | Inspect current Console definitions for Contacts and User IDs/other interactions. Do not state that "no READ_CONTACTS permission" means no relationship data is processed |
| Safety reports / other user-generated content or app activity | Metadata-only reporter/target/report IDs, reason and optional message/group IDs; in-app safety UI in [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java) | Optional; at most 30 days from submission, removed when either reporter or target account is deleted. Pinned-admin deletion clears the undeliverable queue; no post-deletion safety-report exception | Confirm final backend fields and classification; purpose is fraud prevention, security and compliance / app functionality. No message body or image evidence. New reports return 503 when no enabled pinned admin exists |
| Account deletion confirmation / User IDs and operation metadata | [DeletionReceipts](../relay/src/main/java/app/vanishr/relay/DeletionReceipts.java) stores proof SHA-256 digest, original random UUID, PENDING/DELETED state and creation/expiry times; recover deletion completion without retained account credentials | Created for the proof-based deletion flow; durable in SQL across account deletion and Redis reset. Fixed 24-hour lifetime from creation; no extension on checks/retries. Access expires at the deadline; every-minute purge while service runs | Disclose for account management/security and lost-response recovery. Pseudonymous is not anonymous; this is not ephemeral processing or a retained safety report. No plaintext proof, name, login credential, Google subject or content in this receipt |
| Personal info: Email address / support correspondence | External mail request to the operator, including sender address and voluntarily supplied identifiers | Optional email channel; no automatic mailbox TTL in app code | Distinguish external support correspondence from Google sign-in (which does not store email). Determine Console scope and actual mailbox retention/provider handling; do not assert "we never receive email addresses" |
| Network/operational data; possible diagnostics/location depending on use | Relay, Google/FCM and hosting connections expose IP/time/device/network metadata; [AppUpdates](../android/app/src/main/java/app/vanishr/android/AppUpdates.java) contacts static host without account tokens/IDs | Necessary connection processing; operational/provider retention requires verification | Verify actual hosting/logging/SDK processing. Declare approximate location if IP is used to derive it; do not infer location collection solely from IP transit or claim all provider diagnostics are absent |
| Website analytics (separate) | [site.js](../download/site.js) loads GA4 only after consent on the public site; page visits and allowlisted downloads, no app data | Optional; browser consent and analytics cookies at most 180 days; hosting metadata is independent | Not an app Analytics SDK. Keep this distinct from the app form, review browser-opened website scope, and verify configured GA4 retention/settings separately |

### Cross-cutting draft answers

- **Ads:** `false` / No ads based on the current
  [app dependencies](../android/app/build.gradle) and app code. Recheck the
  merged release and third-party inventory before the owner answers. Website
  analytics is not an advertising integration in the Android app.
- **Encryption in transit:** HTTPS/WSS and official libsignal are implemented
  paths, not evidence that every final SDK/configuration was inspected. Verify
  all release network flows before selecting Yes. No cleartext fallback.
- **Sharing:** assess service-provider and intentional user-transfer exceptions
  against Google's definitions. Do not blindly select "not shared" because the
  relay is opaque, especially for automatic admin photo access.
- **Ephemeral processing:** in-memory Redis or a TTL does not automatically
  satisfy Google's definition; 24-hour queues, rotating auth state and 30-day
  reports must be considered separately. Provider records may outlive relay TTLs.
- **Deletion offered:** the public request path is email; complete and verify
  its operator handling and the in-app/backend path before finalizing answers.
  Account deletion removes reports submitted by or targeting the account;
  disclose the fixed 24-hour minimal confirmation receipt, UUID-only admin
  reservation and other deletion limits, not a safety-report retention
  exception. Distinguish initial fresh account-only authentication from
  proof-based status/retry, pending `DELETING` state and explicit `DELETED`
  confirmation matching the original UUID.
- **Independent security review:** do not select or advertise a certification
  or independent-review badge based on unit/emulator tests.

## Signing, packaging and distribution

- [x] [build.gradle](../android/app/build.gradle) defines the candidate as
  `0.4.7` / `28` and supports `-PplayStore=true` (default `false`).
  [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java)
  uses that flag to skip external APK-feed checks and prompts; the manual update
  action opens the Google Play URL for `app.vanishr.android` instead.
- [x] [package-apk.ps1](../scripts/package-apk.ps1) accepts `-Bundle -PlayStore`,
  passes the Play flag to Gradle and selects the release AAB task. `-PlayStore`
  requires `-Bundle`; **`-Bundle` alone does not enable Play update behavior**.
  No signing or packaging command was run by the website documentation task.
- [x] [prepare-download.ps1](../scripts/prepare-download.ps1) includes this
  document and the [Terms](../download/terms/) and
  [deletion](../download/delete-account/) template directories in its source
  archive allowlist. Verify the actual generated archive when packaging; this
  does not alter the immutable 0.4.5 source archive.
- [ ] Confirm candidate `app.vanishr.android`, version **0.4.7 / 28**, target SDK
  and release manifest from [build.gradle](../android/app/build.gradle) and the
  final signed AAB, not from a debug/QA build. Packaging is a separate workstream.
- [ ] The original/current sideload signing certificate SHA-256 is
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`.
  It is the current local signing/upload identity, not proof of which app
  signing certificate Play will use. Never put keystores or passwords in docs,
  fixtures, screenshots or the static distribution.
- [ ] The account holder chooses/configures **Play App Signing**. An upload
  certificate authenticates uploads; Google signs delivered APKs with the app
  signing certificate, which may differ. Record the actual public SHA-1/SHA-256
  certificate fingerprints from Console after enrollment.
- [ ] Register the actual **Play app signing** package/certificate identity
  with the Google Android OAuth client and Firebase, alongside needed sideload
  identities. Upload-key registration alone may not authenticate Play-installed
  builds. Recheck the Web OAuth audience, Android authorized clients and real
  Google/FCM behavior using synthetic accounts. No cloud change is authorized
  merely by this checklist.
- [ ] Plan sideload compatibility before choosing the app signing key. A
  different signer without a supported, verified lineage may not update the
  existing sideload installation. Do not recommend uninstalling to "fix" this:
  uninstall destroys local keys/chats. Test an approved in-place upgrade and
  document any unavoidable incompatibility.
- [ ] Verify the final Play artifact was built with both packaging switches:
  automatic/manual update checks must not fetch the APK feed or show a sideload
  prompt, and the manual action must open the correct Google Play listing.
  Verify sideload update behavior separately with the default flag. The Play
  URL constant is not evidence of an approved or publicly available listing.
  Keep the sideload feed accurate and candidate artifacts separate from
  immutable 0.4.5 evidence.
- [ ] Validate the AAB, ABI/native libraries, target API and page-size/device
  requirements for the actual submission date using existing packaging tools.
  Keep matching source/notices and licensing review aligned with the artifact.

## Listing, reviewer access and account-holder declarations

- [ ] Listing name is **Vanishr**; provide accurate descriptions, icon,
  screenshots and required feature graphics from synthetic data only.
  Describe automatic Remote Photos prominently if retained as a claimed core
  feature. Do not advertise anonymous/untraceable use, unstoppable deletion,
  guaranteed consent, an audit or Play approval.
- [ ] Supply correct support details and developer identity through the
  Console's actual verified account; this doc does not invent an entity/address.
- [ ] Provide reviewer **App access** instructions with working isolated test
  fixtures, safe credentials through Console only, availability and step-by-step
  access to sign-in, verified contacts, groups, policy acceptance, deletion,
  blocking and reporting. Never commit reviewer credentials or real user data.
- [ ] Google sign-in is currently limited to approved test accounts. Ensure
  reviewers can use an approved flow without unplanned provider restrictions;
  a screen saying "Google login" is not evidence reviewers can authenticate.
- [ ] Arrange an accurate Remote Photos demonstration/reviewer path without
  giving out real admin credentials, changing the permanent production pin or
  creating a second admin. If a safe isolated signed fixture/operator-assisted
  demonstration is needed, confirm Google accepts that review arrangement.
  This privileged review access remains an open gate.
- [ ] The official human admin is shown as **Vanishr / @vanishr** and is trusted
  only through the signed pin. Do not modify production admin identity or waive
  independent verification for ordinary contacts to make review easier.
- [ ] Decide the actual target audience/age groups, category, countries and
  access restrictions. Do not guess 18+, "all ages," or a child-directed answer
  from a codebase with no validated age process. Review Families obligations if
  relevant.
- [ ] Answer ads (currently No), content rating, user-generated content,
  social/communication features, App access, Data safety, account deletion,
  photo/video and foreground-service declarations based on the submitted app.
- [ ] Assess Child Safety Standards applicability, including Google's current
  Anonymous/Random Chat and Social/Dating scope; do not infer exemption simply
  from intended age or a category label. Publish the standards regardless.
  Designate a real capable child-safety contact in Console; the public mailbox
  is `richardhenryjames64@gmail.com`. Establish appropriate authority escalation.
- [ ] If the personal developer account was created after 13 November 2023,
  check the applicable production-access testing requirement. The retrieved
  guidance requires at least **12 testers continuously opted in for 14 days**
  in a closed test, followed by a production-access application. Recheck the
  Console/current policy; don't fabricate tester activity or claim production
  access just because the minimum interval elapsed.
- [ ] Run pre-launch/testing tracks, resolve device and policy feedback, then
  have the account holder review every declaration. No upload, publish, commit,
  deployment or live-account action is part of this website task.

## Local website validation and release boundary

Use a **new session-artifact stage**, never overwrite a prior audited release.
The renderer requires an existing `updates.json` and its matching versioned APK.
For a complete static link check, copy only the already-audited public APK,
feed, application source archive, libsignal source, license and notices from the
local 0.4.5 stage, verifying them against that release's distribution audit.
Run [render-website.ps1](../scripts/render-website.ps1) with `-Stage` pointing to
the new directory, not the immutable source stage.

- [x] Confirm six rendered pages with no unresolved placeholders, correct
  0.4.5 download links and a clear 0.4.6 candidate-controls notice.
- [x] Parse the HTML/JSON-LD, verify local links/fragments/assets, all navigation
  menus, sitemap coverage, canonical URLs and mobile layout. The static redirect
  configuration and local emulation passed; deployed Vercel behavior is untested.
- [x] Confirm CSP is still restrictive (`form-action 'none'`, no inline
  executable scripts or forms), JSON-LD hashes match, and no new third-party
  scripts, trackers or remote fonts were added.
- [x] Compare consent analytics code/settings with the baseline; keep
  `privacy/#analytics` and Cookie settings working on every page. No consent
  must mean no Google Analytics requests. The loopback browser test made no
  external page requests; production analytics was not contacted or tested.
- [x] Check mailto recipient/subjects without actually sending mail. No
  sensitive body prefill and no public GitHub personal-info request.
- [x] Rehash the original public release inputs after rendering to demonstrate
  they were not modified. Record local evidence separately from deployment and
  app/backend test claims.

Local evidence on 2 October 2026: a new session-only 0.4.5 stage rendered six
pages / 31 allowlisted files from hash-verified public artifacts. Static checks
covered 280 local links/assets, 18 navigation menus, six JSON-LD/CSP hashes,
all sitemap/llms routes and mail recipients/subjects. The existing analytics
JavaScript, public analytics settings and website hosting/analytics/choice
policy sections were unchanged. Installed Edge, using an isolated temporary
profile and loopback-only page requests, completed 24 page/viewport checks at
320, 360, 800 and 1440 pixels: no horizontal overflow or console errors; Cookie
settings/No thanks worked. Screenshots and machine-readable results are session
artifacts, not production evidence. No app, relay, provider, mailbox, live
account or store-submission behavior was validated by those website checks.

## Official guidance consulted

Google can change policy and Console requirements. Re-read the current full
policies (not only summaries) at submission time.

- [User Data: privacy, prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Account deletion requirements, including an external email request path](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Data safety definitions and developer responsibility](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Sensitive permissions and minimum-scope photo alternatives](https://support.google.com/googleplay/android-developer/answer/16558241)
- [Foreground-service declarations and demonstration requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Child Safety Standards and required public standards/contact](https://support.google.com/googleplay/android-developer/answer/14747720)
- [Play App Signing and API-provider certificates](https://developer.android.com/studio/publish/app-signing)
- [Firebase Android SDK Data safety disclosure](https://firebase.google.com/docs/android/play-data-disclosure)
- [Personal-account testing and production access](https://support.google.com/googleplay/android-developer/answer/14151465)
