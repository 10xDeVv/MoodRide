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
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Path $MyInvocation.MyCommand.Path -Parent
$importNaturalEarthScript = Join-Path $scriptRoot "import-natural-earth-water.ps1"
$importNlcdScript = Join-Path $scriptRoot "import-nlcd.ps1"
$importTrafficSignalsScript = Join-Path $scriptRoot "import-traffic-signals.ps1"
$validateOpenTopoProfilesScript = Join-Path $scriptRoot "validate-opentopo-profiles.ps1"

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

if (-not (Test-Path -Path $importNaturalEarthScript -PathType Leaf)) {
    throw "Required script missing: $importNaturalEarthScript"
}
if (-not (Test-Path -Path $importNlcdScript -PathType Leaf)) {
    throw "Required script missing: $importNlcdScript"
}
if (-not (Test-Path -Path $importTrafficSignalsScript -PathType Leaf)) {
    throw "Required script missing: $importTrafficSignalsScript"
}
if (-not (Test-Path -Path $validateOpenTopoProfilesScript -PathType Leaf)) {
    throw "Required script missing: $validateOpenTopoProfilesScript"
}

# Resolve/download Natural Earth source when requested.
if (-not $NaturalEarthInputPath -and $DownloadNaturalEarth) {
    $zipPath = Join-Path $NaturalEarthWorkingDir "ne_water.zip"
    $extractDir = Join-Path $NaturalEarthWorkingDir "extracted"

    Invoke-Or-Preview `
        -Description "Prepare Natural Earth working directory" `
        -CommandText ('New-Item -ItemType Directory -Path "{0}" -Force' -f $NaturalEarthWorkingDir) `
        -Action {
            New-Item -ItemType Directory -Path $NaturalEarthWorkingDir -Force | Out-Null
        }

    Invoke-Or-Preview `
        -Description "Download Natural Earth water dataset" `
        -CommandText ('Invoke-WebRequest -Uri "{0}" -OutFile "{1}"' -f $NaturalEarthUrl, $zipPath) `
        -Action {
            Invoke-WebRequest -Uri $NaturalEarthUrl -OutFile $zipPath
        }

    Invoke-Or-Preview `
        -Description "Extract Natural Earth archive" `
        -CommandText ('Expand-Archive -Path "{0}" -DestinationPath "{1}" -Force' -f $zipPath, $extractDir) `
        -Action {
            Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
        }

    if (-not $DryRun) {
        $candidate = Get-ChildItem -Path $extractDir -Recurse -File |
            Where-Object { $_.Extension -in @(".gpkg", ".shp", ".geojson") } |
            Select-Object -First 1

        if (-not $candidate) {
            throw "No importable vector file found in $extractDir"
        }

        $NaturalEarthInputPath = $candidate.FullName
        Write-Host "Detected Natural Earth source: $NaturalEarthInputPath"
    } else {
        $NaturalEarthInputPath = Join-Path $extractDir "<auto-detected-natural-earth-file>"
    }
}

if (-not $NlcdInputPath -and $DownloadNlcd) {
    if ([string]::IsNullOrWhiteSpace($NlcdUrl)) {
        throw "-DownloadNlcd requires -NlcdUrl that points to a vector-ready NLCD archive/file (.zip/.gpkg/.shp/.geojson)."
    }

    $nlcdArchivePath = Join-Path $NlcdWorkingDir "nlcd_source.zip"
    $nlcdExtractDir = Join-Path (Join-Path $NlcdWorkingDir "extracted") (Get-Date -Format "yyyyMMddHHmmss")

    Invoke-Or-Preview `
        -Description "Prepare NLCD working directory" `
        -CommandText ('New-Item -ItemType Directory -Path "{0}" -Force' -f $NlcdWorkingDir) `
        -Action {
            New-Item -ItemType Directory -Path $NlcdWorkingDir -Force | Out-Null
        }

    Invoke-Or-Preview `
        -Description "Download NLCD source dataset" `
        -CommandText ('Invoke-WebRequest -Uri "{0}" -OutFile "{1}"' -f $NlcdUrl, $nlcdArchivePath) `
        -Action {
            Invoke-WebRequest -Uri $NlcdUrl -OutFile $nlcdArchivePath
        }

    Invoke-Or-Preview `
        -Description "Extract NLCD archive" `
        -CommandText ('Expand-Archive -Path "{0}" -DestinationPath "{1}" -Force' -f $nlcdArchivePath, $nlcdExtractDir) `
        -Action {
            Expand-Archive -Path $nlcdArchivePath -DestinationPath $nlcdExtractDir -Force
        }

    if (-not $DryRun) {
        $nlcdCandidate = Get-ChildItem -Path $nlcdExtractDir -Recurse -File |
            Where-Object { $_.Extension.ToLowerInvariant() -in @(".gpkg", ".shp", ".geojson", ".tif", ".tiff") } |
            Select-Object -First 1

        if (-not $nlcdCandidate) {
            throw "No importable NLCD file found in $nlcdExtractDir (supported: .gpkg, .shp, .geojson, .tif, .tiff)"
        }

        $NlcdInputPath = $nlcdCandidate.FullName
        Write-Host "Detected NLCD source: $NlcdInputPath"
    } else {
        $NlcdInputPath = Join-Path $nlcdExtractDir "<auto-detected-nlcd-file>"
    }
}

if ($NlcdInputPath -and ([System.IO.Path]::GetExtension($NlcdInputPath).ToLowerInvariant() -eq ".zip")) {
    if (-not $DryRun) {
        Assert-FileExists -Path $NlcdInputPath -Label "NLCD archive"
    }

    $nlcdExtractDir = Join-Path (Join-Path $NlcdWorkingDir "extracted") (Get-Date -Format "yyyyMMddHHmmss")

    Invoke-Or-Preview `
        -Description "Prepare NLCD extraction directory" `
        -CommandText ('New-Item -ItemType Directory -Path "{0}" -Force' -f $NlcdWorkingDir) `
        -Action {
            New-Item -ItemType Directory -Path $NlcdWorkingDir -Force | Out-Null
        }

    Invoke-Or-Preview `
        -Description "Extract local NLCD archive" `
        -CommandText ('Expand-Archive -Path "{0}" -DestinationPath "{1}" -Force' -f $NlcdInputPath, $nlcdExtractDir) `
        -Action {
            Expand-Archive -Path $NlcdInputPath -DestinationPath $nlcdExtractDir -Force
        }

    if (-not $DryRun) {
        $nlcdCandidate = Get-ChildItem -Path $nlcdExtractDir -Recurse -File |
            Where-Object { $_.Extension.ToLowerInvariant() -in @(".gpkg", ".shp", ".geojson", ".tif", ".tiff") } |
            Select-Object -First 1

        if (-not $nlcdCandidate) {
            throw "No importable NLCD file found in extracted archive at $nlcdExtractDir"
        }

        $NlcdInputPath = $nlcdCandidate.FullName
        Write-Host "Detected NLCD source from local archive: $NlcdInputPath"
    } else {
        $NlcdInputPath = Join-Path $nlcdExtractDir "<auto-detected-nlcd-file>"
    }
}

if ($NaturalEarthInputPath) {
    if (-not $DryRun) {
        Assert-FileExists -Path $NaturalEarthInputPath -Label "Natural Earth"
    }

    $neCmd = '& "{0}" -InputPath "{1}" -Database "{2}" -Username "{3}" -DbHost "{4}" -Port {5}' -f `
        $importNaturalEarthScript, $NaturalEarthInputPath, $Database, $Username, $DbHost, $Port
    if ($Password) { $neCmd += " -Password ******" }

    Invoke-Or-Preview `
        -Description "Import Natural Earth water polygons" `
        -CommandText $neCmd `
        -Action {
            $args = @{
                InputPath = $NaturalEarthInputPath
                Database = $Database
                Username = $Username
                DbHost = $DbHost
                Port = $Port
            }
            if ($Password) { $args.Password = $Password }
            & $importNaturalEarthScript @args
        }
} else {
    Write-Host "Skipping Natural Earth import (no input path and download not requested)." -ForegroundColor DarkYellow
}

if ($NlcdInputPath) {
    if (-not $DryRun) {
        Assert-FileExists -Path $NlcdInputPath -Label "NLCD"
    }

    $nlcdCmd = '& "{0}" -InputPath "{1}" -Database "{2}" -Username "{3}" -DbHost "{4}" -Port {5}' -f `
        $importNlcdScript, $NlcdInputPath, $Database, $Username, $DbHost, $Port
    if ($Password) { $nlcdCmd += " -Password ******" }

    Invoke-Or-Preview `
        -Description "Import NLCD land cover dataset" `
        -CommandText $nlcdCmd `
        -Action {
            $args = @{
                InputPath = $NlcdInputPath
                Database = $Database
                Username = $Username
                DbHost = $DbHost
                Port = $Port
            }
            if ($Password) { $args.Password = $Password }
            & $importNlcdScript @args
        }
} else {
    Write-Host "Skipping NLCD import (no input path provided)." -ForegroundColor DarkYellow
}

if ($TrafficSignalsCsvPath) {
    if (-not $DryRun) {
        Assert-FileExists -Path $TrafficSignalsCsvPath -Label "Traffic signal CSV"
    }

    $trafficImportCmd = '& "{0}" -CsvPath "{1}" -Provider "{2}" -Database "{3}" -Username "{4}" -DbHost "{5}" -Port {6} -ScenicApiBaseUrl "{7}/api/internal/scenic"' -f `
        $importTrafficSignalsScript, $TrafficSignalsCsvPath, $TrafficProvider, $Database, $Username, $DbHost, $Port, $ScenicApiBaseUrl
    if ($Password) { $trafficImportCmd += " -Password ******" }

    Invoke-Or-Preview `
        -Description "Import provider-fed traffic_tile_signals from CSV" `
        -CommandText $trafficImportCmd `
        -Action {
            $args = @{
                CsvPath = $TrafficSignalsCsvPath
                Provider = $TrafficProvider
                Database = $Database
                Username = $Username
                DbHost = $DbHost
                Port = $Port
                ScenicApiBaseUrl = "$ScenicApiBaseUrl/api/internal/scenic"
            }
            if ($Password) { $args.Password = $Password }
            & $importTrafficSignalsScript @args
        }
} elseif (-not $SkipTrafficSeed) {
    $trafficUrl = "$IngestionApiBaseUrl/api/ingestion/jobs/traffic-seed"
    Invoke-Or-Preview `
        -Description "Seed traffic_tile_signals via ingestion API" `
        -CommandText ('Invoke-RestMethod -Method POST -Uri "{0}"' -f $trafficUrl) `
        -Action {
            Invoke-RestMethod -Method POST -Uri $trafficUrl | Out-Null
        }
} else {
    Write-Host "Skipping traffic seed endpoint by request." -ForegroundColor DarkYellow
}

if (-not $SkipOpenTopoProfileValidation) {
    Invoke-Or-Preview `
        -Description "Validate OpenTopo configuration across target profiles (local/docker/prod)" `
        -CommandText ('& "{0}" -EmitJson' -f $validateOpenTopoProfilesScript) `
        -Action {
            & $validateOpenTopoProfilesScript -EmitJson
        }
} else {
    Write-Host "Skipping OpenTopo profile validation by request." -ForegroundColor DarkYellow
}

$statusUrl = "$IngestionApiBaseUrl/api/ingestion/phase2/status"
Invoke-Or-Preview `
    -Description "Fetch Phase 2 readiness snapshot" `
    -CommandText ('Invoke-RestMethod -Uri "{0}"' -f $statusUrl) `
    -Action {
        $status = Invoke-RestMethod -Uri $statusUrl
        $status | ConvertTo-Json -Depth 5
    }

Write-Host "`nPhase 2 data-load orchestration complete."

