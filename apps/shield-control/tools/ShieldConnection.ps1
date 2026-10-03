Set-StrictMode -Version Latest

function Find-ShieldAdb {
    [CmdletBinding()]
    param([string]$AdbPath)

    $candidates = @(
        $AdbPath
        $env:SHIELD_ADB
        (Join-Path $PSScriptRoot '..\vendor\platform-tools\adb.exe')
        (Join-Path $env:LOCALAPPDATA 'Programs\shield-control\resources\vendor\platform-tools\adb.exe')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    throw 'adb was not found. Install Shield Control or pass -AdbPath explicitly.'
}

function Test-ShieldEndpoint {
    param(
        [Parameter(Mandatory = $true)] [string]$Adb,
        [Parameter(Mandatory = $true)] [string]$Serial
    )

    $state = ((& $Adb -s $Serial get-state 2>$null) -join '').Trim()
    if ($state -ne 'device') { return $false }

    $model = ((& $Adb -s $Serial shell getprop ro.product.model 2>$null) -join '').Trim()
    return $model -match 'SHIELD'
}

function Resolve-ShieldConnection {
    [CmdletBinding()]
    param(
        [string]$AdbPath,
        [string]$Serial
    )

    $adb = Find-ShieldAdb -AdbPath $AdbPath
    $null = & $adb start-server

    $requested = if ($Serial) { $Serial } elseif ($env:SHIELD_SERIAL) { $env:SHIELD_SERIAL } else { $null }
    if ($requested) {
        if ($requested.Contains(':')) { $null = & $adb connect $requested 2>$null }
        if (Test-ShieldEndpoint -Adb $adb -Serial $requested) {
            return [pscustomobject]@{ Adb = $adb; Serial = $requested }
        }
        throw "The requested Shield is not reachable: $requested"
    }

    $connected = & $adb devices -l
    foreach ($line in $connected) {
        if ($line -match '^([^\s]+)\s+device\b.*\bmodel:SHIELD_Android_TV\b') {
            return [pscustomobject]@{ Adb = $adb; Serial = $Matches[1] }
        }
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($line in (& $adb mdns services 2>$null)) {
        if ($line -match '_adb(?:-tls-connect)?\._tcp\s+([^\s]+:\d+)') {
            $candidates.Add($Matches[1])
        }
    }
    $candidates.Add('10.0.0.11:5555')
    $candidates.Add('10.0.0.6:5555')

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $null = & $adb connect $candidate 2>$null
        if (Test-ShieldEndpoint -Adb $adb -Serial $candidate) {
            return [pscustomobject]@{ Adb = $adb; Serial = $candidate }
        }
    }

    throw 'No NVIDIA Shield is reachable over adb. Start Shield Control or connect the Shield over USB.'
}
