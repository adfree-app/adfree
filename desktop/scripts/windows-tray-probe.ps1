# What the tray actually does on this machine.
#
# Run it on the Windows box, with the app already started:
#
#   powershell -ExecutionPolicy Bypass -File windows-tray-probe.ps1
#
# It answers three questions a Linux machine cannot: is the app running, does
# its data directory look like a working install, and what does the notification
# area look like right now. The screenshots are what a person reads; the text is
# what a later comparison diffs.
param(
    [string]$OutDir = "$env:TEMP\adfree-tray-probe",
    [string]$DataDir = "$env:USERPROFILE\.local\share\adfree"
)

$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Section($name) { "`n=== $name ===" }

Write-Output (Section "machine")
"os            : " + (Get-CimInstance Win32_OperatingSystem).Caption
"version       : " + [System.Environment]::OSVersion.Version
"arch          : " + $env:PROCESSOR_ARCHITECTURE
"session       : " + (Get-Process -Id $PID).SessionId
"interactive   : " + [System.Environment]::UserInteractive

Write-Output (Section "processes")
Get-Process | Where-Object { $_.ProcessName -match 'adfree' } |
    Select-Object ProcessName, Id, SessionId, StartTime, WorkingSet64 |
    Format-Table -AutoSize | Out-String -Width 200

# The install lays down its own JVM; the running app is that java.exe, not a
# system-wide one, so the path is what identifies it.
Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'adfree.exe'" |
    Select-Object ProcessId, ExecutablePath |
    Format-Table -AutoSize | Out-String -Width 300

Write-Output (Section "data")
if (Test-Path $DataDir) {
    Get-ChildItem -Force $DataDir | Select-Object Name, Length, LastWriteTime |
        Format-Table -AutoSize | Out-String -Width 200
    $db = Join-Path $DataDir "adfree.db"
    if (Test-Path $db) { "adfree.db     : " + (Get-Item $db).Length + " bytes" }
    $log = Get-ChildItem -Path $DataDir -Filter "*.log" -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($log) {
        "newest log    : " + $log.FullName + " (" + $log.Length + " bytes)"
        "--- last 40 lines ---"
        Get-Content $log.FullName -Tail 40
    } else {
        "newest log    : none"
    }
} else {
    "no data directory at $DataDir - the app has never run for this user"
}

Write-Output (Section "ports")
# The DNS server and the proxy are what "it is running" really means.
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 5300, 3129, 80, 443 } |
    Select-Object LocalAddress, LocalPort, OwningProcess |
    Format-Table -AutoSize | Out-String -Width 200

Write-Output (Section "screenshots")
# Scaled coordinates would put the crop next to the tray instead of on it.
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public class DpiAware { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
"@
[void][DpiAware]::SetProcessDPIAware()
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$screen = [System.Windows.Forms.SystemInformation]::VirtualScreen
$full = New-Object System.Drawing.Bitmap $screen.Width, $screen.Height
$g = [System.Drawing.Graphics]::FromImage($full)
$g.CopyFromScreen($screen.Left, $screen.Top, 0, 0, $full.Size)
$fullPath = Join-Path $OutDir "screen.png"
$full.Save($fullPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
"screen        : $fullPath ($($screen.Width)x$($screen.Height))"

# The notification area is the bottom-right corner of the primary screen; the
# icon is 16-24 px there, so the crop is scaled up before anyone looks at it.
$primary = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$cropW = [Math]::Min(900, $primary.Width)
$cropH = 90
$cropX = $primary.Right - $cropW
$cropY = $primary.Bottom - $cropH
$crop = New-Object System.Drawing.Bitmap ($cropW * 2), ($cropH * 2)
$cg = [System.Drawing.Graphics]::FromImage($crop)
$cg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$cg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$cg.DrawImage($full, [System.Drawing.Rectangle]::new(0, 0, $cropW * 2, $cropH * 2),
    [System.Drawing.Rectangle]::new($cropX - $screen.Left, $cropY - $screen.Top, $cropW, $cropH),
    [System.Drawing.GraphicsUnit]::Pixel)
$cg.Dispose()
$cropPath = Join-Path $OutDir "tray.png"
$crop.Save($cropPath, [System.Drawing.Imaging.ImageFormat]::Png)
# `$full` is what the crop is read from, so it is released only after it: a
# disposed bitmap draws as an exception, not as a blank corner.
$crop.Dispose(); $full.Dispose()
"tray crop     : $cropPath ($($cropW * 2)x$($cropH * 2), the rightmost $cropW px of the taskbar)"

Write-Output (Section "done")
"everything above is also in this console; the two PNGs are in $OutDir"
