#requires -Version 7.4
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'new-local-config.ps1')
    if (-not $SkipBuild) {
        & mvn -B -ntp '-Dmaven.repo.local=.tools/m2' package
        if ($LASTEXITCODE -ne 0) { throw 'Verification failed; local relay was not started.' }
    }
    & docker compose --env-file .secrets/local.env -f infra/compose.yml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Container configuration is invalid.' }
    & docker compose --env-file .secrets/local.env -f infra/compose.yml up --build --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Local services did not become healthy.' }
    $portLine = Get-Content .secrets/local.env | Where-Object { $_ -match '^PORT=\d+$' }
    $port = ($portLine -split '=', 2)[1]
    Write-Output "Relay ready: https://localhost:$port/health"
    Write-Output "Android emulator origin: https://10.0.2.2:$port"
} finally { Pop-Location }