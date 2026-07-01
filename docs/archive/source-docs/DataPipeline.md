# Wayward Data Pipeline

Last reconciled: 2026-06-30

Wayward route generation depends on precomputed scenic tile scores. Runtime services read those scores from PostGIS; they do not recompute raw geospatial signals during a user request.

## Current Scenic Train

Current local scenic baseline:

- `3.7-bridge-coastal-calibration`

Next scenic scoring candidate:

- seasonal suitability / access warnings

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
- `scenic_poi_score`
- `viewpoint_score`
- `bridge_coastal_score`

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
- Tree canopy is represented in `3.4` through `tree_canopy_score`, a land-cover derived canopy proxy.
- Scenic places, landmarks, natural features, and discovery stops are represented in `3.5` through `scenic_poi_score`.
- Viewpoints and photo-landmark signals are represented in `3.6` through `viewpoint_score`, using weighted Overture categories because raw OSM viewpoint tables are not present locally.
- Bridge/coastal-road moments are represented in `3.7` through `bridge_coastal_score`, combining existing water-road metrics with Overture bridge, pier, marina, lighthouse, beach, and waterfall hints.
- Seasonal suitability should start as warnings/metadata because OSM seasonal/access tags can be sparse and inconsistent.

Recommended data-quality priority:

1. Road stress / road class. Implemented and published as `3.2-road-stress-calibration`.
2. Water visibility and bridge/coastal-road detection. Implemented in code/SQL as the `3.3` release candidate; run only after water geometry is present.
3. Tree canopy proxy. Implemented in code/SQL as the `3.4` release candidate from land-cover classes.
4. Scenic POIs / discovery stops. Implemented in code/SQL as the `3.5` release candidate from weighted Overture scenic-place categories.
5. Viewpoints / photo landmarks. Implemented in code/SQL as the `3.6` release candidate.
6. Bridge/coastal-road moments. Implemented in code/SQL as the `3.7` release candidate.
7. Seasonal suitability warnings.

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
- `scripts/setup/data-quality-enrichment-v35.sql`
- `scripts/setup/data-quality-enrichment-v36.sql`
- `scripts/setup/data-quality-enrichment-v37.sql`

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

Scenic-POI v3.5 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v35.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.4-tree-canopy-calibration" `
  -ExpectedScoringVersion "3.5-scenic-poi-calibration"
```

`data-quality-enrichment-v35.sql` derives `scenic_poi_score` from weighted Overture Places categories. It intentionally differs from generic `poi_score`:

- high: lookouts, waterfalls, lighthouses, national/state parks, nature reserves, beaches, mountains, lakes, rivers
- medium: campgrounds, hiking trails, landmarks, monuments, bridges, museums, galleries
- low/supporting: wineries and farms
- ignored: generic commercial/utility places such as gas stations, offices, banks, medical services, and routine retail

Runtime uses this for photo-worthy, date-night, and hidden-gems style explanations/contracts. It is not yet a dedicated OSM viewpoint or viewshed model.

Viewpoint v3.6 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v36.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.5-scenic-poi-calibration" `
  -ExpectedScoringVersion "3.6-viewpoint-calibration"
```

`data-quality-enrichment-v36.sql` derives `viewpoint_score` from higher-intent photo/view categories such as lookouts, waterfalls, lighthouses, mountains, beaches, piers, bridges, monuments, and landmarks. Runtime uses it for photo-worthy, date-night, hidden-gems, and sunset/golden-hour explanations and contract checks.

Bridge/coastal v3.7 recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v37.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.6-viewpoint-calibration" `
  -ExpectedScoringVersion "3.7-bridge-coastal-calibration"
```

`data-quality-enrichment-v37.sql` derives `bridge_coastal_score` from the existing v3.3 water-road scores plus a small Overture bridge/coastal-place subset. It is intentionally fast because it avoids re-running the full road-water spatial scan.

## Scenic Release Flow

Publish the scenic tile artifact:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.7-bridge-coastal-calibration" `
  -ReleaseTag "scenic-3.7-bridge-coastal-calibration-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
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
