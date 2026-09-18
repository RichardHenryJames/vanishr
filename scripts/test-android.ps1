#requires -Version 7.4
param([string]$Serial = 'emulator-5580', [switch]$ProvisionTestCredential)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if ($env:ANDROID_HOME) { $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
$credential = $null
if ($ProvisionTestCredential) {
    $name = & $adb -s $Serial emu avd name
    if ($LASTEXITCODE -ne 0 -or -not ($name -contains 'Vanishr_Security_Test')) {
        throw 'Credential provisioning is restricted to the dedicated Vanishr_Security_Test emulator.'
    }
    & $adb -s $Serial shell pm clear app.vanishr.android | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset the dedicated test-app fixture.' }
    $credential = [System.Security.Cryptography.RandomNumberGenerator]::GetInt32(100000, 999999).ToString()
    & $adb -s $Serial shell locksettings set-pin $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Use a fresh dedicated test emulator without an existing credential.' }
    & $adb -s $Serial shell locksettings verify --old $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The test credential could not be verified.' }
}
try {
    & $adb -s $Serial install -r (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install test client.' }
    & $adb -s $Serial install -r (Join-Path $root 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install instrumentation.' }
    $testClass = if ($ProvisionTestCredential) {
        'app.vanishr.android.DeviceSecurityTest#authenticatedKeystoreContentIsEncryptedAndUnrecoverableAfterKeyDeletion'
    } else { 'app.vanishr.android.DeviceSecurityTest' }
    $result = & $adb -s $Serial shell am instrument -w -r -e class $testClass app.vanishr.android.test/androidx.test.runner.AndroidJUnitRunner
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_STATUS_CODE: -1')) {
        throw 'Android runtime tests failed.'
    }
    if ($ProvisionTestCredential -and ($result -match 'INSTRUMENTATION_STATUS_CODE: -4')) {
        throw 'Credential-dependent test was skipped; authenticate the emulator and retry.'
    }
} finally {
    if ($null -ne $credential) {
        & $adb -s $Serial shell locksettings clear --old $credential | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'The temporary test credential could not be cleared. Recreate only the dedicated test emulator.' }
        & $adb -s $Serial shell pm clear app.vanishr.android | Out-Null
    }
}