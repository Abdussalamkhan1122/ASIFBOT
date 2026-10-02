param(
    [string]$OutputPath = ".\asifbot-backend.zip"
)

$ErrorActionPreference = "Stop"
$backendDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)

if (Test-Path -LiteralPath $destination) {
    Remove-Item -LiteralPath $destination -Force
}

$exclude = @(
    "data",
    ".env",
    "asifbot-backend.zip"
)

$items = Get-ChildItem -LiteralPath $backendDir -Force | Where-Object {
    $exclude -notcontains $_.Name
}

Compress-Archive -LiteralPath $items.FullName -DestinationPath $destination
Write-Host "Created backend package: $destination"
