#requires -Version 7.4
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$resources = Join-Path $root 'android/app/src/main/res'
$licenses = Join-Path $root 'android/app/src/main/assets/legal'
foreach ($path in @((Join-Path $resources 'drawable'), (Join-Path $resources 'font'), $licenses)) {
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}
Invoke-WebRequest 'https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf' -OutFile (Join-Path $resources 'font/manrope.ttf')
Invoke-WebRequest 'https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt' -OutFile (Join-Path $licenses 'MANROPE-OFL.txt')
Invoke-WebRequest 'https://cdn.jsdelivr.net/npm/lucide-static@0.468.0/LICENSE' -OutFile (Join-Path $licenses 'LUCIDE-LICENSE.txt')
$icons = @('arrow-left', 'arrow-up', 'plus', 'lock-keyhole', 'shield-check', 'message-circle', 'image', 'camera',
    'check', 'check-check', 'clock-3', 'eye', 'eye-off', 'settings-2', 'log-out', 'x', 'search', 'chevron-right',
    'ellipsis-vertical', 'fingerprint', 'wifi-off', 'file-text', 'key-round', 'scan-line', 'bell', 'trash-2', 'circle-alert',
    'users-round', 'square-pen', 'list-filter', 'user-round-plus')
$androidNamespace = 'http://schemas.android.com/apk/res/android'
function Convert-PointList([string]$points) {
    $coordinates = [regex]::Split($points.Trim(), '[,\s]+')
    if ($coordinates.Count -lt 4 -or $coordinates.Count % 2 -ne 0) { throw 'An SVG point list must contain coordinate pairs.' }
    $pairs = for ($index = 0; $index -lt $coordinates.Count; $index += 2) {
        "$($coordinates[$index]),$($coordinates[$index + 1])"
    }
    return 'M ' + ($pairs -join ' L ')
}
foreach ($icon in $icons) {
    $response = Invoke-WebRequest "https://cdn.jsdelivr.net/npm/lucide-static@0.468.0/icons/$icon.svg"
    [xml]$svg = [string]$response.Content
    $vector = [System.Xml.XmlDocument]::new()
    $declaration = $vector.CreateXmlDeclaration('1.0', 'utf-8', $null)
    $null = $vector.AppendChild($declaration)
    $element = $vector.CreateElement('vector')
    $element.SetAttribute('xmlns:android', $androidNamespace)
    foreach ($pair in @{width='24dp';height='24dp';viewportWidth='24';viewportHeight='24'}.GetEnumerator()) {
        $element.SetAttribute($pair.Key, $androidNamespace, $pair.Value)
    }
    $null = $vector.AppendChild($element)
    foreach ($shape in $svg.DocumentElement.ChildNodes) {
        $data = switch ($shape.LocalName) {
            'path' { $shape.GetAttribute('d') }
            'line' { "M $($shape.x1),$($shape.y1) L $($shape.x2),$($shape.y2)" }
            'polyline' { Convert-PointList $shape.points }
            'polygon' { (Convert-PointList $shape.points) + ' Z' }
            'circle' {
                $centerX = [double]::Parse($shape.cx, [Globalization.CultureInfo]::InvariantCulture)
                $centerY = [double]::Parse($shape.cy, [Globalization.CultureInfo]::InvariantCulture)
                $radius = [double]::Parse($shape.r, [Globalization.CultureInfo]::InvariantCulture)
                "M $($centerX - $radius),$centerY a $radius,$radius 0 1,0 $($radius * 2),0 a $radius,$radius 0 1,0 $(-$radius * 2),0"
            }
            'rect' {
                $left = [double]$shape.x; $top = [double]$shape.y
                $right = $left + [double]$shape.width; $bottom = $top + [double]$shape.height
                $radius = if ($shape.HasAttribute('rx')) { [double]$shape.rx } else { 0 }
                "M $($left+$radius),$top H $($right-$radius) Q $right,$top $right,$($top+$radius) V $($bottom-$radius) Q $right,$bottom $($right-$radius),$bottom H $($left+$radius) Q $left,$bottom $left,$($bottom-$radius) V $($top+$radius) Q $left,$top $($left+$radius),$top Z"
            }
            default { $null }
        }
        if (-not $data) { continue }
        $path = $vector.CreateElement('path')
        foreach ($pair in @{pathData=$data;fillColor='@android:color/transparent';strokeColor='#182724';strokeWidth='1.8';strokeLineCap='round';strokeLineJoin='round'}.GetEnumerator()) {
            $path.SetAttribute($pair.Key, $androidNamespace, $pair.Value)
        }
        $null = $element.AppendChild($path)
    }
    $fileName = 'ic_' + $icon.Replace('-', '_') + '.xml'
    $vector.Save((Join-Path $resources "drawable/$fileName"))
}
Write-Output "Prepared Manrope and $($icons.Count) Lucide vector assets with upstream licenses."