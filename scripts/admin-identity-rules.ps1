#requires -Version 7.4
<#
Rules for the admin identity certificate, shared by the tool that signs it (sign-admin-identity.ps1) and by its tests (test-admin-identity.ps1).

They restate what android\app\src\main\java\app\vanishr\android\AdminIdentity.java enforces, so that a file this tool produces is a file the
app accepts, and a few things the app does not need to ask (the owner has to confirm that the identity is the one on the phone in their
hand). The tool is never more lenient than the app. It reads files more strictly than the app does: it only knows the exact shape that it
writes itself.

The strict JSON reader, the base64url helpers and the key list reader come from ota-settings-rules.ps1; this file adds what is particular to
this certificate. Dot-source it; it only defines functions and constants.
#>
. (Join-Path $PSScriptRoot 'ota-settings-rules.ps1')

$script:AdminMaxEnvelope = 2048
$script:AdminMaxPayload = 1024
$script:AdminMaxSequence = 1000000L
$script:AdminDomain = [byte[]]([Text.Encoding]::ASCII.GetBytes('vanishr-admin-identity-v1') + [byte]0)
$script:AdminUuidPattern = '\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z'
$script:AdminFingerprintPattern = '\A[0-9a-f]{64}\z'

# The SHA-256 of "userId:identityKey" as lower-case hex: the same number a phone shows as its safety number (ChatEngine.safetyNumber), and the
# fingerprint the app's pin holds. The identity key is what libsignal serialises for a public identity: 33 bytes that start with 0x05.
function Get-AdminFingerprint([string]$UserId, [string]$IdentityKey) {
    if ($UserId -cnotmatch $script:AdminUuidPattern) { throw 'The user ID must be a lower-case UUID.' }
    try { $bytes = [Convert]::FromBase64String($IdentityKey) } catch { throw 'The identity key is not base64.' }
    if ($bytes.Length -ne 33 -or $bytes[0] -ne 5 -or [Convert]::ToBase64String($bytes) -cne $IdentityKey) { throw 'The identity key is not a 33-byte public identity in the canonical base64 form.' }
    $digest = [System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes("${UserId}:${IdentityKey}"))
    return (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
}

# How the phone writes it: upper case, eight characters to a group.
function Format-AdminSafetyNumber([string]$Fingerprint) {
    if ($Fingerprint -cnotmatch $script:AdminFingerprintPattern) { throw 'Not a fingerprint.' }
    return ((0..7 | ForEach-Object { $Fingerprint.Substring($_ * 8, 8).ToUpperInvariant() }) -join ' ')
}

# The owner reads the number off the phone that is to be the admin and types it; it has to be the identity the relay holds. All of it (spaces
# are ignored), or just the first and the last group (16 characters), which is enough to tell a different identity from this one.
function Confirm-AdminPhoneNumber([string]$Fingerprint, [string]$Typed) {
    if ($Fingerprint -cnotmatch $script:AdminFingerprintPattern) { throw 'Not a fingerprint.' }
    $text = ($Typed -replace '[\s-]', '').ToLowerInvariant()
    if ($text -cnotmatch '\A[0-9a-f]+\z' -or ($text.Length -ne 64 -and $text.Length -ne 16)) {
        throw 'Type the safety number as the phone shows it (My profile > Verify identity: 8 groups of 8 characters, 0-9 and A-F), or only its first and its last group (16 characters).'
    }
    $same = if ($text.Length -eq 64) { $text -ceq $Fingerprint } else { ($text.Substring(0, 8) -ceq $Fingerprint.Substring(0, 8)) -and ($text.Substring(8, 8) -ceq $Fingerprint.Substring(56, 8)) }
    if (-not $same) { throw 'That is not the number of the identity the relay holds for the admin. Check that the phone in your hand is the one that signed in as the admin, and that nobody else replaced it.' }
}

# What this build of the app was made with: the floor (AdminIdentity.BUILD_SEQUENCE_FLOOR) and the built-in pin (AdminOnboarding.OFFICIAL),
# read from the sources so that the tool and the app cannot disagree about them.
function Get-AdminBuild([string]$IdentityJava, [string]$OnboardingJava) {
    $floor = [regex]::Match((Get-Content -LiteralPath $IdentityJava -Raw), 'BUILD_SEQUENCE_FLOOR = (?<value>[0-9]+);')
    if (-not $floor.Success) { throw 'Could not read BUILD_SEQUENCE_FLOOR from AdminIdentity.java.' }
    $pin = [regex]::Match((Get-Content -LiteralPath $OnboardingJava -Raw),
        'static final Pin OFFICIAL = new Pin\(\s*"(?<origin>[^"]+)",\s*UUID\.fromString\("(?<user>[0-9a-f-]{36})"\),\s*UUID\.fromString\("(?<device>[0-9a-f-]{36})"\),\s*"(?<fingerprint>[0-9a-f]{64})"\);', 'Singleline')
    if (-not $pin.Success) { throw 'Could not read the built-in pin (OFFICIAL) from AdminOnboarding.java.' }
    return [pscustomobject]@{
        Floor = [long]$floor.Groups['value'].Value; Origin = $pin.Groups['origin'].Value
        UserId = $pin.Groups['user'].Value; DeviceId = $pin.Groups['device'].Value; Fingerprint = $pin.Groups['fingerprint'].Value
    }
}

# The payload exactly as it is signed: fixed order, no spaces, nothing the app would have to skip.
function New-AdminPayloadText([long]$Sequence, [string]$UserId, [string]$DeviceId, [string]$Fingerprint) {
    return ('{{"schema":1,"sequence":{0},"userId":"{1}","deviceId":"{2}","fingerprint":"{3}"}}' -f $Sequence, $UserId, $DeviceId, $Fingerprint)
}

# The rules about the payload, as the app applies them. $Admin is the account the app pins.
function Assert-AdminPayload($Payload, [string]$Admin) {
    if ($Payload -isnot [pscustomobject]) { throw 'The certificate payload must be a JSON object.' }
    $expected = 'deviceId', 'fingerprint', 'schema', 'sequence', 'userId'
    $names = @($Payload.PSObject.Properties.Name | Sort-Object)
    if ((($names -join ',') -cne ($expected -join ','))) { throw "The certificate payload must hold exactly: $($expected -join ', ')." }
    if (-not (Test-OtaInteger $Payload.schema 1 1)) { throw 'The certificate schema is not one the app knows.' }
    if (-not (Test-OtaInteger $Payload.sequence 1 $script:AdminMaxSequence)) { throw "The sequence number must be between 1 and $($script:AdminMaxSequence)." }
    foreach ($name in 'userId', 'deviceId') {
        if ($Payload.$name -isnot [string] -or $Payload.$name -cnotmatch $script:AdminUuidPattern) { throw "$name must be a lower-case UUID." }
    }
    if ($Payload.fingerprint -isnot [string] -or $Payload.fingerprint -cnotmatch $script:AdminFingerprintPattern) { throw 'fingerprint must be 64 lower-case hex characters.' }
    if ($Payload.userId -cne $Admin) { throw 'The certificate names an account other than the official admin.' }
}

# Reads a signed certificate and verifies it the way the app does. A certificate signed by a key the app does not trust is refused.
function Read-AdminEnvelope([string]$Text, $Trusted, [string]$Admin) {
    if ($Text.Length -gt $script:AdminMaxEnvelope) { throw 'The certificate file is too large.' }
    $shape = '\A\{"v":1,"key":"(?<key>[A-Za-z0-9_-]{1,16})","payload":"(?<payload>[A-Za-z0-9_-]+)","sig":"(?<sig>[A-Za-z0-9_-]+)"\}\s*\z'
    if ($Text -cnotmatch $shape) { throw 'The certificate file does not have the shape the app expects.' }
    $key = $Matches['key']
    $payloadBytes = ConvertFrom-Base64Url $Matches['payload']
    $signature = ConvertFrom-Base64Url $Matches['sig']
    if ($payloadBytes.Length -eq 0 -or $payloadBytes.Length -gt $script:AdminMaxPayload) { throw 'The certificate payload has a size the app refuses.' }
    if (-not $Trusted.ContainsKey($key)) { throw "The certificate names the key '$key', which the app does not trust." }
    $verifier = [System.Security.Cryptography.ECDsa]::Create()
    try {
        $read = 0
        $verifier.ImportSubjectPublicKeyInfo($Trusted[$key], [ref]$read)
        if (-not $verifier.VerifyData([byte[]]($script:AdminDomain + $payloadBytes), $signature, $script:OtaSha256, $script:OtaDer)) { throw 'The signature does not verify.' }
    } finally { $verifier.Dispose() }
    $payload = ConvertFrom-OtaJson ([Text.Encoding]::UTF8.GetString($payloadBytes))
    Assert-AdminPayload $payload $Admin
    return [pscustomobject]@{ Key = $key; Payload = $payload; EnvelopeBytes = $Text.Length }
}

# What the website serves compared with the committed file. Before a release (the default) what is served is only reported on, because the
# release replaces it, except for the one thing that matters: a certificate that a trusted key signed with a higher sequence than the
# committed one means this checkout is behind, and a phone that took that one would refuse the committed one. After a release
# (-RequireIdentical) the website must serve exactly the committed file, the way the app expects it.
# Returns the text to report, or throws; a warning is returned with $Warn set so that the caller can show it as one.
function Test-AdminLive([string]$LocalText, [int]$Status, $Headers, [string]$LiveText, $Trusted, [string]$Admin, [switch]$RequireIdentical) {
    $problem = $null
    if ($Status -eq 404) { $problem = 'Nothing is published there yet.' }
    elseif ($Status -ne 200) { $problem = "The website answered $Status." }
    else {
        $type = Get-OtaHeader $Headers 'Content-Type'
        $cache = Get-OtaHeader $Headers 'Cache-Control'
        if ($type -notmatch '\Aapplication/json(?:\s*;\s*charset=utf-8)?\z') { $problem = "It is served as '$type', not application/json." }
        elseif ($cache -notmatch 'no-store') { $problem = "It is served with Cache-Control '$cache', not no-store." }
    }
    $live = $null
    if (-not $problem) {
        try { $live = Read-AdminEnvelope $LiveText $Trusted $Admin }
        catch { $problem = "What is served is not a certificate the app would take ($($_.Exception.Message))." }
    }
    $local = Read-AdminEnvelope $LocalText $Trusted $Admin
    if ($live -and $live.Payload.sequence -gt $local.Payload.sequence) {
        throw "The website serves sequence $($live.Payload.sequence), which is newer than the committed file's $($local.Payload.sequence): this checkout is behind. A phone that took that certificate refuses this one. Update the checkout before releasing."
    }
    if ($RequireIdentical) {
        if ($problem) { throw "The release is not what the website serves: $problem" }
        if ($LiveText -cne $LocalText) { throw 'The website serves a certificate other than the committed file.' }
        return [pscustomobject]@{ Warn = $false; Text = "The website serves exactly the committed certificate (sequence $($local.Payload.sequence))." }
    }
    if ($problem) { return [pscustomobject]@{ Warn = $true; Text = "$problem The release replaces it." } }
    if ($live.Payload.sequence -lt $local.Payload.sequence -or $LiveText -cne $LocalText) {
        return [pscustomobject]@{ Warn = $false; Text = "The website serves sequence $($live.Payload.sequence); the committed file is sequence $($local.Payload.sequence), which a release will publish." }
    }
    return [pscustomobject]@{ Warn = $false; Text = "The website already serves the committed certificate (sequence $($local.Payload.sequence))." }
}
