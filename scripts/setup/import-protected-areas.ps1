param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$LayerName = "ProtectedConservedArea_2025",

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerGdalImage = "ghcr.io/osgeo/gdal:ubuntu-small-latest",
    [string]$DockerMemoryLimit = "1g",

    [string]$NameColumn = "name",
    [string]$IucnColumn = "iucn_cat",
    [string]$DesignationColumn = "designation",
    [string]$SourceLabel = "CPCAD"
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

if (-not (Test-Path -Path $InputPath)) {
    throw "Protected areas source file not found: $InputPath"
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

$safeNameColumn = $NameColumn -replace '[^a-zA-Z0-9_]', ''
$safeIucnColumn = $IucnColumn -replace '[^a-zA-Z0-9_]', ''
$safeDesignationColumn = $DesignationColumn -replace '[^a-zA-Z0-9_]', ''

try {
    Write-Host "Importing protected areas into staging..."
    if ($useDockerOgr2ogr) {
        $dockerNetwork = (docker inspect $PostgresContainerName | ConvertFrom-Json)[0].NetworkSettings.Networks.PSObject.Properties.Name | Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($dockerNetwork)) {
            throw "Could not determine docker network for container '$PostgresContainerName'."
        }

        $inputDir = Split-Path -Path $InputPath -Parent
        $inputFile = Split-Path -Path $InputPath -Leaf
        $dockerPgConn = "PG:host=$PostgresContainerName port=5432 dbname=$Database user=$Username"
        if ($Password) {
            $dockerPgConn += " password=$Password"
        }

        & docker run --rm --memory $DockerMemoryLimit --memory-swap $DockerMemoryLimit --network $dockerNetwork -v "${inputDir}:/workspace/input" $DockerGdalImage ogr2ogr `
            -f PostgreSQL $dockerPgConn "/workspace/input/$inputFile" $LayerName `
            -nln protected_areas_staging `
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
        $wslInputPath = Convert-ToWslPath -WindowsPath $InputPath
        & wsl.exe ogr2ogr `
            -f PostgreSQL $wslPgConn $wslInputPath $LayerName `
            -nln protected_areas_staging `
            -overwrite `
            -nlt MULTIPOLYGON `
            -lco GEOMETRY_NAME=geometry `
            -lco FID=id `
            -t_srs EPSG:4326
    } else {
        & $ogr2ogr.Source `
            -f PostgreSQL $pgConn $InputPath $LayerName `
            -nln protected_areas_staging `
            -overwrite `
            -nlt MULTIPOLYGON `
            -lco GEOMETRY_NAME=geometry `
            -lco FID=id `
            -t_srs EPSG:4326
    }

    if ($LASTEXITCODE -ne 0) {
        throw "ogr2ogr import failed with exit code $LASTEXITCODE"
    }

    $createTableSql = @"
CREATE TABLE IF NOT EXISTS protected_areas (
    id BIGSERIAL PRIMARY KEY,
    geometry geometry(MULTIPOLYGON, 4326) NOT NULL,
    name text,
    iucn_category text,
    designation_type text,
    source text,
    last_updated timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);
"@

    $insertSql = @"
DO `$$
DECLARE
    has_name_col BOOLEAN;
    has_iucn_col BOOLEAN;
    has_designation_col BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'protected_areas_staging'
          AND column_name = '$safeNameColumn'
    ) INTO has_name_col;

    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'protected_areas_staging'
          AND column_name = '$safeIucnColumn'
    ) INTO has_iucn_col;

    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'protected_areas_staging'
          AND column_name = '$safeDesignationColumn'
    ) INTO has_designation_col;

    EXECUTE format(
        'INSERT INTO protected_areas (
            geometry,
            name,
            iucn_category,
            designation_type,
            source,
            last_updated
         )
         SELECT
            ST_Multi(ST_CollectionExtract(ST_MakeValid(geometry), 3))::geometry(MULTIPOLYGON, 4326),
            %s,
            %s,
            %s,
            %L,
            CURRENT_TIMESTAMP
         FROM protected_areas_staging
         WHERE geometry IS NOT NULL',
        CASE WHEN has_name_col THEN quote_ident('$safeNameColumn') ELSE 'NULL::text' END,
        CASE WHEN has_iucn_col THEN quote_ident('$safeIucnColumn') ELSE 'NULL::text' END,
        CASE WHEN has_designation_col THEN quote_ident('$safeDesignationColumn') ELSE 'NULL::text' END,
        '$SourceLabel'
    );
END `$$;
"@

    $dropSql = "DROP TABLE IF EXISTS protected_areas_staging;"
    $indexSql = "CREATE INDEX IF NOT EXISTS protected_areas_geom_idx ON protected_areas USING GIST (geometry);"
    $countSql = "SELECT COUNT(*) AS protected_areas_rows FROM protected_areas;"

    if ($useDockerPsql) {
        $dockerArgs = @("exec")
        if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
        $dockerArgs += @($PostgresContainerName, "psql", "-h", $DbHost, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $createTableSql)
        & docker @dockerArgs
        $dockerArgs[-1] = $insertSql
        & docker @dockerArgs
        $dockerArgs[-1] = $dropSql
        & docker @dockerArgs
        $dockerArgs[-1] = $indexSql
        & docker @dockerArgs
        $dockerArgs[-1] = $countSql
        & docker @dockerArgs
    } elseif ($useWslPsql) {
        $wslArgs = @("psql", "-h", $effectiveDbHostForWsl, "-p", "$Port", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c")
        if ($Password) {
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs $createTableSql
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs $insertSql
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs $dropSql
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs $indexSql
            & wsl.exe env "PGPASSWORD=$Password" @wslArgs $countSql
        } else {
            & wsl.exe @wslArgs $createTableSql
            & wsl.exe @wslArgs $insertSql
            & wsl.exe @wslArgs $dropSql
            & wsl.exe @wslArgs $indexSql
            & wsl.exe @wslArgs $countSql
        }
    } else {
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $createTableSql
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $insertSql
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $dropSql
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $indexSql
        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -c $countSql
    }

    if ($LASTEXITCODE -ne 0) {
        throw "Protected areas import failed during database load."
    }

    Write-Host "Protected areas import complete."
}
finally {
    if ($Password) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}
