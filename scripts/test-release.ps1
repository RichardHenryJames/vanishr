#requires -Version 7.4
param([string]$Serial = 'emulator-5582', [uri]$RelayOrigin, [switch]$Live, [switch]$Push, [switch]$RemotePhotos)
$ErrorActionPreference = 'Stop'
if ($Push -and -not $Live) { throw 'Live FCM testing also requires -Live.' }
if ($RemotePhotos -and (-not $Live -or $RelayOrigin.AbsoluteUri.TrimEnd('/') -ne 'https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com')) {
    throw 'Remote photo release tests require the approved hosted relay and -Live.'
}
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
$photoAdminHandle = $null
$photoAdminPassword = $null
$photoUserId = $null
$results = Join-Path $root '.tools/release-verification'
New-Item -ItemType Directory -Path $results -Force | Out-Null
function Set-PhotoFixtureRole([ValidateSet('USER', 'ADMIN')][string]$Role) {
    if ($photoAdminHandle -notmatch '^release_photo_[0-9a-f]{16}$' -or $photoUserId -notmatch '^[0-9a-f-]{36}$') { throw 'Invalid synthetic photo account scope.' }
    $owner = & az group show --subscription '44027c71-593a-4d51-977b-ab0604cb76eb' --name 'vanishr-dev-rg' --query tags.managedBy --only-show-errors --output tsv
    if ($LASTEXITCODE -ne 0 -or "$owner".Trim() -ne 'vanishr-isolated-deployment') { throw 'Synthetic role assignment scope could not be verified.' }
    $script = @'
set -eu
cd /opt/vanishr
postgres=$(timeout 10s docker ps --filter label=com.docker.compose.project=vanishr --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
test -n "$postgres"
test "$(printf '%s\n' "$postgres" | wc -l)" -eq 1
timeout 30s docker exec -i "$postgres" sh -c 'export PGPASSWORD="$POSTGRES_PASSWORD"; export PGSSLMODE=verify-full PGSSLROOTCERT=/tls/ca.crt PGCONNECT_TIMEOUT=10; exec psql -X -q -t -A -v ON_ERROR_STOP=1 -h localhost -U vanishr -d vanishr' <<'SQL'
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='10s';
DO $fixture$
DECLARE affected integer;
BEGIN
    UPDATE accounts SET user_type='__ROLE__' WHERE id='__ID__'::uuid AND handle='__HANDLE__';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'Synthetic fixture account did not match'; END IF;
END;
$fixture$;
COMMIT;
SELECT json_build_object('userId',id,'userType',user_type) FROM accounts WHERE id='__ID__'::uuid AND handle='__HANDLE__';
SQL
'@
    $script = $script.Replace('__ROLE__', $Role).Replace('__ID__', $photoUserId).Replace('__HANDLE__', $photoAdminHandle)
    $path = Join-Path $results 'photo-role-fixture.sh'
    [IO.File]::WriteAllText($path, $script.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
    try {
        $response = & az vm run-command invoke --subscription '44027c71-593a-4d51-977b-ab0604cb76eb' --resource-group 'vanishr-dev-rg' --name 'vanishr-dev' --command-id RunShellScript --scripts "@$path" --only-show-errors --output json
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic photo role operation failed.' }
        $execution = ($response -join "`n") | ConvertFrom-Json
        $lines = @(($execution.value.message -join "`n") -split "`r?`n" | Where-Object { $_.Trim().StartsWith('{') })
        if ($lines.Count -ne 1) { throw 'Synthetic photo role result is incomplete.' }
        $account = $lines[0] | ConvertFrom-Json
        if ($account.userId -ne $photoUserId -or $account.userType -ne $Role) { throw 'Synthetic photo role result does not match.' }
        Write-Output "Synthetic photo fixture role verified: $Role."
    } finally { if (Test-Path $path) { Remove-Item -LiteralPath $path } }
}
function Run-Instrumentation([string[]]$TestArguments, [string]$ReportName, [bool]$AllowSkipped = $false) {
    $output = & $adb -s $Serial shell am instrument -w -r @TestArguments app.vanishr.android.test/androidx.test.runner.AndroidJUnitRunner
    $exitCode = $LASTEXITCODE
    $sanitized = ($output -join [Environment]::NewLine).Replace($credential, '[test-credential]')
    if ($photoAdminPassword) { $sanitized = $sanitized.Replace($photoAdminPassword, '[test-password]') }
    [System.IO.File]::WriteAllText((Join-Path $results $ReportName), $sanitized, [System.Text.UTF8Encoding]::new($false))
    Write-Output $sanitized
    if ($exitCode -ne 0 -or $sanitized -notmatch 'OK \(' -or $sanitized -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -2') {
        throw 'Signed-release instrumentation failed.'
    }
    if (-not $AllowSkipped -and $sanitized -match 'INSTRUMENTATION_STATUS_CODE: -4') { throw 'A required release test was skipped.' }
}
try {
    if ($RemotePhotos) {
        $photoAdminHandle = 'release_photo_' + [Guid]::NewGuid().ToString('N').Substring(0, 16)
        $photoAdminPassword = [Guid]::NewGuid().ToString()
        try {
            $registration = Invoke-RestMethod -Method Post -Uri ($RelayOrigin.GetLeftPart([UriPartial]::Authority) + '/auth/register') -ContentType 'application/json' -Body (@{handle=$photoAdminHandle;password=$photoAdminPassword} | ConvertTo-Json -Compress) -MaximumRedirection 0
            $photoUserId = ([guid]$registration.userId).ToString()
            $registration = $null
        } catch { throw 'Synthetic photo account creation failed; credentials were not displayed.' }
        Set-PhotoFixtureRole -Role ADMIN
    }
    & $adb -s $Serial shell locksettings set-pin $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The dedicated release emulator must have no existing credential.' }
    $credentialSet = $true
    & $adb -s $Serial shell locksettings verify --old $credential | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not authenticate the release fixture.' }
    Run-Instrumentation -TestArguments @('-e', 'class', 'app.vanishr.android.DeviceSecurityTest') -ReportName 'device-security.txt' -AllowSkipped $true
    if ($Live) {
        $arguments = @('-e', 'class', 'app.vanishr.android.ReleaseWorkflowTest', '-e', 'releaseLive', 'true',
            '-e', 'releasePush', $Push.IsPresent.ToString().ToLowerInvariant(),
            '-e', 'devicePin', $credential, '-e', 'relayOrigin', $RelayOrigin.GetLeftPart([System.UriPartial]::Authority))
        if ($RemotePhotos) { $arguments += @('-e', 'releaseRemotePhotos', 'true', '-e', 'photoAdminHandle', $photoAdminHandle, '-e', 'photoAdminPassword', $photoAdminPassword) }
        Run-Instrumentation -TestArguments $arguments -ReportName 'live-ui.txt'
        $arguments = $null
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
    $photoAdminPassword = $null
    if ($photoUserId) { Set-PhotoFixtureRole -Role USER }
}