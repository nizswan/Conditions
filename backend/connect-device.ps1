# Run after starting your emulator or connecting a phone with USB debugging.
$adbPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) {
    $adbPath = 'adb'
}
& $adbPath reverse tcp:8000 tcp:8000
if ($LASTEXITCODE -ne 0) {
    throw 'Start one emulator or connect one USB-debugging device, then try again.'
}
Write-Output 'App connected to the local backend without local-network permission.'

# Give an emulator this computer's Windows location. Never override a real phone.
$device = (& $adbPath get-serialno).Trim()
if ($device -match '^emulator-[0-9]+$') {
    $syncScript = Join-Path $PSScriptRoot 'sync-emulator-location.ps1'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $syncScript -Device $device -Once
    if ($LASTEXITCODE -eq 0) {
        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $syncScript + '"'), '-Device', $device)
        Write-Output 'Windows location sync started. It stops when the emulator closes.'
    } else {
        Write-Warning 'Backend connected, but Windows location is unavailable. See the message above.'
    }
}
