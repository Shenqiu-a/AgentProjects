[CmdletBinding()]
param(
    [switch]$InfrastructureOnly,
    [switch]$SkipBuild,
    [ValidateRange(1, 20)][int]$AuthReplicas = 1
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configuration = Join-Path $projectRoot '.env.microservices'
if (!(Test-Path -LiteralPath $configuration)) {
    throw 'Copy .env.microservices.example to .env.microservices and replace the credentials first.'
}
Get-Command docker -ErrorAction Stop | Out-Null
Push-Location $projectRoot
try {
    $composeArgs = @('compose', '--env-file', '.env.microservices', '-f', 'compose.yaml')
    if ($InfrastructureOnly) {
        & docker @composeArgs up -d --wait auth-db order-db knowledge-db agent-db redis
    } else {
        $startArgs = @('up', '-d', '--scale', "auth-service=$AuthReplicas")
        if (!$SkipBuild) { $startArgs += '--build' }
        & docker @composeArgs @startArgs
    }
    if ($LASTEXITCODE -ne 0) { throw 'Microservice startup failed. Inspect docker compose logs.' }
    if (!$InfrastructureOnly) {
        $port = 8080
        foreach ($line in Get-Content -LiteralPath $configuration) {
            if ($line -match '^GATEWAY_PORT=(\d+)\s*$') { $port = [int]$Matches[1] }
        }
        if ($env:GATEWAY_PORT) { $port = [int]$env:GATEWAY_PORT }
        foreach ($name in @('auth', 'orders', 'knowledge', 'agent')) {
            $ready = $false
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                try {
                    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health/$name" -TimeoutSec 3
                    if ($health.status -eq 'UP') { $ready = $true; break }
                } catch { }
                Start-Sleep -Seconds 2
            }
            if (!$ready) { throw "Service $name is not ready. Inspect docker compose logs." }
            Write-Output "$name is ready"
        }
        Write-Output "Gateway: http://127.0.0.1:$port"
    }
} finally { Pop-Location }
