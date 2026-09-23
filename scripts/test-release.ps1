#requires -Version 7.4
param([string]$Serial = 'emulator-5582', [uri]$RelayOrigin, [switch]$Live, [switch]$Push)
$ErrorActionPreference = 'Stop'
if ($Push -and -not $Live) { throw 'Live FCM testing also requires -Live.' }
$root = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$name = & $adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or -not ($name -contains 'Vanishr_Release_Test')) {
    throw 'Release fixture resets are restricted to the dedicated Vanishr_Release_Test emulator.'
}
$booted = & $adb -s $Serial shell getprop sys.boot_completed
if ($LASTEXITCODE -ne 0 -or "$booted".Trim() -ne '1') { throw 'Wait for the dedicated emulator to finish booting before running release tests.' }
if ($Live -and ($null -eq $RelayOrigin -or $RelayOrigin.Scheme -ne 'https' -or $RelayOrigin.UserInfo -or $RelayOrigin.Query -or $RelayOrigin.Fragment -or $RelayOrigin.AbsolutePath -ne '/')) {
    throw 'Live testing requires an explicit verified HTTPS relay origin.'
}
$apk = Join-Path $root 'android/app/build/outputs/apk/release/app-release.apk'
$testApk = Join-Path $root 'android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
& $adb -s $Serial install --no-streaming -r $apk
if ($LASTEXITCODE -ne 0) { throw 'The signed release could not be installed.' }
& $adb -s $Serial install --no-streaming -r $testApk
if ($LASTEXITCODE -ne 0) { throw 'The signed test package could not be installed.' }
foreach ($package in @('app.vanishr.android', 'app.vanishr.android.test')) {
    & $adb -s $Serial shell cmd package compile -m speed -f $package
    if ($LASTEXITCODE -ne 0) { throw 'Signed test package precompilation failed.' }
}
& $adb -s $Serial shell pm clear app.vanishr.android | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not reset the dedicated release fixture.' }
if ($Push -and [int]((& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()) -ge 33) {
    & $adb -s $Serial shell pm revoke app.vanishr.android android.permission.POST_NOTIFICATIONS
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset the dedicated notification-permission fixture.' }
    & $adb -s $Serial shell pm clear-permission-flags app.vanishr.android android.permission.POST_NOTIFICATIONS user-set user-fixed
    if ($LASTEXITCODE -ne 0) { throw 'Could not clear the dedicated notification-permission prompt flags.' }
}
$credential = [System.Security.Cryptography.RandomNumberGenerator]::GetInt32(100000, 999999).ToString()
$credentialSet = $false
$results = Join-Path $root '.tools/release-verification'
New-Item -ItemType Directory -Path $results -Force | Out-Null
function Run-Instrumentation([string[]]$TestArguments, [string]$ReportName, [bool]$AllowSkipped = $false) {
    $output = & $adb -s $Serial shell am instrument -w -r @TestArguments app.vanishr.android.test/androidx.test.runner.AndroidJUnitRunner
    $exitCode = $LASTEXITCODE
    $sanitized = ($output -join [Environment]::NewLine).Replace($credential, '[test-credential]')
    [System.IO.File]::WriteAllText((Join-Path $results $ReportName), $sanitized, [System.Text.UTF8Encoding]::new($false))
    Write-Output $sanitized
    if ($exitCode -ne 0 -or $sanitized -notmatch 'OK \(' -or $sanitized -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -2') {
        throw 'Signed-release instrumentation failed.'
    }
    if (-not $AllowSkipped -and $sanitized -match 'INSTRUMENTATION_STATUS_CODE: -4') { throw 'A required release test was skipped.' }
}
try {
    & $adb -s $Serial shell locksettings set-pin $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The dedicated release emulator must have no existing credential.' }
    $credentialSet = $true
    & $adb -s $Serial shell locksettings verify --old $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not authenticate the release fixture.' }
    Run-Instrumentation -TestArguments @('-e', 'class', 'app.vanishr.android.DeviceSecurityTest') -ReportName 'device-security.txt' -AllowSkipped $true
    if ($Live) {
        Run-Instrumentation -TestArguments @('-e', 'class', 'app.vanishr.android.ReleaseWorkflowTest', '-e', 'releaseLive', 'true',
            '-e', 'releasePush', $Push.IsPresent.ToString().ToLowerInvariant(),
            '-e', 'devicePin', $credential, '-e', 'relayOrigin', $RelayOrigin.GetLeftPart([System.UriPartial]::Authority)) -ReportName 'live-ui.txt'
    }
    Write-Output "Verified signed APK SHA-256: $hash"
} finally {
    & $adb -s $Serial shell test -d /sdcard/Android/data/app.vanishr.android/files/release-check
    if ($LASTEXITCODE -eq 0) { & $adb -s $Serial pull /sdcard/Android/data/app.vanishr.android/files/release-check $results }
    & $adb -s $Serial shell pm clear app.vanishr.android | Out-Null
    if ($credentialSet) {
        & $adb -s $Serial shell locksettings clear --old $credential | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not remove the temporary release-emulator credential.' }
    }
    $credential = $null
}