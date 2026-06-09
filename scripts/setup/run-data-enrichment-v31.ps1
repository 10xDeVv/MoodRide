param(
    [Parameter(Mandatory = $false)]
    [string]$LightPollutionInputPath,

    [string]$SourceScoringVersion = "3.0-overture-lightpollution-enrichment",
    [string]$ScoringVersion = "3.1-darkness-urban-penalty-calibration",
    [int]$ChunkSize = 50000,
    [double]$LightPollutionReferenceMax = 100,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerMemoryLimit = "3g",
    [string]$GdalDockerImage = "ghcr.io/osgeo/gdal:ubuntu-small-latest",
    [int]$RasterSampleProgressInterval = 10000,
    [int]$RasterSampleMaxWindowPixels = 250000000,

    [switch]$SkipImport,
    [switch]$UseDirectRasterSampling,
    [switch]$SkipValidation,
    [switch]$AllowNeutralDarknessForDryRun,
    [switch]$PublishRelease,
    [string]$ReleaseTag = "",
    [string]$Repo = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Escape-SqlLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace("'", "''")
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Command,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage Exit code: $LASTEXITCODE"
    }
}

function Invoke-PsqlText {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            $result = & $psql.Source `
                -h $DbHost `
                -p $Port `
                -U $Username `
                -d $Database `
                -v ON_ERROR_STOP=1 `
                -t `
                -A `
                -F "|" `
                -c $Sql
            if ($LASTEXITCODE -ne 0) {
                throw "psql query failed with exit code $LASTEXITCODE"
            }
            return ($result | Out-String).Trim()
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        throw "Neither psql nor docker is available."
    }

    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1",
        "-t",
        "-A",
        "-F", "|",
        "-c", $Sql
    )
    $result = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker psql query failed with exit code $LASTEXITCODE"
    }
    return ($result | Out-String).Trim()
}

function Expand-GzipRasterIfNeeded {
    param([Parameter(Mandatory = $true)][string]$InputPath)

    $resolvedPath = (Resolve-Path -LiteralPath $InputPath).Path
    if (-not $resolvedPath.EndsWith(".gz", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $resolvedPath
    }

    $expandedPath = $resolvedPath.Substring(0, $resolvedPath.Length - 3)
    if (Test-Path -LiteralPath $expandedPath -PathType Leaf) {
        $sourceItem = Get-Item -LiteralPath $resolvedPath
        $expandedItem = Get-Item -LiteralPath $expandedPath
        if ($expandedItem.LastWriteTimeUtc -ge $sourceItem.LastWriteTimeUtc -and $expandedItem.Length -gt 0) {
            Write-Host "Using existing decompressed raster: $expandedPath"
            return $expandedPath
        }
    }

    Write-Host "Decompressing gzip raster to: $expandedPath"
    $inputStream = [System.IO.File]::OpenRead($resolvedPath)
    try {
        $gzipStream = [System.IO.Compression.GZipStream]::new($inputStream, [System.IO.Compression.CompressionMode]::Decompress)
        try {
            $outputStream = [System.IO.File]::Create($expandedPath)
            try {
                $gzipStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
            }
        } finally {
            $gzipStream.Dispose()
        }
    } finally {
        $inputStream.Dispose()
    }

    $expandedItem = Get-Item -LiteralPath $expandedPath
    if ($expandedItem.Length -le 0) {
        throw "Decompressed raster is empty: $expandedPath"
    }
    return $expandedPath
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$importScript = Join-Path $repoRoot "scripts/setup/import-light-pollution-v30.ps1"
$sampleImportScript = Join-Path $repoRoot "scripts/setup/import-light-pollution-samples-v31.ps1"
$sqlScript = Join-Path $repoRoot "scripts/setup/data-quality-enrichment-v31.sql"
$publishScript = Join-Path $repoRoot "scripts/deploy/publish_scenic_release.ps1"

if (-not (Test-Path -LiteralPath $sqlScript -PathType Leaf)) {
    throw "3.1 SQL script not found: $sqlScript"
}

if (-not $SkipImport) {
    if (-not $LightPollutionInputPath) {
        throw "Pass -LightPollutionInputPath or use -SkipImport when public.light_pollution_raster is already loaded."
    }
    if (-not (Test-Path -LiteralPath $LightPollutionInputPath -PathType Leaf)) {
        throw "Light-pollution raster not found: $LightPollutionInputPath"
    }
    $importInputPath = Expand-GzipRasterIfNeeded -InputPath $LightPollutionInputPath

    Push-Location $repoRoot
    try {
        if ($UseDirectRasterSampling) {
            if (-not (Test-Path -LiteralPath $sampleImportScript -PathType Leaf)) {
                throw "Light-pollution sample import script not found: $sampleImportScript"
            }

            $sampleImportArgs = @(
                "-InputPath", $importInputPath,
                "-SourceScoringVersion", $SourceScoringVersion,
                "-ScoringVersion", $ScoringVersion,
                "-LightPollutionReferenceMax", "$LightPollutionReferenceMax",
                "-Database", $Database,
                "-Username", $Username,
                "-PostgresContainerName", $PostgresContainerName,
                "-DockerMemoryLimit", $DockerMemoryLimit,
                "-GdalDockerImage", $GdalDockerImage,
                "-ProgressInterval", "$RasterSampleProgressInterval",
                "-MaxWindowPixels", "$RasterSampleMaxWindowPixels"
            )
            if ($Password) {
                $sampleImportArgs += @("-Password", $Password)
            }

            Write-Host "Sampling light-pollution raster directly into per-H3 scores..."
            Invoke-Checked -FailureMessage "Light-pollution direct sampling import failed." -Command {
                & powershell -ExecutionPolicy Bypass -File $sampleImportScript @sampleImportArgs
            }
        } else {
            if (-not (Test-Path -LiteralPath $importScript -PathType Leaf)) {
                throw "Light-pollution import script not found: $importScript"
            }

            $importArgs = @(
                "-InputPath", $importInputPath,
                "-Database", $Database,
                "-Username", $Username,
                "-DbHost", $DbHost,
                "-Port", "$Port",
                "-PostgresContainerName", $PostgresContainerName,
                "-DockerMemoryLimit", $DockerMemoryLimit
            )
            if ($Password) {
                $importArgs += @("-Password", $Password)
            }

            Write-Host "Importing light-pollution raster..."
            Invoke-Checked -FailureMessage "Light-pollution raster import failed." -Command {
                & powershell -ExecutionPolicy Bypass -File $importScript @importArgs
            }
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Skipping raster import; using existing public.light_pollution_raster rows."
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
$containerNames = @()
if ($docker) {
    $containerNames = docker ps -a --format "{{.Names}}" 2>$null
}
$useDockerCopy = ($docker -and (($containerNames | Where-Object { $_ -eq $PostgresContainerName } | Measure-Object).Count -gt 0))

if ($useDockerCopy) {
    Write-Host "Copying 3.1 SQL into $PostgresContainerName..."
    Invoke-Checked -FailureMessage "Failed to copy 3.1 SQL into postgres container." -Command {
        & docker cp $sqlScript "${PostgresContainerName}:/tmp/data-quality-enrichment-v31.sql"
    }
    $sqlPathForPsql = "/tmp/data-quality-enrichment-v31.sql"
} else {
    $sqlPathForPsql = $sqlScript
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
$allowNeutral = if ($AllowNeutralDarknessForDryRun) { "true" } else { "false" }

Write-Host "Running 3.1 recompute as '$ScoringVersion'..."
if ($psql) {
    if ($Password) { $env:PGPASSWORD = $Password }
    try {
        Invoke-Checked -FailureMessage "3.1 recompute failed." -Command {
            & $psql.Source `
                -h $DbHost `
                -p $Port `
                -U $Username `
                -d $Database `
                -v ON_ERROR_STOP=1 `
                -v "source_scoring_version=$SourceScoringVersion" `
                -v "scoring_version=$ScoringVersion" `
                -v "chunk_size=$ChunkSize" `
                -v "light_pollution_reference_max=$LightPollutionReferenceMax" `
                -v "allow_neutral_darkness=$allowNeutral" `
                -f $sqlPathForPsql
        }
    } finally {
        if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    }
} elseif ($useDockerCopy) {
    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1",
        "-v", "source_scoring_version=$SourceScoringVersion",
        "-v", "scoring_version=$ScoringVersion",
        "-v", "chunk_size=$ChunkSize",
        "-v", "light_pollution_reference_max=$LightPollutionReferenceMax",
        "-v", "allow_neutral_darkness=$allowNeutral",
        "-f", $sqlPathForPsql
    )
    Invoke-Checked -FailureMessage "3.1 recompute failed." -Command {
        & docker @dockerArgs
    }
} else {
    throw "Neither local psql nor docker postgres container '$PostgresContainerName' is available."
}

if ($SkipValidation) {
    Write-Warning "Skipping 3.1 validation gate. Use this only for script smoke tests."
    Write-Host "MoodRide data enrichment 3.1 completed."
    exit 0
}

$escapedVersion = Escape-SqlLiteral -Value $ScoringVersion
$summarySql = @"
SELECT
  COUNT(*) AS total_tiles,
  COUNT(*) FILTER (WHERE scoring_version = '$escapedVersion') AS v31_tiles,
  COALESCE(MIN(darkness_score), 0) AS min_darkness,
  COALESCE(AVG(darkness_score), 0) AS avg_darkness,
  COALESCE(MAX(darkness_score), 0) AS max_darkness,
  COALESCE(STDDEV_POP(darkness_score), 0) AS stddev_darkness,
  COUNT(*) FILTER (WHERE darkness_score <> 0.5) AS changed_darkness_tiles,
  COUNT(*) FILTER (WHERE building_density_score = urban_penalty_score) AS equal_penalty_tiles
FROM scenic_score_tiles;
"@

$summaryText = Invoke-PsqlText -Sql $summarySql
if (-not $summaryText) {
    throw "3.1 validation query returned no output."
}

$parts = $summaryText.Split("|")
if ($parts.Count -lt 8) {
    throw "Unexpected 3.1 validation output: $summaryText"
}

$totalTiles = [int64]$parts[0]
$v31Tiles = [int64]$parts[1]
$minDarkness = [double]$parts[2]
$avgDarkness = [double]$parts[3]
$maxDarkness = [double]$parts[4]
$stddevDarkness = [double]$parts[5]
$changedDarknessTiles = [int64]$parts[6]
$equalPenaltyTiles = [int64]$parts[7]

Write-Host ""
Write-Host "3.1 validation summary"
Write-Host "  total_tiles: $totalTiles"
Write-Host "  v31_tiles: $v31Tiles"
Write-Host "  darkness min/avg/max/stddev: $minDarkness / $avgDarkness / $maxDarkness / $stddevDarkness"
Write-Host "  changed_darkness_tiles: $changedDarknessTiles"
Write-Host "  equal building_density_score vs urban_penalty_score tiles: $equalPenaltyTiles"

if ($totalTiles -le 0) {
    throw "No scenic_score_tiles rows were found."
}
if ($v31Tiles -ne $totalTiles) {
    throw "Only $v31Tiles of $totalTiles tiles are at '$ScoringVersion'."
}
if (-not $AllowNeutralDarknessForDryRun) {
    if ($changedDarknessTiles -le 0 -or $stddevDarkness -le 0) {
        throw "darkness_score is still flat. Import a real nighttime-light raster and rerun."
    }
}
if ($equalPenaltyTiles -ge $totalTiles) {
    throw "urban_penalty_score is still identical to building_density_score for every tile."
}

$equalPenaltyRatio = $equalPenaltyTiles / [double]$totalTiles
if ($equalPenaltyRatio -gt 0.5) {
    Write-Warning ("More than half of tiles still match building density exactly ({0:P2}). Review v3.1 formula inputs." -f $equalPenaltyRatio)
}

if ($PublishRelease) {
    if (-not (Test-Path -LiteralPath $publishScript -PathType Leaf)) {
        throw "Scenic release publish script not found: $publishScript"
    }

    $publishArgs = @(
        "-ScoringVersion", $ScoringVersion,
        "-Database", $Database,
        "-Username", $Username,
        "-DbHost", $DbHost,
        "-Port", "$Port",
        "-PostgresContainerName", $PostgresContainerName
    )
    if ($Password) { $publishArgs += @("-Password", $Password) }
    if ($ReleaseTag) { $publishArgs += @("-ReleaseTag", $ReleaseTag) }
    if ($Repo) { $publishArgs += @("-Repo", $Repo) }

    Push-Location $repoRoot
    try {
        Write-Host "Publishing scenic 3.1 release..."
        Invoke-Checked -FailureMessage "Scenic release publish failed." -Command {
            & powershell -ExecutionPolicy Bypass -File $publishScript @publishArgs
        }
    } finally {
        Pop-Location
    }
}

Write-Host "MoodRide data enrichment 3.1 completed."
