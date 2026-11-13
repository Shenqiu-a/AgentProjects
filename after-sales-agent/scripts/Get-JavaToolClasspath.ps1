param([string]$MavenRepository)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $projectRoot 'backend'
$mavenArgs = @('-B', '-ntp')
if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
& mvn @mavenArgs -f (Join-Path $backend 'pom.xml') -pl auth-service -am install -DskipTests | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Build of Java database tool dependencies failed.' }
$classpathPath = Join-Path $backend 'auth-service/target/database-tool-classpath.txt'
& mvn @mavenArgs -f (Join-Path $backend 'auth-service/pom.xml') dependency:build-classpath -DincludeScope=runtime "-Dmdep.outputFile=$classpathPath" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Java database tool classpath resolution failed.' }
[IO.File]::ReadAllText($classpathPath).Trim()
