# 把手机截图裁掉状态栏和导航栏、缩到 540 宽，用于 README
# 用法：powershell -File docs\screenshots\prepare.ps1 -In 原图.png -Out 输出.png [-Top 125] [-Bottom 125]
param([string]$In, [string]$Out, [int]$Top = 125, [int]$Bottom = 125, [int]$Width = 540)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($In)
$h = $src.Height - $Top - $Bottom
$scale = $Width / $src.Width
$dst = New-Object System.Drawing.Bitmap $Width, ([int]($h * $scale))
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $dst.Width, $dst.Height), (New-Object System.Drawing.Rectangle 0, $Top, $src.Width, $h), [System.Drawing.GraphicsUnit]::Pixel)
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $dst.Dispose(); $src.Dispose()
