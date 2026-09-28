# Builds the Ottershelf launcher icon, badge, Play icon and logo from docs/brand/ottershelf-artwork.webp
# (Windows PowerShell: WIC decodes the WebP, System.Drawing draws). Writes into -Dir (default
# app/build/brand): out/mipmap-*/ic_launcher_foreground.png, out/drawable-nodpi/ottershelf_badge.png,
# out/store/icon-512.png, out/store/logo-944.png and out/preview.png; copy them into place by hand.
#   powershell -File tools/brand/make-icons.ps1
param(
    [string]$Dir = (Join-Path $PSScriptRoot '../../app/build/brand'),
    [string]$Artwork = (Join-Path $PSScriptRoot '../../docs/brand/ottershelf-artwork.webp'),
    [int]$BgR = 62, [int]$BgG = 103, [int]$BgB = 118
)
Add-Type -AssemblyName System.Drawing, PresentationCore
New-Item -ItemType Directory -Force $Dir | Out-Null
$Dir = (Resolve-Path $Dir).Path
$in = [IO.File]::OpenRead((Resolve-Path $Artwork).Path)
$frame = [Windows.Media.Imaging.BitmapDecoder]::Create($in, 'PreservePixelFormat', 'OnLoad').Frames[0]
$png = New-Object Windows.Media.Imaging.PngBitmapEncoder
$png.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($frame))
$tmp = [IO.File]::Create((Join-Path $Dir 'source.png')); $png.Save($tmp); $tmp.Close(); $in.Close()
# Geometry of the artwork (1254 px square): the round badge's centre and radii.
$src = New-Object System.Drawing.Bitmap (Join-Path $Dir 'source.png')
$cx = 510.5; $cy = 499.0
$sceneR = 427.0    # the water scene inside the cream ring
$badgeR = 472.0    # the whole badge, dark ring included
$bg = [System.Drawing.Color]::FromArgb(255, $BgR, $BgG, $BgB)

function New-Canvas([int]$size) {
    $b = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'; $g.CompositingQuality = 'HighQuality'
    return @($b, $g)
}

# The circle of source radius $r drawn centred at ($ox,$oy) with output diameter $d.
function Draw-Circle($g, [double]$r, [double]$ox, [double]$oy, [double]$d) {
    $scale = $d / (2 * $r)
    $brush = New-Object System.Drawing.TextureBrush $src
    $brush.WrapMode = 'Clamp'
    $m = New-Object System.Drawing.Drawing2D.Matrix
    $m.Translate([single]($ox - $cx * $scale), [single]($oy - $cy * $scale))
    $m.Scale([single]$scale, [single]$scale)
    $brush.Transform = $m
    $g.FillEllipse($brush, [single]($ox - $d / 2), [single]($oy - $d / 2), [single]$d, [single]$d)
    $brush.Dispose()
}

# A ring that fades the scene's edge into the background colour (from $inner of the radius outwards).
function Draw-Feather($g, [double]$ox, [double]$oy, [double]$d, [double]$inner) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse([single]($ox - $d / 2), [single]($oy - $d / 2), [single]$d, [single]$d)
    $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush $path
    $pg.CenterColor = [System.Drawing.Color]::FromArgb(0, $bg.R, $bg.G, $bg.B)
    $pg.SurroundColors = @($bg)
    $blend = New-Object System.Drawing.Drawing2D.Blend 3
    $blend.Factors = [single[]]@(0, 1, 1)          # position 0 is the edge: the surround colour (opaque) there
    $blend.Positions = [single[]]@(0, $inner, 1)
    $pg.Blend = $blend
    $g.FillEllipse($pg, [single]($ox - $d / 2), [single]($oy - $d / 2), [single]$d, [single]$d)
    $pg.Dispose(); $path.Dispose()
}

function Save-Png($b, [string]$path) {
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $b.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# Launcher foreground (108dp canvas): the scene, feathered, 78dp across; the background layer is $bg.
# Only xxhdpi and xxxhdpi: every phone this app runs on (minSdk 35) is one of those.
foreach ($pair in @(@('xxhdpi', 324), @('xxxhdpi', 432))) {
    $size = $pair[1]; $c = New-Canvas $size; $b = $c[0]; $g = $c[1]
    $d = $size * 78.0 / 108.0
    Draw-Circle $g $sceneR ($size / 2) ($size / 2) $d
    Draw-Feather $g ($size / 2) ($size / 2) ($d + 2) 0.10
    $g.Dispose(); Save-Png $b (Join-Path $Dir "out/mipmap-$($pair[0])/ic_launcher_foreground.png"); $b.Dispose()
}

# The badge (splash screen, login, About): transparent outside, 480px.
$size = 480; $c = New-Canvas $size; $b = $c[0]; $g = $c[1]
Draw-Circle $g $badgeR ($size / 2) ($size / 2) $size
$g.Dispose(); Save-Png $b (Join-Path $Dir 'out/drawable-nodpi/ottershelf_badge.png'); $b.Dispose()

# Play Store icon 512x512 (opaque; Play rounds the corners itself).
$size = 512; $c = New-Canvas $size; $b = $c[0]; $g = $c[1]
$g.Clear($bg)
$d = 640
Draw-Circle $g $sceneR ($size / 2) ($size / 2) $d
Draw-Feather $g ($size / 2) ($size / 2) ($d + 2) 0.12
$g.Dispose(); Save-Png $b (Join-Path $Dir 'out/store/icon-512.png'); $b.Dispose()

# Full logo for README / listing: the badge, transparent outside, 944px.
$size = 944; $c = New-Canvas $size; $b = $c[0]; $g = $c[1]
Draw-Circle $g $badgeR ($size / 2) ($size / 2) $size
$g.Dispose(); Save-Png $b (Join-Path $Dir 'out/store/logo-944.png'); $b.Dispose()

# ---- Preview sheet ----
function Tile($g, $img, [double]$x, [double]$y, [double]$vis, [int]$shape) {
    # $img is a full 108dp adaptive composite; show its middle 72dp at $vis px through a mask.
    $full = $vis * 108.0 / 72.0; $off = ($full - $vis) / 2
    $scaled = New-Object System.Drawing.Bitmap $img, ([int]$full), ([int]$full)
    $tex = New-Object System.Drawing.TextureBrush $scaled; $mm = New-Object System.Drawing.Drawing2D.Matrix; $mm.Translate([single]($x - $off), [single]($y - $off)); $tex.Transform = $mm
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    if ($shape -eq 0) { $p.AddEllipse([single]$x, [single]$y, [single]$vis, [single]$vis) }
    else { $rr = $vis * $(if ($shape -eq 1) { 0.62 } else { 0.36 }); $p.AddArc([single]$x, [single]$y, [single]$rr, [single]$rr, 180, 90); $p.AddArc([single]($x + $vis - $rr), [single]$y, [single]$rr, [single]$rr, 270, 90); $p.AddArc([single]($x + $vis - $rr), [single]($y + $vis - $rr), [single]$rr, [single]$rr, 0, 90); $p.AddArc([single]$x, [single]($y + $vis - $rr), [single]$rr, [single]$rr, 90, 90); $p.CloseFigure() }
    $g.FillPath($tex, $p); $tex.Dispose(); $scaled.Dispose(); $p.Dispose()
}
$fg = New-Object System.Drawing.Bitmap (Join-Path $Dir 'out/mipmap-xxxhdpi/ic_launcher_foreground.png')
$comp = New-Canvas 432; $cb = $comp[0]; $cg = $comp[1]; $cg.Clear($bg); $cg.DrawImage($fg, 0, 0, 432, 432); $cg.Dispose(); $fg.Dispose()
$badge = New-Object System.Drawing.Bitmap (Join-Path $Dir 'out/drawable-nodpi/ottershelf_badge.png')
$play = New-Object System.Drawing.Bitmap (Join-Path $Dir 'out/store/icon-512.png')
$W = 1400; $H = 1700
$sheet = New-Canvas 1; $sheet[1].Dispose(); $sheet[0].Dispose()
$sb = New-Object System.Drawing.Bitmap $W, $H; $sg = [System.Drawing.Graphics]::FromImage($sb)
$sg.SmoothingMode = 'AntiAlias'; $sg.InterpolationMode = 'HighQualityBicubic'; $sg.TextRenderingHint = 'AntiAliasGridFit'
$sg.Clear([System.Drawing.Color]::FromArgb(255, 246, 244, 238))
$label = New-Object System.Drawing.Font 'Segoe UI', 20
$ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 60, 60, 60))
# Row 1: launcher icon, big, three launcher shapes.
$sg.DrawString('Launcher icon (adaptive): circle, squircle, rounded square', $label, $ink, 40, 20)
Tile $sg $cb 60 70 300 0; Tile $sg $cb 420 70 300 1; Tile $sg $cb 780 70 300 2
# Small, on a dark and a light wallpaper, as on a home screen (about 60dp).
$wall = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 32, 36, 44)); $sg.FillRectangle($wall, 1130, 70, 230, 140)
$sg.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 214, 226, 236))), 1130, 230, 230, 140)
Tile $sg $cb 1150 95 80 0; Tile $sg $cb 1255 95 80 1; Tile $sg $cb 1150 255 80 0; Tile $sg $cb 1255 255 80 1
$sg.DrawString('home-screen size', $label, $ink, 1135, 380)
# Row 2: Play icon with Play's rounding, and the badge.
$sg.DrawString('Play Store icon (512 px)', $label, $ink, 40, 430)
$pp = New-Object System.Drawing.Drawing2D.GraphicsPath; $x=60; $y=480; $s=360; $rr=$s*0.4
$pp.AddArc($x,$y,$rr,$rr,180,90); $pp.AddArc($x+$s-$rr,$y,$rr,$rr,270,90); $pp.AddArc($x+$s-$rr,$y+$s-$rr,$rr,$rr,0,90); $pp.AddArc($x,$y+$s-$rr,$rr,$rr,90,90); $pp.CloseFigure()
$ps = New-Object System.Drawing.Bitmap $play, $s, $s; $pt = New-Object System.Drawing.TextureBrush $ps; $pm = New-Object System.Drawing.Drawing2D.Matrix; $pm.Translate($x, $y); $pt.Transform = $pm; $sg.FillPath($pt, $pp)
$sg.DrawString('Badge (splash, login, About, README)', $label, $ink, 520, 430)
$sg.DrawImage($badge, 540, 480, 360, 360)
# Row 3: splash screen, light and dark, and the login header.
$sg.DrawString('Splash screen (light, dark)', $label, $ink, 40, 900)
foreach ($i in 0, 1) {
    $px = 60 + $i * 300; $py = 950
    $col = if ($i -eq 0) { [System.Drawing.Color]::FromArgb(255, 248, 249, 251) } else { [System.Drawing.Color]::FromArgb(255, 12, 15, 22) }
    $sg.FillRectangle((New-Object System.Drawing.SolidBrush $col), $px, $py, 260, 560)
    $sg.DrawRectangle((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 180, 180, 180))), $px, $py, 260, 560)
    $sg.DrawImage($badge, $px + 55, $py + 205, 150, 150)
}
$sg.DrawString('Login header', $label, $ink, 720, 900)
$sg.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), 720, 950, 620, 560)
$sg.DrawImage($badge, 950, 1010, 160, 160)
$wm1 = New-Object System.Drawing.Font 'Georgia', 44, ([System.Drawing.FontStyle]::Bold)
$fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = 'Center'
$t1 = 'Otter'; $t2 = 'shelf'
$w1 = $sg.MeasureString($t1, $wm1).Width; $w2 = $sg.MeasureString($t2, $wm1).Width; $sx = 1030 - ($w1 + $w2 - 20) / 2
$sg.DrawString($t1, $wm1, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 17, 17, 17))), $sx, 1185)
$sg.DrawString($t2, $wm1, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 8, 72, 220))), $sx + $w1 - 20, 1185)
$sub = New-Object System.Drawing.Font 'Segoe UI', 18
$sg.DrawString('Sign in to your account', $sub, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 110, 110, 110))), 1030, 1265, $fmt)
$sg.Dispose(); $sb.Save((Join-Path $Dir 'out/preview.png'), [System.Drawing.Imaging.ImageFormat]::Png); $sb.Dispose()
$badge.Dispose(); $play.Dispose(); $cb.Dispose(); $src.Dispose()
Remove-Item -ErrorAction SilentlyContinue (Join-Path $Dir 'out/mipmap-xhdpi') -Recurse
Remove-Item -ErrorAction SilentlyContinue (Join-Path $Dir 'out/drawable-nodpi/ic_splash_badge.png')
Get-ChildItem -Recurse (Join-Path $Dir 'out') -File | ForEach-Object { "{0,8} {1}" -f $_.Length, $_.FullName.Substring($Dir.Length) }
