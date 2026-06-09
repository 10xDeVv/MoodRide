param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$TargetTable = "light_pollution_tile_samples",
    [string]$SourceScoringVersion = "3.0-overture-lightpollution-enrichment",
    [string]$ScoringVersion = "3.1-darkness-urban-penalty-calibration",
    [double]$LightPollutionReferenceMax = 100,
    [int]$ProgressInterval = 10000,
    [int]$MaxWindowPixels = 250000000,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$GdalDockerImage = "ghcr.io/osgeo/gdal:ubuntu-small-latest",
    [string]$DockerMemoryLimit = "8g",
    [switch]$KeepTemp
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

if ($TargetTable -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$') {
    throw "TargetTable must be a safe PostgreSQL identifier: $TargetTable"
}
if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) {
    throw "Light-pollution raster not found: $InputPath"
}
if ($LightPollutionReferenceMax -le 0) {
    throw "LightPollutionReferenceMax must be greater than 0."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$samplerScript = Join-Path $repoRoot "scripts/setup/sample-light-pollution-v31.py"
if (-not (Test-Path -LiteralPath $samplerScript -PathType Leaf)) {
    throw "Sampler script not found: $samplerScript"
}

$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("moodride-v31-light-samples-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $workDir | Out-Null

$pointsName = "scenic_tile_points.csv"
$samplesName = "light_pollution_tile_samples.csv"
$pointsPath = Join-Path $workDir $pointsName
$samplesPath = Join-Path $workDir $samplesName
$containerPointsPath = "/tmp/$pointsName"
$containerSamplesPath = "/tmp/$samplesName"

try {
    $sourceVersion = Escape-SqlLiteral -Value $SourceScoringVersion
    $targetVersion = Escape-SqlLiteral -Value $ScoringVersion
    $whereClause = "COALESCE(scoring_version, '') <> '$targetVersion' AND COALESCE(h3_index, '') <> ''"
    if ($SourceScoringVersion) {
        $whereClause += " AND COALESCE(scoring_version, '') = '$sourceVersion'"
    }

    $exportSql = @"
COPY (
    SELECT
        h3_index,
        ST_X(ST_PointOnSurface(geometry)) AS lon,
        ST_Y(ST_PointOnSurface(geometry)) AS lat
    FROM public.scenic_score_tiles
    WHERE $whereClause
    ORDER BY h3_index
) TO '$containerPointsPath' WITH (FORMAT csv, HEADER true);
"@

    $dockerExecArgs = @("exec")
    if ($Password) { $dockerExecArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerExecArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1",
        "-c", $exportSql
    )

    Write-Host "Exporting scenic tile sample points..."
    Invoke-Checked -FailureMessage "Failed to export scenic tile points." -Command {
        & docker @dockerExecArgs
    }

    Invoke-Checked -FailureMessage "Failed to copy scenic tile points from Postgres container." -Command {
        & docker cp "${PostgresContainerName}:$containerPointsPath" $pointsPath
    }

    $pointRows = [Math]::Max(0, (Get-Content -LiteralPath $pointsPath | Measure-Object -Line).Lines - 1)
    Write-Host "Exported $pointRows scenic tile sample points."

    $rasterResolved = (Resolve-Path -LiteralPath $InputPath).Path
    $rasterDir = Split-Path -Path $rasterResolved -Parent
    $rasterFile = Split-Path -Path $rasterResolved -Leaf
    $scriptDir = Split-Path -Path $samplerScript -Parent

    $gdalArgs = @(
        "run", "--rm",
        "--memory", $DockerMemoryLimit,
        "--memory-swap", $DockerMemoryLimit,
        "-v", "${rasterDir}:/workspace/input:ro",
        "-v", "${workDir}:/workspace/out",
        "-v", "${scriptDir}:/workspace/scripts:ro",
        $GdalDockerImage,
        "python3",
        "/workspace/scripts/sample-light-pollution-v31.py",
        "/workspace/input/$rasterFile",
        "/workspace/out/$pointsName",
        "/workspace/out/$samplesName",
        "--reference-max", "$LightPollutionReferenceMax",
        "--progress-interval", "$ProgressInterval",
        "--max-window-pixels", "$MaxWindowPixels"
    )

    Write-Host "Sampling light-pollution raster directly with GDAL..."
    Invoke-Checked -FailureMessage "GDAL light-pollution sampling failed." -Command {
        & docker @gdalArgs
    }

    if (-not (Test-Path -LiteralPath $samplesPath -PathType Leaf)) {
        throw "Expected sample output not found: $samplesPath"
    }

    Invoke-Checked -FailureMessage "Failed to copy sample CSV into Postgres container." -Command {
        & docker cp $samplesPath "${PostgresContainerName}:$containerSamplesPath"
    }

    $importSql = @"
DROP TABLE IF EXISTS public.$TargetTable;
CREATE TABLE public.$TargetTable (
    h3_index TEXT PRIMARY KEY,
    raw_value DOUBLE PRECISION,
    darkness_score DOUBLE PRECISION NOT NULL
);
COPY public.$TargetTable (h3_index, raw_value, darkness_score)
FROM '$containerSamplesPath'
WITH (FORMAT csv, HEADER true);
ANALYZE public.$TargetTable;
SELECT
    COUNT(*) AS sample_rows,
    COUNT(*) FILTER (WHERE darkness_score <> 0.5) AS changed_darkness_rows,
    MIN(darkness_score) AS min_darkness_score,
    AVG(darkness_score) AS avg_darkness_score,
    MAX(darkness_score) AS max_darkness_score,
    STDDEV_POP(darkness_score) AS stddev_darkness_score
FROM public.$TargetTable;
"@

    $dockerImportArgs = @("exec")
    if ($Password) { $dockerImportArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerImportArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1",
        "-c", $importSql
    )

    Write-Host "Importing sampled darkness values into public.$TargetTable..."
    Invoke-Checked -FailureMessage "Failed to import light-pollution samples." -Command {
        & docker @dockerImportArgs
    }

    Write-Host "Light-pollution sample import complete."
} finally {
    if (-not $KeepTemp -and (Test-Path -LiteralPath $workDir)) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    } elseif ($KeepTemp) {
        Write-Host "Kept temporary sample files at: $workDir"
    }
}
