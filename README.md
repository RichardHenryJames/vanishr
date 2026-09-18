# Vanishr

Android-first, one-to-one disappearing chat. The signed client encrypts text and
images before upload; a Java 21 / Spring Boot relay handles only ciphertext and
minimal delivery metadata. **Development foundation, not an audited production
messenger. Do not use it for real sensitive conversations yet.**

## Hosted Development Build

Download the signed Android 0.2.7 APK, matching application/libsignal source and
license notices at **https://vanishr-download.vercel.app**.

The APK has this tested cloud relay prefilled:
`https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com`.
It works independently of the developer's computer. Android 9 or later and a
device screen lock are required. Public TLS uses a normally trusted certificate;
no manual CA installation is needed for this hosted build.

The hosted candidate includes the profile, username contact lookup and
account-switching flows below, with Google/FCM client configuration. Provider
configuration is deployed on the dev/test relay. Google sign-in is restricted to
approved test accounts; live FCM delivery and broader provider/account-switch
acceptance remain unverified.

Version 0.2.6 removes the separate app lock while retaining phone-lock protection.
Install this signed update over an older release once, without uninstalling or
clearing app data, to receive future daily update prompts. My profile also has
Check for updates. Updates open a browser download and require Android's install
approval; executable code is not silently replaced.

Version 0.2.7 adds immediate send feedback and compact inline profile saves.
Sending clears the composer immediately and leaves later drafts untouched;
network delivery still takes time. My profile keeps both editors open with
inline saving/error states. The public APK, matching source and update feed
were verified anonymously on 2026-09-18.

This is development/test hosting under the Visual Studio Azure benefit, not
a production-service launch or an independently reviewed security release.

## Included

- Signup/login, one active device per account, explicit device replacement.
- Profile avatar, owner-editable shared display name and unique username.
- Contact profile with an editable local display name and read-only username.
- Non-destructive sign-out and isolated account switching without Android's Clear data step.
- Username contact lookup followed by independent safety-number verification.
- Official Signal libsignal sessions with mandatory independent peer verification.
- Text and encrypted image messages; per-image keys remain inside Signal envelopes.
- View once, one hour, six hours or 24 hours; delivery/read status and hard Redis TTLs.
- Foreground HTTPS polling / WSS, bounded offline ciphertext queues, optional generic FCM.
- Phone-unlocked Android Keystore vault without a separate app lock, per-message content keys, screenshot flags,
  no-backup storage and background expiry cleanup.
- Automatic update prompts and a manual update check; Android-approved signed APK installation.
- Real Redis/PostgreSQL integration tests and Android security instrumentation.

No groups, calls, reactions, stories, channels, search, browser client or permanent
message/media history. Images are bounded to 2 MiB and stored transiently in Redis,
not persistent object storage. Java 21 is required by the pinned libsignal release.

## Local Setup

Requirements: JDK 21 in `JAVA_HOME`, Maven 3.9+, PowerShell 7.4+, Docker Desktop
with Linux containers, and Android SDK platform 36 / build tools 36. Set
`ANDROID_HOME` if the SDK is not at the standard Windows location. The build
script downloads Gradle 8.13 only after checking its pinned SHA-256.

From the workspace root:

```powershell
mvn -B -ntp '-Dmaven.repo.local=.tools/m2' verify
.\scripts\build-android.ps1 -Tasks :app:assembleDebug,:app:lintDebug
.\scripts\start-local.ps1
```

The start script generates random credentials and a local CA in ignored
`.secrets`, creates seven-day TLS certificates, builds the relay, and waits for
healthy services. The CA private key is discarded after issuance. It binds only
loopback, choosing the next free port if 8443 is occupied. It does not install a
trusted CA, change system certificate settings or disable TLS verification.

Default relay: `https://localhost:8443/health`.
Android emulator origin: `https://10.0.2.2:8443` (use the printed port).
This is an API, not a web chat page. Browser access to the generated local CA
will warn until that public certificate is explicitly trusted.

Install [the debug APK](android/app/build/outputs/apk/debug/app-debug.apk) on a
test emulator/device with a screen lock. For local development only, install
the public `.secrets/ca.crt` as a **user CA on the test device** using Android
Settings > Security > Encryption & credentials > Install a certificate > CA
certificate. Debug builds accept explicitly installed user CAs; release builds
trust system CAs only. Never add a trust-all client or use certificate bypasses.
Local TLS certificates include localhost, 127.0.0.1 and the emulator's 10.0.2.2.
Real devices require a reachable HTTPS hostname and a matching trusted certificate.

Unlock the phone normally, open Vanishr, create an account, and repeat
on a second device/account. Tap your avatar on the Chats screen to open My profile
and edit your shared display name or unique username. Only your authenticated
account can change either field.
Usernames use 3-32 lowercase letters, numbers or underscores; duplicate names are
rejected even under simultaneous claims. The old username becomes available again,
but existing contacts and encrypted messages remain attached to your stable account
ID and keys, not to the reusable username. Use the new username for password login.

The profile display name is separate, need not be unique, and is visible through
authenticated account lookup. Older local profile names are not uploaded until
you explicitly choose Save name. In a conversation, the three-dot menu's Profile
opens the other person's Contact profile, not your own account. Their username is
read-only. Its Display name field edits only the name you see for that contact;
the change is encrypted locally and cannot change their shared profile, username,
or what they call you. Use profile name restores their shared name. The separate
contact-name menu action has been removed, with existing saved names preserved.
Profile and username changes refresh while online; they are not identity proofs
and do not replace independent verification.
Add a contact by username, then compare their safety number with their
Profile > Verify identity over an independent trusted channel. Lookup alone
does not establish trust. The app rechecks the identity before pinning it.
The input expiry selector controls the next message. Opening view-once content
consumes its local content key before display. Long-press removes local/relay
content; it cannot erase content already retained by the other device.

Backgrounding clears visible content and memory; returning on an unlocked phone
opens the retained account automatically. Profile > Sign out, or Use another account
on a returning login screen, revokes the current session and stores its chats,
contacts, profile and Signal state in a separate encrypted account partition.
Signing in again restores that account only after server authentication, using
the same device and identity keys. Other accounts cannot read its messages or
send its pending outbox. Already-queued relay messages remain deliverable until
their original deadlines; unsent local messages retry when their account returns.
Read acknowledgements are attempted before session revocation and retained for
retry if offline. Google sign-in state and notifications are reset on sign-out.
The registered device is not deleted, and no message lifetime is extended.
Offline logout cannot prove immediate server token revocation; normal token TTLs
still apply. This is not remote account deletion or recovery of expired content.

Version 0.2.2 erased local keys on sign-out. Version 0.2.3 corrects that behavior,
but cannot recover already-erased keys or decrypt ciphertext addressed to them.
Those older accounts may require one explicit device replacement and renewed
contact verification. Do not clear app data or uninstall to perform this update.

Stop local services without deleting account metadata:

```powershell
docker compose --env-file .secrets/local.env -f infra/compose.yml down
```

Redis content and sessions disappear on restart. The named PostgreSQL volume
holds only account/device/public-key metadata. Keep `.secrets/local.env` while
reusing that volume: regenerating a database password does not change an existing
PostgreSQL account password. Local certificate renewal currently requires
operator-managed reissuance preserving the database credentials; the seven-day
fixture is not an automatic production certificate-management system.

## Verification

VS Code task: `Vanishr: Verify Security Foundations`. It runs the same JVM,
packaged-relay dependency-boundary, Android build and lint checks below.

```powershell
mvn -B -ntp '-Dmaven.repo.local=.tools/m2' verify
.\scripts\build-android.ps1 -Tasks :crypto:test,:app:assembleDebug,:app:lintDebug,:app:assembleDebugAndroidTest
.\scripts\test-android.ps1 -Serial emulator-5580
```

For the credential-dependent Keystore test, use a newly created emulator named
`Vanishr_Security_Test`, then run:

```powershell
.\scripts\test-android.ps1 -Serial emulator-5580 -ProvisionTestCredential
```

That option verifies the dedicated emulator's name, **erases only its Vanishr
test-app data**, provisions a temporary random device credential, runs the
Keystore test, then removes the credential and test data. Never use that emulator
for real content. Without this option, the positive Keystore test skips if the
device is not unlocked; the unsecured-device rejection test skips
on a device with a screen lock. A skipped test is not a demonstrated guarantee.

To start the dedicated emulator created in this workspace setup:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Vanishr_Security_Test -port 5580 -no-snapshot
```

Release compilation is available via `scripts/verify.ps1 -Android -Release`.
That verification command does not require signing credentials. To create an
installable release APK with a private local signing key:

```powershell
.\scripts\package-apk.ps1 -InitializeSigningKey
```

Subsequent builds use `.\scripts\package-apk.ps1` and reuse the same key. The
script restricts the signing directory to the current Windows user, keeps
passwords out of command arguments, and verifies the APK signature and size.
The [signed APK](android/app/build/outputs/apk/release/app-release.apk) is a
development build, not a security-reviewed product. Packaging does not create a
backend; the `-RelayOrigin` option verifies and prefills an existing HTTPS relay.
It cannot update an installed debug-signed build because their signers differ.
Uninstalling the debug app erases its local data; do not do so on a real account
without understanding the device-replacement consequences.

Keep `.secrets/android-signing` private and back it up securely offline. Losing
that key prevents compatible app updates. Never upload that directory to Vercel,
GitHub, a download site, or a cloud build. Review the release and AGPL licensing
requirements before public distribution. Debug signing is for local testing only.

### Updating An Installed App

UI, profile, authentication and native-code changes require a signed APK update.
Install the new APK over the existing release; do not uninstall or clear data.
Matching package ID and signing certificate preserve local state during an update.
Sign out in 0.2.3 also preserves encrypted accounts. Uninstalling the app or using
Android's Clear data still removes local keys and can make old messages unreadable.

Starting with 0.2.6, Vanishr checks the fixed HTTPS download feed at most once a
day while foregrounded. My profile > Check for updates also checks manually.
A newer compatible release offers Download or Later. Download opens the browser;
Android still asks before installation and verifies the package signer. An app
older than 0.2.6 needs this first APK installation before it can show prompts.
The feed must be published alongside its APK; a missing/offline feed does not
block chats. No account data or authentication tokens are sent to the update host.

Version 0.2.6 removes the separate Unlock Vanishr screen and the five-minute key
authentication window. It relies on the phone being unlocked, while keeping
encrypted storage and background memory clearing. Existing encrypted records
migrate without resetting message deadlines, consuming view-once content, or
replacing identities. Older keys may need one normal phone PIN/pattern/password
unlock before migration. If a key is missing or invalidated, the app preserves
the ciphertext and reports the error; reinstalling cannot recover that key.

Relay-side changes can be deployed without an APK when the client API stays
compatible. Native UI and encryption changes still require signed packages;
reopening the app twice cannot install them silently. This app never downloads
scripts or DEX to replace running UI or encryption logic.

## Cloud Hosting And Cost

The user approved an **INR 1,200/month planning budget from existing Azure
credits** for an isolated development/test deployment. Only the new
`vanishr-dev-rg` was changed. No existing resource group, subscription spending
limit, shared database, App Service plan or existing app was modified.

Current Central India baseline, using 730 hours/month and public retail prices
checked on 2026-09-16:

| Item | Monthly estimate (INR) |
| --- | ---: |
| One Standard_B2pts_v2 ARM VM, 1 GiB RAM | 390.62 |
| One 32 GiB Standard HDD OS disk | 161.43 |
| One Standard public IPv4 address | 348.72 |
| Fixed baseline | **900.77** |

Disk operations and outbound traffic are additional metered usage. Temporary
private artifact storage was created only for deployment and deleted afterward;
that small one-time usage may appear in billing. There is no managed Redis,
managed PostgreSQL, ACR, paid monitoring, paid domain or autoscaling service.
Redis, PostgreSQL and the relay run inside the same VM with memory caps. The
HTTPS proxy uses an automatically renewed public certificate and verified TLS
to the relay; the database ports are not public. Host swap is disabled.

The portal reported INR 12,084.08 of credit remaining at preflight. That is a
point-in-time report, not a reserved allocation. The subscription still has
**Spending Limit: On**. Existing workloads share the same credit and quotas:
creating a separate resource group does **not** guarantee protection from
subscription-wide credit exhaustion. The user approved this residual risk.
The `vanishr-monthly` resource-group budget sends 50%, 80% and 100% alerts to
the account owner. **Azure budgets do not stop spending** and billing data can
lag. Keep the subscription limit on; never enable paid Marketplace products,
support plans or upgrades to bypass it. Visual Studio credits are for dev/test.

The Vercel `vanishr-download` project is static-only on the Vanishr **Hobby**
team. The plan was verified active with no payment method added; no upgrades,
analytics, functions, Blob store or other integrations were enabled. Its
91.8 MB upload contains only the APK, source archives, notices and static page.
Hobby quotas can pause access. It is not unlimited or a backend-hosting service.

### Operations

The infrastructure template and deployment script are restricted to
`vanishr-dev-rg`; the script refuses an existing group without its local
ownership record and rejects what-if changes outside the group or deletions.
Local SSH keys, deployment state and release signing material are in ignored
`.secrets`. Runtime secrets are generated only on the VM under
`/opt/vanishr/.secrets`. Publication uses protected Azure managed-command
parameters and short-lived private artifact URLs because direct SSH was blocked
from the development network. Never upload that private directory to Vercel.

```powershell
.\scripts\deploy-azure.ps1 -Action Status
.\scripts\publish-azure.ps1 -Action Check
```

After rebuilding and testing a relay change, publish only the allowlisted
artifact with `.\scripts\publish-azure.ps1 -Action Publish`.
Do not redeploy the VM merely to publish application code. Internal certificates
expire one year after provisioning and require planned renewal; their CA
private key is discarded. The public certificate renews through Caddy.

To pause **only this VM's compute**:

```powershell
az vm deallocate --subscription 44027c71-593a-4d51-977b-ab0604cb76eb --resource-group vanishr-dev-rg --name vanishr-dev
```

The disk and public IP remain billable while the VM is deallocated (about INR
510/month together). `az vm start` with the same subscription/group/name
resumes it; queued messages and authentication sessions were memory-only and
are lost. To stop every Vanishr Azure charge, the owner must explicitly delete
only `vanishr-dev-rg`, which also destroys its account metadata and deployment.
Do not remove or modify other groups in a cleanup script.

Build the signed hosted APK with the existing signing identity:

```powershell
.\scripts\package-apk.ps1 -RelayOrigin 'https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com'
```

The source release was authorized under AGPLv3. Read [NOTICE.md](NOTICE.md)
and the full [LICENSE](LICENSE). Dependency notices are bundled in the app;
the download site also provides the source and upstream libsignal archive.
Source availability is not an independent legal or dependency-security review.

See [test evidence and release gates](docs/VERIFICATION.md). The Maven tests use
real disposable Redis/PostgreSQL containers but MockMvc for controller calls;
`LiveRelayTest` separately tested real public TLS/hostname/CA handling and
encrypted text/maximum-size blob delivery and read deletion on the Azure VM.

## Push Configuration

Google sign-in and push owner setup, release certificate fingerprints and live
acceptance checks are documented in [docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md).
The implementation is present but neither provider is enabled by an empty or
placeholder configuration. A signed release build does not turn a dev/test
subscription into a production entitlement.

FCM is off by default. Enable it only with your own Firebase project and scoped
application-default/service-account credentials outside source control. Set
`FCM_ENABLED=true`, `FCM_PROJECT_ID`, and `GOOGLE_APPLICATION_CREDENTIALS` for a
direct relay process. For Compose, add a local uncommitted override mounting the
credential file read-only and setting that variable; the base stack deliberately
does not mount cloud credentials. Do not paste service-account JSON into chat.

Before building Android, set `FIREBASE_APP_ID`, `FIREBASE_API_KEY`,
`FIREBASE_PROJECT_ID`, and `FIREBASE_SENDER_ID` from the Firebase Android app.
These are client configuration identifiers, not server credentials; restrict
the Firebase API key appropriately. Analytics is not included. Notification
opt-in is explicit; only a generic `new_message` event crosses FCM. Display text
is always "New message", with no sender, image, key, token or message content.
Push delivery has not been validated against a live Firebase project here.

## Security Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Threat model](docs/THREAT-MODEL.md)
- [API and minimal schema](docs/API.md)
- [Encryption and key management](docs/ENCRYPTION.md)
- [Verification and limitations](docs/VERIFICATION.md)

libsignal is AGPLv3 and third-party use is unsupported: obtain a licensing and
security review before distributing. A malicious server can retain ciphertext,
deny service, collect metadata and forge receipt status. A malicious recipient
can copy content or photograph a display. Endpoint compromise is out of scope.
TTL and cryptographic erasure are not claims of forensic physical destruction.