# MoodRide Data Enrichment 3.0 Plan

## Status

Current production scenic release: `2.9-protected-areas-enrichment`.

Next release target: `3.0-overture-lightpollution-enrichment`.

## Active Ownership

MoodRide's production data pipeline is now script-driven and release-based:

1. Download or import offline geospatial source data.
2. Load source tables/rasters into PostGIS.
3. Run a versioned SQL recompute script.
4. Publish a versioned scenic release artifact.
5. Deploy the release through GitHub Actions.

`route-api`, `route-worker`, and `notification-service` are runtime services. `scenic-scoring-service` remains an internal targeted recompute experiment until its logic is aligned with the release SQL pipeline.

`ingestion-service` and `cdc-service` were moved to the local ignored `legacy/` archive because they are not part of the current production path.

## Why 3.0

The latest evals show that route generation is technically working, but some route choices still need stronger static signals:

- Better solitude and urban/rural separation.
- Better POI quality than raw OSM tags.
- Better support for quiet, countryside, open-road, hidden-gem, and photo-worthy vibes.

3.0 should improve those signals without adding fragile runtime API dependencies.

## Data Sources

### Overture Places

Purpose:

- Improve `poi_score`.
- Add better scenic stops and place-based route explanations.
- Reduce dependence on inconsistent OSM POI tags.

Expected PostGIS table:

```sql
public.overture_places (
    id text,
    category text,
    names jsonb,
    geometry geometry(Point, 4326)
)
```

The import can keep more fields, but the recompute only needs `category` and `geometry`.

The importer also creates:

```sql
public.overture_place_tile_scores (
    h3_index varchar(15),
    overture_poi_score double precision
)
```

### Overture Buildings

Purpose:

- Compute building density.
- Penalize dense urban corridors for quiet/countryside/open-road vibes.
- Improve `solitude_score`.

Raw Overture buildings are too large to keep as a normal runtime dependency. The importer uses staging data to create a compact tile-level table instead:

```sql
public.overture_building_density_tiles (
    h3_index varchar(15),
    building_density_score double precision,
    building_area_m2 double precision,
    tile_area_m2 double precision,
    building_count bigint
)
```

The recompute only reads this tile-level table, not raw building polygons.

### Light Pollution / Nighttime Lights

Purpose:

- Add `darkness_score`.
- Improve remote/quiet/clear-my-head route quality.
- Differentiate rural areas from suburban areas where road density alone is not enough.

Expected PostGIS table:

```sql
public.light_pollution_raster (
    rid serial primary key,
    rast raster
)
```

## New Component Columns

3.0 should add:

```sql
ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS overture_poi_score double precision NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS building_density_score double precision NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS darkness_score double precision NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS urban_penalty_score double precision NOT NULL DEFAULT 0.0;
```

## Scoring Direction

3.0 should not replace the existing scenic foundation. It should refine it:

- `overture_poi_score` should boost `poi_score`, especially scenic categories.
- `building_density_score` should increase `urban_penalty_score`.
- `darkness_score` should improve `solitude_score`.
- `park_score` from 2.9 remains part of the composite score.

Initial composite direction:

```text
water        0.22
green        0.20
elevation    0.14
solitude     0.14
curve        0.10
poi          0.12
park         0.08
urban penalty subtracts up to 0.10
```

This should be tuned from eval results, not treated as final.

## Runtime API Policy

Do not use Overture, Overpass, Mapillary, TomTom, or weather APIs during route generation for base scenic quality.

Runtime route generation should read local PostGIS/Redis data only. Weather and traffic can be added later as optional runtime modifiers after 3.0 static quality is validated.

## Validation

After recompute, run the route-quality eval and compare against the latest baseline:

- Winnipeg quiet/open-roads should have stronger solitude signal.
- Calgary countryside should have stronger rural/low-density signal.
- Banff/Rockies should remain strong.
- Toronto/Vancouver should not become artificially over-boosted just because they have many POIs.
- Route-option spread should improve without breaking time-budget accuracy.

## Release Flow

### 1. Import Overture Places + Buildings

This requires DuckDB CLI because Overture distributes the source data as cloud-hosted GeoParquet.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup\import-overture-v30.ps1 `
  -OutputDir "data/overture/v30" `
  -Release "2026-05-20.0" `
  -ChunkDegrees 5 `
  -DuckDbMemoryLimit "6GB" `
  -DuckDbThreads 2 `
  -PostgresContainerName "moodride-postgres" `
```

If the DuckDB extraction already completed and only the database import needs to resume:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup\import-overture-v30.ps1 `
  -OutputDir "data/overture/v30" `
  -SkipDownload `
  -PostgresContainerName "moodride-postgres"
```

The importer extracts longitude chunks and loads them into staging tables to avoid materializing one very large Canada-wide GeoJSON in memory. If memory is still high, reduce `-ChunkDegrees` to `2.5` or reduce `-DuckDbThreads` to `1`.

Expected tables:

```sql
SELECT COUNT(*) FROM overture_places;
SELECT COUNT(*) FROM overture_place_tile_scores;
SELECT COUNT(*) FROM overture_building_density_tiles;
```

### 2. Import Light Pollution Raster

Download the chosen VIIRS/Falchi GeoTIFF first, then load it:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup\import-light-pollution-v30.ps1 `
  -InputPath "D:\MoodRide\data\light-pollution\light_pollution.tif" `
  -TargetTable "light_pollution_raster" `
  -PostgresContainerName "moodride-postgres" `
  -DockerMemoryLimit "3g"
```

Expected table:

```sql
SELECT COUNT(*) FROM light_pollution_raster;
```

### 3. Run 3.0 Recompute

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v30.sql" `
  -ChunkSize 1000 `
  -PostgresContainerName "moodride-postgres" `
  -ExpectedScoringVersion "3.0-overture-lightpollution-enrichment" *>&1 |
  Tee-Object scenic-recompute-3.0.log
```

### 4. Publish

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.0-overture-lightpollution-enrichment" `
  -ReleaseTag "scenic-3.0-overture-lightpollution-enrichment-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/MoodRide"
```
