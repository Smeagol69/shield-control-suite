[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$connectionScript = Join-Path $PSScriptRoot '..\..\shield-control\tools\ShieldConnection.ps1'
. $connectionScript
$connection = Resolve-ShieldConnection -AdbPath $AdbPath -Serial $Serial
$AdbPath = $connection.Adb
$Serial = $connection.Serial

$monitor = "dev.roesler.marquee/dev.roesler.marquee.playback.PlaybackMonitorService"
$capture = "dev.roesler.marquee/dev.roesler.marquee.playback.PlaybackCaptureService"

& $AdbPath -s $Serial shell cmd notification allow_listener $monitor | Out-Null

$existing = ((& $AdbPath -s $Serial shell settings get secure enabled_accessibility_services) -join "").Trim()
$services = if ($existing -eq "null" -or [string]::IsNullOrWhiteSpace($existing)) {
    @()
} else {
    @($existing -split ":" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
if ($capture -notin $services) {
    $services += $capture
}

& $AdbPath -s $Serial shell settings put secure enabled_accessibility_services ($services -join ":")
& $AdbPath -s $Serial shell settings put secure accessibility_enabled 1

$listeners = ((& $AdbPath -s $Serial shell settings get secure enabled_notification_listeners) -join "")
$enabledServices = ((& $AdbPath -s $Serial shell settings get secure enabled_accessibility_services) -join "")
if (-not $listeners.Contains($monitor) -or -not $enabledServices.Contains($capture)) {
    throw "Android did not retain one or both Marquee playback grants."
}

Write-Output "Marquee playback bridge enabled on $Serial."
