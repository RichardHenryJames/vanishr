#requires -Version 7.4
<#
Tests the rules and the tool for over-the-air settings without a phone, a network or the real signing key: the rules against the shared cases
(ota\rules-cases.json, which the app's own unit tests run as well), the checks on a signed file, the comparison with what the website serves,
and the whole signing tool in a throwaway copy of the repository layout with a key made for the test.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'ota-settings-rules.ps1')
$javaFile = Join-Path $root 'android\app\src\main\java\app\vanishr\android\RemoteSettings.java'
$known = Get-OtaKnownFeatures $javaFile
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$hour = 3600000L
$passed = 0
$skipped = 0
$skipReason = $null
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('vanishr-ota-settings-' + [guid]::NewGuid().ToString('N'))

function Test-Case([string]$Name, [scriptblock]$Action) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $script:skipReason = $null
    & $Action
    if ($script:skipReason) { $script:skipped++; Write-Output "SKIP: $Name ($($script:skipReason))"; return }
    $script:passed++
    Write-Output ('PASS: {0} ({1:N1}s)' -f $Name, $watch.Elapsed.TotalSeconds)
}

function Skip-Test([string]$Reason) { $script:skipReason = $Reason }

function Expect-Failure([scriptblock]$Action, [string]$Pattern, [string]$Because) {
    $message = $null
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message) { throw "Expected a refusal and there was none: $Because" }
    if ($message -notmatch $Pattern) { throw "Refused for the wrong reason ($Because): $message" }
}

function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

function New-NoticeSource([hashtable]$Changes = @{}) {
    $notice = [ordered]@{ id = 'n1'; title = 'Hello'; body = 'Body'; level = 'info' }
    foreach ($name in $Changes.Keys) { $notice[$name] = $Changes[$name] }
    return [pscustomobject]$notice
}

function Test-Accepts($Notice) { try { $null = ConvertTo-OtaFields $null $Notice $null $known; return $true } catch { return $false } }

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

function New-TestPayload([long]$Sequence = 1, [long]$Issued = ($now - $hour), [long]$Expires = ($now + 10 * $script:OtaDay), $Features = $null) {
    return [ordered]@{ schema = 1; sequence = $Sequence; issuedAt = $Issued; expiresAt = $Expires; features = $(if ($null -eq $Features) { [ordered]@{} } else { $Features }) }
}

function New-TestEnvelope($Signer, $Payload, [string]$KeyId = $null, [switch]$WithoutDomain) {
    if (-not $KeyId) { $KeyId = $Signer.Id }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 8 -Compress))
    $data = if ($WithoutDomain) { $bytes } else { [byte[]]($script:OtaDomain + $bytes) }
    $signature = $Signer.Ecdsa.SignData($data, $script:OtaSha256, $script:OtaDer)
    return '{"v":1,"key":"' + $KeyId + '","payload":"' + (ConvertTo-Base64Url $bytes) + '","sig":"' + (ConvertTo-Base64Url $signature) + '"}'
}

# A web server on the loopback address only, answering every request from $State (Status, Headers, Body), so that the tool's own requests can be
# tested without the internet. $State is shared with the thread that serves, and Requests counts what arrived.
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
    Test-Case 'The rules agree with every shared case, and the signer never accepts what the app refuses' {
        $table = ConvertFrom-OtaJson (Get-Content -LiteralPath (Join-Path $root 'ota\rules-cases.json') -Raw)
        Assert-That (@($table.cases).Count -ge 100) 'The shared cases were not read.'
        # A refusal has to be the tool's own rule speaking: an engine error (a null where an object was expected, a failed conversion) is also
        # an exception, and would make a case "refused" for no reason a rule gives.
        $engineErrors = 'null-valued|cannot be found on this object|Cannot index|You cannot call a method|Cannot convert|is not recognized|Index was outside|Object reference|The term |Cannot bind|Method invocation failed'
        foreach ($case in $table.cases) {
            $accepted = $true
            $why = ''
            try { $null = ConvertTo-OtaSource $case.source $known } catch { $accepted = $false; $why = $_.Exception.Message }
            Assert-That ($accepted -eq $case.signer) "Case '$($case.name)': the rules gave $accepted, the table says $($case.signer)."
            Assert-That ($accepted -or $why -notmatch $engineErrors) "Case '$($case.name)' was refused by an engine error, not by a rule: $why"
            Assert-That ((-not $case.signer) -or $case.app) "Case '$($case.name)': the table lets the signer accept what the app refuses."
        }
    }

    Test-Case 'The size limits sit where the app puts them' {
        Assert-That (Test-Accepts (New-NoticeSource @{ title = 'a' * 60 })) 'A title of 60 characters is allowed.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ title = 'a' * 61 }))) 'A title of 61 characters is not.'
        Assert-That (Test-Accepts (New-NoticeSource @{ body = 'b' * 400 })) 'A body of 400 characters is allowed.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ body = 'b' * 401 }))) 'A body of 401 characters is not.'
        Assert-That (Test-Accepts (New-NoticeSource @{ id = 'i' * 40 })) 'An id of 40 characters is allowed.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ id = 'i' * 41 }))) 'An id of 41 characters is not.'
        $breaks = $script:OtaMaxNewlines
        Assert-That (Test-Accepts (New-NoticeSource @{ body = 'l' + ("`nl" * $breaks) })) "$breaks line breaks are allowed."
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ body = 'l' + ("`nl" * ($breaks + 1)) }))) 'One more is not.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ body = 'x' + ("`n" * 398) + 'y' }))) 'A body that is mostly line breaks is not.'
        Assert-That (Test-Accepts (New-NoticeSource @{ path = '/' + ('a' * 40) })) 'A path segment of 40 characters is allowed.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ path = '/' + ('a' * 41) }))) 'A path segment of 41 is not.'
        Assert-That (Test-Accepts (New-NoticeSource @{ path = '/a/b/c/d' })) 'Four segments are allowed.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ path = '/a/b/c/d/e' }))) 'Five are not.'
        Assert-That (-not (Test-Accepts (New-NoticeSource @{ body = 'a' + [string][char]0xD800 + 'b' }))) 'A lone surrogate is not text.'
        Assert-That (Test-Accepts (New-NoticeSource @{ body = 'a' + [char]::ConvertFromUtf32(0x1F44B) + 'b' })) 'A surrogate pair is an emoji.'
        Assert-That (-not (Test-OtaText 5 10 $false)) 'A number is not text.'
        Assert-That (-not (Test-OtaText $null 10 $false)) 'Nothing is not text.'
    }

    Test-Case 'The key list takes what the app takes: at most three distinct P-256 keys with case-sensitive ids' {
        $one = New-TestSigner 'k1'; $two = New-TestSigner 'k2'; $three = New-TestSigner 'k3'; $four = New-TestSigner 'k4'
        $keys = New-Keys @($one, $two)
        Assert-That ($keys.Count -eq 2 -and $keys.ContainsKey('k1') -and -not $keys.ContainsKey('K1')) 'The list was not read, or ids are not case-sensitive.'
        Assert-That ((New-Keys @($one, $two, $three)).Count -eq 3) 'Three keys are allowed.'
        $path = Join-Path $fixture 'keys.txt'
        foreach ($bad in @(
                (($one, $two, $three, $four | ForEach-Object { $_.Line }) -join "`n"),
                "$($one.Line)`n$($one.Id) $([Convert]::ToBase64String($two.Spki))",
                'k1', 'k1 !!!notbase64', "k5 $([Convert]::ToBase64String([byte[]]::new(40)))", "k`$ $([Convert]::ToBase64String($one.Spki))",
                "$($one.Line) extra", "$('a' * 17) $([Convert]::ToBase64String($one.Spki))")) {
            [IO.File]::WriteAllText($path, $bad)
            Expect-Failure { Read-OtaKeys $path } 'malformed' "a key list of '$($bad.Substring(0, [Math]::Min(30, $bad.Length)))'"
        }
        $p384 = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP384'))
        [IO.File]::WriteAllText($path, "k6 $([Convert]::ToBase64String($p384.ExportSubjectPublicKeyInfo()))")
        Expect-Failure { Read-OtaKeys $path } 'malformed' 'a P-384 key'
        $rsa = [System.Security.Cryptography.RSA]::Create(2048)
        [IO.File]::WriteAllText($path, "k7 $([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()))")
        Expect-Failure { Read-OtaKeys $path } 'malformed' 'an RSA key'
    }

    Test-Case 'The JSON reader is as strict as the app, refuses a name written twice, and keeps text as text' {
        $good = ConvertFrom-OtaJson '{"i":5,"zero":0,"long16":1234567890123456,"s":"2026-10-20","t":"2026-10-20T10:00:00Z","b":true,"n":null,"a":[7],"e":[],"o":{}}'
        Assert-That ($good.i -is [long] -and $good.zero -is [long] -and $good.long16 -is [long]) 'Integers written the way the app reads them are integers.'
        Assert-That ($good.s -is [string] -and $good.s -ceq '2026-10-20' -and $good.t -is [string]) 'Text that looks like a date must stay text.'
        Assert-That ($good.a -is [array] -and $good.a.Count -eq 1 -and $good.e -is [array] -and $good.e.Count -eq 0 -and $good.o -is [pscustomobject] -and $null -eq $good.n) 'Arrays, empty objects and null keep their shape.'
        $notIntegers = ConvertFrom-OtaJson '{"neg":-1,"frac":1.5,"exp":1e3,"long17":12345678901234567}'
        foreach ($name in 'neg', 'frac', 'exp', 'long17') { Assert-That ($notIntegers.$name -is [double]) "'$name' is not an integer the way the app reads one." }
        Assert-That (-not (Test-OtaInteger $notIntegers.neg 1 100)) 'A negative number is not a valid sequence number.'
        $refused = [ordered]@{
            'a name written twice'        = '{"features":{"images":false,"images":true}}'
            'a name twice, nested'        = '{"a":{"b":1,"b":2}}'
            'a block comment'             = '{"a":1 /* c */}'
            'a line comment'              = "{`"a`":1} // c"
            'a trailing comma'            = '{"a":1,}'
            'a trailing comma in a list'  = '{"a":[1,]}'
            'single quotes'               = "{'a':1}"
            'an unquoted name'            = '{a:1}'
            'a leading zero'              = '{"a":01}'
            'a hexadecimal number'        = '{"a":0x10}'
            'NaN'                         = '{"a":NaN}'
            'text after the value'        = '{"a":1} x'
            'two values'                  = '{"a":1}{"b":2}'
            'nothing'                     = ''
            'a lone surrogate escape'     = '{"a":"\ud800"}'
            'nesting deeper than allowed' = (('[' * 40) + (']' * 40))
        }
        foreach ($name in $refused.Keys) { Expect-Failure { ConvertFrom-OtaJson $refused[$name] } 'not valid JSON|written twice' "JSON with $name" }
        Expect-Failure { ConvertFrom-OtaJson $refused['a name written twice'] } 'written twice' 'a switch that is given twice, where the last one would win silently'
        # PowerShell cannot hold two names that differ only in case: it kept one of them, under the first one's spelling and the second one's
        # value, so a kill switch typed in the wrong case was dropped without a word. The object is refused, in either order.
        Expect-Failure { ConvertFrom-OtaJson '{"features":{"Images":false,"images":true}}' } 'two different cases' 'a switch written as Images and images'
        Expect-Failure { ConvertFrom-OtaJson '{"features":{"images":true,"Images":false}}' } 'two different cases' 'a switch written as images and Images'
        Expect-Failure { ConvertFrom-OtaJson '{"a":1,"A":2}' } 'two different cases' 'two names that differ only in case'
        Assert-That ((ConvertFrom-OtaJson '{"a":{"x":1},"b":{"X":2}}').b.X -eq 2) 'Names that differ only in case in two different objects are not a clash.'
        Assert-That ((ConvertFrom-OtaJson '{"v":"\ud83d\udc4b"}').v.Length -eq 2) 'A surrogate pair is one emoji.'
        Assert-That ((ConvertFrom-OtaJson (('[' * 10) + (']' * 10))).Count -eq 1) 'Modest nesting is allowed.'
    }

    Test-Case 'The app''s own source is where the floor is read from, and the committed settings file is not older than it' {
        $fromRules = Get-OtaBuildFloor $javaFile
        $fromTest = [long]([regex]::Match((Get-Content -LiteralPath $javaFile -Raw), 'BUILD_ISSUED_FLOOR\s*=\s*([0-9_]+)L').Groups[1].Value.Replace('_', ''))
        Assert-That ($fromRules -eq $fromTest -and $fromRules -gt $script:OtaEarliest) 'The floor was not read as the app declares it.'
        $committed = Read-OtaEnvelope (Get-Content -LiteralPath (Join-Path $root 'download\ota\settings.json') -Raw) (Read-OtaKeys (Join-Path $root 'android\app\src\main\res\raw\ota_keys.txt')) -IgnoreUnknownKey
        Assert-That ([long]$committed.Payload.issuedAt -ge $fromRules) 'The committed settings file was issued before the floor the app holds files to.'
    }

    Test-Case 'A signed file is read back exactly, and a changed, foreign or unprefixed one is refused' {
        $owner = New-TestSigner 'k1'; $stranger = New-TestSigner 'k1'
        $keys = New-Keys @($owner)
        $envelope = New-TestEnvelope $owner (New-TestPayload 7)
        $read = Read-OtaEnvelope $envelope $keys
        Assert-That ($read.Verified -and $read.Key -ceq 'k1' -and $read.Payload.sequence -eq 7) 'A good file was not read back.'
        Assert-OtaPayload $read.Payload $known $now
        Expect-Failure { Read-OtaEnvelope (New-TestEnvelope $stranger (New-TestPayload 7)) $keys } 'does not verify' 'a file signed by another key with the same id'
        Expect-Failure { Read-OtaEnvelope (New-TestEnvelope $owner (New-TestPayload 7) -WithoutDomain) $keys } 'does not verify' 'a signature without the domain prefix'
        $other = New-TestEnvelope $owner (New-TestPayload 8)
        $forged = $envelope -replace '"payload":"[^"]+"', ('"payload":"' + [regex]::Match($other, '"payload":"([^"]+)"').Groups[1].Value + '"')
        Expect-Failure { Read-OtaEnvelope $forged $keys } 'does not verify' 'a payload that does not belong to the signature'
        Expect-Failure { Read-OtaEnvelope (New-TestEnvelope $owner (New-TestPayload 7) 'unknown') $keys } 'does not trust' 'an unknown key'
        $unknown = Read-OtaEnvelope (New-TestEnvelope $owner (New-TestPayload 7) 'unknown') $keys -IgnoreUnknownKey
        Assert-That ((-not $unknown.Verified) -and $unknown.Payload.sequence -eq 7) 'An unknown key may be read, but never counts as verified.'
        Expect-Failure { Read-OtaEnvelope (New-TestEnvelope $owner (New-TestPayload 7) 'K1') $keys } 'does not trust' 'a key id in another case'
        foreach ($shape in @(($envelope -replace '\}$', ',"extra":1}'), ($envelope -replace '"v":1', '"v":2'), $envelope.Replace('{"v"', '{ "v"'),
                $envelope.Replace('"', "'"), "$envelope{}", '', '{}', 'not json')) {
            Expect-Failure { Read-OtaEnvelope $shape $keys } 'shape' "a file with another shape: $($shape.Substring(0, [Math]::Min(30, $shape.Length)))"
        }
        Expect-Failure { Read-OtaEnvelope ($envelope + (' ' * $script:OtaMaxEnvelope)) $keys } 'too large' 'an envelope over the size limit'
        Assert-That ((Read-OtaEnvelope ($envelope + "`n") $keys).Verified) 'A trailing line break is fine.'
    }

    Test-Case 'The rules about the file as a whole are the app''s' {
        $limits = $script:OtaMaxValidityDays * $script:OtaDay
        Assert-OtaPayload (ConvertFrom-OtaJson ((New-TestPayload 1) | ConvertTo-Json -Compress)) $known $now
        $cases = [ordered]@{
            'expired'              = @{ Payload = (New-TestPayload 1 ($now - 5 * $script:OtaDay) ($now - 1)); Pattern = 'expired' }
            'just expired'         = @{ Payload = (New-TestPayload 1 ($now - 5 * $script:OtaDay) $now); Pattern = 'expired' }
            'dated in the future'  = @{ Payload = (New-TestPayload 1 ($now + $script:OtaClockSkew + $hour) ($now + 10 * $script:OtaDay)); Pattern = 'future' }
            'valid for too long'   = @{ Payload = (New-TestPayload 1 ($now - $hour) ($now - $hour + $limits + 1)); Pattern = 'valid for' }
            'valid for no time'    = @{ Payload = (New-TestPayload 1 ($now - $hour) ($now - $hour)); Pattern = 'valid for' }
            'sequence zero'        = @{ Payload = (New-TestPayload 0); Pattern = 'sequence' }
            'sequence over the cap' = @{ Payload = (New-TestPayload ($script:OtaMaxSequence + 1)); Pattern = 'sequence' }
            'dates before the app' = @{ Payload = (New-TestPayload 1 1000 ($now + $script:OtaDay)); Pattern = 'dates' }
        }
        foreach ($name in $cases.Keys) {
            $payload = ConvertFrom-OtaJson ($cases[$name].Payload | ConvertTo-Json -Compress)
            Expect-Failure { Assert-OtaPayload $payload $known $now } $cases[$name].Pattern "a file that is $name"
        }
        $oldest = ConvertFrom-OtaJson ((New-TestPayload 1 ($now - $hour) ($now - $hour + $limits)) | ConvertTo-Json -Compress)
        Assert-OtaPayload $oldest $known $now
        Assert-OtaPayload (ConvertFrom-OtaJson ((New-TestPayload $script:OtaMaxSequence) | ConvertTo-Json -Compress)) $known $now
        $schema = New-TestPayload 1; $schema.schema = 2
        Expect-Failure { Assert-OtaPayload (ConvertFrom-OtaJson ($schema | ConvertTo-Json -Compress)) $known $now } 'schema' 'a schema the app does not know'
        $bad = New-TestPayload 1; $bad.notice = [ordered]@{ id = 'n1'; title = "He$([char]0x200B)llo"; body = 'Body'; level = 'info' }
        Expect-Failure { Assert-OtaPayload (ConvertFrom-OtaJson ($bad | ConvertTo-Json -Compress)) $known $now } 'title' 'a notice with an invisible character, inside a signed file'
        $nulled = New-TestPayload 1; $nulled.notice = [ordered]@{ id = 'n1'; title = 'Hello'; body = 'Body'; level = 'info'; path = $null }
        Expect-Failure { Assert-OtaPayload (ConvertFrom-OtaJson ($nulled | ConvertTo-Json -Compress)) $known $now } 'null' 'a signed file may not carry null for a field'
        # The app refuses "features": null as malformed, where a null notice or update means there is none.
        $noFeatures = New-TestPayload 1; $noFeatures.features = $null
        Expect-Failure { Assert-OtaPayload (ConvertFrom-OtaJson ($noFeatures | ConvertTo-Json -Compress)) $known $now } 'features' 'a signed file whose features is null'
        $absent = New-TestPayload 1; $absent.Remove('features')
        Assert-OtaPayload (ConvertFrom-OtaJson ($absent | ConvertTo-Json -Compress)) $known $now
        $nullParts = New-TestPayload 1; $nullParts.notice = $null; $nullParts.update = $null
        Assert-OtaPayload (ConvertFrom-OtaJson ($nullParts | ConvertTo-Json -Compress)) $known $now
    }

    Test-Case 'Before a release only a newer file on the website stops it; after a release the website must serve exactly the committed file' {
        $owner = New-TestSigner 'k1'; $second = New-TestSigner 'k2'
        $keys = New-Keys @($owner, $second)
        $headers = @{ 'Content-Type' = 'application/json; charset=utf-8'; 'Cache-Control' = 'no-store'; 'X-Robots-Tag' = 'noindex' }
        $local = New-TestEnvelope $owner (New-TestPayload 5)
        $same = Test-OtaLive $local 200 $headers $local $keys $known $now
        Assert-That ($same.Identical -and $same.LiveSequence -eq 5 -and $same.Warnings.Count -eq 0) 'The identical file was not recognised.'
        $older = Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 4)) $keys $known $now
        Assert-That ((-not $older.Identical) -and $older.Message -match 'replaces it' -and $older.Warnings.Count -eq 0) 'An older live file is simply replaced.'
        $otherKey = Test-OtaLive $local 200 $headers (New-TestEnvelope $second (New-TestPayload 99 ($now - 2 * $hour))) $keys $known $now
        Assert-That ((-not $otherKey.Identical) -and $otherKey.Warnings.Count -eq 0) 'Sequence numbers are counted per key: an earlier file of another key is replaced whatever its number.'

        # What stops a release: a file that a trusted key signed and that a phone may already hold.
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 6)) $keys $known $now } 'not below' 'a live file of the same key with a higher sequence'
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 5 ($now - 2 * $hour))) $keys $known $now } 'not below' 'a different file with the same sequence'
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 3 $now)) $keys $known $now } 'issued later' 'a live file issued later, even with a lower sequence'
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $second (New-TestPayload 1 $now)) $keys $known $now } 'issued later' 'a live file of the other key that was issued later'
        # -IgnoreFutureIssueTime sets aside only what a phone with a right clock refuses (dated more than 36 hours ahead), and only when asked.
        $nearLive = New-TestEnvelope $owner (New-TestPayload 3 ($now + 3 * $hour) ($now + 3 * $hour + 10 * $script:OtaDay))
        $farLive = New-TestEnvelope $owner (New-TestPayload 3 ($now + 40 * $hour) ($now + 40 * $hour + 10 * $script:OtaDay))
        Expect-Failure { Test-OtaLive $local 200 $headers $nearLive $keys $known $now -IgnoreFutureIssueTime } 'issued later' 'a live file dated 3 hours ahead, with the switch'
        Expect-Failure { Test-OtaLive $local 200 $headers $farLive $keys $known $now } 'issued later' 'a live file dated 40 hours ahead, without the switch'
        $setAside = Test-OtaLive $local 200 $headers $farLive $keys $known $now -IgnoreFutureIssueTime
        Assert-That ((-not $setAside.Identical) -and (($setAside.Warnings -join ' ') -match 'dated more than 36 hours ahead') -and $setAside.Message -match 'replaces it') 'A live file dated 40 hours ahead is set aside, reported and replaced when asked.'
        Assert-That (($setAside.Warnings -join ' ') -match 'until real time reaches' -and ($setAside.Warnings -join ' ') -match 'Publish the committed file before then') 'The warning must say until when a phone refuses the file, so that the release is made before.'
        # A file that is set aside is still held to the sequence rule of its key: a phone that does take it holds its number as the floor.
        $farSameKey = New-TestEnvelope $owner (New-TestPayload 9 ($now + 40 * $hour) ($now + 40 * $hour + 10 * $script:OtaDay))
        Expect-Failure { Test-OtaLive $local 200 $headers $farSameKey $keys $known $now -IgnoreFutureIssueTime } 'not below' 'a set-aside live file of the same key whose sequence is not below the committed one'
        $farOtherKey = New-TestEnvelope $second (New-TestPayload 9 ($now + 40 * $hour) ($now + 40 * $hour + 10 * $script:OtaDay))
        Assert-That ((Test-OtaLive $local 200 $headers $farOtherKey $keys $known $now -IgnoreFutureIssueTime).Message -match 'replaces it') 'Sequence numbers are per key: another key''s number does not stop it.'

        # What does not: nothing that can be believed. A file anybody who can change the website could have written is never counted.
        $forged = Test-OtaLive $local 200 $headers (New-TestEnvelope (New-TestSigner 'k1') (New-TestPayload 900000 $now)) $keys $known $now
        Assert-That ($forged.Warnings.Count -eq 1 -and $forged.Warnings[0] -match 'cannot be checked' -and $forged.Warnings[0] -match 'verify' -and $null -eq $forged.LiveSequence) 'A forged live file must be reported and not counted.'
        $retired = Test-OtaLive $local 200 $headers (New-TestEnvelope (New-TestSigner 'k9') (New-TestPayload 900000 $now)) $keys $known $now
        Assert-That ($retired.Warnings.Count -eq 1 -and $retired.Warnings[0] -match 'does not trust') 'A file of a key the app does not list cannot be counted.'
        foreach ($garbage in @('<html>not settings</html>', '', '{}')) {
            $result = Test-OtaLive $local 200 $headers $garbage $keys $known $now
            Assert-That ($result.Warnings.Count -eq 1 -and $result.Warnings[0] -match 'cannot be checked' -and -not $result.Identical) "Something else than a settings file ('$garbage') is reported."
        }
        $expired = Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 4 ($now - 5 * $script:OtaDay) ($now - 1))) $keys $known $now
        Assert-That ($expired.Warnings.Count -eq 1 -and $expired.Warnings[0] -match 'expired' -and $expired.Message -match 'replaces it') 'A live file that has expired is reported and replaced.'
        $missing = Test-OtaLive $local 404 $headers 'Not found' $keys $known $now
        Assert-That ($missing.Warnings.Count -eq 1 -and $missing.Warnings[0] -match '404' -and $missing.Message -match 'Nothing usable') 'Nothing live yet is no reason to stop.'
        foreach ($broken in @(@{ 'Content-Type' = 'text/html' }, @{ 'Content-Type' = 'application/json-seq' }, @{ 'Content-Type' = 'application/jsonp' }, @{ 'Content-Type' = 'text/json' },
                @{ 'Cache-Control' = 'max-age=3600' }, @{ 'X-Robots-Tag' = 'all' })) {
            $wrong = $headers.Clone(); foreach ($name in $broken.Keys) { $wrong[$name] = $broken[$name] }
            $reported = Test-OtaLive $local 200 $wrong $local $keys $known $now
            Assert-That ($reported.Warnings.Count -eq 1 -and $reported.Warnings[0] -match 'content type|no-store|noindex') "Headers of $($broken.Keys) are reported."
        }
        foreach ($fine in @('application/json', 'Application/JSON; charset=utf-8', 'application/json;charset=UTF-8')) {
            $accepted = $headers.Clone(); $accepted['Content-Type'] = $fine
            Assert-That ((Test-OtaLive $local 200 $accepted $local $keys $known $now).Warnings.Count -eq 0) "A content type of '$fine' is what the app takes."
        }
        $noHeader = $headers.Clone(); $noHeader.Remove('X-Robots-Tag')
        Assert-That ((Test-OtaLive $local 200 $noHeader $local $keys $known $now).Warnings[0] -match 'noindex') 'A missing header is reported.'

        # After a release: exactly the committed file, served as the app expects, and in force.
        $exact = Test-OtaLive $local 200 $headers $local $keys $known $now -RequireIdentical
        Assert-That ($exact.Identical -and $exact.Message -match 'in force' -and $exact.Warnings.Count -eq 0) 'The committed file, served, passes.'
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 4)) $keys $known $now -RequireIdentical } 'other than the committed' 'an older file served after the release'
        Expect-Failure { Test-OtaLive $local 200 $headers (New-TestEnvelope $owner (New-TestPayload 6)) $keys $known $now -RequireIdentical } 'other than the committed' 'a newer file served after the release'
        Expect-Failure { Test-OtaLive $local 404 $headers $local $keys $known $now -RequireIdentical } '404' 'a missing file after the release'
        Expect-Failure { Test-OtaLive $local 200 $headers ($local + "`n") $keys $known $now -RequireIdentical } 'other than the committed' 'a file with one byte more'
        foreach ($broken in @(@{ 'Content-Type' = 'text/html' }, @{ 'Content-Type' = 'application/jsonp' }, @{ 'Cache-Control' = 'max-age=3600' }, @{ 'X-Robots-Tag' = 'all' })) {
            $wrong = $headers.Clone(); foreach ($name in $broken.Keys) { $wrong[$name] = $broken[$name] }
            Expect-Failure { Test-OtaLive $local 200 $wrong $local $keys $known $now -RequireIdentical } 'content type|no-store|noindex' "headers of $($broken.Keys) after the release"
        }
        $lapsed = New-TestEnvelope $owner (New-TestPayload 5 ($now - 5 * $script:OtaDay) ($now - 1))
        Expect-Failure { Test-OtaLive $lapsed 200 $headers $lapsed $keys $known $now -RequireIdentical } 'expired' 'the committed file served after it expired'
        Expect-Failure { Test-OtaLive (New-TestEnvelope (New-TestSigner 'k1') (New-TestPayload 5)) 200 $headers $local $keys $known $now } 'verify' 'a committed file that is not signed by the key'
    }

    # ---- the whole tool, in a copy of the repository layout with a key made for this run ----
    foreach ($directory in @('scripts', 'android\app\src\main\java\app\vanishr\android', 'android\app\src\main\res\raw', 'ota', 'download\ota')) {
        New-Item -ItemType Directory -Path (Join-Path $fixture $directory) -Force | Out-Null
    }
    foreach ($script in @('sign-ota-settings.ps1', 'ota-settings-rules.ps1', 'new-ota-key.ps1', 'render-website.ps1', 'prepare-website.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $fixture "scripts\$script") }
    $fixtureJava = Join-Path $fixture 'android\app\src\main\java\app\vanishr\android\RemoteSettings.java'
    Copy-Item -LiteralPath $javaFile -Destination $fixtureJava
    # The copy of the app's source in the fixture holds files to a floor of the test's choosing, so that the tests of other things do not depend on the real one.
    function Set-Floor([long]$Value) {
        $source = [IO.File]::ReadAllText($fixtureJava)
        $changed = [regex]::Replace($source, 'BUILD_ISSUED_FLOOR = [0-9_]+L;', "BUILD_ISSUED_FLOOR = ${Value}L;")
        if ($changed -ceq $source -and (Get-OtaBuildFloor $fixtureJava) -ne $Value) { throw 'The floor in the fixture could not be set.' }
        [IO.File]::WriteAllText($fixtureJava, $changed, [Text.UTF8Encoding]::new($false))
    }
    Set-Floor $script:OtaEarliest
    $sign = Join-Path $fixture 'scripts\sign-ota-settings.ps1'
    $newKey = Join-Path $fixture 'scripts\new-ota-key.ps1'
    $sourceFile = Join-Path $fixture 'ota\settings.source.json'
    $output = Join-Path $fixture 'download\ota\settings.json'
    $keysFile = Join-Path $fixture 'android\app\src\main\res\raw\ota_keys.txt'
    function Write-Source([string]$Json) { [IO.File]::WriteAllText($sourceFile, $Json) }
    function Read-Output { return (Read-OtaEnvelope (Get-Content -LiteralPath $output -Raw) (Read-OtaKeys $keysFile) -IgnoreUnknownKey) }
    function Sign-As($KeyId, [long]$Sequence, $Payload) {
        $signer = [System.Security.Cryptography.ECDsa]::Create()
        $signer.ImportFromEncryptedPem((Get-Content -LiteralPath (Join-Path $fixture ".secrets\ota-signing\ota-$KeyId.pem") -Raw), (Get-Content -LiteralPath (Join-Path $fixture ".secrets\ota-signing\ota-$KeyId.password.txt") -Raw).Trim())
        return (New-TestEnvelope ([pscustomobject]@{ Id = $KeyId; Ecdsa = $signer }) $Payload)
    }
    $emptySource = '{"features":{"images":true,"photos":true,"groups":true},"notice":null,"update":null}'
    Write-Source $emptySource

    Test-Case 'The tool makes a key, signs the settings, counts the sequence up and checks its own file' {
        & $newKey -KeyId 'tk1' | Out-Null
        Assert-That (Test-Path -LiteralPath (Join-Path $fixture '.secrets\ota-signing\ota-tk1.pem')) 'The private key was not written.'
        Assert-That ((Get-Content -LiteralPath $keysFile -Raw) -cnotmatch '-----BEGIN') 'The key list must hold public keys only.'
        & $sign -Offline | Out-Null
        $first = Read-Output
        Assert-That ($first.Key -ceq 'tk1' -and $first.Payload.sequence -eq 1 -and $first.Verified) 'The first file is sequence 1 and verified.'
        Assert-That ($first.Payload.features.images -eq $true) 'The switches were carried over.'
        $summary = & $sign -Check
        Assert-That (($summary -join ' ') -match 'sequence 1') 'The check reports the file.'
        & $sign -Offline | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 2) 'The next file counts up.'
    }

    Test-Case 'A notice and an update hint are written as given, and a mistake changes nothing' {
        Write-Source ('{"features":{"images":false},"notice":{"id":"2026-10-20","title":"Heads up","body":"Line one\nLine two","level":"important","path":"/security/","build":"play","minVersionCode":40,"maxVersionCode":50},' +
            '"update":{"latestVersionCode":43,"latestVersionName":"0.6.2","minSupportedVersionCode":40}}')
        & $sign -Offline | Out-Null
        $payload = (Read-Output).Payload
        Assert-That ($payload.sequence -eq 3 -and $payload.features.images -eq $false) 'The switch was not carried over.'
        Assert-That ($payload.notice.id -ceq '2026-10-20' -and $payload.notice.body -ceq "Line one`nLine two" -and $payload.notice.path -ceq '/security/' -and $payload.notice.build -ceq 'play' -and $payload.notice.minVersionCode -eq 40) 'The notice was not written as given.'
        Assert-That ($payload.update.latestVersionName -ceq '0.6.2' -and $payload.update.minSupportedVersionCode -eq 40) 'The update hint was not written as given.'
        $shown = (& $sign -Check) -join "`n"
        Assert-That ($shown -match 'Notice ''2026-10-20''' -and $shown -match 'images' -and $shown -match 'Update hint') 'The check does not show what is in force.'
        $before = (Get-FileHash -LiteralPath $output).Hash
        $mistakes = [ordered]@{
            '{"features":{"imagess":false}}'                                                                                                  = 'Unknown feature'
            '{"features":{"images":"no"}}'                                                                                                    = 'must be true or false'
            '{"features":{"images":false,"images":true}}'                                                                                     = 'written twice'
            '{"features":{"images":true},"features":{"images":false}}'                                                                        = 'written twice'
            '{"features":{} /* a comment */}'                                                                                                 = 'not valid JSON'
            '{"features":{"images":false},}'                                                                                                  = 'not valid JSON'
            '{"extra":1}'                                                                                                                     = 'Unknown field'
            '{"notice":{"id":"n1","title":"Hi","body":"Body","level":"info","path":"/a.b"}}'                                                  = 'notice\.path'
            '{"notice":{"id":"n1","title":"Hi","body":"Body","level":"info","path":"/vanishr-0.6.2.apk"}}'                                    = 'notice\.path'
            '{"notice":{"id":"n1","title":"Hi","body":"Body\u200b","level":"info"}}'                                                          = 'notice\.body'
            '{"notice":{"id":"n1","title":"Hi","body":"Body","level":"info","minVersionCode":9,"maxVersionCode":1}}'                          = 'minVersionCode'
            '{"notice":{"id":"n1","title":"Hi","body":"Body","level":"info","colour":"red"}}'                                                 = 'Unknown notice field'
            '{"update":{"latestVersionCode":43,"latestVersionName":"0.6","minSupportedVersionCode":0}}'                                       = 'latestVersionName'
            '{"update":{"latestVersionCode":3000000000,"latestVersionName":"0.6.2","minSupportedVersionCode":0}}'                             = 'latestVersionCode'
            '{"notice":'                                                                                                                      = 'not valid JSON'
            'not json'                                                                                                                        = 'not valid JSON'
        }
        foreach ($mistake in $mistakes.Keys) {
            Write-Source $mistake
            Expect-Failure { & $sign -Offline } $mistakes[$mistake] "a source of $mistake"
            Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $before) "A refused source changed the signed file: $mistake"
            Assert-That (-not (Test-Path -LiteralPath "$output.new")) 'A refused source left a temporary file.'
        }
        Write-Source $emptySource
    }

    Test-Case 'A key id in the wrong case is refused instead of writing a file no phone would accept' {
        Expect-Failure { & $sign -Offline -KeyId 'TK1' } 'case-sensitive' 'a key id in capitals'
        Expect-Failure { & $sign -Offline -KeyId 'nokey' } 'not in the list' 'an unlisted key id'
    }

    Test-Case 'The signer reports a file that is about to expire, one that has expired and one signed by a key the app does not list' {
        $payload = New-TestPayload 9 ($now - 10 * $script:OtaDay) ($now + 3 * $script:OtaDay)
        [IO.File]::WriteAllText($output, (Sign-As 'tk1' 9 $payload))
        $warnings = $null
        & $sign -Check -WarningVariable warnings -WarningAction SilentlyContinue | Out-Null
        Assert-That ("$warnings" -match 'under a week') 'A file with three days left must be reported.'
        [IO.File]::WriteAllText($output, (Sign-As 'tk1' 9 (New-TestPayload 9 ($now - 10 * $script:OtaDay) ($now - 1))))
        Expect-Failure { & $sign -Check } 'expired' 'an expired file'
        $tampered = (Get-Content -LiteralPath $output -Raw) -replace '"sig":"(.)', '"sig":"A'
        [IO.File]::WriteAllText($output, $tampered)
        Expect-Failure { & $sign -Check } 'verify|shape' 'a file with a changed signature'
        Expect-Failure { & $sign -Offline } 'verify|shape' 'building on a committed file that was changed'
        Remove-Item -LiteralPath $output -Force
        & $sign -Offline | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 1) 'Without a committed file the numbering starts again at 1.'
    }

    Test-Case 'A retired key does not trap the owner: the next key signs on, and a sequence can be chosen by hand' {
        & $newKey -KeyId 'tk2' | Out-Null
        $before = Read-Output
        Assert-That ($before.Key -ceq 'tk1') 'The file so far is signed by the first key.'
        # The app ships the new key in place of the old one.
        $lines = @(Get-Content -LiteralPath $keysFile | Where-Object { $_ -notmatch '^tk1 ' })
        [IO.File]::WriteAllText($keysFile, ($lines -join "`n") + "`n")
        $warnings = $null
        & $sign -Offline -KeyId 'tk2' -WarningVariable warnings -WarningAction SilentlyContinue | Out-Null
        Assert-That ("$warnings" -match 'no longer lists') 'The retired key must be mentioned.'
        $next = Read-Output
        Assert-That ($next.Key -ceq 'tk2' -and $next.Verified -and $next.Payload.sequence -eq ($before.Payload.sequence + 1)) 'The counting goes on under the new key.'
        & $sign -Check | Out-Null
        & $sign -Offline -KeyId 'tk2' -Sequence 1 -WarningVariable warnings -WarningAction SilentlyContinue | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 1 -and "$warnings" -match 'by hand') 'A sequence chosen by hand is used and reported.'
    }

    Test-Case 'A committed file that has used up the numbers needs a hand-picked sequence, not a silent restart' {
        $poisoned = New-TestPayload $script:OtaMaxSequence
        [IO.File]::WriteAllText($output, (Sign-As 'tk1' $script:OtaMaxSequence $poisoned))
        Expect-Failure { & $sign -Offline -KeyId 'tk2' } 'would pass' 'a sequence that cannot go up'
        & $sign -Offline -KeyId 'tk2' -Sequence 1 -WarningAction SilentlyContinue | Out-Null
        $recovered = Read-Output
        Assert-That ($recovered.Key -ceq 'tk2' -and $recovered.Payload.sequence -eq 1 -and $recovered.Verified) 'The new key starts again at a number of its own.'
        & $sign -Check | Out-Null
    }

    function Set-ListedKeys([string[]]$Ids) {
        $lines = @(Get-Content -LiteralPath $keysFile | Where-Object { $_ -and -not $_.StartsWith('#') -and ($Ids -ccontains $_.Split(' ')[0]) })
        [IO.File]::WriteAllText($keysFile, ($lines -join "`n") + "`n")
    }

    Test-Case 'When two listed keys are both on this computer the signer asks which one to use' {
        & $newKey -KeyId 'tk3' | Out-Null
        Assert-That (@(Get-Content -LiteralPath $keysFile | Where-Object { $_ -and -not $_.StartsWith('#') }).Count -eq 2) 'Two keys are listed.'
        Expect-Failure { & $sign -Offline } 'Pass -KeyId' 'two keys that could sign'
        & $sign -Offline -KeyId 'tk3' | Out-Null
        Assert-That ((Read-Output).Key -ceq 'tk3' -and (Read-Output).Verified) 'The key that was named signs.'
        & $sign -Offline -KeyId 'tk2' | Out-Null
        Assert-That ((Read-Output).Key -ceq 'tk2') 'And so does the other.'
    }

    Test-Case 'The key folder is restricted to the current user every time the key script runs, and the restriction is checked' {
        if (-not $IsWindows) { Skip-Test 'folder permissions are checked on Windows'; return }
        $folder = Join-Path $fixture '.secrets\ota-signing'
        $me = [System.Security.Principal.WindowsIdentity]::GetCurrent()
        $reachedByOthers = { param($Path) @((Get-Acl -LiteralPath $Path).Access | Where-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $me.User.Value }) }
        Assert-That ((Get-Acl -LiteralPath $folder).AreAccessRulesProtected -and (& $reachedByOthers $folder).Count -eq 0) 'A folder the script created is not restricted.'
        # As an older version of the script could have left it: inheritance on, and others let in.
        & icacls.exe $folder /inheritance:e /grant '*S-1-5-11:(OI)(CI)R' | Out-Null
        Assert-That ((& $reachedByOthers $folder).Count -gt 0) 'The test could not loosen the folder.'
        & $newKey -KeyId 'tk4' | Out-Null
        Assert-That ((Get-Acl -LiteralPath $folder).AreAccessRulesProtected -and (& $reachedByOthers $folder).Count -eq 0) 'Running the script on an existing folder did not restrict it.'
        foreach ($file in Get-ChildItem -LiteralPath $folder -File) { Assert-That ((& $reachedByOthers $file.FullName).Count -eq 0) "$($file.Name) can be reached by somebody else." }
        # A file that brings entries of its own, as one restored from a backup can: the folder is repaired and so is the file.
        $restored = Get-ChildItem -LiteralPath $folder -File | Select-Object -First 1
        & icacls.exe $restored.FullName /grant '*S-1-5-11:R' | Out-Null
        Assert-That ((& $reachedByOthers $restored.FullName).Count -gt 0) 'The test could not loosen the file.'
        & $newKey -RestrictOnly | Out-Null
        Assert-That ((& $reachedByOthers $restored.FullName).Count -eq 0) 'A file with entries of its own was left reachable by somebody else.'
        # -RestrictOnly repairs the folder and creates nothing.
        & icacls.exe $folder /grant '*S-1-5-11:(OI)(CI)R' | Out-Null
        $keysBefore = Get-Content -LiteralPath $keysFile -Raw
        $namesBefore = (Get-ChildItem -LiteralPath $folder | ForEach-Object Name | Sort-Object) -join ','
        & $newKey -RestrictOnly | Out-Null
        Assert-That ((& $reachedByOthers $folder).Count -eq 0) '-RestrictOnly did not restrict the folder.'
        Assert-That ((Get-Content -LiteralPath $keysFile -Raw) -ceq $keysBefore -and ((Get-ChildItem -LiteralPath $folder | ForEach-Object Name | Sort-Object) -join ',') -ceq $namesBefore) '-RestrictOnly must not create a key.'
        # The grant is made by SID: the script must not depend on how the account is named.
        $script_ = Get-Content -LiteralPath $newKey -Raw
        Assert-That ($script_ -notmatch '\$identity\.Name|\$Identity\.Name' -and $script_ -match "'\*\{0\}:") 'The grant must be made to the SID, not to the account name.'
    }

    Test-Case 'A fourth public key is refused, and nothing is written for it' {
        $listed = { @(Get-Content -LiteralPath $keysFile | Where-Object { $_ -and -not $_.StartsWith('#') }).Count }
        $index = 0
        while ((& $listed) -lt 3) { & $newKey -KeyId "tf$index" | Out-Null; $index++ }
        $folder = Join-Path $fixture '.secrets\ota-signing'
        $keysBefore = Get-Content -LiteralPath $keysFile -Raw
        $namesBefore = (Get-ChildItem -LiteralPath $folder | ForEach-Object Name | Sort-Object) -join ','
        Expect-Failure { & $newKey -KeyId 'tk5' } 'Three public keys' 'a fourth key'
        Assert-That ((Get-Content -LiteralPath $keysFile -Raw) -ceq $keysBefore) 'The list of keys changed.'
        Assert-That (((Get-ChildItem -LiteralPath $folder | ForEach-Object Name | Sort-Object) -join ',') -ceq $namesBefore) 'A private key was written for the refused key.'
        Expect-Failure { & $newKey -KeyId 'tk2' } 'Three public keys|already' 'a key id that is already listed'
    }

    Test-Case 'The committed file is held to the floor the app declares, and the tool says what to set the floor to' {
        Set-ListedKeys @('tk2')
        & $sign -Offline -KeyId 'tk2' | Out-Null
        $issued = [long](Read-Output).Payload.issuedAt
        $shown = (& $sign -Check) -join "`n"
        Assert-That ($shown -match "BUILD_ISSUED_FLOOR in the app: $($script:OtaEarliest)\." -and $shown -match "set it to $issued ") 'The check must say what the floor is and what to set it to.'
        Set-Floor $issued
        & $sign -Check | Out-Null
        Set-Floor ($issued + 1)
        Expect-Failure { & $sign -Check } 'before the floor' 'a committed file issued a millisecond before the floor'
        Set-Floor $script:OtaEarliest
        & $sign -Check | Out-Null
    }

    Test-Case 'Cutting an app version requires the floor to be the issue time of the settings file that goes out with it' {
        $issued = [long](Read-Output).Payload.issuedAt
        Set-Floor $issued
        & $sign -Check -AppRelease | Out-Null
        Set-Floor ($issued - 1000)
        Expect-Failure { & $sign -Check -AppRelease } "BUILD_ISSUED_FLOOR = $($issued)L" 'a floor that was not brought up to the committed file'
        & $sign -Check | Out-Null
        Set-Floor $script:OtaEarliest
        Expect-Failure { & $sign -Check -AppRelease } 'set BUILD_ISSUED_FLOOR' 'a floor that was never raised'
    }

    Test-Case 'A floor ahead of this computer''s clock dates the file at the floor within what a phone allows, and stops beyond it' {
        $floorAhead = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 2 * $hour
        Set-Floor $floorAhead
        & $sign -Offline -KeyId 'tk2' -WarningVariable warned -WarningAction SilentlyContinue | Out-Null
        Assert-That ("$warned" -match 'behind the time of the newest signed file' -and "$warned" -match 'dated') 'The clamp must be reported.'
        $dated = [long](Read-Output).Payload.issuedAt
        Assert-That ($dated -eq $floorAhead) "The file must be dated at the floor, not at this computer's clock (got $dated, expected $floorAhead)."
        & $sign -Check -WarningAction SilentlyContinue | Out-Null
        $kept = (Get-FileHash -LiteralPath $output).Hash
        Set-Floor ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour)
        Expect-Failure { & $sign -Offline -KeyId 'tk2' } 'BUILD_ISSUED_FLOOR.*more than 36 hours' 'a floor more than a phone allows ahead of the clock'
        Expect-Failure { & $sign -Offline -KeyId 'tk2' -IgnoreFutureIssueTime } 'BUILD_ISSUED_FLOOR.*more than 36 hours' 'the switch is about files and never about the app''s floor'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $kept -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing changed something.'
        Set-Floor $script:OtaEarliest
        Remove-Item -LiteralPath $output -Force
        & $sign -Offline -KeyId 'tk2' | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 1) 'The numbering starts again without a committed file.'
    }

    Test-Case 'A committed file dated ahead of this computer''s clock is never followed by an earlier one: within a phone''s tolerance the next is dated alike, beyond it the tool stops unless told the clock is right' {
        # Within 36 hours: a clock that was a little fast when the file was signed must not strand the owner.
        $dateAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 2 * $hour
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 20 (New-TestPayload 20 $dateAt ($dateAt + 10 * $script:OtaDay))))
        $shown = (& $sign -Check -WarningVariable checkWarnings -WarningAction SilentlyContinue) -join "`n"
        Assert-That ("$checkWarnings" -match 'hours ahead of this computer' -and $shown -match "set it to $dateAt ") '-Check must say that the committed file is dated ahead, and still say what to set the floor to.'
        Assert-That ($shown -notmatch 'Do not set it') 'Guidance that contradicts the release gate must not be printed: a floor up to 36 hours ahead is harmless.'
        & $sign -Offline -KeyId 'tk2' -WarningVariable warned -WarningAction SilentlyContinue | Out-Null
        $next = Read-Output
        Assert-That ([long]$next.Payload.sequence -eq 21 -and [long]$next.Payload.issuedAt -eq $dateAt) 'The next file must follow with the same issue time, not an earlier one.'
        Assert-That ("$warned" -match 'dated .* instead' ) 'Dating the file at the newer time must be reported.'
        Assert-That ([long]$next.Payload.expiresAt - [long]$next.Payload.issuedAt -eq 45 * $script:OtaDay) 'The validity still counts from the issue time.'
        & $sign -Check -WarningAction SilentlyContinue | Out-Null
        # Beyond 36 hours: a phone with a right clock refuses that file and holds nothing back, but a clock that is behind NOW looks the same.
        $far = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 30 (New-TestPayload 30 $far ($far + 10 * $script:OtaDay))))
        $hash = (Get-FileHash -LiteralPath $output).Hash
        Expect-Failure { & $sign -Check } 'dated in the future' 'a committed file dated beyond what a phone allows fails the check as well'
        Expect-Failure { & $sign -Offline -KeyId 'tk2' } 'IgnoreFutureIssueTime' 'a committed file dated beyond what a phone allows'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $hash -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing changed something.'
        $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        & $sign -Offline -KeyId 'tk2' -IgnoreFutureIssueTime -WarningVariable ignored -WarningAction SilentlyContinue | Out-Null
        $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $recovered = Read-Output
        Assert-That ("$ignored" -match 'set aside' -and "$ignored" -match 'clock is right') 'Setting a file aside must be reported, with the reminder.'
        Assert-That ([long]$recovered.Payload.sequence -eq 31 -and [long]$recovered.Payload.issuedAt -ge $before -and [long]$recovered.Payload.issuedAt -le $after) 'The new file is dated now, and counts on from the file that was set aside.'
        & $sign -Check | Out-Null
        # The switch changes nothing for a file dated within what a phone allows.
        $near = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 3 * $hour
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 40 (New-TestPayload 40 $near ($near + 10 * $script:OtaDay))))
        & $sign -Offline -KeyId 'tk2' -IgnoreFutureIssueTime -WarningAction SilentlyContinue | Out-Null
        Assert-That ([long](Read-Output).Payload.issuedAt -eq $near) 'The switch must not set aside a file a phone still takes.'
        # The edge itself: a few seconds under 36 hours is followed (the file stays inside what a phone takes), a minute over stops the tool.
        # (The tool reads the clock a moment after this test did, which is why the margins are seconds and not milliseconds.)
        $under = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 36 * $hour - 8000
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 50 (New-TestPayload 50 $under ($under + 10 * $script:OtaDay))))
        & $sign -Offline -KeyId 'tk2' -WarningAction SilentlyContinue | Out-Null
        Assert-That ([long](Read-Output).Payload.issuedAt -eq $under -and [long](Read-Output).Payload.sequence -eq 51) 'A file just under 36 hours ahead must be followed by one dated alike.'
        $over = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 36 * $hour + 60000
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 60 (New-TestPayload 60 $over ($over + 10 * $script:OtaDay))))
        Expect-Failure { & $sign -Offline -KeyId 'tk2' } 'IgnoreFutureIssueTime' 'a file a minute over 36 hours ahead'
        # -AppRelease is a way of checking: on its own it must never sign.
        $hash = (Get-FileHash -LiteralPath $output).Hash
        Expect-Failure { & $sign -AppRelease -Offline -KeyId 'tk2' } 'dated in the future|set BUILD_ISSUED_FLOOR' '-AppRelease without -Check'
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $hash -and -not (Test-Path -LiteralPath "$output.new")) '-AppRelease on its own signed or changed something.'
        # Back to a committed file that the next tests can build on.
        Remove-Item -LiteralPath $output -Force
        & $sign -Offline -KeyId 'tk2' | Out-Null
        Assert-That ((Read-Output).Payload.sequence -eq 1) 'The numbering starts again without a committed file.'
        $issued = [long](Read-Output).Payload.issuedAt
        Set-Floor $issued
        $hash = (Get-FileHash -LiteralPath $output).Hash
        & $sign -AppRelease | Out-Null
        Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $hash) '-AppRelease with a floor that is right passes and leaves the file alone.'
        Set-Floor $script:OtaEarliest
    }

    # ---- the tool's own requests, against a server on this computer that says whatever the test wants ----
    $served = [hashtable]::Synchronized(@{ Status = 200; Headers = @{}; Body = ''; Stop = $false; Requests = 0; Last = '' })
    $server = Start-TestServer $served
    try {
        $origin = "http://127.0.0.1:$($server.Port)"
        $goodHeaders = @{ 'Content-Type' = 'application/json; charset=utf-8'; 'Cache-Control' = 'no-store'; 'X-Robots-Tag' = 'noindex' }
        function Serve([int]$Status, $Headers, [string]$Body) { $served.Status = $Status; $served.Headers = $Headers; $served.Body = $Body }
        function Invoke-Signer([hashtable]$Arguments) {
            $warned = $null
            $printed = & $sign @Arguments -Origin $origin -WarningVariable warned -WarningAction SilentlyContinue
            return [pscustomobject]@{ Output = (@($printed) -join "`n"); Warnings = (@($warned) -join "`n") }
        }
        $week = 10 * $script:OtaDay

        Test-Case 'A release is checked against what is online, and the website is checked after it, over a real connection' {
            & $sign -Offline -KeyId 'tk2' | Out-Null
            $committed = Get-Content -LiteralPath $output -Raw
            $current = Read-Output
            $sequence = [long]$current.Payload.sequence; $issued = [long]$current.Payload.issuedAt
            Assert-That ($sequence -ge 2) 'The test needs a sequence number to go below.'
            Serve 200 $goodHeaders $committed
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Output -match 'exactly the committed file' -and $result.Warnings -eq '') "The identical file passes quietly: $($result.Warnings)"
            Assert-That ($served.Requests -ge 1 -and $served.Last -match 'GET /ota/settings\.json ') 'The tool asked for the settings file.'
            $older = Sign-As 'tk2' ($sequence - 1) (New-TestPayload ($sequence - 1) ($issued - 5000) ($issued + $week))
            Serve 200 $goodHeaders $older
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Output -match 'replaces it' -and $result.Warnings -eq '') 'An older live file is replaced without a fuss.'
            Serve 404 @{} 'Not found'
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Output -match 'Nothing usable' -and $result.Warnings -match '404') 'Nothing live yet is only a warning before a release.'
            Serve 200 $goodHeaders (Sign-As 'tk2' ($sequence + 1) (New-TestPayload ($sequence + 1) $issued ($issued + $week)))
            Expect-Failure { & $sign -Check -Live -Origin $origin } 'not below' 'a file of the same key with a higher sequence on the website'
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload 1 ($issued + 1000) ($issued + $week)))
            Expect-Failure { & $sign -Check -Live -Origin $origin } 'issued later' 'a file issued later on the website'
            Serve 200 @{ 'Content-Type' = 'text/html' } $committed
            $result = Invoke-Signer @{ Check = $true; Live = $true }
            Assert-That ($result.Warnings -match 'content type' -and $result.Warnings -match 'no-store' -and $result.Warnings -match 'noindex') 'Headers that are not right are reported before a release.'

            Serve 200 $goodHeaders $committed
            $result = Invoke-Signer @{ RequireIdentical = $true }
            Assert-That ($result.Output -match 'exactly the committed file' -and $result.Output -match 'in force' -and $result.Warnings -eq '') 'After the release the committed file is served.'
            Serve 200 $goodHeaders $older
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'other than the committed' 'a different file after the release'
            Serve 404 @{} 'Not found'
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } '404' 'nothing served after the release'
            Serve 200 @{ 'Content-Type' = 'text/html'; 'Cache-Control' = 'no-store'; 'X-Robots-Tag' = 'noindex' } $committed
            Expect-Failure { & $sign -RequireIdentical -Origin $origin } 'content type' 'the wrong content type after the release'
        }

        Test-Case 'Only a live file that a trusted key signed is counted when the next sequence number is chosen' {
            & $sign -Offline -KeyId 'tk2' | Out-Null
            $base = Read-Output
            $sequence = [long]$base.Payload.sequence; $issued = [long]$base.Payload.issuedAt
            $horizon = $issued + $week
            # A file that anybody who can change the website could have written must not move the numbering.
            Serve 200 $goodHeaders (New-TestEnvelope (New-TestSigner 'tk2') (New-TestPayload 900000 $issued $horizon))
            $result = Invoke-Signer @{ KeyId = 'tk2' }
            Assert-That ([long](Read-Output).Payload.sequence -eq $sequence + 1) 'A forged live file moved the sequence number.'
            Assert-That ($result.Warnings -match 'cannot be verified') 'The forged file must be reported.'
            $sequence = [long](Read-Output).Payload.sequence
            Serve 200 $goodHeaders (Sign-As 'tk1' 1 (New-TestPayload 800000 $issued $horizon))
            $result = Invoke-Signer @{ KeyId = 'tk2' }
            Assert-That ([long](Read-Output).Payload.sequence -eq $sequence + 1 -and $result.Warnings -match 'cannot be verified') 'A file of a key the app does not list is not counted.'
            Serve 200 $goodHeaders 'Not found'
            $result = Invoke-Signer @{ KeyId = 'tk2' }
            Assert-That ($result.Warnings -match 'cannot be verified' -and [long](Read-Output).Payload.sequence -eq $sequence + 2) 'Something that is not a settings file is not counted either.'
            # A file the owner signed, that is newer than the committed one, is.
            $sequence = [long](Read-Output).Payload.sequence; $issued = [long](Read-Output).Payload.issuedAt
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload ($sequence + 30) $issued ($issued + $week)))
            $result = Invoke-Signer @{ KeyId = 'tk2' }
            Assert-That ([long](Read-Output).Payload.sequence -eq $sequence + 31 -and $result.Warnings -match 'newer than the committed') 'A newer file that a trusted key signed is counted.'
            # A trusted file dated ahead of this computer's clock by no more than a phone allows: the next file is dated alike, never earlier.
            $ahead = $now + 3 * $hour
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload 5 $ahead ($ahead + $week)))
            $result = Invoke-Signer @{ KeyId = 'tk2' }
            Assert-That ([long](Read-Output).Payload.issuedAt -eq $ahead -and $result.Warnings -match 'dated .* instead') 'A trusted live file dated ahead of the clock must not be followed by an earlier one.'
            # Dated further ahead than that: this computer's clock may be the thing that is wrong, so the tool stops, unless it is told the clock is right.
            $far = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload 5 $far ($far + $week)))
            $hash = (Get-FileHash -LiteralPath $output).Hash
            Expect-Failure { & $sign -Origin $origin -KeyId 'tk2' } 'IgnoreFutureIssueTime' 'a trusted live file dated more than a phone allows ahead of the clock'
            Assert-That ((Get-FileHash -LiteralPath $output).Hash -ceq $hash -and -not (Test-Path -LiteralPath "$output.new")) 'A refused signing changed the committed file.'
            $result = Invoke-Signer @{ KeyId = 'tk2'; IgnoreFutureIssueTime = $true }
            Assert-That ($result.Warnings -match 'The file the website serves .* set aside') 'The file that was set aside must be named.'
            Assert-That ([long](Read-Output).Payload.issuedAt -eq $ahead) 'The file dated far ahead is set aside, but the committed file, dated within what a phone allows, still is not gone back past.'
            # The check made before a release agrees: a live file dated far ahead stops it, unless it is set aside in the same way.
            Expect-Failure { & $sign -Check -Live -Origin $origin } 'issued later' 'a live file dated far ahead, without the switch'
            $replaced = Invoke-Signer @{ Check = $true; Live = $true; IgnoreFutureIssueTime = $true }
            Assert-That ($replaced.Warnings -match 'dated more than 36 hours ahead' -and $replaced.Output -match 'replaces it') 'With the switch the live file is set aside and replaced.'
            # Two sources at once, the other way round: the committed file is the one dated far ahead and the live one is dated within what a phone
            # allows. The switch sets aside the first only; the second still decides the date.
            $nearLive = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 3 * $hour
            $farCommitted = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour
            [IO.File]::WriteAllText($output, (Sign-As 'tk2' 50 (New-TestPayload 50 $farCommitted ($farCommitted + $week))))
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload 6 $nearLive ($nearLive + $week)))
            Expect-Failure { & $sign -Origin $origin -KeyId 'tk2' } 'The committed file .*-IgnoreFutureIssueTime' 'a committed file dated far ahead, with a live file dated near'
            $result = Invoke-Signer @{ KeyId = 'tk2'; IgnoreFutureIssueTime = $true }
            Assert-That ([long](Read-Output).Payload.issuedAt -eq $nearLive -and [long](Read-Output).Payload.sequence -eq 51 -and $result.Warnings -match 'The committed file .* set aside') 'The committed file is set aside, the live file dated within what a phone allows still decides the date, and the numbering counts on from the file set aside.'
            # A hand-chosen number does not escape the sequence rule of a live file that is set aside, in the pre-release check.
            Serve 200 $goodHeaders (Sign-As 'tk2' 1 (New-TestPayload 99 ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour) ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 40 * $hour + $week)))
            Expect-Failure { & $sign -Check -Live -Origin $origin -IgnoreFutureIssueTime } 'not below' 'a live file set aside whose number is not below the committed one'
        }
    } finally { Stop-TestServer $server $served }

    Test-Case 'The website render and the website stage refuse an expired settings file before they touch anything' {
        [IO.File]::WriteAllText($output, (Sign-As 'tk2' 30 (New-TestPayload 30 ($now - 10 * $script:OtaDay) ($now - 1))))
        $stageDirectory = Join-Path $fixture 'stage'
        New-Item -ItemType Directory -Path $stageDirectory -Force | Out-Null
        Expect-Failure { & (Join-Path $fixture 'scripts\render-website.ps1') -Stage $stageDirectory } 'expired' 'rendering the website with an expired settings file'
        Assert-That (@(Get-ChildItem -LiteralPath $stageDirectory -Force).Count -eq 0) 'Nothing may be written into the stage.'
        Expect-Failure { & (Join-Path $fixture 'scripts\prepare-website.ps1') -Version 9.9.9 } 'expired' 'staging the website with an expired settings file'
        Assert-That (-not (Test-Path -LiteralPath (Join-Path $fixture '.tools'))) 'No stage directory may be created.'
    }

    Test-Case 'The release staging checks the settings and the floor first, before the notices and before any stage exists, and a leftover half-written file stops it' {
        $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'prepare-download.ps1') -Raw
        $check = $text.IndexOf("sign-ota-settings.ps1') -Check -AppRelease")
        $leftover = $text.IndexOf('Assert-OtaNoLeftoverFile')
        $dependencies = $text.IndexOf('$dependencies = ')
        $notices = $text.IndexOf('if ($PrepareNoticesOnly) {')
        $staging = $text.IndexOf('$stage = Join-Path $tools')
        Assert-That ($check -gt 0 -and $leftover -gt $check -and $dependencies -gt $leftover -and $notices -gt $dependencies -and $staging -gt $notices) 'prepare-download.ps1 must check the settings and the floor, and look for a leftover half-written file, before anything else, so that a forgotten floor costs a message and not a rebuild.'
        $site = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'prepare-website.ps1') -Raw
        $siteCheck = $site.IndexOf("sign-ota-settings.ps1') -Check")
        Assert-That ($siteCheck -gt 0 -and $siteCheck -lt $site.IndexOf('New-Item -ItemType Directory') -and $siteCheck -lt $site.IndexOf('distribution-audit-')) 'prepare-website.ps1 must check the settings before it reads or creates anything.'
        # A website-only release goes out with the app that is already on phones, whose floor is older: only cutting an app version brings it up.
        Assert-That ($site -notmatch 'AppRelease' -and (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'render-website.ps1') -Raw) -notmatch 'AppRelease') 'Only prepare-download.ps1 may demand that the floor was brought up.'
        # The leftover check itself is executed, not only located: an interrupted signing leaves settings.json.new behind.
        $folder = Join-Path $fixture 'download\ota'
        Assert-OtaNoLeftoverFile $folder
        Assert-OtaNoLeftoverFile (Join-Path $fixture 'no-such-folder')
        [IO.File]::WriteAllText((Join-Path $folder 'settings.json.new'), '{}')
        Expect-Failure { Assert-OtaNoLeftoverFile $folder } 'half-written settings file \(settings\.json\.new\)' 'an interrupted signing'
        Remove-Item -LiteralPath (Join-Path $folder 'settings.json.new') -Force
        Assert-OtaNoLeftoverFile $folder
    }

    Write-Output "Passed $passed over-the-air settings tests$(if ($skipped) { " ($skipped skipped)" }). Nothing left this computer (the web server was on the loopback address), and no phone or real signing key was used."
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $resolved = (Resolve-Path -LiteralPath $fixture).Path
        if ((Split-Path $resolved -Leaf) -notmatch '^vanishr-ota-settings-[a-f0-9]{32}$') { throw 'Refusing cleanup outside the generated test fixture.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
