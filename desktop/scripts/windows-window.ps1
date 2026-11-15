# Lists or moves the top-level windows of the interactive session.
#
# sshd on Windows spawns its children in session 0, which has no desktop: a
# screenshot taken from an ssh shell is black and a click goes nowhere. The
# scheduled task that runs this script is in the console session, so it can see
# the windows the user would see - and get them out of the way of a screenshot.
#
# ASCII only, CRLF line endings: Windows PowerShell 5.1 reads a BOM-less file in
# the console code page and a stray non-ASCII character breaks the parse.
param(
    [ValidateSet('list', 'minimize', 'restore')]
    [string]$Action = 'list',
    [string]$TitleLike = 'Command Prompt|PowerShell|cmd.exe|conhost',
    [string]$OutFile = ''
)

# Everything this script says goes to the file when one is asked for: a task in
# the interactive session has no console to print to.
function Say($text) {
    if ($OutFile -ne '') { Add-Content -LiteralPath $OutFile -Value $text } else { $text }
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class Win {
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
}
"@

$SW_MINIMIZE = 6
$SW_RESTORE = 9

$targets = Get-Process | Where-Object {
    $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -ne ''
} | Where-Object {
    $_.MainWindowTitle -match $TitleLike
}

if ($Action -eq 'list' -or $targets.Count -eq 0) {
    Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -ne '' } |
        Select-Object Id, ProcessName, MainWindowTitle |
        ForEach-Object { Say ("{0,-7} {1,-16} {2}" -f $_.Id, $_.ProcessName, $_.MainWindowTitle) }
    if ($targets.Count -eq 0) { Say "no window matches '$TitleLike'" }
    exit 0
}

foreach ($p in $targets) {
    $cmd = if ($Action -eq 'minimize') { $SW_MINIMIZE } else { $SW_RESTORE }
    $ok = [Win]::ShowWindowAsync($p.MainWindowHandle, $cmd)
    Say ("{0} {1} ({2})" -f $(if ($Action -eq 'minimize') { 'minimized' } else { 'restored' }), $p.ProcessName, $p.Id)
}
