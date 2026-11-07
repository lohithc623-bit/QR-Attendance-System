$ErrorActionPreference = "Stop"

$launcher = Join-Path $PSScriptRoot "run-desktop.bat"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Desktop launcher not found: $launcher"
}

$desktop = [Environment]::GetFolderPath("Desktop")
if ([string]::IsNullOrWhiteSpace($desktop)) {
    throw "Could not determine the current user's Desktop folder."
}

$shortcutPath = Join-Path $desktop "QR Attendance.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $launcher
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = "Launch QR Attendance System"
$shortcut.Save()

Write-Host "Created desktop shortcut: $shortcutPath"
