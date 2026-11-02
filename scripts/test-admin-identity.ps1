#requires -Version 7.4
<#
Tests the rules and the tool for the admin identity certificate without a phone, a network or the real authority key: the rules (what is
signed, how the owner's confirmation works, how the app's build is read), the checks on a signed certificate, the comparison with what the
website serves, and the whole signing tool in a throwaway copy of the repository layout with a key made for the test.
#>
$ErrorActionPreference = 'Stop'
# The tool warns whenever a device ID is given by hand, which the fixtures below do all the time; the one test that cares collects the warning itself.
$WarningPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'admin-identity-rules.ps1')
$identityJava = Join-Path $root 'android\app\src\main\java\app\vanishr\android\AdminIdentity.java'
$onboardingJava = Join-Path $root 'android\app\src\main\java\app\vanishr\android\AdminOnboarding.java'
$build = Get-AdminBuild $identityJava $onboardingJava
$admin = $build.UserId
$passed = 0
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('vanishr-admin-identity-' + [guid]::NewGuid().ToString('N'))

function Test-Case([string]$Name, [scriptblock]$Action) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & $Action
    $script:passed++
    Write-Output ('PASS: {0} ({1:N1}s)' -f $Name, $watch.Elapsed.TotalSeconds)
}

function Expect-Failure([scriptblock]$Action, [string]$Pattern, [string]$Because) {
    $message = $null
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message) { throw "Expected a refusal and there was none: $Because" }
    if ($message -notmatch $Pattern) { throw "Refused for the wrong reason ($Because): $message" }
}

function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

function New-TestSigner([string]$Id) {
    $ecdsa = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP256'))
    $spki = $ecdsa.ExportSubjectPublicKeyInfo()
    return [pscustomobject]@{ Id = $Id; Ecdsa = $ecdsa; Spki = $spki; Line = "$Id $([Convert]::ToBase64String($spki))" }
}

function New-Keys($Signers) {
    $path = Join-Path $fixture 'keys.txt'
    New-Item -ItemType Directory -Path $fixture -Force | Out-Null
    [IO.File]::WriteAllText($path, (($Signers | ForEach-Object { $_.Line }) -join "`n") + "`n")
    return (Read-OtaKeys $path)
}

# A public identity as libsignal writes it: 33 bytes, the first of which is 5.
function New-TestIdentityKey {
    $bytes = [byte[]]::new(33)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $bytes[0] = 5
    return [Convert]::ToBase64String($bytes)
}

function New-TestDeviceId { return [guid]::NewGuid().ToString() }

function New-TestPayloadText([long]$Sequence = 2, [string]$UserId = $admin, [string]$DeviceId = '638b9ff1-8f90-428c-91b7-e1f8ce4d78a0', [string]$Fingerprint = ('ab' * 32)) {
    return (New-AdminPayloadText $Sequence $UserId $DeviceId $Fingerprint)
}

function New-TestEnvelope($Signer, [string]$PayloadText, [string]$KeyId = $null, [switch]$WithoutDomain, [byte[]]$Domain = $null) {
    if (-not $KeyId) { $KeyId = $Signer.Id }
    $bytes = [Text.Encoding]::UTF8.GetBytes($PayloadText)
    $prefix = if ($WithoutDomain) { [byte[]]@() } elseif ($Domain) { $Domain } else { $script:AdminDomain }
    $signature = $Signer.Ecdsa.SignData([byte[]]($prefix + $bytes), $script:OtaSha256, $script:OtaDer)
    return '{"v":1,"key":"' + $KeyId + '","payload":"' + (ConvertTo-Base64Url $bytes) + '","sig":"' + (ConvertTo-Base64Url $signature) + '"}'
}

# A web server on the loopback address only, answering every request from $State (Status, Headers, Body).
function Start-TestServer($State) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $runspace = [runspacefactory]::CreateRunspace()
    $runspace.Open()
    $shell = [powershell]::Create()
    $shell.Runspace = $runspace
    $null = $shell.AddScript({
        param($Listener, $State)
        while (-not $State.Stop) {
            try { $client = $Listener.AcceptTcpClient() } catch { break }
            try {
                $stream = $client.GetStream()
                $buffer = [byte[]]::new(8192)
                $received = ''
                while ($received -notmatch "`r`n`r`n" -and $received.Length -lt 65536) {
                    $count = $stream.Read($buffer, 0, $buffer.Length)
                    if ($count -le 0) { break }
                    $received += [Text.Encoding]::ASCII.GetString($buffer, 0, $count)
                }
                $State.Requests = [int]$State.Requests + 1
                $State.Last = $received
                $body = [Text.Encoding]::UTF8.GetBytes([string]$State.Body)
                $head = "HTTP/1.1 $($State.Status) Test`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n"
                foreach ($name in $State.Headers.Keys) { $head += "${name}: $($State.Headers[$name])`r`n" }
                $bytes = [Text.Encoding]::ASCII.GetBytes($head + "`r`n")
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Write($body, 0, $body.Length)
                $stream.Flush()
            } catch { } finally { $client.Close() }
        }
    }).AddArgument($listener).AddArgument($State)
    return [pscustomobject]@{ Port = $listener.LocalEndpoint.Port; Listener = $listener; Shell = $shell; Runspace = $runspace; Handle = $shell.BeginInvoke() }
}

function Stop-TestServer($Server, $State) {
    $State.Stop = $true
    $Server.Listener.Stop()
    try { $null = $Server.Shell.EndInvoke($Server.Handle) } catch { }
    $Server.Shell.Dispose()
    $Server.Runspace.Dispose()
}

try {
    Test-Case 'The fingerprint is the number a phone shows, worked out from the account and the identity key, and anything that is not an identity is refused' {
        # A real pair: the admin's identity as the relay held it, and the number its phone showed (checked by eye against the screen).
        $key = 'BXorfk3NvS3T9rC5L0P/E27s6I89dF0b/4MxzMSrjPQX'
        $fingerprint = Get-AdminFingerprint '98ad3c75-0127-422d-9a08-540edb9d3301' $key
        Assert-That ($fingerprint -ceq '7b0c40727d9b7de86ec820ef52db3e8a0efea1644cace6c2e3ed64a441520ece') 'The fingerprint of a known identity is not the number its phone showed.'
        $bytes = [System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes("98ad3c75-0127-422d-9a08-540edb9d3301:$key"))
        Assert-That ($fingerprint -ceq (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')) 'The fingerprint is not the SHA-256 of "account:key".'
        Assert-That ((Format-AdminSafetyNumber $fingerprint) -ceq '7B0C4072 7D9B7DE8 6EC820EF 52DB3E8A 0EFEA164 4CACE6C2 E3ED64A4 41520ECE') 'The number is not written the way the phone writes it (8 groups of 8, upper case).'
        $user = '98ad3c75-0127-422d-9a08-540edb9d3301'
        Expect-Failure { Get-AdminFingerprint $user.ToUpperInvariant() $key } 'lower-case UUID' 'an account written in capitals'
        Expect-Failure { Get-AdminFingerprint 'not-an-account' $key } 'lower-case UUID' 'something that is not an account'
        Expect-Failure { Get-AdminFingerprint $user 'not base64!' } 'not base64' 'text that is not base64'
        Expect-Failure { Get-AdminFingerprint $user ([Convert]::ToBase64String([byte[]]@(5) * 32)) } '33-byte' 'a key of 32 bytes'
        Expect-Failure { Get-AdminFingerprint $user ([Convert]::ToBase64String(([byte[]]@(4) + [byte[]]@(1) * 32))) } '33-byte' 'a key that is not marked as a public identity'
        Expect-Failure { Get-AdminFingerprint $user ($key.TrimEnd('=') + '=') } '33-byte|not base64' 'a key that is not in the canonical form'
        Expect-Failure { Format-AdminSafetyNumber 'abc' } 'Not a fingerprint' 'a short fingerprint'
    }

    Test-Case 'The owner has to type the number of the phone in hand: all of it, or the first and the last group, and nothing else passes' {
        $fingerprint = '7b0c40727d9b7de86ec820ef52db3e8a0efea1644cace6c2e3ed64a441520ece'
        foreach ($good in @('7B0C4072 7D9B7DE8 6EC820EF 52DB3E8A 0EFEA164 4CACE6C2 E3ED64A4 41520ECE', $fingerprint, '7b0c4072-7d9b7de8-6ec820ef-52db3e8a-0efea164-4cace6c2-e3ed64a4-41520ece',
                '  7B0C4072   41520ECE  ', '7b0c407241520ece', "7B0C4072`t41520ECE")) {
            Confirm-AdminPhoneNumber $fingerprint $good
        }
        $other = '7B0C4072 7D9B7DE8 6EC820EF 52DB3E8A 0EFEA164 4CACE6C2 E3ED64A4 41520ECF'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint $other } 'not the number of the identity' 'a full number that differs in its last character'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '7B0C4073 41520ECE' } 'not the number of the identity' 'a different first group'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '7B0C4072 41520ECF' } 'not the number of the identity' 'a different last group'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '41520ECE 7B0C4072' } 'not the number of the identity' 'the groups the wrong way round'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '7B0C4072' } 'Type the safety number' 'only the first group'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '7B0C4072 7D9B7DE8 6EC820EF 52DB3E8A' } 'Type the safety number' 'half of the number'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint '' } 'Type the safety number' 'nothing'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint 'HANU1303 41520ECE' } 'Type the safety number' 'letters a safety number never has'
        Expect-Failure { Confirm-AdminPhoneNumber $fingerprint ($fingerprint.Substring(0, 63) + 'f') } 'not the number of the identity' 'a number that differs in one character of the middle'
    }

    Test-Case 'The app''s own sources are where the floor and the built-in pin are read from' {
        Assert-That ($build.Floor -ge 1 -and $build.UserId -ceq '98ad3c75-0127-422d-9a08-540edb9d3301') 'The floor or the account was not read as the app declares them.'
        Assert-That ($build.DeviceId -cmatch $script:AdminUuidPattern -and $build.Fingerprint -cmatch $script:AdminFingerprintPattern -and $build.Origin -match '^https://.*/$') 'The pin was not read as the app declares it.'
        $broken = Join-Path $fixture 'broken'
        New-Item -ItemType Directory -Path $broken -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $broken 'a.java'), 'nothing here')
        Expect-Failure { Get-AdminBuild (Join-Path $broken 'a.java') $onboardingJava } 'BUILD_SEQUENCE_FLOOR' 'a source without the floor'
        Expect-Failure { Get-AdminBuild $identityJava (Join-Path $broken 'a.java') } 'built-in pin' 'a source without the pin'
    }

    Test-Case 'The payload is exactly what the app reads, and every field the app would refuse is refused here first' {
        $good = ConvertFrom-OtaJson (New-TestPayloadText 2)
        Assert-AdminPayload $good $admin
        Assert-That ((New-TestPayloadText 2) -ceq ('{"schema":1,"sequence":2,"userId":"' + $admin + '","deviceId":"638b9ff1-8f90-428c-91b7-e1f8ce4d78a0","fingerprint":"' + ('ab' * 32) + '"}')) 'The payload is not written in the fixed form.'
        $bad = [ordered]@{
            'another account'           = @{ Text = (New-TestPayloadText 2 '11111111-2222-3333-4444-555555555555'); Pattern = 'account other than' }
            'an upper-case account'     = @{ Text = (New-TestPayloadText 2 $admin.ToUpperInvariant()); Pattern = 'userId' }
            'sequence zero'             = @{ Text = (New-TestPayloadText 0); Pattern = 'sequence' }
            'sequence over the cap'     = @{ Text = (New-TestPayloadText ($script:AdminMaxSequence + 1)); Pattern = 'sequence' }
            'an upper-case device'      = @{ Text = (New-TestPayloadText 2 $admin '638B9FF1-8F90-428C-91B7-E1F8CE4D78A0'); Pattern = 'deviceId' }
            'a short device'            = @{ Text = (New-TestPayloadText 2 $admin '638b9ff1-8f90'); Pattern = 'deviceId' }
            'an upper-case fingerprint' = @{ Text = (New-TestPayloadText 2 $admin '638b9ff1-8f90-428c-91b7-e1f8ce4d78a0' ('AB' * 32)); Pattern = 'fingerprint' }
            'a short fingerprint'       = @{ Text = (New-TestPayloadText 2 $admin '638b9ff1-8f90-428c-91b7-e1f8ce4d78a0' ('ab' * 31)); Pattern = 'fingerprint' }
            'another schema'            = @{ Text = ((New-TestPayloadText 2) -replace '"schema":1', '"schema":2'); Pattern = 'schema' }
            'an extra field'            = @{ Text = ((New-TestPayloadText 2) -replace '\}$', ',"expiresAt":1}'); Pattern = 'exactly' }
            'a missing field'           = @{ Text = ((New-TestPayloadText 2) -replace ',"fingerprint":"[0-9a-f]+"', ''); Pattern = 'exactly' }
            'a sequence as text'        = @{ Text = ((New-TestPayloadText 2) -replace '"sequence":2', '"sequence":"2"'); Pattern = 'sequence' }
        }
        foreach ($name in $bad.Keys) {
            $payload = ConvertFrom-OtaJson $bad[$name].Text
            Expect-Failure { Assert-AdminPayload $payload $admin } $bad[$name].Pattern "a payload with $name"
        }
        Expect-Failure { ConvertFrom-OtaJson ((New-TestPayloadText 2) -replace '"sequence":2', '"sequence":2,"sequence":3') } 'written twice' 'a number given twice'
        Expect-Failure { ConvertFrom-OtaJson ((New-TestPayloadText 2) -replace '"sequence":2', '"sequence":02') } 'not valid JSON' 'a number with a leading zero'
        Assert-AdminPayload (ConvertFrom-OtaJson (New-TestPayloadText $script:AdminMaxSequence)) $admin
        Assert-AdminPayload (ConvertFrom-OtaJson (New-TestPayloadText 1)) $admin
    }

    Test-Case 'A signed certificate is read back exactly, and a changed, foreign, unprefixed or settings-signed one is refused' {
        $owner = New-TestSigner 'a1'; $stranger = New-TestSigner 'a1'
        $keys = New-Keys @($owner)
        $envelope = New-TestEnvelope $owner (New-TestPayloadText 7)
        $read = Read-AdminEnvelope $envelope $keys $admin
        Assert-That ($read.Key -ceq 'a1' -and $read.Payload.sequence -eq 7 -and $read.Payload.userId -ceq $admin) 'A good certificate was not read back.'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $stranger (New-TestPayloadText 7)) $keys $admin } 'does not verify' 'a certificate signed by another key with the same id'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $owner (New-TestPayloadText 7) -WithoutDomain) $keys $admin } 'does not verify' 'a signature without the domain prefix'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $owner (New-TestPayloadText 7) -Domain $script:OtaDomain) $keys $admin } 'does not verify' 'a signature made for the settings file'
        $other = New-TestEnvelope $owner (New-TestPayloadText 8)
        $forged = $envelope -replace '"payload":"[^"]+"', ('"payload":"' + [regex]::Match($other, '"payload":"([^"]+)"').Groups[1].Value + '"')
        Expect-Failure { Read-AdminEnvelope $forged $keys $admin } 'does not verify' 'a payload that does not belong to the signature'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $owner (New-TestPayloadText 7) 'unknown') $keys $admin } 'does not trust' 'an unknown key'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $owner (New-TestPayloadText 7) 'A1') $keys $admin } 'does not trust' 'a key id in another case'
        Expect-Failure { Read-AdminEnvelope (New-TestEnvelope $owner (New-TestPayloadText 7 '11111111-2222-3333-4444-555555555555')) $keys $admin } 'account other than' 'a certificate for another account'
        foreach ($shape in @(($envelope -replace '\}$', ',"extra":1}'), ($envelope -replace '"v":1', '"v":2'), $envelope.Replace('{"v"', '{ "v"'), $envelope.Replace('"', "'"), "$envelope{}", '', '{}', 'not json')) {
            Expect-Failure { Read-AdminEnvelope $shape $keys $admin } 'shape' "a file with another shape: $($shape.Substring(0, [Math]::Min(30, $shape.Length)))"
        }
        Expect-Failure { Read-AdminEnvelope ($envelope + (' ' * $script:AdminMaxEnvelope)) $keys $admin } 'too large' 'a certificate over the size limit'
        Assert-That ((Read-AdminEnvelope ($envelope + "`n") $keys $admin).Payload.sequence -eq 7) 'A trailing line break is fine.'
        $two = New-Keys @($owner, (New-TestSigner 'a2'))
        Assert-That ((Read-AdminEnvelope $envelope $two $admin).Key -ceq 'a1') 'A second listed key does not stop the first.'
    }

    Test-Case 'The committed certificate is the one this build of the app stands for, verified under the keys the app embeds' {
        $keys = Read-OtaKeys (Join-Path $root 'android\app\src\main\res\raw\admin_keys.txt')
        Assert-That ($keys.Count -ge 1 -and $keys.Count -le 3) 'The app has to embed between one and three admin keys.'
        $settingsKeys = Read-OtaKeys (Join-Path $root 'android\app\src\main\res\raw\ota_keys.txt')
        foreach ($id in $keys.Keys) {
            foreach ($other in $settingsKeys.Keys) { Assert-That (-not [Linq.Enumerable]::SequenceEqual([byte[]]$keys[$id], [byte[]]$settingsKeys[$other])) 'The settings key must not also be an admin authority key.' }
        }
        $committedPath = Join-Path $root 'download\ota\admin-identity.json'
        Assert-That (Test-Path -LiteralPath $committedPath) 'The certificate this build stands for is not committed.'
        $committed = Read-AdminEnvelope (Get-Content -LiteralPath $committedPath -Raw) $keys $admin
        Assert-That ([long]$committed.Payload.sequence -eq $build.Floor) 'The build''s floor is not the committed certificate''s sequence.'
        Assert-That ($committed.Payload.deviceId -ceq $build.DeviceId -and $committed.Payload.fingerprint -ceq $build.Fingerprint) 'The committed certificate does not name the identity built into the app.'
    }

    Test-Case 'What the website serves is compared with the committed file: reported before a release, required identical after it' {
        $owner = New-TestSigner 'a1'
        $keys = New-Keys @($owner)
        $headers = @{ 'Content-Type' = 'application/json; charset=utf-8'; 'Cache-Control' = 'no-store' }
        $local = New-TestEnvelope $owner (New-TestPayloadText 5)
        $same = Test-AdminLive $local 200 $headers $local $keys $admin
        Assert-That (-not $same.Warn -and $same.Text -match 'already serves') 'The same file is fine before a release.'
        $older = Test-AdminLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayloadText 4)) $keys $admin
        Assert-That (-not $older.Warn -and $older.Text -match 'will publish') 'An older live file is replaced without a fuss.'
        $missing = Test-AdminLive $local 404 $headers 'Not found' $keys $admin
        Assert-That ($missing.Warn -and $missing.Text -match 'Nothing is published') 'Nothing live yet is a warning before a release.'
        $html = Test-AdminLive $local 200 @{ 'Content-Type' = 'text/html'; 'Cache-Control' = 'no-store' } $local $keys $admin
        Assert-That ($html.Warn -and $html.Text -match 'application/json') 'The wrong content type is reported.'
        $cached = Test-AdminLive $local 200 @{ 'Content-Type' = 'application/json'; 'Cache-Control' = 'max-age=3600' } $local $keys $admin
        Assert-That ($cached.Warn -and $cached.Text -match 'no-store') 'A cacheable certificate is reported.'
        $garbage = Test-AdminLive $local 200 $headers '<html>not a certificate</html>' $keys $admin
        Assert-That ($garbage.Warn -and $garbage.Text -match 'not a certificate the app would take') 'Something else than a certificate is reported.'
        $forged = Test-AdminLive $local 200 $headers (New-TestEnvelope (New-TestSigner 'a1') (New-TestPayloadText 900)) $keys $admin
        Assert-That ($forged.Warn -and $forged.Text -match 'verify') 'A forged live certificate is reported, and its number is never counted.'
        Expect-Failure { Test-AdminLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayloadText 6)) $keys $admin } 'checkout is behind' 'a newer certificate that a trusted key signed'
        Expect-Failure { Test-AdminLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayloadText 6)) $keys $admin -RequireIdentical } 'checkout is behind' 'a newer certificate after a release'

        $exact = Test-AdminLive $local 200 $headers $local $keys $admin -RequireIdentical
        Assert-That (-not $exact.Warn -and $exact.Text -match 'exactly') 'The committed file, served, passes after a release.'
        Expect-Failure { Test-AdminLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayloadText 4)) $keys $admin -RequireIdentical } 'other than the committed' 'an older file after the release'
        Expect-Failure { Test-AdminLive $local 404 $headers $local $keys $admin -RequireIdentical } 'Nothing is published' 'a missing file after the release'
        Expect-Failure { Test-AdminLive $local 200 $headers ($local + "`n") $keys $admin -RequireIdentical } 'other than the committed' 'a file with one byte more'
    }

    # ---- the whole tool, in a copy of the repository layout with a key made for this run ----
    foreach ($directory in @('scripts', 'android\app\src\main\java\app\vanishr\android', 'android\app\src\main\res\raw', 'download\ota', '.secrets\azure')) {
        New-Item -ItemType Directory -Path (Join-Path $fixture $directory) -Force | Out-Null
    }
    foreach ($script in @('sign-admin-identity.ps1', 'admin-identity-rules.ps1', 'ota-settings-rules.ps1', 'new-ota-key.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $fixture "scripts\$script") }
    $fixtureIdentityJava = Join-Path $fixture 'android\app\src\main\java\app\vanishr\android\AdminIdentity.java'
    $fixtureOnboardingJava = Join-Path $fixture 'android\app\src\main\java\app\vanishr\android\AdminOnboarding.java'
    Copy-Item -LiteralPath $identityJava -Destination $fixtureIdentityJava
    Copy-Item -LiteralPath $onboardingJava -Destination $fixtureOnboardingJava
    # The copy of the app's source holds what this build stands for (a floor and a built-in pin) as the test chooses, not as the real app has it.
    function Set-Build([long]$Floor, [string]$DeviceId, [string]$Fingerprint) {
        $identity = [regex]::Replace([IO.File]::ReadAllText($fixtureIdentityJava), 'BUILD_SEQUENCE_FLOOR = [0-9]+;', "BUILD_SEQUENCE_FLOOR = $Floor;")
        [IO.File]::WriteAllText($fixtureIdentityJava, $identity, [Text.UTF8Encoding]::new($false))
        $source = [IO.File]::ReadAllText($fixtureOnboardingJava)
        $pattern = '(static final Pin OFFICIAL = new Pin\(\s*"[^"]+",\s*UUID\.fromString\("[0-9a-f-]{36}"\),\s*UUID\.fromString\(")[0-9a-f-]{36}("\),\s*")[0-9a-f]{64}("\);)'
        $changed = [regex]::Replace($source, $pattern, ('${1}' + $DeviceId + '${2}' + $Fingerprint + '${3}'), 'Singleline')
        [IO.File]::WriteAllText($fixtureOnboardingJava, $changed, [Text.UTF8Encoding]::new($false))
        $read = Get-AdminBuild $fixtureIdentityJava $fixtureOnboardingJava
        if ($read.Floor -ne $Floor -or $read.DeviceId -cne $DeviceId -or $read.Fingerprint -cne $Fingerprint) { throw 'The build in the fixture could not be set.' }
    }
    $sign = Join-Path $fixture 'scripts\sign-admin-identity.ps1'
    $newKey = Join-Path $fixture 'scripts\new-ota-key.ps1'
    $output = Join-Path $fixture 'download\ota\admin-identity.json'
    $keysFile = Join-Path $fixture 'android\app\src\main\res\raw\admin_keys.txt'
    function Read-Output { return (Read-AdminEnvelope (Get-Content -LiteralPath $output -Raw) (Read-OtaKeys $keysFile) $admin) }
    $deviceA = New-TestDeviceId; $keyA = New-TestIdentityKey; $fingerprintA = Get-AdminFingerprint $admin $keyA
    $deviceB = New-TestDeviceId; $keyB = New-TestIdentityKey; $fingerprintB = Get-AdminFingerprint $admin $keyB
    $deviceC = New-TestDeviceId; $keyC = New-TestIdentityKey; $fingerprintC = Get-AdminFingerprint $admin $keyC
    function Phone([string]$Fingerprint) { return (Format-AdminSafetyNumber $Fingerprint) }
    Set-Build 1 $deviceA $fingerprintA

    Test-Case 'The admin key is a different key from the settings key: its own folder, restricted to the current user, its own list of public keys' {
        & $newKey -Purpose Admin -KeyId 'ta1' | Out-Null
        $folder = Join-Path $fixture '.secrets\admin-authority'
        Assert-That ((Test-Path -LiteralPath (Join-Path $folder 'admin-ta1.pem')) -and (Test-Path -LiteralPath (Join-Path $folder 'admin-ta1.password.txt'))) 'The private half and its password were not written under the admin authority folder.'
        Assert-That (-not (Test-Path -LiteralPath (Join-Path $fixture '.secrets\ota-signing'))) 'The settings key folder must not be created for an admin key.'
        $list = Get-Content -LiteralPath $keysFile -Raw
        Assert-That ($list -match 'certify the device identity of the official admin account' -and $list -match '(?m)^ta1 [A-Za-z0-9+/=]+$') 'The public half was not listed in admin_keys.txt.'
        Assert-That ((Read-OtaKeys $keysFile).Count -eq 1) 'The list must hold exactly the key that was made.'
        if ($IsWindows) {
            $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
            $others = @((Get-Acl -LiteralPath $folder).Access | Where-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $identity.User.Value })
            Assert-That ((Get-Acl -LiteralPath $folder).AreAccessRulesProtected -and $others.Count -eq 0) 'The admin authority folder is not restricted to the current user.'
            foreach ($file in Get-ChildItem -LiteralPath $folder -File) {
                $fileOthers = @((Get-Acl -LiteralPath $file.FullName).Access | Where-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $identity.User.Value })
                Assert-That ($fileOthers.Count -eq 0) "$($file.Name) can be reached by somebody else."
            }
        }
        Expect-Failure { & $newKey -Purpose Admin -KeyId 'ta1' } 'already listed' 'a key id that is already listed'
    }

    Test-Case 'The first certificate is the identity this build stands for; the number of the phone is asked for before the relay''s is shown, and a wrong one signs nothing' {
        $printed = $null
        $message = $null
        try { & $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber '7B0C4072 41520ECE' 2>&1 | Tee-Object -Variable printed | Out-Null } catch { $message = $_.Exception.Message }
        Assert-That ($message -match 'not the number of the identity') 'A number that is not the relay''s identity must be refused.'
        Assert-That ((@($printed) -join "`n") -notmatch [regex]::Escape((Format-AdminSafetyNumber $fingerprintA).Substring(0, 8))) 'The relay''s number must not be shown before the phone''s has been confirmed.'
        Assert-That (-not (Test-Path -LiteralPath $output) -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing wrote something.'
        $shown = (& $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber (Phone $fingerprintA)) -join "`n"
        Assert-That ($shown -match [regex]::Escape((Format-AdminSafetyNumber $fingerprintA)) -and $shown -match 'sequence 1') 'The tool must say what it signed.'
        $read = Read-Output
        Assert-That ($read.Key -ceq 'ta1' -and $read.Payload.sequence -eq 1 -and $read.Payload.deviceId -ceq $deviceA -and $read.Payload.fingerprint -ceq $fingerprintA -and $read.Payload.userId -ceq $admin) 'The certificate does not say what was confirmed.'
        Assert-That (-not (Test-Path -LiteralPath "$output.new")) 'A temporary file was left behind.'
        & $sign -Check -AppRelease | Out-Null
        Expect-Failure { & $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber (Phone $fingerprintA) } 'already certified' 'the same identity again'
    }

    Test-Case 'A change of phone is certified with the next sequence number, one identity at a time, and the sequence cannot be anything the app would not take' {
        & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber "$($fingerprintB.Substring(0, 8)) $($fingerprintB.Substring(56))" | Out-Null
        $read = Read-Output
        Assert-That ($read.Payload.sequence -eq 2 -and $read.Payload.deviceId -ceq $deviceB -and $read.Payload.fingerprint -ceq $fingerprintB) 'The next certificate must count on.'
        $before = (Get-FileHash -LiteralPath $output).Hash
        Expect-Failure { & $sign -DeviceId $deviceC -IdentityKey $keyC -PhoneSafetyNumber (Phone $fingerprintB) } 'not the number of the identity' 'the number of the previous phone for the next one'
        Expect-Failure { & $sign -DeviceId $deviceC } 'both -DeviceId and -IdentityKey' 'a device without its identity'
        Expect-Failure { & $sign -DeviceId 'NOT-A-DEVICE' -IdentityKey $keyC -PhoneSafetyNumber (Phone $fingerprintC) } 'lower-case UUID' 'a device that is not a UUID'
        Expect-Failure { & $sign -DeviceId $deviceC -IdentityKey ([Convert]::ToBase64String([byte[]]@(5) * 32)) -PhoneSafetyNumber (Phone $fingerprintC) } '33-byte' 'an identity that is not 33 bytes'
        Expect-Failure { & $sign -DeviceId $deviceC -IdentityKey $keyC -PhoneSafetyNumber (Phone $fingerprintC) -Sequence 1000001 } 'between 1 and|ValidateRange|1000000' 'a sequence over the cap'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $before -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing changed something.'
        & $sign -DeviceId $deviceC -IdentityKey $keyC -PhoneSafetyNumber (Phone $fingerprintC) -Sequence 9 | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 9) 'A sequence chosen by hand is written as given.'
        # A number that is not above the committed one is never signed: a phone that has taken 9 would refuse it, and one that has not would be handed an older certificate than the one that is published.
        $nine = (Get-FileHash -LiteralPath $output).Hash
        Expect-Failure { & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) -Sequence 8 } 'not above the committed' 'a number below the committed one'
        Expect-Failure { & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) -Sequence 9 } 'not above the committed' 'the committed number again, for another identity'
        Expect-Failure { & $sign -DeviceId $deviceC -IdentityKey $keyC -PhoneSafetyNumber (Phone $fingerprintC) -Sequence 9 } 'not above the committed' 'the committed number again, for the same identity'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $nine -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing changed something.'
        # The device ID is not covered by the phone's number, so giving it by hand is said out loud, and the number is still asked for first.
        $warned = $null
        & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) -WarningVariable warned -WarningAction SilentlyContinue | Out-Null
        Assert-That ((@($warned) -join ' ') -match 'device ID was given by hand' -and (@($warned) -join ' ') -match 'identity key only') 'Giving the device ID by hand must be warned about.'
        Assert-That ((Read-Output).Payload.sequence -eq 10 -and (Read-Output).Payload.deviceId -ceq $deviceB) 'Going back to an earlier phone is a new certificate with a higher number.'
    }

    Test-Case 'The tool never writes a certificate that a phone of this build would not use' {
        $folder = Split-Path $output -Parent
        Remove-Item -LiteralPath $output -Force
        Set-Build 5 $deviceA $fingerprintA
        Expect-Failure { & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) } 'below the one this build of the app already knows' 'a next number below the floor'
        Set-Build 1 $deviceC $fingerprintC
        Expect-Failure { & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) } 'names another identity than the built-in pin' 'the floor''s own sequence for another identity'
        Assert-That (-not (Test-Path -LiteralPath $output) -and @(Get-ChildItem -LiteralPath $folder -Force).Count -eq 0) 'A refused signing wrote something.'
        Set-Build 1 $deviceA $fingerprintA
        & $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber (Phone $fingerprintA) | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 1) 'Without a committed file the numbering starts at the floor''s own number for the built-in identity.'
    }

    Test-Case 'The check holds the committed file to the build, and cutting an app version needs the two to agree' {
        $shown = (& $sign -Check -AppRelease) -join "`n"
        Assert-That ($shown -match 'sequence 1' -and $shown -notmatch 'newer than') 'The committed certificate of this build passes.'
        # A rotation was certified after the build: fine for a website release, and the app release has to carry it.
        & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) | Out-Null
        $shown = (& $sign -Check) -join "`n"
        Assert-That ($shown -match 'newer than this build' -and $shown -match 'sequence 2') 'A certificate newer than the build is reported, not refused.'
        Expect-Failure { & $sign -Check -AppRelease } 'AdminIdentity.BUILD_SEQUENCE_FLOOR is 1' 'an app version cut before it carries the newest certificate'
        Set-Build 2 $deviceA $fingerprintA
        Expect-Failure { & $sign -Check -AppRelease } 'built-in pin' 'a floor that was raised without the pin'
        Set-Build 2 $deviceB $fingerprintB
        & $sign -Check -AppRelease | Out-Null
        Set-Build 3 $deviceB $fingerprintB
        Expect-Failure { & $sign -Check } 'older than the one this build of the app already knows' 'a committed file older than the floor'
        Set-Build 2 $deviceA $fingerprintA
        Expect-Failure { & $sign -Check } 'no phone would ever use it' 'the floor''s sequence naming another identity'
        Set-Build 1 $deviceA $fingerprintA
        Remove-Item -LiteralPath $output -Force
        Expect-Failure { & $sign -Check -AppRelease } 'none is committed' 'an app version whose certificate is missing'
        Set-Build 0 $deviceA $fingerprintA
        $shown = (& $sign -Check -AppRelease) -join "`n"
        Assert-That ($shown -match 'identity built into the app applies') 'Nothing committed and nothing expected is fine.'
        Set-Build 1 $deviceA $fingerprintA
        & $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber (Phone $fingerprintA) | Out-Null
        $tampered = (Get-Content -LiteralPath $output -Raw) -replace '"sig":"(.)', '"sig":"A'
        [IO.File]::WriteAllText($output, $tampered)
        Expect-Failure { & $sign -Check } 'verify|shape' 'a certificate with a changed signature'
        Expect-Failure { & $sign -DeviceId $deviceB -IdentityKey $keyB -PhoneSafetyNumber (Phone $fingerprintB) } 'verify|shape' 'building on a committed file that was changed'
        Remove-Item -LiteralPath $output -Force
        & $sign -DeviceId $deviceA -IdentityKey $keyA -PhoneSafetyNumber (Phone $fingerprintA) | Out-Null
    }

    Test-Case 'The relay is asked for the admin device in the approved group only, with one read-only query, and what comes back is checked' {
        $endpoint = [ordered]@{ resourceGroup = 'vanishr-dev-rg'; vmName = 'vanishr-test-vm'; hostname = 'example.cloudapp.azure.com' }
        $state = [ordered]@{ subscription = '44027c71-593a-4d51-977b-ab0604cb76eb'; resourceGroup = 'vanishr-dev-rg' }
        $azure = Join-Path $fixture '.secrets\azure'
        $writeScope = { param($Endpoint, $State) [IO.File]::WriteAllText((Join-Path $azure 'endpoint.json'), ($Endpoint | ConvertTo-Json)); [IO.File]::WriteAllText((Join-Path $azure 'deployment.json'), ($State | ConvertTo-Json)) }
        & $writeScope $endpoint $state
        $global:azureRows = @("$admin|$deviceB|$keyB")
        $global:azureCalls = [System.Collections.Generic.List[string]]::new()
        $global:azureQuery = $null
        function az {
            $global:azureCalls.Add(($args -join ' '))
            $scriptArgument = @($args | Where-Object { "$_" -like '@*' })[0]
            if ($scriptArgument) { $global:azureQuery = Get-Content -LiteralPath ([string]$scriptArgument).Substring(1) -Raw }
            if ($global:azureRows -eq 'FAIL') { $global:LASTEXITCODE = 1; return }
            $global:LASTEXITCODE = 0
            return ([pscustomobject]@{ value = @([pscustomobject]@{ message = "Enable succeeded: `n[stdout]`n$($global:azureRows -join "`n")`n[stderr]`n" }) } | ConvertTo-Json -Depth 5)
        }
        & $sign -PhoneSafetyNumber (Phone $fingerprintB) | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 2 -and (Read-Output).Payload.deviceId -ceq $deviceB) 'The certificate must name the device the relay returned.'
        $call = $global:azureCalls -join ' '
        Assert-That ($call -match 'vm run-command invoke' -and $call -match '--resource-group vanishr-dev-rg' -and $call -match '--subscription 44027c71-593a-4d51-977b-ab0604cb76eb' -and $call -match '--name vanishr-test-vm') 'The query must go to the approved group and subscription only.'
        Assert-That ($global:azureQuery -match 'SELECT d.user_id, d.id, d.identity_key FROM devices d JOIN admin_identity p' -and $global:azureQuery -notmatch '(?i)\b(UPDATE|DELETE|INSERT|DROP|ALTER|TRUNCATE)\b') 'The query must be a single read-only SELECT of public columns.'
        Assert-That (@(Get-ChildItem -LiteralPath ([IO.Path]::GetTempPath()) -Filter 'vanishr-admin-read-*.sh').Count -eq 0) 'The temporary query file was left behind.'
        $before = (Get-FileHash -LiteralPath $output).Hash
        $global:azureRows = @("$admin|$deviceB|$keyB", "$admin|$deviceC|$keyC")
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'instead of one' 'two admin devices'
        $global:azureRows = @("11111111-2222-3333-4444-555555555555|$deviceC|$keyC")
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'not the one this app pins' 'another account than the pinned admin'
        $global:azureRows = @('rubbish')
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'instead of one' 'output that is not a row'
        $global:azureRows = 'FAIL'
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'could not be queried' 'an Azure failure'
        & $writeScope ([ordered]@{ resourceGroup = 'other-rg'; vmName = 'vm' }) ([ordered]@{ subscription = '44027c71-593a-4d51-977b-ab0604cb76eb'; resourceGroup = 'other-rg' })
        $global:azureRows = @("$admin|$deviceC|$keyC")
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'approved isolated group' 'another resource group'
        & $writeScope ([ordered]@{ resourceGroup = 'vanishr-dev-rg'; vmName = 'vm' }) ([ordered]@{ subscription = '00000000-0000-0000-0000-000000000000'; resourceGroup = 'vanishr-dev-rg' })
        Expect-Failure { & $sign -PhoneSafetyNumber (Phone $fingerprintC) } 'approved isolated group' 'another subscription'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $before) 'A refused read changed the certificate.'
        Remove-Item -LiteralPath Function:\az
        Remove-Variable -Scope Global -Name azureRows, azureCalls, azureQuery -ErrorAction SilentlyContinue
    }

    # ---- the tool's own requests, against a server on this computer that says whatever the test wants ----
    $served = [hashtable]::Synchronized(@{ Status = 200; Headers = @{}; Body = ''; Stop = $false; Requests = 0; Last = '' })
    $server = Start-TestServer $served
    try {
        $origin = "http://127.0.0.1:$($server.Port)"
        $goodHeaders = @{ 'Content-Type' = 'application/json; charset=utf-8'; 'Cache-Control' = 'no-store' }
        function Serve([int]$Status, $Headers, [string]$Body) { $served.Status = $Status; $served.Headers = $Headers; $served.Body = $Body }
        function Invoke-Signer([hashtable]$Arguments) {
            $warned = $null
            $printed = & $sign @Arguments -Origin $origin -WarningVariable warned -WarningAction SilentlyContinue
            return [pscustomobject]@{ Output = (@($printed) -join "`n"); Warnings = (@($warned) -join "`n") }
        }

        Test-Case 'A release is checked against what is online, and the website is checked after it, over a real connection' {
            $committed = Get-Content -LiteralPath $output -Raw
            $keys = Read-OtaKeys $keysFile
            $sequence = [long](Read-Output).Payload.sequence
            Serve 200 $goodHeaders $committed
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Output -match 'already serves' -and $result.Warnings -eq '') "The identical file passes quietly: $($result.Warnings)"
            Assert-That ($served.Requests -ge 1 -and $served.Last -match 'GET /ota/admin-identity\.json ') 'The tool asked for the certificate file.'
            Serve 404 @{} 'Not found'
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Warnings -match 'Nothing is published') 'Nothing live yet is only a warning before a release.'
            Serve 200 @{ 'Content-Type' = 'text/html'; 'Cache-Control' = 'no-store' } $committed
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Warnings -match 'application/json') 'A wrong content type is reported before a release.'
            $owner = [pscustomobject]@{ Id = 'ta1'; Ecdsa = $null }
            $signerKey = [System.Security.Cryptography.ECDsa]::Create()
            $signerKey.ImportFromEncryptedPem((Get-Content -LiteralPath (Join-Path $fixture '.secrets\admin-authority\admin-ta1.pem') -Raw), (Get-Content -LiteralPath (Join-Path $fixture '.secrets\admin-authority\admin-ta1.password.txt') -Raw).Trim())
            $owner.Ecdsa = $signerKey
            Serve 200 $goodHeaders (New-TestEnvelope $owner (New-AdminPayloadText ($sequence + 1) $admin $deviceC $fingerprintC))
            Expect-Failure { & $sign -Check -Live -Origin $origin } 'checkout is behind' 'a newer certificate of the trusted key on the website'
            Serve 200 $goodHeaders $committed
            $result = Invoke-Signer @{ RequireIdentical = $true }
            Assert-That ($result.Output -match 'exactly the committed certificate' -and $result.Warnings -eq '') 'After the release the committed file is served.'
            Serve 200 $goodHeaders (New-TestEnvelope $owner (New-AdminPayloadText $sequence $admin $deviceC $fingerprintC))
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'other than the committed' 'a different file after the release'
            Serve 404 @{} 'Not found'
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'Nothing is published' 'nothing served after the release'
            Serve 200 @{ 'Content-Type' = 'text/html'; 'Cache-Control' = 'no-store' } $committed
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'application/json' 'the wrong content type after the release'
            Serve 200 $goodHeaders ($committed + "`n")
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'other than the committed' 'a file with one byte more after the release'
            $signerKey.Dispose()
        }
    } finally { Stop-TestServer $server $served }

    Test-Case 'The release scripts check the certificate before they build or stage anything, the website carries it with the right headers, and the verification gate runs these tests' {
        $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'prepare-download.ps1') -Raw
        $settings = $text.IndexOf("sign-ota-settings.ps1') -Check -AppRelease")
        $certificate = $text.IndexOf("sign-admin-identity.ps1') -Check -AppRelease")
        $dependencies = $text.IndexOf('$dependencies = ')
        $staging = $text.IndexOf('$stage = Join-Path $tools')
        Assert-That ($settings -gt 0 -and $certificate -gt $settings -and $dependencies -gt $certificate -and $staging -gt $dependencies) 'prepare-download.ps1 must check the certificate against the build (-AppRelease) before the notices, the build and the stage, so that a forgotten floor or pin costs a message and not a rebuild.'
        foreach ($script in 'sign-admin-identity.ps1', 'admin-identity-rules.ps1', 'test-admin-identity.ps1') { Assert-That ($text.Contains("'scripts/$script'")) "$script is part of the source archive." }
        $site = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'prepare-website.ps1') -Raw
        $siteCheck = $site.IndexOf("sign-admin-identity.ps1') -Check")
        Assert-That ($siteCheck -gt 0 -and $siteCheck -lt $site.IndexOf('New-Item -ItemType Directory') -and $siteCheck -lt $site.IndexOf('distribution-audit-')) 'prepare-website.ps1 must check the certificate before it reads or creates anything.'
        $render = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'render-website.ps1') -Raw
        Assert-That ($render.Contains("sign-admin-identity.ps1') -Check") -and $render.Contains("'ota/admin-identity.json'")) 'render-website.ps1 must check the certificate and carry it into the stage.'
        Assert-That ($site -notmatch 'AppRelease' -and $render -notmatch 'AppRelease') 'Only prepare-download.ps1 may demand that the app was cut with the newest certificate: a website release goes out with the app already on phones.'
        $verify = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify.ps1') -Raw
        Assert-That ($verify.Contains("'test-admin-identity.ps1'") -and $verify.Contains("sign-admin-identity.ps1') -Check")) 'verify.ps1 must run these tests and check the committed certificate.'
        $vercel = Get-Content -LiteralPath (Join-Path $root 'download\vercel.json') -Raw | ConvertFrom-Json
        $rule = @($vercel.headers | Where-Object { $_.source -ceq '/ota/admin-identity.json' })
        Assert-That ($rule.Count -eq 1) 'vercel.json must have exactly one rule for the certificate.'
        $values = @{}; foreach ($header in $rule[0].headers) { $values[$header.key] = $header.value }
        Assert-That ($values['Content-Type'] -ceq 'application/json; charset=utf-8' -and $values['Cache-Control'] -ceq 'no-store' -and $values['X-Robots-Tag'] -ceq 'noindex') 'The certificate must be served as JSON, never cached and not indexed.'
        # The file that is released is a file of the folder that is copied, and no half-written one can travel with it.
        Assert-That (Test-Path -LiteralPath (Join-Path $root 'download\ota\admin-identity.json')) 'The certificate is not in the folder the release copies.'
        Assert-OtaNoLeftoverFile (Join-Path $root 'download\ota')
    }

    Write-Output "Passed $passed admin identity tests. Nothing left this computer (the web server was on the loopback address, and the Azure CLI was replaced by a stand-in), and no phone or real authority key was used."
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $resolved = (Resolve-Path -LiteralPath $fixture).Path
        if ((Split-Path $resolved -Leaf) -notmatch '^vanishr-admin-identity-[a-f0-9]{32}$') { throw 'Refusing cleanup outside the generated test fixture.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
