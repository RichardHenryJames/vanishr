# Google sign-in and push setup

Google sign-in and push require matching client and server configuration. Do not
interpret a token-validation or notification-construction test as a live Google
account login or delivered FCM push.

## Current dev/test setup

On 2026-09-17, project `vanishr-b7616` was configured on the no-cost Spark plan.
The release Android package and signing certificate match the saved client
configuration. OAuth consent remains in Testing with explicitly allowed users.
The dedicated `vanishr-push-sender` service account has
`roles/firebasecloudmessaging.admin` on this project, not Owner or Editor.

The downloaded server credential was validated and relocated from the workspace
root to `.secrets/firebase/fcm-sender.json`, with current-Windows-user-only access.
Its root copy was removed. Neither this file nor the default downloaded key
filename belongs in source control, APKs, Docker images or the Vercel upload.

The existing `vanishr-dev-rg` relay now has the matching provider configuration.
Its credential is owned by relay UID/GID 10001, mode 0400, and mounted read-only.
Runtime access and healthy services were verified; the subscription spending
limit remains On and temporary artifact storage was removed.

Official Google authentication plus an FCM `validate_only` request succeeded;
that request did not deliver a notification. Live relay checks also verified the
matching OAuth audience, five-minute challenge deadline, invalid-token rejection
and consumed-challenge replay rejection. Actual Google account sign-in and
background device notification delivery remain unverified.

On 2026-09-17, the signed 0.2.1 APK was tested on `Vanishr_Release_Test`
with real Android input and device-credential authentication. Tapping
`Continue with Google` launched Google Play services' `MinuteMaidActivity`,
but the provider became unresponsive (ANR). After restarting the provider,
Android Settings' independent Add Google Account flow also returned
`Something went wrong` before account entry. The emulator had no Google
accounts, its clock differed from the host by one second, and Android reported
a validated network with airplane mode off. This is a blocked provider test,
not a successful Google login or proof that the full OAuth configuration works.
The temporary PIN and unsigned-in synthetic Vanishr fixture were removed, and
the original screen timeout was restored. No Google account data was cleared.

In 0.2.3, Profile > Sign out and Use another account retain the account's encrypted
records and device keys in an isolated local partition, not an authenticated
session. A later verified Google response restores only the matching account,
origin and provider. The relay resumes an identical device/key without deleting
prekeys or changing its registration; genuine replacements still need consent.
The Credential Manager session is cleared before another Google attempt. Concurrent
reset requests share one bounded operation, and a persisted pending-clear flag
forces a retry if it fails or times out. Account/provider markers are saved
together. Pending enrollment retries without claiming an unregistered device ID.

Sign-out and session checks no longer consume the sign-in-attempt quota. Actual
authentication attempts and Google challenge requests each remain capped at ten
per source IP per minute. The app reports throttling and registration conflicts
separately instead of using an unrelated pending-ciphertext error. The owner has
reported a successful Google login; the automated two-account retention test uses
password accounts and the shared post-authentication code, not real Google tokens.
Actual Google login/logout/login acceptance still needs a working provider/device.

The prior 0.2.2 sign-out erased local keys. The update cannot recover those keys;
an affected account may still require explicit replacement and fresh verification.

In 0.2.4, usernames are editable by the account owner. Google login resolves the
same stable subject/account UUID and returns the current username. Encrypted
account restoration no longer treats an old saved username as an identity mismatch;
origin, provider and authenticated UUID still must match. Shared display names
are explicitly edited, never imported from Google. Private contact names remain
local to the naming account and do not affect either user's profile.

## Owner steps

1. Open https://console.firebase.google.com/ in your own browser and select or
   create a dedicated Vanishr project. Keep the no-cost Spark plan; do not enable
   billing, paid databases, analytics or other products for this setup. FCM does
   not require storing any chat content in Firebase.
2. Add an Android app with package `app.vanishr.android`. Register the release
   signing certificate fingerprints below. Do not register the `.qa` package as
   the release app. If Play App Signing is used later, also register the Play
   app-signing certificate, not just the upload certificate.
3. In Google Cloud's Google Auth Platform for the same project, configure the
   consent screen, support contact and required public app/privacy information.
   Create an Android OAuth client for the package and SHA-1, and a Web OAuth
   client used as the server audience. Use only sign-in scopes; Vanishr does not
   need Drive, Contacts or Gmail access. Add explicit test users while the
   consent screen remains in Testing. A public launch may require Google's
   brand/consent verification.
4. Download the Android app's `google-services.json` directly into a private
   local folder such as `.secrets/firebase/`. This project uses its values as
   build-time configuration rather than the Google Services Gradle plugin.
   Never paste OAuth client secrets, ID/access tokens or service-account keys
   into chat. Google Web/Android client IDs and project IDs are public identifiers.
5. Confirm the Firebase Cloud Messaging HTTP v1 API is enabled. Create a
   dedicated sending service account with only the required FCM send permissions,
   such as Firebase Cloud Messaging API Admin on this project. Do not use Owner
   or Editor merely to send notifications. Workload identity federation is
   preferable where configured; otherwise download the service-account JSON
   privately and keep it outside the repository and APK. No credential has been
   embedded in the application or distribution artifacts.
6. Tell the assistant only the project ID, Web OAuth client ID, Android OAuth
   client ID and local configuration-file paths. Keep private file contents out
   of chat. Configuration, secure server transfer and live tests can then continue.

Release signing certificate SHA-1:

```text
1B:FC:F7:7F:9A:E8:C1:4A:53:FC:D7:9C:EE:E0:E9:D2:A8:F1:C0:CA
```

Release signing certificate SHA-256:

```text
C7:85:86:EB:E2:9B:1F:AA:F3:E8:28:A3:92:83:66:EB:71:C5:60:46:17:93:ED:85:66:08:E0:ED:DB:C5:92:4C
```

These are public certificate hashes, not private signing keys. Preserve the
existing private app-signing identity for compatible updates.

## Configuration mapping

| Location | Setting | Source |
| --- | --- | --- |
| Android build and relay | `GOOGLE_WEB_CLIENT_ID` | Web OAuth client ID ending in `.apps.googleusercontent.com` |
| Relay | `GOOGLE_ANDROID_CLIENT_IDS` | Comma-separated allowed Android OAuth client IDs |
| Android build | `FIREBASE_APP_ID` | Android `mobilesdk_app_id` |
| Android build | `FIREBASE_API_KEY` | Android client configuration API key; apply appropriate API/package/certificate restrictions |
| Android build | `FIREBASE_PROJECT_ID` | Firebase project ID |
| Android build | `FIREBASE_SENDER_ID` | Project number / messaging sender ID |
| Relay | `FCM_PROJECT_ID` | Same target Firebase project ID |
| Relay | `GOOGLE_APPLICATION_CREDENTIALS` | Read-only private credential mount or configured workload-federation credentials |
| Relay | `FCM_ENABLED` | `true` only after sender credentials are provisioned |

Firebase client configuration is not a substitute for server credentials.
Vanishing text/image content and libsignal private keys never belong in either
configuration. Do not give the Android app a service-account credential.

The Docker deployment requires explicit environment forwarding and a read-only
credential mount when FCM is enabled. The shipped base Compose stack does not
mount cloud credentials. Do not add credentials to a Docker image, cloud-init,
the Vercel upload, a command argument, or a public environment example.

The optional `infra/compose.google.yml` overlay forwards the required settings
and mounts `FCM_CREDENTIAL_FILE` read-only at `/credentials/fcm.json`. It refuses
missing environment values and will not create a missing host credential path.
Host startup enables this overlay only when both the private Google environment
file and credential exist with the required ownership and permissions. An
incomplete setup is rejected rather than silently disabling the integration.

`scripts/read-google-config.ps1` validates the release package, registered
certificate, project number and OAuth clients. Packaging uses only client-side
values and restores its environment even on failure. Publication validates the
dedicated server credential and passes it through Azure protected command input,
not the application archive or command arguments. Temporary local request and
remote staging files are removed after use. Publication remains restricted to
the existing approved dev/test resource group.

```powershell
$endpoint = Get-Content .\.secrets\azure\endpoint.json -Raw | ConvertFrom-Json
$origin = "https://$($endpoint.hostname)"
.\scripts\package-apk.ps1 -GoogleServicesFile .\google-services.json -RelayOrigin $origin -WithInstrumentation
.\scripts\publish-azure.ps1 -Action Publish -GoogleServicesFile .\google-services.json -FcmCredentialFile .\.secrets\firebase\fcm-sender.json
```

The publication command restarts the relay. Run JVM security verification before
publication and public TLS/encrypted-delivery checks afterward. A successful
deployment or signed build does not satisfy the live acceptance checks below.

## Live acceptance checks

- Rebuild the signed release with matching client configuration, preserving its
  signing certificate. Test the actual release package on a Google Play-enabled
  emulator and a physical device with a recent screen-lock authentication.
- Sign in with the owner-approved Google test account. Verify cancellation,
  wrong-account selection, expired/replayed challenge rejection and returning
  login. Google login authenticates the account; independently verified Signal
  peer identities still govern chat encryption.
- Enable notifications and grant Android notification permission. Send an
  encrypted message from another account while the recipient app is backgrounded.
  Verify that the visible notification contains only `Vanishr` / `New message`,
  never a sender, text, image or key, and opens the locked application.
- Disable notifications and verify no notification is displayed and the relay
  registration is removed. Check token replacement, sign-out, device replacement,
  provider rejection and expiry. Doze, force-stop and OEM restrictions can delay
  or prevent delivery; do not promise instant push under those conditions.

## Production hosting gate

A signed `release` APK is not the same as a production-approved service. The
current Visual Studio/MSDN subscription is approved only for dev/test use, and
the current workspace cloud allowlist is `vanishr-dev-rg`. No production group
was created, and the existing group was not deleted or renamed.

Moving to production requires an owner-approved production-eligible subscription,
updated deployment scope, a revised cost decision, security/operational review,
and a migration/cutover plan. Keep the working endpoint until its replacement
passes real TLS, account, encrypted delivery, expiry and provider tests. Deleting
the old group destroys its account database, endpoint and pending deliveries;
it cannot be undone by calling a new group `prod`.

References:
- https://developer.android.com/identity/sign-in/credential-manager-siwg
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://learn.microsoft.com/visualstudio/subscriptions/vs-azure-eligibility