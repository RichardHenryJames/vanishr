# Verification and release gates

Evidence refreshed on Windows, 2026-10-17 UTC. This is a development foundation,
not a production security assessment or independent audit.

## Release 0.6.2 / Signed settings and Google Play sign-in

Release 0.6.2 / code 43 answers two things from one report: "Continue with Google" showed "Google sign-in
cancelled" on the copy installed from Google Play, and a request for a way to change things without a
new app version, as another project of the owner (an Expo app that uses EAS Update) can. It is a
**client-only change** plus tooling and a signed file on the download site: no relay or schema change (the
relay stays at schema 13; `git diff ab93146 HEAD -- relay/src/main` is empty) and no Azure operation. One
infrastructure script did change and is **not deployed**: `infra/azure/install-artifact.sh`, the installer that
runs on the VM, accepted exactly one Android client ID for Google sign-in and now accepts one to three (below);
it takes effect only the next time the owner publishes the relay.

**Google sign-in on a Google Play install: a setup gap, not a defect in the bundle.** Found by reading the
code and the configuration; **not reproduced** (Play Console, Firebase and the owner's phone were not
available):

- [GoogleSignIn](../android/app/src/main/java/app/vanishr/android/GoogleSignIn.java) uses Credential
  Manager (`GetSignInWithGoogleOption`), and a `GetCredentialCancellationException` is shown as "Google
  sign-in cancelled", which is the message that was reported.
- The Firebase configuration holds exactly one Android OAuth client, tied to the SHA-1 of the local release
  key (the upload key). Google Play App Signing **re-signs the delivered app with Google's own key**, so a
  Play install presents a different certificate, Google finds no Android client for that package and
  certificate, and Credential Manager reports a cancellation. The relay would also have refused the token:
  it accepts a Google ID token only if its `azp` is in `GOOGLE_ANDROID_CLIENT_IDS`, and the release scripts
  passed only the one local client.
- Nothing in the 0.6.1 bundle was found to be at fault (its validation is recorded under 0.6.1). The same
  APK from the website, signed with the local key, was not affected, which is consistent with this reading.
- What changed: [google-certificates.json](../scripts/google-certificates.json) (the upload SHA-1 and a
  `playAppSigningSha1` that is empty until the owner has it), [read-google-config.ps1](../scripts/read-google-config.ps1)
  (when that SHA-1 is set it requires an Android OAuth client for it in `google-services.json` and passes both
  client IDs to the relay; it refuses a missing, duplicated or malformed one) and
  [test-google-config.ps1](../scripts/test-google-config.ps1) (21 checks, 8 of them new for this release, all
  passing; the real configuration still validates). The owner's steps are in [GOOGLE-SETUP.md](GOOGLE-SETUP.md#phone-installs-from-google-play-play-app-signing).
  **They are not done and not verified:** they need the Play app-signing certificate from Play Console, a new
  `google-services.json` from Firebase and a relay publish (a short restart). Until then Google sign-in on a
  Play install keeps failing; no rebuild of the app is needed for it.
- **A second defect, found by review and fixed: the VM-side installer could not have accepted both client
  IDs.** `read-google-config.ps1` hands the relay a comma-separated list, but the `jq` pattern in
  [install-artifact.sh](../infra/azure/install-artifact.sh) allowed exactly one ID, so a publish with the Play
  certificate registered would have been refused on the VM. The pattern now allows one to three. This was
  shown with the real `jq` (the official 1.7.1 image, the exact filter extracted from the script, synthetic IDs):
  the old pattern rejects two and three IDs, the new one accepts one to three and rejects four, a leading or
  trailing comma, a space, an empty value, another project number and line breaks; the pattern is also pinned
  by `test-google-config.ps1`. It was **not** run against the VM.

**Signed settings** ("OTA" without code). A native Java app cannot be updated the way an interpreted one can,
Google Play forbids an app downloading code that changes how it works, and
[THREAT-MODEL.md](THREAT-MODEL.md#signed-settings-changes-without-an-update-062) already rules out downloaded
code. What was built instead is **data only**: a small JSON file on the download site, signed with an
ECDSA P-256 key that is kept off the website, that the app fetches at most once a day while it is open
(Google Play build included). It can show one plain-text notice on the chat list (with an optional "Learn
more" path on the download site), switch **off** image sending, opening photos and creating groups, and tell
Google Play installs that a newer version exists. It cannot touch keys, messages, expiry times, identity
checks or the relay address, and a bug fix or a new screen still needs a new version.

- [RemoteSettings](../android/app/src/main/java/app/vanishr/android/RemoteSettings.java): the verification
  and parsing (pure functions) and one bounded request. The signature covers a fixed domain prefix plus the
  exact payload bytes; the file is refused whole if it is unsigned, altered, signed by another key, over its
  size limits (12 KiB envelope, 8 KiB payload), not strict JSON, has a duplicate key, a field outside its
  limits, a validity over 60 days, an `issuedAt` more than 36 hours ahead or an expiry in the past. The
  request has no identifiers, cookies, cache, redirects or cleartext, and 3/3/6 second timeouts.
- [RemoteSettingsStore](../android/app/src/main/java/app/vanishr/android/RemoteSettingsStore.java): keeps the
  last accepted file as received and checks it again, signature and expiry included, whenever it is loaded.
  A file is taken only if its sequence number is above every one taken before **from the same key** and it was
  issued no earlier than any file taken before **from any key** (so an old file of one listed key cannot be put
  back over a newer file of another), and never if it was issued before `BUILD_ISSUED_FLOOR`, the issue time of
  the newest file that existed when the version was cut. The issue time that is remembered is never later than
  the phone's own clock, so a file dated into the 36 hours ahead that are allowed cannot make the phone refuse
  the files that really follow it. A hint about an update is dismissed by its exact version code, so a mistyped
  one can be corrected. A failed check is retried after two hours.
- [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java): the key list is read
  and the stored file checked **on the update lane after the screen is up**. This was measured and not
  assumed: done in `onCreate` it cost **34 to 42 ms of main-thread time** in a cold process on the
  emulator (key list 13 to 17 ms, first signature check 18 to 23 ms; a probe run three times in fresh
  processes), which is the kind of start-up cost 0.5.9 removed, so it was moved off the main thread before
  anything else was built on it. Until it has run the app behaves as built. The daily check, the three
  gates (image sending in the attachment menu and in `enqueueSend`, opening a photo, creating a group),
  the notice and update card on the chat list and a "Settings: as built" / "Settings N, valid until ..."
  line in the profile are the rest of it. A file stops counting the moment it expires, with no network.
- Owner tooling, all in PowerShell 7 and all tested without the real key (see below):
  [new-ota-key.ps1](../scripts/new-ota-key.ps1) creates the key (the private half is encrypted and stays in
  `.secrets/ota-signing`, which is git-ignored and must be backed up), restricts that folder, and each file in it,
  to the current user **every time it runs** (not only when it creates it), reads the result back and stops if
  anybody else could still reach it, and refuses a fourth public key; `-RestrictOnly` repairs an existing folder
  without creating a key.
  [sign-ota-settings.ps1](../scripts/sign-ota-settings.ps1) reads `ota/settings.source.json` with a strict JSON
  reader (comments, trailing commas, a name written twice or in two cases and other leniencies of
  `ConvertFrom-Json` are refused; text that looks like a date stays text), applies the app's own rules from
  [ota-settings-rules.ps1](../scripts/ota-settings-rules.ps1), signs, writes `download/ota/settings.json` with
  the next sequence number and a 45-day expiry and re-verifies it. It counts a live file's sequence number
  **only if a key the app trusts signed it**, and **never dates a file earlier than the newest one it knows of**
  (the committed file's, a trusted live file's, or the app's `BUILD_ISSUED_FLOOR`): when the computer's clock is
  behind that time by up to 36 hours it dates the file at that time and says so; behind by more it stops, unless
  `-IgnoreFutureIssueTime` says the clock is right.
  `-Check` verifies the committed file, fails if it was issued before the floor, warns if it is dated ahead of
  the clock and prints what the next version's floor should be; `-Check -AppRelease` (used by
  `prepare-download.ps1`) also requires the floor to equal the committed file's issue time;
  `-Check -Live` is the check *before* a release (only a verified, newer file on the
  website stops it; a missing, expired, unverifiable or oddly served one is a warning because the release
  replaces it) and `-Check -Live -RequireIdentical` the check *after* it (the website must serve exactly the
  committed file, properly served and in force). `prepare-download.ps1`, `prepare-website.ps1` and
  `render-website.ps1` run `-Check` before they create anything. The file is published by a website-only release
  (see [DEPLOYMENT-GUIDE.md](DEPLOYMENT-GUIDE.md)). The committed file is sequence 1, with every feature on and no
  notice.

**Checks run on the source before release:**

- JVM, [RemoteSettingsTest](../android/app/src/test/java/app/vanishr/android/RemoteSettingsTest.java): **30
  tests, all passing** (21 when first written, 26 after the first review, 30 after the second). They cover a good file read back exactly, an empty file changing nothing, features
  that can only be switched off and only by name, a changed payload or signature, another key or an unknown
  key id, the domain prefix, expired / not-yet-valid / too long-lived files, the sequence rule, the
  once-a-day rule, malformed and non-strict envelopes and payloads (single quotes, bare names, comments,
  duplicate keys, numbers), the size limits, the notice text and path rules (control and bidirectional
  characters, `..`, `//`, other hosts), build and version targeting, the update hint, the key list (at most
  three, P-256 only), the request (one anonymous GET, no redirect, JSON only, a body of unknown length cut
  at the limit), the hardening of the HTTP client, and that the **committed `download/ota/settings.json`,
  signed by the PowerShell tool, is accepted by the Java verifier under the key the app embeds**. The shared
  table [ota/rules-cases.json](../ota/rules-cases.json) (131 cases of what a notice, a switch or an update hint
  may contain) is run by this test **and** by the signing tool's own tests, so the two cannot drift apart: what
  the tool accepts the app must accept, and what the app refuses the tool must refuse. The later tests cover the
  per-key sequence floor (a retired key cannot hold back its successor), an older file of another listed key not
  being put back over a newer one, a file dated into the future not holding back the next, the newest issue time
  never going backward even if the phone's clock does, a stored file below the build floor not being used, and a
  dismissed update hint being shown again when the version changes (an earlier one included).
- Emulator (Android 16 / API 36, dedicated test emulator), 14 tests in `ScreenFlowTest` (13 after the first
  review), all passing: the
  embedded keys are the ones in use after the load and a stored file signed by anybody else is not in force;
  a notice shows, "Learn more" opens exactly that page on the download site (checked through an activity
  monitor), "Dismiss" persists across redraws and a different notice shows again; a notice for the other
  build, for another version or from an expired file is not shown; the update hint shows only on a Play
  build, an unsupported-version card cannot be dismissed, and a dismissed hint comes back for any other
  version (an earlier one included); the three switches refuse the action with
  their message, only by name and leave text messages alone, and are checked again at the point of action (the
  retry of an unsent image, a view-once photo whose confirmation was already open, the Create button of a group
  dialog that was already open); the profile says which settings are in force; a notice that the verifier
  would refuse, with nearly 400 line breaks, still cannot push the chat list out of reach (the notice area is
  bounded to 40% and scrolls); a key that signed the highest number does not block its successor; an older
  file of another listed key is not put back over a newer one, and a file dated 36 hours ahead is taken
  without holding back the next one; and the whole daily check (fetch, verify, apply, a lower sequence refused,
  a tampered file, another key, an unknown key id and an HTML page all refused whole with the settings
  unchanged and a retry in about two hours, offline changing nothing, reload from storage under the right
  key, another key, after expiry and after the stored file was altered).
- Tooling: [test-ota-settings.ps1](../scripts/test-ota-settings.ps1) **25 tests, all passing** (23 after the second
  review), run without
  the real key, in a throwaway copy of the repository layout with a key made for the run and, for the
  online path, a web server on the loopback address only: the shared rule cases (a refusal has to come from a
  rule, not from an engine error); the strict JSON reader (a name written twice, **or in two different cases**,
  comments, trailing commas, leading zeros, depth, dates kept as text, number types);
  signature, domain prefix and key-id handling; the file-level rules (expiry, validity, sequence cap,
  `features: null`); the comparison with what the website serves before and after a release (including a
  forged or retired-key live file that must never be counted, the content type of `application/json-seq`
  or `jsonp` refused, and `-IgnoreFutureIssueTime` setting aside a live file dated 40 hours ahead but not one dated
  3 hours ahead, and still holding it to the sequence rule of its key); signing, counting the sequence up, a retired
  key, a hand-picked sequence, two keys
  that could sign; the refusal reasons for 16 kinds of bad source; the **issue-time rules** (a floor ahead of the
  clock dates the file at the floor, a floor more than 36 hours ahead stops it, a committed file dated 2 hours ahead
  is followed by a file with the same issue time and one dated 40 hours ahead stops the tool until
  `-IgnoreFutureIssueTime` is given, the switch leaves a file dated 3 hours ahead alone, the edge itself (a file 8
  seconds under 36 hours ahead is followed, one a minute over stops the tool) and the two sources together, in both
  combinations), what `-Check` prints, and `-AppRelease` (the floor must equal the committed file's issue time, and
  on its own it never signs); the key folder and each file in it
  (loosened as an older script left it, a file with entries of its own, then repaired; `-RestrictOnly`; the grant
  made to the SID; a fourth key refused with nothing written); the website render and the website stage refusing
  an expired file before they create anything (both executed); and, for the release staging, the **order** of its
  checks (read from the script, not executed) with the leftover half-written file check itself executed. The
  permissions test is skipped, and reported as skipped, on a system other than Windows.
  `test-google-config.ps1` 21 of 21. A standalone .NET `ECDsa` check of the committed file under the embedded
  public key passes and fails with a different domain prefix; `sign-ota-settings.ps1 -Check` passes on the
  committed file (sequence 1, issued 2026-10-17 06:28:06Z, expires 2026-12-01; `BUILD_ISSUED_FLOOR` equals
  its issue time).
- **The mutation harnesses are not part of the repository.** They were written and run from the working folder
  of the release session and are kept with its evidence, so the mutation figures below are as reported and cannot
  be reproduced from a checkout; the tests they were run against are in the repository.
- **JVM mutation checks** (`RemoteSettings.java` and `RemoteSettingsStore.java` altered a line at a time in
  temporary copies that are compiled with `javac` and run against `RemoteSettingsTest`, so the repository's own
  files are never touched; a mutation counts as caught when a test written for it fails): **71 mutations, all
  caught by the test written for them.** The set grew with the reviews. The first pass of 34 was caught by the
  intended test for 29; **five were not**: lenient JSON accepted in the envelope, lenient JSON accepted in
  the signed payload, redirects followed, a response body of unknown length not cut at the limit, and "naming
  a feature with true switches it off" (which a different test did catch). Tests were added for each, and the
  set was rebuilt and extended after each review: **57** mutations after the first, **68** after the second and
  **71** after the third, with a mutation that no longer applied replaced. The mutations: domain prefix
  dropped; signature result ignored; expiry check, not-yet-valid check and validity cap removed; the sequence
  cap removed; any sequence and the same sequence accepted again; one floor for all keys, the floor never
  recorded, the time of a check not recorded; the signing key not recorded on the settings; the **issue-time
  high-water mark ignored, the build floor ignored,
  a file issued at the very same time refused, the issue-time check dropped, the newest issue time never
  recorded, accepted when negative, not held to the phone's clock, or allowed to go down, a stored file
  below the build floor used, and the next check not brought forward when such a file is dropped (or brought
  forward for every refused file)**; envelope version and schema unchecked; duplicate keys accepted (envelope,
  payload); lenient JSON (envelope, payload); size limits removed (envelope, payload, chunked body); more than
  three keys; non-P-256 keys; notice path characters (dots allowed), segment count and segment length;
  control, line-separator, format, private-use, surrogate, noncharacter and replacement
  characters allowed in text, the joiners that emoji need refused, characters an older phone does not know
  refused, the line-break cap ignored, line breaks in
  a title, blank text allowed; notice version bounds and build targeting ignored; update hint on direct builds;
  update minimum above latest; a hint at or below the dismissed version staying hidden, a dismissed hint never
  shown again, the dismissal keeping the larger version, the notice and the hint sharing one dismissal;
  features off by default; a mentioned feature switched off whatever its value; the check due on every
  resume; a failed check retried only tomorrow; a non-200 and a non-JSON response accepted; redirects followed;
  cleartext, cookies, cache, retries and long timeouts allowed.
- **Tooling mutation checks** (the PowerShell tools altered in a mirrored copy and `test-ota-settings.ps1`
  run against it; the repository is never touched): **40 mutations, all caught, each at the test written for
  it** (the failing test was read, not only counted; 21 after the second review, 34 after the third): a live file's
  sequence counted
  without verification, and its issue time left out of the clock rules; **a file dated a little ahead followed by
  an earlier one, one dated far ahead always followed, never stopping the tool, or the override switch ignored or
  applied to files a phone still takes (in the signer and in the pre-release comparison); the edge at 36 hours an
  hour early or an hour late; a set-aside live file skipping the sequence rule of its key; the warning of the switch
  without its time limit; a floor far ahead not
  stopping the signing, or not dating the file; the `-AppRelease` gate removed, not used by the release staging, or
  not implying `-Check` (which would have *signed a new file*);
  the leftover half-written file check removed from the staging or never finding anything; the warning about a file
  dated ahead removed**; the floor not
  enforced by `-Check`; `-RequireIdentical` not
  implying `-Live` (which would have *signed a new file* instead of checking); a missing file stopping a
  release; the after-release comparison skipping the bytes; a live file that is issued later, or has a
  sequence that is not lower for the same key, not stopping a release; a loose content type; a name written
  twice, or in two cases, accepted; `features: null` accepted; the key folder restricted only when created; key
  files not protected; the grant made to the account name; foreign entries
  not removed from it; a fourth key allowed; the early settings check removed from the website stage, the
  render and the release staging; a generic error for a source that is not JSON.
- **Emulator mutation checks** (`MainActivity.java` and `RemoteSettingsStore.java` altered, the QA app and its
  tests rebuilt and the 14 signed-settings tests run on the emulator, the files restored byte for byte after each
  group and the hashes compared at the end): **25 mutations in 7 groups, all caught, each by a test written
  for it** (a group applies mutations that are not caught by the same tests together; every build and test run
  is kept). They were run on the sources as they stood after the second review; the third review then added one
  statement to `RemoteSettingsStore.load()` (the next check is due at once when a stored file is dropped for the
  floor), which the JVM mutations above cover and which the emulator tests below were re-run on. They are:
  expired settings still in force; a dismissed notice coming back; an older file replacing
  the stored one; the attachment menu, the retry of an unsent image, a queued image, opening a photo (menu and
  view-once) and creating a group (the menu and a dialog that was already open) not gated; every feature
  allowed; the shipped keys not used; the profile line inverted; the notice area not bounded; the direct build
  treated as Google Play; a failed check retried only tomorrow; a check not recorded; the unsupported card
  dismissable; "Learn more" opening the site root; the floor read from the wrong key; the newest issue time not
  consulted, never recorded, or not held to the phone's clock; a dismissed update hint shown again, and the old
  at-or-below rule for it.
- **Full emulator QA** (`ScreenFlowTest`, `AccountSafetyTest`, `AccountBackupFlowTest` on the dedicated Android
  16 / API 36 emulator): **234 tests, 232 pass, 2 fail.** The QA app of this run was built from the final
  application sources (the last change to the app's Java files was made before it; later changes are to scripts and
  documents only) but before the release version constants were raised from 0.6.1 / 42 to 0.6.2 / 43, which affects
  nothing the tests use except that they read the running version. The two failures are the
  same two that are recorded for 0.5.8 to 0.6.1 and are not touched by this release:
  `compactClassicProfilesOpenAsSecureBottomSheetsAndKeepTheChatDraft` ("Secondary sheet buttons must render
  white", a colour assertion; run alone on the final build it **passes**, so it fails only inside a full run)
  and `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount` (run alone on the final build it **fails**, at
  a UiAutomator wait of 5 seconds for a "Photos" entry in the conversation menu). Both are **unresolved**:
  neither is fixed here, and the cause of neither is established. The first full run of this release had 233
  tests and the same two failures.
- **The gate.** `verify.ps1 -Android` passed on the final sources (the only change to the build since is the raising of
  the version constants to 0.6.2 / 43, and the signed build below ran from the committed tree), with Docker Desktop
  started for the relay
  integration tests and stopped afterwards: relay 101 tests, 0 failures, the 2 opt-in live-relay tests skipped; the
  Android build and lint with 0 errors and **19 warnings, one more than the 18 of 0.6.1**. The extra one is
  `ViewConstructor` on the new `BoundedScrollView`, which is created only in code and never from a layout file, so
  the layout editor warning is of no consequence; it was left as it is rather than change code that had just been
  verified. The other 18 have the same kinds and counts as the lint report of the 0.6.1 release build (SetTextI18n 8,
  UnusedResources 7, InlinedApi 1, MonochromeLauncherIcon 1, ObsoleteSdkInt 1). It also ran `test-google-config.ps1` (21 of 21),
  `test-ota-settings.ps1` (25 of 25) and `sign-ota-settings.ps1 -Check`. `:app:testDebugUnitTest`,
  `:app:exportDependencyInventory` and notice generation (116 components) passed afterwards, and the regenerated
  notices left the tracked legal assets unchanged.
- **Independent code reviews.** A read-only reviewer with no stake in the code was given the diff and the threat
  model four times, and every finding was checked by reading or running the code before it was accepted.
  *First round, 8 findings, all fixed:* the VM-side installer accepted one Android client ID only (above); one
  global sequence floor could be used up by a stolen key (now per key, with a cap of 1,000,000); a long notice
  could push the chat list out of reach (now a scrolling area of at most 40% of the height that is left); the
  notice and the update card shared one dismissal slot; the signing tool did not apply the app's rules (now a
  shared rules library and the 131-case table above); the stage and deploy checks of the settings file were
  missing; the three gates sat too early (now at the point of action: the retry of an unsent image, opening a
  view-once photo, the Create button of a group dialog that was already open); and some wording overclaimed.
  *Second round, 2 medium and 7 low findings, all fixed:* the signer trusted the sequence number of an
  unverified live file (anyone who can change the website could have used up the numbers); `-Check -Live`
  failed on things that a release replaces and did not demand the exact committed file after one (now the
  two modes above); per-key floors allowed an older file of one listed key to be put back over a newer file
  of another, and the build floor was not per key (now the issue-time mark and `BUILD_ISSUED_FLOOR`); the key
  folder's restriction was applied only when it was created (on the real folder it was inherited rather
  than blocked, and was repaired; the script now applies and checks it every time); the tooling's JSON reader
  was more lenient than the app's; the stage check ran after the stage had been built; there were gaps in the
  tooling tests (the online path, two keys, refusal reasons, the folder restriction, a fourth key); and the
  claims about invisible characters and about what the `photos` switch covers were too broad (they now list
  what is **not** refused and say that opening a photo you sent yourself is covered too).
  *Third round, no critical or high finding, 1 medium and several low ones, all fixed except where stated:* the
  medium was that after the signing computer's clock had once been fast, the tool refused to sign whatever the
  size of the error and gave no way out. The fix the review proposed (ignore any file dated far ahead) would have
  been unsafe, because a clock that is *behind* looks the same from the files alone, so the tool now dates the
  next file at the newer time when the difference is within what a phone allows, stops beyond that, and has an
  explicit `-IgnoreFutureIssueTime` for an owner who knows the clock is right. Low findings: two names that differ
  only in case were merged by PowerShell, so a switch typed in the wrong case could be dropped without a word
  (one order of the pair was signed with the wrong value; the other was refused only further down, by luck; now
  both are refused where the file is read); a local variable that
  shadowed a parameter; the key-folder grant by account name and files not covered (now by SID, files
  included); a forgotten raise of the floor when cutting a version went unnoticed (now stopped by
  `-AppRelease`, which `prepare-download.ps1` runs); engine errors that would have counted as refusals in a
  test and a skipped permissions test counted as passed; a leftover half-written settings file that could ship in
  the source archive (now stopped); and an app update that left a stored file below its new floor waiting up to a
  day for the next check (now due at once). Accepted and written down in the threat model: a phone whose clock is
  behind real time keeps a correspondingly lower issue-time mark. Still weak: the order of the checks in
  `prepare-download.ps1` is tested by reading the script, not by running it.
  *Fourth round (only what the third round changed), no critical, high or medium finding and six low ones:* the
  reviewer agreed with not taking the third round's suggested fix. Fixed: the override's reasoning held only until
  36 hours before the old file's date, after which a phone with a right clock takes that file (so the tool and the
  guide now say until when to publish, and a set-aside file is still held to the sequence rule of its key);
  contradictory advice about the floor when the committed file is dated ahead, and the floor gate running only
  after the release build (it now runs first, in `prepare-download.ps1`, so a forgotten floor costs a message and
  not a rebuild); `-AppRelease` signing a new file when given without `-Check` (it now implies `-Check`); a
  failure message of the key script that said "No key was created" after one had been; and test and wording gaps
  (the exact 36-hour edge, the two sources together, the leftover-file check now executed, and a claim that three
  stage scripts were run when two were). Accepted: a live file that a listed key signed moves the date of the next
  file whether or not phones would take it, which needs the key and costs only phones whose clocks are far behind.
  A review is not a
  proof: it found real problems in all four rounds, fewer and less serious each time, which is not evidence that
  none remain.

**Publication, 2026-10-17 (UTC):**

- **Relay.** Unchanged: `git diff ab93146 HEAD -- relay/src/main` is empty, so the deployed schema-13 relay was not
  redeployed and no Azure operation ran. (`infra/azure/install-artifact.sh` did change, as described above; it is not
  deployed.)
- **Build and stage.** The gate above passed. `package-apk.ps1` built the signed APK from
  commit `2cd09c4` (release lint included): **44,518,588 bytes**, SHA-256
  `061c3128f23ee48f15eeeeda801f63e9125674119464d52c04ab767bfa43f379`, version 0.6.2 / code 43, min API 28, target API
  36, exactly the same 15 permissions as 0.6.1 (compared one by one) and the same certificate as 0.6.0 and 0.6.1
  (SHA-256 `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`; `apksigner` reports the v2 scheme only,
  as it did for those two). `.tools\vercel-download-0.6.2` holds 32 files / 59,957,440 bytes (limit 99,000,000), no
  hidden file and no `.env` (the Vercel project link was added after the audit, as for earlier releases). The source
  ZIP has 467 entries: 249 source files, each byte-identical to its blob in commit `2cd09c4` (13 more than 0.6.1,
  the files of the signed-settings work), plus 218 dependency-source files. Name, content and known-private-value
  scans of the stage, the ZIP and the APK, with seven private values searched for by value (the signing password,
  the Firebase API key and service-account values, the deployment e-mail address and, new in this release, the
  settings signing key and its password), found nothing. This is recorded in `.tools\distribution-audit-0.6.2.json`;
  it was an inspection by the release agent with automated scans, not an independent audit.
- **Vercel.** Deployment `dpl_6v6nEjprYRwip3YD7UrPaoJ6efNW` (production, READY) was uploaded from that stage with
  the existing Vanishr project link (project and team IDs checked; the live feed served 0.6.1 / code 42, so
  code 43 is no downgrade). The canonical alias `vanishr-download.vercel.app`, which still served 0.6.1, was moved to
  it explicitly.
- **Public verification** (`.tools\public-release-verification-0.6.2.json`). The canonical feed reports 0.6.2 / 43
  with `no-store`; the 31 served files (`vercel.json` is configuration and is not served) are byte-identical to the
  audit; the downloaded APK verifies with the original certificate; the security headers and the APK download
  headers are present; 18 private or missing paths (secret folders and keys, including the settings signing key; the
  signing, installer and publish scripts; the settings source and rule table; `google-services.json`; and a path
  that does not exist) return 404; and the Android page carries the
  "Settings that can change without an update" notes. **The signed settings file is live**:
  `https://vanishr-download.vercel.app/ota/settings.json` answers 200 with `application/json; charset=utf-8`,
  `Cache-Control: no-store` and `nosniff`, is byte-identical to the committed file (SHA-256
  `75923248c667b6ae90b901b7c3aa4a0e8fce275674678b050df2c3f9569cf8cf`, 320 bytes) and its signature verifies under the
  public key embedded in the app (key `ke4057b2b4ec1`, sequence 1, issued 2026-10-17 06:28:06 UTC, expires
  2026-12-01, which was 44.6 days away when checked). `sign-ota-settings.ps1 -Check -Live -RequireIdentical`
  agrees: the website serves exactly the committed file, in force. It holds no notice and leaves all three
  features on, so no phone sees a change. **It has to be renewed before 2026-12-01** (see the deployment guide);
  after that date an installed 0.6.2 simply behaves as built.
- **Over-the-air update, 0.5.6 / 0.5.7 / 0.5.8 / 0.5.9 / 0.6.0 / 0.6.1 to 0.6.2**
  (`.tools\ota-check-0.6.2\ota-verification-0.6.2.json`). On the dedicated Android 16 / API 36 emulator, with a
  temporary screen lock that was cleared afterwards, the published release APKs of 0.5.6 (code 37), 0.5.7 (38),
  0.5.8 (39), 0.5.9 (40), 0.6.0 (41) and 0.6.1 (42) were installed one at a time. Each, with no manual trigger,
  fetched the live feed and showed the **Update available / Vanishr 0.6.2** sheet with **Download** and **Later**.
  Tapping **Download** launched an `ACTION_VIEW` browsable intent to `https://vanishr-download.vercel.app/...` in
  Chrome (Android redacts the path in its log; the app's feed validation accepts only the exact versioned URL on this
  host, and well-formed size and SHA-256 fields, but it does not hash the downloaded file itself; the public
  verification above hashed that file). The published 0.6.2 APK was then installed over each with `adb install -r`:
  it succeeded in place, the version code became 43, the first-install time was kept (so the app data stays), the app
  launched and was still running after 12 seconds and the update was no longer offered. **The check was run in full
  four times, and the 0.5.6 step twice more on its own.** In the **first** run all six steps passed except that the
  crash buffer was **not empty after the 0.5.6 step** (empty after the other five); the script kept only a yes/no
  flag, so what was in the buffer, and in which process, **was not recorded and is unknown**. It was not
  reproduced: the 0.5.6 step run again on its own, and again after Chrome's data had been cleared (a guess at a
  first-launch crash), had an empty crash buffer, and **three further complete runs** (the script now keeps the
  text of any crash) had an empty one for all 18 steps, with the other results the same. The record
  `ota-verification-0.6.2.json` is the second run; the first, third and fourth runs and the two single-step
  repeats are kept beside it. Earlier releases' runs had an empty buffer throughout, so this was not dismissed as
  noise, but it was not explained either. A crash of the new version after the 0.5.6 update is **not excluded** by
  this evidence, only not seen again in the 20 further steps that were run (the 0.5.6 step five times, each other
  step three times).
- **Google Play bundle** (`.tools\play-store-0.6.2\`, not uploaded anywhere and not part of the Vercel stage).
  `package-apk.ps1 -Bundle -PlayStore` produced `vanishr-0.6.2.aab`: **45,826,747 bytes**, SHA-256
  `b685f08b18268fbc2b3eeee39544d80274017e7c68177f40ef9817df9987f707`. The official bundletool 1.18.3 `validate` passed;
  the manifest says package `app.vanishr.android`, version 0.6.2 / code 43, min API 28, target API 36 and differs from
  the 0.6.1 bundle's manifest only in the version, with the same 14 permissions and an identical bundle
  configuration; the JAR signature verifies with the same certificate as the APK; the four 64-bit native libraries
  are 16 KiB aligned (`PAGE_ALIGNMENT_16K`); the notices match the source; the manifest has no external-updater
  permission and the code holds no update-feed text; the signed-settings client is present (the Play build does
  contact the download site, as the privacy page now says); the embedded settings public-key file is, byte for byte,
  the committed one; and the Google/Firebase client values are present and equal to the validated configuration.
  A scan of every decompressed entry (883 entries, about 127 MB) found none of six known private values; the scan
  was itself checked with a planted value and a tampered key file, which it found. `verification.json` there records
  these results, and `playConsoleSubmitted`, `affectedMotorolaOutcomeVerified` and `realGoogleAccountLoginTested`
  remain `false`: a signed bundle is not Play approval. **This bundle does not fix Google sign-in on a Play
  install by itself**: it carries the same Google/Firebase configuration as 0.6.1 (the upload-key client only), and
  the fix is the owner's registration of the Play app-signing certificate and a relay publish (above).
- **What this does not show.** The browser download itself and Android's installation approval screen were not
  completed by hand. The update is not silent: the prompt opens the browser and Android asks the user to approve
  the install, and an app only checks automatically once a day, so an older install can take up to a day to show
  the prompt unless the user opens My profile and chooses Check for updates. No account existed on the emulator, so
  an update of a phone holding real account data, and a long chat on a real relay, were not exercised, and the
  0.6.2 app's own first fetch of the live settings file was not observed (the emulator is a production build and its
  app data cannot be read; only the file, its headers and its signature were checked). Nothing was measured on a
  physical phone, and the bundle was not installed or uploaded.

**Not verified, and not claimed:** that any of this works on a physical phone (everything above ran on one
dedicated emulator, with synthetic accounts, synthetic settings files and signing keys made for the test); a real
Google sign-in on a Google Play install (the owner's steps in GOOGLE-SETUP.md are not done, so it is expected to
keep failing until they are); a relay publish with the new allow-list or the changed installer script on the VM;
the Google Play listing's Data safety form, which has to be revisited because the Play build now contacts the
download site once a day; the behavior of the Google Play bundle on a Play-installed phone; and a settings file
read by a real installed app (the file is on the public site now, and what was checked is the file, its headers and
its signature under the embedded key, not a phone).

**Changed by the owner, not by this work:** after 0.6.1 the owner's commit `c457e6c` removed a paragraph from the
in-app policy summary (`PlayPolicy.SUMMARY`) that said that new accounts are introduced to the admin, that the admin
sees a user's name, username, public device identity and Online, Typing and Last seen status, and that the
admin's app can save a photo it is sent, even a view-once one. Asked before this release was built, the owner chose to
keep it removed, so 0.6.2 and its bundle ship without it. The privacy and security pages and `llms.txt` still say it.
No test covers the text of the summary. [THREAT-MODEL.md](THREAT-MODEL.md) was corrected where it said the in-app
summary carries the sentence.

## Release 0.6.1 / Long chats and the keyboard

Release 0.6.1 / code 42 answers a complaint that a chat got slower the longer two people went
back and forth, and a second report that opening the keyboard pushed only the message box up
and left the chat behind it. It is a **client-only change**: no relay or schema change (the relay
stays at schema 13; `git diff ab93146 HEAD -- relay/src/main infra` is empty) and no Azure
operation. Installs of 0.5.6 to 0.6.0 can update through the in-app prompt; see the publication
notes for what was and was not tested.

**What was wrong, measured on the unmodified 0.6.0 code first** (dedicated Android 16 / API 36
emulator, software-backed Keystore, synthetic accounts, a synthetic relay and a real second Signal
contact; temporary probe tests that became the permanent tests below):

- Opening a chat decrypted every message one after another before showing anything. One decrypt is
  **18.7 ms** (`KeyStore` lookup 0.1, `containsAlias` 2.2, `getKey` 1.8, cipher setup 0.1, `init` 4.0,
  `doFinal` 6.4), because every message has its own Keystore key. A chat of 20 messages took 703 ms to
  appear, **100 messages 2.8 to 4.4 s** (three runs) and **300 messages 7.5 s**, with nothing on screen
  until all of them were ready. This repeated each time the app came back from the background.
- The phone stopped at **100 live messages across all chats** (`vault.names("entry/").size() >= 100`).
  A to-fro chat reaches that within a one-hour expiry. At the limit a send ended in the generic
  "request could not be completed" toast and the "Not sent" bubble (the 101st send in the probe never
  completed), and an incoming message was silently not stored or acknowledged, so the relay kept
  retrying it. Group messages had a second, separate copy of the same limit.
- Every refresh, purge and sync pass parsed every stored message record again (`entries()` took 32 to
  36 ms at 100 messages and 72 ms at 300), a send did one more full listing just to find the message it had
  stored, and the timer that expires messages walked every row four times a second (7 to 10 ms per tick
  at 100 rows, 26 to 31 ms at 300 on the cold emulator). Across 52 back-and-forth cycles the main thread
  had **1,187 tasks of 16 ms or more** (20 to 38 per cycle).
- A received message was shown only after the **whole sync pass** had finished (acknowledgements,
  receipts for all sent messages, groups, profiles, photos): 420 to 823 ms after the wake-up on the
  emulator, typically 650 to 750, and the send path took about 500 ms from tap to a stored message.
- The keyboard: the list's height shrank from 1,748 px to 928 px while its scroll position stayed, so
  **820 px of the newest messages were hidden** behind the composer, and a message that then arrived ended
  1,001 px out of view because the list no longer counted as "at the end".
- Not a cause: typing and the send tap. Twelve keystrokes took 21 to 96 ms and the tap 7 to 65 ms, the same
  at 100 and at 300 messages. What the vault costs per write was measured too: one encrypted commit is about
  27 ms plus 170 ms per MB of vault on this emulator (1 KB 27 ms, 500 KB 104 ms, 1 MB 191 ms, 2 MB 357 ms), and
  a message cycle makes six or seven of them.

**What changed** (see [THREAT-MODEL.md](THREAT-MODEL.md#long-chats-the-message-limit-and-the-keyboard-061)
and [ARCHITECTURE.md](ARCHITECTURE.md#client-threading-and-the-open-vault)):

- [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java): a chat loads **newest
  first in batches of 16**, each batch decrypted on the reader lane one message at a time, its read
  receipts committed once, then shown; the next batch is queued behind whatever else the lane has to do. Only
  the newest 100 messages are prepared at open and older ones load when the user scrolls up to them, with the
  message they were looking at staying where it is. The expiry tick walks the rows only when one is due.
  A send looks up the one message it stored instead of listing the conversation. A scroll to the bottom that has been
  queued but has not run yet counts as being at the bottom for the next batch (`bottomScrollsPending`): the reader can
  post a batch in the few milliseconds between one batch's rows being added and its scroll being queued, and measuring
  the list at that moment finds it laid out but not yet scrolled, which left the chat showing the oldest of the
  messages loaded so far instead of the newest. The first full QA run found this as an intermittent failure of the
  long-chat test while two other emulators were using most of the host's CPU; it was then reproduced deterministically
  (below) and fixed.
- [ChatScroll](../android/app/src/main/java/app/vanishr/android/ChatScroll.java) (new): the message list keeps
  its bottom edge where it was when its own height changes, so the keyboard pushes the newest messages up with
  the composer (and, scrolled up, keeps the same messages just above it).
- [ChatEngine](../android/app/src/main/java/app/vanishr/android/ChatEngine.java): a sync pass shows each received
  message as soon as it is stored; parsed message records are kept while their stored bytes are unchanged (ids,
  deadlines and states only); acknowledgements the relay accepted are removed in one commit instead of one each;
  and the limit is **300 messages with `makeRoom`**: when a phone is full, or its encrypted store passes 16 MB
  (photos), the messages that would expire soonest are removed before the next one is stored, 15 at a time (or
  the earliest photos until 4 MB are free). Only a message already read, or one this phone sent that is no longer
  waiting to be sent, is ever removed; unread messages (including an unopened view-once one) and unsent ones are
  never taken. "Read" means what it always has: opening a chat marks the messages it shows (the newest 100) read as
  they are prepared, so one that was shown but not yet scrolled to can be removed early; the earliest deadlines go
  first, and the newest messages of a chat that was just opened expire last, which is what limits that. All the
  records go in one commit and the keys are deleted only after it is durable (`afterCommit`, so a removal made inside
  a transaction that then rolls back also keeps its keys), and the sender is told nothing. The "seen" marker that stops
  the relay handing a message over twice is removed with the message once its acknowledgement has been **accepted**
  (the relay's `acknowledge.lua` takes the message out of the recipient's inbox on the first accepted acknowledgement,
  and `pending()` lists only that inbox), so markers of removed messages cannot pile up towards the vault's 4,000-record
  limit. It is kept while an acknowledgement is still waiting to be sent and for one the relay **refused**: the relay
  answers "not found" both when it no longer has the message and while the two accounts are blocked from each other
  (`AccountDirectory.requireInteraction`), and then lists the message again once they are not, so `flushAcks` (and the
  group one) now keeps a refused `delivered` or `read` receipt as a `held` receipt that is never sent again and is removed
  at its deadline, instead of dropping it.
  (with a specific message) and an incoming message wait at the relay. Group sending and receiving use the same
  rule; the group copy of the old limit is gone.
- [AndroidVault](../android/app/src/main/java/app/vanishr/android/AndroidVault.java): a key is fetched with one
  `getKey` instead of `containsAlias` plus `getKey`, one fewer round trip to the keystore on every use, and the vault
  can report its size.
- Unchanged: every message keeps its own hardware key, deleted at its deadline; text is decrypted only for the open
  chat and wiped on the same events; no parallel Keystore use was added (parallel decrypts were measured, 40 bodies
  in 927 ms on one thread and 301 ms on four, but hardware-backed stores limit concurrent operations, so it was
  not used); and the vault is still re-encrypted whole on each commit.

**Measured after, same emulator and scenarios.** The first screen of a 100-message chat appears after
**388 ms** in the permanent test (668 to 818 ms in the probe) and the whole page is ready after 2.3 s, in the
background; a 300-message chat prepares only its newest 100. Back and forth, a message is stored about
**200 ms** after the tap (was about 500), a reply is on screen **about 280 ms** after the wake-up
(248 to 323 ms; was 650 to 750) and 314 ms with the rest of the sync pass deliberately held up, and the main
thread had **169 tasks of 16 ms or more** over the same 52 cycles (was 1,187). The 101st send and beyond went
through. With 300 messages on screen, twelve keystrokes took 34 ms and the send tap 22 ms (worst of four); the
expiry tick took 0.3 to 0.8 ms (was 26 to 31). Listing 150 messages took 52 ms the first time and 12 ms the next.
With the keyboard open, **0 px** of the newest messages were hidden (was 820), a message arriving with the keyboard
open stayed in view and the keyboard stayed open with focus in the message box.

Demonstrated on 2026-10-13 (synthetic accounts and an emulator only):

- Nineteen permanent tests were added to `ScreenFlowTest` (220 tests in the suite now):
  `theKeyboardPushesTheNewestMessagesUpWithTheComposer` and
  `aChatScrolledUpKeepsTheSameMessagesAboveTheComposerWhenTheKeyboardOpens` (real keyboard, pixel positions, a new
  message with the keyboard open, closing it again); `aLongChatShowsItsNewestMessagesBeforeTheOlderOnesAreReady`
  (the first rows appear after one batch, the rest in further steps, in the order they were sent);
  `aStageAppliedBeforeTheScrollForThePreviousOneHasRunStillFollowsTheBottom` (two batches applied by hand with a layout
  pass in between and no chance for the queued scroll to run: the newest message must still be in view; without the
  fix 2,248 px of the newest messages were below the screen);
  `olderMessagesBeyondTheFirstPageLoadWhenTheUserScrollsUpToThem` (only the newest 100 are prepared, scrolling up
  loads the rest and the message being looked at does not move);
  `aReceivedMessageIsShownBeforeTheRestOfTheSyncPassFinishes` (the receipts call is blocked and the message is already
  on screen); `typingAndSendingStayQuickInAChatOfThreeHundredMessages`; `theExpiryTickOnlyWalksTheMessagesWhenOneIsDue`
  (a counter shows 40 idle ticks walk nothing and an expiry is still removed on time);
  `acknowledgementsAreRemovedInOneCommitAndWhatTheRelayAcceptedSurvivesARejection`;
  `unchangedMessageRecordsAreNotParsedAgainWhenAChatIsListed` (a changed record is parsed once, a removed one leaves
  the cache); and six `makeRoom` tests: the earliest-deadline read or sent messages go first while unread and unsent ones
  never do, an incoming message at the limit is stored and acknowledged, a phone holding only unread messages still
  refuses with a specific message and leaves nothing queued, an incoming message waits at the relay until one has been
  read, a 21 MB store of photos is brought under 13 MB before it can hit its hard limit (earliest photos first, short
  messages untouched), and the open chat drops the removed messages from the screen with no failed send while their
  keys are really gone from the Keystore. Three more came from the independent review (below):
  `aRemovedMessageKeepsItsSeenMarkerOnlyWhileTheRelayCouldStillHandItOverAgain` (real Signal messages through the
  real receive, acknowledge and removal path: the relay accepts the receipts for three and answers "not found" to three,
  the refused ones are kept as held receipts and not sent again, a full phone removes four of the six, the markers of
  the three accepted ones go, the one refused one keeps its marker, the removed messages' keys are gone from the
  Keystore and the others' are not, a message whose read receipt is still waiting to be sent keeps its marker too, and
  when the relay lists the removed message again
  it is recognised and not decrypted a second time);
  `aRemovalThatDoesNotCommitNeverCostsAMessageItsKey` (the removal runs inside an outer transaction that fails: every
  record is back, every key is still in the Keystore and a screen can still open the message; run for real, records
  and keys both go); and `aGroupReceiptTheRelayRefusedIsHeldAndItsMessageIsNotReceivedAgainAfterBeingRemovedForRoom`
  (the same for a group message, with a real sender-key group fixture). The relay stub of the earlier receipt tests now
  also counts every receipt call, so a receipt sent a second time is visible.
- Mutation checks, each failing the matching test and then reverted: the plain scroll view (820 px hidden again, the
  anchored position 58 px becoming -762 px); a first batch of 100,000 (the first rows appeared all at once); a history
  page of 100,000 (130 rows at open); no hook after each received message (the message never appeared while the pass
  was held); a limit of 100 again; per-acknowledgement commits (12 revisions instead of 8); a tick that walks every
  time (7 walks became 47); the queued-scroll rule removed (2,248 px of the newest messages below the screen); and,
  for `makeRoom`, unread messages made removable, the photo branch switched off, key
  deletion removed and the receive path not making room. For the review fixes: refused receipts dropped again (the
  held receipts were gone), refused group receipts dropped again, held receipts sent again (12 receipt calls became 15
  in the 1-1 test, an unexpected request in the group test), a `seen` cleanup that ignored a waiting or held
  receipt (the marker of a message the relay would hand over again was lost, 1-1 and group), no `seen` cleanup at all
  (the marker stayed), and keys deleted before the commit again (the rolled-back message had lost its key); each failed
  the matching test for the expected reason. (An earlier version of these fixes used a ten-minute read grace and
  a "being removed" flag; the review showed that the grace was lost on every pause and could refuse a send right after a
  chat had been opened, and both were replaced by the earliest-deadline order and the commit-then-keys order above.)
  A timing-only check of the expiry tick passed under that mutation on the fast emulator, so it was replaced by the counter
  above.
- Full QA run on the final source on the dedicated Android 16 / API 36 emulator: **220 tests (174 screen-flow, 41
  account-safety, 5 backup); 218 passed and 2 failed.** Both failures are the ones recorded for 0.5.8, 0.5.9 and 0.6.0
  and are unrelated: `compactClassicProfilesOpenAsSecureBottomSheetsAndKeepTheChatDraft` (a pixel check of a sheet
  button that fails inside a full run) and `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount`. No source file
  changed between that build and the run. An earlier full run of the same code before the scroll fix and before the
  notification test was changed (219 tests) had two more failures, both intermittent while two other emulators on the
  host, not part of this project, were using most of its CPU: `aLongChatShowsItsNewestMessagesBeforeTheOlderOnesAreReady`,
  which was the scroll race described above (it failed in 2 of the first 5 runs, the full run and one of four paired
  reruns, and in none of 16 later runs; the race was then reproduced and fixed), and
  `notificationShadeTapLaunchesTheExactChatThroughItsImmutablePendingIntent`, a test that existed before this release and
  asserted that the "View once" row was on screen the moment the chat header appeared, although the rows are prepared on
  the reader lane and follow a moment later (3 to 362 ms later in the 8 runs that measured it; before the change it
  failed in 4 of 15 runs). It now waits for the row, for up to 15 seconds, before making the same assertions, and passed
  in the final full run. An earlier full run on the build before `makeRoom` existed (211 tests) had only the two known
  failures.

**Not verified:** any physical phone, and in particular a phone whose Keystore is hardware backed or uses StrongBox,
where each decrypt and key creation is slower than the 19 ms measured here (the first screen of a long chat is the part
that matters, and it no longer waits for the rest); the vault's cost per commit, which still grows with its size and with
photos and is the remaining reason a very photo-heavy chat is slower (it is only kept from failing at its size limit, not
made cheaper); a phone with several saved accounts at the limit; a group chat at the limit end to end (the group
receipt and marker behavior has its own test, but a group message removed by a real full phone was only exercised
with a lowered limit); a relay that really refuses a receipt because of a block (the relay's `not_found` for a block
was read from its code and stubbed, not run against a relay); and the early removal itself on a real busy phone. A very
busy phone now removes its oldest read messages a little before their timer instead of stopping; that is a behavior
change to be aware of and is stated on the public Android and security pages.

**Independent review.** A separate read-only code-review agent (an AI reviewer, not a human audit) read the change in
three rounds. It found no defect in the staged load, the expiry tick, the scroll anchoring, the parsed-record cache, the
batched receipts, the sync hook or the make-room selection, order and cost, but three Medium problems in what the first
version of make-room left behind or did around a removal (the "seen" markers of removed messages could pile up towards
the vault's record limit; opening a chat marks its whole window read, so never-scrolled messages counted as seen; keys
were deleted before the commit, so a screen decrypting a victim could see a failure). My first fixes for those had three
further Medium problems
that the second round found (a receipt the relay refused could let a removed message come back after a block and
break every sync until it expired; the ten-minute read grace was lost on every pause and could refuse a send right
after a chat was opened; a failed commit after the keys were deleted left listed messages without keys). Those were
fixed by changing the design, not by patching it (earliest-deadline order instead of the grace, records first and keys
after the commit, refused receipts held) and the third round, on the new state, found no defect in it. It left one
low-severity note that was not acted on: an `Error` (not an `Exception`) thrown by a Keystore call while the keys are
deleted after a commit would skip the remaining keys, which then expire with their messages. The scroll race above
was not found by the review but by analysing an intermittent test failure.

**Publication, 2026-10-13 (UTC):**

- **Relay.** Unchanged: `git diff ab93146 HEAD -- relay/src/main infra` is empty, so the deployed schema-13 relay
  was not redeployed and no Azure operation ran.
- **Build and stage.** `verify.ps1 -Android` passed (client-core 19 tests, relay 101 tests with the two opt-in live
  tests skipped, lint with 0 errors and 18 warnings, none of them in the new code, instrumentation compilation) with Docker Desktop started for
  the relay integration tests and stopped afterwards. `:app:testDebugUnitTest` (13 tests, no failure),
  `:app:exportDependencyInventory` and notice generation (116 components) also passed. `package-apk.ps1` built the
  signed APK from commit `711dba1` (release lint included): **44,501,660 bytes**, SHA-256
  `d04225dd4f873112b21985497fe90119dca77529cd9c4b20132568bb7d986040`, version 0.6.1 / code 42, min API 28, target API
  36, exactly the same 15 permissions as 0.6.0 (compared one by one) and the same certificate (SHA-256
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`). `.tools\vercel-download-0.6.1` holds 31 files /
  59,837,224 bytes (limit 99,000,000), no hidden file and no `.env` (the Vercel project link was added after the
  audit, as for earlier releases). The source ZIP has 454 entries: 236 source files, each byte-identical to its blob
  in commit `711dba1` (one more than 0.6.0: `ChatScroll.java`), plus 218 dependency-source files. Name, content and
  known-private-value scans of the stage, the ZIP and the APK found nothing. This is recorded in
  `.tools\distribution-audit-0.6.1.json`; it was an inspection by the release agent with automated scans, not an
  independent audit.
- **Vercel.** Deployment `dpl_27sV3eXNZbJBH2BZqGE8LZdMKHPT` (production, READY) was uploaded from that stage with the
  existing Vanishr project link (project and team IDs checked, no downgrade against the published 0.6.0 feed). The
  canonical alias `vanishr-download.vercel.app`, which still served 0.6.0, was moved to it explicitly.
- **Public verification** (`.tools\public-release-verification-0.6.1.json`). The canonical feed reports 0.6.1 / 42
  with `no-store`; the 30 served files (`vercel.json` is configuration and is not served) are byte-identical to
  the audit; the downloaded APK verifies with the original certificate; the security headers and the APK download
  headers are present; ten private or missing paths return 404; and the Android page carries the 0.6.1 notes
  (smoother long chats, the 300-message limit and the early removal of already read messages) and the security page the
  matching sentence.
- **Over-the-air update, 0.5.6 / 0.5.7 / 0.5.8 / 0.5.9 / 0.6.0 to 0.6.1**
  (`.tools\ota-check-0.6.1\ota-verification-0.6.1.json`). On the dedicated Android 16 / API 36 emulator, with a
  temporary screen lock that was cleared afterwards, the published release APKs of 0.5.6 (code 37), 0.5.7 (38),
  0.5.8 (39), 0.5.9 (40) and 0.6.0 (41) were installed one at a time. Each, with no manual trigger, fetched the live
  feed and showed the **Update available / Vanishr 0.6.1** sheet with **Download** and **Later**. Tapping **Download**
  launched an `ACTION_VIEW` browsable intent to `https://vanishr-download.vercel.app/...` in Chrome (Android redacts
  the path in its log; the app's feed validation accepts only the exact versioned URL on this host, and well-formed
  size and SHA-256 fields, but it does not hash the downloaded file itself; the public verification above hashed that
  file). The published 0.6.1 APK was then installed over each with `adb install -r`: it succeeded in place, the
  version code became 42, the first-install time was kept (so the app data stays), the app launched and was still
  running after 12 seconds, the crash buffer was empty and the update was no longer offered.
- **Google Play bundle** (`.tools\play-store-0.6.1\`, not uploaded anywhere and not part of the Vercel stage).
  `package-apk.ps1 -Bundle -PlayStore` produced `vanishr-0.6.1.aab`: **45,804,473 bytes**, SHA-256
  `2eacf626b1713f9004af951a6fd932d03f429d306b78a2fba3915da36adfdf0f`. The official bundletool 1.18.3 `validate` passed;
  the manifest says package `app.vanishr.android`, version 0.6.1 / code 42, min API 28, target API 36; the JAR
  signature verifies with the same certificate as the APK; the four 64-bit native libraries are 16 KiB aligned
  (`PAGE_ALIGNMENT_16K`, as in the 0.5.6 bundle); the notices match the source; the manifest has no external-updater
  permission and no update-feed text; and the Google/Firebase client values are present and equal to the validated
  configuration. `verification.json` there records these results with the same fields as the 0.5.6 bundle, and
  `playConsoleSubmitted`, `affectedMotorolaOutcomeVerified` and `realGoogleAccountLoginTested` remain `false`: a
  signed bundle is not Play approval.
- **What this does not show.** The browser download itself and Android's installation approval screen were not
  completed by hand. The update is not silent: the prompt opens the browser and Android asks the user to approve
  the install, and an app only checks automatically once a day, so an older install can take up to a day to show
  the prompt unless the user opens My profile and chooses Check for updates. No account existed on the emulator, so
  an update of a phone holding real account data, and a long chat on a real relay, were not exercised. Nothing was
  measured on a physical phone, and the bundle was not installed or uploaded.

## Release 0.6.0 / Full-screen photos and the admin's save option

Release 0.6.0 / code 41 changes how a photo from a chat is shown and adds one option for the
official admin. It is a **client-only change**: no relay or schema change (the relay stays at
schema 13, unchanged since 0.5.7; `git diff ab93146 HEAD -- relay/src/main infra` is empty) and no
Azure operation. Installs of 0.5.6 to 0.5.9 can update through the in-app prompt; see the
publication notes for what was and was not tested.

**The request and how it was read.** A photo should open at the full screen width with no white
border or padding; the Done button goes and only a cross at the top right stays; pressing and
holding anywhere on the photo opens a popup to save it to the gallery, for every photo including
one sent as view-once; and the save is "admin only". The viewer change applies to every account.
"Admin only" was read as: **only the official admin account's app can save**. That reading was not
confirmed with the owner before release; it is the one built, tested and disclosed.

**What changed** (see [THREAT-MODEL.md](THREAT-MODEL.md#photo-viewer-and-the-admins-save-option-060)
and [ARCHITECTURE.md](ARCHITECTURE.md#content-flow)):

- [PhotoViewer](../android/app/src/main/java/app/vanishr/android/PhotoViewer.java) (new): a full-screen
  dialog on a black, non-floating window theme (`Theme.Vanishr.PhotoViewer`), an image with no padding scaled
  to the full width, and one 48 dp close cross at the top end, kept clear of system bars and cutouts on both
  sides. It keeps `FLAG_SECURE` and hides overlay windows like the sheets did, and releases its bitmap and any
  kept bytes when it stops.
- [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java): photos open in the viewer
  instead of a bottom sheet; it closes at the message deadline, when the app is backgrounded and on sign-out.
  Text messages still use the sheet with Done, the "Send photo" preview before sending is unchanged, and
  Remote photos is unchanged. A long press (or the accessibility action "Save to gallery") asks for
  confirmation; the save runs on its own thread and reports "Saved to your gallery." or "The photo could not
  be saved."
- [AdminOnboarding](../android/app/src/main/java/app/vanishr/android/AdminOnboarding.java) `verifiedAdmin()`:
  the save option is unlocked only when the signed-in user id, this device's id and this phone's own identity
  key all match the pin in the signed app, and any failure counts as "not the admin". It is decided on the
  worker before the message is consumed. The older `admin()` compares only the user id, which the relay
  supplies at sign-in, so it is not used for this (an independent review found that, see below).
- [SafeImages](../android/app/src/main/java/app/vanishr/android/SafeImages.java) `saveToGallery`: writes the
  unchanged bytes to `Pictures/Vanishr` through MediaStore (`IS_PENDING` while writing, the row deleted if
  the write fails), after checking that they are a JPEG, PNG or WebP image. No storage permission and no
  manifest permission were added. It needs Android 10 or later; on Android 9 (the minimum) the option says so.
- Every other account's app still wipes a photo's decrypted bytes as soon as the bitmap is decoded and has no
  save gesture. The in-app policy summary gained the sentence that the admin's app can save a photo sent to
  it, even a view-once one; the policy version is unchanged by the owner's earlier decision, so no
  re-acceptance screen appears. The privacy, security and Android pages and `llms.txt` say the same.

Demonstrated on 2026-10-12 and 2026-10-13 (synthetic accounts and an emulator only):

- Seven permanent tests were added to `ScreenFlowTest`:
  - `photosFillTheScreenWithOnlyACloseButtonAndOrdinaryAccountsCannotSaveThem`: the viewer window, the image and
    the picture span the whole screen width, no padding, no border, no Done button, the cross is the only
    control (top right, at least 44 dp), `FLAG_SECURE` is set; an ordinary account has no long-click handler,
    no popup, no kept bytes, and a view-once photo is consumed when opened; nothing reaches the gallery.
  - `onlyTheAdminCanSaveAPhotoByLongPressingItEvenWhenItWasViewOnce`: the popup (Save photo, Cancel, Save); Cancel
    saves nothing; Save writes exactly the original bytes (`image/jpeg`, not pending, under `Pictures/Vanishr`);
    the same for a view-once photo after it was consumed and for a photo the admin sent; the kept bytes are zero
    after the cross and after backgrounding; exactly two copies exist at the end.
  - `aRealPressAndHoldAnywhereOnTheAdminsPhotoOpensTheSavePopupAndTheCrossClosesIt`: injected touches (about a
    second without moving) at three places open the popup while the photo stays open; a real tap on the cross
    closes the photo; the same hold on an ordinary account's photo does nothing.
  - `anAccountThatOnlyCarriesTheAdminsUserIdCannotSavePhotos`: with the pinned user id but a different identity
    key, or a different device id, `admin()` is true, `verifiedAdmin()` is false and the viewer has no save
    option; the same account with the pinned identity can.
  - `theCloseCrossFollowsTheReadingDirectionAndTheSideInsets`: the cross sits at the right edge in left-to-right
    and at the left edge in right-to-left, each clear of its own side inset and of the status bar.
  - `aGalleryCopyKeepsTheFormatAndAnUnsupportedFormatIsRefused`: JPEG and PNG copies keep their bytes, MIME type
    and extension; an unsupported or empty payload is refused and leaves nothing behind.
  - `anOpenPhotoClosesWhenItsMessageExpires`.
  The opt-in live-relay `ReleaseWorkflowTest` was updated for the new control (it taps the cross, "Close
  photo", instead of Done) but not run, because it needs a live relay and real accounts.
- The viewer was captured from the final build and checked by eye: the picture spans the full width on black, one
  white cross at the top right, no border, no button with text.
- Mutation checks, each failing the matching new test and then reverted: every account treated as the admin
  ("Ordinary accounts have no save gesture"); 24 dp padding on the picture (`expected:<0> but was:<63>`); the kept
  bytes not wiped on close ("The kept bytes are wiped when the photo closes", `expected:<0> but was:<-1>`);
  `verifiedAdmin()` reduced to the user-id check ("The device or identity key does not"); the left inset dropped
  (`expected:<179.0> but was:<21.0>`); and a touch listener that swallows real touches, which fails only the
  real-touch test while the `performLongClick()` tests still pass.
- An independent read-only code review of the change found two issues, both fixed with a test: (1) the first
  version gated saving on `admin()`, which a compromised relay could make true for an ordinary account by
  returning the admin's user id on a fresh sign-in, contradicting the threat-model claim; it now uses
  `verifiedAdmin()`; (2) the cross's margin was applied on the right only, so in a right-to-left language it sat
  flush against the left edge and ignored a left cutout or bar; margins now follow both sides. It found no defects in
  wiping, account switching, backgrounding, expiry, the MediaStore write or the unchanged text path.
- One 0.5.9 test, `aMessageThatVanishesWhileTheChatOpensIsSkippedButARealFailureIsStillReported`, failed in the
  first full run of this release: the first scheduled sync, about two seconds after launch, could refresh and
  display the fixture messages before the test deleted a key. It now publishes the record only after the key is
  gone (helper `entryWithoutItsKey`) and passed in three isolated runs and every later full run. Only test code
  changed.
- Full QA run on the final build and final tests on the dedicated Android 16 / API 36 emulator: **201 tests (155
  screen-flow, 41 account-safety, 5 backup); 199 passed and 2 failed.** Both failures are the ones recorded for
  0.5.8 and 0.5.9 and are unrelated to photos: `compactClassicProfilesOpenAsSecureBottomSheetsAndKeepTheChatDraft`
  (a pixel check of a sheet button that fails inside a full run) and
  `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount`. An earlier full run of the same code, before the
  real-touch test existed, had the same result (200 tests, the same two failures).

**Publication, 2026-10-13 (UTC):**

- **Relay.** Unchanged: `git diff ab93146 HEAD -- relay/src/main infra` is empty, so the deployed schema-13 relay
  was not redeployed and no Azure operation ran.
- **Build and stage.** `verify.ps1 -Android` passed (client-core 19 tests, relay 101 tests with the two opt-in live
  tests skipped, lint with no finding in the new code, 13 JVM unit tests, instrumentation compilation). Its first
  attempt stopped at the relay integration tests because Docker Desktop was not running (Testcontainers found no
  engine); it passed after Docker Desktop was started. `:app:testDebugUnitTest`, `:app:exportDependencyInventory`
  and notice generation (116 components; the committed notices did not change) also passed. `package-apk.ps1`
  built the signed APK from commit `14e5ad3`: **44,485,276 bytes**, SHA-256
  `f9837b10a69e58143fa7ff61d37183a48a6a54a1ee9219af351af0b540b80004`, min API 28, target API 36, exactly the same 15
  permissions as 0.5.9 (compared one by one) and the same certificate (SHA-256
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`). `.tools\vercel-download-0.6.0` holds 31
  files / 59,791,638 bytes (limit 99,000,000), no hidden file and no `.env`. The source ZIP has 453 entries: 235
  source files, each byte-identical to its blob in commit `14e5ad3` (one more than 0.5.9: `PhotoViewer.java`), plus
  218 dependency-source files. Name, content and known-private-value scans of the stage, the ZIP and the APK found
  nothing, and the dependency inventory (116) and the 16 unavailable-source entries equal 0.5.9's. This is
  recorded in `.tools\distribution-audit-0.6.0.json`; it was an inspection by the release agent with automated
  scans, not an independent audit.
- **Vercel.** Deployment `dpl_EvK55GC53osuWVyKPgUhdccwraPK` (production, READY) was uploaded from that stage with the
  existing Vanishr project link (project and team IDs checked, no downgrade against the published 0.5.9 feed). The
  CLI moved only the team alias, which is behind Vercel Authentication, so the canonical alias
  `vanishr-download.vercel.app`, which still served 0.5.9, was moved explicitly.
- **Public verification** (`.tools\public-release-verification-0.6.0.json`). The canonical feed reports 0.6.0 / 41
  with `no-store`; the 30 served files (`vercel.json` is configuration and is not served) are byte-identical to
  the audit; the downloaded APK verifies with the original certificate; CSP, nosniff, referrer, HSTS and the APK
  download headers are present; ten private or missing paths return 404; and the Android, security and privacy
  pages carry the 0.6.0 notes, including that the admin's app can save a received photo.
- **Over-the-air update, 0.5.6 / 0.5.7 / 0.5.8 / 0.5.9 to 0.6.0**
  (`.tools\ota-check-0.6.0\ota-verification-0.6.0.json`). On the dedicated Android 16 / API 36 emulator, with a
  temporary screen lock that was cleared afterwards, the published release APKs of 0.5.6 (code 37), 0.5.7 (38),
  0.5.8 (39) and 0.5.9 (40) were installed one at a time. Each, with no manual trigger, fetched the live feed and
  showed the **Update available / Vanishr 0.6.0** sheet with **Download** and **Later**. Tapping **Download** launched
  an `ACTION_VIEW` browsable intent to `https://vanishr-download.vercel.app/...` in Chrome (Android redacts the path
  in its log; the app's feed validation accepts only the exact versioned URL on this host, and well-formed size
  and SHA-256 fields, but it does not hash the downloaded file itself; the public verification above hashed that
  file). The published 0.6.0 APK was then installed over each with `adb install -r`: it succeeded in place, the
  version code became 41, the first-install time was kept (so the app data stays), the app launched and was still
  running after 12 seconds, the crash buffer was empty and the update was no longer offered.
- **What this does not show.** The browser download itself and Android's installation approval screen were not
  completed by hand. The update is not silent: the prompt opens the browser and Android asks the user to approve
  the install, and an app only checks automatically once a day, so an older install can take up to a day to show
  the prompt unless the user opens My profile and chooses Check for updates. No account existed on the emulator, so
  an update of a phone holding real account data, and the new viewer with a real account on a real relay, were not
  exercised. Nothing was measured on a physical phone.

**Not verified:** any physical phone, including how the gallery app and any gallery backup show or copy the
saved file (the tests read it back through MediaStore); an Android 9 device, where the option only explains that
it needs Android 10 (the emulator is API 36); the "Save to gallery" accessibility action with a screen reader (it
is registered but only the long press was exercised); landscape, cutouts and foldables other than through
synthetic insets; the live-relay `ReleaseWorkflowTest`; and that the sender is told nothing when the admin saves
their photo (by design: there is no notification, which is why the public text asks people to send the admin only
what they are happy for it to keep). The saved file is an ordinary, unencrypted gallery file that does not
expire.

## Release 0.5.9 / Startup and tap responsiveness

Release 0.5.9 / code 40 fixes a delay reported after opening the app: tapping a chat right
after start-up could make the app wait several seconds before it responded again. It is a
**client-only change**: no relay or schema change (the relay stays at schema 13, unchanged
since 0.5.7) and no Azure operation. Installs of 0.5.6, 0.5.7 and 0.5.8 can update through the
in-app prompt; see the publication notes below for what was and was not tested.

**Causes, measured on the unmodified 0.5.8 code first** (dedicated Android 16 / API 36 emulator,
synthetic accounts, temporary probe tests that became the permanent tests below):

- Every chat-list row called `engine.entries(peer)`, which listed every stored message, read each
  one and checked the block list per message, so drawing the list on the main thread was
  O(contacts × messages). The admin's list with 64 automatic contacts and 60 messages took
  **26,275 ms**; with 43 contacts and 120+ messages the list had not appeared after 15 s (two probe
  timeouts).
- Every vault read made two Android binder calls (`isDeviceSecure`, `isDeviceLocked`): 1,000 reads
  took **2,405 ms** of which the lock checks were 2,296 ms.
- Reads waited for the vault monitor, which an encrypted commit holds while it re-encrypts and
  writes the whole vault, so a tap or a refresh could wait behind another thread's commit.
- One single-thread worker did everything: the 15-second sync (many sequential relay calls), the
  3-second presence heartbeat, push registration, every avatar load, every conversation refresh and
  every user action. Tapping the automatic admin chat, the only chat a new account has and every
  chat for the admin, queued behind that backlog and set the screen busy, which also dropped
  further taps: **4,685 ms** with the worker busy for 3 s. Verified contacts opened inline, so
  only automatic chats showed it.
- Opening a chat with unread messages wrote one encrypted vault commit per message.

**What changed** (see [ARCHITECTURE.md](ARCHITECTURE.md#client-threading-and-the-open-vault) and
[THREAT-MODEL.md](THREAT-MODEL.md#responsiveness-and-the-open-vault)):

- [AndroidVault](../android/app/src/main/java/app/vanishr/android/AndroidVault.java): reads come from an
  immutable snapshot of the last committed state and no longer wait for a commit; a transaction sees
  its own writes and others see them after persistence; wiping of replaced values is O(n) instead of
  O(n²) and happens after in-flight readers finish; `close()` clears the snapshot under the same lock.
  A successful lock check is reused for at most 250 ms for reads of records already in memory; opening
  the vault and every Keystore operation (sealing, decrypting message content, writing) still check each
  time. The account-scoped wrapper checks ownership and reads from one snapshot, so a sign-out cannot land
  between its check and its read.
- [ChatEngine](../android/app/src/main/java/app/vanishr/android/ChatEngine.java): `unreadCounts()` counts
  every conversation in one pass; `entries()` caches the block-list lookup per call and skips records that
  vanish mid-scan; `peer(id)` looks up one contact; `markRead(batch)` commits a screen's read receipts
  once and skips messages that were cleared meanwhile; `sync(yield)` can stop between steps.
- [MainActivity](../android/app/src/main/java/app/vanishr/android/MainActivity.java): a second, read-only
  worker (background priority) prepares screens (message text, avatars, the presence header); a saved contact, including the
  automatic admin chat, opens after local identity, pin and block checks on the main thread and no longer
  waits for the sync worker or sets the screen busy; user actions are counted so a running sync yields at
  its next step boundary and resumes behind them; the list calls `unreadCounts()` once.
- [AdminOnboarding](../android/app/src/main/java/app/vanishr/android/AdminOnboarding.java) split `prepare` into
  the local half (`prepareSaved`) and the network introduction, which is unchanged for a contact this phone
  never saved.
- An independent code review of the diff found three races that the lane split made possible, all fixed with
  tests:
  - the read-only worker stores a read receipt under the same name as the "delivered" acknowledgement, and the
    sync worker could delete it while "delivered" was in flight, so the sender would never see "read"; the sync
    worker now deletes a sent acknowledgement only if it is unchanged (one-to-one and group paths);
  - the profile-photo generation counter, which tells the screen to reload avatars, was bumped inside the
    transaction that stores the photo, before other threads could read it, so a reload in that window could
    keep an old or revoked photo on screen; the vault now has an `afterCommit` hook and the counter is bumped
    only once the commit is published;
  - an erase deletes a message's key a moment before its record, so a chat refresh in that window reported a
    storage failure for a message that was being removed; the refresh now waits up to 600 ms for the record to
    go before treating a failure on it as real, and a failure on a message that stays is still reported.
  The entry-scanning loops of clear chat, block and group removal now skip a record that expired and vanished
  mid-scan instead of failing.

**Measured on the same emulator, 0.5.8 before / 0.5.9 after.** The 0.5.9 columns are the median and range of
the same permanent tests over seven runs of the final code and its intermediate builds (emulator timing varies
from run to run, and the last two runs were on a recreated emulator, see below):

| Scenario | 0.5.8 | 0.5.9 median | 0.5.9 range |
|---|---|---|---|
| Admin list, 64 automatic contacts, 60 messages, drawn on the main thread | 26,275 ms | 498 ms | 435 to 938 ms |
| Tap an automatic contact while the sync worker is blocked, until the chat opens | 4,685 ms (3 s block) | 108 ms (blocked until released) | 72 to 168 ms |
| Launch to a visible list, 43 contacts, 120+ messages | not shown within 15 to 20 s | 1,676 ms | 1,558 to 2,121 ms |
| 1,000 vault reads | 2,405 ms | 51 ms | 43 to 68 ms |
| 100 `peers()` calls, 43 contacts | 19,900 ms | 877 ms | 684 to 1,082 ms |
| 20 `entries(peer)` calls, 130 messages | 6,800 ms | 354 ms | 240 to 1,423 ms |
| 20 `unreadCounts()` calls, 130 messages (replaces one `entries` call per row) | not applicable | 428 ms | 334 to 1,463 ms |
| Tap on a verified contact while another thread holds a storage commit for 2 s | 2,194 to 2,225 ms (three runs, only 8 chats: it waited for the whole hold) | 55 ms (43 chats) | 34 to 125 ms |

The 0.5.8 values come from temporary probes of the same scenarios, run before the fix. The last row could
not be measured at 43 chats on 0.5.8 because the list itself did not appear, so 0.5.8 was measured with
8 chats. The remaining 0.5 s of the admin list is building 64 rows of views on an emulator with a software
renderer; it was not reduced further. The two slowest values in the `entries` and `unreadCounts` rows
(1,423 and 1,463 ms) come from the final full run on a recreated emulator that had been up for about 40 minutes
and was short of memory (3.6 of 4 GB used, swap in use); the same test run alone three times right afterwards took
392 to 415 ms and 426 to 441 ms, in line with the earlier runs, so this is recorded as a transient emulator
slowdown rather than a regression.

Demonstrated on 2026-10-10 and 2026-10-11 (synthetic accounts and an emulator only):

- Sixteen permanent tests were added to `ScreenFlowTest`, each with a threshold or assertion that the old
  code fails: reads stay cheap with many contacts; vault reads never wait for an in-flight commit, see only
  committed state, publish nothing from a failed transaction and fail closed once closed; `afterCommit`
  actions run only after the change is readable and never after a rollback; unread counts equal
  the slow per-conversation count including blocked and expired messages; read receipts for a screen are
  exactly one commit and skip vanished messages; saved contacts prepare locally while unknown, changed and
  blocked ones do not; a sync yields between steps and resumes behind a waiting action; reads fail closed
  within the cache window after the phone locks (needs the generated device credential); a tap does not wait
  for a storage commit; an automatic contact opens in about 0.1 s while the sync worker is blocked and its
  messages load meanwhile; the list and a tap stay prompt at start-up with many contacts; a read receipt
  stored while "delivered" is on the wire is not deleted (one-to-one and group); the photo generation never
  moves before the photo can be read; and a message that vanishes while its chat opens is skipped while a
  real failure is still reported.
- Mutation checks: with the fixes reverted one at a time (no sync yield, automatic contacts through the
  worker, one commit per receipt, lock check on every read, reads that take the monitor, the
  unconditional acknowledgement delete, the photo generation bumped inside the transaction, and no settle wait
  for a vanishing message) each matching new test failed, for example a 2,350 ms tap, 24
  commits instead of 20, 2,461 ms for 1,000 reads, a lost read receipt and "The generation moved before the
  photo could be read".
- Three existing tests were adapted without changing what they assert. Two block the sync worker to prove that
  reads queued behind it are dropped after backgrounding (`backgroundingDropsQueuedConversationReadsAndClearsRetainedRows`,
  `queuedAvatarCannotReappearAfterBackgrounding`); those reads now run on the read-only worker, so they block
  that worker instead. `typingDoesNotWaitForPresenceAudienceStorageReads` stalls the vault's wipe lock because
  reads no longer wait on the monitor.
- One existing bound was loosened: `chatTypingAndSendDoNotWaitForBusyEncryptedStorage` required typing and a
  send tap to take under 100 ms while the vault is locked for 2 s, a bound that is already marginal. Six
  isolated runs on the unmodified 0.5.8 build took 100 (failed), 87, 96, 90, 89 and 69 ms (median 90). The
  first 0.5.9 build took 119, 158, 161, 147, 124 and 82 ms (median 135), because the read-only worker's tasks
  now run during the measurement instead of queueing behind the blocked worker. A breakdown showed that
  `refreshMessages` and the status refresh cost at most 1 ms on the main thread and that the time is twelve
  cold keystrokes (55 to 75 ms) and the send tap (about 16 ms). With the read-only worker at background
  priority the runs were 95, 100 (failed), 107 (failed), 113 (failed), 82 and 90 ms (median 97). The bound is now
  250 ms: a wait on the 2 s lock is still caught, and the measured value is still recorded.
- Full QA run on the final build and final tests, on the recreated emulator (see the next item): **194 tests
  (148 screen-flow, 41 account-safety, 5 backup); 192 passed and 2 failed.** Both failures also occur on the
  unmodified 0.5.8 build: a full `ScreenFlowTest` run of 0.5.8 (132 tests) failed these two and also the
  scroll-position race `receiptBurstsReuseEightyMessageRowsAndExpiryClearsCachedText` (flaky; it failed in one
  earlier 0.5.9 run and passed in the others, including the final one). They are
  `compactClassicProfilesOpenAsSecureBottomSheetsAndKeepTheChatDraft`, a pixel check of a sheet button that
  passes alone and fails inside a full run, and `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount`,
  which fails alone as well (as on 0.5.7). Earlier full runs on intermediate builds also failed the 100 ms
  typing bound and the presence typing test described above, both since adapted; the receipt race found by
  the review was fixed after the first two full runs, and every later full run includes its tests.
- A first run of the suite on the final code was killed when the Windows host rebooted (about 15:10 UTC on
  2026-10-10), which also stopped the emulator and the test script before it could clear the random screen-lock
  PIN it had set; nothing but that dead script knew the PIN. Only the dedicated `Vanishr_Security_Test`
  emulator was touched: it was restarted with `-wipe-data` (synthetic data only), after which the credential check
  passed and the suite was rerun from the start. The first rerun on the recreated emulator had one extra
  failure, in my new `aLockedPhoneStopsVaultReadsAfterTheShortUnlockCache`: its unlock step woke the phone about
  a second after sleeping it, the PIN pad did not open, and because the unlock sat in a `finally` it could also
  have replaced a real assertion failure. The test passed three times out of three when run alone. It now waits
  for the keyguard to settle, retries the unlock up to three times and no longer lets an unlock failure replace
  an assertion failure; only test code changed. A temporary mutation that made the unlock cache never expire
  was then run against it and failed with `expected ... PhoneLockedException to be thrown, but nothing was
  thrown`, so the test still catches that regression. The final full run above includes this version.
- `DeviceSecurityTest` (6 tests) passed on the final build without a screen lock (5 passed, 1 skipped because it
  needs a lock) and with one (5 passed, 1 skipped because it needs no lock), so each of the six ran once and
  opening the vault is still strict.

**Publication, 2026-10-11 (UTC):**

- **Relay.** Unchanged: `git diff ab93146 HEAD -- relay/src/main infra` is empty, so the deployed schema-13
  relay was not redeployed and no Azure operation ran. The packaging script's read-only `/health` check of the
  relay returned `up`.
- **Build and stage.** After `verify.ps1 -Android` (client-core 19 tests, relay 101 tests with the two opt-in live
  tests skipped, lint, instrumentation compilation), `:app:testDebugUnitTest`, `:app:exportDependencyInventory`
  and notice generation (116 components; the committed notices did not change), `package-apk.ps1` built the
  signed APK from commit `3158c5c`: **44,471,474 bytes**, SHA-256
  `2198f913a5a317b805c1a0dec873f075dcac8a98b5888932cfe4e78f6858e252`, min API 28, target API 36, the same 15
  permissions as 0.5.8 and the same certificate (SHA-256
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`). The size equals 0.5.7's and 0.5.8's; the
  hashes differ and its dex contains the new read-only worker and not 0.5.8's. `.tools\vercel-download-0.5.9`
  holds 31 files / 59,760,009 bytes (limit 99,000,000), no hidden file and no `.env`. The source ZIP has 452
  entries: 234 source files, each byte-identical to its blob in commit `3158c5c`, plus 218 dependency-source
  files. Name, content and known-private-value scans of the stage, the ZIP and the APK found nothing, and the
  dependency inventory (116) and the 16 unavailable-source entries equal 0.5.8's. This is recorded in
  `.tools\distribution-audit-0.5.9.json`; it was an inspection by the release agent with automated scans, not an
  independent audit.
- **Vercel.** Deployment `dpl_CGKAQVUnS3rwMPRo77HNZaysJn1q` (production, READY) was uploaded from that stage
  with the existing Vanishr project link (project and team IDs checked, no downgrade against the published 0.5.8
  feed). The CLI moved only the team alias, which is behind Vercel Authentication, so the canonical alias
  `vanishr-download.vercel.app`, which still served 0.5.8, was moved explicitly.
- **Public verification** (`.tools\public-release-verification-0.5.9.json`). The canonical feed reports 0.5.9 / 40
  with `no-store`; the 30 served files (`vercel.json` is configuration and is not served) are byte-identical to
  the audit; the downloaded APK verifies with the original certificate; CSP, nosniff, referrer, HSTS and the APK
  download headers are present; ten private or missing paths return 404; and the Android and security pages carry
  the 0.5.9 notes.
- **Over-the-air update, 0.5.6 / 0.5.7 / 0.5.8 to 0.5.9**
  (`.tools\ota-check-0.5.9\ota-verification-0.5.9.json`). On the dedicated Android 16 / API 36 emulator, with a
  temporary screen lock that was cleared afterwards, the published release APKs of 0.5.6 (code 37), 0.5.7 (38)
  and 0.5.8 (39) were installed one at a time. Each, with no manual trigger, fetched the live feed and showed
  the **Update available / Vanishr 0.5.9** sheet with **Download** and **Later**. Tapping **Download** launched
  an `ACTION_VIEW` browsable intent to `https://vanishr-download.vercel.app/...` in Chrome (Android redacts the
  path in its log; the app's feed validation accepts only the exact versioned URL on this host, and well-formed
  size and SHA-256 fields, but it does not hash the downloaded file itself; the public verification above hashed
  that file). The published 0.5.9 APK was then installed over each with
  `adb install -r`: it succeeded in place, the version code became 40, the first-install time was kept (so the
  app data stays), the app launched and was still running after 12 seconds, the crash buffer was empty and the
  update was no longer offered.
- **What this does not show.** The browser download itself and Android's installation approval screen were not
  completed by hand (Chrome stopped at its first-run screen on the recreated emulator). The update is not silent:
  the prompt opens the browser and Android asks the user to approve the install, and an app only checks
  automatically once a day, so an older install can take up to a day to show the prompt unless the user opens
  My profile and chooses Check for updates. No account existed on the emulator, so an update of a phone holding
  real account data, and the new app's behavior with a real account on a real relay, were not exercised.
  Nothing was measured on a physical phone.

**Not verified:** any physical phone; a phone whose Keystore is hardware backed or uses StrongBox, which
encrypts and decrypts more slowly, because the vault is still re-encrypted whole on each commit and opening
it still decrypts it whole (this release does not change that); vaults larger than the test data; the
release (minified) build on a device with a real account; the list with more than about 64 rows, which is
still built in one pass; whether a real relay under load changes how often a sync yields.

## Release 0.5.8 / Admin chat presence

Release 0.5.8 / code 39 fixes the automatic chat between the official admin and each new
account, which never showed Online, Typing or Last seen although a verified contact chat does.
The cause was client-side: presence audiences listed only independently verified contacts, and
the pinned automatic admin connection was deliberately left out on both sides (an earlier test
asserted an empty audience). The relay treats any two accounts that list each other the same
way, so there is **no relay or schema change** (the relay stays at schema 13) and no Azure
operation. The admin's app and the new account's app must both run 0.5.8; with an older app on
either side nothing is shared.

**What changed.** [ChatEngine](../android/app/src/main/java/app/vanishr/android/ChatEngine.java)
`automaticConnection` / `sharesPresence` and [AdminOnboarding](../android/app/src/main/java/app/vanishr/android/AdminOnboarding.java)
`connected` admit an automatic contact to the presence audience only while the saved contact
exists, its Signal identity is still pinned, it is neither blocked nor dismissed and the
official-admin pin check still passes (the pinned identity for a new account's view of the
admin, the local pinned identity for the admin's view of an introduced account); any exception
fails closed. [ContactPresence](../android/app/src/main/java/app/vanishr/android/ContactPresence.java)
fills its audience with the open chat first, then independently verified contacts, then
automatic ones, up to the relay's 128-contact limit, and its status snapshot uses the same rule.
`independentlyVerified` is unchanged, so groups, profile photos and the contacts backup still
exclude the automatic chat. The in-app policy summary and the public privacy, security, Android
and `llms.txt` text now say the admin and introduced accounts see each other's status. The in-app
policy version is unchanged by the owner's decision, so no re-acceptance screen appears.

Demonstrated on 2026-10-10 (synthetic accounts and relays only):

- `verify.ps1 -Android` passed. client-core: 19 tests. Relay: 101 tests (85 integration tests with
  PostgreSQL/Redis containers) with the two opt-in live tests skipped. The new integration test
  `officialAdminAndIntroducedAccountsShareOnlineTypingAndLastSeenOnlyWhenBothListEachOther` uses a
  real admin pin and its introduced accounts: nothing is shared until both sides list each other,
  an introduced account the admin did not list sees nothing, two introduced accounts cannot see each
  other through the admin, typing reaches only its recipient, and after the admin disconnects each
  listed account gets a bounded Last seen. Android: lint, 13 JVM unit tests and instrumentation
  compilation passed.
- Isolated QA instrumentation on the dedicated Android 16 / API 36 emulator. Four tests were added
  or updated: the former "no sharing permissions" admin test now expects the pinned admin in the
  audience while profile photos, remote photos and key claims stay off and the contact stays not
  independently verified (which groups and the contacts backup require); a new-account test covers Typing, Online
  and Last seen, a tampered saved identity, a block, a wrong pin, an unrelated contact carrying an
  automatic marker, forgetting the chat and manual verification; an admin-side test with 130 introduced
  accounts keeps the open chat first, then verified contacts, within 128 and shares nothing when the
  local identity no longer matches the pin; and a UI test shows Online, Typing and Last seen in the
  official-admin chat header. All four pass, and all four failed when `automaticConnection` was forced to
  `false` (the old behavior; checked before a code review tightened two negative assertions), so they test the fix.
- A full run of 178 QA tests (132 screen-flow, 41 account-safety, 5 backup) had 26 failures after
  Android's System UI stopped responding under host load and its "isn't responding" dialog stayed
  focused, so UiAutomator and keyboard checks could not see the app. All 26 are screen-flow tests,
  none touches presence or admin onboarding, and the 46 account-safety and backup tests passed. On a
  freshly booted emulator with animations disabled the 26 were rerun: 23 passed. Two fail as on the
  unmodified 0.5.7 build (`notificationColdStartIntentOpensTheChatAndKeepsViewOnceClosed`,
  `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount`). `receiptBurstsReuseEightyMessageRowsAndExpiryClearsCachedText`,
  a scroll-position race, failed in that rerun and in one of two isolated reruns and passed in the other.

**Publication, 2026-10-10 (UTC):**

- **Relay.** Unchanged: `git diff ab93146 HEAD -- relay/src/main infra` is empty, so the deployed
  schema-13 relay was not redeployed and no Azure operation ran. `/health` returned `up` after
  publication.
- **Build and stage.** After `:app:testDebugUnitTest`, `:app:exportDependencyInventory` and notice
  generation (116 components; the committed notices did not change), `package-apk.ps1` built the
  signed APK from commit `72c41fe`: **44,471,474 bytes**, SHA-256
  `1d90827940ce3089bb4a08447e5ecb7313b4e243530812d31c11d1b49c3517d7`, min API 28, target API 36,
  the same 15 permissions as 0.5.7 and the same certificate (SHA-256
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`). The size equals 0.5.7's; the
  hashes differ and its dex contains the new in-app summary and not the old one.
  `.tools\vercel-download-0.5.8` holds 31 files / 59,737,488 bytes (limit 99,000,000), no hidden file
  and no `.env`. The source ZIP has 452 entries: 234 source files, each byte-identical to its blob in
  commit `72c41fe`, plus 218 dependency-source files. Name, content and known-private-value scans of
  the stage, the ZIP and the APK found nothing, and the dependency inventory (116) and the 16
  unavailable-source entries equal 0.5.7's. This is recorded in
  `.tools\distribution-audit-0.5.8.json`; it was an inspection by the release agent with automated
  scans, not an independent audit.
- **Vercel.** Deployment `dpl_9GK51PD7fyf444sLChzKwj6oiab7` (production, READY) was uploaded from that
  stage with the existing Vanishr project link (project and team IDs checked, no downgrade against the
  published 0.5.7 feed). The CLI moved only the team alias, so the canonical alias
  `vanishr-download.vercel.app`, which still served 0.5.7, was moved explicitly.
- **Public verification** (`.tools\public-release-verification-0.5.8.json`). The canonical feed reports
  0.5.8 / 39 with `no-store`; the 30 served files (`vercel.json` is configuration and is not served)
  are byte-identical to the audit; the downloaded APK verifies with the original certificate; CSP,
  nosniff, referrer, HSTS and the APK download headers are present; ten private or missing paths
  return 404; and the privacy, security and Android pages carry the new status disclosure.
- **Update on a device.** On the dedicated Android 16 / API 36 emulator the published 0.5.7 APK was
  installed and the public 0.5.8 APK was installed over it with `adb install -r`: code 38 to 39,
  first-install time kept, the app launched, stayed alive after 12 seconds and left an empty crash
  buffer. No account existed on that emulator, so the status line was not exercised in the release build.

**Not verified:** two real phones or a live relay (the admin identity is pinned to the production
account and no admin was created on the shared relay); the release (minified) build showing the status
with a real account; the in-app "Check for updates" path and an update of a phone holding real account
data; more than 128 introduced accounts on a real relay (a client test uses 130, the relay tests a
handful); physical-phone and manufacturer foreground/background behavior; accessibility and
narrow-screen layouts of the status line beyond the existing header checks; independent security
review. **Rollout:** the official admin must install 0.5.8 in place and open Vanishr, and each new
account must update, before either sees the other's status. **Known limit:** the admin's app lists at most 128
contacts at a time (the open chat first, then verified contacts, then introduced accounts by name), so
with more introduced accounts an account outside that list sees the admin's status only while the admin
has its chat open. **Privacy effect:** every introduced account in the admin's list can now see when the
official admin uses the app, and the admin sees each such account's status; the relay already observed
this metadata. Play declarations were not changed or resubmitted.

## Release 0.5.7 / Encrypted contacts backup

Release 0.5.7 / code 38 adds an opt-in backup of verified contacts and private nicknames
([design](ENCRYPTION.md#8-account-backup-opt-in),
[threat model](THREAT-MODEL.md#threats-and-limitations)), uploaded by a second `dataSync`
foreground service, with restore on a new phone. It needs relay schema 13, which was deployed
before the APK was offered. The pre-release evidence comes first; the measured deployment and
public-release results follow it.

**Release build 0.5.7 / code 38, built on 2026-10-09.** It was packaged
with `package-apk.ps1` (Google/Firebase client configuration validated, relay health check passed,
release lint passed, signature verified). The release stage rebuilt it from the clean committed
tree and produced identical bytes.
APK: **44,471,474 bytes**, SHA-256 `4b2dbe6c1a6c3c25fcf784baa3f25b07faf7d56a026307c8f66854b7d8042136`;
min API 28, target API 36. It is signed with the same certificate as the published 0.5.6
(certificate SHA-256 `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`), so it can
update that release in place. Its 15 manifest permissions are identical to 0.5.6; the only
manifest addition is the `BackupService` declaration (`dataSync`). The backup flows were exercised
only in the unminified QA build; the R8 mapping keeps the backup record types, but the minified
build has not run them against a relay.

Demonstrated on 2026-10-09 (all with synthetic accounts and relays):

- `verify.ps1 -Android` passed. client-core: 19 tests, including six for the backup
  cipher (RFC 5869 HKDF vectors cross-checked with an independent .NET implementation,
  every-byte tamper rejection, wrong key/account, exact 512 KiB bound, strict recovery-key parsing).
  Relay: 99 tests in that run, with PostgreSQL/Redis containers. Four new
  integration tests cover authentication, owner isolation, replace/delete, size bounds
  (including 413 above 512 KiB), device-only access, bounded expiry and purge, account-erasure
  cascade and the write rate limit. The schema guard now pins the new table's exact columns,
  and the relay JAR still has no client-crypto dependency. After an opt-in live probe was added
  to `LiveRelayTest`, `mvn -pl relay -am test` passed again: 100 relay tests, 0 failures, with the
  two live tests skipped because they run only against an explicit relay origin.
- A first full run failed one new relay test: the 90-day bound was written as calendar days,
  which a daylight-saving session time zone stretches by an hour. It is now 2,160 absolute hours
  in both the statement and the CHECK constraint. The test exercises UTC, America/Los_Angeles and
  Asia/Kolkata sessions, checks the CHECK boundary at fixed timestamps, and failed against the
  calendar-day variant on 2026-10-09 (a window that crosses a daylight-saving change).
- Android: 13 JVM unit tests (seven new: strict snapshot validation, limits, redaction, sealing),
  debug and release lint with no issue in the new files, and an R8-minified release build that
  keeps the record types Gson needs.
- Isolated QA instrumentation on the dedicated Android 16 / API 36 emulator: five new
  engine-level tests passed, including a real `BackupService` start that uploads the sealed blob
  to a synthetic relay and stops itself, a restore that re-pins only the contact whose relay
  identity is unchanged (changed, blocked, automatic and username-less contacts excluded), wrong,
  malformed and damaged inputs restoring nothing, and turn-off keeping the key when the server
  delete fails. Two new UI tests passed (secure sheets, no key shown until requested), as did all
  41 account-safety tests.
- The full screen-flow suite ran 129 tests. Three fail identically on the unmodified HEAD build
  (`chatTypingAndSendDoNotWaitForBusyEncryptedStorage`, a 100 ms timing threshold;
  `notificationColdStartIntentOpensTheChatAndKeepsViewOnceClosed`;
  `remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount`), so they pre-date this work on this
  emulator; recorded earlier runs used Android 12. One pixel-colour check was flaky and passed on
  rerun. One new UI test raced the automatic refresh and was made deterministic.

**Deployment and publication, 2026-10-09 (UTC):**

- **Relay.** `publish-azure.ps1 -Action Check`, then `-Action Publish`, installed the relay JAR built
  from commit `fab7945` (its `V13__account_backups.sql` is byte-identical to the source) on the
  existing VM in `vanishr-dev-rg`. The only Azure operations were the script's temporary artifact
  storage account (created and deleted) and the VM run command that installs the artifact; VM size,
  disk, network and spending settings were not changed, and the group lists only the VM, disk, NIC,
  IP, NSG, VNet and run command afterwards. `/health` returned HTTP 200 before and after. The opt-in
  live probe `LiveRelayTest#realHttpsStoresOneOpaqueBoundedBackupPerOwnerAndErasesItWithTheAccount`
  passed against the public HTTPS relay: two synthetic `probe_` accounts stored, replaced and deleted
  a backup; retention was exactly 2,160 hours; one account could not read the other's backup; 63 bytes
  returned 400 and 512 KiB + 1 returned 413; a maximum-size 512 KiB blob made a round trip through
  Caddy; and each device token returned 401 once its account was deleted (the test removed both
  probe accounts).
- **Stage audit.** After `:app:testDebugUnitTest`, `:app:exportDependencyInventory` and notice
  generation (116 components; the committed notices did not change), `.tools\vercel-download-0.5.7`
  held 31 files / 59,728,475 bytes (limit 99,000,000), no hidden file and no `.env`. Its generated
  `vercel.json` is static-only, with the APK headers and a `no-store` feed. The source ZIP has 452
  entries: 234 source files, each byte-identical to its blob in commit `fab7945`, plus 218
  dependency-source files. Name, content and known-private-value scans of the stage, the ZIP and the
  APK found nothing, and the dependency inventory (116) and the 16 unavailable-source entries equal
  0.5.6's. This is recorded in `.tools\distribution-audit-0.5.7.json`; it was an inspection by the
  release agent with automated scans, not an independent audit.
- **Vercel.** Deployment `dpl_Ab5jhrT2UoVE7hDG2WTGxV8tKf4p` (production, READY) was uploaded from that
  stage with the existing Vanishr project link (project and team IDs checked). The CLI moved only
  the team alias `vanishr-download-vanishr.vercel.app`, so the canonical alias
  `vanishr-download.vercel.app`, which still served 0.5.6, was explicitly moved to the new deployment.
- **Public verification** (`.tools\public-release-verification-0.5.7.json`). The canonical feed
  reports 0.5.7 / 38 with `no-store`; the 30 served files (`vercel.json` is configuration and is not
  served) are byte-identical to the audit; the downloaded APK verifies with the original
  certificate; CSP, nosniff, referrer, HSTS and the APK download headers are present; ten private or
  missing paths (`/.secrets/...`, `/.env`, `/.git/config`, `/.vercel/project.json`,
  `/google-services.json`, `/infra/azure/cloud-config.json`, `/scripts/publish-azure.ps1`, a
  nonexistent page) return 404; and the privacy, security, delete-account and Android pages carry
  the backup disclosure.
- **Update on a device.** On the dedicated Android 16 / API 36 emulator, the published 0.5.6 APK was
  installed and the public 0.5.7 APK was installed over it with `adb install -r`. The package updated
  in place (code 37 to 38, first-install time kept), launched and stayed alive with an empty crash
  buffer. The public APK's manifest declares `BackupService` and `PhotoSharingService` as
  non-exported `dataSync` services and the same 15 permissions. No account existed on that emulator.

**Not verified:** the in-app "Check for updates" path and an in-place update of a phone that holds
real account data; the minified release build's backup, restore and foreground-service flows with a
real account (only the unminified QA build ran them; the generic R8 rule keeps every record and its
members, and the published manifest declares the service); a real rate limiter with 200 restored
contacts; physical devices, OEM foreground-service/Doze behavior and Android 12; the Android 15
`onTimeout` path; accessibility and narrow-screen layouts of the new sheets; Play Console
declarations and Data safety ([checklist](PLAY-STORE.md#foreground-service-declaration-draft)); any
independent cryptographic review.

**Rollback hazard.** Schema 13 is additive, but redeploying an older relay JAR after it is applied is
expected to fail Flyway validation (applied migration not resolved locally). There is no tested
rollback; it would need a reviewed manual removal of the `account_backups` table and its
`flyway_schema_history` row, so keep the V13-capable JAR for any redeploy.

The feature changes the earlier "no cloud content backups" non-goal; the project owner gave the
go-ahead to release it on 2026-10-09, and the threat model, public
privacy/security/delete-account/Android pages and Play checklist now describe it. The
in-app policy version `2026-10-02` was not changed, so existing users are not forced to re-accept;
the opt-in sheet itself discloses what the backup contains.

## Release 0.5.6 / Google and password account deletion confirmation

- A Google-created account's owner reported the same-account picker returned
  "Account deletion did not finish. Keep the app installed...". That generic
  message did not identify the failure stage; no actual provider token, email
  or affected-phone trace was collected.
- Three regressions failed against unchanged production code: a fresh Google
  challenge with a simulated ten-second server-ahead clock was rejected despite
  being within the challenge preparation's existing 30-second tolerance; fresh
  Google and password account-only authorization had the same strict-boundary
  failure. These reproduce a real client defect compatible with the reported
  message, not proof of the affected phone's clock setting.
- Challenge and deletion-authorization upper bounds now use the same fixed
  30-second tolerance. Already expired credentials remain rejected. The saved
  deletion credential is capped to its original server deadline or five local
  minutes, whichever is earlier. Recovery does not reset that deadline.
  Wrong-account, device-scoped, reusable, malformed and excessively long-lived
  credentials still cannot authorize deletion.
- The Google picker result remains bound to the selected account, origin and
  provider after reopening. Cancellation while backgrounded now returns to an
  account-bound retry prompt. Pre-request failures explain the safe cause
  (expired confirmation, account mismatch, connectivity, rate limit or validation)
  rather than an unexplained generic toast. Retry still requires typing DELETE
  and freshly authenticating. Pending deletion retains its separate proof-based
  cleanup flow. Confirmed erasure shows an explicit success message.
- **51 Android instrumentation tests passed, no failures/skips, 94.536 seconds**
  on the isolated Android 12 QA emulator: the complete account-safety suite and
  ten Google/deletion/sign-in UI regressions. Coverage includes popup pause/
  reopen/cancel/success, account-switch rejection, clock bounds, original
  credential/proof deadlines, wrong identities, loss of the deletion response,
  interrupted local cleanup, no re-enrollment, and preservation of other saved
  accounts. Google responses in these tests are synthetic.
- **Six PostgreSQL/Redis relay integration tests passed** for Google subject
  erasure, erased-account reauthentication protection, recent owner-only
  enrollment and proof recovery/expiry. The six existing Android unit tests,
  QA build and QA lint passed. The relay is unchanged; no backend deployment
  is required by this correction.
- Version 0.5.6 / code 37 packages these client-only changes for publication.
  Real Google-provider deletion on the affected phone is not verified.
  Never uninstall or clear app data as a deletion-recovery workaround.
- The final signed APK and separate Play-only AAB built successfully with
  release lint, the original certificate and validated Google/Firebase settings.
  Actual APK manifest: **0.5.6 / 37**, min API 28, target API 36; its direct
  update feed is present and 16-KiB ZIP alignment passes.
  APK: **44,438,025 bytes**, SHA-256
  `37266da2c6c52ca373b4df055c18143ede807e4c09fd99e126599006fec250a4`.
  Play AAB: **45,754,081 bytes**, SHA-256
  `ac6fecaba57ee72f6634b2840819bf0037fa9e899a4d2f769de1e5ba6e5e2010`.
  The AAB passes signature and pinned official bundletool 1.18.3 validation;
  its actual manifest, Google settings, matching notices, excluded external
  updater, 16-KiB native packaging and all four 64-bit native-library load
  alignments were verified. The bundle was not uploaded to Play Console.
- Signed **0.5.6** runtime checks on the isolated Android 16 / API 36 emulator
  passed for visible protected UI, Keystore encryption/deletion, native
  libsignal/wire serialization, backups/cleartext rejection and generic
  notification boundaries. The secured batch passed five tests with the
  incompatible no-screen-lock case skipped; that complementary case passed
  separately after removing only the generated emulator credential.
  The full live messaging workflow was not repeated for this client-only
  release; its prior 0.5.5 evidence remains separate. No Motorola-specific
  resolution or real Google-provider deletion is inferred from these checks.
- **Published and verified:** Vercel deployment
  `dpl_EnEubtEkE7e45CDq36pzW78qnEbi` is Ready; the exact canonical
  `vanishr-download.vercel.app` alias was explicitly updated. All **30 public
  artifacts** were anonymously downloaded and matched the audited hashes.
  The exact app feed advertises **0.5.6/code 37** with `Cache-Control: no-store`;
  the public APK verifies with the original release signer. Six private paths
  returned 404. The first check immediately after alias assignment retrieved
  the previous 0.5.5 home-page hash; after propagation, the complete unchanged
  verification passed without accepting stale content.
- The audited static folder totals **59,656,164 bytes**, with **224** exact
  matching application/documentation source files. Public source matches
  release-source commit `bde4b2d`; this later publication-evidence entry is
  intentionally not in the immutable archive. Evidence is saved locally in
  `.tools/distribution-audit-0.5.6.json`,
  `.tools/public-release-verification-0.5.6.json`,
  `.tools/release-verification-0.5.6/` and `.tools/play-store-0.5.6/`.
  No backend/Azure changes were made for this release; Play Console submission
  remains manual.

## Release 0.5.5 / offline first-message support

- The reporter confirmed `prekeys_unavailable` ("The contact needs to sign in
  before a new encrypted conversation can start") when a new user messaged the
  official admin. The existing relay consumed its one-time pool, expired keys
  after 24 hours, and offered no fallback; direct clients checked replenishment
  only hourly. The error does not mean the new account failed registration.
- The owner explicitly approved standard libsignal PQXDH fallback support and
  publication. The relay prefers one-time keys, then returns a recipient-published
  signed EC/Kyber fallback only to capable senders. The client publishes a fixed
  <=30-day fallback, rotates after about seven days and checks inventory every
  30 seconds while foregrounded, before inbox processing. Private retention
  covers the original deadline plus 24 hours. Successful fallback tuples are
  remembered to reject replay, including after removing a conversation session.
  Neither identity verification nor disappearing-message deadlines were relaxed.
- Thirteen native JVM protocol tests and ten focused real-PostgreSQL/Redis relay
  integration tests passed. They include twenty separate new accounts encrypting
  and queuing messages for an offline-admin fixture after its one-time pool
  expired; fallback remained usable without renewing its deadline. Legacy claims,
  one-time preference, owner isolation, invalid/extended deadlines, rotation,
  native decryption/replay, device deletion, permanent admin identity, blocks,
  account erasure and the relay's no-client-crypto boundary were checked.
- Sixteen Android 12 QA regressions passed in 28.266 seconds, including the exact
  initial-key error followed by successful retry without another sign-in or
  changing message ID/deadline; interrupted publication/reopening retained the
  same private key and deadline. Identity-change rejection, admin introductions,
  profile/group sessions, account partitioning and background cancellation passed.
  The first run had a 140 ms overrun in the existing <100 ms busy-storage callback
  test; the unchanged full focused batch passed without concurrent builds.
  Existing Android unit tests and QA/release lint passed.
- The V12 relay was deployed only to `vanishr-dev-rg`. HTTPS health is up, the
  actual running JAR matches SHA-256
  `8fd9c1c5e6d5fcc3a92b8b2a47717a9cbf689fad9d117a41bf04d08ea221d011`,
  the database reports schema 12 and one admin, and Google configuration remains
  present. Redis/PostgreSQL/HTTPS containers retained their existing uptime.
  Temporary artifact storage was removed; the subscription spending limit was On.
- Rollout requirement: the admin must update **in place** and open the app once
  to publish keys; the relay cannot generate its private key. New senders also
  need 0.5.5. Missing/expired fallback keys still fail closed. Publication does
  not promise unlimited offline availability, immediate delivery or retention
  beyond each message's original deadline.
- Separately, the owner confirmed a Motorola Edge 50 Pro / Android 16 user still
  sees a completely black screen on 0.5.4. No affected phone or sanitized app
  crash/ANR stack is available. Startup/lifecycle inspection did not establish
  another device-specific defect. Android 16 signed checks demonstrated visible
  app controls, Keystore access, native protocol serialization and backup/network
  protections (the complementary no-screen-lock check passed separately).
  An initial emulator probe was obstructed by a confirmed **System UI** ANR;
  that is not evidence of the Motorola's cause. Do not uninstall, clear data,
  remove the phone lock, or claim this release fixes that report.
- The final **signed 0.5.5 APK** completed the full existing live UI workflow
  against the deployed HTTPS relay on the isolated **Android 16 / API 36**
  emulator in **366.003 seconds**. It covered password registration, encrypted
  text/photo and view-once handling, receipt/read cleanup, reopening, retained
  account switching, session renewal/reauthentication, profile exchange and
  invite-only group messaging. The six device checks were covered across the
  secured run (five passes, one incompatible-fixture skip) and the separate
  no-screen-lock rejection test (pass).
  The legacy live test initially omitted the current Terms consent step; its
  test-only click helper now accepts that visible checkbox for each synthetic
  signup/sign-in. Production consent was not bypassed or changed. Earlier
  incomplete live attempts are not counted as passes.
  This supports Android 16 compatibility, not Motorola/OEM certification.
- Direct APK: 44,438,021 bytes, SHA-256
  `162cddf160bd880bd5bf28a59dfe1e9bf7dce39a88e6fd1b8ab7af11105e4c25`.
  Separate Play AAB: SHA-256
  `2c147f2fb3d11c778233fc03a76ed250cad0093b617e0c2bd038849ec379a790`.
  Both retain the original signing certificate and validated Google/Firebase
  client configuration. Official bundletool 1.18.3 validates the AAB; its
  manifest is code 36/min 28/target 36, native packaging is 16-KiB aligned and
  all four 64-bit native libraries have >=16-KiB load alignment. Matching notices
  and absence of the external APK updater were checked in the actual AAB.
  No Play Console upload, real Google-provider login or Motorola fix is claimed.
- Publication is complete: Vercel deployment
  `dpl_8Ki5MTb58GRTzn3yKcBdEpSDAsFg` is Ready and the exact canonical
  `vanishr-download.vercel.app` alias was explicitly updated. All **30 public
  artifacts** were downloaded anonymously and matched the audited hashes,
  including APK, matching source, notices, six pages and assets. The feed
  advertises **0.5.5/code 36** with `Cache-Control: no-store`; the downloaded APK
  verifies with the original signer. Six private paths returned 404.
  The audited static folder totals **59,650,150 bytes** and its source ZIP
  matched **224** application/documentation files; no signing material, client
  configuration, service credential or server environment file was staged.
  The immutable published source matches commit
  `4fc0083`; this later publication-evidence entry is intentionally not in it.
  Local audit/public evidence: `.tools/distribution-audit-0.5.5.json`,
  `.tools/public-release-verification-0.5.5.json`; Play bundle and verification:
  `.tools/play-store-0.5.5/` (AAB **45,751,985 bytes**).

## Release 0.5.4 / startup and foreground recovery investigation

- Published direct-download `0.5.4` / code `35` packages the tested fixes below.
  The matching signed Play AAB is prepared separately for manual submission.
  No backend deployment, account reset or provider configuration change was
  needed for this Android-only release.

- A user reported slow opening, crashes and an Android "not responding" state
  on an unspecified Motorola Edge device running 0.5.3. No affected phone,
  exact model/Android version or crash/ANR trace was available. Username-based
  lookup cannot provide these: device registration stores a random device ID
  and public identity, not phone model, OS version or crash history. No real
  account records, content or credentials were accessed for this investigation.
- An unauthenticated public relay health check returned HTTPS 200 in 0.383
  seconds from the workstation. This is not a measurement of the user's network.
- Two failures were reproduced before changing production code in the isolated
  Android 12 QA emulator:
  - Home-avatar refresh blocked the main thread for **2,003 ms** while another
    task held the vault lock (required callback bound: <100 ms).
  - Backgrounding did not cancel an in-flight relay request within one second:
    connection/vault closure was queued behind the blocked network task.
- Avatar reads/decoding now run on the worker with engine, screen, request,
  photo-revision and deadline checks. Stale results are recycled; failed storage
  reads report a protected-storage error without deleting the account.
  Cleanup cancels active network requests before queued vault closure, and
  retired connections reject subsequent requests. Account data, refresh-recovery
  state and explicit sign-out cleanup are preserved. Late completion callbacks
  cannot reset the busy state of a different screen generation.
- The final focused run passed **19 Android instrumentation tests** without
  failures/skips in 81.129 seconds. Measured avatar-refresh callback time was
  **2 ms** with the same held vault lock; background transition plus request
  cancellation was **299 ms**, below the one-second bound. The existing busy-
  storage typing/send batch remained within its <100 ms bound at **78 ms**.
  These are deterministic emulator callback/cancellation measurements, not
  physical-Motorola frame timing or real-network delivery guarantees.
- Coverage includes storage failure reporting, expired/stale-avatar removal,
  normal/Google/password account reopening, sign-out while backgrounding,
  renewal recovery, pending-send/privacy cleanup, photo permissions/UI,
  obsolete sync callbacks and the independent photo-service lifecycle.
  QA compilation/lint and release compilation/lint passed.
- An earlier unprecompiled run had a 137 ms timing overrun; the final run uses
  precompiled QA packages without loosening the threshold. The avatar expiry
  assertion now checks the contact avatar itself, not unrelated bitmap branding.
- The affected user's exact crash cause remains **unconfirmed**. For a device-
  specific conclusion, obtain the model and Android version from About phone,
  installation source, approximate failure time and an app-scoped, sanitized
  crash/ANR stack. Do not share a full bugreport/logcat containing accounts,
  tokens, request headers or private content. Do not uninstall or clear app
  data to collect diagnostics.
- No production telemetry or account reset is introduced. The release retains
  the existing signing identity, Google/Firebase configuration and the separate
  website/Play updater channels. This is not a confirmed fix for the reported
  physical Motorola device without its crash/ANR evidence and retest.
- Source commit: `5e00f7119a262defb6aa423d2c32d2c56e45a40b`. Both final artifacts
  passed compilation, R8, release lint and signature checks. APK:
  44,438,029 bytes, SHA-256
  `b251ed1558eaa7e56f16c25cef94a39ca3aeac60a567f7f33b7bf4f4e5c239f3`.
  Play AAB: 45,749,719 bytes, SHA-256
  `08a70cc31d85c062a9646296ef74efc4452b6cfafe4621f1876eda19534a00d5`.
  The original release/upload certificate SHA-256 remains
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`.
- The AAB passed checksum-verified official bundletool validation, actual
  package/version/minimum-28/target-36 checks, original signer, matching notices,
  16-KiB bundle packaging and load-segment checks for all four 64-bit native
  libraries. Its actual DEX includes the configured Google/Firebase values and
  Play listing action, with the external APK updater absent. It is saved at
  `.tools/play-store-0.5.4/vanishr-0.5.4.aab` alongside verification evidence.
- Inspected 31 static files / 59,637,603 bytes and the 441-entry corresponding-
  source ZIP against the explicit allowlist. No private artifact paths or
  credential-content markers were found. Source ZIP SHA-256:
  `589334fbfebd926e90625a37dfe3b2264960174065b703ff0ecd95d969107d12`.
- Published only that distribution to the existing Vanishr Hobby project:
  `dpl_8LWQj87EwC4DeiGLnY1fLrwpcmiT`. The canonical public alias was explicitly
  moved to the Ready deployment. At `2026-10-05T07:12:52Z`, all 30 public
  artifacts matched their audited hashes anonymously, including six pages and
  the actual APK. Public APK manifest/signature and embedded Google settings
  matched; the exact app feed serves `0.5.4` / `35` with `no-store`.
- Distribution, Play and public verification records are saved outside the
  upload folder. No Play Console upload, Play signing enrollment verification
  or real Google-account login was performed. Existing Play policy/consent and
  production-readiness limitations are unchanged.

## Release 0.5.3 / readable Google usernames

- Published `0.5.3` / code `34` gives first-time Google accounts usernames such
  as `quiet-otter-4827`. Each position has **512 distinct English words**;
  an independent random number from 1000 through 9999 gives
  **2,359,296,000 candidates**. Selection uses `SecureRandom`, not the Google
  profile/email or a substring of the account UUID.
- Existing `g_...` and user-chosen handles remain unchanged on sign-in. Internal
  account UUIDs, device identities, Signal keys, admin roles and the permanent
  single-admin pin are unchanged. A username remains public metadata, not an
  authentication secret or verification proof.
- PostgreSQL enforces global handle/Google-subject uniqueness. Creation retries
  collisions without aborting the transaction, returns the already-created
  account for concurrent requests on the same subject, and fails explicitly
  after at most 16 attempts. It cannot claim a pre-existing password/admin
  account just because a generated handle collides.
- V11 expands only the handle CHECK constraint to accept hyphens, keeping the
  3-32-character bound and previous lowercase/digit/underscore names. Relay
  registration/login/rename and Android input, lookup, profile, admin-directory
  and group-member validation accept the new format.
- **Compatibility:** both participants, the admin and group members should
  update to 0.5.3. Earlier clients may reject new hyphenated handles, including
  those returned by a first Google sign-in; this is not fixed by changing only
  the website. No existing user is forcibly renamed.
- Sixteen targeted JVM tests passed with zero failures/errors/skips: three
  vocabulary/format tests, three Google-authentication tests and ten PostgreSQL/
  Redis integration tests. Coverage includes the >=500-words-per-list threshold,
  every word-pair's length, exact numeric boundaries, 10,000 sampled names,
  deterministic collisions, concurrent same/different subjects, bounded
  exhaustion, old/custom-name preservation, API lookup/login/rename, admin
  onboarding and a V10-to-V11 migration preserving accounts/devices/admin state.
- Eight focused Android instrumentation tests passed with no failures/skips
  on the isolated Android 12 `Vanishr_Onboarding_Test` emulator and separate QA
  package (45.36 seconds). They cover hyphenated lookup from the real dialog,
  profile/account persistence, identity retention, group validation, admin
  onboarding, legacy usernames and Google reauthentication. The six existing
  update-parser unit tests and QA compilation/lint also passed. Synthetic QA
  account data and its generated screen-lock credential were removed.
- The tested relay JAR contains V11 and the generator, with no production
  dependency on client-core/libsignal. SHA-256:
  `fbb22ae4b718268a061a54c196efdc40078fc93047038effc1ab6f892d300efe`.
- Source commit: `15ae9f835fd3b6a95ba64e99ac815757616f263b`. Final signed APK
  and Play AAB compilation, R8, release lint and signature checks passed.
  APK: 44,438,025 bytes, SHA-256
  `68f3dc7a478c2a58ad7d6ac5d10ccf3ce19670df65888c00dfb6563dba81bdd0`.
  AAB: 45,740,959 bytes, SHA-256
  `9cc3fcb3913255144e976f65fc668e1b26c1025667df27c43e3113362ce5b9ef`.
  Both contain the existing Google/Firebase settings and hyphen-compatible
  validation; the direct APK retains its website updater, while the Play AAB
  contains the Play listing action and no external APK updater.
- The AAB passed checksum-verified official bundletool validation, actual
  package/version/minimum-28/target-36 checks, the original upload signer,
  matching notices, 16-KiB bundle packaging and load-segment alignment checks
  for all four 64-bit native libraries. It is preserved separately at
  `.tools/play-store-0.5.3/vanishr-0.5.3.aab`.
- Deployed the tested relay only to `vanishr-dev-rg` in the approved subscription.
  A read-only check of the running container matched the JAR hash above and
  verified applied schema 11 and the hyphenated-handle constraint. Public
  HTTPS health and the existing Google audience/challenge checks passed.
  Temporary transfer storage was removed; no VM sizing or provider credentials
  were changed, and the subscription spending limit remained On.
- Inspected 31 static files / 59,633,311 bytes and a 441-entry source archive
  against the explicit source allowlist. No private artifact paths or
  credential-content markers were found. Source ZIP SHA-256:
  `8ae0a4f8016fe4f9d211572f5db530421cc9e7139be11bc39441db6d37315f6e`.
  Vercel deployment `dpl_BGbSDqyNRc8z6HGvhHnBafSGcQcc` is Ready on the existing
  Vanishr Hobby project; the canonical public alias was explicitly updated.
  At `2026-10-05T03:20:55Z`, all 30 served artifacts matched their audited
  hashes anonymously, including the signed APK and all six website pages.
  The exact update-feed URL serves `0.5.3` / `34` with `no-store`.
- Distribution, relay, Play-bundle and public-verification JSON evidence is
  saved outside the upload directory. No real Google-account login or Play
  Console upload is claimed. Existing Play review/consent/production-readiness
  concerns are not resolved or reclassified by this username-only release.

## Release 0.5.1 / restore Google sign-in configuration

- Published `0.5.1` / code `32` restores the existing Google/Firebase Android
  client settings omitted from the 0.5.0 APK and Play bundle. The earlier
  "Google sign-in is not configured for this build yet" message was triggered
  locally by the empty compiled Web OAuth client ID, before contacting Google.
  Existing encrypted accounts, signing keys, relay identity checks and the
  separate website/Play update channels are not changed.
- The saved client configuration passed the existing package, release-certificate,
  Web/Android OAuth client and Firebase-field validation. A real HTTPS relay
  challenge matched the configured Web OAuth audience and its bounded shape/
  deadline. No Google ID token, challenge value or client key was printed.
  This preflight is not a completed Google account login or FCM delivery test.
- Release packaging now loads an explicitly selected or unambiguous local
  client configuration and fails if it is missing/invalid. A Google-free build
  requires explicit `-NoGoogleServices`; that choice clears inherited provider
  settings only for the build and warns about losing Google account access.
  Unknown command parameters are rejected. Low-level debug/QA builds retain
  their existing configuration behavior.
- Thirteen local packaging regressions passed: default and explicit selection,
  APK/Play settings, missing/ambiguous/malformed configuration, wrong certificate,
  missing Web OAuth, rejected server credentials, misspelled/conflicting options
  and environment restoration. The fixtures use no real signing credentials,
  network requests or Android builds.
- Both final signed artifacts passed compilation, R8 and release lint. The actual
  APK and AAB DEX contain the validated Google/Firebase client values, checked
  without displaying them. The direct APK retains the website updater; the Play
  AAB retains its Play listing action and excludes the external APK updater.
  The original release/upload certificate is unchanged.
- Direct APK: 44,438,025 bytes, SHA-256
  `66bc5048ef87b6c7a5dafbff40262bf8f5e368aca1bd8e33918b27b64d7452ea`.
  Play AAB: 45,741,102 bytes, SHA-256
  `1783680526344f02e12be100384f5e6d49d70149b6b0b16f8347bc20ccb121d7`.
  The AAB passed official checksum-verified bundletool validation, actual
  package/version/minimum-28/target-36 manifest checks, signer/notices checks
  and 16-KiB packaging/load-segment alignment for all four 64-bit native libraries.
  The versioned AAB and its verification record are in `.tools/play-store-0.5.1`.
- Source commit: `15f9dd76e3bc423883d2d3f5f7a3a9068dbad10e`.
  The inspected static stage contains 31 files / 59,621,007 bytes and a
  438-entry corresponding-source ZIP. Client JSON, server credentials, signing
  material and private deployment files were excluded; no credential-content
  markers were found. Source ZIP SHA-256:
  `c03a812c233c7dc14977fc14dedf061941a0d73a06d78369aa054dce9f0fcae0`.
- Published only that stage to the existing Vanishr Hobby project:
  `dpl_EBrwxQh1i8xd2kHuRDBhV7UrQAR7`, then updated the canonical public alias.
  At `2026-10-03T15:19:18Z`, all 30 public artifacts matched their audited hashes.
  The anonymously downloaded APK's version, original signer and restored
  Google/Firebase values were verified. The exact update-feed URL advertises
  `0.5.1` / `32` with `no-store`.
- No relay/OAuth project configuration, account records or Play Console settings
  were changed. Real Google-account sign-in and FCM delivery were not performed
  by this verification. Play-installed Google access still requires registration
  and authorization of the actual Play app-signing certificate/client, which
  may differ from the upload certificate. No Play upload or approval is claimed.

## Release 0.5.0 / active-chat responsiveness

- Published direct-download release: versionName `0.5.0`, versionCode `31`.
  The regression evidence below covers the tested responsiveness changes before
  the version-only metadata bump; the final signed release also passed R8,
  compilation, release lint and APK signature verification.
- Release source commit: `e5b16a0d754a440f5ae3fcb7656d7d705d2d59b7`.
  The APK is 44,438,025 bytes, SHA-256
  `17345ce26c31a3bebeaf061cde4aad04cd651b01b52e56c53313aeab43e7a76f`.
  Its existing release certificate SHA-256 is
  `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`.
- Deployed only the inspected static stage to `vanishr/vanishr-download` on the
  existing Hobby team: `dpl_5iitogZ8tVcUSELcbjGWAbPsP4UA`. Explicitly assigned
  the public alias <https://vanishr-download.vercel.app> to this deployment.
  Anonymous verification completed at `2026-10-03T10:02:03Z`, after alias
  propagation: the exact update-feed URL returns `0.5.0`/`31` with `no-store`;
  the downloaded APK's checksum, embedded version and signer all match.
- The inspected stage contains 31 files / 59,615,517 bytes, including the Vercel
  configuration. All 30 public artifacts were downloaded anonymously and
  matched their audited hashes, including six pages, source, notices and assets.
  The 437-entry source ZIP matches the explicit packaging allowlist and its
  inspected source; no private paths or credential-content markers were found.
  Source ZIP SHA-256:
  `9c6bf506d5df21ccb64fbf6ed1cde9c2088d5445338d58267c2f56720bcc7d1f`.
  Local distribution and public-verification JSON records were saved outside
  the upload folder for subsequent website-only releases. Only the validated
  public project-link JSON was reused; no CLI environment file was uploaded.

- Receipt and message updates previously rebuilt the conversation and called
  protected-content decryption/read persistence on the main thread. Protected
  entry reads and new-message text loading now run on the existing serialized
  worker. Receipt changes update retained rows in place, preserving the composer,
  caret and scroll position. Durable outgoing bubbles still appear after local
  encrypted persistence without waiting for the upload response.
- Presence-header validation no longer reads the vault from the UI timer, and
  typing edits no longer contend with a presence lock held during vault reads.
  Snapshot expiry, disconnect, identity checks and foreground cleanup remain in
  effect. A websocket wake received during a sync now schedules one follow-up,
  rather than potentially waiting for the next 15-second poll.
- QA APK/instrumentation compilation, QA lint and release compilation/lint passed.
  The final focused run passed **27 Android instrumentation tests**, with no
  failures or skips, in 95.635 seconds on the isolated, secured Android 12
  `Vanishr_Onboarding_Test` emulator using the separate `.qa` package.
- New regressions demonstrate:
  - Twelve text edits, a send tap and entering the next draft completed in
    **73 ms** while the worker held the vault lock (required bound: <100 ms).
    This is a UI-callback batch measurement, not end-to-end delivery latency.
  - An 80-message history retained the same row objects across three receipt
    transitions and 90 refresh requests, preserving draft, focus, caret and
    scroll. A fresh incoming text rendered and became read; view-once content
    was not consumed. Expired text was cleared from the retained row.
  - A stored outgoing bubble appeared while the synthetic relay upload remained
    blocked, without claiming delivery or replacing the next draft.
  - Typing remained responsive while presence audience validation waited on
    storage. Backgrounding discarded a queued conversation read, retained its
    unread state and cleared previously rendered text.
  - A burst of 100 wakes during an in-flight sync was coalesced and fetched again
    within five seconds of releasing the synthetic response, without waiting
    for the periodic poll.
- Related tests cover retry IDs/deadlines, pending-send cancellation, clear chat,
  presence identity/expiry/last-seen rules, secure profiles, group consent and
  removal, notification view-once behavior and official Signal admin messaging.
  The generated emulator credential and synthetic QA account data were removed.
- These are local deterministic fixtures, not a WhatsApp comparison or sustained
  two-phone/mobile-network load test. Real-device frame timing and live relay
  delivery latency still need testing. This direct-download release keeps the
  existing relay, signing identity and unconfigured Google/FCM client settings.
  Publication is not a Google Play submission or a production security audit.

## Release 0.4.8 / bilateral administrator onboarding

- Version 0.4.8/code 28 makes the server-authorized official-administrator
  relationship visible on both sides before either participant sends a message.
  The client now persists validated introductions for the administrator as well
  as ordinary users. Background refresh always starts with the newest page, and
  previously validated pages remain in the protected local contact store.
- V10 timestamps administrator introductions and orders the administrator's
  bounded directory newest-first. The existing V6 single-admin database
  constraints and pinned Android administrator identity remain unchanged.
  Clients still cannot choose an administrator, promote an account or replace a
  previously pinned identity.
- Two targeted relay integration tests passed with no failures, errors or skips.
  Two Android onboarding regressions passed on the isolated secured QA emulator,
  including a 65-contact paged directory and visibility before messaging.
  Android compilation, update-parser unit tests and signed-release lint passed.
  The final full Maven reactor run passed 83 tests with no failures or errors
  and one environment-gated skip.
- The schema-10 relay artifact was published only to the approved
  `vanishr-dev-rg` deployment. Temporary transfer storage was removed and the
  public HTTPS health endpoint returned `{"status":"up"}` after installation.
- Final direct APK: 44,421,641 bytes, SHA-256
  `f9547ef492a98c172ec5792e4daf78973ee6e76e9ac9acdd2d03ff912ed28cec`.
  APK Signature Scheme v2 verification passed with the existing release
  certificate.
- Final Play AAB: 45,734,605 bytes, SHA-256
  `96157d0c7e5da80be34a93a55c1cb1383f39c389bbf5e4ffee52318954bf55c8`.
  JAR signature verification and official bundletool validation passed;
  bundletool reported version code 28 and version name 0.4.8.
- The static 0.4.8 candidate contains 31 files / 59,602,665 bytes. Both the
  staged files and 469-entry source archive were checked for secret, signing-key
  and deployment-state paths with no matches. Vercel publication is **pending**:
  the Vercel CLI and a direct request to the public Vercel origin both hung from
  this workstation, so no successful deployment or public-file verification is
  claimed.
- No Android Firebase client configuration was available locally. The artifacts
  were therefore built without configured FCM/Google sign-in client values;
  those provider features are not claimed ready in this release.

## Release 0.4.7 / Play candidate

- Version 0.4.7/code 27 implements fresh-authentication account deletion,
  owner-only blocked accounts, server-enforced direct-contact blocking,
  metadata-only user/message reports, pinned-admin review, explicit Terms
  acknowledgement and in-app privacy/deletion/support links. Account deletion
  removes only the current local account partition; other remembered accounts
  and their protected keys are preserved.
- V8 adds deletion state/block relationships and guards that preserve only the
  erased admin's permanent UUID reservation. V9 adds digest-only, bounded
  24-hour deletion receipts so a lost response or Redis reset cannot turn an
  arbitrary 401/404 into success. Dedicated proof status/retry never logs in,
  reenrolls, chooses another account or extends its deadline. Saved Google
  reauthentication is bound to the expected existing UUID rather than creating
  a replacement account after erasure.
- The relay was deployed to the existing approved host before client publication.
  Read-only postflight confirmed schema 9, the same sole admin and public-key
  fingerprint, exact packaged JAR, healthy services, unchanged PostgreSQL/Redis/
  HTTPS containers and authenticated-only safety endpoints. The original 75
  hosted accounts were unchanged by deployment.
- The final targeted relay matrix passed **42 tests**, no failures/errors/skips,
  including 31 integration checks and 11 Google/auth/notifier/policy checks.
  A broader 68-test relay regression run also passed earlier in implementation;
  counts overlap and must not be added. Final native verification passed **54
  tests** in 240.35 seconds on the isolated Android 12 fixture (37 safety-core
  cases plus 17 UI/crypto/permission-boundary regressions), with no skips.
  Six update-parser unit tests, QA lint and signed-release lint passed.
- A live smoke test created two synthetic accounts, verified new-admin discovery,
  DEVICE-token deletion rejection, block/unblock enforcement against the other
  client, metadata-only report submission with admin-only review, fresh-ENROLL
  deletion, old-token/login rejection, and original-deadline proof confirmation
  and retry. Both synthetic accounts were deleted. Two earlier attempts were
  cleaned up too; they exposed a 108-millisecond clock-skew validation issue,
  corrected with a 30-second client validation allowance without changing any
  server TTL. Group-only reports no longer falsely require a message ID.
- The Play bundle is built with `-Bundle -PlayStore` (Gradle
  `-PplayStore=true`), which disables the external APK update check/prompt and
  uses the Google Play listing instead. The ordinary direct-download APK retains
  its existing update path. Same package and signing identity are retained.
  Final Play AAB: 45,733,371 bytes, SHA-256
  `18366ae3135c520307a53835affbdb9524ee436264925c265911b4ed30ef44e0`.
  Official bundletool validation, version/package/target-36 checks and original
  signing-certificate verification passed. The bundle requests 16 KiB native
  packaging; its 64-bit libraries match the previously inspected 16 KiB-aligned
  binaries. No new 16 KiB runtime test was performed.
- Public-policy templates cover app/website privacy, account deletion by email
  without reinstalling, Terms/child-safety standards, and the supplied support
  inbox. The six-page stage passed link/CSP checks and 24 viewport checks without
  changing consent-only website analytics. Publication state is recorded below
  after the immutable distribution is uploaded and checked.
- Evidence: `.tools/play-safety-0.4.7-verified-native.txt`,
  `.tools/play-safety-host-after.json`, `.tools/play-safety-relay-artifact.json`,
  `.tools/play-store-0.4.7/validation.json`, current Surefire reports, and the
  sanitized live smoke/website evidence in the session workspace. No full
  Android suite, physical-phone acceptance or independent security audit is
  claimed.
- Public direct-download deployment `dpl_BJM7xauBekngSTAqHKxFNT38xDQR` is READY
  on the existing Vanishr Hobby static project. The audited bundle contains 31
  files (59,617,431 bytes) and 217 matching workspace source files. The APK is
  44,438,029 bytes, SHA-256
  `3882f5acaa868cbda703c58209ddfe929107254c833f8d4ed5c99b0067b451a0`.
  Anonymous verification matched all 30 public files, original APK signer,
  code-27 no-store update feed, six crawlable pages, eleven canonical redirects
  and nine private/missing-path 404s. Privacy, Terms and deletion-request pages
  are publicly accessible without app installation or login. Initial alias
  propagation briefly returned the old home page; the complete retry passed.
  This is website/APK publication, not a Google Play upload or approval.
  Evidence: `.tools/distribution-audit-0.4.7.json`,
  `.tools/public-release-verification-0.4.7.json` and
  `.tools/public-website-verification-0.4.7.json`.
  Publication-status documentation updates postdate the immutable source ZIP;
  the signed binaries and archived source were not silently repackaged.

## Release 0.4.5

- Code 26: new logo and automatic official-admin conversations. The relay (schema
  V6 single-admin lock, V7 admin introductions) was deployed to the existing
  `vanishr-dev-rg` host on 2026-10-01 before the APK. Live read-only checks: schema
  version 7, exactly one admin (same UUID digest as before), valid unique-admin
  index, triggers, restrictive foreign key, exact deployed JAR hash, four services
  healthy, no change to PostgreSQL/Redis/HTTPS containers, trusted-TLS health 200,
  anonymous admin-directory request 401 with no-store, 0 introduction rows (no
  backfill) and 74 existing accounts unchanged. The admin profile is display name
  `Vanishr`, username `vanishr`; no email is stored anywhere in the account schema.
  VM size and the subscription spending limit were unchanged; temporary transfer
  storage was removed.
- Introductions are database-trigger-created for accounts inserted after V7 only
  (password and Google). Participants see only their own introduction; the admin
  directory is paged at 64 and rate-limited. Peer encryption state is created only
  when a conversation is opened or a message arrives.
- Not verified: the full JVM and 99+ method Android suites and the signed live
  workflow were not rerun for this release; physical devices and OEM behavior;
  a live end-to-end account creation against the hosted relay. If the admin's
  registered device is replaced, the compiled pin intentionally blocks automatic
  admin chats until a reviewed app update. The first new-user binding on the
  admin side trusts authenticated relay enrollment; a compromised relay could
  omit or lie about that binding, but cannot impersonate the pinned admin to
  users' apps.
- Published on 2026-10-01 as 0.4.5/code 26: production deployment
  `dpl_BZ8SRyT8a1RVxfgnd6gM7wYazqw7` in the existing `vanishr-download` project on
  the Vanishr Hobby team (zero builds/functions). The public alias
  `vanishr-download.vercel.app` was repointed from the 0.4.4 deployment
  `vanishr-release-044-ea0i8o0nv-vanishr.vercel.app`, which remains available as
  the rollback target. The immutable 29-file bundle (59,416,250 bytes) passed the
  audit (199 matching source files, 417 source entries, no known secrets or native
  test libraries). Signed APK: 44,388,181 bytes, SHA-256
  `9013504b3f694c7b8cd5c271c3f3bbc5e0bb903bf0c74125d695451ead2b23e0`, original
  signer. Anonymous verification matched 28 public files/hashes, the original APK
  signer, the no-store code-26 feed, security headers and six private-path 404s.
  The temporary Vercel CLI login and local project link were removed. README and
  this document's publication-status edits postdate the source archive; it was not
  rebuilt. Records: `.tools/distribution-audit-0.4.5.json` and
  `.tools/public-release-verification-0.4.5.json`.
- Evidence: `.tools/admin-onboarding-0.4.5-final-qa.txt`,
  `.tools/admin-onboarding-0.4.5-android12-ui/`, `relay/target/surefire-reports`
  and the hosted postflight records retained in the session workspace.

## Permanent single admin (relay only, unreleased, 2026-10-01)

- Migration V6 pins the existing sole admin's UUID, limits `ADMIN` to one account,
  and rejects pin replacement/removal or deletion/change of that UUID. Revoking
  the role does not release the pin. Empty installations grant nobody admin
  automatically; multiple existing admins make the migration roll back.
- Maven packaging succeeded. The packaged V6 migration matches the source, and
  the relay artifact still excludes client-core and libsignal dependencies.
  Editor diagnostics and diff checks are clean. The release-test script parses
  and rejects `-RemotePhotos` before any emulator, account or cloud side effects;
  it no longer creates temporary admins on the shared hosted relay. The 0.4.2
  live test described below is historical, not a repeatable shared-admin fixture.
- Initial validation was blocked by host memory pressure and then a stopped
  Docker engine. A bounded-memory retry with the local engine running completed.
  One new test initially expected payload rejection before the existing
  enrollment-scope rejection; it now checks both boundaries separately.
- Evidence: `relay/target/surefire-reports/app.vanishr.relay.RelayIntegrationTest.txt`
  and the corresponding XML report. The full JVM suite, Android build/device
  suites, and hosted upgrade were not run for this relay-only change.
- No hosted database or relay has been updated, and no APK has been published.
  Before deployment, inspect the current admin count/identity; do not resolve an
  ambiguous database by choosing or demoting an account automatically.
  These checks do not protect against takeover of the pinned account or a
  trusted host/database owner deliberately changing the protections.

## Owner lock continuation (unreleased)

- The approval text explicitly discloses locked-phone access. The notification's
  redacted lock-screen version omits the contact name and retains the immutable
  End access action.
- Initial attempts hit a screenshot-confirmed System UI ANR, then a relocked
  fixture after System UI restarted. The PIN-enabled test setup now waits for
  the normal Android PIN field before entering its generated credential; no
  production unlock check was bypassed. Attempt reports were preserved.
- Evidence: `.tools/remote-photos-owner-lock-build.txt`,
  `.tools/remote-photos-owner-lock-fixture-build.txt` and
  `.tools/remote-photos-owner-lock-focused.txt`. Physical phones, OEM/Doze behavior
  and Android 14+ runtime acceptance remain unverified. The full 99-method suite
  and signed live release gate were not rerun for this local follow-up.
- This change is not in the published 0.4.2 APK, and no relay, hosted account or
  immutable distribution has been changed.

## Release 0.4.2

- Published on 2026-09-24 as 0.4.2/code 23. ADMIN-only initiation and one owner
  Allow/Don't allow gate are implemented,
  with required Android permissions and an owner-approved persistent notification
  offering End access for background sessions.
- Clear chat follows Remove contact in the direct-chat menu, with a local-only
  confirmation. Two focused checks passed in 10.210 seconds for ordering,
  cancellation, deleted content keys and outbox, preserved contact/identity,
  unchanged other chats and retained expiry-bounded replay markers.
- The original-signer APK passed release lint and the R8/instrumentation build.
  The complete signed live workflow passed in 326.940 seconds: actual FCM, owner
  photo approval, two thumbnail pages, byte-exact original transfer after owner
  backgrounding, immutable-notification revocation, Clear chat cancellation and
  confirmation, and successful encrypted messaging afterward. Five protected-
  device tests passed in 3.513 seconds and no-screen-lock rejection in 0.020 seconds.
  A generated synthetic admin was demoted in cleanup; no existing admin, real
  gallery or personal device was used. Synthetic images and temporary PIN were
  removed. No production security checks were relaxed.
- Signed APK: 44,282,893 bytes, SHA-256
  `7f0eaca14c8c6adae23c27b94cb07569bbb92ee493ca5dfec74aae47c5a90042`.
  The immutable 27-file bundle is 58,670,230 bytes; audit verified 184 matching
  workspace source files and 402 source entries, with private values and native
  test libraries excluded. Source ZIP: 9,792,581 bytes, SHA-256
  `1966ebee26c543fdee1152279ed67bf583062f29f9aa5ae6f125e46446400500`.
  The 16 optional unavailable upstream source artifacts are unchanged from 0.4.1.
- Production deployment `dpl_3ZBC9JRiEvTci79NR8wCUCjz7RHk` is READY at
  `https://vanishr-download.vercel.app` on the existing Vanishr Hobby/static
  project, with zero functions/builds and no domain, plan or Google configuration
  changes. All 27 files were hash-verified in the browser before upload.
- Anonymous public verification passed all 26 file hashes/sizes, the original
  APK signer, no-store code-23 feed and security headers. Four crawlable pages,
  seven canonical redirects and nine private/missing-path 404s passed. Records:
  `.tools/distribution-audit-0.4.2.json`, `.tools/website-build-0.4.2.json`,
  `.tools/public-release-verification-0.4.2.json` and
  `.tools/public-website-verification-0.4.2.json`. Final publication-status edits
  to README/this document postdate the immutable source archive; it was not rebuilt.
- The owned Android 12 test emulator was stopped after generated PIN and synthetic
  app-data cleanup. Existing user accounts and real galleries were not modified.
- Local and live test evidence:
  Evidence: `.tools/clear-chat-0.4.2-focused.txt`,
  `.tools/release-0.4.2-jvm-verify.txt`, `.tools/release-0.4.2-unit-tests.txt`,
  `.tools/profile-0.4.2-qa-verification.json`, `.tools/release-0.4.2-package.txt`,
  `.tools/remote-photos-0.4.2-relay-publication.txt` and
  `.tools/release-verification-0.4.2/verified-artifact.json`
  (`livePushVerified:true`, `liveRemotePhotosVerified:true`).
- One isolated-libsignal-session regression passed, proving stable pinned identity,
  independent chat/photo ratchets and replay rejection. Five focused relay tests
  passed in 24.04 seconds for roles, owner consent, packet retries/revocation,
  independent connections, device changes, prekey substitution and expiry.
- Six Android QA tests passed in 84.497 seconds on the isolated Android 12 device.
  They cover 37 synthetic MediaStore photos across pages, exact original bytes,
  thumbnails before originals, owner transfer after chat/vault closure, immutable
  notification End access, admin-only menu, denial without gallery access, remote
  disconnect cleanup, and sign-out/screen-off/Android-timeout cleanup callbacks.
  TLS mock-server trust is test-only; production TLS validation is unchanged.
- A runtime check exposed TLS pool teardown on the Android main thread. Teardown
  now cancels from a background thread and clears session state on its serialized
  worker; the full six-test focused rerun passed. Initial fixture cleanup/compile
  issues were repaired without relaxing security assertions.
- Full JVM verification passed 63 executed tests with one opt-in LiveRelayTest
  skipped, and the relay artifact still excludes client cryptography. Android
  unit tests, QA/release lint and the R8-optimized release build passed. All 97
  ScreenFlow QA methods passed across seven batches. The final notification
  dismissal action was then verified by three focused checks at 320dp and 130%
  text in 65.616 seconds; the final release build/lint was rerun successfully.
- Full QA found a previously hidden send-error row below the scroll viewport.
  Layout-aware following now keeps new/expanded pending rows visible without
  taking composer focus or moving an intentionally scrolled-up conversation.
  The send/retry pair passed in 15.216 seconds and the full suite then passed.
  Optional read-only photo polling has its own worker, short timeout and backoff,
  so it cannot occupy the serialized chat worker. A teardown test now waits for
  service shutdown before asserting erased keys; its focused rerun passed in
  50.770 seconds and the affected full batches were rerun.
- Synthetic secure screenshots verify the grid, full photo and single approval
  sheet at normal and narrow sizes. Notification dismissal uses the same immutable
  End access action. Original bytes are preserved across transfer; the viewer's
  64 MiB original/4096-pixel display bounds remain explicitly documented, not an
  unlimited-resolution claim.
- No physical-phone/OEM or Android 14+ partial-access/foreground-service runtime
  acceptance is claimed. The signed live gate and public verification above
  supplement these earlier local/mock-TLS checks; neither is an independent audit.
- Evidence: `.tools/remote-photos-security-focused.txt`,
  `.tools/remote-photos-background-relay.txt`, `.tools/remote-photos-all-focused.txt`
  and `.tools/remote-photos-lifecycle-focused.txt`,
  `.tools/remote-photos-jvm-verify.txt`, `.tools/remote-photos-unit-tests.txt`,
  `.tools/profile-0.4.2-qa-verification.json`, `.tools/remote-photos-narrow-qa.txt`
  and `.tools/remote-photos-release-build.txt`. No immutable 0.4.1 distribution
  files or existing hosted user accounts were changed for this feature.

## Account types (relay only, 2026-09-24)

- Migration V5 adds constrained `accounts.user_type` metadata, default `USER`,
  permitted values `USER` and `ADMIN`. `GET /account/type` reads only the current
  authenticated enrolled account's type from PostgreSQL. No client role-write
  endpoint or new chat permission is introduced.
- Six focused account-type/rename checks passed in 29.20 seconds, including a
  real V4-to-V5 migration with existing password/Google accounts and an idempotent
  rerun. Account IDs, handles and authentication mappings were preserved. Defaults,
  constraint rejection, enrollment/anonymous denial, self-only visibility, client
  self-promotion rejection, rename retention and unchanged inbox isolation passed.
- The full JVM gate initially identified the existing exact account-schema
  allowlist needing the new column. That assertion was updated, retaining its
  exact-column check and adding the ordinary Google-account role expectation.
  Five focused checks then passed in 19.82 seconds; the full rerun passed 57
  executed tests with one separately opt-in LiveRelayTest skipped. The relay
  artifact still excludes client-only cryptography.
- Deployment was restricted to the existing `vanishr-dev-rg` relay. Trusted TLS
  and health passed, temporary transfer storage was removed, and a live anonymous
  request to `/account/type` returned 401 with `Cache-Control: no-store`.
- The explicitly requested account was inspected before assignment, then promoted
  in a bounded transaction guarded by its immutable UUID and expected current
  handle. Exactly one row was required; only `user_type` was changed. A post-commit
  read confirmed `ADMIN`. Account identifiers are kept in local operational
  evidence, not this public document. Passwords, device keys and messages were
  neither read nor changed by the assignment.
- PostgreSQL database `vanishr`, table `public.accounts`, was confirmed on the
  approved VM using the `vanishr_accounts` volume at `/var/lib/postgresql/data`.
  Username rename history is not stored; code and tests establish ID continuity
  for renames, not independent historical ownership of a former handle.
- Evidence: `.tools/account-type-upgrade-focused.txt`,
  `.tools/account-type-schema-focused.txt`, `.tools/account-type-jvm-verify.txt`,
  `.tools/account-type-relay-publication.txt`,
  `.tools/account-type-hosted-inspection.txt`,
  `.tools/account-type-hosted-promotion.txt` and
  `.tools/account-type-hosted-verification.json`.
- Android, client cryptography and the immutable published 0.4.1 distribution
  were unchanged; their device/live workflow tests were not rerun for this
  server-only metadata change. Special admin chat features remain unspecified
  and unimplemented; role assignment does not override expiry, verification,
  group ownership or encrypted-content access boundaries.

## Release 0.4.1

- Published on 2026-09-23. Version 0.4.1/code 22 adds offline contact Last
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
- After the user renewed Vercel authentication, all 27 audited files were
  hash-verified in the browser and uploaded to the existing Vanishr Hobby/static
  project. Production deployment `dpl_Hqub7AdhiuEVUaNgToSGoUw7gxnF` is READY at
  `https://vanishr-download.vercel.app`, with zero functions or builds. No domain,
  paid-plan, analytics configuration or account-data changes were made.
- Anonymous verification passed all 26 public file hashes/sizes, the original
  APK signing identity, no-store code-22 feed and security headers. Four crawlable
  pages, seven canonical redirects and nine private/missing-path 404s passed.
  The Android download page renders without horizontal overflow at 320, 390 and
  1280 pixels, links to the correct APK/source and discloses bounded last seen.
  No Google Analytics requests were observed before consent in that check.
- The audited `.tools/vercel-download-0.4.1` remains immutable. Publication-status
  edits to README and this document intentionally postdate the source archive;
  neither the source ZIP nor the signed APK was repackaged after verification.
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
  `.tools/website-build-0.4.1.json`. Publication evidence:
  `.tools/public-release-verification-0.4.1.json` and
  `.tools/public-website-verification-0.4.1.json`.
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

## 0.4.9 (published)
- Audited stage: `.tools/vercel-download-0.4.9`
- APK: vanishr-0.4.9.apk
- APK SHA-256: `69176ba9b775aeb814871e21e80ca6312a981a9c6981f1ff4e0a9dc8287019d7`
- Signer certificate SHA-256: `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`
- Default baked relay: `https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com`
- Version: versionName 0.4.9 / versionCode 30

Deployment (Vercel, vanishr/vanishr-download):
- Deployment id: `dpl_7vcJq8dtwd52Y7pvxf3fXGnwsEDq`
- Production alias: https://vanishr-download.vercel.app
- Published: 2026-10-03T13:23+05:30

Anonymous verification (performed against public domain):
- `https://vanishr-download.vercel.app/updates.json` returns versionName 0.4.9, versionCode 30, matching SHA-256.
- Public APK download SHA-256 matches `69176ba9b775aeb814871e21e80ca6312a981a9c6981f1ff4e0a9dc8287019d7`.
- apksigner on the public APK confirms signer certificate SHA-256 `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c` (CN=Vanishr Local Release).

## 0.4.8 (published)
- Audited stage: `.tools/vercel-download-0.4.8`
- APK: vanishr-0.4.8.apk
- APK SHA-256: `7897efc9026d6ea3259d487e7d2516e603e4dfce9ec2d1717fd1c9c53b7baef4`
- Signer certificate SHA-256: `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c`
- Default baked relay: `https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com`
- Version: versionName 0.4.8 / versionCode 29

Deployment (Vercel, vanishr/vanishr-download):
- Deployment id: `dpl_H3aqEeHqndqMbhVc4jGv9c7suwZa`
- Production alias: https://vanishr-download.vercel.app
- Published: 2026-10-03T12:30+05:30

Anonymous verification (performed against public domain):
- `https://vanishr-download.vercel.app/updates.json` returns versionName 0.4.8, versionCode 29, matching SHA-256.
- Public APK download SHA-256 matches `7897efc9026d6ea3259d487e7d2516e603e4dfce9ec2d1717fd1c9c53b7baef4`.
- apksigner on the public APK confirms signer certificate SHA-256 `c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c` (CN=Vanishr Local Release).


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