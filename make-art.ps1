# Generates the TV banner (320x180) and launcher icon (192x192) for Marquee.
Add-Type -AssemblyName System.Drawing
$root = $PSScriptRoot
$res = Join-Path $root 'app\src\main\res'
New-Item -ItemType Directory -Force (Join-Path $res 'drawable') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $res 'mipmap') | Out-Null

$green = [System.Drawing.Color]::FromArgb(255,118,185,0)
$bg    = [System.Drawing.Color]::FromArgb(255,14,17,21)

function New-Canvas([int]$w,[int]$h) {
  $bmp = New-Object System.Drawing.Bitmap($w,$h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $g.TextRenderingHint = 'AntiAliasGridFit'
  $g.Clear($bg)
  return @($bmp,$g)
}

# --- banner 320x180 ---
$c = New-Canvas 320 180
$bmp = $c[0]; $g = $c[1]
# play triangle
$tri = @(
  (New-Object System.Drawing.PointF(38,60)),
  (New-Object System.Drawing.PointF(38,120)),
  (New-Object System.Drawing.PointF(86,90))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush($green)), $tri)
$font = New-Object System.Drawing.Font('Segoe UI',30,[System.Drawing.FontStyle]::Bold)
$g.DrawString('Marquee', $font, (New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)), 96, 60)
$sfont = New-Object System.Drawing.Font('Segoe UI',12)
$g.DrawString('Discover · then watch', $sfont, (New-Object System.Drawing.SolidBrush($green)), 98, 108)
$bmp.Save((Join-Path $res 'drawable\banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# --- icon 192x192 (rounded green square + play) ---
$c = New-Canvas 192 192
$bmp = $c[0]; $g = $c[1]
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$r = 44
$path.AddArc(0,0,$r,$r,180,90); $path.AddArc(192-$r,0,$r,$r,270,90)
$path.AddArc(192-$r,192-$r,$r,$r,0,90); $path.AddArc(0,192-$r,$r,$r,90,90); $path.CloseFigure()
$g.FillPath((New-Object System.Drawing.SolidBrush($green)), $path)
$tri = @(
  (New-Object System.Drawing.PointF(74,58)),
  (New-Object System.Drawing.PointF(74,134)),
  (New-Object System.Drawing.PointF(138,96))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush($bg)), $tri)
$bmp.Save((Join-Path $res 'mipmap\ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Output "wrote banner.png + ic_launcher.png"
