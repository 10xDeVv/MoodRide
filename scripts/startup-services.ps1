param(
    [switch]$SkipBuild,
    [switch]$ForceBuild,
    [int]$RouteApiHealthTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Path $PSScriptRoot -Parent

function Test-ListeningPort {
    param([int]$Port)
    try {
        $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        foreach ($endpoint in $listeners) {
            if ($endpoint.Port -eq $Port) {
                return $true
            }
        }
        return $false
    } catch {
        return $false
    }
}

function Wait-ForHttpUp {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 120,
        [int]$DelaySeconds = 3
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri $Url -TimeoutSec 5
            if ($resp.status -eq "UP") {
                return $true
            }
        } catch {
            # Keep polling until timeout.
        }
        Start-Sleep -Seconds $DelaySeconds
    }

    return $false
}

Write-Host "MoodRide service startup"
Write-Host "Project root: $projectRoot"
Write-Host "Build mode: SkipBuild=$SkipBuild ForceBuild=$ForceBuild"

Set-Location $projectRoot

$requiredInfra = @("postgres", "redis", "kafka", "zookeeper", "debezium", "osrm")
Write-Host "Ensuring required infrastructure services are running..."
docker compose up -d postgres redis kafka zookeeper debezium debezium-init osrm | Out-Null

$infraWaitOk = $true
for ($i = 0; $i -lt 10; $i++) {
    $runningInfra = docker compose ps --services --filter "status=running"
    $missing = $requiredInfra | Where-Object { $runningInfra -notcontains $_ }
    if ($missing.Count -eq 0) {
        break
    }
    if ($i -eq 9) {
        $infraWaitOk = $false
        Write-Host "[warn] infra still missing after wait: $($missing -join ', ')"
        break
    }
    Start-Sleep -Seconds 3
}

if ($infraWaitOk) {
    foreach ($service in $requiredInfra) {
        Write-Host "[ok] infra service running: $service"
    }
}

if (-not $SkipBuild) {
    $requiredJars = @(
        "services/route-api/target/route-api-1.0.0-SNAPSHOT.jar",
        "services/route-worker/target/route-worker-1.0.0-SNAPSHOT.jar",
        "services/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
    )

    $missingArtifacts = @()
    foreach ($jar in $requiredJars) {
        $path = Join-Path $projectRoot $jar
        if (-not (Test-Path -Path $path -PathType Leaf)) {
            $missingArtifacts += $jar
        }
    }

    if ($ForceBuild -or $missingArtifacts.Count -gt 0) {
        if ($missingArtifacts.Count -gt 0) {
            Write-Host "Missing artifacts detected: $($missingArtifacts -join ', ')"
        }

        Write-Host "Installing shared modules to local Maven repo..."
        mvn -pl shared/geo-commons,shared/event-models,shared/data-models -am -DskipTests install
        if ($LASTEXITCODE -ne 0) {
            throw "Shared module install failed."
        }

        Write-Host "Building service modules (tests skipped)..."
        mvn -pl services/route-api,services/route-worker,services/notification-service -am package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw "Service build failed."
        }
    } else {
        Write-Host "[ok] Existing service artifacts found; skipping build. Use -ForceBuild to rebuild."
    }
}

$routeApiPort = 8080
Write-Host "Checking Route API port $routeApiPort..."
if (Test-ListeningPort -Port $routeApiPort) {
    Write-Host "[warn] route-api port $routeApiPort already in use; assuming service may already be running."
} else {
    $routeApiPom = Join-Path $projectRoot "services/route-api/pom.xml"
    $routeApiCmd = "mvn -f '$routeApiPom' spring-boot:run"
    Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $routeApiCmd -WindowStyle Normal
    Write-Host "[ok] started window for route-api"
}

Write-Host "Waiting for Route API health..."
if (Wait-ForHttpUp -Url "http://localhost:8080/actuator/health" -TimeoutSeconds $RouteApiHealthTimeoutSeconds) {
    Write-Host "[ok] Route API is healthy"
} else {
    Write-Host "[warn] Route API did not become healthy within timeout ($RouteApiHealthTimeoutSeconds s)"
}

$servicePoms = @(
    @{ Name = "route-worker"; Pom = "services/route-worker/pom.xml"; Port = 8081 },
    @{ Name = "cdc-service"; Pom = "services/cdc-service/pom.xml"; Port = 8082 },
    @{ Name = "notification-service"; Pom = "services/notification-service/pom.xml"; Port = 8084 },
    @{ Name = "scenic-scoring-service"; Pom = "services/scenic-scoring-service/pom.xml"; Port = 8085 },
    @{ Name = "ingestion-service"; Pom = "services/ingestion-service/pom.xml"; Port = 8086 }
)

foreach ($svc in $servicePoms) {
    if (Test-ListeningPort -Port $svc.Port) {
        Write-Host "[warn] $($svc.Name) port $($svc.Port) already in use; skipping launch"
        continue
    }

    $pomPath = Join-Path $projectRoot $svc.Pom
    if ($svc.Name -in @("route-worker", "notification-service")) {
        $jarName = if ($svc.Name -eq "route-worker") { "route-worker-1.0.0-SNAPSHOT.jar" } else { "notification-service-1.0.0-SNAPSHOT.jar" }
        $jarPath = Join-Path (Split-Path $pomPath -Parent) "target/$jarName"
        $cmd = "java -Xms32m -Xmx128m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -jar '$jarPath'"
    } else {
        $cmd = "mvn -f '$pomPath' spring-boot:run"
    }
    Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $cmd -WindowStyle Normal
    Write-Host "[ok] started window for $($svc.Name)"
    Start-Sleep -Seconds 2
}

Write-Host "Services launched."
Write-Host "Run E2E after startup settles:"
Write-Host "  .\\scripts\\e2e-test.ps1"


