#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(3, 100)][int]$Requests = 18,
    [ValidateRange(1, 20)][int]$ExpectedInstances = 1,
    [switch]$CheckSharedCsrf
)
$ErrorActionPreference = 'Stop'
function Read-JsonResponse {
    param($Response)
    $content = $Response.Content
    if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
    ConvertFrom-Json -InputObject ([string]$content)
}
$hits = @{}
for ($i = 0; $i -lt $Requests; $i++) {
    $response = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health') -NoProxy -TimeoutSec 10
    $upstream = [string](@($response.Headers['X-Upstream-Addr'])[0])
    $body = Read-JsonResponse $response
    if ($response.StatusCode -ne 200 -or $body.status -ne 'UP' -or !$upstream) {
        throw 'Health response was not UP or the upstream response header was missing.'
    }
    if (!$hits.ContainsKey($upstream)) { $hits[$upstream] = 0 }
    $hits[$upstream]++
}
$hits.GetEnumerator() | Sort-Object Key | ForEach-Object {
    [pscustomobject]@{ Upstream = $_.Key; Requests = $_.Value }
} | Format-Table -AutoSize
if ($hits.Count -ne $ExpectedInstances) {
    throw ('Expected ' + $ExpectedInstances + ' backends but observed ' + $hits.Count + '. Allow Docker DNS up to 5 seconds after scaling.')
}
if ($CheckSharedCsrf) {
    # 不使用真实账号；以预期的认证失败区分“CSRF 已跨实例通过”与 403。
    $csrfResponse = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/api/v1/auth/csrf') -SessionVariable probeSession -NoProxy -TimeoutSec 10
    $csrf = (Read-JsonResponse $csrfResponse).data
    $csrfUpstream = [string](@($csrfResponse.Headers['X-Upstream-Addr'])[0])
    $requestHeaders = @{}
    $requestHeaders[$csrf.headerName] = $csrf.token
    $probeBody = @{ username = 'lb-probe-' + [Guid]::NewGuid().ToString('N'); password = 'not-a-real-account-password' } | ConvertTo-Json
    $login = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/api/v1/auth/login') -Method POST -ContentType 'application/json' -Body $probeBody -Headers $requestHeaders -WebSession $probeSession -SkipHttpErrorCheck -NoProxy -TimeoutSec 10
    $loginUpstream = [string](@($login.Headers['X-Upstream-Addr'])[0])
    $result = Read-JsonResponse $login
    if ($login.StatusCode -ne 401) {
        throw ('Expected normal authentication rejection after CSRF validation; status=' + $login.StatusCode + ' code=' + $result.code)
    }
    if ($ExpectedInstances -gt 1 -and $csrfUpstream -eq $loginUpstream) {
        throw 'CSRF and login reached the same backend; rerun to verify a cross-instance request.'
    }
    Write-Output ('Shared CSRF verified: ' + $csrfUpstream + ' -> ' + $loginUpstream + ' (expected authentication rejection for a synthetic account)')
}
Write-Output 'Load balancing verification passed.'
