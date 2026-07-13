# Generates build/icon.png (512x512) — dark rounded square with a green shield + play glyph.
# electron-builder converts this PNG to the Windows .ico automatically.
Add-Type -AssemblyName System.Drawing

$size = 512
$bmp = [System.Drawing.Bitmap]::new($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)

# rounded-square background
$bg = [System.Drawing.Drawing2D.GraphicsPath]::new()
$r = 110
$w = $size
$bg.AddArc(0, 0, $r, $r, 180, 90)
$bg.AddArc($w - $r, 0, $r, $r, 270, 90)
$bg.AddArc($w - $r, $w - $r, $r, $r, 0, 90)
$bg.AddArc(0, $w - $r, $r, $r, 90, 90)
$bg.CloseFigure()
$bgBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 18, 21, 26))
$g.FillPath($bgBrush, $bg)

$green = [System.Drawing.Color]::FromArgb(255, 118, 185, 0)
$dark = [System.Drawing.Color]::FromArgb(255, 18, 21, 26)
$greenBrush = [System.Drawing.SolidBrush]::new($green)
$darkBrush = [System.Drawing.SolidBrush]::new($dark)

function Shield-Points([float]$cx, [float]$cy, [float]$s) {
    [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new($cx, $cy - 1.05 * $s),
        [System.Drawing.PointF]::new($cx + $s, $cy - 0.62 * $s),
        [System.Drawing.PointF]::new($cx + $s, $cy + 0.12 * $s),
        [System.Drawing.PointF]::new($cx, $cy + 1.05 * $s),
        [System.Drawing.PointF]::new($cx - $s, $cy + 0.12 * $s),
        [System.Drawing.PointF]::new($cx - $s, $cy - 0.62 * $s)
    )
}

$g.FillPolygon($greenBrush, (Shield-Points 256 262 168))
$g.FillPolygon($darkBrush, (Shield-Points 256 262 134))

# play triangle
$tri = [System.Drawing.PointF[]]@(
    [System.Drawing.PointF]::new(218, 198),
    [System.Drawing.PointF]::new(218, 326),
    [System.Drawing.PointF]::new(326, 262)
)
$g.FillPolygon($greenBrush, $tri)

$out = Join-Path $PSScriptRoot 'icon.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output "wrote $out"
