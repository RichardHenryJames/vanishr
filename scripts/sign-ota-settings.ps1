#requires -Version 7.4
<#
Signs the over-the-air settings (see docs/DEPLOYMENT-GUIDE.md, "Push settings over the air").

Reads ota\settings.source.json, which holds only what the owner edits: "features", "notice" and "update". It adds the schema, the next
sequence number, the time it was issued and the time it expires, signs the result with the private key under .secrets\ota-signing and
writes the signed envelope to download\ota\settings.json. That file reaches phones through an ordinary website release. The rules the app
applies to a file are applied here first (scripts\ota-settings-rules.ps1), so a file this tool writes is a file the app accepts.

-Check   verifies the committed signed file against the public keys the app embeds and against the app's rules, without needing the
         private key, checks that this version of the app would take it (not issued before BUILD_ISSUED_FLOOR) and warns when it is about to
         expire.
-Live    does that and also fetches the file the website serves now, for use BEFORE a release: what is served now is only reported on
         (nothing there yet, expired, signed by a key the app no longer lists, headers that are not right are warnings, because the release
         replaces it), but a file that a trusted key signed and that is newer than the committed one fails: this checkout is behind, and a
         phone that took that file would refuse the committed one.
-RequireIdentical (implies -Check -Live) is for AFTER a release: the website must serve exactly the committed file, served the way the
         app expects it, and in force. Anything else fails.
-Sequence chooses the sequence number instead of the next one. Only for recovery: a phone that already holds a higher number for the same
         key ignores the file.
-Offline does not look at the website for the sequence number of the file that is live.

A file is never issued earlier than the newest one the tool knows of (the committed one, or a live one that a listed key signed) or than the
app's BUILD_ISSUED_FLOOR, because a phone ignores a file issued before one it has already taken. When this computer's clock is behind that
time by up to 36 hours (what a phone allows ahead of its own clock), the new file is dated at that time instead, with a warning. When it is
behind by more, the tool stops: either this computer's clock is wrong now (correct it) or the newer file was signed while a clock was fast
and a phone with a right clock refuses it, so it holds nothing back. -IgnoreFutureIssueTime is the owner's statement that the clock is
right now: it sets such a file aside, and the new file is dated now.
-AppRelease (with -Check) is for cutting an app version: it also requires BUILD_ISSUED_FLOOR to equal the committed file's issue time.
#>
[CmdletBinding()]
param(
    [string]$Source,
    [string]$Output,
    [ValidatePattern('\A[A-Za-z0-9_-]{1,16}\z')][string]$KeyId,
    [ValidateRange(1, 60)][int]$ValidDays = 45,
    [ValidateRange(1, 1000000)][long]$Sequence,
    [string]$Origin = 'https://vanishr-download.vercel.app',
    [switch]$Offline,
    [switch]$Check,
    [switch]$Live,
    [switch]$RequireIdentical,
    [switch]$IgnoreFutureIssueTime,
    [switch]$AppRelease
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'ota-settings-rules.ps1')
if ($RequireIdentical) { $Check = [switch]$true; $Live = [switch]$true }
if ($AppRelease) { $Check = [switch]$true }
if (-not $Source) { $Source = Join-Path $root 'ota\settings.source.json' }
if (-not $Output) { $Output = Join-Path $root 'download\ota\settings.json' }
$keysFile = Join-Path $root 'android\app\src\main\res\raw\ota_keys.txt'
$secrets = Join-Path $root '.secrets\ota-signing'
$day = $script:OtaDay
if ($Origin -cnotmatch '\Ahttps://[A-Za-z0-9.-]+\z' -and $Origin -cnotmatch '\Ahttp://(?:127\.0\.0\.1|localhost):[0-9]{2,5}\z') { throw 'The origin must be an https address (or a local one for tests).' }

$trusted = Read-OtaKeys $keysFile
if ($trusted.Count -eq 0) { throw 'No public key is listed; run scripts\new-ota-key.ps1 first.' }

# The switches the app knows, read from the app's own list so that they cannot drift.
$known = Get-OtaKnownFeatures (Join-Path $root 'android\app\src\main\java\app\vanishr\android\RemoteSettings.java')

function Show-Settings($Result) {
    $payload = $Result.Payload
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Write-Output ("Signed by '{0}', sequence {1}, issued {2:u}, expires {3:u} ({4:N1} days left), {5} bytes." -f $Result.Key, $payload.sequence,
        [DateTimeOffset]::FromUnixTimeMilliseconds($payload.issuedAt), [DateTimeOffset]::FromUnixTimeMilliseconds($payload.expiresAt),
        (($payload.expiresAt - $now) / $day), $Result.EnvelopeBytes)
    $off = @($payload.features.PSObject.Properties | Where-Object { $_.Value -eq $false } | ForEach-Object { $_.Name })
    Write-Output ("Features switched off: " + $(if ($off.Count) { $off -join ', ' } else { 'none' }))
    if ($payload.notice) { Write-Output ("Notice '{0}' ({1}): {2}" -f $payload.notice.id, $payload.notice.level, $payload.notice.title) } else { Write-Output 'No notice.' }
    if ($payload.update) { Write-Output ("Update hint: latest {0} (code {1}), minimum supported code {2}" -f $payload.update.latestVersionName, $payload.update.latestVersionCode, $payload.update.minSupportedVersionCode) }
}

function Get-LiveFile {
    try { $response = Invoke-WebRequest -Uri "$Origin/ota/settings.json" -Headers @{ 'Cache-Control' = 'no-cache' } -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 30 }
    catch { throw "Could not fetch the settings file from $Origin ($($_.Exception.GetType().Name))." }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Headers = $response.Headers; Text = [string]$response.Content }
}

$javaFile = Join-Path $root 'android\app\src\main\java\app\vanishr\android\RemoteSettings.java'
$buildFloor = Get-OtaBuildFloor $javaFile
function Format-OtaTime([long]$Milliseconds) { [DateTimeOffset]::FromUnixTimeMilliseconds($Milliseconds).ToString('u') }

if ($Check -or $Live) {
    $text = Get-Content -LiteralPath $Output -Raw
    $result = Read-OtaEnvelope $text $trusted
    Show-Settings $result
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Assert-OtaPayload $result.Payload $known $now
    $committedIssued = [long]$result.Payload.issuedAt
    if ($committedIssued -lt $buildFloor) {
        throw "The committed file was issued ($committedIssued) before the floor this version of the app holds every file to (BUILD_ISSUED_FLOOR = $buildFloor in RemoteSettings.java), so the app would refuse it. Sign it again, or correct the floor."
    }
    if ($AppRelease -and $committedIssued -ne $buildFloor) {
        throw ("BUILD_ISSUED_FLOOR in RemoteSettings.java is $buildFloor, but the committed settings file was issued at $committedIssued. When an app version is cut the floor has to be the issue time of the settings file that goes out with it, so that no older signed file can be offered to a fresh install: set BUILD_ISSUED_FLOOR = $($committedIssued)L, rebuild, and run this again." +
            $(if ($committedIssued -gt $now) { " (That time is a little ahead of this computer's clock, which is fine up to 36 hours: the next file is dated no earlier than the committed one either way.)" } else { '' }))
    }
    if ($committedIssued -gt $now) {
        Write-Warning ("The committed file is dated {0:N1} hours ahead of this computer's clock. Phones accept a file up to 36 hours ahead, so it is in force, but nothing can be issued earlier than it: until real time reaches it, the next file is dated at the same time. If this computer's clock was fast when the file was signed, there is nothing to do; if the clock is wrong now, correct it." -f (($committedIssued - $now) / 3600000.0))
    }
    Write-Output "BUILD_ISSUED_FLOOR in the app: $buildFloor. When the next app version is cut, set it to $committedIssued (the committed file's issue time) so that no older signed file can be offered to a fresh install."
    if (($result.Payload.expiresAt - $now) / $day -lt 7) { Write-Warning 'The signed settings expire in under a week; sign and publish new ones.' }
    if ($Live) {
        $served = Get-LiveFile
        $compared = Test-OtaLive $text $served.Status $served.Headers $served.Text $trusted $known $now -RequireIdentical:$RequireIdentical -IgnoreFutureIssueTime:$IgnoreFutureIssueTime
        foreach ($warning in $compared.Warnings) { Write-Warning $warning }
        Write-Output $compared.Message
    }
    return
}

# ---- build the payload from what the owner edits ----
try { $edited = ConvertFrom-OtaJson (Get-Content -LiteralPath $Source -Raw) }
catch { throw "The settings source '$Source' is not valid JSON: $($_.Exception.Message)" }
$fields = ConvertTo-OtaSource $edited $known
$payload = [ordered]@{ schema = 1; sequence = 0L; issuedAt = 0L; expiresAt = 0L; features = $fields.features }
if ($fields.Contains('notice')) { $payload.notice = $fields.notice }
if ($fields.Contains('update')) { $payload.update = $fields.update }

# ---- the next sequence number, and the issue times not to go back past: above the committed file and above whatever is live ----
# The committed file is the owner's own bookkeeping, so it is read even when the key that signed it has been retired from the app. The
# website is not: whoever can change it could write any numbers there, so a file that is live counts only when a key the app trusts signed it.
$previous = 0L
$issueTimes = [System.Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $Output) {
    $earlier = Read-OtaEnvelope (Get-Content -LiteralPath $Output -Raw) $trusted -IgnoreUnknownKey
    if (-not $earlier.Verified) { Write-Warning "The committed file was signed by '$($earlier.Key)', which the app no longer lists; its sequence number is still counted on." }
    $previous = [long]$earlier.Payload.sequence
    $issueTimes.Add([pscustomobject]@{ From = 'The committed file'; At = [long]$earlier.Payload.issuedAt })
}
if (-not $Offline) {
    try {
        $served = Get-LiveFile
        if ($served.Status -eq 200) {
            $believed = Read-OtaTrusted $served.Text $trusted
            if ($believed.Read) {
                $liveSequence = [long]$believed.Read.Payload.sequence
                if ($liveSequence -gt $previous) { Write-Warning "The live file (sequence $liveSequence) is newer than the committed one ($previous)."; $previous = $liveSequence }
                $issueTimes.Add([pscustomobject]@{ From = 'The file the website serves'; At = [long]$believed.Read.Payload.issuedAt })
            } else { Write-Warning "The file the website serves now cannot be verified, so its sequence number is not counted: $($believed.Why)" }
        }
    } catch { Write-Warning 'Could not read the live settings file; using the committed sequence only. Use -Offline to skip this check.' }
}
if ($PSBoundParameters.ContainsKey('Sequence')) {
    $next = $Sequence
    Write-Warning "Sequence $next was chosen by hand. A phone that already holds a number of $next or more for this key ignores the file."
} else { $next = $previous + 1 }
if ($next -gt $script:OtaMaxSequence) { throw "The next sequence number would pass $($script:OtaMaxSequence). If the key is new on every phone that matters, choose a lower one with -Sequence." }

# A phone ignores a file issued before one it has already taken (whichever key signed it), and this build of the app ignores one issued before
# its floor, so a file is never dated earlier than either. A phone accepts a file dated up to 36 hours ahead of its own clock, so when this
# computer's clock is behind by no more than that, the file is dated at the newer time (that is also what a clock that was moved back needs).
# Behind by more, the tool cannot tell a clock that is wrong now from a file that was signed while a clock was fast (a file that a phone with a
# right clock refuses and so holds nothing back), and it stops unless the owner says the clock is right.
$clockNow = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$issued = $clockNow
foreach ($item in $issueTimes) {
    if ($item.At -le $clockNow) { continue }
    $ahead = ($item.At - $clockNow) / 3600000.0
    if ($item.At - $clockNow -le $script:OtaClockSkew) {
        $issued = [Math]::Max($issued, $item.At)
        continue
    }
    if (-not $IgnoreFutureIssueTime) {
        throw ("{0} ({1}) is dated {2:N1} hours after this computer's clock ({3}), more than the 36 hours a phone allows. Either this computer's clock is behind (correct it), or that file was signed while a clock was fast: a phone whose clock is right refuses a file dated that far ahead, so none holds it yet (one takes it once real time is within 36 hours of its date). If the clock is right, -IgnoreFutureIssueTime sets that file aside and dates the new one now; publish before that time. Never use the switch to get around a clock that is wrong: a file dated in the past is ignored by every phone that has taken a later one." -f
            $item.From, (Format-OtaTime $item.At), $ahead, (Format-OtaTime $clockNow))
    }
    Write-Warning ("{0} ({1}) is dated {2:N1} hours after this computer's clock and is set aside because -IgnoreFutureIssueTime was given. Make sure this computer's clock is right. A phone whose clock is right takes that file once real time is within 36 hours of its date ({3}), so publish the new file before then: a phone that took the old one first holds a later time than the new file and ignores it." -f $item.From, (Format-OtaTime $item.At), $ahead, (Format-OtaTime ($item.At - $script:OtaClockSkew)))
}
if ($buildFloor -gt $clockNow + $script:OtaClockSkew) {
    throw ("The app's BUILD_ISSUED_FLOOR ({0}) is more than 36 hours after this computer's clock ({1}). This version of the app refuses a file issued before it, and a phone refuses a file dated that far ahead. Correct this computer's clock; if it is right, the floor was set to a time that has not come: correct BUILD_ISSUED_FLOOR in RemoteSettings.java." -f (Format-OtaTime $buildFloor), (Format-OtaTime $clockNow))
}
$issued = [Math]::Max($issued, $buildFloor)
if ($issued -gt $clockNow) {
    Write-Warning ("This computer's clock ({0}) is behind the time of the newest signed file or of the app's BUILD_ISSUED_FLOOR, by {1:N1} hours. A phone ignores a file issued before one it has already taken, so the new file is dated {2} instead; phones accept that, up to 36 hours ahead of their own clock. If this computer's clock is wrong, correct it." -f
        (Format-OtaTime $clockNow), (($issued - $clockNow) / 3600000.0), (Format-OtaTime $issued))
}
$payload.sequence = $next
$payload.issuedAt = $issued
$payload.expiresAt = $issued + $ValidDays * $day
$payloadText = $payload | ConvertTo-Json -Depth 8 -Compress
$payloadBytes = [Text.Encoding]::UTF8.GetBytes($payloadText)
if ($payloadBytes.Length -gt $script:OtaMaxPayload) { throw 'The settings are too large.' }
# What the app will parse, checked as the app checks it with the phone's clock where this computer's clock is, before anything is signed.
Assert-OtaPayload (ConvertFrom-OtaJson $payloadText) $known $clockNow

# ---- sign ----
if (-not $KeyId) {
    $withKeys = @($trusted.Keys | Where-Object { Test-Path -LiteralPath (Join-Path $secrets "ota-$_.pem") })
    if ($withKeys.Count -ne 1) { throw 'Pass -KeyId: it is not clear which listed key has its private half on this computer.' }
    $KeyId = $withKeys[0]
}
if (-not $trusted.ContainsKey($KeyId)) { throw 'That key ID is not in the list of public keys the app embeds (key IDs are case-sensitive).' }
$signer = [System.Security.Cryptography.ECDsa]::Create()
try {
    $signer.ImportFromEncryptedPem((Get-Content -LiteralPath (Join-Path $secrets "ota-$KeyId.pem") -Raw), (Get-Content -LiteralPath (Join-Path $secrets "ota-$KeyId.password.txt") -Raw).Trim())
    if (-not [Linq.Enumerable]::SequenceEqual([byte[]]$signer.ExportSubjectPublicKeyInfo(), [byte[]]$trusted[$KeyId])) { throw 'The private key does not match the public key the app embeds.' }
    $signature = $signer.SignData([byte[]]($script:OtaDomain + $payloadBytes), $script:OtaSha256, $script:OtaDer)
} finally { $signer.Dispose() }
$envelope = '{"v":1,"key":"' + $KeyId + '","payload":"' + (ConvertTo-Base64Url $payloadBytes) + '","sig":"' + (ConvertTo-Base64Url $signature) + '"}'
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null
$temporaryOutput = "$Output.new"
[IO.File]::WriteAllText($temporaryOutput, $envelope, [Text.UTF8Encoding]::new($false))
try {
    $written = Read-OtaEnvelope (Get-Content -LiteralPath $temporaryOutput -Raw) $trusted
    Assert-OtaPayload $written.Payload $known ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
} catch { Remove-Item -LiteralPath $temporaryOutput -Force; throw }
Move-Item -LiteralPath $temporaryOutput -Destination $Output -Force
Show-Settings (Read-OtaEnvelope (Get-Content -LiteralPath $Output -Raw) $trusted)
Write-Output "Wrote $Output. Publish it with a website release (docs/DEPLOYMENT-GUIDE.md), then check what is live with -Check -Live; phones pick it up within a day."
