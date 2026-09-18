#requires -Version 7.4
param([switch]$Android, [switch]$Release)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & mvn -B -ntp '-Dmaven.repo.local=.tools/m2' verify
    if ($LASTEXITCODE -ne 0) { throw 'JVM security verification failed.' }
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $root 'relay/target/relay-0.1.0-SNAPSHOT.jar'))
    try {
        $forbidden = @($archive.Entries | Where-Object { $_.FullName -match 'BOOT-INF/lib/(libsignal|client-core)' })
        if ($forbidden.Count -ne 0) { throw 'Relay artifact contains a client-only crypto dependency.' }
    } finally { $archive.Dispose() }
    if ($Android) {
        $tasks = @(':crypto:test', ':app:assembleDebug', ':app:lintDebug', ':app:assembleDebugAndroidTest')
        if ($Release) { $tasks += @(':app:assembleRelease', ':app:lintRelease') }
        & (Join-Path $PSScriptRoot 'build-android.ps1') -Tasks $tasks
    }
    Write-Output 'Verification passed. Review docs/VERIFICATION.md for device and production release gates.'
} finally { Pop-Location }