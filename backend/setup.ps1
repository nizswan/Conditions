# Generate the database password once without displaying it.
$envPath = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $password = [Convert]::ToBase64String($bytes)
    Set-Content -LiteralPath $envPath -Value "POSTGRES_PASSWORD=$password" -Encoding ascii
}
Write-Output 'Database configuration ready.'
