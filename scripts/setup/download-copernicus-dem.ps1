param(
    [string]$OutputDir = "C:\Users\aadeb\OneDrive\Desktop\Wayward\data\elevation\dem_tiles",
    [string]$RegionName,
    [int]$MinLat = 41,
    [int]$MaxLat = 83,
    [int]$MinLon = -141,
    [int]$MaxLon = -52,
    [string]$BaseUrl = "https://copernicus-dem-30m.s3.amazonaws.com",
    [string]$TileListPath,
    [switch]$Overwrite,
    [switch]$UseBits
)

$ErrorActionPreference = "Stop"

$effectiveOutputDir = $OutputDir
if ($RegionName) {
    $effectiveOutputDir = Join-Path -Path $OutputDir -ChildPath $RegionName
}

function Get-TileName {
    param(
        [int]$Lat,
        [int]$Lon
    )

    $latPrefix = if ($Lat -ge 0) { "N" } else { "S" }
    $lonPrefix = if ($Lon -ge 0) { "E" } else { "W" }
    $latValue = [Math]::Abs($Lat).ToString("00")
    $lonValue = [Math]::Abs($Lon).ToString("000")

    return "Copernicus_DSM_COG_10_{0}{1}_00_{2}{3}_00_DEM" -f $latPrefix, $latValue, $lonPrefix, $lonValue
}

function Get-TileUrlsFromBounds {
    param(
        [int]$MinLat,
        [int]$MaxLat,
        [int]$MinLon,
        [int]$MaxLon,
        [string]$BaseUrl
    )

    $urls = New-Object System.Collections.Generic.List[string]
    for ($lat = $MinLat; $lat -le $MaxLat; $lat++) {
        for ($lon = $MinLon; $lon -le $MaxLon; $lon++) {
            $tile = Get-TileName -Lat $lat -Lon $lon
            $urls.Add("$BaseUrl/$tile/$tile.tif")
        }
    }

    return $urls
}

function Get-UrlsFromList {
    param([string]$Path)

    $lines = Get-Content -Path $Path -ErrorAction Stop
    $urls = $lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch '^#' }
    return ,$urls
}

if (-not (Test-Path -Path $effectiveOutputDir)) {
    New-Item -ItemType Directory -Path $effectiveOutputDir | Out-Null
}

$urls = @()
if ($TileListPath) {
    if (-not (Test-Path -Path $TileListPath)) {
        throw "Tile list not found: $TileListPath"
    }
    $urls = Get-UrlsFromList -Path $TileListPath
} else {
    $urls = Get-TileUrlsFromBounds -MinLat $MinLat -MaxLat $MaxLat -MinLon $MinLon -MaxLon $MaxLon -BaseUrl $BaseUrl
}

Write-Host "Preparing to download $($urls.Count) tiles into $effectiveOutputDir ..."

foreach ($url in $urls) {
    $fileName = Split-Path -Path $url -Leaf
    $destPath = Join-Path $effectiveOutputDir $fileName

    if ((-not $Overwrite) -and (Test-Path -Path $destPath)) {
        continue
    }

    try {
        if ($UseBits) {
            Start-BitsTransfer -Source $url -Destination $destPath -ErrorAction Stop
        } else {
            Invoke-WebRequest -Uri $url -OutFile $destPath -UseBasicParsing
        }
        Write-Host "Downloaded $fileName"
    } catch {
        Write-Warning "Failed to download ${url}: $($_.Exception.Message)"
    }
}

Write-Host "Download pass complete."
