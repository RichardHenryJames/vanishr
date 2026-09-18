# Verification and release gates

Evidence refreshed on Windows, 2026-09-18. This is a development foundation,
not a production security assessment or independent audit.

## Release candidate 0.2.7

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
| Signed release packaging | Original local release key reused; apksigner verifies the APK Signature Scheme v2 signature; 0.2.7 is about 42.1 MiB after excluding test-only JNI libraries. Signing does not establish security-review or public-backend readiness |
| Android native protocol | Official Signal JNI loads on API-36 x86_64 emulator; Signal encryption/decryption and Gson records/Base64 interoperate |
| Android configuration | Runtime verifies backup flag disabled, cleartext network disallowed, plaintext HTTP origin rejected, vault closed until explicitly opened by the foreground lifecycle |
| Android launch | Instrumentation launches the actual activity, verifies FLAG_SECURE and visible control hierarchy |
| Android secure storage | Dedicated emulator verifies phone-unlocked key policy, locked-phone rejection, automatic reopen after Android unlock, encrypted atomic persistence and inability to decrypt after content-key deletion |
| Local TLS deployment | All three Compose services reach healthy state with certificate verification: HTTPS relay, TLS Redis and PostgreSQL `verify-full`; no cleartext fallback |
| Low-memory deployment | Capped local stack passed real HTTPS Signal text and three 2 MiB encrypted-blob round trips/deletion. The Azure ARM VM then passed the same test without a custom CA or TLS bypass |
| Azure isolation and cost | Created only vanishr-dev-rg after a what-if preview confined to it. Fixed Standard_B2pts_v2 VM, 32 GiB Standard HDD and public IP; INR 1,200 monthly budget alert. Existing groups and subscription Spending Limit On were preserved. Temporary artifact storage was deleted |
| Public distribution | Vercel Hobby static deployment at https://vanishr-download.vercel.app. Anonymous APK download matches the signed local SHA-256; matching application/libsignal source and notices are accessible. Page checked at desktop and 320/390-pixel mobile widths |

Hosted APK 0.2.7 SHA-256:
`000d47824cb7fea6dda984cccfae5f8d03731c272aedeb053bef6d9869c1e96d`.
The endpoint is prefilled and verified healthy during the signed live workflow.
Release lint passed. The source archive was audited against private filenames
and known local secret values before upload. The ten-file static upload is
56,910,205 bytes, including the deployment configuration; nine files are public
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