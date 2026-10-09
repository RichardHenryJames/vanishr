# Manual app and website deployment guide

These are operator instructions, not a claim that a deployment has completed.
Use PowerShell **7.4 or later**. Run command blocks in the same terminal from
the repository root and stop on any failed check.

**Building an APK, changing a version in Git, pushing to GitHub, deploying the
relay, and publishing the Vercel download site are separate operations.**
The app discovers a release only after the public update feed advertises it.

## 1. Where the Terms page and app popup are implemented

Public Terms URL: <https://vanishr-download.vercel.app/terms/>

| Surface | Source to edit | What it controls |
| --- | --- | --- |
| Full browser Terms page | [download/terms/index.html](../download/terms/index.html) | Terms, community standards, reporting and child-safety text. |
| Website Privacy page | [download/privacy/index.html](../download/privacy/index.html) | Full app and website privacy information. |
| Website deletion page | [download/delete-account/index.html](../download/delete-account/index.html) | Public account-deletion request instructions. |
| Website appearance | [download/site.css](../download/site.css) | Shared website styling, not the native app UI. |
| In-app summary and policy URLs | [PlayPolicy.java](../android/app/src/main/java/app/vanishr/android/PlayPolicy.java) | `SUMMARY`, `TERMS_URL`, `PRIVACY_URL`, and the separate policy `VERSION`. |
| Native popup and acceptance screen | [MainActivity.java](../android/app/src/main/java/app/vanishr/android/MainActivity.java) | `policyDetails()` builds the summary/links; `ensurePolicies()` shows "Before using Vanishr"; `policyScreen()` shows the required agreement screen. |
| Saved acceptance | [AccountSafety.java](../android/app/src/main/java/app/vanishr/android/AccountSafety.java) | `termsAccepted()` / `acceptTerms()` store the accepted policy version in the account's protected vault. |

The app's **Terms & safety** action opens the public Terms URL in a browser.
It does not fetch that HTML to populate the native popup.

- Changing only the full web page needs a website release, not an APK rebuild.
- Changing the native summary, checkbox, buttons or screen needs a new APK.
- If revised Terms require renewed acceptance, change the policy `VERSION` in
  [PlayPolicy.java](../android/app/src/main/java/app/vanishr/android/PlayPolicy.java)
  as well as the relevant text, and ship a new app version. Existing acceptance
  then no longer matches in the updated app. A website edit alone does not
  trigger renewed acceptance in an already-installed app.
- Keep the website and native disclosure consistent with actual behavior.
  Do not equate Terms acceptance with separate photo-access consent.

Edit the source templates, not generated copies under `.tools`.
[render-website.ps1](../scripts/render-website.ps1) generates the final pages,
release labels, links and Content Security Policy hashes.

## 2. How users learn about a new version

The direct-download APK reads:
<https://vanishr-download.vercel.app/updates.json>

[AppUpdates.java](../android/app/src/main/java/app/vanishr/android/AppUpdates.java)
uses a fixed HTTPS origin, strict metadata validation, a higher `versionCode`
than the installed app, and a compatible Android minimum version.
[MainActivity.java](../android/app/src/main/java/app/vanishr/android/MainActivity.java)
wires the check and the **Update available / Download / Later** prompt.

- Automatic checks happen at most once per 24 hours while the app is foregrounded.
  This is not a push notification to every phone at deployment time.
- **My profile > Check for updates** checks manually without that daily delay.
- Download opens the browser. The user must approve Android installation;
  updates are not silently installed. Install over the existing release without
  uninstalling or clearing data.
- New users use <https://vanishr-download.vercel.app/android/>.
- Play builds use **Open Google Play**, not this external APK updater.
  Publishing to Vercel does not update a Play listing or roll out a Play AAB.

If the public feed still says `0.4.7`, direct-download users cannot discover
`0.4.8`, even if the newer binary exists on your computer or its code is on GitHub.
Do not change only the website label or only the feed: publish the matching
signed APK, feed, source archive, notices and pages together.

## 3. Prerequisites and release variables

Use the existing Java 21/Maven, Android SDK platform/build-tools 36, Gradle
build script, Node.js and Vercel CLI setup. `JAVA_HOME` and `ANDROID_HOME` must
be correct. JVM integration tests need the configured local container runtime.
See [local setup](../README.md#local-setup) and
[verification](VERIFICATION.md) for device-test prerequisites.

Keep the existing private signing material available locally. **Do not use
`-InitializeSigningKey` for an update or replace the release signing key.**
Never upload signing material, credentials, private server configuration,
the repository root or a raw checkout to Vercel.

```powershell
Set-Location 'C:\Users\parimalkumar\Desktop\Projects\vanishr'
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$Root = (Get-Location).Path
$Origin = 'https://vanishr-download.vercel.app'
$RelayOrigin = 'https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com'
$Vercel = Join-Path $Root '.tools\vercel-cli\node_modules\.bin\vercel.cmd'
$Version = '0.4.8'
$VersionCode = 29
```

`0.4.8/code 29` is an **example**, not a release made by these instructions.
Choose a code above every previously distributed or Play-uploaded code.
The current source at the time of writing is `0.4.8/code 28`.

**To publish the already-built 0.4.8 instead:** set `$Version = '0.4.8'` and
`$VersionCode = 29`, use `.tools\vercel-download-0.4.8`, and start at section 5.
Re-audit it; do not rebuild or silently replace that version's signed artifacts.
Review its recorded [release limitations](VERIFICATION.md#release-047--bilateral-administrator-onboarding)
first, including the absent Google/Firebase client configuration.

## 4. Prepare a genuinely new app version

### 4.1 Change source and version metadata

1. Implement the intended changes, with relevant tests. Read the
   [threat model](THREAT-MODEL.md) before security-sensitive changes.
2. In [android/app/build.gradle](../android/app/build.gradle), set the new
   `versionName` and strictly increasing `versionCode`. Do not change
   `applicationId` or the signing identity.
3. Update release notes and verification/Play documentation without marking
   unperformed tests, publication or Play approval as complete.
4. Keep the default versions in [prepare-download.ps1](../scripts/prepare-download.ps1)
   and [prepare-website.ps1](../scripts/prepare-website.ps1) aligned, or always
   pass the explicit `-Version` as shown here.

### 4.2 Verify and, only if needed, update the relay first

```powershell
.\scripts\verify.ps1 -Android
.\scripts\build-android.ps1 -Tasks @(':app:testDebugUnitTest', ':app:exportDependencyInventory')
```

Also run the existing device/regression tests covering your change. The commands
above compile Android instrumentation tests but **do not execute them**.
Use isolated test accounts/emulators, never reset a real user's app data.

If the app needs new relay APIs or schema migrations, deploy the verified,
compatible backend before advertising the app:

```powershell
.\scripts\publish-azure.ps1 -Action Publish
Invoke-RestMethod -Uri "$RelayOrigin/health" -MaximumRedirection 0 -TimeoutSec 30
```

Use this only with the existing approved Azure deployment configuration, after
verification has produced the current relay JAR. Confirm health is `up` and
check the migration/API behavior; health alone is not functional verification.
Skip this step for app-only or website-only changes. Azure changes remain
restricted to `vanishr-dev-rg` in the approved subscription; do not resize
resources, enable paid add-ons or alter the spending limit.

The opt-in encrypted contacts backup needs relay schema V13 (`account_backups`,
no existing row is rewritten). Publish that relay before any app version that
offers backup; against an older relay the backup requests fail and no backup is
created. The relay only stores an
opaque blob (at most 512 KiB per account, 90-day retention), so no new Azure
resource, VM size change or paid add-on is involved; the table adds at most
512 KiB per opted-in account to the existing PostgreSQL volume.

### 4.3 Generate notices, then build the final signed direct APK

```powershell
.\scripts\prepare-download.ps1 -PrepareNoticesOnly -Version $Version
.\scripts\package-apk.ps1 -RelayOrigin $RelayOrigin
```

The notice step requires the dependency inventory exported above and the
existing verified libsignal notice/source downloads in `.tools`. On a fresh
machine, restore those public dependencies from the documented upstream sources;
do not bypass missing-notice checks.

Signed packaging requires a validated **Android client** configuration by default.
Pass `-GoogleServicesFile` to every APK/AAB invocation, or keep exactly one
`google-services.json` in the repository root or `.secrets\firebase\`.
Missing or ambiguous configuration stops the build. Follow
[GOOGLE-SETUP.md](GOOGLE-SETUP.md); never substitute a server service-account JSON.
The explicit `-NoGoogleServices` opt-out creates an intentionally unconfigured
build and must not be used for releases that need to retain Google account
access. Run `.\scripts\test-google-config.ps1` when changing these packaging rules.

[package-apk.ps1](../scripts/package-apk.ps1) checks relay HTTPS health, reuses
the existing signer, builds the release, runs release lint and verifies the
signature. The direct artifact is
[app-release.apk](../android/app/build/outputs/apk/release/app-release.apk).

### 4.4 Assemble the static app release

```powershell
.\scripts\prepare-download.ps1 -Version $Version
$Stage = Join-Path $Root ".tools\vercel-download-$Version"
```

This generates the signed APK copy, matching source ZIP, license/notices,
the update feed and all website pages. The feed gets its code, name, size and
SHA-256 from the release metadata and APK, rather than a hand-written number.

It intentionally refuses existing staging/source directories. For a retry of
the same immutable release, use and verify the existing stage. If preparation
failed partway, inspect and move only its two named generated directories aside
before retrying. Never delete `.tools` or the repository, and never replace an
already-published APK under the same version name.

### 4.5 Optional: build the separate Google Play bundle

After freezing the direct-download stage:

```powershell
.\scripts\package-apk.ps1 -RelayOrigin $RelayOrigin -Bundle -PlayStore
$PlayDirectory = Join-Path $Root ".tools\play-store-$Version"
New-Item -ItemType Directory -Path $PlayDirectory | Out-Null
Copy-Item -LiteralPath '.\android\app\build\outputs\bundle\release\app-release.aab' `
    -Destination "$PlayDirectory\vanishr-$Version.aab"
```

Use the same valid Google client configuration here if required. The separate
copy avoids losing the versioned AAB to a later build. An existing destination
is a reason to inspect it, not overwrite a released bundle.

Validate with the checksum-verified official bundletool and follow
[PLAY-STORE.md](PLAY-STORE.md) before uploading to a Play Console testing track.
Unresolved policy, consent and account-holder gates still apply. A signed AAB
is not approval. Do not put the AAB in the Vercel stage; users download the APK.
Do not regenerate the frozen direct stage from a Play build's intermediates.

## 5. Audit the exact upload folder

For a new app release, set:

```powershell
$Stage = (Resolve-Path (Join-Path $Root ".tools\vercel-download-$Version")).Path
Get-ChildItem -LiteralPath $Stage -Recurse -Force -File
$LocalFeed = Get-Content -LiteralPath "$Stage\updates.json" -Raw | ConvertFrom-Json
$Apk = Get-Item -LiteralPath "$Stage\vanishr-$Version.apk"
$ApkHash = (Get-FileHash -LiteralPath $Apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($LocalFeed.versionName -ne $Version -or $LocalFeed.versionCode -ne $VersionCode -or
    $LocalFeed.apkUrl -ne "$Origin/vanishr-$Version.apk" -or
    $LocalFeed.size -ne $Apk.Length -or $LocalFeed.sha256 -ne $ApkHash) {
    throw 'Staged APK and update feed do not match the intended release.'
}
```

Before uploading:

- Inspect **all** staged files, including hidden files, and the entries/content
  of the source ZIP. Check against the explicit distribution source allowlist in
  [prepare-download.ps1](../scripts/prepare-download.ps1).
- Exclude private files: `.secrets`, `.env` files, keys/keystores, tokens,
  Google/server credentials, private deployment state and repository history.
  A filename scan alone is not a complete secret/content audit.
- Preserve corresponding source and third-party notices; review the generated
  dependency-source unavailability list and licensing obligations.
- Verify the original APK signer as in section 7 before upload as well.
- Confirm the stage is below the script's **99,000,000-byte** static limit.
- Inspect the generated `vercel.json`: static-only, no build command/framework,
  correct APK headers and `Cache-Control: no-store` for the update feed.
- Save a file inventory with byte lengths and SHA-256 hashes outside the stage.
  Do not modify audited release artifacts after recording their hashes.

For later website-only builds,
[prepare-website.ps1](../scripts/prepare-website.ps1) expects two local evidence
files. The preparation/publication scripts do **not** automatically create both:

| Evidence file | Required fields used by the website script |
| --- | --- |
| `.tools\distribution-audit-<version>.json` | `version`, `secretsExcluded`, and `files` entries containing `name`, `bytes`, `sha256`. Include every staged release artifact. |
| `.tools\public-release-verification-<version>.json` | `version`, `apkSha256`, `signingIdentityMatches`. Also record the real deployment ID, time and verification results. |

Create these records only from completed inspection and public verification.
See the existing local 0.4.7 records for their full structure. Do not copy an old
version's hashes or set success flags just to bypass the website guard.
Missing evidence means the publication/audit must be verified first.

For a **first app-release audit**, after the inspection above, this records the
actual staged file hashes. If a matching audit already exists, preserve and
check it instead. Website-only releases keep the original app audit and use
the separate inventory generated by the website script.

```powershell
$AuditPath = Join-Path $Root ".tools\distribution-audit-$Version.json"
if (Test-Path -LiteralPath $AuditPath) { throw 'Inspect the existing audit; do not overwrite it.' }
$Files = @(Get-ChildItem -LiteralPath $Stage -Recurse -Force -File | Where-Object {
    $_.FullName -notmatch '[\\/]\.vercel[\\/]' -and
    $_.Name -notin @('.gitignore', '.vercelignore')
})
$Bytes = ($Files | Measure-Object Length -Sum).Sum
if ($Bytes -ge 99000000) { throw 'The static bundle exceeds the approved size limit.' }
$Inventory = @($Files | ForEach-Object {
    [ordered]@{
        name = [IO.Path]::GetRelativePath($Stage, $_.FullName).Replace('\', '/')
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
if ((Read-Host 'Type AUDITED only after inspecting stage and ZIP contents for private data') -cne 'AUDITED') {
    throw 'Content audit was not confirmed.'
}
[ordered]@{
    version = $Version
    versionCode = $VersionCode
    auditedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    staticBytes = $Bytes
    secretsExcluded = $true
    files = $Inventory
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $AuditPath -Encoding utf8
```

The explicit confirmation records an operator review, not an automated promise
that filename checks can detect every secret. CLI link/ignore files are excluded
from the public inventory, but still belong in the inspection.

## 6. Manually publish to Vercel production

This section also publishes a website-only stage from section 8. Use its
`$Stage` without switching back to the app-release folder.

Use the existing local CLI at `$Vercel`. Only if it is missing, install the
project's previously used CLI version outside the upload folder:

```powershell
npm install --prefix .\.tools\vercel-cli vercel@60.0.0
```

Authenticate the CLI once; being logged into the Vercel website alone does not
necessarily authenticate the terminal:

```powershell
& $Vercel login
& $Vercel whoami
```

Complete the browser login yourself. Never paste a token into source, a guide,
a command transcript or a public upload.

Confirm the existing **Vanishr Hobby team**, slug `vanishr`, and existing project
`vanishr-download`. Do not create a replacement project or select a personal or
unrelated team.

```powershell
& $Vercel link --yes --project vanishr-download --scope vanishr --cwd $Stage
$Link = Get-Content -LiteralPath "$Stage\.vercel\project.json" -Raw | ConvertFrom-Json
if ($Link.projectId -ne 'prj_IfkyrkIw1wZuxbjAryv3nj9bYcW3' -or
    $Link.orgId -ne 'team_vRp561kumEFCgFL9GP2x7gW1') {
    throw 'The stage is not linked to the approved Vanishr project/team.'
}
if (@(Get-ChildItem -LiteralPath $Stage -Recurse -Force -File |
        Where-Object { $_.Name -like '.env*' }).Count -gt 0) {
    throw 'Linking added an environment file. Remove only the generated stage file and re-audit before uploading.'
}
$LocalFeed = Get-Content -LiteralPath "$Stage\updates.json" -Raw | ConvertFrom-Json
$Published = Invoke-RestMethod -Uri "$Origin/updates.json" `
    -Headers @{ 'Cache-Control' = 'no-cache' } -MaximumRedirection 0 -TimeoutSec 30
if ($Published.versionCode -gt $LocalFeed.versionCode -or
    ($Published.versionCode -eq $LocalFeed.versionCode -and
        ($Published.versionName -ne $LocalFeed.versionName -or
         $Published.sha256 -ne $LocalFeed.sha256 -or $Published.size -ne $LocalFeed.size))) {
    throw 'Refusing an app downgrade or different APK under an existing version code.'
}
& $Vercel deploy --prod --yes --scope vanishr --cwd $Stage
```

These team/project IDs identify the existing project; they are not credentials.
If the check fails, stop and inspect project ownership rather than replacing
the expected values to make an unrelated deployment pass.
CLI-created `.vercel` link metadata is not a public website file; do not add
credentials to it or include it in the source archive.

Some CLI versions download a credential-bearing `.env.local` while linking.
Being Git-ignored does not make that file safe for a static upload. When reusing
an existing release's link, copy **only** `.vercel\project.json` into the new
stage and rerun the project/team ID checks above; never copy its environment
files. Recheck the complete stage inventory after linking.

**The `--cwd $Stage` and `--prod` arguments matter.** Do not run an unscoped
deployment from the repository root, upload the raw `download` templates, deploy
only the APK, or stop after a preview deployment.

Wait for the deployment to be **Ready**, and confirm its production domain is
`vanishr-download.vercel.app`. Record the deployment URL and ID from the CLI or
dashboard. A random deployment URL is not proof that the app's fixed domain
serves that release.

If deployment only updates `vanishr-download-vanishr.vercel.app`, explicitly
move the existing canonical alias to the **new** Ready deployment URL printed
by the CLI:

```powershell
# Set this to the new deployment's URL, not an older release or the canonical alias.
$DeploymentUrl = 'https://<new-deployment>.vercel.app'
& $Vercel alias set $DeploymentUrl vanishr-download.vercel.app --scope vanishr --cwd $Stage
if ($LASTEXITCODE -ne 0) { throw 'The public update alias was not moved.' }
```

Allow a short time for alias propagation, then perform section 7 against the
exact canonical URL. Do not change the app's fixed update host or disable
project-wide deployment protection to work around a stale alias.

If the CLI hangs or you interrupt it, inspect the project's Deployments page
before retrying; a server-side deployment may already exist. Never mark a
stalled attempt as published. A dashboard **Redeploy** of the old 0.4.7
deployment reuses its old files; it does not upload your local 0.4.8 folder.

## 7. Verify the actual public release, without login

Do not stop at "Ready". In the same terminal, fetch the fixed production origin:

```powershell
$Response = Invoke-WebRequest -Uri "$Origin/updates.json" `
    -Headers @{ 'Cache-Control' = 'no-cache' } -MaximumRedirection 0 -TimeoutSec 30
$PublicFeed = $Response.Content | ConvertFrom-Json
if (($Response.Headers['Content-Type'] -join ',') -notmatch '^application/json\b' -or
    ($Response.Headers['Cache-Control'] -join ',') -notmatch '\bno-store\b' -or
    $PublicFeed.versionName -ne $Version -or $PublicFeed.versionCode -ne $VersionCode -or
    $PublicFeed.apkUrl -ne "$Origin/vanishr-$Version.apk" -or
    $PublicFeed.sha256 -ne $LocalFeed.sha256 -or $PublicFeed.size -ne $LocalFeed.size) {
    throw 'The production update feed is not the intended release.'
}
$PublicDirectory = Join-Path $Root ".tools\public-download-$Version"
New-Item -ItemType Directory -Path $PublicDirectory -Force | Out-Null
$DownloadedApk = Join-Path $PublicDirectory "vanishr-$Version.apk"
Invoke-WebRequest -Uri $PublicFeed.apkUrl -OutFile $DownloadedApk `
    -MaximumRedirection 0 -TimeoutSec 180
$PublicHash = (Get-FileHash -LiteralPath $DownloadedApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($PublicHash -ne $LocalFeed.sha256 -or
    (Get-Item -LiteralPath $DownloadedApk).Length -ne $LocalFeed.size) {
    throw 'Public APK bytes differ from the audited release.'
}
$Signer = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
$Signature = & $Signer verify --verbose --print-certs $DownloadedApk
$ExpectedSigner = 'c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c'
if (($Signature -join "`n") -notmatch
    ('Signer #1 certificate SHA-256 digest: ' + [regex]::Escape($ExpectedSigner))) {
    throw 'The public APK does not have the original release certificate.'
}
$Signature
```

Compare **Signer #1 certificate SHA-256 digest** against the established
Vanishr release certificate:

```text
c78586ebe29b1faaf3e828a3928366eb71c560461793ed856608e0eddbc5924c
```

Do the same signer check on the staged APK before uploading. A valid signature
from a *different* key is not a compatible update. Do not set
`signingIdentityMatches` until this comparison passes.

Also verify:

1. Home, `/android/`, `/security/`, `/privacy/`, `/terms/` and `/delete-account/`
   load anonymously over HTTPS on desktop and mobile. Check links, rendering,
   support email, headers, consent behavior and canonical redirects.
2. Download links point to the intended version. Source archives and notices
   are available and match the audited local hashes.
3. Private/missing paths such as `/.secrets/`, `/.git/config`,
   `/.vercel/project.json` and a nonexistent page return 404, not private files.
4. On an older direct-download app, use **My profile > Check for updates**.
   Confirm the new version, then test installation over the old app on an
   isolated device without losing its local state. An app already at the new
   code should correctly report that it is up to date.
5. Save the real audit/public-verification JSON described in section 5 and
   update [VERIFICATION.md](VERIFICATION.md) with measured results and limitations.
   Preserve the immutable stage. Remove only temporary downloaded verification
   copies when they are no longer needed, not release artifacts or evidence.

For a **first app publication**, record the measured result after all those
checks. Keep an existing record rather than overwriting it for a website-only
release. The deployment ID must be copied from the real successful deployment.

```powershell
$VerificationPath = Join-Path $Root ".tools\public-release-verification-$Version.json"
if (Test-Path -LiteralPath $VerificationPath) { throw 'Preserve the existing release verification.' }
$DeploymentId = Read-Host 'Paste the successful Vercel deployment ID (dpl_...)'
if ($DeploymentId -cnotmatch '^dpl_[A-Za-z0-9]+$') { throw 'A real deployment ID is required.' }
if ((Read-Host 'Type VERIFIED only after the public page, source and device checks pass') -cne 'VERIFIED') {
    throw 'Public release verification was not confirmed.'
}
[ordered]@{
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    origin = $Origin
    deploymentId = $DeploymentId
    version = $Version
    versionCode = $VersionCode
    apkSha256 = $PublicHash
    apkBytes = (Get-Item -LiteralPath $DownloadedApk).Length
    signingIdentityMatches = $true
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $VerificationPath -Encoding utf8
```

Only now call the direct-download release **published and verified**.
Stage and commit the intended source/documentation changes and push them to
GitHub separately; never commit private files or build outputs. A Git push is
not a substitute for the Vercel upload above.

## 8. Website-only release: Terms, styling or page content

Use this when the APK is unchanged. Do not increase the Android version, build
an APK/AAB, redeploy Azure or advertise a new app update just for a web edit.

1. Edit the relevant source files under [download](../download/).
2. Read the production update feed. Use the **currently published and locally
   verified** app version, not a newer unpublished build:

   ```powershell
   $Current = Invoke-RestMethod -Uri "$Origin/updates.json" -MaximumRedirection 0 -TimeoutSec 30
   $Version = [string]$Current.versionName
   $VersionCode = [int]$Current.versionCode
   .\scripts\prepare-website.ps1 -Version $Version
   $Stage = (Resolve-Path (Join-Path $Root ".tools\vercel-website-$Version")).Path
   $LocalFeed = Get-Content -LiteralPath "$Stage\updates.json" -Raw | ConvertFrom-Json
   ```

3. The script requires the matching local app-release stage and both real
   evidence files described in section 5. It hash-checks and copies the
   unchanged APK, feed, source and notices, then renders the revised website.
   It writes `.tools\website-build-<version>.json`; review its inventory.
   Do not fabricate missing evidence, bypass the guard, or deploy a newer
   unreleased app as part of a website edit.
4. For a revised website stage, the supported switch is
   `.\scripts\prepare-website.ps1 -Version $Version -Refresh`.
   It is not a cleanup command: unexpected files left by a previous CLI link
   can fail the exact-file check. Inspect/move the specific old generated
   website stage aside and prepare a fresh one rather than removing safety
   checks or deleting broad directories.
5. Audit the website stage as in section 5, keeping its website `$Stage`.
   Publish it with section 6's link/deploy commands and verify with section 7.
   The public APK hash, version code and feed must remain unchanged.
6. Record website-specific verification separately; keep the original app
   release audit. Existing users can open the revised full Terms page, but
   their native popup text/acceptance version is unchanged until an app update.

## 9. Quick troubleshooting

| Symptom | Check |
| --- | --- |
| App still sees 0.4.7 after building 0.4.8 | Fetch the production `updates.json`. Publish the audited 0.4.8 stage, not just Git changes or the relay. |
| A preview URL has the new version but production does not | Verify team/project, `--prod`, deployment Ready state and production domain assignment; then recheck the fixed origin. |
| Feed is new but no automatic popup appears immediately | Automatic checks are daily and prompts can be deferred. Use the manual check while foregrounded; verify installed code, Android compatibility and direct-vs-Play build. |
| App says it cannot check right now | Verify HTTPS, HTTP 200 without redirects, JSON content type, the strict feed shape and network access. Do not disable TLS checks. |
| APK will not update an existing installation | Verify package ID, increasing code and the original certificate. Do not uninstall/clear real data to hide a signer mismatch. |
| Website preparation says verified release evidence is missing | Complete the genuine stage audit and anonymous publication checks, or restore their existing records. Do not invent successful evidence. |
| Website text changed but native popup did not | Edit the native policy source and release a new signed app; web HTML is not the popup's text source. |
| Vercel succeeded but Google Play is unchanged | Upload/review/roll out the Play AAB separately, subject to [Play readiness gates](PLAY-STORE.md). |
