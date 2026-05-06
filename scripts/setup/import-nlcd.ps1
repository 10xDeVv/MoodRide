param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerGdalImage = "ghcr.io/osgeo/gdal:ubuntu-small-latest",
    [string]$DockerMemoryLimit = "3g",

    # Name of the class column in the NLCD source file (for example: nlcd_class, DN, gridcode)
    [string]$ClassColumn = "nlcd_class",

    # Optional source label stored in nlcd_land_cover_cells.source
    [string]$SourceLabel = "NLCD"
)

$ErrorActionPreference = "Stop"

function Test-WslCommand {
    param([string]$CommandName)
    $null = & wsl.exe -e bash -lc "command -v $CommandName" 2>$null
    return ($LASTEXITCODE -eq 0)
}

function Convert-ToWslPath {
    param([string]$WindowsPath)
    $normalized = $WindowsPath -replace '\\', '/'
    if ($normalized -match '^([A-Za-z]):/(.+)$') {
        $drive = $matches[1].ToLowerInvariant()
        $tail = $matches[2]
        return "/mnt/$drive/$tail"
    }
    if ($normalized.StartsWith('/')) {
        return $normalized
    }
    throw "Unable to convert Windows path to WSL path: $WindowsPath"
}

function Resolve-WslDbHost {
    param([string]$OriginalHost)

    if ($OriginalHost -ne "localhost" -and $OriginalHost -ne "127.0.0.1") {
        return $OriginalHost
    }

    $hostDockerLine = & wsl.exe -e bash -lc "getent hosts host.docker.internal | awk '{print `$1}' | head -n 1" 2>$null | Select-Object -First 1
    $hostDocker = if ($null -ne $hostDockerLine) { $hostDockerLine.ToString().Trim() } else { "" }
    if (-not [string]::IsNullOrWhiteSpace($hostDocker)) {
        return $hostDocker
    }

    $gatewayLine = & wsl.exe -e bash -lc "ip route show default | awk '{print `$3}' | head -n 1" 2>$null | Select-Object -First 1
    $gateway = if ($null -ne $gatewayLine) { $gatewayLine.ToString().Trim() } else { "" }
    if (-not [string]::IsNullOrWhiteSpace($gateway)) {
        return $gateway
    }

    return $OriginalHost
}

if (-not (Test-Path -Path $InputPath -PathType Leaf)) {
    throw "NLCD source file not found: $InputPath"
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
$ogr2ogr = Get-Command ogr2ogr -ErrorAction SilentlyContinue
$useDockerOgr2ogr = $false
$useWslOgr2ogr = $false
if (-not $ogr2ogr) {
    if ($docker) {
        $useDockerOgr2ogr = $true
        Write-Host "Using Docker GDAL fallback image '$DockerGdalImage'." -ForegroundColor DarkYellow
    } elseif (Test-WslCommand -CommandName "ogr2ogr") {
        $useWslOgr2ogr = $true
        Write-Host "Using WSL ogr2ogr fallback." -ForegroundColor DarkYellow
    } else {
        throw "ogr2ogr is not installed on Windows PATH and no Docker/WSL fallback is available."
    }
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
$useDockerPsql = $false
$useWslPsql = $false
if (-not $psql) {
    if ($docker) {
        $useDockerPsql = $true
        Write-Host "Using Docker psql fallback via container '$PostgresContainerName'." -ForegroundColor DarkYellow
    } elseif (Test-WslCommand -CommandName "psql") {
        $useWslPsql = $true
        Write-Host "Using WSL psql fallback." -ForegroundColor DarkYellow
    } else {
        throw "psql is not installed on Windows PATH and no Docker/WSL fallback is available."
    }
}

if ($Password) {
    $env:PGPASSWORD = $Password
}

$pgConn = "PG:host=$DbHost port=$Port dbname=$Database user=$Username"
if ($Password) {
    $pgConn += " password=$Password"
}

$effectiveDbHostForWsl = $DbHost
if ($effectiveDbHostForWsl -eq "localhost" -or $effectiveDbHostForWsl -eq "127.0.0.1") {
    $effectiveDbHostForWsl = Resolve-WslDbHost -OriginalHost $DbHost
}

try {
    $dockerNetwork = $null
    if ($useDockerOgr2ogr) {
        $dockerNetwork = (docker inspect $PostgresContainerName | ConvertFrom-Json)[0].NetworkSettings.Networks.PSObject.Properties.Name | Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($dockerNetwork)) {
            throw "Could not determine docker network for container '$PostgresContainerName'."
        }
    }

    $sourcePathForImport = $InputPath
    $inputExtension = [System.IO.Path]::GetExtension($InputPath).ToLowerInvariant()
    if ($inputExtension -in @(".tif", ".tiff")) {
        Write-Host "Detected raster NLCD source; polygonizing to vector staging first..." -ForegroundColor DarkYellow
        $polygonizedPath = Join-Path ([System.IO.Path]::GetDirectoryName($InputPath)) ([System.IO.Path]::GetFileNameWithoutExtension($InputPath) + ".polygonized.gpkg")

        if ($ClassColumn -eq "nlcd_class") {
            $ClassColumn = "DN"
            Write-Host "ClassColumn not provided for raster NLCD; using default polygonized field 'DN'." -ForegroundColor DarkYellow
        }

        if ($useDockerOgr2ogr) {
            $inputDir = Split-Path -Path $InputPath -Parent
            $inputFile = Split-Path -Path $InputPath -Leaf
            $outputFile = Split-Path -Path $polygonizedPath -Leaf
            & docker run --rm --memory $DockerMemoryLimit --memory-swap $DockerMemoryLimit --network $dockerNetwork -v "${inputDir}:/workspace/input" $DockerGdalImage gdal_polygonize.py "/workspace/input/$inputFile" -f GPKG "/workspace/input/$outputFile"
        } elseif ($useWslOgr2ogr) {
            if (-not (Test-WslCommand -CommandName "gdal_polygonize.py")) {
                throw "gdal_polygonize.py is required for raster NLCD conversion but was not found in WSL."
            }
            $wslInput = Convert-ToWslPath -WindowsPath $InputPath
            $wslOutput = Convert-ToWslPath -WindowsPath $polygonizedPath
            & wsl.exe gdal_polygonize.py $wslInput -f GPKG $wslOutput
        } else {
            $gdalPolygonize = Get-Command gdal_polygonize.py -ErrorAction SilentlyContinue
            if (-not $gdalPolygonize) {
                throw "gdal_polygonize.py is required for raster NLCD conversion but was not found on PATH."
            }
            & $gdalPolygonize.Source $InputPath -f GPKG $polygonizedPath
        }

        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -Path $polygonizedPath -PathType Leaf)) {
            throw "Raster polygonization failed for $InputPath"
        }

        $sourcePathForImport = $polygonizedPath
        Write-Host "Raster polygonization complete: $sourcePathForImport"
    }

    Write-Host "Importing NLCD source to staging table..."
    if ($useDockerOgr2ogr) {
        $sourceDir = Split-Path -Path $sourcePathForImport -Parent
        $sourceFile = Split-Path -Path $sourcePathForImport -Leaf
        $dockerPgConn = "PG:host=$PostgresContainerName port=5432 dbname=$Database user=$Username"
        if ($Password) {
            $dockerPgConn += " password=$Password"
        }
        & docker run --rm --memory $DockerMemoryLimit --memory-swap $DockerMemoryLimit --network $dockerNetwork -v "${sourceDir}:/workspace/input" $DockerGdalImage ogr2ogr `
            -f PostgreSQL $dockerPgConn "/workspace/input/$sourceFile" `
            -nln nlcd_land_cover_cells_staging `
            -overwrite `
            -nlt MULTIPOLYGON `
            -lco GEOMETRY_NAME=geometry `
            -lco FID=id `
            -t_srs EPSG:4326
    } elseif ($useWslOgr2ogr) {
        $wslPgConn = "PG:host=$effectiveDbHostForWsl port=$Port dbname=$Database user=$Username"
        if ($Password) {
            $wslPgConn += " password=$Password"
        }
        $wslSourcePath = Convert-ToWslPath -WindowsPath $sourcePathForImport
        & wsl.exe ogr2ogr `
            -f PostgreSQL $wslPgConn $wslSourcePath `
            -nln nlcd_land_cover_cells_staging `
            -overwrite `
            -nlt MULTIPOLYGON `
            -lco GEOMETRY_NAME=geometry `
            -lco FID=id `
            -t_srs EPSG:4326
    } else {
        & $ogr2ogr.Source `
            -f PostgreSQL $pgConn $sourcePathForImport `
            -nln nlcd_land_cover_cells_staging `
            -overwrite `
            -nlt MULTIPOLYGON `
            -lco GEOMETRY_NAME=geometry `
            -lco FID=id `
            -t_srs EPSG:4326
    }

    if ($LASTEXITCODE -ne 0) {
        throw "ogr2ogr import failed with exit code $LASTEXITCODE"
    }

    # PowerShell-friendly way to avoid SQL injection from identifier input
    $safeClassColumn = $ClassColumn -replace '[^a-zA-Z0-9_]', ''
    if ([string]::IsNullOrWhiteSpace($safeClassColumn)) {
        throw "ClassColumn must contain at least one alphanumeric character."
    }

    $insertSql = @"
INSERT INTO nlcd_land_cover_cells (geometry, nlcd_class, source, last_updated)
SELECT
            dumped.geom::geometry(POLYGON, 4326) AS geometry,
    CAST($safeClassColumn AS INTEGER) AS nlcd_class,
    '$SourceLabel' AS source,
    CURRENT_TIMESTAMP AS last_updated
FROM nlcd_land_cover_cells_staging
        CROSS JOIN LATERAL ST_Dump(ST_CollectionExtract(ST_MakeValid(geometry), 3)) AS dumped
WHERE geometry IS NOT NULL
  AND $safeClassColumn IS NOT NULL;
"@

    Write-Host "Loading staging rows into nlcd_land_cover_cells..."
    if ($useDockerPsql) {
        $dockerArgs = @("exec")
        if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
        $dockerArgs += @($PostgresContainerName, "psql", "-h", $DbHost, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $insertSql)
        & docker @dockerArgs
    } elseif ($useWslPsql) {
        $wslArgs = @("psql", "-h", $effectiveDbHostForWsl, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $insertSql)
        if ($Password) {
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs
        } else {
            & wsl.exe @wslArgs
        }
    } else {
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $insertSql
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to insert NLCD rows into nlcd_land_cover_cells"
    }

    $dropSql = "DROP TABLE IF EXISTS nlcd_land_cover_cells_staging;"
    $countSql = "SELECT COUNT(*) AS nlcd_rows, MIN(nlcd_class) AS min_class, MAX(nlcd_class) AS max_class FROM nlcd_land_cover_cells;"

    if ($useDockerPsql) {
        $dropArgs = @("exec")
        if ($Password) { $dropArgs += @("-e", "PGPASSWORD=$Password") }
        $dropArgs += @($PostgresContainerName, "psql", "-h", $DbHost, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $dropSql)
        & docker @dropArgs

        $countArgs = @("exec")
        if ($Password) { $countArgs += @("-e", "PGPASSWORD=$Password") }
        $countArgs += @($PostgresContainerName, "psql", "-h", $DbHost, "-p", "$Port", "-U", $Username, "-d", $Database, "-c", $countSql)
        & docker @countArgs
    } elseif ($useWslPsql) {
        $dropWslArgs = @("psql", "-h", $effectiveDbHostForWsl, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $dropSql)
        $countWslArgs = @("psql", "-h", $effectiveDbHostForWsl, "-p", "$Port", "-U", $Username, "-d", $Database, "-c", $countSql)
        if ($Password) {
            & wsl.exe env "PGPASSWORD=$Password" @dropWslArgs
            & wsl.exe env "PGPASSWORD=$Password" @countWslArgs
        } else {
            & wsl.exe @dropWslArgs
            & wsl.exe @countWslArgs
        }
    } else {
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $dropSql
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -c $countSql
    }

    Write-Host "NLCD import completed."
}
finally {
    if ($Password) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}
