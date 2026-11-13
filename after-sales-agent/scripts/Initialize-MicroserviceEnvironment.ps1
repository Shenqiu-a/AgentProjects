$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot '.env.microservices'
if (Test-Path -LiteralPath $destination) { throw '.env.microservices already exists; existing credentials were preserved.' }
$text = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.microservices.example'))
foreach ($placeholder in @(
    'replace-with-a-random-secret-at-least-32-characters',
    'replace-with-auth-database-password', 'replace-with-order-database-password',
    'replace-with-knowledge-database-password', 'replace-with-agent-database-password',
    'replace-with-redis-password'
)) {
    $bytes = New-Object byte[] 36
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $text = $text.Replace($placeholder, [Convert]::ToBase64String($bytes))
}
[IO.File]::WriteAllText($destination, $text, [Text.UTF8Encoding]::new($false))
Write-Output 'Created .env.microservices with generated local credentials.'
