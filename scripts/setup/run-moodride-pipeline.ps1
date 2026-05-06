param(
    [string]$NaturalEarthInputPath,
    [string]$NlcdInputPath,

    [switch]$DownloadNaturalEarth,
    [string]$NaturalEarthUrl = "https://naciscdn.org/naturalearth/10m/physical/ne_10m_lakes.zip",
    [string]$NaturalEarthWorkingDir = "C:\Users\aadeb\OneDrive\Desktop\MoodRide\data\natural-earth",

    [switch]$DownloadNlcd,
    [string]$NlcdUrl,
    [string]$NlcdWorkingDir = "C:\Users\aadeb\OneDrive\Desktop\MoodRide\data\nlcd",

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,

    [string]$IngestionApiBaseUrl = "http://localhost:8086",
    [string]$ScenicApiBaseUrl = "http://localhost:8085",
    [string]$TrafficSignalsCsvPath,
    [string]$TrafficProvider = "external-csv",
    [switch]$SkipTrafficSeed,
    [switch]$SkipOpenTopoProfileValidation,
    [int]$Phase1TimeoutSeconds = 1800,
    [int]$Phase2TimeoutSeconds = 3600,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Path $MyInvocation.MyCommand.Path -Parent
$supportingDataScript = Join-Path $scriptRoot "phase2-data-load.ps1"

function Invoke-Or-Preview {
    param(
        [string]$Description,
        [string]$CommandText,
        [scriptblock]$Action
    )

    Write-Host "`n==> $Description"
    if ($DryRun) {
        Write-Host "[dry-run] $CommandText" -ForegroundColor Yellow
        return
    }

    & $Action
}

function Assert-FileExists {
    param([string]$Path, [string]$Label)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -Path $Path -PathType Leaf)) {
        throw "$Label file not found: $Path"
    }
}

function Wait-ForReadiness {
    param(
        [string]$Url,
        [string]$Description,
        [string]$ReadyProperty,
        [string]$CountProperty,
        [int]$TimeoutSeconds,
        [int]$DelaySeconds = 10
    )

    if ($DryRun) {
        Write-Host "[dry-run] would wait for $Description at $Url"
        return $null
    }

    Write-Host "Waiting for $Description..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 15
            $ready = $response.$ReadyProperty
            if ($ready -eq $true) {
                $count = if ($CountProperty -and $response.PSObject.Properties.Name -contains $CountProperty) { $response.$CountProperty } else { $null }
                if ($null -ne $count) {
                    Write-Host "[ok] $Description is ready ($CountProperty=$count)"
                } else {
                    Write-Host "[ok] $Description is ready"
                }
                return $response
            }

            $countText = if ($CountProperty -and $response.PSObject.Properties.Name -contains $CountProperty) { " $CountProperty=$($response.$CountProperty)" } else { "" }
            Write-Host "  not ready yet: $ReadyProperty=$ready$countText"
        } catch {
            Write-Host "  waiting: $($_.Exception.Message)"
        }

        Start-Sleep -Seconds $DelaySeconds
    }

    throw "Timed out waiting for $Description after $TimeoutSeconds seconds."
}

function Invoke-JsonPost {
    param(
        [string]$Url,
        [string]$Description
    )

    Invoke-Or-Preview `
        -Description $Description `
        -CommandText ('Invoke-RestMethod -Method POST -Uri "{0}"' -f $Url) `
        -Action {
            Invoke-RestMethod -Method POST -Uri $Url | Out-Null
        }
}

if (-not (Test-Path -Path $supportingDataScript -PathType Leaf)) {
    throw "Required script missing: $supportingDataScript"
}

if ($NaturalEarthInputPath) {
    Assert-FileExists -Path $NaturalEarthInputPath -Label "Natural Earth"
}
if ($NlcdInputPath) {
    Assert-FileExists -Path $NlcdInputPath -Label "NLCD"
}
if ($TrafficSignalsCsvPath) {
    Assert-FileExists -Path $TrafficSignalsCsvPath -Label "Traffic signal CSV"
}

$supportingArgs = @{
    NaturalEarthInputPath = $NaturalEarthInputPath
    NlcdInputPath = $NlcdInputPath
    DownloadNaturalEarth = $DownloadNaturalEarth
    NaturalEarthUrl = $NaturalEarthUrl
    NaturalEarthWorkingDir = $NaturalEarthWorkingDir
    DownloadNlcd = $DownloadNlcd
    NlcdUrl = $NlcdUrl
    NlcdWorkingDir = $NlcdWorkingDir
    Database = $Database
    Username = $Username
    DbHost = $DbHost
    Port = $Port
    Password = $Password
    IngestionApiBaseUrl = $IngestionApiBaseUrl
    ScenicApiBaseUrl = $ScenicApiBaseUrl
    TrafficSignalsCsvPath = $TrafficSignalsCsvPath
    TrafficProvider = $TrafficProvider
    SkipTrafficSeed = $true
    SkipOpenTopoProfileValidation = $SkipOpenTopoProfileValidation
    DryRun = $DryRun
}

Invoke-Or-Preview `
    -Description "Run supporting data load" `
    -CommandText ('& "{0}" -SkipTrafficSeed' -f $supportingDataScript) `
    -Action {
        & $supportingDataScript @supportingArgs
    }

$phase1TriggerUrl = "$IngestionApiBaseUrl/api/ingestion/jobs/osm-ingest"
$phase1StatusUrl = "$IngestionApiBaseUrl/api/ingestion/phase1/status"
$phase2TriggerUrl = "$IngestionApiBaseUrl/api/ingestion/jobs/scenic-score"
$phase2StatusUrl = "$IngestionApiBaseUrl/api/ingestion/phase2/status"
$trafficSeedUrl = "$IngestionApiBaseUrl/api/ingestion/jobs/traffic-seed"

Invoke-JsonPost -Url $phase1TriggerUrl -Description "Trigger OSM ingestion batch"
Wait-ForReadiness -Url $phase1StatusUrl -Description "Phase 1 road-segment readiness" -ReadyProperty "road_segments_ready" -CountProperty "road_segments_rows" -TimeoutSeconds $Phase1TimeoutSeconds

if (-not $TrafficSignalsCsvPath -and -not $SkipTrafficSeed) {
    Invoke-JsonPost -Url $trafficSeedUrl -Description "Seed traffic signals from the freshly ingested road network"
}

Invoke-JsonPost -Url $phase2TriggerUrl -Description "Trigger scenic scoring batch"
Wait-ForReadiness -Url $phase2StatusUrl -Description "Phase 2 scenic tile readiness" -ReadyProperty "scenic_score_tiles_ready" -CountProperty "scenic_score_tiles_rows" -TimeoutSeconds $Phase2TimeoutSeconds

Invoke-Or-Preview `
    -Description "Fetch final pipeline readiness snapshot" `
    -CommandText ('Invoke-RestMethod -Uri "{0}"' -f $phase2StatusUrl) `
    -Action {
        $status = Invoke-RestMethod -Uri $phase2StatusUrl
        $status | ConvertTo-Json -Depth 5
    }

Write-Host "`nMoodRide ingestion pipeline complete."
