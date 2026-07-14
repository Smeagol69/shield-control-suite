#!/usr/bin/env pwsh
# shield.ps1 — one-command control surface for the NVIDIA Shield TV.
#
# Wraps the adb bundled with this project (no system adb needed) and pins it to
# the Shield at 10.0.0.6:5555. Any Claude Code session (or you) can drive the
# device through this without re-deriving paths, serial, or the root pattern.
#
#   ./tools/shield.ps1 shell getprop ro.product.model     # any adb subcommand
#   ./tools/shield.ps1 root 'ls -al /data/data'           # run as root (Magisk su)
#   ./tools/shield.ps1 pull /sdcard/Download/x.mp4 .      # adb pull
#   ./tools/shield.ps1 push .\file.apk /sdcard/Download   # adb push
#   ./tools/shield.ps1 read /data/media/0/some/file       # cat a file to stdout (root)
#   ./tools/shield.ps1 fg                                  # current foreground app
#   ./tools/shield.ps1 connect                            # (re)establish the link
#
# FUSE denies /storage/emulated and /sdcard/Android/data even to root; read those
# via the raw backing store at /data/media/0/... (see rp() below).

param(
  [Parameter(Position = 0)] [string] $Cmd,
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$ErrorActionPreference = 'Stop'
$Serial = '10.0.0.6:5555'
$Adb = Join-Path $PSScriptRoot '..\vendor\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { throw "bundled adb not found at $Adb" }

function Ensure-Connected {
  $devs = & $Adb devices
  if ($devs -notmatch [regex]::Escape($Serial) -or $devs -notmatch "$([regex]::Escape($Serial))\s+device") {
    & $Adb connect $Serial | Out-Null
  }
}

# translate a presented path to one root can actually read past the FUSE layer
function rp([string] $p) {
  if ($p -eq '/sdcard' -or $p -like '/sdcard/*') { return '/data/media/0' + $p.Substring('/sdcard'.Length) }
  if ($p -eq '/storage/emulated' -or $p -like '/storage/emulated/*') { return '/data/media' + $p.Substring('/storage/emulated'.Length) }
  return $p
}

switch ($Cmd) {
  'connect' { & $Adb connect $Serial; break }
  'root' {
    Ensure-Connected
    $inner = ($Rest -join ' ')
    & $Adb -s $Serial shell su -c $inner
    break
  }
  'read' {
    Ensure-Connected
    $target = rp ($Rest[0])
    & $Adb -s $Serial exec-out su -c "cat '$target'"
    break
  }
  'fg' {
    Ensure-Connected
    & $Adb -s $Serial shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1"
    break
  }
  { $_ -in 'shell','pull','push','install','logcat','devices','reboot','uninstall' } {
    Ensure-Connected
    & $Adb -s $Serial $Cmd @Rest
    break
  }
  default {
    if ($Cmd) { Ensure-Connected; & $Adb -s $Serial $Cmd @Rest }
    else { Write-Output "usage: shield.ps1 <shell|root|read|fg|pull|push|install|connect|...> [args]" }
  }
}
