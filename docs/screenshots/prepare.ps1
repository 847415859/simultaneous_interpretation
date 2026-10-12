# 把手机截图裁剪、缩放后用于 README（裁掉状态栏、导航栏、弹幕区里的用户名等）
# 用法：powershell -File docs\screenshots\prepare.ps1 -In 原图 -Out 输出.png
#        [-Top 125] [-Bottom 125] [-Left 0] [-Right 0] [-Width 540]
#        [-Dim "x,y,w,h"] [-Fill "x,y,w,h"]
# Top/Bottom/Left/Right：从四边各裁掉的像素数（按原图尺寸）
# Dim：在该区域内把暗于阈值的像素换成区域里暗像素的平均色——用来抹掉
#      透过半透明字幕窗隐约可见的弹幕用户名，同时保留亮色的字幕文字
# Fill：把该区域整块填成区域内的平均色（遮住整条通知等）
# Mosaic：把该区域打马赛克（直播画面里自带的弹幕/打赏栏）
param([string]$In, [string]$Out, [int]$Top = 125, [int]$Bottom = 125, [int]$Left = 0, [int]$Right = 0,
      [int]$Width = 540, [string]$Dim = '', [string]$Fill = '', [string]$Mosaic = '', [int]$Threshold = 110)
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System.Drawing;
public static class ShotClean {
    static int Lum(Color c) { return (c.R * 299 + c.G * 587 + c.B * 114) / 1000; }
    public static void Dim(Bitmap b, Rectangle r, int threshold) {
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = r.Top; y < r.Bottom; y++) for (int x = r.Left; x < r.Right; x++) {
            Color c = b.GetPixel(x, y);
            if (Lum(c) < threshold) { sr += c.R; sg += c.G; sb += c.B; n++; }
        }
        if (n == 0) return;
        Color bg = Color.FromArgb((int)(sr / n), (int)(sg / n), (int)(sb / n));
        for (int y = r.Top; y < r.Bottom; y++) for (int x = r.Left; x < r.Right; x++)
            if (Lum(b.GetPixel(x, y)) < threshold) b.SetPixel(x, y, bg);
    }
    public static void Pixelate(Bitmap b, Rectangle r, int block) {
        for (int by = r.Top; by < r.Bottom; by += block) for (int bx = r.Left; bx < r.Right; bx += block) {
            int ex = System.Math.Min(bx + block, r.Right), ey = System.Math.Min(by + block, r.Bottom);
            long sr = 0, sg = 0, sb = 0, n = 0;
            for (int y = by; y < ey; y++) for (int x = bx; x < ex; x++) {
                Color c = b.GetPixel(x, y); sr += c.R; sg += c.G; sb += c.B; n++;
            }
            Color avg = Color.FromArgb((int)(sr / n), (int)(sg / n), (int)(sb / n));
            for (int y = by; y < ey; y++) for (int x = bx; x < ex; x++) b.SetPixel(x, y, avg);
        }
    }
    public static void Fill(Bitmap b, Rectangle r) {
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = r.Top; y < r.Bottom; y++) for (int x = r.Left; x < r.Right; x++) {
            Color c = b.GetPixel(x, y); sr += c.R; sg += c.G; sb += c.B; n++;
        }
        using (Graphics g = Graphics.FromImage(b))
        using (SolidBrush br = new SolidBrush(Color.FromArgb((int)(sr / n), (int)(sg / n), (int)(sb / n))))
            g.FillRectangle(br, r);
    }
}
'@
function Rect([string]$s) { $v = $s.Split(',') | ForEach-Object { [int]$_ }; New-Object System.Drawing.Rectangle $v[0], $v[1], $v[2], $v[3] }

$img = [System.Drawing.Image]::FromFile($In)
$src = New-Object System.Drawing.Bitmap $img
$img.Dispose()
if ($Mosaic) { [ShotClean]::Pixelate($src, (Rect $Mosaic), 18) }
if ($Fill) { [ShotClean]::Fill($src, (Rect $Fill)) }
if ($Dim) { [ShotClean]::Dim($src, (Rect $Dim), $Threshold) }

$w = $src.Width - $Left - $Right
$h = $src.Height - $Top - $Bottom
$scale = $Width / $w
$dst = New-Object System.Drawing.Bitmap $Width, ([int]($h * $scale))
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $dst.Width, $dst.Height), (New-Object System.Drawing.Rectangle $Left, $Top, $w, $h), [System.Drawing.GraphicsUnit]::Pixel)
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $dst.Dispose(); $src.Dispose()
