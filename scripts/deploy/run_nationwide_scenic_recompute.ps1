param(
    [Parameter(Mandatory = $false)]
    [string]$SqlScriptPath = "scripts/setup/data-quality-enrichment-v31.sql",

    [Parameter(Mandatory = $false)]
    [int]$ChunkSize = 50000,

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
    [string]$PostgresContainerName = "moodride-postgres",

    [Parameter(Mandatory = $false)]
    [string]$ExpectedScoringVersion = "3.1-darkness-urban-penalty-calibration"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path ".").Path
$resolvedSqlPath = (Resolve-Path -LiteralPath (Join-Path $repoRoot $SqlScriptPath)).Path

function Invoke-PostgresSqlFile {
    param(
        [Parameter(Mandatory = $true)][string]$SqlFilePath
    )

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -v "chunk_size=$ChunkSize" -f $SqlFilePath
            if ($LASTEXITCODE -ne 0) {
                throw "psql execution failed with exit code $LASTEXITCODE"
            }
            return
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        throw "Neither psql nor docker is available."
    }

    $containerNames = docker ps -a --format "{{.Names}}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker CLI is present but daemon is unavailable. Start Docker Desktop or install local psql."
    }
    $containerExists = ($containerNames | Where-Object { $_ -eq $PostgresContainerName } | Measure-Object).Count -gt 0
    if (-not $containerExists) {
        throw "Docker fallback requested but container '$PostgresContainerName' does not exist."
    }

    $tempSqlInContainer = "/tmp/$([Guid]::NewGuid().ToString())-data-quality-upgrade.sql"
    docker cp "$SqlFilePath" "${PostgresContainerName}:$tempSqlInContainer" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy SQL file into docker container."
    }

    try {
        $dockerArgs = @("exec")
        if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
        $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-v", "chunk_size=$ChunkSize", "-f", $tempSqlInContainer)
        & docker @dockerArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Docker psql execution failed with exit code $LASTEXITCODE"
        }
    } finally {
        docker exec "$PostgresContainerName" sh -lc "rm -f '$tempSqlInContainer'" | Out-Null
    }
}

function Invoke-PostgresScalar {
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

    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-t", "-A", "-c", $Sql)
    $result = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker psql scalar query failed with exit code $LASTEXITCODE"
    }
    return ($result | Out-String).Trim()
}

Write-Host "Running nationwide scenic recompute SQL:"
Write-Host "  $resolvedSqlPath"
Write-Host "  chunk_size=$ChunkSize"
Invoke-PostgresSqlFile -SqlFilePath $resolvedSqlPath

$escapedVersion = $ExpectedScoringVersion.Replace("'", "''")
$summarySql = @"
SELECT json_build_object(
    'scoringVersion', '$escapedVersion',
    'tileCount', COUNT(*),
    'avgScenicScore', AVG(scenic_score),
    'stddevScenicScore', STDDEV_POP(scenic_score),
    'minScenicScore', MIN(scenic_score),
    'maxScenicScore', MAX(scenic_score),
    'lastScoredMin', MIN(last_scored),
    'lastScoredMax', MAX(last_scored)
)::text
FROM scenic_score_tiles
WHERE scoring_version = '$escapedVersion';
"@
$summary = Invoke-PostgresScalar -Sql $summarySql
Write-Host "`nRecompute summary:"
Write-Host $summary

Write-Host "`nNext step (publish scenic release):"
Write-Host "./scripts/deploy/publish_scenic_release.ps1 -ScoringVersion `"$ExpectedScoringVersion`" -ReleaseTag `"scenic-$ExpectedScoringVersion-$(Get-Date -Format 'yyyyMMdd-HHmm')`" -Repo `"10xDeVv/Wayward`""
