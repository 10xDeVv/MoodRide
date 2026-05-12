param(
    [switch]$Build,
    [string]$MavenRepoLocal = "C:/Users/aadeb/OneDrive/Desktop/MoodRide/.m2/repository",
    [string]$Xms = "256m",
    [string]$Xmx = "512m"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$workerJar = Join-Path $repoRoot "services/route-worker/target/route-worker-1.0.0-SNAPSHOT.jar"

if ($Build -or -not (Test-Path $workerJar)) {
    Write-Host "Building route-worker artifact..."
    Push-Location $repoRoot
    try {
        & mvn "-Dmaven.repo.local=$MavenRepoLocal" -pl services/route-worker -am package -DskipTests
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $workerJar)) {
    throw "Worker JAR not found at $workerJar"
}

Write-Host "Starting route-worker with JVM heap: -Xms$Xms -Xmx$Xmx"
Push-Location $repoRoot
try {
    & java "-Xms$Xms" "-Xmx$Xmx" -jar $workerJar
}
finally {
    Pop-Location
}
