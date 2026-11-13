[CmdletBinding()]
param(
    [ValidateSet('plan', 'copy', 'seed-accounts')][string]$Action = 'plan',
    [string]$ClasspathFile,
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($ClasspathFile) {
    $classpath = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ClasspathFile)).Trim()
} else {
    $classpath = & (Join-Path $PSScriptRoot 'Get-JavaToolClasspath.ps1') -MavenRepository $MavenRepository
}
& java '-Dfile.encoding=UTF-8' -cp $classpath (Join-Path $PSScriptRoot 'MicroserviceDataMigration.java') $projectRoot $Action
if ($LASTEXITCODE -ne 0) { throw 'Data migration failed. No automatic deletion or reset was performed.' }
