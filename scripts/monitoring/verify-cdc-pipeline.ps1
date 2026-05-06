param(
    [string]$CdcBaseUrl = "http://localhost:8082",
    [string]$DebeziumBaseUrl = "http://localhost:8083",
    [string]$ConnectorName = "moodride-postgres-connector",
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = "GET",
        [object]$Body = $null
    )

    try {
        if ($null -ne $Body) {
            $payload = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 }
            return Invoke-RestMethod -Uri $Uri -Method $Method -ContentType "application/json" -Body $payload -TimeoutSec 10
        }
        return Invoke-RestMethod -Uri $Uri -Method $Method -TimeoutSec 10
    } catch {
        if ($_.ErrorDetails.Message) {
            throw $_.ErrorDetails.Message
        }
        throw $_.Exception.Message
    }
}

function Invoke-JsonRequestWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = "GET",
        [object]$Body = $null,
        [int]$Attempts = 6,
        [int]$DelaySeconds = 2
    )

    $lastError = $null
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            return Invoke-JsonRequest -Uri $Uri -Method $Method -Body $Body
        } catch {
            $lastError = $_
            if ($i -lt ($Attempts - 1)) {
                Start-Sleep -Seconds $DelaySeconds
            }
        }
    }

    throw $lastError
}

$checks = [ordered]@{}
$details = [ordered]@{}

try {
    $liveness = Invoke-JsonRequest -Uri "$CdcBaseUrl/actuator/health/liveness"
    $checks.cdcLivenessUp = ($liveness.status -eq "UP")
    $details.cdcLivenessStatus = $liveness.status

    $readiness = Invoke-JsonRequest -Uri "$CdcBaseUrl/actuator/health/readiness"
    $checks.cdcReadinessUp = ($readiness.status -eq "UP")
    $details.cdcReadinessStatus = $readiness.status

    $statusBefore = Invoke-JsonRequest -Uri "$CdcBaseUrl/api/internal/cdc/status"
    $checks.cdcStatusEndpointReachable = ($null -ne $statusBefore.paused)
    $details.cdcStatusBefore = $statusBefore

    $pauseResult = Invoke-JsonRequest -Uri "$CdcBaseUrl/api/internal/cdc/pause" -Method "POST"
    $checks.cdcPauseWorks = ($pauseResult.paused -eq $true)

    $resumeResult = Invoke-JsonRequest -Uri "$CdcBaseUrl/api/internal/cdc/resume" -Method "POST"
    $checks.cdcResumeWorks = ($resumeResult.paused -eq $false)
    $details.cdcStatusAfterResume = $resumeResult

    $lagMetric = Invoke-JsonRequest -Uri "$CdcBaseUrl/actuator/metrics/moodride.cdc.consumer.lag"
    $checks.cdcLagMetricExposed = ($null -ne $lagMetric.name -and $lagMetric.name -eq "moodride.cdc.consumer.lag")
    $details.cdcLagMetric = $lagMetric

    $prometheusRaw = Invoke-WebRequest -Uri "$CdcBaseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10 | Select-Object -ExpandProperty Content
    $checks.cdcPrometheusMetricNamesPresent = ($prometheusRaw -match "moodride_cdc_consumer_lag")

    $connectors = Invoke-JsonRequestWithRetry -Uri "$DebeziumBaseUrl/connectors"
    $connectorNames = @($connectors)
    $checks.debeziumConnectReachable = $true
    $checks.connectorRegistered = $connectorNames -contains $ConnectorName
    $details.registeredConnectors = $connectorNames

    if ($checks.connectorRegistered) {
        $connectorStatus = Invoke-JsonRequestWithRetry -Uri "$DebeziumBaseUrl/connectors/$ConnectorName/status"
        $checks.connectorRunning = (
            $connectorStatus.connector.state -eq "RUNNING" -and
            $connectorStatus.tasks.Count -gt 0 -and
            ($connectorStatus.tasks | Where-Object { $_.state -ne "RUNNING" }).Count -eq 0
        )
        $details.connectorStatus = $connectorStatus
    } else {
        $checks.connectorRunning = $false
    }

    $checksPassed = -not ($checks.Values -contains $false)

    $result = [PSCustomObject]@{
        checkedAt = (Get-Date).ToString("o")
        passed = $checksPassed
        checks = $checks
        details = $details
    }

    if ($EmitJson) {
        $result | ConvertTo-Json -Depth 10
    } else {
        $result | Format-List
    }

    if (-not $checksPassed) {
        throw "CDC pipeline verification failed."
    }
} finally {
    try {
        Invoke-JsonRequest -Uri "$CdcBaseUrl/api/internal/cdc/resume" -Method "POST" | Out-Null
    } catch {
        # Ignore resume cleanup errors during verification teardown.
    }
}
