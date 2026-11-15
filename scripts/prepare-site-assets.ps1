#requires -Version 7.4
param([switch]$BrandingOnly, [string]$Source)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'download/assets'
$null = New-Item -ItemType Directory -Path $assets -Force
if (-not $Source) { $Source = Join-Path $root '.tools/store-screenshots/raw/native' }
# The website shows real screens of the app: the 1080 x 2400 captures made on the test emulator by the store-listing screenshot test
# (docs/PLAY-STORE.md, "Listing screenshots"). They hold made-up people and pictures drawn by the test, nothing of a real account.
$screens = [ordered]@{
    'app-chats.png' = '01-chats.png'
    'app-chat.png' = '02-chat.png'
    'app-disappearing.png' = '06-disappearing.png'
    'app-photos.png' = '03-photos.png'
    'app-group.png' = '08-group.png'
    'app-verify.png' = '07-verify.png'
}
Add-Type -AssemblyName System.Drawing
if (-not $BrandingOnly) {
foreach ($screen in $screens.GetEnumerator()) {
    $path = Join-Path $Source $screen.Value
    if (-not (Test-Path $path)) { throw "Make the screenshots first (docs/PLAY-STORE.md, Listing screenshots); not found: $path" }
    $original = [Drawing.Bitmap]::FromFile($path)
    if ($original.Width -ne 1080 -or $original.Height -ne 2400) { throw "Unexpected size for $($screen.Value): expected 1080 x 2400." }
    $bitmap = [Drawing.Bitmap]::new(540, 1200, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($original, [Drawing.Rectangle]::new(0, 0, 540, 1200), 0, 0, 1080, 2400, [Drawing.GraphicsUnit]::Pixel, $attributes)
        $bitmap.Save((Join-Path $assets $screen.Key), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $attributes.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $original.Dispose() }
}
Copy-Item (Join-Path $root 'android/app/src/main/res/font/manrope.ttf') (Join-Path $assets 'manrope.ttf') -Force
Copy-Item (Join-Path $root 'android/app/src/main/assets/legal/MANROPE-OFL.txt') (Join-Path $assets 'MANROPE-OFL.txt') -Force
Copy-Item (Join-Path $root 'docs/designs/home/assets/lucide.min.js') (Join-Path $assets 'lucide.min.js') -Force
Copy-Item (Join-Path $root 'android/app/src/main/assets/legal/LUCIDE-LICENSE.txt') (Join-Path $assets 'LUCIDE-LICENSE.txt') -Force
}
& (Join-Path $PSScriptRoot 'prepare-brand-assets.ps1') -Verify
$fonts = [Drawing.Text.PrivateFontCollection]::new()
$fonts.AddFontFile((Join-Path $assets 'manrope.ttf'))
function New-RoundedPath([double]$x, [double]$y, [double]$width, [double]$height, [double]$radius) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $radius * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return , $path
}
# A screen is drawn with round corners, a thin edge and a soft shadow. One that runs past the bottom of the board is cut there, as a phone
# held up from below would be.
function Draw-Screen($graphics, [string]$name, [int]$left, [int]$top, [int]$height) {
    $image = [Drawing.Bitmap]::FromFile((Join-Path $assets $name))
    $width = [int][math]::Round($height * $image.Width / $image.Height)
    $radius = $width * .075
    $scaled = [Drawing.Bitmap]::new($width, $height, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $scaler = [Drawing.Graphics]::FromImage($scaled)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $scaler.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $scaler.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $scaler.DrawImage($image, [Drawing.Rectangle]::new(0, 0, $width, $height), 0, 0, $image.Width, $image.Height, [Drawing.GraphicsUnit]::Pixel, $attributes)
    } finally { $attributes.Dispose(); $scaler.Dispose() }
    $brush = [Drawing.TextureBrush]::new($scaled)
    $brush.TranslateTransform($left, $top)
    $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#b4c5be'), 2)
    $shade = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(6, 24, 39, 36))
    try {
        for ($spread = 12; $spread -ge 1; $spread--) {
            $shadow = New-RoundedPath ($left - $spread) ($top - $spread + 10) ($width + 2 * $spread) ($height + 2 * $spread) ($radius + $spread)
            try { $graphics.FillPath($shade, $shadow) } finally { $shadow.Dispose() }
        }
        $outline = New-RoundedPath $left $top $width $height $radius
        try { $graphics.FillPath($brush, $outline); $graphics.DrawPath($pen, $outline) } finally { $outline.Dispose() }
    } finally { $shade.Dispose(); $pen.Dispose(); $brush.Dispose(); $scaled.Dispose(); $image.Dispose() }
}
function Save-Jpeg($bitmap, [string]$path) {
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq 'image/jpeg'
    $parameters = [Drawing.Imaging.EncoderParameters]::new(1)
    $parameters.Param[0] = [Drawing.Imaging.EncoderParameter]::new([Drawing.Imaging.Encoder]::Quality, [long]92)
    try { $bitmap.Save($path, $codec, $parameters) } finally { $parameters.Dispose() }
}
function Write-Board([string]$name, [int]$width, [int]$height, [scriptblock]$draw) {
    $bitmap = [Drawing.Bitmap]::new($width, $height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $green = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#dfede5'))
    $blue = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#e5ebf6'))
    $coral = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#f4e3e7'))
    try {
        $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#f3f7f4'))
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        & $draw $graphics $green $blue $coral
        Save-Jpeg $bitmap (Join-Path $assets $name)
    } finally { $graphics.Dispose(); $bitmap.Dispose(); $green.Dispose(); $blue.Dispose(); $coral.Dispose() }
}
if (-not $BrandingOnly) {
Write-Board 'app-overview.jpg' 1600 840 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 380, 600, 460)
    $graphics.FillRectangle($blue, 600, 380, 400, 460)
    $graphics.FillRectangle($coral, 1000, 380, 600, 460)
    Draw-Screen $graphics 'app-chats.png' 278 400 680
    Draw-Screen $graphics 'app-photos.png' 1016 400 660
    Draw-Screen $graphics 'app-chat.png' 623 330 780
}
Write-Board 'app-overview-mobile.jpg' 780 1120 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 500, 260, 620)
    $graphics.FillRectangle($blue, 260, 500, 260, 620)
    $graphics.FillRectangle($coral, 520, 500, 260, 620)
    Draw-Screen $graphics 'app-chat.png' 178 500 940
}
}
Write-Board 'social.jpg' 1200 630 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 420, 400, 210)
    $graphics.FillRectangle($blue, 400, 420, 400, 210)
    $graphics.FillRectangle($coral, 800, 420, 400, 210)
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#182724'))
    $title = [Drawing.Font]::new($fonts.Families[0], 96, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $body = [Drawing.Font]::new($fonts.Families[0], 28, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
    $logo = [Drawing.Bitmap]::FromFile((Join-Path $root 'download/icon.png'))
    try {
        $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.DrawImage($logo, 65, 88, 88, 88)
        $graphics.DrawString('Vanishr', $title, $brush, 168, 70)
        $graphics.DrawString("Encrypted disappearing chat.`nFor Android.", $body, $brush, 70, 207)
        $graphics.DrawString('Development build / Open source', $body, $brush, 70, 482)
        Draw-Screen $graphics 'app-photos.png' 1040 120 640
        Draw-Screen $graphics 'app-chat.png' 800 50 660
    } finally { $logo.Dispose(); $brush.Dispose(); $title.Dispose(); $body.Dispose() }
}
$fonts.Dispose()
Get-ChildItem $assets -File | Select-Object Name, Length