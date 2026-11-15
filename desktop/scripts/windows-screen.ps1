# The screen, and the mouse, for a machine nobody is sitting at.
#
#   powershell -ExecutionPolicy Bypass -File windows-screen.ps1 -Action shot
#   powershell -ExecutionPolicy Bypass -File windows-screen.ps1 -Action click -X 1300 -Y 760
#   powershell -ExecutionPolicy Bypass -File windows-screen.ps1 -Action key -Text "{ENTER}"
#
# `-Action shot` writes a PNG and prints its path; nothing is opened, so it can be
# called in a loop. Coordinates are screen pixels - the crop the tray probe takes
# and a click here therefore agree.
#
# The one thing this cannot do is run in another session's desktop: a process
# started by sshd is in session 0 and sees a black screen. Start it in the
# logged-in session instead (`schtasks /it`, or run it by hand there).
param(
    [ValidateSet("shot", "click", "move", "key", "type", "winshot")]
    [string]$Action = "shot",
    [string]$Out = "",
    [int]$X = 0,
    [int]$Y = 0,
    [int]$MaxWidth = 1400,
    [string]$Text = "",
    [ValidateSet("left", "right", "double")]
    [string]$Button = "left",
    [string]$Title = "AdFree",
    [string]$Log = ""
)

$ErrorActionPreference = "Stop"

function Say($text) {
    if ($Log -ne "") { Add-Content -LiteralPath $Log -Value $text } else { $text }
}

# A task in the interactive session has no console to print to: an unhandled
# error would exit silently, the PNG would be whatever the last run left, and
# the next reader would take a stale picture for the current state. The trap
# writes the reason where the caller can read it.
trap {
    Say "failed        : $_"
    if ($Log -ne "") { exit 1 }
    break
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class WinShot {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@

# Without this the process sees scaled coordinates while the screenshots come
# out in real pixels, so a click lands somewhere near the icon instead of on it.
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Input {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out System.Drawing.Point p);
}
"@ -ReferencedAssemblies System.Drawing
[void][Win32Input]::SetProcessDPIAware()

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$dir = Join-Path $env:TEMP "adfree-screen"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

switch ($Action) {
    "move" {
        [void][Win32Input]::SetCursorPos($X, $Y)
        Start-Sleep -Milliseconds 120
        $p = New-Object System.Drawing.Point
        [void][Win32Input]::GetCursorPos([ref]$p)
        "cursor        : $($p.X),$($p.Y)"
    }
    "click" {
        [void][Win32Input]::SetCursorPos($X, $Y)
        Start-Sleep -Milliseconds 150
        # LEFTDOWN/LEFTUP, RIGHTDOWN/RIGHTUP: sent separately so the shell reads
        # them as a real press on the icon rather than a synthetic jump.
        if ($Button -eq "right") {
            [Win32Input]::mouse_event(0x0008, 0, 0, 0, [IntPtr]::Zero)
            [Win32Input]::mouse_event(0x0010, 0, 0, 0, [IntPtr]::Zero)
        } else {
            [Win32Input]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
            [Win32Input]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
            if ($Button -eq "double") {
                Start-Sleep -Milliseconds 60
                [Win32Input]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
                [Win32Input]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
            }
        }
        "clicked       : $X,$Y ($Button)"
    }
    "key" {
        [System.Windows.Forms.SendKeys]::SendWait($Text)
        "sent          : $Text"
    }
    "type" {
        # Literal text: every SendKeys metacharacter escaped, because a password
        # or a path with braces is not a chord.
        $escaped = ($Text -replace '([+^%~(){}\[\]])', '{$1}')
        [System.Windows.Forms.SendKeys]::SendWait($escaped)
        "typed         : $($Text.Length) characters"
    }
    "shot" {
        $screen = [System.Windows.Forms.SystemInformation]::VirtualScreen
        $bmp = New-Object System.Drawing.Bitmap $screen.Width, $screen.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($screen.Left, $screen.Top, 0, 0, $bmp.Size)
        $g.Dispose()
        # Downscaled when wider than $MaxWidth: a 4K desktop becomes unreadable
        # to a reader with a token budget, and the tray icon is a small mark in a
        # large image either way - take the crop when the icon is the question.
        if ($bmp.Width -gt $MaxWidth) {
            $h = [int]($bmp.Height * $MaxWidth / $bmp.Width)
            $small = New-Object System.Drawing.Bitmap $MaxWidth, $h
            $sg = [System.Drawing.Graphics]::FromImage($small)
            $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $sg.DrawImage($bmp, 0, 0, $MaxWidth, $h)
            $sg.Dispose(); $bmp.Dispose()
            $bmp = $small
        }
        if (-not $Out) { $Out = Join-Path $dir "shot.png" }
        $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
        $size = "$($bmp.Width)x$($bmp.Height)"
        $bmp.Dispose()
        Say "shot          : $Out ($size)"
    }
    "winshot" {
        # The screen is a shared surface: anything raised in front of the app
        # hides it, and a machine nobody is sitting at keeps whatever window was
        # raised last. PrintWindow asks the window itself to draw into a bitmap,
        # so the answer does not depend on who is on top.
        $proc = Get-Process | Where-Object {
            $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "*$Title*"
        } | Select-Object -First 1
        if (-not $proc) { throw "no window whose title contains '$Title'" }
        $hwnd = $proc.MainWindowHandle
        if ([WinShot]::IsIconic($hwnd)) {
            # A minimised window has no client area to ask for; this is the one
            # case where the script has to reach across and touch the desktop.
            [WinShot]::ShowWindow($hwnd, 9) | Out-Null
            Start-Sleep -Milliseconds 400
        }
        $rect = New-Object WinShot+RECT
        [WinShot]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
        $w = $rect.Right - $rect.Left
        $h = $rect.Bottom - $rect.Top
        if ($w -le 0 -or $h -le 0) { throw "window has no area: $w x $h" }
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $hdc = $g.GetHdc()
        # 2 = PW_RENDERFULLCONTENT: without it a DWM-composited window (Compose
        # draws through Skia into one) prints as an empty white rectangle.
        $ok = [WinShot]::PrintWindow($hwnd, $hdc, 2)
        $g.ReleaseHdc($hdc)
        $g.Dispose()
        if (-not $ok) { $bmp.Dispose(); throw "PrintWindow refused the window" }
        if ($bmp.Width -gt $MaxWidth) {
            $nh = [int]($bmp.Height * $MaxWidth / $bmp.Width)
            $small = New-Object System.Drawing.Bitmap $MaxWidth, $nh
            $sg = [System.Drawing.Graphics]::FromImage($small)
            $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $sg.DrawImage($bmp, 0, 0, $MaxWidth, $nh)
            $sg.Dispose(); $bmp.Dispose()
            $bmp = $small
        }
        if (-not $Out) { $Out = Join-Path $dir "window.png" }
        $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
        $size = "$($bmp.Width)x$($bmp.Height)"
        $bmp.Dispose()
        Say "winshot       : $Out ($size) title=$($proc.MainWindowTitle)"
    }
}
