param(
    [Parameter(Mandatory = $true)]
    [string]$ScoringVersion,

    [Parameter(Mandatory = $false)]
    [string]$ReleaseTag = "",

    [Parameter(Mandatory = $false)]
    [string]$Repo = "",

    [Parameter(Mandatory = $false)]
    [string]$Database = "moodride",

    [Parameter(Mandatory = $false)]
    [string]$Username = "postgres",

    [Parameter(Mandatory = $false)]
    [string]$DbHost = "localhost",

    [Parameter(Mandatory = $false)]
    [int]$Port = 5432,

    [Parameter(Mandatory = $false)]
    [string]$Password,

    [Parameter(Mandatory = $false)]
    [string]$PostgresContainerName = "moodride-postgres"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Escape-SqlLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace("'", "''")
}

function To-PosixPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return $Path.Replace("\", "/")
}

function Invoke-PsqlServerCopyToFile {
    param(
        [Parameter(Mandatory = $true)][string]$SelectSql,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        $escapedPath = Escape-SqlLiteral -Value (To-PosixPath -Path $OutputPath)
        $copyCommand = "\copy ($SelectSql) TO '$escapedPath' WITH (FORMAT csv, HEADER true)"

        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $copyCommand
            if ($LASTEXITCODE -ne 0) {
                throw "psql copy command failed with exit code $LASTEXITCODE"
            }
            return
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        throw "Neither psql nor docker is available for export."
    }

    $containerNames = docker ps -a --format "{{.Names}}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker CLI is present but daemon is unavailable. Start Docker Desktop or install local psql."
    }
    $containerExists = ($containerNames | Where-Object { $_ -eq $PostgresContainerName } | Measure-Object).Count -gt 0
    if (-not $containerExists) {
        throw "Docker fallback requested but container '$PostgresContainerName' does not exist."
    }

    $tempCsvInContainer = "/tmp/scenic-release-$([Guid]::NewGuid().ToString("N")).csv"
    $copySql = "COPY ($SelectSql) TO '$tempCsvInContainer' WITH (FORMAT csv, HEADER true);"

    try {
        $dockerArgs = @("exec")
        if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
        $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $copySql)
        & docker @dockerArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Docker psql COPY failed with exit code $LASTEXITCODE"
        }

        & docker cp "${PostgresContainerName}:$tempCsvInContainer" $OutputPath
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to copy exported CSV from docker container."
        }
    } finally {
        & docker exec $PostgresContainerName sh -lc "rm -f '$tempCsvInContainer'" | Out-Null
    }
}

function Invoke-PsqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            $result = & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -t -A -c $Sql
            if ($LASTEXITCODE -ne 0) {
                throw "psql scalar query failed with exit code $LASTEXITCODE"
            }
            return ($result | Out-String).Trim()
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        throw "Neither psql nor docker is available for scalar query."
    }

    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-t", "-A", "-c", $Sql)
    $result = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker psql scalar query failed with exit code $LASTEXITCODE"
    }
    return ($result | Out-String).Trim()
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI (gh) is required. Install from https://cli.github.com/ and run 'gh auth login'."
}

$sanitizedVersion = ($ScoringVersion.ToLowerInvariant() -replace '[^a-z0-9._-]', '-')
if (-not $ReleaseTag) {
    $ReleaseTag = "scenic-$sanitizedVersion-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$ensureRoadStressSql = @"
ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS road_stress_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_visibility_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_crossing_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS coastal_road_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS tree_canopy_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS scenic_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS viewpoint_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS bridge_coastal_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;
"@
Invoke-PsqlScalar -Sql $ensureRoadStressSql | Out-Null

$escapedVersion = Escape-SqlLiteral -Value $ScoringVersion
$selectSql = @"
SELECT
    h3_index,
    scenic_score,
    water_score,
    green_score,
    elevation_score,
    solitude_score,
    curve_score,
    poi_score,
    park_score,
    overture_poi_score,
    building_density_score,
    darkness_score,
    urban_penalty_score,
    road_stress_score,
    natural_land_use,
    elevation_variance,
    last_scored,
    scoring_version,
    water_visibility_score,
    water_crossing_score,
    coastal_road_score,
    tree_canopy_score,
    scenic_poi_score,
    viewpoint_score,
    bridge_coastal_score
FROM scenic_score_tiles
WHERE scoring_version = '$escapedVersion'
ORDER BY h3_index
"@

$workDir = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("moodride-scenic-release-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $workDir | Out-Null

try {
    $csvName = "scenic_score_tiles_updates.csv"
    $csvPath = Join-Path $workDir $csvName
    Invoke-PsqlServerCopyToFile -SelectSql $selectSql -OutputPath $csvPath

    if (-not (Test-Path -LiteralPath $csvPath)) {
        throw "Expected CSV export file not found: $csvPath"
    }

    $lineCount = (Get-Content -LiteralPath $csvPath | Measure-Object -Line).Lines
    if ($lineCount -le 1) {
        throw "No rows found for scoring_version '$ScoringVersion'."
    }

    $statsSql = @"
SELECT json_build_object(
  'tileCount', COUNT(*),
  'minScenicScore', MIN(scenic_score),
  'maxScenicScore', MAX(scenic_score),
  'avgScenicScore', AVG(scenic_score),
  'stddevScenicScore', STDDEV_POP(scenic_score),
  'lastScoredMin', MIN(last_scored),
  'lastScoredMax', MAX(last_scored)
)::text
FROM scenic_score_tiles
WHERE scoring_version = '$escapedVersion';
"@
    $statsJson = Invoke-PsqlScalar -Sql $statsSql
    if (-not $statsJson) {
        throw "Failed to compute release stats for scoring_version '$ScoringVersion'."
    }

    $metadataObj = [ordered]@{
        scoringVersion = $ScoringVersion
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        database       = $Database
        dbHost         = $DbHost
        rowStats       = $statsJson | ConvertFrom-Json
    }
    $metadata = $metadataObj | ConvertTo-Json -Depth 6
    $metadataPath = Join-Path $workDir "metadata.json"
    Set-Content -LiteralPath $metadataPath -Value $metadata -Encoding UTF8

    $assetName = "scenic-tiles-$sanitizedVersion.tar.gz"
    $assetPath = Join-Path (Get-Location) $assetName
    if (Test-Path $assetPath) {
        Remove-Item -LiteralPath $assetPath -Force
    }

    Push-Location $workDir
    try {
        tar -czf $assetPath $csvName "metadata.json"
    } finally {
        Pop-Location
    }

    $hash = (Get-FileHash -Algorithm SHA256 -Path $assetPath).Hash.ToLowerInvariant()
    $checksumPath = "$assetPath.sha256"
    Set-Content -LiteralPath $checksumPath -Value "$hash  $assetName" -Encoding ASCII

    $repoArgs = @()
    if ($Repo) {
        $repoArgs = @("--repo", $Repo)
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & gh release view $ReleaseTag @repoArgs *> $null
        $releaseExists = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if (-not $releaseExists) {
        & gh release create $ReleaseTag --title "Scenic release $ReleaseTag" --notes "Scenic tile release for scoring_version '$ScoringVersion'." @repoArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create release $ReleaseTag."
        }
    }

    & gh release upload $ReleaseTag $assetPath $checksumPath --clobber @repoArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload release assets."
    }

    Write-Host "Published scenic release tag: $ReleaseTag"
    Write-Host "Assets:"
    Write-Host "  - $assetName"
    Write-Host "  - $(Split-Path -Leaf $checksumPath)"
} finally {
    if (Test-Path $workDir) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
}
