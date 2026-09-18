param([string[]]$Tasks = @(':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
$gradle = Join-Path $tools 'gradle-8.13\bin\gradle.bat'
if (-not (Test-Path $gradle)) {
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $archive = Join-Path $tools 'gradle-8.13-bin.zip'
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile $archive
    $expected = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Gradle checksum verification failed.' }
    Expand-Archive $archive -DestinationPath $tools -Force
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $env:ANDROID_HOME)) { throw 'Install Android SDK platform 36 and set ANDROID_HOME.' }
Push-Location (Join-Path $root 'android')
try {
    & $gradle --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
} finally { Pop-Location }