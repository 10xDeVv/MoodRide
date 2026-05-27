param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$TargetTable = "light_pollution_raster",
    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [int]$Srid = 4326,
    [string]$TileSize = "256x256",
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerMemoryLimit = "3g",
    [switch]$Append,
    [switch]$SkipVerify
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($TargetTable -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$') {
    throw "TargetTable must be a safe PostgreSQL identifier: $TargetTable"
}

$repoRoot = (Resolve-Path ".").Path
$importScript = Join-Path $repoRoot "scripts/setup/import-raster-to-postgis.ps1"

if (-not (Test-Path -LiteralPath $importScript -PathType Leaf)) {
    throw "Raster import helper not found: $importScript"
}

if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) {
    throw "Light-pollution raster not found: $InputPath"
}

$argsList = @(
    "-InputPath", $InputPath,
    "-TargetTable", $TargetTable,
    "-Database", $Database,
    "-Username", $Username,
    "-DbHost", $DbHost,
    "-Port", "$Port",
    "-Srid", "$Srid",
    "-TileSize", $TileSize,
    "-PostgresContainerName", $PostgresContainerName,
    "-DockerMemoryLimit", $DockerMemoryLimit
)

if ($Password) {
    $argsList += @("-Password", $Password)
}
if ($Append) {
    $argsList += "-Append"
}
if ($SkipVerify) {
    $argsList += "-SkipVerify"
}

Write-Host "Importing light-pollution raster into public.$TargetTable ..."
& powershell -ExecutionPolicy Bypass -File $importScript @argsList
if ($LASTEXITCODE -ne 0) {
    throw "Light-pollution raster import failed with exit code $LASTEXITCODE"
}

$indexSql = @"
CREATE INDEX IF NOT EXISTS ${TargetTable}_convexhull_gist_idx
    ON public.$TargetTable
    USING GIST (ST_ConvexHull(rast));
ANALYZE public.$TargetTable;
SELECT
    COUNT(*) AS raster_tiles,
    MIN(ST_SRID(rast)) AS min_srid,
    MAX(ST_SRID(rast)) AS max_srid
FROM public.$TargetTable;
"@

$psql = Get-Command psql -ErrorAction SilentlyContinue
if ($psql) {
    if ($Password) { $env:PGPASSWORD = $Password }
    try {
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $indexSql
        if ($LASTEXITCODE -ne 0) {
            throw "Light-pollution index/analyze failed with exit code $LASTEXITCODE"
        }
    } finally {
        if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    }
} else {
    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $indexSql)
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker light-pollution index/analyze failed with exit code $LASTEXITCODE"
    }
}

Write-Host "Light-pollution raster import complete."
