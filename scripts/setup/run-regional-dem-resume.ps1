param(
    [string]$BaseDir = "D:\MoodRide\data\elevation\dem_tiles",
    [string]$StateFilePath,
    [string]$TargetTable = "elevation_raster",
    [string]$RegionName,
    [ValidateSet("PerTile", "Vrt")]
    [string]$ImportMode = "PerTile",
    [string]$DockerImportMemoryLimit = "1500m",
    [switch]$SkipDownload,
    [switch]$SkipVrt,
    [switch]$SkipImport,
    [switch]$UseBits = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$downloadScript = Join-Path $repoRoot "scripts\setup\download-copernicus-dem.ps1"
$importScript = Join-Path $repoRoot "scripts\setup\import-raster-to-postgis.ps1"

if (-not (Test-Path $downloadScript)) { throw "Missing script: $downloadScript" }
if (-not (Test-Path $importScript)) { throw "Missing script: $importScript" }

if (-not $StateFilePath) {
    $StateFilePath = Join-Path $BaseDir "regional-dem-state.json"
}

$regions = @(
    @{ name = "west";     minLat = 41; maxLat = 83; minLon = -141; maxLon = -115 },
    @{ name = "prairies"; minLat = 41; maxLat = 83; minLon = -114; maxLon = -96  },
    @{ name = "central";  minLat = 41; maxLat = 83; minLon = -95;  maxLon = -64  },
    @{ name = "atlantic"; minLat = 41; maxLat = 83; minLon = -63;  maxLon = -52  }
)

if (-not (Test-Path $BaseDir)) {
    New-Item -ItemType Directory -Path $BaseDir | Out-Null
}

function New-State {
    $regionState = @{}
    foreach ($r in $regions) {
        $regionState[$r.name] = @{
            downloaded = $false
            vrtBuilt = $false
            imported = $false
            tifCount = 0
            importedTileCount = 0
            updatedAt = $null
        }
    }

    return @{
        baseDir = $BaseDir
        targetTable = $TargetTable
        createdAt = (Get-Date).ToString("o")
        regions = $regionState
    }
}

function ConvertTo-HashtableRecursive {
    param([Parameter(Mandatory = $true)]$InputObject)

    if ($null -eq $InputObject) {
        return $null
    }

    if ($InputObject -is [System.Collections.IDictionary]) {
        $dict = @{}
        foreach ($k in $InputObject.Keys) {
            $dict[$k] = ConvertTo-HashtableRecursive -InputObject $InputObject[$k]
        }
        return $dict
    }

    if ($InputObject -is [System.Collections.IEnumerable] -and -not ($InputObject -is [string])) {
        $list = @()
        foreach ($item in $InputObject) {
            $list += ,(ConvertTo-HashtableRecursive -InputObject $item)
        }
        return $list
    }

    if ($InputObject -is [pscustomobject]) {
        $hash = @{}
        foreach ($p in $InputObject.PSObject.Properties) {
            $hash[$p.Name] = ConvertTo-HashtableRecursive -InputObject $p.Value
        }
        return $hash
    }

    return $InputObject
}

function Load-State {
    if (-not (Test-Path $StateFilePath)) {
        return (New-State)
    }
    $raw = Get-Content -Raw $StateFilePath
    $parsed = $raw | ConvertFrom-Json
    return (ConvertTo-HashtableRecursive -InputObject $parsed)
}

function Save-State($state) {
    $state | ConvertTo-Json -Depth 10 | Set-Content -Path $StateFilePath -Encoding UTF8
}

function Update-RegionState {
    param(
        [hashtable]$state,
        [hashtable]$region
    )

    $regionDir = Join-Path $BaseDir $region.name
    $vrtPath = Join-Path $regionDir "elevation_merged.vrt"
    $markerDir = Join-Path $regionDir ".imported"
    $tifCount = if (Test-Path $regionDir) { @(Get-ChildItem -Path $regionDir -Filter *.tif -File -ErrorAction SilentlyContinue).Count } else { 0 }
    $importedTileCount = if (Test-Path $markerDir) { @(Get-ChildItem -Path $markerDir -Filter *.done -File -ErrorAction SilentlyContinue).Count } else { 0 }

    $state.regions[$region.name].tifCount = $tifCount
    $state.regions[$region.name].importedTileCount = $importedTileCount
    if ($tifCount -gt 0) { $state.regions[$region.name].downloaded = $true }
    if (Test-Path $vrtPath) { $state.regions[$region.name].vrtBuilt = $true }
    if ($tifCount -gt 0 -and $importedTileCount -ge $tifCount) { $state.regions[$region.name].imported = $true }
    $state.regions[$region.name].updatedAt = (Get-Date).ToString("o")
}

$state = Load-State

# Seed west as imported when we already have west files + populated target table.
if (-not $state.regions["west"].imported) {
    $westDir = Join-Path $BaseDir "west"
    $westVrt = Join-Path $westDir "elevation_merged.vrt"
    if ((Test-Path $westVrt) -and (Test-Path $westDir)) {
        try {
            $countRaw = docker exec moodride-postgres psql -U postgres -d moodride -t -A -c "SELECT COUNT(*) FROM $TargetTable;" 2>$null
            if ($LASTEXITCODE -eq 0) {
                $count = [int]($countRaw.Trim())
                if ($count -gt 0) {
                    $state.regions["west"].imported = $true
                }
            }
        } catch {
            # Ignore detection failures; runner still works without seeding.
        }
    }
}

foreach ($r in $regions) {
    Update-RegionState -state $state -region $r
}
Save-State $state

$selectedRegions = $regions
if ($RegionName) {
    $selectedRegions = $regions | Where-Object { $_.name -eq $RegionName }
    if (-not $selectedRegions) {
        throw "Unknown region '$RegionName'. Valid values: $($regions.name -join ', ')"
    }
}

foreach ($region in $selectedRegions) {
    Write-Host "`n=== Region: $($region.name) ==="
    $regionDir = Join-Path $BaseDir $region.name
    $vrtPath = Join-Path $regionDir "elevation_merged.vrt"
    $markerDir = Join-Path $regionDir ".imported"

    if (-not $SkipDownload -and -not $state.regions[$region.name].downloaded) {
        Write-Host "Downloading missing DEM tiles..."
        $downloadArgs = @{
            OutputDir = $BaseDir
            RegionName = $region.name
            MinLat = $region.minLat
            MaxLat = $region.maxLat
            MinLon = $region.minLon
            MaxLon = $region.maxLon
        }
        if ($UseBits) { $downloadArgs.UseBits = $true }
        & $downloadScript @downloadArgs
        Update-RegionState -state $state -region $region
        Save-State $state
    } else {
        Write-Host "Download step already complete (or skipped)."
    }

    if ($ImportMode -eq "Vrt" -and -not $SkipVrt -and -not $state.regions[$region.name].vrtBuilt) {
        Write-Host "Building VRT mosaic..."
        if (-not (Test-Path $regionDir)) { throw "Region directory not found: $regionDir" }
        docker run --rm -v "${regionDir}:/data" ghcr.io/osgeo/gdal:ubuntu-small-latest sh -lc "gdalbuildvrt /data/elevation_merged.vrt /data/*.tif"
        if ($LASTEXITCODE -ne 0) { throw "gdalbuildvrt failed for region '$($region.name)'" }
        Update-RegionState -state $state -region $region
        Save-State $state
    } elseif ($ImportMode -eq "PerTile") {
        Write-Host "VRT step skipped (ImportMode=PerTile)."
    } else {
        Write-Host "VRT step already complete (or skipped)."
    }

    if (-not $SkipImport -and -not $state.regions[$region.name].imported) {
        if ($ImportMode -eq "Vrt") {
            Write-Host "Importing VRT into PostGIS..."
            if (-not (Test-Path $vrtPath)) { throw "Missing VRT: $vrtPath" }
            $importArgs = @{
                InputPath = $vrtPath
                TargetTable = $TargetTable
                Append = $true
                DockerMemoryLimit = $DockerImportMemoryLimit
            }
            & $importScript @importArgs
            $state.regions[$region.name].imported = $true
            $state.regions[$region.name].updatedAt = (Get-Date).ToString("o")
            Save-State $state
        } else {
            Write-Host "Importing DEM tiles individually into PostGIS (low-memory resumable mode)..."
            if (-not (Test-Path $regionDir)) { throw "Region directory not found: $regionDir" }
            if (-not (Test-Path $markerDir)) { New-Item -ItemType Directory -Path $markerDir | Out-Null }

            $tifs = @(Get-ChildItem -Path $regionDir -Filter *.tif -File -ErrorAction SilentlyContinue | Sort-Object Name)
            if (-not $tifs -or $tifs.Count -eq 0) { throw "No .tif files found in $regionDir" }

            $index = 0
            foreach ($tif in $tifs) {
                $index++
                $marker = Join-Path $markerDir ($tif.Name + ".done")
                if (Test-Path $marker) {
                    continue
                }

                Write-Host ("[{0}/{1}] Importing {2}" -f $index, $tifs.Count, $tif.Name)
                $importArgs = @{
                    InputPath = $tif.FullName
                    TargetTable = $TargetTable
                    Append = $true
                    DockerMemoryLimit = $DockerImportMemoryLimit
                    SkipVerify = $true
                }
                & $importScript @importArgs
                New-Item -ItemType File -Path $marker | Out-Null

                if (($index % 20) -eq 0) {
                    Update-RegionState -state $state -region $region
                    Save-State $state
                }
            }

            Update-RegionState -state $state -region $region
            $state.regions[$region.name].imported = $true
            $state.regions[$region.name].updatedAt = (Get-Date).ToString("o")
            Save-State $state
        }
    } else {
        Write-Host "Import step already complete (or skipped)."
    }
}

Write-Host "`nRegional DEM resume runner complete."
Write-Host "State file: $StateFilePath"
