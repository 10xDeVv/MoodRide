param(
    [string]$LandCoverInputPath = "C:\Users\aadeb\OneDrive\Desktop\MoodRide\data\Canada Land Cover\landcover-2020-classification.tif",
    [string]$ElevationInputPath,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,

    [string]$LandCoverTable = "landcover_raster",
    [string]$ElevationTable = "elevation_raster",
    [switch]$SkipLandCoverImport,
    [switch]$SkipElevationImport,

    [string]$PostgresContainerName = "moodride-postgres",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Path $MyInvocation.MyCommand.Path -Parent
$rasterImportScript = Join-Path $scriptRoot "import-raster-to-postgis.ps1"
$upgradeSqlPath = Join-Path $scriptRoot "data-quality-upgrade.sql"

function Invoke-Or-Preview {
    param(
        [string]$Description,
        [string]$CommandText,
        [scriptblock]$Action
    )

    Write-Host "`n==> $Description"
    if ($DryRun) {
        Write-Host "[dry-run] $CommandText" -ForegroundColor Yellow
        return
    }

    & $Action
}

function Assert-FileExists {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -Path $Path -PathType Leaf)) {
        throw "$Label file not found: $Path"
    }
}

function Invoke-PostgresSqlFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SqlFilePath
    )

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    $docker = Get-Command docker -ErrorAction SilentlyContinue

    if ($psql) {
        if ($Password) {
            $env:PGPASSWORD = $Password
        }

        try {
            & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -f $SqlFilePath
            if ($LASTEXITCODE -ne 0) {
                throw "psql execution failed with exit code $LASTEXITCODE"
            }
            return
        } finally {
            if ($Password) {
                Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
            }
        }
    }

    if (-not $docker) {
        throw "psql is not available and docker fallback is not available."
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
        $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-f", $tempSqlInContainer)
        & docker @dockerArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Docker psql execution failed with exit code $LASTEXITCODE"
        }
    } finally {
        docker exec "$PostgresContainerName" sh -lc "rm -f '$tempSqlInContainer'" | Out-Null
    }
}

if (-not (Test-Path -Path $rasterImportScript -PathType Leaf)) {
    throw "Required script missing: $rasterImportScript"
}
if (-not (Test-Path -Path $upgradeSqlPath -PathType Leaf)) {
    throw "Required SQL script missing: $upgradeSqlPath"
}

if (-not $SkipLandCoverImport) {
    Assert-FileExists -Path $LandCoverInputPath -Label "Land cover raster"
    $cmd = "& `"$rasterImportScript`" -InputPath `"$LandCoverInputPath`" -TargetTable `"$LandCoverTable`""
    Invoke-Or-Preview `
        -Description "Import land cover raster into PostGIS" `
        -CommandText $cmd `
        -Action {
            $args = @{
                InputPath = $LandCoverInputPath
                TargetTable = $LandCoverTable
                Database = $Database
                Username = $Username
                DbHost = $DbHost
                Port = $Port
                PostgresContainerName = $PostgresContainerName
            }
            if ($Password) { $args.Password = $Password }
            & $rasterImportScript @args
        }
} else {
    Write-Host "Skipping land cover raster import by request." -ForegroundColor DarkYellow
}

if (-not $SkipElevationImport) {
    Assert-FileExists -Path $ElevationInputPath -Label "Elevation raster"
    $cmd = "& `"$rasterImportScript`" -InputPath `"$ElevationInputPath`" -TargetTable `"$ElevationTable`""
    Invoke-Or-Preview `
        -Description "Import elevation raster into PostGIS" `
        -CommandText $cmd `
        -Action {
            $args = @{
                InputPath = $ElevationInputPath
                TargetTable = $ElevationTable
                Database = $Database
                Username = $Username
                DbHost = $DbHost
                Port = $Port
                PostgresContainerName = $PostgresContainerName
            }
            if ($Password) { $args.Password = $Password }
            & $rasterImportScript @args
        }
} else {
    Write-Host "Skipping elevation raster import by request." -ForegroundColor DarkYellow
}

Invoke-Or-Preview `
    -Description "Run data quality upgrade scoring SQL" `
    -CommandText ("psql -d {0} -f `"{1}`"" -f $Database, $upgradeSqlPath) `
    -Action {
        Invoke-PostgresSqlFile -SqlFilePath $upgradeSqlPath
    }

Write-Host "`nData quality upgrade run complete."
