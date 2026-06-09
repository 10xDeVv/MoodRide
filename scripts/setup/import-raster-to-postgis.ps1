param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$TargetTable,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,

    [int]$Srid = 4326,
    [string]$TileSize = "256x256",
    [switch]$Append,

    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerGdalImage = "artsdatabanken/raster2pgsql",
    [string]$DockerMemoryLimit = "3g",
    [string]$DockerRaster2PgsqlPath = "/usr/lib/postgresql/12/bin/raster2pgsql",
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

function Assert-SafeIdentifier {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if ($Value -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$') {
        throw ('{0} must match ^[a-zA-Z_][a-zA-Z0-9_]*$: {1}' -f $Label, $Value)
    }
}

if (-not (Test-Path -Path $InputPath -PathType Leaf)) {
    throw "Raster source file not found: $InputPath"
}

Assert-SafeIdentifier -Value $TargetTable -Label "TargetTable"

$resolvedInput = (Resolve-Path -Path $InputPath).Path
$raster2pgsql = Get-Command raster2pgsql -ErrorAction SilentlyContinue
$psql = Get-Command psql -ErrorAction SilentlyContinue
$docker = Get-Command docker -ErrorAction SilentlyContinue

$modeFlag = if ($Append) { "-a" } else { "-d" }
$qualifiedTable = "public.$TargetTable"
$applyIndex = -not $Append
$applyConstraints = -not $Append
$applyVacuum = -not $Append

if ($Password) {
    $env:PGPASSWORD = $Password
}

function Invoke-LocalImport {
    Write-Host "Loading raster with local raster2pgsql + psql into $qualifiedTable ..."
    $rasterArgs = @(
        "-s", $Srid,
        "-t", $TileSize,
        $modeFlag,
        $resolvedInput,
        $qualifiedTable
    )
    if ($applyIndex) {
        $rasterArgs = @($rasterArgs[0..3] + "-I" + $rasterArgs[4..($rasterArgs.Count - 1)])
    }
    if ($applyConstraints) {
        $rasterArgs = @($rasterArgs[0..3] + "-C" + $rasterArgs[4..($rasterArgs.Count - 1)])
    }
    if ($applyVacuum) {
        $rasterArgs = @($rasterArgs[0..3] + "-M" + $rasterArgs[4..($rasterArgs.Count - 1)])
    }

    & $raster2pgsql.Source @rasterArgs `
    | & $psql.Source `
        -h $DbHost `
        -p $Port `
        -U $Username `
        -d $Database `
        -v ON_ERROR_STOP=1

    if ($LASTEXITCODE -ne 0) {
        throw "Local raster import failed with exit code $LASTEXITCODE"
    }

    if ($SkipVerify) {
        Write-Host "Verification query skipped."
        return
    }

    & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c @"
SELECT
    COUNT(*) AS raster_tiles,
    MIN(ST_SRID(rast)) AS min_srid,
    MAX(ST_SRID(rast)) AS max_srid
FROM $qualifiedTable;
DO $$
DECLARE
    raster_tiles BIGINT;
BEGIN
    SELECT COUNT(*) INTO raster_tiles FROM $qualifiedTable;
    IF raster_tiles = 0 THEN
        RAISE EXCEPTION 'Raster import produced 0 rows in $qualifiedTable.';
    END IF;
END $$;
"@
}

function Invoke-DockerImport {
    if (-not $docker) {
        throw "Neither local raster2pgsql/psql nor docker fallback is available."
    }

    $containerNames = docker ps -a --format "{{.Names}}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker CLI is present but daemon is unavailable. Start Docker Desktop or install local psql/raster2pgsql."
    }

    $containerExists = ($containerNames | Where-Object { $_ -eq $PostgresContainerName } | Measure-Object).Count -gt 0
    if (-not $containerExists) {
        throw "Docker fallback requested but container '$PostgresContainerName' does not exist."
    }

    $modeArg = if ($Append) { "-a" } else { "-d" }

    $inputDir = Split-Path -Path $resolvedInput -Parent
    $inputFile = Split-Path -Path $resolvedInput -Leaf

    Write-Host "Loading raster via GDAL container into $qualifiedTable ..."
    $gdalArgs = @(
        "run", "--rm",
        "--memory", $DockerMemoryLimit,
        "--memory-swap", $DockerMemoryLimit,
        "-v", "${inputDir}:/workspace/input",
        $DockerGdalImage,
        $DockerRaster2PgsqlPath,
        "-s", "$Srid",
        "-t", $TileSize,
        $modeArg,
        "/workspace/input/$inputFile",
        $qualifiedTable
    )
    if ($applyIndex) {
        $insertAt = [Math]::Max(0, $gdalArgs.Count - 3)
        $gdalArgs = @($gdalArgs[0..($insertAt - 1)] + "-I" + $gdalArgs[$insertAt..($gdalArgs.Count - 1)])
    }
    if ($applyConstraints) {
        $insertAt = [Math]::Max(0, $gdalArgs.Count - 3)
        $gdalArgs = @($gdalArgs[0..($insertAt - 1)] + "-C" + $gdalArgs[$insertAt..($gdalArgs.Count - 1)])
    }
    if ($applyVacuum) {
        $insertAt = [Math]::Max(0, $gdalArgs.Count - 3)
        $gdalArgs = @($gdalArgs[0..($insertAt - 1)] + "-M" + $gdalArgs[$insertAt..($gdalArgs.Count - 1)])
    }

    $psqlArgs = @("exec", "-i")
    if ($Password) { $psqlArgs += @("-e", "PGPASSWORD=$Password") }
    $psqlArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1"
    )

    & docker @gdalArgs | & docker @psqlArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker raster import failed with exit code $LASTEXITCODE"
    }

    if ($SkipVerify) {
        Write-Host "Verification query skipped."
        return
    }

    $verifyCommand = @"
SELECT
    COUNT(*) AS raster_tiles,
    MIN(ST_SRID(rast)) AS min_srid,
    MAX(ST_SRID(rast)) AS max_srid
FROM $qualifiedTable;
DO $$
DECLARE
    raster_tiles BIGINT;
BEGIN
    SELECT COUNT(*) INTO raster_tiles FROM $qualifiedTable;
    IF raster_tiles = 0 THEN
        RAISE EXCEPTION 'Raster import produced 0 rows in $qualifiedTable.';
    END IF;
END $$;
"@
    $execArgs = @("exec")
    if ($Password) { $execArgs += @("-e", "PGPASSWORD=$Password") }
    $execArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $verifyCommand)
    & docker @execArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Verification query failed after docker raster import."
    }
}

try {
    if ($raster2pgsql -and $psql) {
        Invoke-LocalImport
    } else {
        Invoke-DockerImport
    }

    Write-Host "Raster import completed for table '$TargetTable'."
}
finally {
    if ($Password) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}
