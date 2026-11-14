#requires -Version 7.4
# Makes every logo file of the app and the website from one picture, "Overlapping Green Chat Bubbles.png": a square, flat-coloured
# background with the artwork on it. With -Verify nothing is written; it stops if a committed file is not what this picture gives.
# -PlayIcon <path> also writes the 512 x 512 icon that the Google Play listing asks for (outside the repository, not verified).
param([switch]$Verify, [string]$PlayIcon)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$sourceName = 'Overlapping Green Chat Bubbles.png'
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

function Save-Png([Drawing.Bitmap]$Bitmap) {
    $stream = [IO.MemoryStream]::new()
    try { $Bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png); return ,$stream.ToArray() }
    finally { $stream.Dispose() }
}

function Resize-Once([Drawing.Bitmap]$Bitmap, [int]$Width, [int]$Height) {
    $result = [Drawing.Bitmap]::new($Width, $Height, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($result)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($Bitmap, [Drawing.Rectangle]::new(0, 0, $Width, $Height), 0, 0, $Bitmap.Width, $Bitmap.Height, [Drawing.GraphicsUnit]::Pixel, $attributes)
        return $result
    } catch { $result.Dispose(); throw } finally { $attributes.Dispose(); $graphics.Dispose() }
}

# Halves until within twice the size wanted, so that a large picture does not alias when it is made small.
function Get-Scaled([Drawing.Bitmap]$Bitmap, [int]$Width, [int]$Height) {
    $current = $Bitmap
    while ($current.Width -ge $Width * 2 -and $current.Height -ge $Height * 2) {
        $next = Resize-Once $current ([int][Math]::Ceiling($current.Width / 2)) ([int][Math]::Ceiling($current.Height / 2))
        if (-not [object]::ReferenceEquals($current, $Bitmap)) { $current.Dispose() }
        $current = $next
    }
    $final = Resize-Once $current $Width $Height
    if (-not [object]::ReferenceEquals($current, $Bitmap)) { $current.Dispose() }
    return $final
}

# White, with the given alpha at every pixel (the colour of an icon that Android tints itself).
function New-AlphaBitmap([byte[]]$Alpha, [int]$Size) {
    $bitmap = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $data = $bitmap.LockBits([Drawing.Rectangle]::new(0, 0, $Size, $Size), [Drawing.Imaging.ImageLockMode]::WriteOnly, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $buffer = [byte[]]::new($data.Stride * $Size)
        [Array]::Fill($buffer, [byte]255)
        for ($y = 0; $y -lt $Size; $y++) {
            $row = $y * $data.Stride; $from = $y * $Size
            for ($x = 0; $x -lt $Size; $x++) { $buffer[$row + $x * 4 + 3] = $Alpha[$from + $x] }
        }
        [Runtime.InteropServices.Marshal]::Copy($buffer, 0, $data.Scan0, $buffer.Length)
    } finally { $bitmap.UnlockBits($data) }
    return $bitmap
}

# Android tints a monochrome icon itself and reads only its alpha, so every pixel is exactly white; resampling would otherwise leave
# the faint edge pixels slightly grey.
function Set-White([Drawing.Bitmap]$Bitmap) {
    $data = $Bitmap.LockBits([Drawing.Rectangle]::new(0, 0, $Bitmap.Width, $Bitmap.Height), [Drawing.Imaging.ImageLockMode]::ReadWrite, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $buffer = [byte[]]::new($data.Stride * $Bitmap.Height)
        [Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buffer, 0, $buffer.Length)
        for ($offset = 0; $offset -lt $buffer.Length; $offset += 4) { $buffer[$offset] = 255; $buffer[$offset + 1] = 255; $buffer[$offset + 2] = 255 }
        [Runtime.InteropServices.Marshal]::Copy($buffer, 0, $data.Scan0, $buffer.Length)
    } finally { $Bitmap.UnlockBits($data) }
}

function New-RoundedPath([float]$Size, [float]$Radius) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $d = $Radius * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($Size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
    $path.AddArc(0, $Size - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

$picture = [Drawing.Bitmap]::FromFile((Join-Path $root $sourceName))
$argb = $null; $monochromeSource = $null; $silhouetteSource = $null
try {
    if ($picture.Width -ne $picture.Height -or $picture.Width -lt 512) { throw "$sourceName must be a square picture of at least 512 pixels." }
    $size = $picture.Width
    $argb = $picture.Clone([Drawing.Rectangle]::new(0, 0, $size, $size), [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $data = $argb.LockBits([Drawing.Rectangle]::new(0, 0, $size, $size), [Drawing.Imaging.ImageLockMode]::ReadOnly, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $pixels = [byte[]]::new($stride * $size)
    [Runtime.InteropServices.Marshal]::Copy($data.Scan0, $pixels, 0, $pixels.Length)
    $argb.UnlockBits($data)

    # The background is the commonest value of each channel in a frame around the picture, and the frame has to be flat.
    $frame = [int]($size / 20)
    $histogram = @([int[]]::new(256), [int[]]::new(256), [int[]]::new(256))
    $samples = 0
    for ($y = 0; $y -lt $size; $y += 2) {
        $wholeRow = $y -lt $frame -or $y -ge $size - $frame
        for ($x = 0; $x -lt $size; $x += 2) {
            if (-not ($wholeRow -or $x -lt $frame -or $x -ge $size - $frame)) { $x = $size - $frame - 1; continue }
            $i = $y * $stride + $x * 4
            $histogram[0][$pixels[$i + 2]]++; $histogram[1][$pixels[$i + 1]]++; $histogram[2][$pixels[$i]]++; $samples++
        }
    }
    $background = @(0, 0, 0)
    for ($channel = 0; $channel -lt 3; $channel++) {
        $running = 0
        for ($value = 0; $value -lt 256; $value++) { $running += $histogram[$channel][$value]; if ($running * 2 -ge $samples) { $background[$channel] = $value; break } }
        $near = 0
        for ($value = [Math]::Max(0, $background[$channel] - 6); $value -le [Math]::Min(255, $background[$channel] + 6); $value++) { $near += $histogram[$channel][$value] }
        if ($near -lt $samples * 0.995) { throw "The background of $sourceName is not one flat colour, so the logo cannot be separated from it." }
    }
    $br = $background[0]; $bg = $background[1]; $bb = $background[2]
    $backgroundHex = '#{0:X2}{1:X2}{2:X2}' -f $br, $bg, $bb

    # Two masks of the artwork against that background. The themed icon keeps the red channel (white stays full, the pale green is
    # about 60 percent, the dots are holes), so that it still reads as two bubbles; the status-bar icon is one full silhouette.
    $monochrome = [byte[]]::new($size * $size)
    $silhouette = [byte[]]::new($size * $size)
    $redRange = 255 - $br
    $left = $size; $top = $size; $right = -1; $bottom = -1
    for ($y = 0; $y -lt $size; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $size; $x++) {
            $i = $row + $x * 4
            $dr = $pixels[$i + 2] - $br; $dg = $pixels[$i + 1] - $bg; $db = $pixels[$i] - $bb
            $distanceSquared = $dr * $dr + $dg * $dg + $db * $db
            if ($distanceSquared -le 64) { continue }
            $k = $y * $size + $x
            $solid = [Math]::Sqrt($distanceSquared) * 255 / 150
            $silhouette[$k] = [byte][Math]::Round([Math]::Min(255, $solid))
            $red = $dr * 255 / $redRange
            $monochrome[$k] = [byte][Math]::Round([Math]::Max(0, [Math]::Min(255, $red)))
            if ($distanceSquared -gt 1600) {
                if ($x -lt $left) { $left = $x }; if ($x -gt $right) { $right = $x }
                if ($y -lt $top) { $top = $y }; if ($y -gt $bottom) { $bottom = $y }
            }
        }
    }
    if ($right -lt $left -or $bottom -lt $top) { throw "$sourceName shows no artwork against its background." }
    $artWidth = $right - $left + 1; $artHeight = $bottom - $top + 1
    $centreX = ($left + $right + 1) / 2.0; $centreY = ($top + $bottom + 1) / 2.0
    $radius = 0.0
    for ($y = $top; $y -le $bottom; $y++) {
        for ($x = $left; $x -le $right; $x++) {
            if ($silhouette[$y * $size + $x] -lt 68) { continue }
            $distance = [Math]::Sqrt([Math]::Pow($x + 0.5 - $centreX, 2) + [Math]::Pow($y + 0.5 - $centreY, 2))
            if ($distance -gt $radius) { $radius = $distance }
        }
    }
    $monochromeSource = New-AlphaBitmap $monochrome $size
    $silhouetteSource = New-AlphaBitmap $silhouette $size

    # The launcher draws its icon in a 108 dp square of which only a circle of 66 dp is safe from every shape of mask, so the artwork
    # is scaled to sit inside that circle, with the rest of the picture (its own flat background) around it.
    $canvas = 432
    $safe = $canvas * 33 / 108
    $scale = ($safe - 2) / $radius
    if ($radius * $scale -gt $safe) { throw 'Launcher artwork exceeds the adaptive-icon safe circle.' }
    $artSize = [int][Math]::Round($size * $scale)
    $offsetX = [int][Math]::Round($canvas / 2 - $centreX * $scale); $offsetY = [int][Math]::Round($canvas / 2 - $centreY * $scale)
    $visibleFrom = [int]($canvas * 18 / 108); $visibleTo = [int]($canvas * 90 / 108)
    if ($offsetX -gt $visibleFrom -or $offsetY -gt $visibleFrom -or $offsetX + $artSize -lt $visibleTo -or $offsetY + $artSize -lt $visibleTo) {
        throw 'The picture does not cover the part of the launcher icon that a mask can show.'
    }

    function New-LauncherPng([Drawing.Bitmap]$Artwork, [bool]$Solid) {
        $bitmap = [Drawing.Bitmap]::new($canvas, $canvas, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        $scaled = Get-Scaled $Artwork $artSize $artSize
        try {
            if ($Solid) { $graphics.Clear([Drawing.Color]::FromArgb(255, $br, $bg, $bb)) } else { $graphics.Clear([Drawing.Color]::Transparent) }
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $graphics.DrawImage($scaled, [Drawing.Rectangle]::new($offsetX, $offsetY, $artSize, $artSize))
            if (-not $Solid) { Set-White $bitmap }
            return (Save-Png $bitmap)
        } finally { $scaled.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
    }

    # The whole picture as a square with rounded corners and transparent outside them (the logo in the app and on the website).
    function New-BadgePng([int]$Pixels) {
        $scaled = Get-Scaled $argb $Pixels $Pixels
        $bitmap = [Drawing.Bitmap]::new($Pixels, $Pixels, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        $brush = [Drawing.TextureBrush]::new($scaled, [Drawing.Drawing2D.WrapMode]::Clamp)
        $path = New-RoundedPath $Pixels ($Pixels * 0.225)
        try {
            $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.FillPath($brush, $path)
            return (Save-Png $bitmap)
        } finally { $path.Dispose(); $brush.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $scaled.Dispose() }
    }

    function New-SquarePng([int]$Pixels) {
        $scaled = Get-Scaled $argb $Pixels $Pixels
        try { return (Save-Png $scaled) } finally { $scaled.Dispose() }
    }

    function New-StatusPng([int]$Pixels, [int]$Artwork) {
        $cropped = $silhouetteSource.Clone([Drawing.Rectangle]::new($left, $top, $artWidth, $artHeight), [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $factor = $Artwork / [Math]::Max($artWidth, $artHeight)
        $width = [int][Math]::Round($artWidth * $factor); $height = [int][Math]::Round($artHeight * $factor)
        $scaled = Get-Scaled $cropped $width $height
        $bitmap = [Drawing.Bitmap]::new($Pixels, $Pixels, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::Transparent)
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $graphics.DrawImage($scaled, [Drawing.Rectangle]::new([int][Math]::Round(($Pixels - $width) / 2), [int][Math]::Round(($Pixels - $height) / 2), $width, $height))
            Set-White $bitmap
            return (Save-Png $bitmap)
        } finally { $graphics.Dispose(); $bitmap.Dispose(); $scaled.Dispose(); $cropped.Dispose() }
    }

    # The colour behind the launcher artwork is the picture's own background, kept in a resource file the app reads.
    $brandPath = Join-Path $root 'android\app\src\main\res\values\brand.xml'
    $brand = [IO.File]::ReadAllText($brandPath)
    $colour = '<color name="launcher_background">' + $backgroundHex + '</color>'
    if (-not $brand.Contains($colour)) {
        if ($Verify) { throw "launcher_background in brand.xml is not the logo's background ($backgroundHex). Run scripts\prepare-brand-assets.ps1 before rebuilding." }
        $updated = [regex]::Replace($brand, '<color name="launcher_background">[^<]*</color>', $colour)
        if ($updated -ceq $brand) { throw 'brand.xml has no launcher_background colour to set.' }
        [IO.File]::WriteAllText($brandPath, $updated, [Text.UTF8Encoding]::new($false))
    }

    Write-Asset 'android\app\src\main\res\drawable-nodpi\vanishr_logo.png' (New-BadgePng 256)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_launcher_foreground.png' (New-LauncherPng $argb $true)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_launcher_monochrome.png' (New-LauncherPng $monochromeSource $false)
    Write-Asset 'android\app\src\main\res\drawable-xxxhdpi\ic_stat_vanishr.png' (New-StatusPng 96 80)
    Write-Asset 'download\icon.png' (New-BadgePng 192)
    Write-Asset 'download\apple-touch-icon.png' (New-SquarePng 180)

    $sizes = @(16, 32, 48)
    $images = @($sizes | ForEach-Object { ,(New-BadgePng $_) })
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

    if ($PlayIcon) {
        $target = [IO.Path]::GetFullPath($PlayIcon)
        $null = New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force
        [IO.File]::WriteAllBytes($target, (New-SquarePng 512))
        Write-Output "Wrote the 512 x 512 Google Play icon: $target"
    }
    Write-Output "$(if ($Verify) { 'Verified' } else { 'Prepared' }) seven app and website images and the launcher background $backgroundHex from $sourceName (artwork $artWidth x $artHeight, radius $([Math]::Round($radius, 1)))."
} finally {
    foreach ($item in @($monochromeSource, $silhouetteSource, $argb)) { if ($null -ne $item) { $item.Dispose() } }
    $picture.Dispose()
}
