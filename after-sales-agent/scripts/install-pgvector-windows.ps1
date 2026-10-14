[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PostgresRoot,
    [Parameter(Mandatory = $true)]
    [string]$SourceDirectory,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedDllSha256,
    [Parameter(Mandatory = $true)]
    [string]$ResultFile
)

# Install an already-built official pgvector 0.8.7 for PostgreSQL 18 x64.
# The caller must build with the target server's headers and libraries first.
# Existing different files are never overwritten; no service restart or deletion.
$ErrorActionPreference = 'Stop'
$taskResult = [ordered]@{
    status = 'FAILED'
    version = '0.8.7'
    installedFiles = 0
    completedAt = $null
}

try {
    $taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $taskPrincipal = New-Object Security.Principal.WindowsPrincipal($taskIdentity)
    if (-not $taskPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this installer with Windows administrator privileges.'
    }

    $taskPgRoot = (Get-Item -LiteralPath $PostgresRoot).FullName.TrimEnd('\')
    $taskSource = (Get-Item -LiteralPath $SourceDirectory).FullName
    $taskVersion = & (Join-Path $taskPgRoot 'bin\pg_config.exe') --version
    if ($LASTEXITCODE -ne 0 -or $taskVersion -ne 'PostgreSQL 18.0') {
        throw 'Target PostgreSQL does not match the verified 18.0 build.'
    }

    $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskSource 'vector.dll') -Algorithm SHA256).Hash
    if ($taskHash -ne $ExpectedDllSha256) {
        throw 'Compiled DLL changed after verification.'
    }

    $taskFiles = @(
        [PSCustomObject]@{ Source = (Join-Path $taskSource 'vector.dll'); Destination = (Join-Path $taskPgRoot 'lib\vector.dll') },
        [PSCustomObject]@{ Source = (Join-Path $taskSource 'vector.control'); Destination = (Join-Path $taskPgRoot 'share\extension\vector.control') }
    )
    foreach ($taskFile in (Get-ChildItem -LiteralPath (Join-Path $taskSource 'sql') -Filter 'vector--*.sql' -File)) {
        $taskFiles += [PSCustomObject]@{
            Source = $taskFile.FullName
            Destination = (Join-Path $taskPgRoot ('share\extension\' + $taskFile.Name))
        }
    }
    foreach ($taskHeader in @('halfvec.h', 'sparsevec.h', 'vector.h')) {
        $taskFiles += [PSCustomObject]@{
            Source = (Join-Path $taskSource ('src\' + $taskHeader))
            Destination = (Join-Path $taskPgRoot ('include\server\extension\vector\' + $taskHeader))
        }
    }
    if ($taskFiles.Count -ne 47) {
        throw 'Installation file count differs from the verified official build.'
    }

    foreach ($taskFile in $taskFiles) {
        $taskDestination = [IO.Path]::GetFullPath($taskFile.Destination)
        if (-not $taskDestination.StartsWith(($taskPgRoot + '\'), [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Destination escaped the verified PostgreSQL directory.'
        }
        if (-not (Test-Path -LiteralPath $taskFile.Source -PathType Leaf)) {
            throw 'A required source file is missing.'
        }
        if (Test-Path -LiteralPath $taskDestination) {
            if ((Get-FileHash -LiteralPath $taskDestination).Hash -ne (Get-FileHash -LiteralPath $taskFile.Source).Hash) {
                throw ('An existing file differs; installation stopped: ' + $taskDestination)
            }
        }
    }

    foreach ($taskFile in $taskFiles) {
        if (-not (Test-Path -LiteralPath $taskFile.Destination)) {
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskFile.Destination))
            [IO.File]::Copy($taskFile.Source, $taskFile.Destination, $false)
        }
        if ((Get-FileHash -LiteralPath $taskFile.Destination).Hash -ne (Get-FileHash -LiteralPath $taskFile.Source).Hash) {
            throw 'Installed file hash mismatch.'
        }
        $taskResult.installedFiles++
    }
    $taskResult.status = 'SUCCESS'
    $taskResult.postgresRoot = $taskPgRoot
    $taskResult.dllSha256 = $taskHash.ToLowerInvariant()
}
catch {
    $taskResult.error = $_.Exception.Message
}
finally {
    $taskResult.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $taskResult | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ResultFile -Encoding UTF8
}

if ($taskResult.status -ne 'SUCCESS') { exit 1 }
