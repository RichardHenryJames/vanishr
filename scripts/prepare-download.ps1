#requires -Version 7.4
param([switch]$PrepareNoticesOnly, [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.4.1')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
$dependencies = Get-Content (Join-Path $root 'android/app/build/reports/distribution/dependencies.json') -Raw | ConvertFrom-Json
$noticesDirectory = Join-Path $root 'android/app/src/main/assets/legal'
New-Item -ItemType Directory -Path $noticesDirectory -Force | Out-Null
$text = [System.Text.StringBuilder]::new()
$null = $text.AppendLine((Get-Content (Join-Path $root 'NOTICE.md') -Raw))
foreach ($notice in @('MANROPE-OFL.txt', 'LUCIDE-LICENSE.txt')) {
    $null = $text.AppendLine((Get-Content (Join-Path $noticesDirectory $notice) -Raw))
}
$inventory = @($dependencies | Select-Object group, name, version -Unique)
foreach ($dependency in $inventory) { $null = $text.AppendLine("$($dependency.group):$($dependency.name):$($dependency.version)") }
$null = $text.AppendLine((Get-Content (Join-Path $tools 'libsignal-android-notices.md') -Raw))
foreach ($dependency in $dependencies) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($dependency.file)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '(?i)(^|/)(LICENSE|NOTICE|COPYING|COPYRIGHT)([._-].*)?$' -or $entry.Length -gt 1MB) { continue }
            $reader = [System.IO.StreamReader]::new($entry.Open())
            try {
                $null = $text.AppendLine("`n--- $($dependency.group):$($dependency.name):$($dependency.version) / $($entry.FullName) ---")
                $null = $text.AppendLine($reader.ReadToEnd())
            } finally { $reader.Dispose() }
        }
    } finally { $archive.Dispose() }
}
Copy-Item (Join-Path $root 'LICENSE') (Join-Path $noticesDirectory 'LICENSE.txt') -Force
[System.IO.File]::WriteAllText((Join-Path $noticesDirectory 'NOTICES.txt'), $text.ToString(), [System.Text.UTF8Encoding]::new($false))
if ($PrepareNoticesOnly) {
    Write-Output "Prepared notices for $($inventory.Count) runtime components. Rebuild the signed APK before packaging downloads."
    return
}

$apkFile = Join-Path $root 'android/app/build/outputs/apk/release/app-release.apk'
$metadata = Get-Content (Join-Path $root 'android/app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.elements[0].versionName -ne $Version) { throw 'The APK version does not match the requested distribution version.' }
[xml]$manifest = Get-Content (Join-Path $root 'android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml') -Raw
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$versionCode = [int]$metadata.elements[0].versionCode
$minSdk = [int]$manifest.manifest.'uses-sdk'.GetAttribute('minSdkVersion', $androidNamespace)
if ($versionCode -le 0 -or $minSdk -lt 28 -or $manifest.manifest.package -ne 'app.vanishr.android' -or
    $manifest.manifest.GetAttribute('versionName', $androidNamespace) -ne $Version -or
    [int]$manifest.manifest.GetAttribute('versionCode', $androidNamespace) -ne $versionCode) {
    throw 'The merged Android manifest does not match the release metadata.'
}
$apkArchive = [System.IO.Compression.ZipFile]::OpenRead($apkFile)
try {
    $entry = $apkArchive.GetEntry('assets/legal/NOTICES.txt')
    if ($null -eq $entry) { throw 'The APK is missing bundled license notices.' }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { if ($reader.ReadToEnd() -ne $text.ToString()) { throw 'Rebuild the signed APK with the current notices before preparing downloads.' } }
    finally { $reader.Dispose() }
} finally { $apkArchive.Dispose() }
$stage = Join-Path $tools "vercel-download-$Version"
$source = Join-Path $tools "distribution-source-$Version"
foreach ($directory in @($stage, $source)) {
    if (Test-Path $directory) { throw 'Distribution staging already exists. Use a new staging directory or explicitly remove the previous generated output.' }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
$sourcePaths = @('LICENSE', 'NOTICE.md', 'README.md', 'pom.xml', '.gitignore', '.dockerignore',
    'android/build.gradle', 'android/settings.gradle', 'android/gradle.properties', 'android/app/build.gradle', 'android/app/proguard-rules.pro',
    'android/app/src', 'client-core/pom.xml', 'client-core/build.gradle', 'client-core/src', 'relay/pom.xml', 'relay/src',
    'docs/API.md', 'docs/ARCHITECTURE.md', 'docs/ENCRYPTION.md', 'docs/THREAT-MODEL.md', 'docs/VERIFICATION.md', 'docs/GOOGLE-SETUP.md',
    'infra/Dockerfile', 'infra/compose.yml', 'infra/compose.low-memory.yml', 'infra/pg_hba.conf', 'infra/postgres-entrypoint.sh',
    'scripts/build-android.ps1', 'scripts/new-local-config.ps1', 'scripts/package-apk.ps1', 'scripts/start-local.ps1', 'scripts/test-android.ps1',
    'scripts/test-release.ps1', 'scripts/prepare-ui-assets.ps1', 'scripts/verify.ps1', 'scripts/read-google-config.ps1', 'scripts/prepare-download.ps1',
    'scripts/render-website.ps1', 'scripts/prepare-website.ps1', 'scripts/prepare-site-assets.ps1',
    'download/index.html', 'download/site.css', 'download/site.js', 'download/site-settings.json', 'download/vercel.json',
    'download/android', 'download/security', 'download/privacy', 'download/assets', 'download/robots.txt', 'download/sitemap.xml', 'download/llms.txt')
foreach ($relative in $sourcePaths) {
    $destination = Join-Path $source $relative
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item (Join-Path $root $relative) $destination -Recurse
}
$dependencySource = Join-Path $source 'dependency-sources'
New-Item -ItemType Directory -Path $dependencySource -Force | Out-Null
$unavailable = [System.Collections.Generic.List[string]]::new()
foreach ($dependency in $inventory) {
    $coordinate = "$($dependency.group):$($dependency.name):$($dependency.version)"
    $relative = "$($dependency.group.Replace('.', '/'))/$($dependency.name)/$($dependency.version)/$($dependency.name)-$($dependency.version)"
    $baseUrl = if ($dependency.group -eq 'org.signal') { 'https://build-artifacts.signal.org/libraries/maven' }
        elseif ($dependency.group -like 'androidx.*' -or $dependency.group -like 'com.google.android.*' -or $dependency.group -eq 'com.google.firebase') { 'https://dl.google.com/dl/android/maven2' }
        else { 'https://repo.maven.apache.org/maven2' }
    $prefix = Join-Path $dependencySource ($coordinate.Replace(':', '_'))
    foreach ($suffix in @('.pom', '-sources.jar')) {
        try { Invoke-WebRequest "$baseUrl/$relative$suffix" -OutFile "$prefix$suffix" }
        catch {
            if ($_.Exception.Response.StatusCode -ne 404 -and $_.Exception.Response.StatusCode -ne 403) { throw "Dependency source retrieval failed: $coordinate" }
            if (Test-Path "$prefix$suffix") { Remove-Item -LiteralPath "$prefix$suffix" }
            $unavailable.Add("$coordinate$suffix : upstream source location $baseUrl/$relative$suffix is not published or access is restricted")
        }
    }
}
[System.IO.File]::WriteAllText((Join-Path $dependencySource 'inventory.json'), ($inventory | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllLines((Join-Path $dependencySource 'unavailable-artifacts.txt'), $unavailable, [System.Text.UTF8Encoding]::new($false))
if (Get-ChildItem $source -Recurse -File | Where-Object { $_.FullName -match '[\\/]\.secrets[\\/]|[\\/]\.env($|\.)|\.(p12|pem|keystore|jks)$|id_rsa|deployment\.json' }) {
    throw 'A forbidden private artifact was found in the source bundle.'
}
Compress-Archive -Path (Join-Path $source '*') -DestinationPath (Join-Path $stage "vanishr-$Version-source.zip")
Copy-Item $apkFile (Join-Path $stage "vanishr-$Version.apk")
Copy-Item (Join-Path $tools 'libsignal-v0.102.3.tar.gz') (Join-Path $stage 'libsignal-0.102.3-source.tar.gz')
Copy-Item (Join-Path $noticesDirectory 'LICENSE.txt') (Join-Path $stage 'LICENSE.txt')
Copy-Item (Join-Path $noticesDirectory 'NOTICES.txt') (Join-Path $stage 'THIRD-PARTY-NOTICES.txt')
$apk = Get-Item (Join-Path $stage "vanishr-$Version.apk")
$hash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$update = [ordered]@{
    schemaVersion = 1
    versionCode = $versionCode
    versionName = $Version
    minSdk = $minSdk
    apkUrl = "https://vanishr-download.vercel.app/vanishr-$Version.apk"
    sha256 = $hash
    size = $apk.Length
}
[System.IO.File]::WriteAllText((Join-Path $stage 'updates.json'), ($update | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.Drawing
$bitmap = [System.Drawing.Bitmap]::new(152, 152)
$drawing = [System.Drawing.Graphics]::FromImage($bitmap)
$font = [System.Drawing.Font]::new('Georgia', 74, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
try {
    $drawing.Clear([System.Drawing.Color]::FromArgb(20, 100, 72))
    $drawing.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $drawing.DrawString('V', $font, $brush, 38, 29)
    $bitmap.Save((Join-Path $stage 'icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally { $brush.Dispose(); $font.Dispose(); $drawing.Dispose(); $bitmap.Dispose() }
$null = & (Join-Path $PSScriptRoot 'render-website.ps1') -Stage $stage
$total = (Get-ChildItem $stage -Recurse -File | Measure-Object Length -Sum).Sum
if ($total -ge 99000000) { throw 'Static bundle exceeds the safe Vercel Hobby upload size.' }
Get-ChildItem $stage -Recurse -File | Select-Object Name, Length
Write-Output "Static deployment bytes: $total. No Azure credentials, private signing keys or server environment files are included."
Write-Output "Dependencies with unpublished optional source artifacts: $($unavailable.Count). Inspect dependency-sources/unavailable-artifacts.txt before redistribution."