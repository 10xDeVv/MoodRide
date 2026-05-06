param(
    [Parameter(Mandatory = $true)]
    [string]$OsmPbfPath,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,

    # Optionally pass a password; if omitted, .pgpass/interactive auth applies.
    [string]$Password,

    # Keep import small and reliable by default for local runs.
    [int]$NumberProcesses = 2,
    [switch]$Slim
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -Path $OsmPbfPath -PathType Leaf)) {
    throw "OSM file not found: $OsmPbfPath"
}

$osm2pgsql = Get-Command osm2pgsql -ErrorAction SilentlyContinue
if (-not $osm2pgsql) {
    throw "osm2pgsql is not installed or not on PATH. Install it first, then retry."
}

$args = @(
    "--create",
    "--database", $Database,
    "--username", $Username,
    "--host", $DbHost,
    "--port", $Port,
    "--number-processes", $NumberProcesses
)

if ($Slim) {
    $args += "--slim"
}

$args += $OsmPbfPath

if ($Password) {
    $env:PGPASSWORD = $Password
}

try {
    Write-Host "Starting osm2pgsql import into '$Database' on $DbHost`:$Port ..."
    & $osm2pgsql.Source $args
    if ($LASTEXITCODE -ne 0) {
        throw "osm2pgsql failed with exit code $LASTEXITCODE"
    }

    Write-Host "Verifying imported OSM tables..."
    if ($Password) {
        & psql -h $DbHost -p $Port -U $Username -d $Database -c "SELECT to_regclass('public.planet_osm_line') AS planet_osm_line, to_regclass('public.planet_osm_polygon') AS planet_osm_polygon, to_regclass('public.planet_osm_point') AS planet_osm_point;"
    } else {
        & psql -h $DbHost -p $Port -U $Username -d $Database -c "SELECT to_regclass('public.planet_osm_line') AS planet_osm_line, to_regclass('public.planet_osm_polygon') AS planet_osm_polygon, to_regclass('public.planet_osm_point') AS planet_osm_point;"
    }

    if ($LASTEXITCODE -ne 0) {
        throw "Verification query failed (psql exit code $LASTEXITCODE)."
    }

    Write-Host "OSM import completed."
} finally {
    if ($Password) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}
