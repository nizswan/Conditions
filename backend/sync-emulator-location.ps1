param(
    [switch]$Once,
    [string]$Device
)

# Windows PowerShell 5.1 provides System.Device. No web lookup or API key.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Device
$adbPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { $adbPath = 'adb' }
if (-not $Device) { $Device = (& $adbPath get-serialno).Trim() }
if ($Device -notmatch '^emulator-[0-9]+$') {
    throw 'Choose one running Android emulator. Physical phones use their own GPS.'
}

$locationWatcher = New-Object System.Device.Location.GeoCoordinateWatcher(
    [System.Device.Location.GeoPositionAccuracy]::High)
$locationWatcher.MovementThreshold = 10
$culture = [Globalization.CultureInfo]::InvariantCulture
$statusFolder = Join-Path $env:LOCALAPPDATA 'Conditions'
New-Item -ItemType Directory -Path $statusFolder -Force | Out-Null
$statusFile = Join-Path $statusFolder 'emulator-location-status.txt'
$syncLock = New-Object Threading.Mutex($false, "Local\ConditionsLocation-$Device")
if (-not $syncLock.WaitOne(0)) {
    $syncLock.Dispose()
    Write-Output 'Location sync is already running for this emulator.'
    exit 0
}

try {
    $locationWatcher.TryStart($false, [TimeSpan]::FromSeconds(15)) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ($locationWatcher.Position.Location.IsUnknown -and [DateTime]::UtcNow -lt $deadline -and
            $locationWatcher.Permission -ne 'Denied' -and $locationWatcher.Status -ne 'Disabled') {
        Start-Sleep -Milliseconds 500
    }
    do {
        $deviceState = & $adbPath -s $Device get-state 2>$null
        if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { break }
        $position = $locationWatcher.Position
        if ($locationWatcher.Permission -eq 'Denied' -or $locationWatcher.Status -eq 'Disabled') {
            throw 'Windows location is disabled or denied. Enable Location services and desktop app location access in Windows Settings > Privacy & security > Location.'
        }
        if ($position.Location.IsUnknown -or ([DateTimeOffset]::Now - $position.Timestamp).TotalMinutes -gt 5) {
            $message = 'Windows has not supplied a current location. No coordinates were sent to the emulator.'
            Set-Content -LiteralPath $statusFile -Value $message
            if ($Once) { throw $message }
        } else {
            $longitude = $position.Location.Longitude.ToString('F6', $culture)
            $latitude = $position.Location.Latitude.ToString('F6', $culture)
            $reply = & $adbPath -s $Device emu geo fix $longitude $latitude
            if ($LASTEXITCODE -ne 0 -or $reply -match '^KO') {
                throw 'Could not send location; the emulator may have closed.'
            }
            $accuracy = $position.Location.HorizontalAccuracy.ToString('F0', $culture)
            $message = "Windows location sent to $Device. Estimated accuracy: $accuracy metres. Updated: $([DateTime]::Now.ToString('s'))"
            Set-Content -LiteralPath $statusFile -Value $message
            if ($Once) { Write-Output $message }
        }
        if (-not $Once) { Start-Sleep -Seconds 10 }
    } while (-not $Once)
} catch {
    Set-Content -LiteralPath $statusFile -Value $_.Exception.Message
    Write-Error $_.Exception.Message
    exit 1
} finally {
    $locationWatcher.Stop()
    $locationWatcher.Dispose()
    $syncLock.ReleaseMutex()
    $syncLock.Dispose()
}
