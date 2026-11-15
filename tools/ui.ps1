param(
  [string]$cmd = "shot",
  [int]$x = 0,
  [int]$y = 0,
  [int]$x2 = 0,
  [int]$y2 = 0,
  [string]$out = "shot.png"
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, IntPtr e);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

[Win]::SetProcessDPIAware() | Out-Null

$proc = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -like 'java*' } | Select-Object -First 1
if (-not $proc) { Write-Output "NO_WINDOW"; exit 1 }
$h = $proc.MainWindowHandle
$r = New-Object Win+RECT
[Win]::GetWindowRect($h, [ref]$r) | Out-Null
$rect = "RECT $($r.Left) $($r.Top) $($r.Right) $($r.Bottom)"

function Press([int]$px, [int]$py, [uint32]$flags) {
  [Win]::SetCursorPos($px, $py) | Out-Null
  Start-Sleep -Milliseconds 60
  [Win]::mouse_event($flags, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 60
}

switch ($cmd) {
  "rect" { Write-Output $rect }
  "focus" { [Win]::SetForegroundWindow($h) | Out-Null; Write-Output $rect }
  "shot" {
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 250
    $w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
    $bmp = New-Object System.Drawing.Bitmap $w, $ht
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size $w, $ht))
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output "$rect SAVED $out"
  }
  "move" {
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 120
    [Win]::SetCursorPos(($r.Left + $x), ($r.Top + $y)) | Out-Null
    Start-Sleep -Milliseconds 60
    [Win]::SetCursorPos(($r.Left + $x + 2), ($r.Top + $y)) | Out-Null
    Write-Output "MOVE $x,$y"
  }
  "key" {
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait($out)
    Write-Output "KEY $out"
  }
  "click" {
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 150
    Press ($r.Left + $x) ($r.Top + $y) 0x0002
    [Win]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    Write-Output "CLICK $x,$y"
  }
  "drag" {
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 150
    $sx = $r.Left + $x; $sy = $r.Top + $y
    $ex = $r.Left + $x2; $ey = $r.Top + $y2
    [Win]::SetCursorPos($sx, $sy) | Out-Null
    Start-Sleep -Milliseconds 120
    [Win]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 120
    $steps = 24
    for ($i = 1; $i -le $steps; $i++) {
      $nx = [int]($sx + ($ex - $sx) * $i / $steps)
      $ny = [int]($sy + ($ey - $sy) * $i / $steps)
      [Win]::SetCursorPos($nx, $ny) | Out-Null
      Start-Sleep -Milliseconds 25
    }
    Start-Sleep -Milliseconds 120
    [Win]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    Write-Output "DRAG $x,$y -> $x2,$y2"
  }
  default { Write-Output "unknown cmd" }
}
