#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(0, 20)][int]$Replicas = 0,
    [string]$AppImage
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$oldReplicas = $env:APP_REPLICAS
$oldImage = $env:APP_IMAGE

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw ('Docker command failed: docker ' + ($Arguments[0..([Math]::Min(2, $Arguments.Count - 1))] -join ' '))
    }
}

function Invoke-Portainer {
    param([string]$Method, [string]$Path, $Payload, [hashtable]$Headers = @{})
    $request = @{
        Uri = $script:portainerUrl + $Path
        Method = $Method
        Headers = $Headers
        SkipCertificateCheck = $true
        NoProxy = $true
        TimeoutSec = 240
    }
    if ($null -ne $Payload) {
        $request.ContentType = 'application/json'
        $request.Body = ConvertTo-Json -InputObject $Payload -Depth 100 -Compress
    }
    Invoke-RestMethod @request
}

Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath '.env')) { throw 'Create .env from .env.example and fill in database passwords first.' }
    if ($Replicas -gt 0) { $env:APP_REPLICAS = [string]$Replicas }
    if ($AppImage) { $env:APP_IMAGE = $AppImage }

    $composeFiles = @('-f', 'compose.yaml', '-f', 'compose.image.yaml', '-f', 'compose.gateway.yaml')
    $raw = (Invoke-Docker -Arguments (@('compose') + $composeFiles + @('config', '--format', 'json'))) -join [Environment]::NewLine
    $config = ConvertFrom-Json -InputObject $raw -AsHashtable
    $projectName = [string]$config.name
    $appImageName = [string]$config.services.backend.image
    $gatewayImageName = [string]$config.services.nginx.image
    $replicaCount = [int]$config.services.backend.scale
    $gatewayPort = [int]$config.services.nginx.ports[0].published

    $gatewayMappings = (Invoke-Docker -Arguments @('ps', '--filter', ('label=com.docker.compose.project=' + $projectName), '--filter', 'label=com.docker.compose.service=nginx', '--format', '{{.Ports}}')) -join ' '
    if (!$gatewayMappings.Contains('127.0.0.1:' + $gatewayPort + '->')) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $gatewayPort)
        try { $listener.Start() }
        catch { throw ('GATEWAY_PORT=' + $gatewayPort + ' cannot bind on Windows. Set an available port in .env; reserved ports also fail even without a listening process.') }
        finally { $listener.Stop() }
    }
    Invoke-Docker -Arguments @('image', 'inspect', $appImageName, '--format', '{{.Id}}') | Out-Null

    # 应用 Stack 由 Portainer 维护；去掉当前未启用的评测数据库。
    [void]$config.services.Remove('postgres-eval')
    [void]$config.volumes.Remove('postgres-eval-data')
    # 复用已有命名卷并标记为 external，避免通过 Stack 删除业务数据卷。
    foreach ($key in @($config.volumes.Keys)) {
        $volumeName = [string]$config.volumes[$key].name
        Invoke-Docker -Arguments @('volume', 'inspect', $volumeName, '--format', '{{.Name}}') | Out-Null
        $config.volumes[$key] = @{ name = $volumeName; external = $true }
    }

    Write-Output ('Building gateway image: ' + $gatewayImageName)
    Invoke-Docker -Arguments @('build', '-t', $gatewayImageName, 'deploy/nginx')

    $credentialDir = Join-Path $projectRoot 'data/local'
    [void][IO.Directory]::CreateDirectory($credentialDir)
    $passwordFile = Join-Path $credentialDir 'portainer-admin.password'
    if (!(Test-Path -LiteralPath $passwordFile)) {
        $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) + 'aA1!'
        [IO.File]::WriteAllText($passwordFile, $password, [Text.UTF8Encoding]::new($false))
    }
    $password = [IO.File]::ReadAllText($passwordFile).Trim()
    if ($password.Length -lt 12) { throw 'Portainer admin password file must contain at least 12 characters.' }

    Invoke-Docker -Arguments @('compose', '-p', 'after-sales-management', '-f', 'compose.portainer.yaml', 'up', '-d')
    $managementRaw = (Invoke-Docker -Arguments @('compose', '-p', 'after-sales-management', '-f', 'compose.portainer.yaml', 'config', '--format', 'json')) -join [Environment]::NewLine
    $managementConfig = ConvertFrom-Json -InputObject $managementRaw -AsHashtable
    $portainerPort = [int]$managementConfig.services.portainer.ports[0].published
    $script:portainerUrl = 'https://127.0.0.1:' + $portainerPort

    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $null = Invoke-RestMethod -Uri ($portainerUrl + '/api/status') -SkipCertificateCheck -NoProxy -TimeoutSec 3
            $ready = $true
            break
        } catch { Start-Sleep -Seconds 2 }
    }
    if (!$ready) { throw 'Portainer did not become ready in 120 seconds.' }

    $auth = Invoke-Portainer -Method POST -Path '/api/auth' -Payload @{ Username = 'admin'; Password = $password }
    $headers = @{ Authorization = 'Bearer ' + $auth.jwt }
    $endpoints = @(Invoke-Portainer -Method GET -Path '/api/endpoints' -Headers $headers)
    $endpoint = $endpoints | Where-Object { $_.Type -eq 1 -and $_.URL -eq 'unix:///var/run/docker.sock' } | Select-Object -First 1
    if (!$endpoint) {
        $endpoint = Invoke-RestMethod -Uri ($portainerUrl + '/api/endpoints') -Method Post -SkipCertificateCheck -NoProxy -Headers $headers -Form @{
            Name = 'local-docker'
            EndpointCreationType = '1'
            URL = 'unix:///var/run/docker.sock'
        }
    }

    # 凭证单独交给 Portainer 的 Environment variables；编辑器保留变量引用。
    $stackEnv = @{}
    foreach ($key in @($config.services.backend.environment.Keys)) {
        $stackEnv[$key] = [string]$config.services.backend.environment[$key]
        $config.services.backend.environment[$key] = '${' + $key + '}'
    }
    $stackEnv.POSTGRES_DB = [string]$config.services.postgres.environment.POSTGRES_DB
    $stackEnv.DATABASE_USERNAME = [string]$config.services.postgres.environment.POSTGRES_USER
    $stackEnv.DATABASE_PASSWORD = [string]$config.services.postgres.environment.POSTGRES_PASSWORD
    $stackEnv.REDIS_PASSWORD = [string]$config.services.redis.environment.REDIS_PASSWORD
    $config.services.postgres.environment.POSTGRES_DB = '${POSTGRES_DB}'
    $config.services.postgres.environment.POSTGRES_USER = '${DATABASE_USERNAME}'
    $config.services.postgres.environment.POSTGRES_PASSWORD = '${DATABASE_PASSWORD}'
    $config.services.redis.environment.REDIS_PASSWORD = '${REDIS_PASSWORD}'
    $config.services.redis.command[-1] = '${REDIS_PASSWORD}'
    $stackEnv.APP_IMAGE = $appImageName
    $stackEnv.APP_REPLICAS = [string]$replicaCount
    $stackEnv.GATEWAY_IMAGE = $gatewayImageName
    $stackEnv.GATEWAY_PORT = [string]$gatewayPort
    $config.services.backend.image = '${APP_IMAGE}'
    $config.services.backend.scale = '${APP_REPLICAS}'
    $config.services.nginx.image = '${GATEWAY_IMAGE}'
    $config.services.nginx.ports[0].published = '${GATEWAY_PORT}'
    $pairs = @($stackEnv.GetEnumerator() | Sort-Object Key | ForEach-Object { @{ name = $_.Key; value = [string]$_.Value } })
    $stackContent = ConvertTo-Json -InputObject $config -Depth 100
    # Compose JSON 与 YAML 等价；保留安全的模板供审阅，不落盘真实应用凭证。
    [IO.File]::WriteAllText((Join-Path $credentialDir 'portainer-stack.template.json'), $stackContent, [Text.UTF8Encoding]::new($false))

    $stacks = @(Invoke-Portainer -Method GET -Path '/api/stacks' -Headers $headers)
    $stack = $stacks | Where-Object { $_.Name -eq $projectName -and $_.EndpointId -eq $endpoint.Id } | Select-Object -First 1
    if ($stack) {
        $payload = @{ StackFileContent = $stackContent; Env = $pairs; PullImage = $false; Prune = $false }
        $stack = Invoke-Portainer -Method PUT -Path ('/api/stacks/' + $stack.Id + '?endpointId=' + $endpoint.Id) -Headers $headers -Payload $payload
    } else {
        $payload = @{ Name = $projectName; StackFileContent = $stackContent; Env = $pairs; FromAppTemplate = $false }
        $stack = Invoke-Portainer -Method POST -Path ('/api/stacks/create/standalone/string?endpointId=' + $endpoint.Id) -Headers $headers -Payload $payload
    }

    $gatewayUrl = 'http://127.0.0.1:' + $gatewayPort
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri ($gatewayUrl + '/actuator/health') -NoProxy -TimeoutSec 5
            if ($health -is [byte[]]) { $health = [Text.Encoding]::UTF8.GetString($health) | ConvertFrom-Json }
            elseif ($health -is [string]) { $health = ConvertFrom-Json $health }
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch {}
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Gateway could not reach a healthy backend in 180 seconds. Inspect the Portainer stack and container logs.' }

    Write-Output ('Business entry: ' + $gatewayUrl)
    Write-Output ('Portainer: ' + $portainerUrl)
    Write-Output ('Stack: ' + $projectName + '; requested replicas: ' + $replicaCount)
    Write-Output ('Admin user: admin; password file: ' + $passwordFile)
    Write-Output 'Use Portainer > Stacks > Environment variables to change APP_IMAGE / APP_REPLICAS, then Update the stack (do not force image re-pull for local images).'
} finally {
    $env:APP_REPLICAS = $oldReplicas
    $env:APP_IMAGE = $oldImage
    Pop-Location
}
