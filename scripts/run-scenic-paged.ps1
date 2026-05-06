param(
    [string]$BaseUrl = "http://localhost:8086/api/ingestion",
    [int]$PageSize = 25,
    [int]$MaxPages = 200,
    [bool]$OnlyUnscored = $true,
    [string]$StartAfterH3 = "",
    [int]$HealthTimeoutSeconds = 120
)

$terminalStates = @("COMPLETED", "FAILED", "STOPPED", "ABANDONED", "UNKNOWN")
$healthUrl = "$BaseUrl/health"
$serviceReady = $false
$healthDeadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)

Write-Host "Waiting for ingestion-service health at $healthUrl ..."
do {
    try {
        $health = Invoke-RestMethod -Method Get -Uri $healthUrl -TimeoutSec 5
        if ($health.status -eq "UP") {
            $serviceReady = $true
            break
        }
    } catch {
        # service not reachable yet
    }
    Start-Sleep -Seconds 2
} until ((Get-Date) -ge $healthDeadline)

if (-not $serviceReady) {
    Write-Host "ingestion-service is not reachable at $healthUrl"
    Write-Host "Start it in another shell, then rerun this script."
    Write-Host ""
    Write-Host "Suggested start command:"
    Write-Host '$env:JAVA_TOOL_OPTIONS=''-Xms256m -Xmx3072m -XX:MaxMetaspaceSize=384m -Djava.awt.headless=true'''
    Write-Host "mvn -f 'C:/Users/aadeb/OneDrive/Desktop/MoodRide/services/ingestion-service/pom.xml' spring-boot:run"
    exit 1
}

Write-Host "ingestion-service is UP. Starting paged scenic runs."

for ($page = 1; $page -le $MaxPages; $page++) {
    $payload = @{
        maxTiles = $PageSize
        onlyUnscored = $OnlyUnscored
    }
    if (-not [string]::IsNullOrWhiteSpace($StartAfterH3)) {
        $payload.startAfterH3 = $StartAfterH3
    }

    $body = $payload | ConvertTo-Json -Depth 4
    try {
        $launch = Invoke-RestMethod -Method Post -Uri "$BaseUrl/jobs/scenic-score" -ContentType "application/json" -Body $body
    } catch {
        Write-Host "Failed to launch scenic page $page."
        Write-Host $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            Write-Host $_.ErrorDetails.Message
        }
        exit 1
    }

    if ($launch.status -eq "NOOP") {
        Write-Host "No more matching H3 tiles. Stopping."
        $launch | ConvertTo-Json -Depth 6
        break
    }

    if (-not $launch.jobId) {
        throw "Missing jobId in launch response. Response: $($launch | ConvertTo-Json -Depth 6)"
    }

    $jobId = [int64]$launch.jobId
    Write-Host "Page $page launched: jobId=$jobId, targetCount=$($launch.targetH3Count), first=$($launch.targetFirstH3), last=$($launch.targetLastH3)"

    do {
        Start-Sleep -Seconds 5
        try {
            $status = Invoke-RestMethod -Method Get -Uri "$BaseUrl/jobs/scenic-score/$jobId"
        } catch {
            Write-Host "Failed to fetch status for jobId=$jobId"
            Write-Host $_.Exception.Message
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                Write-Host $_.ErrorDetails.Message
            }
            exit 1
        }
        $readCount = $null
        $writeCount = $null
        $rollbackCount = $null
        $jobStatus = [string]$status.status
        if ([string]::IsNullOrWhiteSpace($jobStatus) -and $status.summary) {
            if ($status.summary -match 'status=([^,;]+)') {
                $jobStatus = $Matches[1]
            }
        }
        if ($status.stepStats -and $status.stepStats.Count -gt 0) {
            $readCount = $status.stepStats[0].readCount
            $writeCount = $status.stepStats[0].writeCount
            $rollbackCount = $status.stepStats[0].rollbackCount
        }
        Write-Host "jobId=$jobId status=$jobStatus read=$readCount write=$writeCount rollback=$rollbackCount"
        $status.status = $jobStatus
    } until ($terminalStates -contains [string]$status.status)

    if ($status.status -ne "COMPLETED") {
        Write-Host "Job ended with non-completed status: $($status.status)"
        $status | ConvertTo-Json -Depth 8
        break
    }

    $StartAfterH3 = [string]$launch.targetLastH3
    if ([string]::IsNullOrWhiteSpace($StartAfterH3)) {
        Write-Host "No targetLastH3 returned. Stopping."
        break
    }
}
