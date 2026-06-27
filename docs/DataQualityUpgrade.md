# Wayward Data Quality Upgrade

Status: completed and deployed to production on 2026-05-18.

Historical scope note: this document records the completed 2.8 land-cover/DEM calibration release. It is not the latest overall scenic-enrichment status; see `docs/DataEnrichment30Plan.md` for the later 2.9 protected-area and 3.0 Overture/light-pollution enrichment state.

This document records the completed core data upgrade for scenic routing. The original plan was to replace weak OSM-only scenic components with real raster-backed data for land cover and elevation. That work is now complete for the current national Canada operating dataset.

## Final Production State

| Item | Status | Result |
|---|---|---|
| Canada OSRM coverage | Complete | `canada-latest` deployed in production |
| Land cover raster | Complete | `landcover_raster`, SRID `3979`, spatial index present |
| Copernicus DEM raster | Complete | `elevation_raster`, SRID `4326`, `310,900` raster tiles |
| DEM coverage | Complete | lon `-141` to `-51`, lat `41` to `84` |
| Scenic recompute | Complete | `211,510` tiles at `2.8-urban-aware-elevation-calibration` |
| Scenic release deploy | Complete | GitHub Actions deploy succeeded |
| Production verification | Complete | Prod DB has `211,510` rows at `2.8`; API smoke tests passed |

## What Improved

| Component | Previous quality | Current source | Current quality |
|---|---|---|---|
| `green_score` | OSM tag dependent, sparse | Canada land cover raster | National raster-backed greenery |
| `solitude_score` | Mostly road-density driven | Land cover urban proportion + road density | Distinguishes wild/forest roads from developed areas |
| `elevation_score` | Sparse/weak OSM contour signal | Copernicus GLO-30 DEM | Real terrain variance where DEM has meaningful relief |
| `scenic_score` | Composite over weak components | Recomputed from upgraded components | Better variance and stronger route preference signal |

Existing `water_score`, `curve_score`, and `poi_score` were retained.

## Current 2.8 Metrics

`2.8` was the production calibration release for the core land-cover/DEM upgrade. It keeps the completed national DEM coverage from `2.7`, corrects the NALCMS land-cover class legend, recomputes `green_score` and `solitude_score`, and downweights DEM surface-model elevation where land cover is urban/built-up.

```text
scoring_version: 2.8-urban-aware-elevation-calibration
tile_count: 211510
green_non_zero_tiles: 189796
solitude_non_zero_tiles: 211510
elevation_non_zero_tiles: 122173
avg_green_score: 0.486400
stddev_green_score: 0.294640
avg_elevation_score: 0.497642
stddev_elevation_score: 0.457073
avg_scenic_score: 0.508183
stddev_scenic_score: 0.199907
min_scenic_score: 0.041511627906976746
max_scenic_score: 0.9218142190162193
```

Production DB verification:

```sql
SELECT scoring_version, COUNT(*)
FROM scenic_score_tiles
GROUP BY scoring_version
ORDER BY COUNT(*) DESC;
```

Expected:

```text
2.8-urban-aware-elevation-calibration | 211510
```

## Historical 2.7 Metrics

Local recompute and production deploy completed with:

```text
scoring_version: 2.7-raster-data-quality-upgrade-national-batched
tile_count: 211510
green_non_zero_tiles: 187904
solitude_non_zero_tiles: 211510
elevation_non_zero_tiles: 122173
avg_green_score: 0.330282
stddev_green_score: 0.241962
avg_elevation_score: 0.550105
stddev_elevation_score: 0.492935
avg_scenic_score: 0.491143
stddev_scenic_score: 0.201429
min_scenic_score: 0.034941860465116284
max_scenic_score: 0.9252447761002579
```

Production DB verification:

```sql
SELECT scoring_version, COUNT(*)
FROM scenic_score_tiles
GROUP BY scoring_version
ORDER BY COUNT(*) DESC;
```

Historical expected result:

```text
2.7-raster-data-quality-upgrade-national-batched | 211510
```

## DEM Import Runbook

The national DEM import is resumable by region. It uses per-tile imports to avoid high memory pressure on Docker Desktop.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup\run-regional-dem-resume.ps1 `
  -RegionName prairies `
  -ImportMode PerTile `
  -DockerImportMemoryLimit 1800m

powershell -ExecutionPolicy Bypass -File .\scripts\setup\run-regional-dem-resume.ps1 `
  -RegionName central `
  -ImportMode PerTile `
  -DockerImportMemoryLimit 1800m

powershell -ExecutionPolicy Bypass -File .\scripts\setup\run-regional-dem-resume.ps1 `
  -RegionName atlantic `
  -ImportMode PerTile `
  -DockerImportMemoryLimit 1800m
```

After DEM import, rebuild one clean raster spatial index and analyze:

```powershell
docker exec moodride-postgres psql -U postgres -d moodride -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS elevation_raster_st_convexhull_idx ON public.elevation_raster USING gist (ST_ConvexHull(rast));"
docker exec moodride-postgres psql -U postgres -d moodride -v ON_ERROR_STOP=1 -c "VACUUM ANALYZE public.elevation_raster;"
```

Verify DEM coverage:

```powershell
docker exec moodride-postgres psql -U postgres -d moodride -c "SELECT COUNT(*) AS raster_tiles, MIN(ST_XMin(ST_Envelope(rast))) AS min_lon, MAX(ST_XMax(ST_Envelope(rast))) AS max_lon, MIN(ST_YMin(ST_Envelope(rast))) AS min_lat, MAX(ST_YMax(ST_Envelope(rast))) AS max_lat FROM elevation_raster;"
```

Expected current result:

```text
raster_tiles: 310900
min_lon: -141.00027777777777
max_lon: -51.000277777777775
min_lat: 41.000138888888884
max_lat: 84.00013888888888
```

## Scenic Recompute Runbook

Run the national `2.7` recompute after DEM import:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-upgrade-batched-v27.sql" `
  -ChunkSize 2000 `
  -PostgresContainerName "moodride-postgres" `
  -ExpectedScoringVersion "2.7-raster-data-quality-upgrade-national-batched" *>&1 |
  Tee-Object scenic-recompute-2.7.log
```

The SQL is resumable. It targets only rows not already at `2.7-raster-data-quality-upgrade-national-batched`.

Publish a release artifact:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "2.7-raster-data-quality-upgrade-national-batched" `
  -ReleaseTag "scenic-2.7-raster-data-quality-upgrade-national-batched-20260518-1311" `
  -Repo "10xDeVv/MoodRide"
```

Deploy it to production:

```powershell
gh workflow run "Deploy Scenic Release" --repo 10xDeVv/MoodRide `
  -f release_tag=scenic-2.7-raster-data-quality-upgrade-national-batched-20260518-1311 `
  -f scoring_version=2.7-raster-data-quality-upgrade-national-batched

gh run watch --repo 10xDeVv/MoodRide
```

Run the current `2.8` calibration release after `2.7`:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-calibration-v28.sql" `
  -ChunkSize 5000 `
  -PostgresContainerName "moodride-postgres" `
  -ExpectedScoringVersion "2.8-urban-aware-elevation-calibration"
```

Publish and deploy:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "2.8-urban-aware-elevation-calibration" `
  -ReleaseTag "scenic-2.8-urban-aware-elevation-calibration-20260518-1406" `
  -Repo "10xDeVv/MoodRide"

gh workflow run "Deploy Scenic Release" --repo 10xDeVv/MoodRide `
  -f release_tag=scenic-2.8-urban-aware-elevation-calibration-20260518-1406 `
  -f scoring_version=2.8-urban-aware-elevation-calibration
```

## Implementation Notes

- `scripts/setup/import-raster-to-postgis.ps1` skips per-tile `-I`, `-C`, and `-M` in append mode. This prevents duplicate spatial indexes, stale extent/alignment constraints, and per-tile vacuum/analyze overhead.
- `scripts/setup/data-quality-upgrade-batched-v27.sql` uses raster convex-hull indexes and materialized batch geometries so raster clipping uses indexed prefiltering.
- `scripts/setup/data-quality-calibration-v28.sql` fixes the NALCMS class mapping: class `10` is grassland, class `17` is urban/built-up, and class `18` is water.
- Land cover remains in SRID `3979`; scenic/elevation geometries are SRID `4326`. The `2.7` SQL transforms scenic tile geometry once per batch for land cover.
- `elevation_non_zero_tiles` is lower than total tile count because many tiles are flat, water-dominated, outside meaningful terrain relief, or have no useful DEM variance.

## Remaining Future Work

The core data upgrade is complete. Future quality work should use `docs/AdditionalDataQualityUpgrade.md` and should be treated as separate enrichment releases, not blockers for the completed `2.8` calibrated upgrade.
