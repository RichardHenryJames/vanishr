#requires -Version 7.4
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'download/assets'
$null = New-Item -ItemType Directory -Path $assets -Force
$screens = @{
    'conversations.png' = '50-compact-classic-home.png'
    'chat.png' = '10-conversation.png'
    'profile.png' = '61-my-profile-photo.png'
}
Add-Type -AssemblyName System.Drawing
foreach ($screen in $screens.GetEnumerator()) {
    $source = Join-Path $root ('.tools/profile-0.3.3-narrow-ui/' + $screen.Value)
    if (-not (Test-Path $source)) { throw 'Generate the synthetic 0.3.3 Android screenshots before preparing website assets.' }
    $original = [Drawing.Bitmap]::FromFile($source)
    $bitmap = [Drawing.Bitmap]::new(480, [int]($original.Height * 480 / $original.Width))
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($original, 0, 0, $bitmap.Width, $bitmap.Height)
        $bitmap.Save((Join-Path $assets $screen.Key), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose(); $original.Dispose() }
}
Copy-Item (Join-Path $root 'android/app/src/main/res/font/manrope.ttf') (Join-Path $assets 'manrope.ttf') -Force
Copy-Item (Join-Path $root 'android/app/src/main/assets/legal/MANROPE-OFL.txt') (Join-Path $assets 'MANROPE-OFL.txt') -Force
Copy-Item (Join-Path $root 'docs/designs/home/assets/lucide.min.js') (Join-Path $assets 'lucide.min.js') -Force
Copy-Item (Join-Path $root 'android/app/src/main/assets/legal/LUCIDE-LICENSE.txt') (Join-Path $assets 'LUCIDE-LICENSE.txt') -Force
$fonts = [Drawing.Text.PrivateFontCollection]::new()
$fonts.AddFontFile((Join-Path $assets 'manrope.ttf'))
function Draw-Screen($graphics, [string]$name, [int]$left, [int]$top, [int]$height) {
    $image = [Drawing.Bitmap]::FromFile((Join-Path $assets $name))
    $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#cad8d1'), 2)
    try {
        $width = [int]($height * $image.Width / $image.Height)
        $graphics.DrawImage($image, $left, $top, $width, $height)
        $graphics.DrawRectangle($pen, $left, $top, $width, $height)
    } finally { $image.Dispose(); $pen.Dispose() }
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
        $bitmap.Save((Join-Path $assets $name), [Drawing.Imaging.ImageFormat]::Jpeg)
    } finally { $graphics.Dispose(); $bitmap.Dispose(); $green.Dispose(); $blue.Dispose(); $coral.Dispose() }
}
Write-Board 'app-overview.jpg' 1600 840 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 380, 580, 460)
    $graphics.FillRectangle($blue, 580, 380, 500, 460)
    $graphics.FillRectangle($coral, 1080, 380, 520, 460)
    Draw-Screen $graphics 'conversations.png' 345 388 440
    Draw-Screen $graphics 'chat.png' 670 330 498
    Draw-Screen $graphics 'profile.png' 1025 388 440
}
Write-Board 'app-overview-mobile.jpg' 780 1120 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 500, 260, 620)
    $graphics.FillRectangle($blue, 260, 500, 260, 620)
    $graphics.FillRectangle($coral, 520, 500, 260, 620)
    Draw-Screen $graphics 'chat.png' 235 500 600
}
Write-Board 'social.jpg' 1200 630 {
    param($graphics, $green, $blue, $coral)
    $graphics.FillRectangle($green, 0, 420, 400, 210)
    $graphics.FillRectangle($blue, 400, 420, 400, 210)
    $graphics.FillRectangle($coral, 800, 420, 400, 210)
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#182724'))
    $title = [Drawing.Font]::new($fonts.Families[0], 96, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $body = [Drawing.Font]::new($fonts.Families[0], 28, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
    try {
        $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.DrawString('Vanishr', $title, $brush, 65, 70)
        $graphics.DrawString("Encrypted disappearing chat.`nFor Android.", $body, $brush, 70, 207)
        $graphics.DrawString('Development build / Open source', $body, $brush, 70, 482)
        Draw-Screen $graphics 'chat.png' 780 56 522
    } finally { $brush.Dispose(); $title.Dispose(); $body.Dispose() }
}
$fonts.Dispose()
Get-ChildItem $assets -File | Select-Object Name, Length