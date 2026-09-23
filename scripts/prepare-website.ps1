#requires -Version 7.4
param([ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.3.9', [switch]$Refresh)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
$source = Join-Path $root 'download'
$release = Join-Path $tools "vercel-download-$Version"
$stage = Join-Path $tools "vercel-website-$Version"
$audit = Get-Content (Join-Path $tools "distribution-audit-$Version.json") -Raw | ConvertFrom-Json
$public = Get-Content (Join-Path $tools "public-release-verification-$Version.json") -Raw | ConvertFrom-Json
if ($audit.version -ne $Version -or $public.version -ne $Version -or -not $audit.secretsExcluded -or -not $public.signingIdentityMatches) { throw 'A verified, already-published application release is required.' }
if ((Test-Path $stage) -and -not $Refresh) { throw 'Website stage already exists; use -Refresh explicitly for a revised website build.' }
$null = New-Item -ItemType Directory -Path $stage -Force
$artifacts = @('icon.png', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt', 'libsignal-0.102.3-source.tar.gz', 'updates.json', "vanishr-$Version.apk", "vanishr-$Version-source.zip")
foreach ($name in $artifacts) {
    $expected = @($audit.files | Where-Object name -eq $name)
    $file = Get-Item (Join-Path $release $name)
    if ($expected.Count -ne 1 -or $file.Length -ne $expected[0].bytes -or (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected[0].sha256) { throw "Application release artifact changed: $name" }
    Copy-Item $file.FullName (Join-Path $stage $name) -Force
}
$feed = Get-Content (Join-Path $stage 'updates.json') -Raw | ConvertFrom-Json
if ($feed.versionName -ne $Version -or $feed.sha256 -ne $public.apkSha256) { throw 'The website would point at a different APK.' }
$rendered = & (Join-Path $PSScriptRoot 'render-website.ps1') -Stage $stage
$settings = $rendered.Settings
$pages = $rendered.Pages
$expected = @($artifacts + $rendered.Files | Sort-Object)
$files = @(Get-ChildItem $stage -Recurse -File)
$actual = @($files | ForEach-Object { [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/') } | Sort-Object)
if (@(Compare-Object $expected $actual).Count -gt 0) { throw 'Unexpected files in website staging; nothing was published.' }
$total = ($files | Measure-Object Length -Sum).Sum
if ($total -ge 99000000) { throw 'Website bundle exceeds the approved static size limit.' }
$inventory = @($files | ForEach-Object { [ordered]@{ path = [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/'); bytes = $_.Length; sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } })
[ordered]@{
    version = $Version
    stage = $stage
    origin = $settings.origin
    bytes = $total
    pageCount = $pages.Count
    ga4MeasurementId = $settings.ga4MeasurementId
    searchConsoleConfigured = [bool]$settings.googleSiteVerification
    applicationArtifactSha256 = $public.apkSha256
    files = $inventory
    preparedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $tools "website-build-$Version.json")
Write-Output "Website prepared: $($files.Count) audited-path files, $total bytes; $($pages.Count) pages; application downloads unchanged."
Write-Output "Stage: $stage"