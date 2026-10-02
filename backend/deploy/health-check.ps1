param(
    [string]$ApiBaseUrl = "https://api.asifbot.com"
)

$ErrorActionPreference = "Stop"
$base = $ApiBaseUrl.TrimEnd("/")
$healthUrl = "$base/health"

Write-Host "Checking ASIFBOT backend: $healthUrl"
$response = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 20

if (-not $response.ok) {
    throw "Backend responded, but ok was not true."
}

Write-Host "OK: ASIFBOT backend is reachable at $base"
