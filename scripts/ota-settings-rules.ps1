#requires -Version 7.4
<#
Rules for over-the-air settings, shared by the tool that signs them (sign-ota-settings.ps1) and by its tests (test-ota-settings.ps1).

They restate what android\app\src\main\java\app\vanishr\android\RemoteSettings.java enforces, so that a file this tool produces is a file
the app accepts: the app refuses a whole file when one field is wrong, kill switches included. ota\rules-cases.json lists cases that the
tests of this tool and the app's unit tests (RemoteSettingsTest) are both run against, so the two cannot drift apart unnoticed.
The app is not stricter anywhere than this tool, except for one thing it is more relaxed about: it skips fields and switches it does not
know, so that a newer tool can talk to an older app, while this tool refuses them, because on this side an unknown name is a typing mistake.

Dot-source this file; it only defines functions and constants.
#>

$script:OtaMaxEnvelope = 12288
$script:OtaMaxPayload = 8192
$script:OtaMaxSequence = 1000000L
$script:OtaMaxNewlines = 6
$script:OtaDay = 86400000L
$script:OtaMaxValidityDays = 60
$script:OtaClockSkew = 36L * 3600000L
$script:OtaEarliest = 1600000000000L
$script:OtaLatest = 4102444800000L
$script:OtaDomain = [byte[]]([Text.Encoding]::ASCII.GetBytes('vanishr-ota-settings-v1') + [byte]0)
$script:OtaDer = [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence
$script:OtaSha256 = [System.Security.Cryptography.HashAlgorithmName]::SHA256
# A page on the download site and nothing else: no dots (so no file such as the APK, no dot segments), no empty segment, no query.
$script:OtaPagePattern = '\A(?:/|/[A-Za-z0-9_-]{1,40}(?:/[A-Za-z0-9_-]{1,40}){0,3}/?)\z'

function ConvertTo-Base64Url([byte[]]$Bytes) { [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }

function ConvertFrom-Base64Url([string]$Text) {
    $padded = $Text.Replace('-', '+').Replace('_', '/')
    $padded += '=' * ((4 - $padded.Length % 4) % 4)
    try { return [Convert]::FromBase64String($padded) } catch { throw 'The signed settings file holds text that is not base64url.' }
}

# A strict JSON reader for everything this tool reads or checks. PowerShell's ConvertFrom-Json accepts comments, trailing commas, single quotes,
# unquoted names and leading zeros, lets the last of two equal names win without a word (in a settings source that would flip a switch silently)
# and, depending on the version, turns text that looks like a date into a date. This reader is System.Text.Json's strict one (so syntax is judged
# as the app's strict reader judges it) plus a refusal of repeated names; strings stay strings, and a number is an integer only when it is written
# the way the app reads integers (no sign, fraction, exponent or leading zero, at most 16 digits).
function ConvertFrom-OtaElement($Element) {
    switch ($Element.ValueKind.ToString()) {
        'Object' {
            $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            # PowerShell cannot hold two names that differ only in case, and would quietly keep one of them (renamed, with the other's value);
            # the app, which does tell them apart, would use one and skip the other as unknown. Either way the owner did not write what is
            # signed, so such an object is refused.
            $folded = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            $values = [ordered]@{}
            foreach ($property in $Element.EnumerateObject()) {
                if (-not $seen.Add($property.Name)) { throw "The name '$($property.Name)' is written twice in the same object." }
                if (-not $folded.Add($property.Name)) { throw "The name '$($property.Name)' is written in two different cases in the same object; names are case-sensitive, so one of them would be silently ignored." }
                $values[$property.Name] = ConvertFrom-OtaElement $property.Value
            }
            return [pscustomobject]$values
        }
        'Array' { return , @($Element.EnumerateArray() | ForEach-Object { ConvertFrom-OtaElement $_ }) }
        'String' { return $Element.GetString() }
        'Number' {
            $literal = $Element.GetRawText()
            if ($literal -cmatch '\A(?:0|[1-9][0-9]{0,15})\z') { return [long]$literal }
            return [double]::Parse($literal, [Globalization.CultureInfo]::InvariantCulture)
        }
        'True' { return $true }
        'False' { return $false }
        default { return $null }
    }
}

function ConvertFrom-OtaJson([string]$Text) {
    $options = [System.Text.Json.JsonDocumentOptions]::new()
    $options.MaxDepth = 16
    $document = $null
    try { $document = [System.Text.Json.JsonDocument]::Parse($Text, $options) }
    catch {
        $cause = if ($_.Exception.InnerException) { $_.Exception.InnerException } else { $_.Exception }
        $where = if ($cause -is [System.Text.Json.JsonException] -and $null -ne $cause.LineNumber) { " (line $($cause.LineNumber + 1), position $($cause.BytePositionInLine + 1))" } else { '' }
        throw "The text is not valid JSON$where."
    }
    try { return (ConvertFrom-OtaElement $document.RootElement) }
    catch {
        $cause = if ($_.Exception.InnerException) { $_.Exception.InnerException } else { $_.Exception }
        throw $(if ($cause.Message -like 'The name *') { $cause.Message } else { 'The text is not valid JSON.' })
    }
    finally { $document.Dispose() }
}

# The same list of keys the app embeds: "keyId base64-of-X.509-SubjectPublicKeyInfo" per line, P-256 only, at most three. Key IDs are case-sensitive.
function Read-OtaKeys([string]$Path) {
    $keys = [System.Collections.Generic.Dictionary[string, byte[]]]::new([StringComparer]::Ordinal)
    foreach ($line in Get-Content -LiteralPath $Path) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        $parts = $entry -split '\s+'
        if ($parts.Count -ne 2 -or $parts[0] -cnotmatch '\A[A-Za-z0-9_-]{1,16}\z' -or $keys.ContainsKey($parts[0]) -or $keys.Count -ge 3) { throw 'The public key list is malformed.' }
        try {
            $der = [Convert]::FromBase64String($parts[1])
            $probe = [System.Security.Cryptography.ECDsa]::Create()
            try { $read = 0; $probe.ImportSubjectPublicKeyInfo($der, [ref]$read); if ($probe.KeySize -ne 256) { throw 'Not a P-256 key.' } } finally { $probe.Dispose() }
        } catch { throw 'The public key list is malformed.' }
        $keys[$parts[0]] = $der
    }
    return , $keys
}

function Test-OtaInteger($Value, [long]$Minimum, [long]$Maximum) {
    return ($Value -is [int] -or $Value -is [long]) -and $Value -ge $Minimum -and $Value -le $Maximum
}

# The switches the app knows, read from the app's own list so that they cannot drift.
function Get-OtaKnownFeatures([string]$JavaFile) {
    $java = Get-Content -LiteralPath $JavaFile -Raw
    $body = [regex]::Match($java, 'enum Flag \{(?<body>.*?);', 'Singleline').Groups['body'].Value
    $names = @([regex]::Matches($body, '[A-Z_]+\("([A-Za-z]+)",') | ForEach-Object { $_.Groups[1].Value })
    if ($names.Count -eq 0) { throw 'Could not read the feature names from RemoteSettings.java.' }
    return , $names
}

function Get-OtaCodePoints([string]$Value) {
    $index = 0
    while ($index -lt $Value.Length) {
        $unit = $Value[$index]
        if ([char]::IsHighSurrogate($unit) -and $index + 1 -lt $Value.Length -and [char]::IsLowSurrogate($Value[$index + 1])) {
            [char]::ConvertToUtf32($unit, $Value[$index + 1]); $index += 2
        } else { [int]$unit; $index += 1 }
    }
}

# Visible text: no controls, line or paragraph separators, nothing invisible or direction-changing (the "format" characters, apart from the
# two joiners that emoji and some scripts need), no private-use or lone surrogate code points and no noncharacters. Whether a code point is
# assigned is deliberately not asked: that depends on the Unicode version of the phone's Android.
function Test-OtaVisible([int]$Point) {
    if ($Point -eq 0x200C -or $Point -eq 0x200D) { return $true }
    if ($Point -eq 0xFFFD -or ($Point -ge 0xFDD0 -and $Point -le 0xFDEF) -or (($Point -band 0xFFFE) -eq 0xFFFE)) { return $false }
    $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($Point)
    return -not ($category -eq [Globalization.UnicodeCategory]::Control -or $category -eq [Globalization.UnicodeCategory]::Format -or
        $category -eq [Globalization.UnicodeCategory]::LineSeparator -or $category -eq [Globalization.UnicodeCategory]::ParagraphSeparator -or
        $category -eq [Globalization.UnicodeCategory]::PrivateUse -or $category -eq [Globalization.UnicodeCategory]::Surrogate)
}

# What Java's String.isBlank() calls blank: only white space, where a no-break space is not white space.
function Test-OtaBlank([string]$Value) {
    foreach ($point in (Get-OtaCodePoints $Value)) {
        $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($point)
        $space = ($category -eq [Globalization.UnicodeCategory]::SpaceSeparator -or $category -eq [Globalization.UnicodeCategory]::LineSeparator -or
            $category -eq [Globalization.UnicodeCategory]::ParagraphSeparator) -and $point -ne 0xA0 -and $point -ne 0x2007 -and $point -ne 0x202F
        if (-not ($space -or ($point -ge 9 -and $point -le 13) -or ($point -ge 0x1C -and $point -le 0x1F))) { return $false }
    }
    return $true
}

function Test-OtaText($Value, [int]$Maximum, [bool]$Newlines) {
    if ($Value -isnot [string] -or $Value.Length -eq 0 -or $Value.Length -gt $Maximum) { return $false }
    $breaks = 0
    foreach ($point in (Get-OtaCodePoints $Value)) {
        if ($point -eq 10) {
            $breaks++
            if (-not $Newlines -or $breaks -gt $script:OtaMaxNewlines) { return $false }
        } elseif (-not (Test-OtaVisible $point)) { return $false }
    }
    return -not (Test-OtaBlank $Value)
}

# The three things a settings file can carry, checked as the app checks them. Returns what is to be written; throws on the first problem.
# With -NoNulls a field that is present must hold a value, as in a signed file; a source file may leave an optional field as null.
function ConvertTo-OtaFields($Features, $Notice, $Update, [string[]]$Known, [switch]$NoNulls) {
    $result = [ordered]@{}
    $switches = [ordered]@{}
    if ($null -ne $Features) {
        if ($Features -isnot [pscustomobject]) { throw 'features must be an object.' }
        foreach ($property in $Features.PSObject.Properties) {
            if ($Known -cnotcontains $property.Name) { throw "Unknown feature '$($property.Name)'. The app knows: $($Known -join ', '). A new switch needs a new app version." }
            if ($property.Value -isnot [bool]) { throw "Feature '$($property.Name)' must be true or false." }
            $switches[$property.Name] = $property.Value
        }
    }
    $result.features = $switches
    if ($null -ne $Notice) {
        if ($Notice -isnot [pscustomobject]) { throw 'notice must be an object.' }
        $fields = 'id', 'title', 'body', 'level', 'path', 'build', 'minVersionCode', 'maxVersionCode'
        foreach ($property in $Notice.PSObject.Properties) {
            if ($fields -cnotcontains $property.Name) { throw "Unknown notice field '$($property.Name)'." }
            if ($NoNulls -and $null -eq $property.Value) { throw "notice.$($property.Name) must not be null." }
        }
        if ($Notice.id -isnot [string] -or $Notice.id -cnotmatch '\A[A-Za-z0-9._-]{1,40}\z') { throw 'notice.id must be 1 to 40 letters, digits, dots, dashes or underscores.' }
        if (-not (Test-OtaText $Notice.title 60 $false)) { throw 'notice.title must be 1 to 60 characters of plain visible text on one line.' }
        if (-not (Test-OtaText $Notice.body 400 $true)) { throw "notice.body must be 1 to 400 characters of plain visible text with at most $($script:OtaMaxNewlines) line breaks." }
        if ($Notice.level -isnot [string] -or $Notice.level -cnotin @('info', 'important')) { throw 'notice.level must be info or important.' }
        $written = [ordered]@{ id = $Notice.id; title = $Notice.title; body = $Notice.body; level = $Notice.level }
        if ($null -ne $Notice.path) {
            if ($Notice.path -isnot [string] -or $Notice.path -cnotmatch $script:OtaPagePattern) { throw 'notice.path must be a page on the download site, such as /security/: letters, digits, dashes and slashes only, no file names.' }
            $written.path = $Notice.path
        }
        if ($null -ne $Notice.build) {
            if ($Notice.build -isnot [string] -or $Notice.build -cnotin @('all', 'play', 'direct')) { throw 'notice.build must be all, play or direct.' }
            $written.build = $Notice.build
        }
        foreach ($bound in 'minVersionCode', 'maxVersionCode') {
            if ($null -ne $Notice.$bound) {
                if (-not (Test-OtaInteger $Notice.$bound 0 ([long][int]::MaxValue))) { throw "notice.$bound must be a version code." }
                $written[$bound] = [long]$Notice.$bound
            }
        }
        if ($written.Contains('minVersionCode') -and $written.Contains('maxVersionCode') -and $written.minVersionCode -gt $written.maxVersionCode) { throw 'notice.minVersionCode cannot be above maxVersionCode.' }
        $result.notice = $written
    }
    if ($null -ne $Update) {
        if ($Update -isnot [pscustomobject]) { throw 'update must be an object.' }
        $fields = 'latestVersionCode', 'latestVersionName', 'minSupportedVersionCode'
        foreach ($property in $Update.PSObject.Properties) {
            if ($fields -cnotcontains $property.Name) { throw "Unknown update field '$($property.Name)'." }
        }
        if (-not (Test-OtaInteger $Update.latestVersionCode 1 ([long][int]::MaxValue))) { throw 'update.latestVersionCode must be a version code.' }
        if (-not (Test-OtaInteger $Update.minSupportedVersionCode 0 ([long][int]::MaxValue))) { throw 'update.minSupportedVersionCode must be a version code.' }
        if ($Update.minSupportedVersionCode -gt $Update.latestVersionCode) { throw 'update.minSupportedVersionCode cannot be above latestVersionCode.' }
        if ($Update.latestVersionName -isnot [string] -or $Update.latestVersionName.Length -gt 20 -or
            $Update.latestVersionName -cnotmatch '\A(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\z') { throw 'update.latestVersionName must look like 0.6.2.' }
        $result.update = [ordered]@{ latestVersionCode = [long]$Update.latestVersionCode; latestVersionName = $Update.latestVersionName; minSupportedVersionCode = [long]$Update.minSupportedVersionCode }
    }
    return $result
}

# A settings source (what the owner edits) as a whole: only the three known parts are allowed, and each is checked.
function ConvertTo-OtaSource($Edited, [string[]]$Known) {
    if ($Edited -isnot [pscustomobject]) { throw 'The settings source must be a JSON object.' }
    foreach ($name in $Edited.PSObject.Properties.Name) {
        if ($name -cnotin @('features', 'notice', 'update')) { throw "Unknown field '$name' in the settings source. Only features, notice and update can be set." }
    }
    return (ConvertTo-OtaFields $Edited.features $Edited.notice $Edited.update $Known)
}

# Reads a signed file and verifies it. A file signed by a key the app does not (or no longer) trust is refused, unless -IgnoreUnknownKey asks
# only for what it says, as when the next sequence number is worked out from an older file.
function Read-OtaEnvelope([string]$Text, $Trusted, [switch]$IgnoreUnknownKey) {
    if ($Text.Length -gt $script:OtaMaxEnvelope) { throw 'The signed settings file is too large.' }
    $shape = '\A\{"v":1,"key":"(?<key>[A-Za-z0-9_-]{1,16})","payload":"(?<payload>[A-Za-z0-9_-]+)","sig":"(?<sig>[A-Za-z0-9_-]+)"\}\s*\z'
    if ($Text -cnotmatch $shape) { throw 'The signed settings file does not have the shape the app expects.' }
    $key = $Matches['key']
    $payloadBytes = ConvertFrom-Base64Url $Matches['payload']
    $signature = ConvertFrom-Base64Url $Matches['sig']
    if ($payloadBytes.Length -eq 0 -or $payloadBytes.Length -gt $script:OtaMaxPayload) { throw 'The signed settings payload has a size the app refuses.' }
    $verified = $false
    if ($Trusted.ContainsKey($key)) {
        $verifier = [System.Security.Cryptography.ECDsa]::Create()
        try {
            $read = 0
            $verifier.ImportSubjectPublicKeyInfo($Trusted[$key], [ref]$read)
            if (-not $verifier.VerifyData([byte[]]($script:OtaDomain + $payloadBytes), $signature, $script:OtaSha256, $script:OtaDer)) { throw 'The signature does not verify.' }
        } finally { $verifier.Dispose() }
        $verified = $true
    } elseif (-not $IgnoreUnknownKey) { throw "The signed settings file names the key '$key', which the app does not trust." }
    return [pscustomobject]@{ Key = $key; Verified = $verified; Payload = (ConvertFrom-OtaJson ([Text.Encoding]::UTF8.GetString($payloadBytes))); EnvelopeBytes = $Text.Length }
}

# The rules about the file as a whole, as the app applies them with the phone's clock at $Now (milliseconds since 1970).
function Assert-OtaPayload($Payload, [string[]]$Known, [long]$Now) {
    if ($Payload -isnot [pscustomobject] -or -not (Test-OtaInteger $Payload.schema 1 1)) { throw 'The settings schema is not one the app knows.' }
    if (-not (Test-OtaInteger $Payload.sequence 1 $script:OtaMaxSequence)) { throw "The sequence number must be between 1 and $($script:OtaMaxSequence)." }
    if (-not (Test-OtaInteger $Payload.issuedAt $script:OtaEarliest $script:OtaLatest) -or -not (Test-OtaInteger $Payload.expiresAt 1 $script:OtaLatest)) { throw 'The dates are out of range.' }
    if ($Payload.expiresAt -le $Payload.issuedAt -or ($Payload.expiresAt - $Payload.issuedAt) -gt $script:OtaMaxValidityDays * $script:OtaDay) { throw "The file must be valid for more than nothing and at most $($script:OtaMaxValidityDays) days." }
    if ($Payload.issuedAt -gt $Now + $script:OtaClockSkew) { throw 'The file is dated in the future.' }
    if ($Payload.expiresAt -le $Now) { throw 'The signed settings have expired; phones ignore them. Sign and publish new ones.' }
    # A part that is present must be what the app reads: notice and update may be null there, features may not.
    $features = $Payload.PSObject.Properties['features']
    if ($null -ne $features -and $null -eq $features.Value) { throw 'features must be an object, not null; the app refuses a file that says otherwise.' }
    $null = ConvertTo-OtaFields $Payload.features $Payload.notice $Payload.update $Known -NoNulls
}

# The issue time that this build of the app holds every signed file to (BUILD_ISSUED_FLOOR), read from the app's own source.
function Get-OtaBuildFloor([string]$JavaFile) {
    $found = [regex]::Match((Get-Content -LiteralPath $JavaFile -Raw), 'BUILD_ISSUED_FLOOR = (?<value>[0-9][0-9_]*)L;')
    if (-not $found.Success) { throw 'Could not read BUILD_ISSUED_FLOOR from RemoteSettings.java.' }
    return [long]($found.Groups['value'].Value.Replace('_', ''))
}

# What a file that the website controls may be believed to say: only what a trusted key signed. $Read is $null, and $Why says why, when the
# file cannot be believed; the numbers of a file that anybody who can change the website could have written must never be counted.
function Read-OtaTrusted([string]$Text, $Trusted) {
    try { $read = Read-OtaEnvelope $Text $Trusted }
    catch { return [pscustomobject]@{ Read = $null; Why = $_.Exception.Message } }
    $payload = $read.Payload
    if ($payload -isnot [pscustomobject] -or -not (Test-OtaInteger $payload.sequence 1 $script:OtaMaxSequence) -or
        -not (Test-OtaInteger $payload.issuedAt $script:OtaEarliest $script:OtaLatest)) {
        return [pscustomobject]@{ Read = $null; Why = 'It has no sequence number and issue time the app would accept.' }
    }
    return [pscustomobject]@{ Read = $read; Why = '' }
}

function Get-OtaHeader($Headers, [string]$Name) {
    foreach ($key in $Headers.Keys) { if ($key -ieq $Name) { return (@($Headers[$key]) -join ',') } }
    return ''
}

# A half-written settings file (settings.json.new) is what an interrupted signing leaves behind; a release that copies the settings folder
# would carry it into the source archive.
function Assert-OtaNoLeftoverFile([string]$Folder) {
    $left = @(Get-ChildItem -LiteralPath $Folder -Filter '*.new' -Force -ErrorAction SilentlyContinue)
    if ($left.Count -gt 0) {
        throw "$Folder holds a half-written settings file ($($left[0].Name)) from an interrupted signing, which would be copied into the source archive. Remove it, and sign again if the settings file is not the one that should go out."
    }
}

# Compares what the website serves with the committed file (which the caller has already verified).
#
# Before a release (the default) the website is about to be replaced, so whatever is wrong with what it serves now is only reported in
# Warnings: nothing there yet, a file that has expired or was signed by a key the app no longer lists, headers that are not right. What
# stops the release is a verified file that is newer than the committed one: a phone that took it would refuse the committed file, so the
# checkout is behind (the same key with a sequence that is not lower, or any file issued later).
#
# After a release (-RequireIdentical) the website must serve exactly the committed file, the way the app expects it, and in force.
#
# -IgnoreFutureIssueTime is the recovery from a file that was signed while the signing computer's clock was fast: a verified live file that is
# dated more than a phone allows (36 hours) ahead of $Now no longer stops the release, because a phone whose clock is right refuses it and so
# does not hold it. It is the owner's statement that the clock is right now; it never applies to a file dated within the 36 hours.
function Test-OtaLive([string]$LocalText, [int]$Status, $Headers, [string]$LiveText, $Trusted, [string[]]$Known, [long]$Now, [switch]$RequireIdentical, [switch]$IgnoreFutureIssueTime) {
    $local = Read-OtaEnvelope $LocalText $Trusted
    $localSequence = [long]$local.Payload.sequence
    $localIssued = [long]$local.Payload.issuedAt
    $warnings = [System.Collections.Generic.List[string]]::new()
    $answer = {
        param($Live, $Identical, [string]$Message)
        [pscustomobject]@{ Identical = $Identical; LiveSequence = $Live; LocalSequence = $localSequence; Warnings = $warnings.ToArray(); Message = $Message }
    }
    $problem = { param([string]$Text) if ($RequireIdentical) { throw $Text } else { $warnings.Add($Text) } }

    if ($Status -ne 200) {
        & $problem "The website answered $Status, not 200, for the settings file."
        return (& $answer $null $false "Nothing usable is served yet; the committed file (sequence $localSequence) is what a release publishes.")
    }
    if ((Get-OtaHeader $Headers 'Content-Type') -notmatch '\Aapplication/json(?:\s*;.*)?\z') { & $problem 'The website serves the settings file with a content type other than application/json.' }
    if ((Get-OtaHeader $Headers 'Cache-Control') -notmatch '\bno-store\b') { & $problem 'The website does not mark the settings file no-store.' }
    if ((Get-OtaHeader $Headers 'X-Robots-Tag') -notmatch '\bnoindex\b') { & $problem 'The website does not mark the settings file noindex.' }

    if ($RequireIdentical) {
        if ($LiveText -cne $LocalText) { throw 'The website serves a file other than the committed one.' }
        Assert-OtaPayload $local.Payload $Known $Now
        return (& $answer $localSequence $true "The website serves exactly the committed file (sequence $localSequence), in force.")
    }

    $believed = Read-OtaTrusted $LiveText $Trusted
    if (-not $believed.Read) {
        $warnings.Add("The file the website serves now cannot be checked, and nothing in it is counted: $($believed.Why)")
        return (& $answer $null $false "The committed file (sequence $localSequence) replaces whatever is served now when it is published.")
    }
    $live = $believed.Read
    $liveSequence = [long]$live.Payload.sequence
    $liveIssued = [long]$live.Payload.issuedAt
    try { Assert-OtaPayload $live.Payload $Known $Now }
    catch { $warnings.Add("The file the website serves now (sequence $liveSequence) is not in force, so phones ignore it: $($_.Exception.Message)") }
    if ($LiveText -ceq $LocalText) { return (& $answer $liveSequence $true "The website serves exactly the committed file (sequence $liveSequence).") }
    $setAside = $false
    if ($liveIssued -gt $localIssued) {
        if (-not ($IgnoreFutureIssueTime -and $liveIssued -gt $Now + $script:OtaClockSkew)) {
            throw "The website serves a file issued later ($liveIssued, sequence $liveSequence, signed by '$($live.Key)') than the committed one ($localIssued): this checkout is behind, and a phone that took the file on the website would refuse the committed one. Update the checkout before publishing. (If this computer's clock is right and that file was signed while a clock was fast, -IgnoreFutureIssueTime sets a file dated more than 36 hours ahead aside.)"
        }
        $setAside = $true
        $takenFrom = [DateTimeOffset]::FromUnixTimeMilliseconds($liveIssued - $script:OtaClockSkew).ToString('u')
        $warnings.Add("The file the website serves now (sequence $liveSequence) is dated more than 36 hours ahead of this computer's clock, so a phone whose clock is right refuses it until real time reaches $takenFrom; it is replaced because -IgnoreFutureIssueTime was given. Publish the committed file before then: a phone that takes the old one first holds a later time than the committed file and ignores it. Make sure this computer's clock is right.")
    }
    # The same key's sequence rule applies to a file that is set aside as well: a phone that does take it holds its number as the floor.
    if ($live.Key -ceq $local.Key -and $liveSequence -ge $localSequence) {
        throw "The website serves sequence $liveSequence of key '$($live.Key)', which is not below the committed file's ($localSequence), and it is not the same file. A phone that took it would refuse the committed one. Update the checkout, or sign again."
    }
    if ($setAside) { return (& $answer $liveSequence $false "The website still serves sequence $liveSequence, which is dated far ahead; the committed file (sequence $localSequence) replaces it when it is published.") }
    return (& $answer $liveSequence $false "The website still serves sequence $liveSequence; the committed file (sequence $localSequence) replaces it when it is published.")
}
