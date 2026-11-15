#requires -Version 7.4
# Makes the screenshots for the Google Play listing and for the home page of the website: real screens of the QA build on the dedicated test
# emulator, with made-up people, messages and pictures (docs/PLAY-STORE.md, "Listing screenshots"). Build the QA app and its test first:
#   .\scripts\build-android.ps1 -Tasks @('-PtestBuildType=qa', ':app:assembleQa', ':app:assembleQaAndroidTest', '--offline', '--daemon')
# Then, with the emulator running and nothing else using it:
#   .\scripts\make-store-screenshots.ps1
#       1080 x 2400, the emulator's own screen, into .tools\store-screenshots\raw\native (the website images are made from these)
#   .\scripts\make-store-screenshots.ps1 -Name 1080x1920 -Size 1080x1920 -Keep 'aarav:3,maya:3,noah:2,group:5'
#       1080 x 1920, the size Google Play asks for; a shorter screen holds fewer messages, so -Keep names how many of the newest
#       messages to make in a chat (aarav, maya, noah, group) so that no bubble is cut off at the top.
param(
    [string]$Name = 'native',
    [string]$Size,
    [string]$Keep = '',
    [string]$Out,
    [string]$Serial = 'emulator-5580'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK.' }
if ($Name -cnotmatch '^[A-Za-z0-9._-]{1,40}$') { throw 'The name of the folder may only hold letters, digits, dots, dashes and underscores.' }
if ($Size -and $Size -cnotmatch '^\d{3,4}x\d{3,4}$') { throw 'The size must look like 1080x1920.' }
if ($Keep -and $Keep -cnotmatch '^[a-z]+:\d{1,2}(,[a-z]+:\d{1,2})*$') { throw 'Keep must look like aarav:3,maya:3.' }
if (-not $Out) { $Out = Join-Path $root ('.tools\store-screenshots\raw\' + $Name) }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
# Only the dedicated test emulator: it is given a temporary screen lock, and its screen size and status bar are changed for a while.
$avd = (& $adb -s $Serial emu avd name 2>$null) | Select-Object -First 1
if (-not $avd -or $avd.Trim() -ne 'Vanishr_Security_Test') { throw 'Screenshots are made only on the dedicated Vanishr_Security_Test emulator.' }
function Demo([string[]]$arguments) { & $adb -s $Serial shell am broadcast -a com.android.systemui.demo @arguments | Out-Null }
$pin = [System.Security.Cryptography.RandomNumberGenerator]::GetInt32(100000, 999999).ToString()
$pinSet = $false
try {
    if ($Size) { & $adb -s $Serial shell wm size $Size | Out-Null; Start-Sleep -Seconds 4 }
    'display: ' + ((& $adb -s $Serial shell wm size) -join ' ') + '; navigation_mode=' + (& $adb -s $Serial shell settings get secure navigation_mode)
    # A clean status bar: the time of the phone, a full battery and Wi-Fi, and no notification icons. The emulator draws its mobile
    # connection as "3G", which a phone does not, so that icon is left out.
    $hhmm = ((& $adb -s $Serial shell date +%H%M) -join '').Trim()
    & $adb -s $Serial shell settings put global sysui_demo_allowed 1 | Out-Null
    Demo @('-e', 'command', 'enter')
    Demo @('-e', 'command', 'clock', '-e', 'hhmm', $hhmm)
    Demo @('-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false')
    Demo @('-e', 'command', 'network', '-e', 'wifi', 'show', '-e', 'level', '4', '-e', 'fully', 'true')
    Demo @('-e', 'command', 'network', '-e', 'mobile', 'hide')
    Demo @('-e', 'command', 'notifications', '-e', 'visible', 'false')
    & $adb -s $Serial install -r (Join-Path $root 'android\app\build\outputs\apk\qa\app-qa.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the QA app.' }
    & $adb -s $Serial install -r (Join-Path $root 'android\app\build\outputs\apk\androidTest\qa\app-qa-androidTest.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the QA instrumentation.' }
    & $adb -s $Serial shell pm clear app.vanishr.android.qa | Out-Null
    & $adb -s $Serial shell pm grant app.vanishr.android.qa android.permission.POST_NOTIFICATIONS | Out-Null
    & $adb -s $Serial shell locksettings set-pin $pin | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Use a fresh dedicated test emulator without an existing credential.' }
    $pinSet = $true
    & $adb -s $Serial shell svc power stayon true | Out-Null
    $keepArguments = if ($Keep) { @('-e', 'storeKeep', $Keep) } else { @() }
    $result = & $adb -s $Serial shell am instrument -w -r -e devicePin $pin -e storeShots $Name @keepArguments -e class 'app.vanishr.android.ScreenFlowTest#storeListingScreenshots' app.vanishr.android.qa.test/androidx.test.runner.AndroidJUnitRunner
    $text = $result -join "`n"
    ($text -split "`n") | Where-Object { $_ -match '^OK \(|^FAILURES|Tests run:|INSTRUMENTATION_RESULT: shortMsg|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS: stack|AssertionError|Exception' } | Select-Object -First 12
    $codes = [regex]::Matches($text, 'INSTRUMENTATION_STATUS_CODE: (-?\d+)') | ForEach-Object { $_.Groups[1].Value }
    'status codes: ' + ($codes -join ',') + '  (1 = start, 0 = made, -2 = failed, -4 = skipped)'
    if ($codes -notcontains '0') { throw 'The screenshots were not made.' }
    $null = New-Item -ItemType Directory -Force $Out
    & $adb -s $Serial pull "/sdcard/Android/data/app.vanishr.android.qa/files/store-shots/$Name/." $Out 2>&1 | Select-Object -Last 2
} finally {
    if ($pinSet) {
        & $adb -s $Serial shell locksettings clear --old $pin | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'The temporary test credential could not be cleared. Recreate only the dedicated test emulator.' }
    }
    & $adb -s $Serial shell svc power stayon false | Out-Null
    try { Demo @('-e', 'command', 'exit') } catch { }
    if ($Size) { & $adb -s $Serial shell wm size reset | Out-Null }
    & $adb -s $Serial shell pm clear app.vanishr.android.qa | Out-Null
}
