# Verification and release gates

Evidence refreshed on Windows, 2026-09-22. This is a development foundation,
not a production security assessment or independent audit.

## Release 0.4.1

- Candidate, not yet published. Version 0.4.1/code 22 adds offline contact Last
  seen beneath the direct-chat name, below Typing and Online in precedence.
- The relay stores only the latest foreground heartbeat and pinned audience
  with an atomic 24-hour TTL. Reads do not extend retention. Mutual identities,
  current reader authentication and peer device generation are required;
  sign-out/empty or replaced audiences revoke sharing. Legacy clients retain
  their Online/Typing contract. No draft text or phone status cache is added.
- Six focused Redis/PostgreSQL integration tests passed, covering last-seen
  privacy, bounded expiry, sign-out/audience removal, changed devices and the
  existing presence behavior. The local Docker backend initially could not
  start tests; it was recovered only after confirming its VM was stopped.
- Client and instrumentation compilation passed with the QA test variant.
  Android unit tests, QA lint and all 91 ScreenFlow QA methods passed. The six
  focused presence/header checks passed in 33.960 seconds. Two header/profile
  checks at 320dp and 130% font passed in 18.743 seconds, including the longest
  last-seen label without truncation or overlap.
- Initial full-QA attempts encountered a confirmed System UI ANR intercepting
  input and a relocked test keyguard after System UI restarted. Those fixture
  issues were resolved without changing app security. One unchanged sheet-color
  assertion then failed transiently, passed its focused rerun in 10.336 seconds,
  and passed the complete 91-method rerun. Original attempt reports are retained.
- Full JVM verification passed 53 executed tests with one opt-in LiveRelayTest
  skip; the separate signed live workflow below did run. The relay artifact
  contains no client-only crypto dependency. Only the existing vanishr-dev-rg
  relay was deployed; trusted TLS/health passed, temporary transfer storage was
  removed and accounts/provider configuration/VM size were preserved.
- Signed release build/lint passed with the original signer. The complete live
  workflow passed in 282.967 seconds, including real Online/Typing then Last seen,
  reciprocal offline visibility after backgrounding, encrypted messaging, contact
  photos, groups, retained sessions, actual FCM and notification taps. Five signed
  protected-device checks passed in 4.107 seconds and the complementary no-screen-
  lock rejection in 0.019 seconds. The generated test credential was cleared.
- The verified APK is 44,233,489 bytes, SHA-256
  `9003eac840a76d07e3e6cca9fbf17e64d0f0da2312c81a88747215b232220c47`.
  The immutable 27-file static bundle passed its source/secret/native-test audit:
  58,571,898 bytes, 175 matching workspace source files and 393 source entries.
  Its source ZIP is 9,745,853 bytes, SHA-256
  `06a47e919d448b899fa41311e0d5b9330e6c49d87335bce9346cf8ff478abca7`.
  The 16 optional unavailable upstream source artifacts are unchanged from 0.3.9.
- Static publication is blocked on a fresh interactive Vercel sign-in. No file
  upload or deployment creation was attempted; the public APK/feed remains
  0.3.9/code 21. The audited `.tools/vercel-download-0.4.1` must not be overwritten
  or recreated. Resume with its exact allowlist, then verify anonymous hashes,
  original signer, no-store code-22 feed and all website routes/security headers.
  This post-audit status update is intentionally newer than the source archive.
- The isolated Android 12 emulator was stopped after generated credential,
  synthetic app-data and screen-setting cleanup. No shared device was used.
- Evidence: `.tools/last-seen-0.4.1-relay-focused.txt`,
  `.tools/last-seen-0.4.1-jvm-verify.txt`, `.tools/last-seen-0.4.1-qa-focused.txt`,
  `.tools/profile-0.4.1-qa-verification.json`, `.tools/last-seen-0.4.1-narrow-qa.txt`,
  `.tools/last-seen-0.4.1-relay-publication.txt`, `.tools/release-0.4.1-package.txt`
  and `.tools/release-verification-0.4.1/verified-artifact.json`
  (`livePushVerified:true`). The last QA change only lengthened the header fixture
  to 23 hours for the subsequent narrow-screen test; production code is unchanged.
  Packaging evidence: `.tools/distribution-audit-0.4.1.json` and
  `.tools/website-build-0.4.1.json`.
- Existing 0.3.9 biometric-compatible storage and migration behavior is retained;
  physical-phone/OEM and Android 15+ runtime caveats still apply.

## Release 0.3.9

- Version 0.3.9/code 21 changes new Keystore keys on Android 14 and below to
  accept any system-accepted phone unlock. Every vault access still requires a
  configured secure screen lock and an unlocked phone. The user approved the
  reduced Keystore defense in depth on those Android versions; Android 15 and
  above retain unlocked-device-required for newly created keys. See the threat
  model for the distinction between app-enforced and Keystore-enforced access.
- Protected-record versions 1 and 2 migrate to version 3 using authenticated
  decryption and encrypted atomic writes. A blocked old key can require one
  final PIN/pattern/password unlock. Android's own post-reboot and biometric
  lockout rules still apply. No automatic account reset is introduced.
- Ten focused storage-policy, migration and failure-preservation checks passed
  in 28.196 seconds on the isolated Android 12/API 31 AVD on port 5588. They cover
  retained-account identity, expiry and view-once state, tampered ciphertext,
  missing keys, fresh storage usability and the one-time migration error.
- All 89 Android QA methods passed across six batches, and QA lint passed.
  Startup System UI failure and a relocked emulator initially blocked UI tests;
  neither app lock checks nor secure-window flags were bypassed. The sheet test
  now checks its usable layout container instead of system navigation space.
  The rejected-profile test separately awaits the request and worker completion
  before checking sign-in recovery. Their focused checks passed in 10.223 and
  6.819 seconds; the affected full batches were rerun successfully.
- Five QA device-security checks passed in 5.574 seconds, including production
  Keystore generation, encrypted round trips, key-deletion failure, official
  libsignal, secure windows, disabled backup/cleartext and notification privacy.
  The separate no-screen-lock rejection executed and passed in 0.029 seconds.
- Signed release build, release lint and original-signer verification passed.
  The published APK is 44,233,489 bytes, SHA-256
  `c2861a78e40aac400ae22d315f1f4f5cb800a459c28dfa18c1e8d854d2904cfb`.
  The complete signed live workflow passed in 318.840 seconds, including actual
  FCM delivery, notification taps, encrypted messaging, contact photos, presence,
  account switching, groups and phone-lock recovery. Five protected-device
  checks passed in 3.782 seconds; the complementary no-screen-lock rejection
  passed separately in 0.044 seconds after the generated credential was removed.
- Evidence: `.tools/unlock-0.3.9-migration-qa.txt`,
  `.tools/profile-0.3.9-qa-verification.json`,
  `.tools/unlock-0.3.9-device-security.txt`,
  `.tools/unlock-0.3.9-no-screen-lock.txt`,
  `.tools/release-0.3.9-package.txt` and
  `.tools/release-verification-0.3.9/verified-artifact.json` (`livePushVerified:true`).
- Key-policy construction is checked for APIs 28, 30-36; that is not runtime
  coverage of those platforms. Physical/OEM face and fingerprint unlock and
  Android 15+ runtime behavior remain unverified for this release. No relay,
  cloud configuration or hosted-account reset was needed. JVM code is unchanged
  and retains the prior release's evidence; its tests were not rerun for 0.3.9.
- Published at https://vanishr-download.vercel.app on the existing Vanishr Hobby
  project. Deployment `dpl_3xFYg4ywqQQ9thjWoFQ3zs76M3Xi` is READY and assigned to
  the public address, with zero functions/build jobs. Only the audited 27-file
  static bundle was uploaded: 58,565,727 bytes, 174 matching local source files
  and 392 audited source entries. Known private values, signing/server files and
  native test libraries are excluded. The 16 unavailable optional dependency
  source artifacts are unchanged from 0.3.8.
- The source ZIP is 9,740,452 bytes, SHA-256
  `e2767b309050b018c5f282ea434744578eac3de9f37b318fe5aeb0f2a109726c`.
  All 26 anonymous public files match the audit, including the original APK
  signer and no-store code-21 update feed. Four crawlable pages, seven canonical
  redirects, CSP/schema hashes and nine private/missing 404s passed.
- Download-page checks at 320/390/1280px found no horizontal overflow, broken
  images or stale APK link. The one-time migration caveat is visible and the
  browser sent no Google Analytics requests during the check. Existing Google
  configuration and opt-in consent remain unchanged. Temporary upload controls
  were removed after verification; the isolated test emulator was already stopped
  with its generated credential and synthetic app data cleared.
- Publication evidence: `.tools/distribution-audit-0.3.9.json`,
  `.tools/public-release-verification-0.3.9.json` and
  `.tools/public-website-verification-0.3.9.json`. The immutable source archive
  predates these final local publication-status documentation edits.

## Release 0.3.8

- A crossing mutual photo request set the pending request's refresh time to zero.
  The next scheduling pass could replace its ID before an authorized photo reply
  arrived on a later sync. Replacement now respects the existing one-minute
  minimum retry window, including records with a zero refresh time. Photo and
  request deadlines, identity verification and mutual-contact checks are unchanged.
- Three focused timing tests passed in 7.662 seconds, including the new
  delayed-reply-across-syncs regression. Four adjacent authorization, expiry,
  removal and identical-ciphertext retry tests passed in 7.107 seconds. QA build
  and lint passed. The pre-fix emulator attempt timed out in Android's phone-unlock
  service, so it did not demonstrate the expected assertion failure.
- All 83 Android QA methods and QA lint passed on the final candidate. The full
  run exposed a status timer reading the conversation from a closed account
  vault; it now checks the active session, connection and busy state before that
  lookup. Five focused DP/profile/status checks passed in 132.272 seconds before
  the successful full rerun. The fresh emulator needed its confirmed System UI
  error dismissed and QA package precompilation after startup timeouts. No app
  security gate or assertion was disabled.
- Tests use a new workspace-scoped Vanishr_Release_Test AVD on port 5586. The
  existing emulator on port 5582 was running another app, so neither it nor port
  5584 was reset, stopped or used for this release. Test runners now accept the
  explicit emulator serial while retaining the dedicated-AVD safety check.
- Version 0.3.8/code 20 has a strengthened signed live workflow: send the mutual
  request first, wait for its response, then deliver the original photo reply on
  a separate sync. The complete signed live workflow passed in 478.188 seconds,
  including delayed photo display/removal, real FCM delivery/notification taps,
  presence, encrypted messages/photos, view once, session renewal, account
  switching and groups. Five protected-device checks passed in 8.274 seconds;
  the complementary no-screen-lock rejection passed in 0.088 seconds.
- Signed build, release lint and the original signing certificate check passed.
  The verified APK is 44,233,489 bytes, SHA-256
  `c296e46c2792e7051132bbe3638b99b43ee290ee2afbd53cbb3b0723abc49317`.
  The first live attempt stopped at an unacknowledged Android notification Allow
  tap; its permission check was not bypassed. The complete unchanged retry passed.
- Temporary test credentials and release-app test data were cleared, and the
  isolated emulator on port 5586 was stopped. The existing emulators on ports
  5582 and 5584 were untouched. The reporting user's phone, real Google-provider
  acceptance, physical/OEM
  notification behavior and sustained production load remain unverified.
- This is an Android-only change; no relay deployment, schema change, provider
  change or hosted account reset is needed. The unchanged JVM code retains the
  previous release's 50-test evidence; those JVM tests were not rerun for 0.3.8.
- Evidence: `.tools/dp-crossed-reply-after.txt`,
  `.tools/dp-crossed-reply-security.txt`, `.tools/dp-0.3.8-timing-qa.txt`,
  `.tools/dp-0.3.8-closed-status-qa.txt` and
  `.tools/profile-0.3.8-qa-verification.json`. Signing evidence is in
  `.tools/release-0.3.8-package.txt`; signed runtime evidence is in
  `.tools/release-verification-0.3.8/verified-artifact.json` (`livePushVerified:true`).
- Published on 2026-09-22 at https://vanishr-download.vercel.app on the existing
  static Hobby project. Deployment `dpl_GCxWpHjufR725iG31VYQ7p1dTQc3` is READY,
  with zero functions/build jobs. The 27-file bundle totals 58,560,562 bytes;
  174 local source files matched the ZIP and 392 source entries were audited.
  Private values, signing/server files and native test libraries are excluded.
  The source ZIP is 9,736,659 bytes, SHA-256
  `2991fa21f75c0d651ecbb6c52860755b2834fd6fb4db691deaa68c9d0e6a4248`.
- All 26 anonymous public files match the audit, including the original APK
  signer and no-store code-20 update feed. Four crawlable pages, seven canonical
  redirects, CSP/schema hashes and nine private/missing 404s passed. Download
  layouts at 320/390/1280px have no horizontal overflow. Existing Google IDs,
  assets and opt-in consent are retained; declined consent sent no Google
  requests in the browser check. The temporary upload control was removed.
- Public evidence: `.tools/distribution-audit-0.3.8.json`,
  `.tools/public-release-verification-0.3.8.json` and
  `.tools/public-website-verification-0.3.8.json`. The immutable source archive
  predates these final local publication-status documentation edits.

## Release 0.3.7

- Direct conversation rows and headers show the saved display name without an
  additional username. Contact profile still edits only the local display name
  and shows the other person's username as read-only text.
- Direct headers no longer label our own relay connection as Connected. Fresh
  peer status shows Online or Typing, otherwise no secondary status. Foreground
  presence requires mutually shared current pinned identities and authenticated
  websocket connections, expires within 12 seconds, and limits typing to five
  seconds. No draft text or last-seen history is sent or persisted. The relay
  sees the short-lived presence audience and activity metadata over TLS.
- Three focused relay integration tests passed with isolated Redis/PostgreSQL
  after an initial PostgreSQL startup timeout. Full JVM verification then passed,
  with 50 executed tests and one opt-in live test skipped, including the
  relay/client-crypto dependency boundary. Android QA build, unit tests and lint
  passed. After emulator startup recovered, all 80 Android QA methods passed in
  six batches. Five focused presence/profile checks passed in 52.403 seconds
  after dismissing a screenshot-confirmed System UI ANR. Three 320dp/130%-text
  checks passed in 84.044 seconds. No secure-window flag was bypassed.
- The signed workflow exposed a queued sync UI callback racing sign-out and
  reading a closed vault. The callback now checks its screen generation and busy
  state before refreshing the UI. The new deterministic stale-callback regression
  and three adjacent sign-out/header checks passed in 45.590 seconds. The earlier
  80-method run predates this small guard; it is not a full run of all 81 methods.
- Signed build, release lint and original-signer checks pass for 0.3.7/code 19:
  44,233,489 bytes, SHA-256
  `92df83dd1259c6fe9a99a7c9cc8e0bc6cd824f08d6394132dd507afea738a80f`.
  The presence-capable relay is deployed only to vanishr-dev-rg; existing accounts,
  schema, provider settings and VM size are preserved. Temporary transfer storage
  was removed and the trusted public TLS/health check passed.
- The complete final signed live workflow passed in 354.478 seconds, including
  real peer Online/Typing status in both directions, status removal on disconnect,
  no draft upload, actual FCM delivery/notification taps, phone locking, remembered
  sessions, private photos, account switching and groups. Five protected-device
  checks passed in 5.566 seconds and the complementary no-screen-lock rejection
  passed in 0.063 seconds. The temporary emulator PIN and test data were cleared;
  the dedicated emulator was stopped and unrelated emulator-5584 was untouched.
- A permission-dialog tap initially timed out; the unchanged retry passed that
  step. Release-test cleanup now closes its own RelayApi instead of an R8-removed
  WebSocket method, and owner-profile versus chat-header username assertions were
  corrected without weakening either check. Real Google-provider, physical-phone,
  OEM/Doze and sustained production-load acceptance remain unverified.
- Published on 2026-09-22 at https://vanishr-download.vercel.app on the existing
  static Hobby project. Deployment `dpl_4Pg93WtUvZvMe7LonFmegi8H5Hjg` is READY
  with zero functions/build jobs. Its 27-file bundle totals 58,558,935 bytes;
  174 local source files matched the source ZIP and 392 source entries were
  audited. Private values, signing/server material and native test libraries
  are excluded. The source ZIP is 9,734,931 bytes, SHA-256
  `f793c59baca7360805639574165d8ce8c01eebdc3ba9c2b190b14ceebe3f1375`.
- All 26 anonymous public downloads match the audit, including the original APK
  signer and no-store code-19 update feed. Four crawlable pages, seven canonical
  redirects, CSP/schema hashes and nine private/missing 404s pass. Download-page
  layouts at 320/390/1280px have no horizontal overflow. The shortened consent
  choice and existing Google IDs are retained; no analytics requests were sent
  in the declined-consent browser check. Temporary upload controls were removed.
- Public evidence: `.tools/distribution-audit-0.3.7.json`,
  `.tools/public-release-verification-0.3.7.json` and
  `.tools/public-website-verification-0.3.7.json`. The immutable source archive
  predates these final local publication-status documentation edits.
- Evidence: `.tools/presence-0.3.7-relay-tests.txt`,
  `.tools/presence-0.3.7-jvm-verify.txt`,
  `.tools/presence-0.3.7-android-build-checks.txt`,
  `.tools/profile-0.3.7-qa-verification.json`,
  `.tools/presence-0.3.7-release-focused.txt`,
  `.tools/presence-0.3.7-narrow-qa.txt`, `.tools/presence-0.3.7-sync-guard-qa.txt`
  and `.tools/release-0.3.7-package.txt`. Signed runtime evidence is in
  `.tools/release-verification-0.3.7/verified-artifact.json` (`livePushVerified:true`).

## Release 0.3.6

- Notifications now default on only when no preference exists. A saved Off value
  remains off, including legacy sign-out values that cannot be distinguished from
  explicit opt-out. UI, registration and receiver use the same preference helper.
  Android 13+ permission is requested once after sign-in; denying does not cause
  repeated prompts on app reopening. Explicit enable can retry through Android.
- The preference is separate from the active-registration flag. Successful
  authenticated upload activates alerts; sign-out/opt-out suppress them. Sign-out
  invalidates pending registration callbacks and retains the preference rather
  than saving Off. No token or account ID is stored in the delivery flag.
- All 76 Android QA methods passed across six completed batches with QA lint.
  The full run exposed a delayed profile-sheet dismissal redraw during vault
  closure; the callback now ignores replaced sheets and busy sign-out. The failed
  batch and remaining batch passed after that local fix. A subsequent generation
  guard passed two focused sign-out/stale-registration tests. An earlier shade
  test was blocked by a screenshot-confirmed System UI ANR; the unchanged six
  focused tests passed after dismissing it.
- Signed build/lint and original-signer checks passed. Version is 0.3.6, code 18,
  44,233,881 bytes, SHA-256
  `6b1e09ab289d94dcf3cf97456422660aa9f700c521a4a99c19465f9e022b5d52`.
  The full signed live workflow passed in 298.606 seconds with `-Push`: Android
  permission began denied, the real system Allow dialog was accepted, registration
  completed with an unset/default-on preference and no profile-toggle change,
  then actual FCM delivery and notification-to-chat routing succeeded. Existing
  encrypted messaging, groups, profile photos, session renewal and account
  switching also passed. Five protected-device tests and the complementary
  no-screen-lock rejection passed. The first permission-tap attempt timed out;
  the test now waits for the system UI to settle and for the dialog to dismiss.
- This is an Android-only change. No relay/Azure deployment, provider credential
  change, schema change or hosted account reset was performed. The unchanged JVM
  code retains the previous release's 47-test evidence; the JVM suite was not rerun
  for this version. Real Google login, physical-phone/OEM behavior and Doze/force-
  stop delivery still need device testing.
- Evidence: `.tools/profile-0.3.6-qa-verification.json`,
  `.tools/notification-0.3.6-signout-callbacks.txt` and
  `.tools/release-verification-0.3.6/verified-artifact.json` (`livePushVerified:true`).
- Published on 2026-09-21 at https://vanishr-download.vercel.app on the existing
  static Hobby project. Deployment `dpl_2qYngMbimLG4R83849xBMNLK8yz6` is READY,
  with zero functions/build jobs. The 27-file distribution totals 58,547,671 bytes;
  171 local source files match the ZIP and 389 source entries were audited.
  Private values, signing/server material and native test libraries are excluded.
- All 26 anonymous public downloads match the audit, including the original APK
  signer and no-store code-18 feed. Four crawlable pages, seven canonical
  redirects, CSP/schema hashes and nine private/missing 404s pass. Existing
  website Google IDs and consent behavior are retained. The dedicated emulator
  was stopped; emulator-5584 was not modified.
- Public evidence: `.tools/distribution-audit-0.3.6.json`,
  `.tools/public-release-verification-0.3.6.json` and
  `.tools/public-website-verification-0.3.6.json`. The immutable source archive
  predates these final local publication-status documentation edits.

## Release 0.3.5

- Adds generic notification-to-chat routing using a random 256-bit opaque hint.
  The relay retains only its digest and recipient-bound destination with an
  atomic TTL of at most five minutes/the original message deadline, one mapping
  per device. Resolution requires the current device session. Older clients keep
  event-only pushes unless they register the new routing capability.
- Android handles cold and existing-activity intents with immutable PendingIntents,
  waits for unlocked secure storage, resolves through authenticated HTTPS and
  requires a matching verified incoming unread message after sync. Wrong-account,
  expired, read, superseded, missing and changed-identity cases do not open a chat.
  The route never opens view-once content or a photo viewer automatically.
- The 72-method Android QA run and QA lint passed, including a real emulator
  notification-shade tap using a synthetic destination, cold/warm routing,
  direct/group trust, expired/read rejection and view-once nonconsumption.
  After the final registration change, QA lint and four focused checks passed,
  including two new busy-state and stale/opt-out registration regressions. An
  earlier UI run was blocked by a confirmed System UI ANR; unchanged UI checks
  passed after dismissing that system dialog. The launch intent is retained while
  only transient routing extras are consumed.
- Full JVM verify passed 47 executed tests, with one opt-in live JVM test skipped.
  Real Redis/PostgreSQL checks cover authenticated recipient isolation, bounded
  expiry, superseded references, sign-out cleanup and rejection of extra fields.
  Notifier tests preserve generic payloads and registration compatibility.
- Signed build/lint and original-signer checks pass. Version 0.3.5 is code 17,
  44,233,881 bytes, SHA-256
  `2f8e548e892ca21bd9d027ea821c4b66884e788a2812a5aeffdca17d7f01fd7f`.
  The complete signed live workflow passed in 426.338 seconds with `-Push`,
  including actual Firebase registration, FCM background notification delivery,
  notification-shade tap into the matching chat, encrypted receipts, profile
  photos, view once, session renewal, groups and account switching. Five protected-
  device tests and the complementary no-screen-lock rejection also passed.
- Push-token upload is independent of the general UI busy flag, guarded by the
  current engine/account/generation and notification preference. My profile shows
  registration readiness/failure without tokens or raw errors. Initial live
  attempts exposed test-only R8 helper/resource dependencies, then a delivery
  timeout before explicit registration readiness. The final test enables the
  shipped profile toggle and waits for token upload before backgrounding. A later
  group fixture tap was ignored while busy; the signed helper now observes actual
  send acceptance before asserting receipts, without changing product security.
- The routing-capable relay is deployed only to vanishr-dev-rg. Accounts, schema,
  provider configuration and VM size are preserved; temporary transfer storage
  was removed and trusted public TLS/health passes. No client crypto dependency
  entered the relay.
- Published on 2026-09-21 at https://vanishr-download.vercel.app on the existing
  static Hobby project. Deployment `dpl_Ap6vhUNsZgM2WA1CYzVaJSVgr5YZ` is READY with
  zero functions/build jobs. Its 27-file bundle totals 58,544,094 bytes; 171 local
  source files match the source ZIP and 389 source entries were audited. Private
  values, signing/server material and native test libraries are excluded.
- All 26 anonymous public downloads match the audit. The APK verifies with the
  original signer; the code-17 feed is no-store. Four crawlable pages, seven
  canonical redirects, CSP/schema hashes and nine private/missing 404s pass.
  Website Google IDs and opt-in analytics are unchanged. The dedicated test
  emulator was stopped and unrelated emulator-5584 was untouched.
- Evidence: `.tools/profile-0.3.5-qa-verification.json`,
  `.tools/notification-0.3.5-registration-qa.txt`,
  `.tools/notification-0.3.5-jvm-verify.txt` and
  `.tools/release-verification-0.3.5/verified-artifact.json` (`livePushVerified:true`).
  Public evidence is in `.tools/distribution-audit-0.3.5.json`,
  `.tools/public-release-verification-0.3.5.json` and
  `.tools/public-website-verification-0.3.5.json`. The immutable source archive
  predates these local publication-status documentation edits.
  Actual Google-provider sign-in, physical-device push, force-stop/Doze and OEM
  behavior remain unverified; emulator delivery is not an availability guarantee.

## Release 0.3.4

- Reproduced the missing renewal credential: enrolled sessions expired after one
  hour with no credential-free continuation. Added one-hour access plus rotating
  30-day renewal for both Google/password accounts, encrypted on the client and
  hash-only on the relay. Atomic rotation, expiry, device-generation rejection,
  enrollment-token exclusion, concurrent single-winner behavior and sign-out
  revocation pass focused real Redis/PostgreSQL tests.
- The client prepares a random replacement handle in the encrypted vault before
  rotation. A lost-response/reopen regression passes without saving a Google ID
  token or password. Tests cover offline retention, mismatched response rejection,
  one retry after 401, credential retention across profile changes and remembered-
  only sign-out revocation without deleting keys or retained content.
- The actual background/reopen UI test passes for both login providers. Its first
  attempt was blocked by a screenshot-confirmed Android System UI ANR; the test
  passed unchanged after dismissing that system dialog. This is synthetic provider
  state, not a new real Google-provider/OEM acceptance claim.
- Reproduced crossed mutual profile requests discarding the first authorized photo
  reply. The fixed request handling accepts it, preserves mutual verification and
  search exclusion, and retries old unanswered mutual requests after at least one
  minute instead of waiting 12 hours. Both focused regressions pass.
- All 66 Android QA methods passed with QA lint. The subsequent remembered-only
  sign-out edge case also passed its strengthened focused test. Full JVM verify
  passed 44 executed tests; one opt-in live JVM test remains separately skipped.
- The renewal-capable relay is deployed only to vanishr-dev-rg without an account
  reset or schema change; existing provider settings and infrastructure size are
  preserved, trusted public TLS/health passes and temporary transfer storage was
  removed. The relay artifact excludes client crypto dependencies.
- Signed release build/lint and the original signing identity pass. Version is
  0.3.4, code 16, 44,217,497 bytes, SHA-256
  `5a2f552fbccd11df6379134f82c445622c57d5a9c75bc538b7ac78844988a9e2`.
  Five protected-device tests and the complementary no-screen-lock rejection
  pass. The signed live workflow passed in 272.805 seconds, including forced
  access expiry, automatic renewal, encrypted send after reopening without a
  password, explicit remote revocation, profiles/photos, groups, account switching
  and retained identities.
- Published on 2026-09-21 at https://vanishr-download.vercel.app using the same
  static Hobby project. Deployment `dpl_9Kt5bXu7xSRUEBnN2JYBA9FEJc6b` is READY with
  zero functions/build jobs. Its 27-file bundle totals 58,514,535 bytes; 171 local
  source files match the archive and 389 source entries were audited. Known
  private values, signing/server material and native test libraries are excluded.
- All 26 anonymous public downloads match the audit. The public APK verifies
  with the original signer; the code-16 feed is no-store. Four crawlable pages,
  seven canonical redirects, CSP/schema hashes and nine private/missing 404s
  pass. Existing website analytics/ownership IDs and consent behavior are retained.
  Evidence is in `.tools/release-verification-0.3.4/verified-artifact.json`,
  `.tools/distribution-audit-0.3.4.json`, `.tools/public-release-verification-0.3.4.json`
  and `.tools/public-website-verification-0.3.4.json`. The source archive is the
  pre-publication snapshot; these status-only documentation edits came afterward.
  The dedicated test emulator was stopped; unrelated emulator state was untouched.
  Real Google-provider and OEM acceptance remain unverified, not implied by the
  synthetic Google-session fixtures or the signed password-account live workflow.

## Public Website (2026-09-21)

- Published four static information pages at https://vanishr-download.vercel.app,
  with canonical redirects, social metadata, parseable structured data, robots,
  a four-URL sitemap and an optional factual llms.txt summary. Product images use
  fictional native test fixtures; fonts/icons and their licenses are self-hosted.
- Deployment `dpl_HCpif8FbbgChK28QjnAToaZ6qZg4` is READY on the existing Vanishr
  Hobby project, with zero functions/build jobs. Its 27-file audited stage totals
  58,031,649 bytes. All 26 public files match their audited hashes, seven canonical
  redirects pass and nine private/missing paths return 404. The generated CSP
  hashes each inline JSON-LD block and contains no unsafe-inline or unsafe-eval.
- Browser checks cover 320x568, 390x844, 1280x800 and 1440x600 layouts, no horizontal
  overflow, nonoverlapping hero copy/product images, loaded assets/font/icons,
  navigation, FAQ and consent controls. All four pages' local links resolve.
  The signed 0.3.3 APK, corresponding source, notices, license and no-store update
  feed are byte-for-byte unchanged from their prior public verification.
- GA4 property `555211785` / web stream `15815337269` / measurement ID
  `G-T42R6HPR2E` belongs to the separate Vanishr Website property, with India time
  and INR reporting. No native app stream or advertising integration was added.
  Enhanced measurement, Google signals and user-provided data remain disabled.
  Property-level ads personalisation is disallowed in all 307 regions.
- Consent fixtures verify default-off, explicit permission, saved refusal,
  180-day expiry, GPC and Do Not Track. The public site made zero Google requests
  before consent. Google returned HTTP 204 for the consented page-view request
  and the batch containing the source-download event. Page locations exclude
  query strings/fragments. No CSP violations were observed during collection.
  Live revocation made zero further Google requests, removed analytics cookies
  and reloaded without the tag. These QA events can appear in initial reports;
  consent-based measurement necessarily misses visitors who decline or block it.
- Search Console verified ownership through the deployed HTML meta tag. Sitemap
  submission reports Success and four discovered pages. Homepage inspection says
  Discovered - currently not indexed. The manual indexing request was rejected
  with Quota exceeded and Google's instruction to retry tomorrow; no further
  request was attempted. Indexing, ranking and inclusion in AI answers are not
  guaranteed by this work.
- Local evidence is in `.tools/website-build-0.3.3.json` and
  `.tools/public-website-verification-0.3.3.json`; the latter is reproducible with
  `.tools/verify-public-website.ps1`. The immutable app release/source archive
  predates this website and subsequent status-documentation edits. No app rebuild,
  account reset, relay deployment, Azure change or git commit was performed for
  the website.

## Release 0.3.3

- Adds choose/preview/save/remove profile-photo controls. Normalized images are
  bounded square JPEGs stored in the encrypted account vault and delivered over
  official Signal sessions only between mutually saved, independently verified
  contacts. Search and verification results remain photo-free.
- Focused Android tests cover unknown and unsolicited sender rejection, mutual
  request/reply, removal/revocation, original-deadline retries, outbox capacity,
  identity-change cache removal, no chat-history side effects, bitmap cleanup,
  photo expiry, search exclusion and retained-account isolation. A preview bitmap
  ownership bug was reproduced and fixed. UI waits blocked by launcher/System UI
  ANRs passed unchanged after those confirmed emulator dialogs were dismissed.
- The recipient-only profile relay integration test passed with real Redis,
  PostgreSQL and libsignal ciphertext. It covers authorization, expiry bounds,
  retries/nonresurrection, size limits, and absence of photos in lookup/chat APIs.
- All 58 Android QA methods passed across four completed batches, with QA lint.
  Five additional UI checks passed at 320dp with 130% text. Full JVM verification
  passed 40 executed tests; one opt-in live-relay JVM test was skipped separately.
  The relay artifact still excludes client crypto dependencies.
- Signed release build, release lint and original-signer verification passed.
  The APK is version code 15, 44,217,497 bytes, SHA-256
  `2795ab9cabc72cfb117cc0a9b16e8d5d4fb02059527795a4167846ec44c2b4f7`.
  Five protected-device security methods and the complementary no-screen-lock
  rejection passed. The signed live direct/group/profile-photo workflow passed
  in 256.348 seconds, including avatar display/removal, lookup exclusion, session
  recovery and retained account switching. Test-only R8/selection timing issues
  were corrected without weakening the product's privacy checks.
- The profile-packet relay routes are deployed only in vanishr-dev-rg. Existing
  accounts, schema and provider configuration are preserved; temporary artifact
  storage was removed. Published on 2026-09-20 at
  https://vanishr-download.vercel.app on the existing Hobby static project.
  Ten audited files total 57,085,475 bytes; 148 application files match the source
  archive and 366 source entries were checked. All nine anonymous downloads
  match the audited hashes and sizes; the public APK verifies with the original
  signer, the code-15 feed is no-store, security headers pass and six private
  paths return 404. No functions, build jobs or hosting-plan changes were added.
  The published source is the pre-publication snapshot; status-only documentation
  was updated locally afterward.

### Google sign-in and storage

- A POCO X4 Pro 5G on Android 13 was reported to recover after a PIN/pattern/password
  unlock. This is consistent with the documented Android 12-14
  [unlocked-device-required Keystore issues](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean)),
  but the OEM failure has not been reproduced on the development emulator.
- Keystore authentication errors no longer claim that a new install is performing
  a storage update. A fresh vault verifies an in-memory encrypt/decrypt round trip
  before allowing sign-in. Four focused storage checks passed, including unusable
  key rejection without replacement and non-destructive legacy migration.
- Google provider return and token exchange retain a progress screen instead of
  briefly showing the login form. Three focused local tests passed for provider
  return, cancellation and network failure. An initial visibility failure was
  blocked by a confirmed emulator System UI ANR; unchanged tests passed after
  that system dialog was dismissed.
- Ten neighboring identity-retention, Google recovery and background-privacy
  checks also passed, for 17 focused/related passes before integration. These
  checks are included in the subsequently completed 58-method Android run above.
- No account reset, storage-policy downgrade or real Google credential was used
  in these checks. Actual provider
  and POCO-device acceptance still require testing on that phone.

## Release 0.3.2

- The home empty-state label now explicitly centers its text in a full-width
  block, covering No conversations yet, All caught up and No matching conversations.
- Audited the other full-screen placeholders: empty direct/group chats, loading
  and secure-storage errors already use centered text. Inline errors and message
  bubbles retain their contextual alignment.
- Five focused Android checks passed at the default display size and again at
  320dp with 130% text. Assertions measure label/icon positions and every rendered
  text line, including wrapping. The QA app and test package compile successfully.
- Signed release build and release lint passed. The 0.3.2 APK uses version code
  14, is 44,201,113 bytes, and verifies with the original signing certificate.
  SHA-256: `e904da97fedbd9eb8aeb4bd16bbd3e58773717d5ca0a2bb21319c443ffff6e24`.
- Five protected-device security methods passed, followed by the complementary
  no-screen-lock rejection after temporary credential cleanup. The signed live
  direct/group messaging, profile, session-recovery and account-switch workflow
  passed in 279.934 seconds. Earlier attempts were blocked by confirmed emulator
  System UI ANRs and stale lock-screen state; the unchanged checks passed after
  recovery, without disabling security flags or weakening assertions.
- Published on 2026-09-20 at https://vanishr-download.vercel.app on the existing
  Vanishr Hobby static project. The ten-file bundle totals 57,050,389 bytes; 144
  application files match the source ZIP and 362 source entries were audited.
  All nine anonymous downloads match the audited hashes and sizes. The public
  APK verifies with the original signer, the code-14 feed is no-store, security
  headers pass and six private paths return 404. No relay, Azure, hosting-plan,
  function or build-job changes were introduced.
- The published source is the audited pre-publication snapshot; status-only
  documentation was updated locally afterward. Real Google-provider login, FCM
  delivery and OEM security acceptance remain unverified; these functional checks
  are not an independent security assessment.

## Previous release 0.3.1 (historical)

- Compact Classic is implemented in the native client: a 60dp home toolbar and
  56dp search row, unread filtering, compact conversation rows, matching chat
  header/composer, and bottom sheets for profiles, attachments and group actions.
  Manrope weights, Lucide icons, colors and spacing follow the selected A design.
- All 43 Android QA methods have explicit passing results across two recorded
  batches. The first run was interrupted after 35 completed methods; the remaining
  eight passed in a completed batch. No partial run is claimed as a full-suite pass.
- The final font/color adjustments passed the profile and complete-screen-tour
  checks at normal size, followed by five checks at 320dp and 130% text. Those
  cover home geometry/filtering, contact/My profile actions, draft retention,
  keyboard/save reachability, group consent and all primary screen/privacy states.
  A rendered-pixel assertion verifies white secondary sheet buttons. The public
  Material tint API passed the same focused check after release-lint corrections.
- Earlier UI attempts were blocked by confirmed emulator System UI ANRs and
  dead system-service connections. One prolonged run exceeded the synthetic
  session lifetime. The final focused/narrow checks passed on the same fixture
  after recovery, without disabling secure-window flags or changing expiry rules.
- Screenshots render synthetic fixtures only while keeping FLAG_SECURE enabled.
  They do not demonstrate real-device screenshot resistance or identical OEM
  font/system-bar rendering. Real Google-provider login and FCM delivery remain
  outside these UI gates.
- Signed release build and release lint passed. The 0.3.1 APK is version code 13,
  44,201,113 bytes, SHA-256
  `b18a74e7e6cbd9f38edd4e850585316f705669d9a598c930304cad9e42dca195`,
  and verifies with the original signing certificate.
- Five protected-device security methods passed. The complementary no-screen-lock
  rejection passed separately after the temporary test credential was removed.
  The signed live two-account workflow passed in 224.975 seconds: direct
  text/photo/view-once, identity checks, session recovery, profile/nickname edits,
  retained account switching, group invitation/acceptance, encrypted messages,
  per-member receipts, removal and closure through the updated native UI.
- Published on 2026-09-20 at https://vanishr-download.vercel.app using the existing
  Vanishr Hobby static project. Ten audited files total 57,049,171 bytes; 144
  application files match the source ZIP and 362 source entries were checked.
  Known private values, signing/server configuration and native-test libraries
  are excluded. All nine anonymous downloads match the audited hashes and sizes;
  the public APK verifies with the original signer, the code-13 update feed uses
  no-store, security headers pass and six private paths return 404.
- No relay, Azure infrastructure, hosting-plan, function or build-job changes
  were introduced. The published source is the audited pre-publication snapshot;
  release-status documentation was updated locally after publication. These
  functional checks are not a production security assessment or OEM certification.

## Previous release 0.3.0 (historical)

- Invite-only groups support up to 200 members including owner and pending
  invitations, owner add/remove/close, member accept/decline/leave, member lists,
  encrypted text/photos, expiry/view-once and per-member receipt counts.
- Official libsignal sender keys are scoped to group/epoch/sender. Canonical
  owner-approved rosters and sender-key distributions travel inside pairwise
  Signal messages. Members independently verify the owner and explicitly consent
  to the owner verifying invitees. Membership changes rotate the encryption epoch;
  no old keys are sent to new members and removed members lose relay access.
- Full JVM verification passed: 38 executed tests, with the opt-in live-relay
  test skipped separately. This includes 200-member capacity, 199-recipient real
  native decryptions, one shared 2 MiB encrypted photo, per-member consumption,
  TTL/nonresurrection, control authorization, tamper/replay/context rejection,
  refreshed distributions with unread messages, and the no-client-crypto relay
  artifact plus exact metadata-only schema checks.
- All 40 Android QA tests passed. Two final group screen/consent checks passed
  at 320dp and 130% text. Earlier attempts were blocked by a confirmed emulator
  System UI ANR/system restart; the unchanged full suite passed after that system
  dialog was closed. No secure-window flags or security assertions were disabled.
- Signed release build, release lint and original-signer verification passed.
  Startup uses WorkManager's supported on-demand configuration. Five protected
  device-security methods passed; the complementary no-screen-lock rejection
  passed after temporary fixture credential cleanup, without skips.
- Signed live two-account workflow passed on the deployed relay: create group,
  invite a verified contact, accept with owner trust, encrypted group messages in
  both directions, aggregate read counts, member removal and group closure.
  Existing direct text/photo/view-once, identity, session-recovery, profile and
  retained account-switch checks passed in the same workflow.
- Published signed APK: 0.3.0, code 12, 44,180,801 bytes, SHA-256
  `cd17875e4ccb75bc498a80161d3e7801fe9fe725357ac62a96dc221f2c915d46`.
  The group relay/membership migration is deployed only in vanishr-dev-rg, with
  no VM resize, extra permanent resource or paid add-on. Temporary artifact
  storage was removed. The ten-file static bundle is 57,018,005 bytes; 139
  application files match the source ZIP, 357 source entries were checked, and
  known private values/test JNI libraries were excluded.
- Published on 2026-09-19 at https://vanishr-download.vercel.app using the
  existing Vanishr Hobby static project. All nine anonymous release downloads
  match the audited hashes and sizes. The public APK verifies with the original
  signing certificate, and the version-code-12 JSON update feed returns
  Cache-Control: no-store. Security headers and six private-path 404 checks
  passed. No functions, build jobs or hosting-plan changes were introduced.

These are functional 200-member tests, not simultaneous 200-phone/OEM or sustained
production-load certification. First setup can require multiple foreground sync
cycles; the owner must come online to approve membership changes. Group sender-key
integration needs independent security review. Google-provider login, actual FCM
delivery and production licensing/security acceptance remain unverified. The
published source is the audited pre-publication snapshot; status-only
documentation was updated locally after publication.

## Previous release 0.2.8 (historical)

- Reproduced a rejected-session UI failure: a foreground 401 only showed an
  error instead of clearing the rejected session and offering reauthentication.
  Expired/rejected bearer tokens and their websocket are now cleared without
  signing out, resetting identity keys or removing retained account content.
  Polling-driven expiry also dismisses stale authenticated dialogs; profile-save
  401s use the same recovery path.
- Google reauthentication clears provider credential state, then requests a
  fresh account-only challenge. It no longer binds an old device ID into the
  challenge, which could otherwise repeat 401 before enrollment was reachable.
  The verified account must match the retained UUID, and enrollment reuses the
  original device UUID/public key. Matching registration remains idempotent;
  a different device still requires explicit replacement. Returned enrollment
  sessions are checked against the requested account and device.
- Nine new controlled regressions cover rejected sessions, expired bearer
  removal on reopen, fresh challenges, same-key reenrollment, wrong-account
  rejection, explicit replacement, mismatched enrollment responses, failed
  provider resets, pause/resume and profile-save expiry. All 34 QA tests passed.
  One existing offline-send state wait timed out while the release build ran;
  that unchanged test and the unchanged full suite passed sequentially.
- Signed release build, lint and original-certificate verification passed. All
  five protected-device security methods passed; the complementary no-screen-lock
  rejection passed separately after fixture cleanup without skips.
- The signed live workflow passed after actually revoking a synthetic account's
  relay session while backgrounded, resuming and signing in without local logout
  or device replacement. The same registered device/public key and conversation
  survived, and consumed view-once content did not return. Existing encrypted
  text/photo/receipt/profile/account-switch checks also passed.
- Published signed APK: version 0.2.8, code 11, 44,114,745 bytes, SHA-256
  `18721e4cb49c5fe003c769c899800b4212915b323cf9f74459c900d7961a29c8`.
- Published on 2026-09-19 to the existing Vanishr Hobby static project at
  https://vanishr-download.vercel.app. All nine public release assets match the
  audited hashes/sizes, the public APK has the original signing identity, and
  the version-code-11 JSON feed returns Cache-Control: no-store. Security
  headers and six private-path 404 checks passed. The ten-file static upload
  is 56,914,869 bytes, with no functions, builds or paid-plan changes.

Google-specific tests use controlled transport/provider-reset fixtures, not a
real Google account. The live session-recovery test uses password authentication;
actual Google-provider reauthentication on the user's phone remains unverified.
Session TTLs, one-use nonces, message expiry and explicit device-replacement
rules remain unchanged. No personal phone data or relay infrastructure was reset.

## Previous release 0.2.7 (historical)

- Reproduced the send-feedback delay with a blocked background worker: the old
  handler left the draft in the composer until peer lookup, protected storage
  and relay upload finished. The regression now passes with immediate composer
  clearing and a Sending bubble before the worker runs. Rapid sends do not
  share the global busy gate or overwrite the next draft.
- Up to eight unstored sends have fixed IDs/deadlines from their original tap.
  Failed preparation offers retry/discard, retries preserve the deadline, and
  backgrounding cancels/clears unstored UI payloads. Existing encrypted outboxes
  retain their normal delivery/expiry behavior. Sending is not a delivery claim;
  first-session setup and relay latency still depend on the network.
- My profile uses accessible 48dp inline save icons inside both text fields,
  compact action rows, in-place saving/success/error states and no dialog reopen
  after a save. Editing clears stale feedback and saving one field preserves the
  other draft. Contact profile remains separate with a read-only username.
- The 25-test QA screen/storage suite passed after the send/profile changes.
  Five focused regressions passed again at 320dp with 130% text after the final
  field-feedback polish; keyboard accessibility and popup layout were checked.
- Signed release build, lint and original-certificate verification passed.
  Five signed device-security methods passed; the no-screen-lock case skipped
  on the credential-protected fixture, then passed separately after credential
  cleanup without skips. The signed live two-account workflow
  passed, including text/photo/view-once/read receipts, phone lock/unlock,
  inline profile and username saves, and retained account switching.
- Published signed APK: version 0.2.7, code 10, 44,114,745 bytes, SHA-256
  `000d47824cb7fea6dda984cccfae5f8d03731c272aedeb053bef6d9869c1e96d`.
- Published on 2026-09-18 to the existing Vanishr Hobby static project at
  https://vanishr-download.vercel.app. All nine public release assets match
  audited hashes and sizes, the public APK has the original signing identity,
  and the version-code-10 JSON update feed returns Cache-Control: no-store.
  Security headers and six private-path 404 checks passed. The ten-file upload
  is 56,910,205 bytes, with no functions, builds or paid plan changes.

This is UI responsiveness, not a guarantee of instant network delivery. Real
Google-provider sign-in and FCM delivery were not included in this automated
workflow. No personal phone data or Azure infrastructure was changed.

## Previous release 0.2.6 (historical)

- The separate Unlock Vanishr screen, credential intent and Lock actions are
  removed. Phone-unlocked foreground startup/resume opens encrypted storage
  automatically; pausing still clears visible content and the in-memory engine.
- Protected-record version 2 uses phone-unlocked Keystore keys, not the old
  five-minute authentication requirement. Synthetic version-1 root/content
  fixtures demonstrate migration across active/parked accounts, unchanged
  identity/deadlines, no view-once consumption and rollback on tampered content.
  These fixtures do not prove every OEM's legacy authentication-key behavior.
- All 21 QA screen/storage tests passed, including retained accounts, profile
  ownership, contact names, keyboard/expiry, migration, Google error return,
  automatic opening and update-prompt deferral/dismissal.
- All six updater JVM unit tests passed: newer/same/older/incompatible versions,
  strict fields/types and bounded metadata, exact HTTPS download URLs, cooldown,
  no account headers, no redirects, and offline/oversized responses.
- Release lint and the signed Google-configured build passed. Five device-security
  methods passed; the no-screen-lock test skipped on the protected fixture.
  That complementary no-screen-lock rejection test then passed separately after
  fixture credential cleanup, without skips.
  KeyInfo confirms no timed user-authentication requirement for the new master key.
- The signed live two-account workflow passed without skips, including E2EE
  text/photo/read/view-once behavior, profile/username/contact-name changes and
  retained sign-out/switching. It now locks the phone, verifies vault access is
  rejected, unlocks through Android's normal PIN screen, and returns to the chat
  without any Vanishr authentication prompt. A transient Android keyguard-state
  race found during this check was fixed and the identical workflow passed.
- Daily update checks use the fixed static host on a separate worker. The
  prompt opens a browser download, not a silent installer or executable OTA
  bundle. The checksum is published metadata, not an in-app APK verification.
- Test-only libsignal native libraries are excluded from APK JNI packaging;
  production libraries for arm64-v8a, armeabi-v7a, x86 and x86_64 remain included.
  The signed device-security and live chat workflow passed after this size reduction.
- Current signed APK: version 0.2.6, code 9, 44,097,061 bytes, SHA-256
  `31b9d21d888df9f810f4be17d408c8f8c46640b4ddea4cdc9878745a0164d86e`.
  The original release certificate is preserved. Published on 2026-09-18 at
  https://vanishr-download.vercel.app on the existing Vanishr Hobby static project.
- All nine anonymous public downloads match the audited hashes and sizes. The
  public APK passes apksigner verification with the existing release certificate.
  The live update feed identifies version code 9 and the final APK, returns JSON
  with Cache-Control: no-store, and uses the fixed HTTPS download URL. Security
  headers are present and six private-path probes return 404. The deployment
  has no functions or builds and does not change the hosting plan.

Actual Google-provider sign-in and FCM delivery were not part of the automated
passing workflow. An old key may require one normal phone credential unlock for
migration; already invalidated/erased keys remain unrecoverable. No personal
phone data was reset, and no relay/Azure changes were needed for this release.

## Previous candidate 0.2.5 (historical)

The redesigned candidate is a signed, minified, non-debuggable release build;
the original emulator installation shown in the reported screenshot was the
old 0.1.0 debug package. The separate `.qa` installation contains the new UI.
The old app and its data were not uninstalled or overwritten.

- The QA screen flow passes with the redesigned lock screen, credential forms,
  contact verification, conversation controls, photos and secure dialogs.
- In 0.2.5, the conversation three-dot Profile action opens Contact profile for
  that peer, with exactly one Display name editor and a read-only username. My
  profile remains the separate owner account editor on the Chats screen. The
  duplicate contact-name menu item is removed, but existing locally saved names
  remain intact. The previous menu opened the owner's editor; it did not grant
  authorization to edit another account's username.
- Three focused regressions passed for actual popup-menu navigation, local-name
  saving without changing either username/shared profile, and horizontal centering
  of Just the two of you. Four checks, including keyboard layout, also passed at
  320dp with 130% text sizing. The two existing backend ownership tests were rerun
  successfully; backend code and cloud deployment were unchanged in this update.
- View-once confirmation does not decrypt or consume content. Choosing
  `Not now` preserves it; `Open` consumes the local content key before display.
  The QA flow asserts this and verifies locking when backgrounded.
- Three focused login regressions pass on the isolated QA package: real screen
  taps switch Sign up/Sign in and trigger form validation; connection settings
  are absent; Google picker failures survive pause/resume without unlocking the
  vault; returning accounts show only their original sign-in method.
- The preceding 0.2.4 sixteen-test QA screen suite passed: profile persistence,
  non-destructive sign-out, unchanged signed-out expiry, consumed view-once
  non-resurrection, isolated account restoration and pending outboxes, independent
  content keys for two local copies of a message, safe login error messages,
  username verification, protected dialogs and keyboard-safe spacing, plus
  owner profile/username metadata validation and private-name isolation during
  profile updates, username changes and account switching. Four focused name,
  contact and keyboard checks also passed at 320dp with 130% text sizing.
- The owner can edit a globally unique username and a non-unique shared profile
  name. Private contact names stay only in that account's encrypted vault and
  are not changed by shared profile refreshes. Sign-out retains each account's encrypted
  content, contacts, Signal state and device keys but removes its session token.
  Reauthentication restores the same identity instead of replacing the device.
- The login screen uses the built-in relay for a new account and preserves a
  saved account's relay. Form values are captured on the UI thread before login
  work starts. Google failures use fixed messages, not provider exception text;
  cancelled, unavailable, missing-account and expired flows permit retry.
- All 28 JVM tests executed in the latest full local run passed: four crypto,
  fifteen relay integration, three Google verifier, four generic notifier and two
  retention-policy tests. The opt-in live relay probe is skipped in a normal run.
- On the signed APK, device tests have demonstrated native Signal/Gson
  interoperability, disabled backups/cleartext, secure window flags, generic
  notification construction, encrypted vault reopen and content-key deletion.
  Tests requiring an unsecured device skip on a credential-protected fixture;
  some first-boot runs also skip the positive vault test until the device is
  unlocked. Report these skips explicitly, not as passes.
- Release lint reports no errors. The Google no-credential handling warning is
  resolved; unrelated resource and dependency-update warnings remain.
- The full UI-driven signed workflow passed against the public relay: actual
  Android credential unlock, signup, contact lookup by username, safety-number
  verification, encrypted text and image delivery, read receipts, view-once
  cancellation/consumption, background lock, profile save, confirmed sign-out
  and creation of a second account with a different identity and empty contacts.
  It now also signs back into both accounts, verifies unchanged device IDs/keys,
  receives a message queued while signed out, sends between the two local accounts,
  and preserves the sender's copy with the receiver's READ receipt after switching.
  The 0.2.5 run also renames both usernames in My profile, verifies unchanged
  identities/device IDs and login with the new usernames, reads owner-edited
  profiles, edits local contact display names `abc` and `xyz` through each chat's
  Contact profile while asserting the username is not editable, confirms those names
  survive the other account's username change, and removes a private override
  to reveal the shared profile name. Other users' profile values remain unchanged.
  This acceptance run uses password accounts, not real Google-provider tokens.
- The Google-configured 0.2.5 APK (version code 8) and release instrumentation build successfully;
  signature verification preserves the original certificate. The current APK is
  87,472,277 bytes, SHA-256
  `44ab1eecc493fb535f6443f55754026aeb0e193874221b3d054a21dcaa1a0fa2`.
- Google/FCM provider settings are deployed on the approved dev/test relay.
  Official Google service-account authentication and FCM validation-only
  authorization passed without delivering a notification. Public challenge
  audience/deadline, invalid-token rejection and replay rejection also passed.
  Actual Google account sign-in and live FCM device delivery remain unverified.
  See [GOOGLE-SETUP.md](GOOGLE-SETUP.md) for provisioning details and fingerprints.
- The post-deployment public HTTPS probe passed again with Signal text, three
  maximum-size encrypted image transfers, recipient authorization and read
  deletion. All four host services are healthy; the provider credential is
  readable but not writable by the relay, with no host swap enabled.
- The signed device-security run passed five methods and skipped the
  no-screen-lock case on the protected fixture. The separate signed workflow
  passed without skips. Earlier System UI ANRs and test-only R8 linkage failures
  do not replace this final result, and this result does not establish successful
  Google account login or FCM device delivery.
- On 2026-09-18, the verified relay artifact was deployed only to the existing
  `vanishr-dev-rg`. Identical device enrollment now resumes without resetting
  prekeys; changed identities still require replacement. Logout remains available
  when the authentication-attempt quota is exhausted, while both login and Google
  challenge quotas remain enforced. Public trusted-HTTPS health and the signed
  two-account workflow passed; temporary deployment staging was removed and the
  subscription spending limit remained On.
- The profile migration and owner-only username/profile endpoints were subsequently
  deployed in that same group. Unauthenticated writes and target-ID injection are
  rejected, duplicate/concurrent username claims are tested, and renaming preserves
  Google's subject mapping. Shared profile metadata is an explicit privacy change;
  private nicknames and old local-only profile names are not automatically uploaded.
  The existing sub-second expiry fixture failed once during concurrent builds and
  passed in the unchanged serial full-suite rerun. Emulator system ANRs were cleared
  before the final input tests. Test-only R8/linkage and completion-wait defects were
  corrected before the signed workflow passed without changing production behavior.
  The 0.2.4 short private-name field hint was checked at 320dp/130% text sizing.
  In 0.2.5 that separate editor is replaced by Contact profile, with the routing,
  read-only contact username and centered empty-chat tests described above.

Version 0.2.2 deliberately erased keys on sign-out; that behavior caused the
reported account-switch message loss and is corrected here. This update cannot
recover keys already erased by that version or recover expired messages. Old
affected registrations may still require explicit replacement and re-verification.

`scripts/test-release.ps1` operates only on the dedicated `Vanishr_Release_Test`
emulator and runs the same release-signed APK intended for distribution. It uses
temporary emulator credentials, synthetic accounts and synthetic content, and
clears only its dedicated fixture. Secure flags remain enabled; internal-view
captures are produced only by signed test instrumentation and never by the app.

No Azure group was deleted or replaced by this UI/release-candidate work.
The current Visual Studio benefit remains dev/test hosting. A production
migration requires production-eligible hosting, cost approval, security review,
provider acceptance tests and a safe cutover before retiring the old endpoint.

## Demonstrated

| Check | Evidence |
| --- | --- |
| JVM protocol/core | Four JUnit tests using official libsignal 0.102.3 native code on Java 21: independent identities, real Signal round trip, mandatory trust, tampering/replay rejection, independent image keys/AAD, authenticated expiry/context |
| Relay integration | Fifteen tests with real digest-pinned Redis and PostgreSQL containers: encryption/deletion/TTL and account/device security plus owner-only usernames/profiles, concurrent unique-name claims and stable Google identity after rename; no private content or contact-nickname tables |
| Retention policy | Two tests: all expiry modes reject stale/unbounded deadlines; Redis persistence and replication rejected |
| Android build | Debug and unsigned release APKs build with API 36, Java 21 and pinned Gradle 8.13; debug/release lint has zero errors (three version-availability warnings) |
| Signed release packaging | Original local release key reused; apksigner verifies the APK Signature Scheme v2 signature; 0.3.0 is about 42.1 MiB after excluding test-only JNI libraries. Signing does not establish security-review or public-backend readiness |
| Android native protocol | Official Signal JNI loads on API-36 x86_64 emulator; Signal encryption/decryption and Gson records/Base64 interoperate |
| Android configuration | Runtime verifies backup flag disabled, cleartext network disallowed, plaintext HTTP origin rejected, vault closed until explicitly opened by the foreground lifecycle |
| Android launch | Instrumentation launches the actual activity, verifies FLAG_SECURE and visible control hierarchy |
| Android secure storage | Dedicated emulator verifies phone-unlocked key policy, locked-phone rejection, automatic reopen after Android unlock, encrypted atomic persistence and inability to decrypt after content-key deletion |
| Local TLS deployment | All three Compose services reach healthy state with certificate verification: HTTPS relay, TLS Redis and PostgreSQL `verify-full`; no cleartext fallback |
| Low-memory deployment | Capped local stack passed real HTTPS Signal text and three 2 MiB encrypted-blob round trips/deletion. The Azure ARM VM then passed the same test without a custom CA or TLS bypass |
| Azure isolation and cost | Created only vanishr-dev-rg after a what-if preview confined to it. Fixed Standard_B2pts_v2 VM, 32 GiB Standard HDD and public IP; INR 1,200 monthly budget alert. Existing groups and subscription Spending Limit On were preserved. Temporary artifact storage was deleted |
| Public distribution | Vercel Hobby static deployment at https://vanishr-download.vercel.app. Anonymous APK download matches the signed local SHA-256; matching application/libsignal source and notices are accessible. Page checked at desktop and 320/390-pixel mobile widths |

Hosted APK 0.3.0 SHA-256:
`cd17875e4ccb75bc498a80161d3e7801fe9fe725357ac62a96dc221f2c915d46`.
The endpoint is prefilled and verified healthy during the signed live workflow.
Release lint passed. The source archive was audited against private filenames
and known local secret values before upload. The ten-file static upload is
57,018,005 bytes, including the deployment configuration; nine files are public
release assets. The published source is the audited pre-publication application
snapshot; release-status documentation was updated locally after publication.
Sixteen optional Maven source JARs are unavailable upstream (primarily Google
SDK components); the inventory records this and complete libsignal source is
provided separately.

The original five-method Android suite was exercised across two device states: four
pass on the unsecured fixture and the credential-dependent method skips; that
method then passes separately after temporary credential provisioning. The
runner removes the temporary credential/test data afterward. The VS Code
verification task also completed successfully.

The JVM integration tests inspect stored ciphertext and verify that an actor
without device keys cannot decrypt it. They demonstrate the implemented trusted
client boundary; they cannot prove that every arbitrary client uploads ciphertext
or that an attacker-controlled server will delete data. The relay production
artifact must contain neither `client-core` nor `libsignal`.

## Not Yet Demonstrated

- A complete two-physical-phone chat session, camera/gallery edge cases,
  OEM Keystore/StrongBox behavior, biometrics and backup/device-transfer behavior.
- A complete conversation using the final signed release APK on two physical
  phones. Signed-emulator and JVM live tests use synthetic peers against the
  actual public relay, not two physical phones.
- Guaranteed zero cost or isolation from shared Azure credit exhaustion. The
  approximate INR 901/month baseline consumes credits, and budgets are alerts,
  not hard caps. Existing resource groups were not modified, but credit is shared.
- Live FCM delivery, token rotation under background
  restrictions, user notification permissions and provider outage handling.
- Exact background expiry timing while powered off, in Doze or under OEM task
  restriction. Access-time rejection is independent of background scheduling.
- iOS: no client, APNs, Keychain or Secure Enclave integration is included.
- Production TLS certificate rotation, horizontally scaled realtime delivery,
  independently audited key transparency, password recovery or remote account erasure.
- Load testing, distributed abuse resistance, DoS resilience and third-party
  dependency/advisory/license audit. Pinned dependencies can still be vulnerable.
- Physical erasure of RAM, flash, hardware key slots or old ratchet snapshots.
  Host swap/hibernation/crash snapshots and unauthorized backups are operator risks.

## Important MVP Limits

Clock synchronization is a security assumption. Content deadlines are authenticated
and capped on both sides, but a user/compromised OS can change a device clock.
No secure monotonic cross-reboot clock or trusted time attestation is included.

Screenshots are discouraged using `FLAG_SECURE` on activity and dialogs, recents
capture disabled where supported, overlay windows hidden where supported, and
content cleared when backgrounded. This does not prevent cameras, malicious OS
software or a modified recipient client.

Camera capture uses the system camera's in-memory preview result for this MVP,
so resolution is lower than a full-resolution camera capture. The camera/gallery
provider may keep its own original file; Vanishr does not erase the user's photo
library or guarantee the privacy of third-party source apps. Vanishr normalizes
the selected bytes in memory and never creates a plaintext media cache.

The vault's nonexportable AES wrapping key protects serialized libsignal state;
the Signal/Kyber private keys are not themselves nonexportable hardware objects.
They exist in process memory while unlocked, as required by libsignal.

Read and delivery status are unauthenticated relay metadata, not cryptographic
proof of viewing. Once a sender deletes a message, already delivered local
recipient content still follows its original local expiry. There is no remote
device wipe or guarantee against recipient copies.

## Required Before Production

1. Independent application/protocol-integration security review and AGPL legal review.
2. Supply-chain review, dependency vulnerability scan, reproducible signed release,
   protected signing-key backup and licensing compliance before public APK distribution.
3. Two-device manual workflow and instrumentation across supported Android versions/OEMs.
4. Backup/transfer/lock/removal/reinstall/clock-tampering and process-death tests.
5. Live FCM and TLS trust/renewal tests using dedicated test credentials.
6. Operations verification: Redis no persistence/replication, host/VM no swap or dumps,
   PostgreSQL minimal data, no content/header/SQL logging or analytics/APM capture.
7. Abuse/load tests and recovery procedures that never resurrect expired content.

Android lint currently reports newer compatible-line/library release suggestions;
the chosen versions are pinned. OkHttp 5.5 requires API 37, while this workspace
uses the installed API 36 toolchain and compatible OkHttp 5.3.2. Do not suppress
security or compatibility errors to force a release.