#requires -Version 7.4
<#
Certifies the device identity of the official admin account (see docs/DEPLOYMENT-GUIDE.md, "Changing the admin's phone").

The app pins the admin's identity (AdminOnboarding.OFFICIAL). When the owner signs in as the admin on another phone, the relay holds a new
identity and every phone that only knows the old pin refuses it ("Official admin identity changed"). This tool signs a short statement of
the new identity with the admin authority key (scripts\new-ota-key.ps1 -Purpose Admin, kept under .secrets\admin-authority) and writes it
to download\ota\admin-identity.json. A website release publishes it; a phone that sees an admin identity it does not know asks for that file,
checks the signature against the public key it embeds, and switches. It changes nothing else: the account and the relay stay what the app
was built with.

Signing (no switch):
  1. The new phone has signed in as the admin. Its identity is read from the relay (a read-only query on the relay's VM, through the same
     approved Azure group as scripts\publish-azure.ps1), or given with -DeviceId and -IdentityKey (read both from the relay yourself: the
     phone's number covers the identity key, not the device ID, so a device ID typed by hand cannot be checked, and the tool says so).
  2. The tool asks for the safety number of the NEW phone (it does not show the relay's number first, so that it is the phone that is read and
     not this screen). Open My profile > Verify identity on that phone and type the number it shows (-PhoneSafetyNumber, or when prompted):
     all of it, or just the first and the last group. A different number means the relay does not hold that phone's identity, and nothing is
     signed.
  3. The sequence number is the next one after the committed file's. The new file is written next to the old one atomically and read back.

-Check   verifies the committed certificate against the public keys the app embeds and against the app's rules, without the private key,
         and compares it with this build of the app (AdminIdentity.BUILD_SEQUENCE_FLOOR and the built-in pin).
-Live    (with -Check) also compares it with what the website serves, for use BEFORE a release: what is served is reported on, but a
         certificate that a trusted key signed and that is newer than the committed one fails, because this checkout is behind.
-RequireIdentical (implies -Check -Live) is for AFTER a release: the website must serve exactly the committed file, the way the app
         expects it. Anything else fails.
-AppRelease (implies -Check) is for cutting an app version: the build's floor must equal the committed certificate's sequence and the
         built-in pin must be that certificate's identity, or the phones that update would not agree with the certificate they fetch.
-Sequence chooses the sequence number instead of the next one. Only for recovery, and it has to be above the committed certificate's: a lower
         or equal one is refused, because phones that have taken the committed one would refuse it.
#>
[CmdletBinding()]
param(
    [string]$DeviceId,
    [string]$IdentityKey,
    [string]$PhoneSafetyNumber,
    [string]$Output,
    [ValidatePattern('\A[A-Za-z0-9_-]{1,16}\z')][string]$KeyId,
    [ValidateRange(1, 1000000)][long]$Sequence,
    [string]$Origin = 'https://vanishr-download.vercel.app',
    [switch]$Check,
    [switch]$Live,
    [switch]$RequireIdentical,
    [switch]$AppRelease
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'admin-identity-rules.ps1')
if ($RequireIdentical) { $Check = [switch]$true; $Live = [switch]$true }
if ($AppRelease) { $Check = [switch]$true }
if ($Live -and -not $Check) { throw '-Live belongs with -Check.' }
if (-not $Output) { $Output = Join-Path $root 'download\ota\admin-identity.json' }
$keysFile = Join-Path $root 'android\app\src\main\res\raw\admin_keys.txt'
$secrets = Join-Path $root '.secrets\admin-authority'
if ($Origin -cnotmatch '\Ahttps://[A-Za-z0-9.-]+\z' -and $Origin -cnotmatch '\Ahttp://(?:127\.0\.0\.1|localhost):[0-9]{2,5}\z') { throw 'The origin must be an https address (or a local one for tests).' }

if (-not (Test-Path -LiteralPath $keysFile)) { throw 'No admin authority key is listed; run scripts\new-ota-key.ps1 -Purpose Admin first.' }
$trusted = Read-OtaKeys $keysFile
if ($trusted.Count -eq 0) { throw 'No admin authority key is listed; run scripts\new-ota-key.ps1 -Purpose Admin first.' }
$build = Get-AdminBuild (Join-Path $root 'android\app\src\main\java\app\vanishr\android\AdminIdentity.java') (Join-Path $root 'android\app\src\main\java\app\vanishr\android\AdminOnboarding.java')

function Read-Committed {
    if (-not (Test-Path -LiteralPath $Output)) { return $null }
    return (Read-AdminEnvelope (Get-Content -LiteralPath $Output -Raw) $trusted $build.UserId)
}

function Show-Certificate($Read) {
    $payload = $Read.Payload
    Write-Output ("Certified by '{0}': sequence {1}, device {2}, safety number {3}." -f $Read.Key, $payload.sequence, $payload.deviceId, (Format-AdminSafetyNumber $payload.fingerprint))
}

# ---- check ----
if ($Check) {
    $committed = Read-Committed
    if (-not $committed) {
        if ($AppRelease -and $build.Floor -ne 0) { throw "This build of the app knows certificate $($build.Floor) (AdminIdentity.BUILD_SEQUENCE_FLOOR) but none is committed at $Output. Restore it, or the phones that update could not follow the next change." }
        Write-Output "No admin identity certificate is committed: the identity built into the app applies (floor $($build.Floor))."
        if ($RequireIdentical) { throw 'Nothing was released: the website would have to serve the committed file.' }
        return
    }
    Show-Certificate $committed
    $payload = $committed.Payload
    $same = $payload.deviceId -ceq $build.DeviceId -and $payload.fingerprint -ceq $build.Fingerprint
    if ($payload.sequence -lt $build.Floor) { throw "The committed certificate (sequence $($payload.sequence)) is older than the one this build of the app already knows (floor $($build.Floor)): this checkout has lost a newer file." }
    if ($AppRelease) {
        if ($payload.sequence -ne $build.Floor) { throw "The committed certificate is sequence $($payload.sequence) but the app's AdminIdentity.BUILD_SEQUENCE_FLOOR is $($build.Floor). Make this version of the app carry that certificate: set the floor to $($payload.sequence) and the built-in pin (AdminOnboarding.OFFICIAL) to the device and fingerprint it names." }
        if (-not $same) { throw 'The app''s built-in pin (AdminOnboarding.OFFICIAL) is not the identity the committed certificate names. Set its device ID and fingerprint to the certificate''s.' }
    } elseif ($payload.sequence -eq $build.Floor -and -not $same) {
        throw 'The committed certificate has the same sequence as the app''s floor but names another identity than the built-in pin: no phone would ever use it.'
    } elseif ($payload.sequence -gt $build.Floor) {
        Write-Output "It is newer than this build of the app knows (floor $($build.Floor)): phones of this build pick it up when they see the new identity. The next app version has to carry it (-Check -AppRelease says so)."
    }
    if ($Live) {
        $served = $null
        try {
            $response = Invoke-WebRequest -Uri "$Origin/ota/admin-identity.json" -Headers @{ 'Cache-Control' = 'no-cache' } -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 30
            $served = [pscustomobject]@{ Status = [int]$response.StatusCode; Headers = $response.Headers; Text = [string]$response.Content }
        } catch {
            if ($RequireIdentical) { throw "The website could not be reached: $($_.Exception.Message)" }
            Write-Warning "The website could not be reached ($($_.Exception.Message)); nothing was compared."
        }
        if ($served) {
            $result = Test-AdminLive (Get-Content -LiteralPath $Output -Raw) $served.Status $served.Headers $served.Text $trusted $build.UserId -RequireIdentical:$RequireIdentical
            if ($result.Warn) { Write-Warning $result.Text } else { Write-Output $result.Text }
        }
    }
    return
}

# ---- sign ----
# The admin device as the relay holds it: one read-only query on the relay's VM, in the approved group only. It prints nothing but the admin's
# own public row (account, device, public identity key).
function Read-AdminDeviceFromRelay {
    $private = Join-Path $root '.secrets\azure'
    $endpoint = Get-Content -LiteralPath (Join-Path $private 'endpoint.json') -Raw | ConvertFrom-Json
    $state = Get-Content -LiteralPath (Join-Path $private 'deployment.json') -Raw | ConvertFrom-Json
    if ($state.subscription -ne '44027c71-593a-4d51-977b-ab0604cb76eb' -or $state.resourceGroup -ne 'vanishr-dev-rg' -or $endpoint.resourceGroup -ne $state.resourceGroup) {
        throw 'Deployment scope does not match the approved isolated group.'
    }
    $query = "docker exec vanishr-postgres-1 psql -U vanishr -d vanishr -At -F '|' -c ""SELECT d.user_id, d.id, d.identity_key FROM devices d JOIN admin_identity p ON p.user_id = d.user_id"""
    $scriptFile = Join-Path ([IO.Path]::GetTempPath()) ("vanishr-admin-read-" + [guid]::NewGuid().ToString('N') + '.sh')
    try {
        [IO.File]::WriteAllText($scriptFile, $query + "`n", [Text.UTF8Encoding]::new($false))
        $raw = & az vm run-command invoke --subscription $state.subscription --resource-group $state.resourceGroup --name $endpoint.vmName --command-id RunShellScript --scripts "@$scriptFile" --only-show-errors --output json 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $raw) { throw 'The relay could not be queried (is the Azure CLI signed in?). Give -DeviceId and -IdentityKey instead.' }
        $message = [string]((($raw -join "`n") | ConvertFrom-Json).value[0].message)
    } finally { Remove-Item -LiteralPath $scriptFile -Force -ErrorAction SilentlyContinue }
    $rows = @(($message -split "`r?`n") | Where-Object { $_ -cmatch '\A[0-9a-f-]{36}\|[0-9a-f-]{36}\|[A-Za-z0-9+/=]{44}\z' })
    if ($rows.Count -ne 1) { throw "The relay returned $($rows.Count) admin devices instead of one; nothing was signed." }
    $parts = $rows[0] -split '\|'
    return [pscustomobject]@{ UserId = $parts[0]; DeviceId = $parts[1]; IdentityKey = $parts[2] }
}

if ([bool]$DeviceId -ne [bool]$IdentityKey) { throw 'Give both -DeviceId and -IdentityKey, or neither (they are then read from the relay).' }
if ($DeviceId) {
    $device = [pscustomobject]@{ UserId = $build.UserId; DeviceId = $DeviceId; IdentityKey = $IdentityKey }
    Write-Warning 'The device ID was given by hand. The phone''s safety number covers the account and the identity key only, not the device ID, so nothing here can tell whether the ID is the one the relay holds for the admin. A wrong ID is signed as given and every phone would refuse the admin until a corrected certificate is published. Leave -DeviceId and -IdentityKey out to read both from the relay.'
} else {
    Write-Output 'Reading the admin device from the relay (read-only)...'
    $device = Read-AdminDeviceFromRelay
}
if ($device.UserId -cne $build.UserId) { throw 'The relay''s admin account is not the one this app pins; nothing was signed.' }
if ($device.DeviceId -cnotmatch $script:AdminUuidPattern) { throw 'The device ID must be a lower-case UUID.' }
$fingerprint = Get-AdminFingerprint $device.UserId $device.IdentityKey

Write-Output 'Now the NEW phone: its safety number is asked for before the relay''s is shown, so that it is the phone that is read and not this screen.'
if (-not $PhoneSafetyNumber) { $PhoneSafetyNumber = Read-Host 'Type the safety number the NEW phone shows (My profile > Verify identity); all of it, or the first and the last group' }
Confirm-AdminPhoneNumber $fingerprint $PhoneSafetyNumber
Write-Output 'The number matches the identity the relay holds for the admin account:'
Write-Output "  device        $($device.DeviceId)"
Write-Output "  safety number $(Format-AdminSafetyNumber $fingerprint)"

$committed = Read-Committed
$baseline = if ($committed) { [long]$committed.Payload.sequence } else { 0L }
if ($committed -and $committed.Payload.deviceId -ceq $device.DeviceId -and $committed.Payload.fingerprint -ceq $fingerprint -and -not $Sequence) {
    throw "That identity is already certified (sequence $baseline); nothing was signed."
}
$next = if ($Sequence) { $Sequence } else { $baseline + 1 }
if ($next -gt $script:AdminMaxSequence) { throw "The next sequence number would pass $($script:AdminMaxSequence)." }
if ($committed -and $next -le $baseline) {
    throw "Sequence $next is not above the committed certificate's ($baseline). A phone that has taken $baseline would refuse it, and a phone that has not would be handed an older certificate than the one that is published. Choose a number above $baseline."
}
if ($next -lt $build.Floor) { throw "The next sequence number ($next) is below the one this build of the app already knows (floor $($build.Floor)); the committed file has lost a newer certificate. Restore it, or choose a higher number with -Sequence." }
if ($next -eq $build.Floor -and ($device.DeviceId -cne $build.DeviceId -or $fingerprint -cne $build.Fingerprint)) {
    throw "Sequence $next is the one this build of the app already stands for, and it names another identity than the built-in pin: no phone of this build would use it. Choose a higher number with -Sequence."
}
$payloadText = New-AdminPayloadText $next $device.UserId $device.DeviceId $fingerprint
$payloadBytes = [Text.Encoding]::UTF8.GetBytes($payloadText)
Assert-AdminPayload (ConvertFrom-OtaJson $payloadText) $build.UserId

if (-not $KeyId) {
    $withKeys = @($trusted.Keys | Where-Object { Test-Path -LiteralPath (Join-Path $secrets "admin-$_.pem") })
    if ($withKeys.Count -ne 1) { throw 'Pass -KeyId: it is not clear which listed key has its private half on this computer.' }
    $KeyId = $withKeys[0]
}
if (-not $trusted.ContainsKey($KeyId)) { throw 'That key ID is not in the list of public keys the app embeds (key IDs are case-sensitive).' }
$signer = [System.Security.Cryptography.ECDsa]::Create()
try {
    $signer.ImportFromEncryptedPem((Get-Content -LiteralPath (Join-Path $secrets "admin-$KeyId.pem") -Raw), (Get-Content -LiteralPath (Join-Path $secrets "admin-$KeyId.password.txt") -Raw).Trim())
    if (-not [Linq.Enumerable]::SequenceEqual([byte[]]$signer.ExportSubjectPublicKeyInfo(), [byte[]]$trusted[$KeyId])) { throw 'The private key does not match the public key the app embeds.' }
    $signature = $signer.SignData([byte[]]($script:AdminDomain + $payloadBytes), $script:OtaSha256, $script:OtaDer)
} finally { $signer.Dispose() }
$envelope = '{"v":1,"key":"' + $KeyId + '","payload":"' + (ConvertTo-Base64Url $payloadBytes) + '","sig":"' + (ConvertTo-Base64Url $signature) + '"}'
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null
$temporaryOutput = "$Output.new"
[IO.File]::WriteAllText($temporaryOutput, $envelope, [Text.UTF8Encoding]::new($false))
try { $null = Read-AdminEnvelope (Get-Content -LiteralPath $temporaryOutput -Raw) $trusted $build.UserId }
catch { Remove-Item -LiteralPath $temporaryOutput -Force; throw }
Move-Item -LiteralPath $temporaryOutput -Destination $Output -Force
Show-Certificate (Read-AdminEnvelope (Get-Content -LiteralPath $Output -Raw) $trusted $build.UserId)
Write-Output "Wrote $Output. Publish it with a website release (docs/DEPLOYMENT-GUIDE.md, ""Changing the admin's phone""), then check what is live with -Check -Live -RequireIdentical."
