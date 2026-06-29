param(
    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$OutputDir = "artifacts/scenic-data-quality",
    [string]$RunLabel = "",
    [switch]$UseDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-PsqlText {
    param([Parameter(Mandatory = $true)][string]$Sql)

    if (-not $UseDocker) {
        $psql = Get-Command psql -ErrorAction SilentlyContinue
        if ($psql) {
            if ($Password) { $env:PGPASSWORD = $Password }
            try {
                $result = & $psql.Source `
                    -h $DbHost `
                    -p $Port `
                    -U $Username `
                    -d $Database `
                    -v ON_ERROR_STOP=1 `
                    -t `
                    -A `
                    -c $Sql
                if ($LASTEXITCODE -ne 0) {
                    throw "psql query failed with exit code $LASTEXITCODE"
                }
                return ($result | Out-String).Trim()
            } finally {
                if ($Password) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
            }
        }
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        throw "Neither local psql nor docker is available. Install psql or rerun with Docker available."
    }

    $dockerArgs = @("exec")
    if ($Password) { $dockerArgs += @("-e", "PGPASSWORD=$Password") }
    $dockerArgs += @(
        $PostgresContainerName,
        "psql",
        "-U", $Username,
        "-d", $Database,
        "-v", "ON_ERROR_STOP=1",
        "-t",
        "-A",
        "-c", $Sql
    )

    $result = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Docker psql query failed with exit code $LASTEXITCODE"
    }
    return ($result | Out-String).Trim()
}

function Invoke-JsonSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $json = Invoke-PsqlText -Sql $Sql
    if ([string]::IsNullOrWhiteSpace($json)) {
        return $null
    }
    return $json | ConvertFrom-Json
}

function Test-Table {
    param([Parameter(Mandatory = $true)][string]$TableName)
    $safeName = $TableName.Replace("'", "''")
    return (Invoke-PsqlText -Sql "SELECT to_regclass('public.$safeName') IS NOT NULL;") -eq "t"
}

function Test-Column {
    param(
        [Parameter(Mandatory = $true)][string]$TableName,
        [Parameter(Mandatory = $true)][string]$ColumnName
    )
    $safeTable = $TableName.Replace("'", "''")
    $safeColumn = $ColumnName.Replace("'", "''")
    return (Invoke-PsqlText -Sql "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$safeTable' AND column_name = '$safeColumn');") -eq "t"
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [Parameter(Mandatory = $true)][string]$Signal,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Evidence,
        [Parameter(Mandatory = $true)][string]$NextStep
    )
    $Checks.Add([pscustomobject]@{
        signal = $Signal
        status = $Status
        evidence = $Evidence
        nextStep = $NextStep
    }) | Out-Null
}

function Format-NullableNumber {
    param($Value, [int]$Decimals = 4)
    if ($null -eq $Value) { return "n/a" }
    return "{0:N$Decimals}" -f [double]$Value
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $repoRoot $OutputDir
}
New-Item -ItemType Directory -Path $resolvedOutputDir -Force | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$label = if ([string]::IsNullOrWhiteSpace($RunLabel)) { "scenic-data-quality-v32-audit-$timestamp" } else { $RunLabel }
$jsonPath = Join-Path $resolvedOutputDir "$label.json"
$mdPath = Join-Path $resolvedOutputDir "$label.md"

$checks = [System.Collections.Generic.List[object]]::new()
$details = [ordered]@{}

Write-Host "Auditing scenic data quality inputs..."

if (Test-Table -TableName "scenic_score_tiles") {
    $details.scenicScoreTiles = Invoke-JsonSql -Sql @"
SELECT jsonb_build_object(
    'tiles', (SELECT COUNT(*) FROM public.scenic_score_tiles),
    'scoring_versions', (
        SELECT COALESCE(jsonb_object_agg(COALESCE(scoring_version, 'null'), version_count), '{}'::jsonb)
        FROM (
            SELECT scoring_version, COUNT(*) AS version_count
            FROM public.scenic_score_tiles
            GROUP BY scoring_version
        ) versions
    ),
    'darkness', jsonb_build_object(
        'min', (SELECT ROUND(MIN(darkness_score)::numeric, 6) FROM public.scenic_score_tiles),
        'avg', (SELECT ROUND(AVG(darkness_score)::numeric, 6) FROM public.scenic_score_tiles),
        'max', (SELECT ROUND(MAX(darkness_score)::numeric, 6) FROM public.scenic_score_tiles),
        'stddev', (SELECT ROUND(STDDEV_POP(darkness_score)::numeric, 6) FROM public.scenic_score_tiles),
        'neutral_share', (SELECT ROUND((COUNT(*) FILTER (WHERE darkness_score = 0.5)::numeric / NULLIF(COUNT(*), 0)), 6) FROM public.scenic_score_tiles)
    ),
    'water', jsonb_build_object(
        'min', (SELECT ROUND(MIN(water_score)::numeric, 6) FROM public.scenic_score_tiles),
        'avg', (SELECT ROUND(AVG(water_score)::numeric, 6) FROM public.scenic_score_tiles),
        'max', (SELECT ROUND(MAX(water_score)::numeric, 6) FROM public.scenic_score_tiles),
        'stddev', (SELECT ROUND(STDDEV_POP(water_score)::numeric, 6) FROM public.scenic_score_tiles)
    ),
    'green', jsonb_build_object(
        'min', (SELECT ROUND(MIN(green_score)::numeric, 6) FROM public.scenic_score_tiles),
        'avg', (SELECT ROUND(AVG(green_score)::numeric, 6) FROM public.scenic_score_tiles),
        'max', (SELECT ROUND(MAX(green_score)::numeric, 6) FROM public.scenic_score_tiles),
        'stddev', (SELECT ROUND(STDDEV_POP(green_score)::numeric, 6) FROM public.scenic_score_tiles)
    )
);
"@
    $darknessStddev = if ($details.scenicScoreTiles.darkness.stddev -ne $null) { [double]$details.scenicScoreTiles.darkness.stddev } else { 0.0 }
    $neutralShare = if ($details.scenicScoreTiles.darkness.neutral_share -ne $null) { [double]$details.scenicScoreTiles.darkness.neutral_share } else { 1.0 }
    if ($darknessStddev -gt 0.01 -and $neutralShare -lt 0.90) {
        Add-Check $checks "darkness/light pollution" "ready" "darkness_score varies across tiles; stddev=$(Format-NullableNumber $details.scenicScoreTiles.darkness.stddev), neutral_share=$(Format-NullableNumber $details.scenicScoreTiles.darkness.neutral_share)." "Use as a real night/quiet/open-road input in a future v3.2 score review."
    } else {
        Add-Check $checks "darkness/light pollution" "needs data QA" "darkness_score appears weak or mostly neutral; stddev=$(Format-NullableNumber $details.scenicScoreTiles.darkness.stddev), neutral_share=$(Format-NullableNumber $details.scenicScoreTiles.darkness.neutral_share)." "Re-run 3.1 with real light-pollution samples before increasing darkness weights."
    }

    if (Test-Column -TableName "scenic_score_tiles" -ColumnName "road_stress_score") {
        $details.roadStressTiles = Invoke-JsonSql -Sql @"
SELECT jsonb_build_object(
    'min', ROUND(MIN(road_stress_score)::numeric, 6),
    'avg', ROUND(AVG(road_stress_score)::numeric, 6),
    'max', ROUND(MAX(road_stress_score)::numeric, 6),
    'stddev', ROUND(STDDEV_POP(road_stress_score)::numeric, 6),
    'non_zero_share', ROUND((COUNT(*) FILTER (WHERE road_stress_score > 0.0)::numeric / NULLIF(COUNT(*), 0)), 6)
)
FROM public.scenic_score_tiles;
"@
        $roadStressStddev = if ($details.roadStressTiles.stddev -ne $null) { [double]$details.roadStressTiles.stddev } else { 0.0 }
        if ($roadStressStddev -gt 0.01) {
            Add-Check $checks "existing road_stress_score" "ready" "road_stress_score varies across tiles; stddev=$(Format-NullableNumber $details.roadStressTiles.stddev), non_zero_share=$(Format-NullableNumber $details.roadStressTiles.non_zero_share)." "Compare route-quality eval warnings before publishing this score to production."
        } else {
            Add-Check $checks "existing road_stress_score" "needs data QA" "road_stress_score exists but appears flat; stddev=$(Format-NullableNumber $details.roadStressTiles.stddev), non_zero_share=$(Format-NullableNumber $details.roadStressTiles.non_zero_share)." "Run data-quality-enrichment-v32.sql or verify road_segments coverage before publishing 3.2."
        }
    } else {
        Add-Check $checks "existing road_stress_score" "missing" "scenic_score_tiles.road_stress_score is not present yet." "Run the V26 schema migration before publishing a 3.2 scenic release."
    }
} else {
    Add-Check $checks "scenic_score_tiles" "blocked" "public.scenic_score_tiles is missing." "Load scenic tiles before running v3.2 data-quality work."
}

if (Test-Table -TableName "light_pollution_tile_samples") {
    $details.lightPollutionSamples = Invoke-JsonSql -Sql "SELECT jsonb_build_object('rows', COUNT(*), 'min_raw', MIN(raw_value), 'max_raw', MAX(raw_value), 'min_darkness', MIN(darkness_score), 'max_darkness', MAX(darkness_score), 'stddev_darkness', STDDEV_POP(darkness_score)) FROM public.light_pollution_tile_samples;"
    Add-Check $checks "light-pollution samples" "present" "$($details.lightPollutionSamples.rows) sampled H3 rows are loaded." "Keep using direct raster sampling for large rasters."
} elseif (Test-Table -TableName "light_pollution_raster") {
    $details.lightPollutionRaster = Invoke-JsonSql -Sql "SELECT jsonb_build_object('rows', COUNT(*)) FROM public.light_pollution_raster;"
    Add-Check $checks "light-pollution raster" "present" "$($details.lightPollutionRaster.rows) raster rows are loaded." "Consider direct sampling to avoid expensive PostGIS raster scans during enrichment."
} else {
    Add-Check $checks "darkness/light pollution source" "missing" "No light_pollution_tile_samples or light_pollution_raster table found." "Import a real nighttime-light raster before publishing a darkness-dependent release."
}

if (Test-Table -TableName "road_segments") {
    $details.roadSegments = Invoke-JsonSql -Sql @"
SELECT jsonb_build_object(
    'rows', COUNT(*),
    'distinct_h3_tiles', COUNT(DISTINCT h3_tile_index),
    'road_type_coverage', ROUND((COUNT(*) FILTER (WHERE road_type IS NOT NULL)::numeric / NULLIF(COUNT(*), 0)), 6),
    'surface_coverage', ROUND((COUNT(*) FILTER (WHERE surface IS NOT NULL)::numeric / NULLIF(COUNT(*), 0)), 6),
    'speed_limit_coverage', ROUND((COUNT(*) FILTER (WHERE speed_limit_kmh IS NOT NULL)::numeric / NULLIF(COUNT(*), 0)), 6),
    'major_road_share', ROUND((COUNT(*) FILTER (WHERE road_type IN ('motorway', 'trunk', 'primary'))::numeric / NULLIF(COUNT(*), 0)), 6),
    'local_road_share', ROUND((COUNT(*) FILTER (WHERE road_type IN ('residential', 'service', 'living_street'))::numeric / NULLIF(COUNT(*), 0)), 6),
    'top_road_types', (
        SELECT jsonb_object_agg(road_type, road_count)
        FROM (
            SELECT COALESCE(road_type, 'unknown') AS road_type, COUNT(*) AS road_count
            FROM public.road_segments
            GROUP BY COALESCE(road_type, 'unknown')
            ORDER BY road_count DESC
            LIMIT 12
        ) t
    )
)
FROM public.road_segments;
"@
    Add-Check $checks "road stress / road class" "candidate" "road_segments has $($details.roadSegments.rows) rows; road_type coverage=$(Format-NullableNumber $details.roadSegments.road_type_coverage), major_share=$(Format-NullableNumber $details.roadSegments.major_road_share)." "Derive a tile-level road_stress_score from road class, surface, speed, density, and major-road share."
} else {
    Add-Check $checks "road stress / road class" "blocked" "public.road_segments is missing." "Load OSM road segments before road-stress scoring."
}

if (Test-Table -TableName "planet_osm_point") {
    if (Test-Column -TableName "planet_osm_point" -ColumnName "tags") {
        $details.viewpointPois = Invoke-JsonSql -Sql @"
SELECT jsonb_build_object(
    'viewpoints', COUNT(*) FILTER (WHERE tags ? 'tourism' AND tags->'tourism' = 'viewpoint'),
    'peaks', COUNT(*) FILTER (WHERE tags ? 'natural' AND tags->'natural' = 'peak'),
    'springs', COUNT(*) FILTER (WHERE tags ? 'natural' AND tags->'natural' = 'spring'),
    'tourism_points', COUNT(*) FILTER (WHERE tags ? 'tourism')
)
FROM public.planet_osm_point;
"@
        $viewpointCount = [int]$details.viewpointPois.viewpoints + [int]$details.viewpointPois.peaks
        if ($viewpointCount -gt 0) {
            Add-Check $checks "scenic viewpoints" "candidate" "OSM points include $($details.viewpointPois.viewpoints) viewpoints and $($details.viewpointPois.peaks) peaks." "Promote viewpoint/peak proximity into photo_peak_score or a dedicated viewpoint_score."
        } else {
            Add-Check $checks "scenic viewpoints" "needs data QA" "planet_osm_point exists, but no viewpoint/peak tags were found." "Confirm OSM import includes hstore tags and region coverage."
        }
    } else {
        Add-Check $checks "scenic viewpoints" "blocked" "planet_osm_point exists but does not expose a tags column." "Import OSM with tags/hstore support before viewpoint scoring."
    }
} else {
    Add-Check $checks "scenic viewpoints" "missing" "public.planet_osm_point is missing." "Import OSM point features before viewpoint scoring."
}

if (Test-Table -TableName "planet_osm_line") {
    if (Test-Column -TableName "planet_osm_line" -ColumnName "tags") {
        $details.osmLineTags = Invoke-JsonSql -Sql @"
SELECT jsonb_build_object(
    'bridge_lines', COUNT(*) FILTER (WHERE tags ? 'bridge' AND COALESCE(tags->'bridge', '') NOT IN ('', 'no')),
    'tunnel_lines', COUNT(*) FILTER (WHERE tags ? 'tunnel' AND COALESCE(tags->'tunnel', '') NOT IN ('', 'no')),
    'seasonal_lines', COUNT(*) FILTER (
        WHERE (tags ? 'seasonal' AND COALESCE(tags->'seasonal', '') NOT IN ('', 'no'))
           OR (tags ? 'winter_road')
           OR (tags ? 'ice_road')
           OR (tags ? 'access:conditional')
    ),
    'coastal_hint_lines', COUNT(*) FILTER (
        WHERE (tags ? 'bridge' AND COALESCE(tags->'bridge', '') NOT IN ('', 'no'))
           OR (tags ? 'waterway')
           OR (tags ? 'ford')
    )
)
FROM public.planet_osm_line;
"@
        Add-Check $checks "bridge/coastal-road detection" "candidate" "OSM line tags include $($details.osmLineTags.bridge_lines) bridge lines and $($details.osmLineTags.coastal_hint_lines) coastal/water crossing hints." "Build a bridge/coastal-road tile signal by intersecting OSM roads with water geometry and bridge tags."
        Add-Check $checks "seasonal suitability" "candidate" "OSM line tags include $($details.osmLineTags.seasonal_lines) seasonal/access-conditional hints." "Start as warnings/metadata; do not hard-block until seasonal tags are validated by region."
    } else {
        Add-Check $checks "bridge/coastal-road detection" "blocked" "planet_osm_line exists but does not expose a tags column." "Import OSM with line tags/hstore support before bridge and seasonal scoring."
        Add-Check $checks "seasonal suitability" "blocked" "planet_osm_line exists but does not expose a tags column." "Import OSM with line tags/hstore support before seasonal scoring."
    }
} else {
    Add-Check $checks "bridge/coastal-road detection" "missing" "public.planet_osm_line is missing." "Import OSM line features before bridge/coastal detection."
    Add-Check $checks "seasonal suitability" "missing" "public.planet_osm_line is missing." "Import OSM line features before seasonal suitability scoring."
}

$hasNaturalEarthWater = (Invoke-PsqlText -Sql 'SELECT to_regclass(''public."natural_earth_Water_Bodies"'') IS NOT NULL;') -eq "t"
if ($hasNaturalEarthWater) {
    $details.waterBodies = Invoke-JsonSql -Sql 'SELECT jsonb_build_object(''rows'', COUNT(*)) FROM public."natural_earth_Water_Bodies";'
    if ([int64]$details.waterBodies.rows -gt 0) {
        Add-Check $checks "water visibility" "partial" "Natural Earth water geometry is present with $($details.waterBodies.rows) rows; current scoring mainly uses proximity." "Upgrade from proximity to water-adjacent-road share, bridge/water crossing hints, and road-water parallelism."
    } else {
        Add-Check $checks "water visibility" "needs data QA" "Natural Earth water geometry table exists, but it has 0 rows." "Re-import water geometry before building water visibility or coastal-road scoring."
    }
} else {
    Add-Check $checks "water visibility" "blocked" "Natural Earth water geometry table is missing." "Import water geometry before water visibility scoring."
}

if (Test-Table -TableName "landcover_raster") {
    $details.landcover = Invoke-JsonSql -Sql "SELECT jsonb_build_object('raster_rows', COUNT(*), 'class_weights_present', to_regclass('public.landcover_class_weights') IS NOT NULL) FROM public.landcover_raster;"
    Add-Check $checks "tree canopy" "partial" "landcover_raster is present; this supports broad green_score but not true canopy density." "Use land-cover forest classes as a first canopy proxy; add a real canopy raster later if available."
} elseif (Test-Table -TableName "nlcd_land_cover_cells") {
    $details.landcoverCells = Invoke-JsonSql -Sql "SELECT jsonb_build_object('rows', COUNT(*), 'classes', COUNT(DISTINCT nlcd_class)) FROM public.nlcd_land_cover_cells;"
    Add-Check $checks "tree canopy" "partial" "nlcd_land_cover_cells has $($details.landcoverCells.rows) rows across $($details.landcoverCells.classes) classes." "Map forest/tree classes into canopy_score as a first release."
} else {
    Add-Check $checks "tree canopy" "missing" "No landcover_raster or nlcd_land_cover_cells table found." "Import land-cover or canopy data before building canopy_score."
}

if (Test-Table -TableName "protected_areas") {
    $details.protectedAreas = Invoke-JsonSql -Sql "SELECT jsonb_build_object('rows', COUNT(*)) FROM public.protected_areas;"
    Add-Check $checks "parks/protected context" "ready" "protected_areas has $($details.protectedAreas.rows) rows." "Keep using park_score as a stable support signal for green/quiet routes."
} else {
    Add-Check $checks "parks/protected context" "missing" "protected_areas is missing." "Import CPCAD/protected areas before park-sensitive scoring."
}

$report = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    database = $Database
    host = if ($UseDocker) { "docker:$PostgresContainerName" } else { "$DbHost`:$Port" }
    purpose = "Read-only v3.2 scenic data-quality readiness audit"
    checks = $checks
    details = $details
}

$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# Scenic Data Quality v3.2 Audit") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("Generated: $($report.generatedAt)") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("This is a read-only audit. It does not change route scoring or scenic tile values.") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("## Checks") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("| Signal | Status | Evidence | Next step |") | Out-Null
$lines.Add("| --- | --- | --- | --- |") | Out-Null
foreach ($check in $checks) {
    $signal = ($check.signal -replace '\|', '/')
    $status = ($check.status -replace '\|', '/')
    $evidence = ($check.evidence -replace '\|', '/')
    $nextStep = ($check.nextStep -replace '\|', '/')
    $lines.Add("| $signal | $status | $evidence | $nextStep |") | Out-Null
}
$lines.Add("") | Out-Null
$lines.Add("## Recommended v3.2 Order") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("1. Road stress / road class: usually available from road_segments and directly improves drive feel.") | Out-Null
$lines.Add("2. Water visibility: upgrade coastal scoring from nearby water to roads that actually run near, cross, or parallel water.") | Out-Null
$lines.Add("3. Scenic viewpoints: promote OSM viewpoints/peaks into photo-worthy/date-night route signals.") | Out-Null
$lines.Add("4. Tree canopy: start with land-cover forest classes, then replace with a real canopy raster when one is imported.") | Out-Null
$lines.Add("5. Seasonal suitability: keep as warning/metadata until OSM tag quality is proven region by region.") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("JSON details: $jsonPath") | Out-Null
$lines | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Host "Wrote audit JSON: $jsonPath"
Write-Host "Wrote audit report: $mdPath"
