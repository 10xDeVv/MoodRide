# Wayward Data Pipeline

Last reconciled: 2026-06-29

Wayward route generation depends on precomputed scenic tile scores. Runtime services read those scores from PostGIS; they do not recompute raw geospatial signals during a user request.

## Current Scenic Train

Current local scenic baseline:

- `3.3-water-visibility-calibration`

Next scenic scoring candidate:

- `3.4-tree-canopy-calibration`

## What Gets Precomputed

Each row in `scenic_score_tiles` is an H3 tile with a scenic feature vector:

- `water_score`
- `green_score`
- `elevation_score`
- `solitude_score`
- `curve_score`
- `poi_score`
- `park_score`
- `overture_poi_score`
- `building_density_score`
- `darkness_score`
- `urban_penalty_score`
- `road_stress_score`
- `water_visibility_score`
- `water_crossing_score`
- `coastal_road_score`
- `tree_canopy_score`

`hybrid_osrm_v2` samples these tile scores along returned OSRM route corridors.

## Current Raw Inputs

- OpenStreetMap roads
- Natural Earth water geometry
- land-cover raster
- DEM/elevation raster
- protected/conserved area geometry
- Overture Places and Buildings
- light-pollution/nighttime-light raster

## Next Data-Quality Wave

The current data-quality cycle is the `3.x` scenic train. This is a data-pipeline project, not a runtime routing patch. The goal is to make the raw scenic inputs more truthful before increasing algorithm weights.

Current status:

- Darkness/light-pollution is already represented in `3.1` through `darkness_score`.
- OSM road class and surface are represented in `3.2` through `road_stress_score`.
- Water visibility and bridge/coastal-road detection are represented in `3.3` through `water_visibility_score`, `water_crossing_score`, and `coastal_road_score`.
- Tree canopy is the next `3.4` implementation target through `tree_canopy_score`, a land-cover derived canopy proxy.
- OSM viewpoints/peaks can support photo-worthy routes, but coverage needs to be audited region by region.
- Tree canopy is currently approximated through land-cover/green scoring. A true canopy signal should be a separate source or a carefully named proxy.
- Seasonal suitability should start as warnings/metadata because OSM seasonal/access tags can be sparse and inconsistent.

Recommended data-quality priority:

1. Road stress / road class. Implemented and published as `3.2-road-stress-calibration`.
2. Water visibility and bridge/coastal-road detection. Implemented in code/SQL as the `3.3` release candidate; run only after water geometry is present.
3. Tree canopy proxy. Implemented in code/SQL as the `3.4` release candidate from land-cover classes.
4. Scenic viewpoints / peaks.
5. Seasonal suitability warnings.

Run the read-only audit before publishing any new scenic scoring SQL:

```powershell
./scripts/setup/audit-scenic-data-quality-v32.ps1 `
  -Database moodride `
  -Username postgres `
  -OutputDir artifacts/scenic-data-quality
```

The audit writes JSON and Markdown under `artifacts/scenic-data-quality/`. It checks whether the database has usable evidence for darkness variance, light-pollution samples, road class/surface coverage, OSM viewpoints and peaks, bridge/coastal-road hints, seasonal/access tags, water geometry, land-cover/canopy proxies, and protected-area context.

## Local Enrichment Flow

Typical 3.1 flow:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -LightPollutionInputPath "D:\MoodRide\data\VNL_v22_npp-j01_2022_global_vcmslcfg_c202303062300.average_masked.dat.tif" `
  -UseDirectRasterSampling `
  -DockerMemoryLimit 8g
```

The direct raster sampling path avoids importing a very large light-pollution raster into PostGIS. It exports H3 tile centroids, samples the raster with GDAL, imports the sampled values, and then runs the 3.1 scoring SQL.

Core scripts:

- `scripts/setup/run-data-enrichment-v31.ps1`
- `scripts/setup/import-light-pollution-samples-v31.ps1`
- `scripts/setup/sample-light-pollution-v31.py`
- `scripts/setup/data-quality-enrichment-v31.sql`
- `scripts/setup/data-quality-enrichment-v32.sql`
- `scripts/setup/data-quality-enrichment-v33.sql`
- `scripts/setup/data-quality-enrichment-v34.sql`

Road-stress v3.2 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v32.sql" `
  -ChunkSize 50000 `
  -ExpectedScoringVersion "3.2-road-stress-calibration"
```

`data-quality-enrichment-v32.sql` derives `road_stress_score` from `road_segments` using road class, speed limit, surface, major-road share, and segment length. It treats the score as friction:

- low values mean calmer/local/lower-stress road context
- high values mean high-speed or major-road context

Runtime uses this as a modest penalty and drive-quality signal, especially for Open Roads, Quiet, Minimal Traffic, Clear My Head, Smooth Cruise, and countryside-style vibes.

Water-visibility v3.3 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v33.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.2-road-stress-calibration" `
  -ExpectedScoringVersion "3.3-water-visibility-calibration"
```

`data-quality-enrichment-v33.sql` derives:

- `water_visibility_score`: road length close enough to water to plausibly feel water-adjacent
- `water_crossing_score`: bridge/water-crossing moments from water intersections or OSM bridge/waterway/ford hints
- `coastal_road_score`: roads that run close to water rather than merely being inside a tile near water

The script intentionally fails if `natural_earth_Water_Bodies` is missing or empty. Optional OSM line hints are used when `planet_osm_line` exists with tags; otherwise the release can still use road-water geometry, but bridge/coastal hints will be weaker.

Tree-canopy v3.4 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v34.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.3-water-visibility-calibration" `
  -ExpectedScoringVersion "3.4-tree-canopy-calibration"
```

`data-quality-enrichment-v34.sql` derives `tree_canopy_score` from land-cover classes. It prefers `landcover_raster` and can fall back to `nlcd_land_cover_cells`. The score is a canopy proxy:

- forest and woody wetlands score high
- shrub, mixed natural classes, and sparse canopy score partially
- grassland, crops, water, and developed classes score low

Runtime uses this as a forest/nature explanation and scoring signal. It is not a true tree-height, street-tree, or viewshed dataset.

## Scenic Release Flow

Publish the scenic tile artifact:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.4-tree-canopy-calibration" `
  -ReleaseTag "scenic-3.4-tree-canopy-calibration-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/Wayward"
```

Deploy it with:

- `.github/workflows/deploy-scenic-release.yml`

The deploy workflow downloads the release asset, applies score updates into `scenic_score_tiles`, and restarts route services so runtime caches refresh.

## OSRM Dataset Flow

OSRM datasets are built separately from scenic tiles.

Build:

```powershell
./scripts/deploy/build_osrm_dataset.ps1 `
  -InputPbf "D:\DATA\canada-260405.osm.pbf" `
  -OutputDir "D:\DATA\osrm\canada" `
  -DatasetBasename "canada-latest"
```

Publish:

```powershell
./scripts/deploy/publish_data_release.ps1 `
  -DatasetBasename "canada-latest" `
  -DataDirectory "D:\DATA\osrm\canada" `
  -ReleaseTag "data-canada-latest-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/Wayward"
```

Deploy it with:

- `.github/workflows/deploy-data-release.yml`

## Notes

- Keep raw data, OSRM archives, scenic tarballs, and database dumps out of Git.
- Older versioned SQL files can stay when they reproduce older releases.
- Do not add new progress-tracker docs for data work; update this document and the deployment pipeline doc instead.
