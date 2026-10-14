param(
    [ValidateSet('setup', 'migrate', 'verify')]
    [string]$Action = 'setup',
    [string]$MavenRepository,
    # 已有有效 classpath 时跳过 Maven；通常不需要填写。
    [string]$ClasspathFile
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
if (-not $ClasspathFile) {
    $ClasspathFile = Join-Path $backendRoot 'target\database-tool-classpath.txt'
    $mavenArgs = @('-B', '-ntp', '-f', (Join-Path $backendRoot 'pom.xml'))
    if ($MavenRepository) { $mavenArgs += ('-Dmaven.repo.local=' + $MavenRepository) }
    $mavenArgs += @('dependency:build-classpath', '-DincludeScope=runtime', ('-Dmdep.outputFile=' + $ClasspathFile))
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Maven classpath resolution failed.' }
}
if (-not (Test-Path -LiteralPath $ClasspathFile)) { throw 'Classpath file does not exist.' }
$runtimeClasspath = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ClasspathFile)).Trim()
& java '-Dfile.encoding=UTF-8' -cp $runtimeClasspath (Join-Path $PSScriptRoot 'DatabaseSetup.java') $projectRoot $Action
if ($LASTEXITCODE -ne 0) { throw 'Database operation failed; inspect the error above.' }
