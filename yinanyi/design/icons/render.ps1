# 把 design/icons 下的前景 SVG 渲染成各密度的 PNG，输出到 app/src/main/res/mipmap-*/
# 用法：powershell -File design\icons\render.ps1
# Edge 会往 stderr 打无害警告（libpng iCCP），PowerShell 5 遇 Stop 会当成错误，所以用 Continue 并自己检查输出文件
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$res = Join-Path $here '..\..\app\src\main\res'
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$tmp = Join-Path $env:TEMP 'yinanyi-icon-render'
New-Item -ItemType Directory -Force $tmp | Out-Null

# 自适应图标前景是 108dp，各密度像素 = 108 × 倍数
$densities = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }

foreach ($name in @('ic_launcher_foreground')) {
    $svg = Get-Content -Raw -Encoding UTF8 (Join-Path $here "$name.svg")
    $svg = $svg -replace 'width="108" height="108"', 'width="432" height="432"'
    $html = Join-Path $tmp "$name.html"
    Set-Content -Encoding UTF8 $html "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;background:transparent}svg{display:block}</style></head><body>$svg</body></html>"
    $big = Join-Path $tmp "$name-432.png"
    Remove-Item $big -ErrorAction SilentlyContinue
    & $edge --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 `
        --window-size=432,432 "--screenshot=$big" "file:///$($html -replace '\\','/')" 2>$null | Out-Null
    Start-Sleep -Milliseconds 500
    if (-not (Test-Path $big)) { throw "render failed: $name" }

    $src = [System.Drawing.Image]::FromFile($big)
    foreach ($d in $densities.Keys) {
        $size = $densities[$d]
        $dir = Join-Path $res "mipmap-$d"
        New-Item -ItemType Directory -Force $dir | Out-Null
        $bmp = New-Object System.Drawing.Bitmap $size, $size
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.DrawImage($src, 0, 0, $size, $size)
        $bmp.Save((Join-Path $dir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
    }
    $src.Dispose()
    Write-Output "rendered $name"
}
