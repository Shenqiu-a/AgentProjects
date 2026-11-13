# Compatibility entry point. The microservice Compose stack replaces the single-backend stack.
[CmdletBinding()]
param(
    [ValidateRange(1, 20)][int]$Replicas = 1,
    [string]$AppImage
)
if ($AppImage) { throw 'A single AppImage no longer represents this project. Build the four services using scripts/Start-Microservices.ps1.' }
& (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/Start-Microservices.ps1') -AuthReplicas $Replicas
