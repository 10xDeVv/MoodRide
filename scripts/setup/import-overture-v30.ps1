param(
    [string]$OutputDir = "data/overture/v30",
    [string]$Release = "2026-05-20.0",

    [double]$MinLon = -141.0,
    [double]$MinLat = 41.0,
    [double]$MaxLon = -52.0,
    [double]$MaxLat = 84.0,
    [double]$ChunkDegrees = 5.0,

    [string]$DuckDbMemoryLimit = "6GB",
    [int]$DuckDbThreads = 2,

    [switch]$SkipDownload,
    [switch]$SkipImport,
    [switch]$NormalizeOnly,
    [switch]$KeepChunkFiles,
    [switch]$DropStagingAfterNormalize,
    [switch]$UnloggedStaging,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Invoke-PsqlSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            Invoke-Checked -FailureMessage "psql command failed." -Command {
                & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $Sql
            }
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
        return
    }

    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $Sql)
    Invoke-Checked -FailureMessage "Docker psql command failed." -Command {
        & docker @dockerArgs
    }
}

function Import-CsvToTable {
    param(
        [Parameter(Mandatory = $true)][string]$CsvPath,
        [Parameter(Mandatory = $true)][string]$TableName
    )

    if (-not (Test-Path -LiteralPath $CsvPath -PathType Leaf)) {
        throw "CSV chunk not found: $CsvPath"
    }

    $lineCount = (Get-Content -LiteralPath $CsvPath -TotalCount 2 | Measure-Object -Line).Lines
    if ($lineCount -lt 2) {
        Write-Host "Skipping empty chunk: $CsvPath"
        return
    }

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) {
        $escapedPath = $CsvPath.Replace("\", "\\").Replace("'", "''")
        $copySql = "\copy public.$TableName FROM '$escapedPath' WITH (FORMAT csv, HEADER true)"
        if ($Password) { $env:PGPASSWORD = $Password }
        try {
            Invoke-Checked -FailureMessage "psql CSV import failed for $TableName." -Command {
                & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $copySql
            }
        } finally {
            if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        }
        return
    }

    $remotePath = "/tmp/$([IO.Path]::GetFileName($CsvPath))"
    Invoke-Checked -FailureMessage "docker cp failed for $CsvPath." -Command {
        & docker cp $CsvPath "${PostgresContainerName}:$remotePath"
    }

    try {
        $copySql = "COPY public.$TableName FROM '$remotePath' WITH (FORMAT csv, HEADER true);"
        Invoke-PsqlSql -Sql $copySql
    } finally {
        & docker exec $PostgresContainerName sh -lc "rm -f '$remotePath'" | Out-Null
    }
}

function Invoke-DuckDbExtract {
    param([Parameter(Mandatory = $true)][string]$SqlPath)

    $duckdb = Get-Command duckdb -ErrorAction SilentlyContinue
    if (-not $duckdb) {
        throw "DuckDB CLI is required for Overture extraction. Install DuckDB, then rerun this script."
    }

    Invoke-Checked -FailureMessage "DuckDB Overture extraction failed." -Command {
        & $duckdb.Source -c ".read '$SqlPath'"
    }
}

function Write-ChunkSql {
    param(
        [Parameter(Mandatory = $true)][string]$SqlPath,
        [Parameter(Mandatory = $true)][double]$ChunkMinLon,
        [Parameter(Mandatory = $true)][double]$ChunkMaxLon,
        [Parameter(Mandatory = $true)][string]$PlacesCsv,
        [Parameter(Mandatory = $true)][string]$BuildingsCsv
    )

    $placesCsvSql = $PlacesCsv.Replace("\", "/").Replace("'", "''")
    $buildingsCsvSql = $BuildingsCsv.Replace("\", "/").Replace("'", "''")
    $tempDirSql = $duckDbTempDir.Replace("\", "/").Replace("'", "''")

    $sql = @"
INSTALL spatial;
INSTALL httpfs;
LOAD spatial;
LOAD httpfs;
SET s3_region='us-west-2';
SET memory_limit='$DuckDbMemoryLimit';
SET threads=$DuckDbThreads;
SET preserve_insertion_order=false;
SET temp_directory='$tempDirSql';
SET geometry_always_xy=true;

COPY (
    SELECT
        id,
        names.primary AS name,
        categories.primary AS category,
        confidence,
        ST_AsText(geometry) AS wkt
    FROM read_parquet(
        's3://overturemaps-us-west-2/release/$Release/theme=places/type=place/*',
        filename=true,
        hive_partitioning=1
    )
    WHERE bbox.xmax >= $ChunkMinLon
      AND bbox.xmin < $ChunkMaxLon
      AND bbox.ymax >= $MinLat
      AND bbox.ymin <= $MaxLat
      AND (confidence IS NULL OR confidence >= 0.60)
) TO '$placesCsvSql' WITH (FORMAT CSV, HEADER true);

COPY (
    SELECT
        id,
        subtype,
        "class" AS building_class,
        height,
        num_floors,
        ST_X(ST_Centroid(geometry)) AS lon,
        ST_Y(ST_Centroid(geometry)) AS lat
    FROM read_parquet(
        's3://overturemaps-us-west-2/release/$Release/theme=buildings/type=building/*',
        filename=true,
        hive_partitioning=1
    )
    WHERE bbox.xmax >= $ChunkMinLon
      AND bbox.xmin < $ChunkMaxLon
      AND bbox.ymax >= $MinLat
      AND bbox.ymin <= $MaxLat
) TO '$buildingsCsvSql' WITH (FORMAT CSV, HEADER true);
"@

    Set-Content -LiteralPath $SqlPath -Value $sql -Encoding UTF8
}

$resolvedOutputDir = Join-Path (Resolve-Path ".").Path $OutputDir
$chunkDir = Join-Path $resolvedOutputDir "chunks"
$duckDbTempDir = Join-Path $resolvedOutputDir "duckdb-temp"
New-Item -ItemType Directory -Force -Path $resolvedOutputDir, $chunkDir, $duckDbTempDir | Out-Null

$stagingTableKind = if ($UnloggedStaging) { "UNLOGGED TABLE" } else { "TABLE" }

$createStagingSql = @"
DROP TABLE IF EXISTS public.overture_places_staging;
DROP TABLE IF EXISTS public.overture_buildings_staging;

CREATE $stagingTableKind public.overture_places_staging (
    id text,
    name text,
    category text,
    confidence double precision,
    wkt text
);

CREATE $stagingTableKind public.overture_buildings_staging (
    id text,
    subtype text,
    building_class text,
    height double precision,
    num_floors integer,
    lon double precision,
    lat double precision
);
"@

if (-not $SkipImport -and -not $NormalizeOnly) {
    Write-Host "Preparing Overture staging tables..."
    Invoke-PsqlSql -Sql $createStagingSql
}

$chunkIndex = 0
if ($NormalizeOnly) {
    Write-Host "NormalizeOnly set; reusing existing Overture staging tables."
} else {
    for ($chunkMin = $MinLon; $chunkMin -lt $MaxLon; $chunkMin += $ChunkDegrees) {
        $chunkIndex++
        $chunkMax = [Math]::Min($chunkMin + $ChunkDegrees, $MaxLon)
        $chunkLabel = "{0:D3}_{1}_{2}" -f $chunkIndex, ([Math]::Round($chunkMin, 2).ToString().Replace("-", "m").Replace(".", "p")), ([Math]::Round($chunkMax, 2).ToString().Replace("-", "m").Replace(".", "p"))
        $placesCsv = Join-Path $chunkDir "overture_places_$chunkLabel.csv"
        $buildingsCsv = Join-Path $chunkDir "overture_buildings_$chunkLabel.csv"
        $sqlPath = Join-Path $chunkDir "extract_$chunkLabel.duckdb.sql"

        if (-not $SkipDownload) {
            Write-Host "Extracting Overture chunk $chunkIndex [$chunkMin, $chunkMax)..."
            Write-ChunkSql -SqlPath $sqlPath -ChunkMinLon $chunkMin -ChunkMaxLon $chunkMax -PlacesCsv $placesCsv -BuildingsCsv $buildingsCsv
            Invoke-DuckDbExtract -SqlPath $sqlPath
        } else {
            Write-Host "Skipping extraction for chunk $chunkIndex [$chunkMin, $chunkMax)."
        }

        if (-not $SkipImport) {
            Write-Host "Importing Overture chunk $chunkIndex into staging..."
            Import-CsvToTable -CsvPath $placesCsv -TableName "overture_places_staging"
            Import-CsvToTable -CsvPath $buildingsCsv -TableName "overture_buildings_staging"
        }

        if (-not $KeepChunkFiles -and -not $SkipDownload) {
            Remove-Item -LiteralPath $placesCsv, $buildingsCsv, $sqlPath -Force -ErrorAction SilentlyContinue
        }
    }
}

if ($SkipImport) {
    Write-Host "Skipping PostGIS normalization."
    return
}

$normalizeSql = @"
DO `$`$
DECLARE
    places_staged bigint;
    buildings_staged bigint;
BEGIN
    SELECT COUNT(*) INTO places_staged FROM public.overture_places_staging;
    SELECT COUNT(*) INTO buildings_staged FROM public.overture_buildings_staging;

    IF places_staged = 0 OR buildings_staged = 0 THEN
        RAISE EXCEPTION 'Overture staging is empty: places=%, buildings=%', places_staged, buildings_staged;
    END IF;
END
`$`$;

DROP TABLE IF EXISTS public.overture_places;
CREATE TABLE public.overture_places (
    id text PRIMARY KEY,
    name text,
    category text,
    names jsonb,
    confidence double precision,
    geometry geometry(Point, 4326) NOT NULL,
    imported_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO public.overture_places (id, name, category, names, confidence, geometry)
SELECT DISTINCT ON (id)
    id,
    NULLIF(name, ''),
    NULLIF(category, ''),
    jsonb_build_object('primary', NULLIF(name, '')),
    confidence,
    ST_SetSRID(ST_GeomFromText(wkt), 4326)::geometry(Point, 4326)
FROM public.overture_places_staging
WHERE id IS NOT NULL
  AND wkt IS NOT NULL
  AND wkt <> '';

CREATE INDEX IF NOT EXISTS overture_places_geom_idx
    ON public.overture_places USING GIST (geometry);
CREATE INDEX IF NOT EXISTS overture_places_category_idx
    ON public.overture_places (category);
ANALYZE public.overture_places;

DROP TABLE IF EXISTS public.overture_place_tile_scores;
CREATE TABLE public.overture_place_tile_scores AS
SELECT
    sst.h3_index,
    LEAST(
        1.0,
        LN(1.0 + COALESCE(SUM(
            CASE
                WHEN op.id IS NULL THEN 0.0
                WHEN lower(COALESCE(op.category, '')) ~
                    '(park|view|trail|beach|waterfall|lookout|historic|landmark|nature|garden|camp|museum|tourist|attraction|scenic)'
                THEN 3.0
                ELSE 1.0
            END
        ), 0.0)) / LN(18.0)
    ) AS overture_poi_score
FROM public.scenic_score_tiles sst
LEFT JOIN public.overture_places op
  ON op.geometry && sst.geometry
 AND ST_Intersects(op.geometry, sst.geometry)
GROUP BY sst.h3_index;

CREATE UNIQUE INDEX IF NOT EXISTS overture_place_tile_scores_h3_idx
    ON public.overture_place_tile_scores (h3_index);
ANALYZE public.overture_place_tile_scores;

DROP TABLE IF EXISTS public.overture_building_centroids;
CREATE TABLE public.overture_building_centroids AS
SELECT
    id,
    ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geometry(Point, 4326) AS geometry
FROM public.overture_buildings_staging
WHERE lon IS NOT NULL
  AND lat IS NOT NULL;

CREATE INDEX IF NOT EXISTS overture_building_centroids_geom_idx
    ON public.overture_building_centroids USING GIST (geometry);
ANALYZE public.overture_building_centroids;

DROP TABLE IF EXISTS public.overture_building_density_tiles;
CREATE TABLE public.overture_building_density_tiles (
    h3_index varchar(15) PRIMARY KEY,
    building_density_score double precision NOT NULL,
    building_area_m2 double precision NOT NULL,
    tile_area_m2 double precision NOT NULL,
    building_count bigint NOT NULL
);

WITH tile_metrics AS MATERIALIZED (
    SELECT
        h3_index,
        geometry,
        ST_Transform(geometry, 3979) AS geom_3979,
        NULLIF(ST_Area(ST_Transform(geometry, 3979)), 0.0) AS tile_area_m2
    FROM public.scenic_score_tiles
),
building_metrics AS (
    SELECT
        tm.h3_index,
        COUNT(*) AS building_count,
        0.0 AS building_area_m2,
        MAX(tm.tile_area_m2) AS tile_area_m2
    FROM tile_metrics tm
    JOIN public.overture_building_centroids obc
      ON obc.geometry && tm.geometry
     AND ST_Intersects(obc.geometry, tm.geometry)
    GROUP BY tm.h3_index
)
INSERT INTO public.overture_building_density_tiles (
    h3_index,
    building_density_score,
    building_area_m2,
    tile_area_m2,
    building_count
)
SELECT
    tm.h3_index,
    LEAST(1.0, LN(1.0 + COALESCE(bm.building_count, 0)) / LN(250.0)) AS building_density_score,
    COALESCE(bm.building_area_m2, 0.0) AS building_area_m2,
    COALESCE(tm.tile_area_m2, 0.0) AS tile_area_m2,
    COALESCE(bm.building_count, 0) AS building_count
FROM tile_metrics tm
LEFT JOIN building_metrics bm
  ON bm.h3_index = tm.h3_index;

DROP TABLE IF EXISTS public.overture_building_centroids;

ANALYZE public.overture_building_density_tiles;

SELECT 'overture_places' AS table_name, COUNT(*) AS rows FROM public.overture_places
UNION ALL
SELECT 'overture_place_tile_scores' AS table_name, COUNT(*) AS rows FROM public.overture_place_tile_scores
UNION ALL
SELECT 'overture_building_density_tiles' AS table_name, COUNT(*) AS rows FROM public.overture_building_density_tiles;
"@

Write-Host "Normalizing Overture tables..."
Invoke-PsqlSql -Sql $normalizeSql

if ($DropStagingAfterNormalize) {
    Write-Host "Dropping Overture staging tables..."
    Invoke-PsqlSql -Sql @"
DROP TABLE IF EXISTS public.overture_places_staging;
DROP TABLE IF EXISTS public.overture_buildings_staging;
"@
}

Write-Host "Overture v3.0 import complete."
