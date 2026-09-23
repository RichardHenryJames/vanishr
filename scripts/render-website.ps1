#requires -Version 7.4
param([Parameter(Mandatory)][string]$Stage)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root 'download'
$Stage = (Resolve-Path $Stage).Path
$settings = Get-Content (Join-Path $source 'site-settings.json') -Raw | ConvertFrom-Json
if ($settings.origin -cne 'https://vanishr-download.vercel.app' -or @($settings.PSObject.Properties.Name | Where-Object { $_ -notin @('origin', 'ga4MeasurementId', 'googleSiteVerification') }).Count -gt 0) { throw 'Unexpected public website settings.' }
if ($settings.ga4MeasurementId -and $settings.ga4MeasurementId -cnotmatch '^G-[A-Z0-9]{6,20}$') { throw 'Invalid public GA4 Measurement ID.' }
if ($settings.googleSiteVerification -and $settings.googleSiteVerification -cnotmatch '^[A-Za-z0-9_-]{20,200}$') { throw 'Invalid public Search Console verification value.' }
$feed = Get-Content (Join-Path $Stage 'updates.json') -Raw | ConvertFrom-Json
$version = [string]$feed.versionName
if ($version -cnotmatch '^\d+\.\d+\.\d+$' -or $feed.versionCode -le 0 -or $feed.apkUrl -cne "$($settings.origin)/vanishr-$version.apk") { throw 'Invalid website release metadata.' }
$apk = Get-Item (Join-Path $Stage "vanishr-$version.apk")
if ($apk.Length -ne $feed.size -or (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $feed.sha256) { throw 'Website release metadata does not match the APK.' }
$assets = @('app-overview.jpg', 'app-overview-mobile.jpg', 'social.jpg', 'conversations.png', 'chat.png', 'profile.png', 'manrope.ttf', 'MANROPE-OFL.txt', 'lucide.min.js', 'LUCIDE-LICENSE.txt')
$static = @('site.css', 'site.js', 'robots.txt', 'sitemap.xml', 'llms.txt') + @($assets | ForEach-Object { 'assets/' + $_ })
foreach ($relative in $static) {
    $target = Join-Path $Stage $relative
    $null = New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force
    Copy-Item (Join-Path $source $relative) $target -Force
}
$consentPanel = @'
<aside class="consent-strip" id="analytics-consent" aria-label="Website analytics choice" hidden>
  <div class="wrap consent-inner">
    <div class="consent-copy"><h2>Help improve Vanishr</h2><p>Allow Google Analytics for site visits and downloads? No chat data. <a href="/privacy/#analytics">Privacy</a></p><p data-consent-status data-consent-note hidden></p></div>
    <div class="consent-actions"><button class="button secondary" type="button" data-consent-deny>No thanks</button><button class="button primary" type="button" data-consent-allow>Allow</button></div>
  </div>
</aside>
'@
$verification = if ($settings.googleSiteVerification) { '<meta name="google-site-verification" content="' + [System.Net.WebUtility]::HtmlEncode($settings.googleSiteVerification) + '">' } else { '' }
$analyticsStatus = if ($settings.ga4MeasurementId) { 'Optional Google Analytics is available on this website. It is disabled until you give consent.' } else { 'Google Analytics is not configured on this website yet. No Google Analytics requests are sent.' }
$replacements = [ordered]@{
    '__VERSION__' = $version
    '__VERSION_CODE__' = [string]$feed.versionCode
    '__APK_SHA256__' = $feed.sha256
    '__APK_SIZE__' = [math]::Round($feed.size / 1MB, 1).ToString([Globalization.CultureInfo]::InvariantCulture)
    '__RELEASED_ON__' = $apk.LastWriteTimeUtc.ToString('d MMMM yyyy', [Globalization.CultureInfo]::InvariantCulture)
    '__GA4_ID__' = [string]$settings.ga4MeasurementId
    '__SEARCH_VERIFICATION__' = $verification
    '__CONSENT_PANEL__' = $consentPanel
    '__ANALYTICS_STATUS__' = $analyticsStatus
}
$pages = @('index.html', 'android/index.html', 'security/index.html', 'privacy/index.html')
$schemaHashes = [System.Collections.Generic.HashSet[string]]::new()
foreach ($relative in $pages) {
    $html = Get-Content (Join-Path $source $relative) -Raw
    foreach ($replacement in $replacements.GetEnumerator()) { $html = $html.Replace($replacement.Key, $replacement.Value) }
    if ($html -match '__[A-Z_]+__') { throw "Unresolved website placeholder: $relative" }
    foreach ($schema in [regex]::Matches($html, '(?s)<script type="application/ld\+json">(.*?)</script>')) {
        $null = $schema.Groups[1].Value | ConvertFrom-Json
        $hash = [Convert]::ToBase64String([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($schema.Groups[1].Value)))
        $null = $schemaHashes.Add("'sha256-$hash'")
    }
    $target = Join-Path $Stage $relative
    $null = New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force
    [IO.File]::WriteAllText($target, $html, [Text.UTF8Encoding]::new($false))
}
  [xml]$sitemap = Get-Content (Join-Path $source 'sitemap.xml') -Raw
  foreach ($entry in $sitemap.urlset.url) {
    $uri = [uri]$entry.loc
    if ($uri.GetLeftPart([UriPartial]::Authority) -cne $settings.origin) { throw 'Sitemap contains a different website origin.' }
    $relative = $uri.AbsolutePath.TrimStart('/') + 'index.html'
    if ($relative -notin $pages) { throw 'Sitemap contains an unpublished page.' }
    $modified = @((Get-Item (Join-Path $source $relative)).LastWriteTimeUtc, (Get-Item (Join-Path $source 'site-settings.json')).LastWriteTimeUtc)
    if ($relative -in @('index.html', 'android/index.html')) { $modified += $apk.LastWriteTimeUtc }
    $entry.lastmod = ($modified | Sort-Object -Descending | Select-Object -First 1).ToString('yyyy-MM-dd')
  }
  $sitemap.Save((Join-Path $Stage 'sitemap.xml'))
$config = Get-Content (Join-Path $source 'vercel.json') -Raw | ConvertFrom-Json
$policy = "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'none'; style-src 'self'; font-src 'self'; img-src 'self' https://www.google-analytics.com https://region1.google-analytics.com; script-src 'self' https://www.googletagmanager.com $($schemaHashes -join ' '); connect-src 'self' https://www.google-analytics.com https://region1.google-analytics.com https://analytics.google.com; upgrade-insecure-requests"
($config.headers[0].headers | Where-Object key -eq 'Content-Security-Policy').value = $policy
$config.headers[1].source = "/vanishr-$version.apk"
($config.headers[1].headers | Where-Object key -eq 'Content-Disposition').value = "attachment; filename=vanishr-$version.apk"
[IO.File]::WriteAllText((Join-Path $Stage 'vercel.json'), ($config | ConvertTo-Json -Depth 16), [Text.UTF8Encoding]::new($false))
[pscustomobject]@{ Settings = $settings; Pages = $pages; Files = @($static + $pages + @('vercel.json')) }