#requires -Version 7.4
param([switch]$InitializeSigningKey, [uri]$RelayOrigin, [switch]$WithInstrumentation, [string]$GoogleServicesFile)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$signingDirectory = Join-Path $root '.secrets/android-signing'
$keystore = Join-Path $signingDirectory 'release.p12'
$passwordFile = Join-Path $signingDirectory 'password.txt'
$oldKeystore = $env:VANISHR_SIGNING_KEYSTORE
$oldPassword = $env:VANISHR_SIGNING_PASSWORD
$oldOrigin = $env:VANISHR_RELAY_ORIGIN
$googleEnvironmentNames = @('GOOGLE_WEB_CLIENT_ID', 'FIREBASE_APP_ID', 'FIREBASE_API_KEY', 'FIREBASE_PROJECT_ID', 'FIREBASE_SENDER_ID')
$oldGoogleEnvironment = @{}
foreach ($name in $googleEnvironmentNames) { $oldGoogleEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    if ($GoogleServicesFile) {
        $googleSettings = & (Join-Path $PSScriptRoot 'read-google-config.ps1') -Path $GoogleServicesFile
        foreach ($name in $googleEnvironmentNames) { [Environment]::SetEnvironmentVariable($name, $googleSettings[$name], 'Process') }
    }
    if ($null -ne $RelayOrigin) {
        if (-not $RelayOrigin.IsAbsoluteUri -or $RelayOrigin.Scheme -ne 'https' -or $RelayOrigin.UserInfo -or
            $RelayOrigin.Query -or $RelayOrigin.Fragment -or $RelayOrigin.AbsolutePath -ne '/') {
            throw 'Provide an HTTPS relay origin without credentials, path, query or fragment.'
        }
        $env:VANISHR_RELAY_ORIGIN = $RelayOrigin.GetLeftPart([System.UriPartial]::Authority)
        try { $health = Invoke-RestMethod -Uri "$env:VANISHR_RELAY_ORIGIN/health" -MaximumRedirection 0 }
        catch { throw 'Relay TLS or health verification failed; no APK was built for this origin.' }
        if ($health.status -ne 'up') { throw 'Relay health verification failed.' }
    }
    if (-not (Test-Path $keystore) -or -not (Test-Path $passwordFile)) {
        if (-not $InitializeSigningKey) { throw 'Initialize a local signing key explicitly with -InitializeSigningKey.' }
        if ((Test-Path $keystore) -or (Test-Path $passwordFile)) { throw 'Incomplete signing material exists. It will not be overwritten.' }
        if (-not $IsWindows) { throw 'Provision signing material securely on this platform before packaging.' }
        New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $acl = Get-Acl $signingDirectory
        $acl.SetAccessRuleProtection($true, $false)
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.SetAccessRule($rule)
        Set-Acl $signingDirectory $acl
        $env:VANISHR_SIGNING_PASSWORD = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
        & $keytool -genkeypair -alias vanishr-release -keystore $keystore -storetype PKCS12 `
            -storepass:env VANISHR_SIGNING_PASSWORD -keypass:env VANISHR_SIGNING_PASSWORD `
            -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
            -dname 'CN=Vanishr Local Release' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing-key generation failed.' }
        [System.IO.File]::WriteAllText($passwordFile, $env:VANISHR_SIGNING_PASSWORD, [System.Text.UTF8Encoding]::new($false))
    }
    $env:VANISHR_SIGNING_KEYSTORE = $keystore
    $env:VANISHR_SIGNING_PASSWORD = [System.IO.File]::ReadAllText($passwordFile)
    $buildTasks = @(':app:assembleRelease', ':app:lintRelease')
    if ($WithInstrumentation) { $buildTasks += @('-PtestBuildType=release', ':app:assembleReleaseAndroidTest') }
    & (Join-Path $PSScriptRoot 'build-android.ps1') -Tasks $buildTasks
    $apk = Join-Path $root 'android/app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path $apk)) { throw 'No signed release APK was produced.' }
    $signer = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
    & $signer verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $size = (Get-Item $apk).Length
    if ($size -gt 95MB) { throw 'The signed APK exceeds the reserved static-download upload budget.' }
    $checksum = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "Signed APK: $apk"
    Write-Output "Size: $([math]::Round($size / 1MB, 1)) MiB"
    Write-Output "SHA-256: $checksum"
    if ($env:VANISHR_RELAY_ORIGIN) { Write-Output "Default relay: $env:VANISHR_RELAY_ORIGIN" }
    Write-Output 'This signature is not a security audit. The app still needs a reachable trusted HTTPS relay.'
    Write-Output 'Keep .secrets/android-signing private and back it up securely offline. Losing it prevents compatible app updates.'
} finally {
    $env:VANISHR_SIGNING_KEYSTORE = $oldKeystore
    $env:VANISHR_SIGNING_PASSWORD = $oldPassword
    $env:VANISHR_RELAY_ORIGIN = $oldOrigin
    foreach ($name in $googleEnvironmentNames) {
        if ($null -eq $oldGoogleEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $oldGoogleEnvironment[$name], 'Process') }
    }
}