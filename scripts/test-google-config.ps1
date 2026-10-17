#requires -Version 7.4
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('vanishr-google-config-' + [guid]::NewGuid().ToString('N'))
$names = @('GOOGLE_WEB_CLIENT_ID', 'FIREBASE_APP_ID', 'FIREBASE_API_KEY', 'FIREBASE_PROJECT_ID', 'FIREBASE_SENDER_ID')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$passed = 0

function New-ClientConfiguration {
    return @{
        project_info = @{ project_id = 'vanishr-fixture'; project_number = '123456789012' }
        client = @(@{
            client_info = @{
                android_client_info = @{ package_name = 'app.vanishr.android' }
                mobilesdk_app_id = '1:123456789012:android:abcdef0123456789'
            }
            oauth_client = @(
                @{ client_type = 3; client_id = '123456789012-web-fixture.apps.googleusercontent.com' },
                @{ client_type = 1; client_id = '123456789012-android-fixture.apps.googleusercontent.com'
                    android_info = @{ package_name = 'app.vanishr.android'; certificate_hash = '1bfcf77f9ae8c14a53fcd79ceee0e9d2a8f1c0ca' } }
            )
            api_key = @(@{ current_key = 'AIza' + ('A' * 35) })
        })
    }
}

function Write-Client([string]$Relative, $Configuration = (New-ClientConfiguration)) {
    [IO.File]::WriteAllText((Join-Path $fixture $Relative), ($Configuration | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
}

function Expect-Failure([scriptblock]$Action, [string]$Pattern) {
    $message = $null
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message -or $message -notmatch $Pattern) { throw 'The expected safe packaging failure was not reported.' }
    foreach ($name in $names) {
        if ([Environment]::GetEnvironmentVariable($name, 'Process') -cne "inherited-fixture-$name") {
            throw 'Packaging failed to restore a caller environment setting.'
        }
    }
}

function Run-Package([hashtable]$Arguments) {
    Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') @Arguments -WarningAction SilentlyContinue } '^Synthetic build boundary$'
    $capture = Get-Content -LiteralPath (Join-Path $fixture 'captured.json') -Raw | ConvertFrom-Json
    return $capture
}

function Assert-Configured($Capture, [string]$ExpectedFile = 'expected.json') {
    $expected = & (Join-Path $fixture 'scripts\read-google-config.ps1') -Path (Join-Path $fixture $ExpectedFile)
    foreach ($name in $names) {
        if ($Capture.$name -cne $expected[$name]) { throw 'The build did not receive the validated client setting.' }
    }
}

function Test-Case([string]$Name, [scriptblock]$Action) {
    foreach ($relative in @('google-services.json', '.secrets\firebase\google-services.json', 'custom.json', 'captured.json')) {
        $path = Join-Path $fixture $relative
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    # The fixture always starts from the upload certificate alone, whatever the repository has registered for Google Play by now.
    [IO.File]::WriteAllText((Join-Path $fixture 'scripts\google-certificates.json'),
        '{"uploadSha1":"1bfcf77f9ae8c14a53fcd79ceee0e9d2a8f1c0ca","playAppSigningSha1":null}', [Text.UTF8Encoding]::new($false))
    & $Action
    $script:passed++
    Write-Output "PASS: $Name"
}

function Set-PlayCertificate($Sha1) {
    $certificates = Get-Content -LiteralPath (Join-Path $fixture 'scripts\google-certificates.json') -Raw | ConvertFrom-Json
    $certificates.playAppSigningSha1 = $Sha1
    [IO.File]::WriteAllText((Join-Path $fixture 'scripts\google-certificates.json'), ($certificates | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
}

try {
    foreach ($directory in @('scripts', '.secrets\firebase', '.secrets\android-signing')) {
        New-Item -ItemType Directory -Path (Join-Path $fixture $directory) -Force | Out-Null
    }
    foreach ($script in @('package-apk.ps1', 'read-google-config.ps1', 'google-certificates.json')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $fixture "scripts\$script")
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'scripts\prepare-brand-assets.ps1'), 'param([switch]$Verify)')
    $buildStub = @'
param([string[]]$Tasks)
$settings = [ordered]@{}
foreach ($name in @('GOOGLE_WEB_CLIENT_ID','FIREBASE_APP_ID','FIREBASE_API_KEY','FIREBASE_PROJECT_ID','FIREBASE_SENDER_ID')) {
    $settings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$settings | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) 'captured.json') -Encoding utf8
throw 'Synthetic build boundary'
'@
    [IO.File]::WriteAllText((Join-Path $fixture 'scripts\build-android.ps1'), $buildStub, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $fixture '.secrets\android-signing\release.p12'), 'synthetic-non-key-fixture')
    [IO.File]::WriteAllText((Join-Path $fixture '.secrets\android-signing\password.txt'), 'synthetic-fixture')
    Write-Client 'expected.json'
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, "inherited-fixture-$name", 'Process') }

    Test-Case 'Missing configuration fails instead of silently dropping Google access' {
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'Google client configuration is required'
        if (Test-Path -LiteralPath (Join-Path $fixture 'captured.json')) { throw 'An unconfigured build reached Gradle.' }
    }
    Test-Case 'Root client configuration is loaded automatically and environment is restored' {
        Write-Client 'google-services.json'
        Assert-Configured (Run-Package @{})
    }
    Test-Case 'Private client configuration is loaded automatically' {
        Write-Client '.secrets\firebase\google-services.json'
        Assert-Configured (Run-Package @{})
    }
    Test-Case 'An explicit client path configures the Play bundle too' {
        Write-Client 'custom.json'
        Assert-Configured (Run-Package @{ GoogleServicesFile = (Join-Path $fixture 'custom.json'); Bundle = $true; PlayStore = $true })
    }
    Test-Case 'Ambiguous default files require an explicit selection' {
        Write-Client 'google-services.json'; Write-Client '.secrets\firebase\google-services.json'
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'Multiple Google client configuration files'
    }
    Test-Case 'Explicit selection resolves multiple default files' {
        Write-Client 'google-services.json'; Write-Client '.secrets\firebase\google-services.json'
        Assert-Configured (Run-Package @{ GoogleServicesFile = (Join-Path $fixture 'google-services.json') })
    }
    Test-Case 'A server credential cannot configure an Android build' {
        Write-Client 'google-services.json' @{ type = 'service_account'; private_key = 'synthetic-not-a-private-key' }
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'A server credential must never be used'
    }
    Test-Case 'A different release signing certificate is rejected' {
        $configuration = New-ClientConfiguration
        $configuration.client[0].oauth_client[1].android_info.certificate_hash = '0' * 40
        Write-Client 'google-services.json' $configuration
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'matching the release certificate are required'
    }
    Test-Case 'Missing Web OAuth configuration fails before building' {
        $configuration = New-ClientConfiguration
        $configuration.client[0].oauth_client = @($configuration.client[0].oauth_client[1])
        Write-Client 'google-services.json' $configuration
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'One Web OAuth client'
    }
    Test-Case 'Without a Google Play app-signing certificate only the upload client is allowed by the relay' {
        Write-Client 'google-services.json'
        $ids = (& (Join-Path $fixture 'scripts\read-google-config.ps1') -Path (Join-Path $fixture 'expected.json')).GOOGLE_ANDROID_CLIENT_IDS
        if ($ids -cne '123456789012-android-fixture.apps.googleusercontent.com') { throw 'Unexpected relay allow-list.' }
    }
    Test-Case 'The repository certificate fingerprints are well formed' {
        $certificates = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'google-certificates.json') -Raw | ConvertFrom-Json
        if ($certificates.uploadSha1 -cnotmatch '\A[0-9a-f]{40}\z') { throw 'The upload certificate fingerprint is malformed.' }
        if ($null -ne $certificates.playAppSigningSha1 -and ($certificates.playAppSigningSha1 -cnotmatch '\A[0-9a-f]{40}\z' -or
                $certificates.playAppSigningSha1 -ceq $certificates.uploadSha1)) { throw 'The Google Play certificate fingerprint is malformed.' }
    }
    Test-Case 'A configured Google Play app-signing certificate must have its own Android OAuth client' {
        Set-PlayCertificate ('ab' * 20)
        Write-Client 'google-services.json'
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'matching the Google Play app-signing certificate is required'
    }
    Test-Case 'Both Android OAuth clients reach the relay allow-list once Google Play signing is registered' {
        Set-PlayCertificate ('ab' * 20)
        $configuration = New-ClientConfiguration
        $configuration.client[0].oauth_client += @{ client_type = 1; client_id = '123456789012-play-fixture.apps.googleusercontent.com'
            android_info = @{ package_name = 'app.vanishr.android'; certificate_hash = ('AB' * 20) } }
        Write-Client 'google-services.json' $configuration
        Write-Client 'registered.json' $configuration
        $settings = & (Join-Path $fixture 'scripts\read-google-config.ps1') -Path (Join-Path $fixture 'registered.json')
        if ($settings.GOOGLE_ANDROID_CLIENT_IDS -cne '123456789012-android-fixture.apps.googleusercontent.com,123456789012-play-fixture.apps.googleusercontent.com') {
            throw 'The relay allow-list does not contain both Android OAuth clients.'
        }
        Assert-Configured (Run-Package @{}) 'registered.json'
    }
    Test-Case 'The Google Play certificate cannot equal the upload certificate or be malformed' {
        foreach ($bad in @('1bfcf77f9ae8c14a53fcd79ceee0e9d2a8f1c0ca', 'AB:CD', 'g' * 40)) {
            Set-PlayCertificate $bad
            Write-Client 'google-services.json'
            Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'release certificate fingerprints are invalid'
        }
    }
    Test-Case 'Two Android OAuth clients for the upload certificate are refused rather than guessed between' {
        $configuration = New-ClientConfiguration
        $configuration.client[0].oauth_client += @{ client_type = 1; client_id = '123456789012-other-fixture.apps.googleusercontent.com'
            android_info = @{ package_name = 'app.vanishr.android'; certificate_hash = '1bfcf77f9ae8c14a53fcd79ceee0e9d2a8f1c0ca' } }
        Write-Client 'google-services.json' $configuration
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'matching the release certificate are required'
    }
    Test-Case 'Two Android OAuth clients for the Google Play certificate are refused too' {
        Set-PlayCertificate ('ab' * 20)
        $configuration = New-ClientConfiguration
        foreach ($id in 'play-one', 'play-two') {
            $configuration.client[0].oauth_client += @{ client_type = 1; client_id = "123456789012-$id-fixture.apps.googleusercontent.com"
                android_info = @{ package_name = 'app.vanishr.android'; certificate_hash = ('ab' * 20) } }
        }
        Write-Client 'google-services.json' $configuration
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } 'matching the Google Play app-signing certificate is required'
    }
    Test-Case 'The relay publish script on the VM accepts exactly the allow-lists this reader produces' {
        # The reader joins the Android client IDs with commas and publish-azure.ps1 hands the result to infra\azure\install-artifact.sh, which
        # checks it with jq before writing it to the relay's environment. A check that allows one ID only made the publish for Play fail.
        $installer = Get-Content -LiteralPath (Join-Path $root 'infra\azure\install-artifact.sh') -Raw
        $found = [regex]::Match($installer, '\(\.androidClientIds \| test\("(?<pattern>[^"]+)"\)\)')
        if (-not $found.Success) { throw 'The relay publish script no longer checks the Android client allow-list.' }
        $pattern = $found.Groups['pattern'].Value.Replace('\\', '\')
        $one = '167823115834-abc_DEF-1.apps.googleusercontent.com'
        $two = '167823115834-xyz-2.apps.googleusercontent.com'
        foreach ($good in @($one, "$one,$two")) { if ($good -cnotmatch $pattern) { throw "The relay publish script would refuse a valid allow-list: $good" } }
        foreach ($bad in @('', "$one,", ",$one", "$one, $two", "$one,$two,$one,$two", "$one`n", '999-abc.apps.googleusercontent.com', "$one;$two", "$one,evil.example.com")) {
            if ($bad -cmatch $pattern) { throw "The relay publish script would accept an invalid allow-list: $bad" }
        }
    }
    Test-Case 'Malformed configuration is rejected with a content-free error' {
        [IO.File]::WriteAllText((Join-Path $fixture 'google-services.json'), 'invalid-json-fixture')
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') } '^Could not read the Firebase client configuration; no file contents were displayed\.$'
    }
    Test-Case 'Opt-out cannot conflict with an explicit configuration file' {
        Write-Client 'google-services.json'
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') -NoGoogleServices -GoogleServicesFile (Join-Path $fixture 'google-services.json') } 'Choose either'
    }
    Test-Case 'Misspelled configuration parameters are rejected rather than ignored' {
        Write-Client 'google-services.json'
        Expect-Failure { & (Join-Path $fixture 'scripts\package-apk.ps1') -GoogleServicesFilesTypo (Join-Path $fixture 'google-services.json') } 'GoogleServicesFilesTypo'
    }
    Test-Case 'Explicit opt-out clears inherited provider settings only for that build' {
        Write-Client 'google-services.json'
        $capture = Run-Package @{ NoGoogleServices = $true }
        foreach ($name in $names) {
            if (-not [string]::IsNullOrEmpty($capture.$name)) { throw 'Explicit opt-out retained a provider setting.' }
        }
    }
    Write-Output "Passed $passed Google release-configuration tests. No Android build, network request or real signing credential was used."
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    if (Test-Path -LiteralPath $fixture) {
        $resolved = (Resolve-Path -LiteralPath $fixture).Path
        if ((Split-Path $resolved -Leaf) -notmatch '^vanishr-google-config-[a-f0-9]{32}$') { throw 'Refusing cleanup outside the generated test fixture.' }
        Remove-Item -LiteralPath $resolved -Recurse
    }
}
