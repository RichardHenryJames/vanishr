#requires -Version 7.4
param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Drawing

function Write-Asset([string]$RelativePath, [byte[]]$Bytes) {
    $path = Join-Path $root $RelativePath
    if ($Verify) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing brand asset: $RelativePath" }
        $expected = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes))
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $expected) {
            throw "Brand asset is stale: $RelativePath. Run scripts\prepare-brand-assets.ps1 before rebuilding."
        }
    } else {
        $null = New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force
        [IO.File]::WriteAllBytes($path, $Bytes)
    }
}

function New-LogoPng([int]$Size, [int]$ArtworkSize, [bool]$Monochrome = $false, [bool]$SolidBackground = $false, [bool]$Adaptive = $false) {
    $bitmap = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $stream = [IO.MemoryStream]::new()
    try {
        $background = if ($SolidBackground) { [Drawing.ColorTranslator]::FromHtml('#F5F8F6') } else { [Drawing.Color]::Transparent }
        $graphics.Clear($background)
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $scale = $ArtworkSize / [Math]::Max($logo.Width, $logo.Height)
        if ($Adaptive) { $scale = [Math]::Min($scale, ($Size * 33 / 108 - 2) / $logoRadius) }
        $width = [int][Math]::Round($logo.Width * $scale)
        $height = [int][Math]::Round($logo.Height * $scale)
        $target = [Drawing.Rectangle]::new([int][Math]::Round(($Size - $width) / 2), [int][Math]::Round(($Size - $height) / 2), $width, $height)
        $graphics.DrawImage($logo, $target)
        if ($Monochrome -or $Adaptive) {
            for ($y = 0; $y -lt $Size; $y++) {
                for ($x = 0; $x -lt $Size; $x++) {
                    $pixel = $bitmap.GetPixel($x, $y)
                    if ($Adaptive -and $pixel.A -gt 8) {
                        $distanceSquared = [Math]::Pow($x + 0.5 - $Size / 2, 2) + [Math]::Pow($y + 0.5 - $Size / 2, 2)
                        if ($distanceSquared -gt [Math]::Pow($Size * 33 / 108, 2)) { throw 'Launcher artwork exceeds the adaptive-icon safe circle.' }
                    }
                    if ($Monochrome) { $bitmap.SetPixel($x, $y, [Drawing.Color]::FromArgb($pixel.A, 255, 255, 255)) }
                }
            }
        }
        $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
        return ,$stream.ToArray()
    } finally { $stream.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}

$original = [Drawing.Bitmap]::FromFile((Join-Path $root 'Vanishr Icon.png'))
$logo = $null
try {
    $left = $original.Width; $top = $original.Height; $right = -1; $bottom = -1
    # Ignore nearly transparent export noise around the supplied artwork.
    for ($y = 0; $y -lt $original.Height; $y++) {
        for ($x = 0; $x -lt $original.Width; $x++) {
            if ($original.GetPixel($x, $y).A -le 8) { continue }
            $left = [Math]::Min($left, $x); $top = [Math]::Min($top, $y)
            $right = [Math]::Max($right, $x); $bottom = [Math]::Max($bottom, $y)
        }
    }
    if ($right -lt $left -or $bottom -lt $top) { throw 'The supplied logo is empty.' }
    $bounds = [Drawing.Rectangle]::new($left, $top, $right - $left + 1, $bottom - $top + 1)
    $logo = $original.Clone($bounds, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $logoRadius = 0.0
    for ($y = 0; $y -lt $logo.Height; $y++) {
        for ($x = 0; $x -lt $logo.Width; $x++) {
            if ($logo.GetPixel($x, $y).A -le 8) { continue }
            $radius = [Math]::Sqrt([Math]::Pow($x + 0.5 - $logo.Width / 2, 2) + [Math]::Pow($y + 0.5 - $logo.Height / 2, 2))
            $logoRadius = [Math]::Max($logoRadius, $radius)
        }
    }
    if ($logoRadius -le 0) { throw 'The supplied logo has no visible area.' }
    Write-Asset 'android\app\src\main\res\drawable-nodpi\vanishr_logo.png' (New-LogoPng 256 232)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_launcher_foreground.png' (New-LogoPng 432 240 -Adaptive $true)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_launcher_monochrome.png' (New-LogoPng 432 240 -Monochrome $true -Adaptive $true)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_stat_vanishr.png' (New-LogoPng 96 80 -Monochrome $true)
    Write-Asset 'download\icon.png' (New-LogoPng 192 176)
    Write-Asset 'download\apple-touch-icon.png' (New-LogoPng 180 160 -SolidBackground $true)

    $sizes = @(16, 32, 48)
    $images = @($sizes | ForEach-Object { ,(New-LogoPng $_ ([int][Math]::Round($_ * 0.9))) })
    $icon = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($icon)
    try {
        $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
        $offset = 6 + 16 * $sizes.Count
        for ($index = 0; $index -lt $sizes.Count; $index++) {
            $writer.Write([byte]$sizes[$index]); $writer.Write([byte]$sizes[$index])
            $writer.Write([byte]0); $writer.Write([byte]0)
            $writer.Write([uint16]1); $writer.Write([uint16]32)
            $writer.Write([uint32]$images[$index].Length); $writer.Write([uint32]$offset)
            $offset += $images[$index].Length
        }
        foreach ($image in $images) { $writer.Write([byte[]]$image) }
        $writer.Flush()
        Write-Asset 'download\favicon.ico' $icon.ToArray()
    } finally { $writer.Dispose(); $icon.Dispose() }
} finally {
    if ($null -ne $logo) { $logo.Dispose() }
    $original.Dispose()
}
Write-Output "$(if ($Verify) { 'Verified' } else { 'Prepared' }) seven app and website assets from Vanishr Icon.png."
