# Wayward Data Pipeline

Last reconciled: 2026-06-27

Wayward route generation depends on precomputed scenic tile scores. Runtime services read those scores from PostGIS; they do not recompute raw geospatial signals during a user request.

## Current Release

Current scenic scoring version:

- `3.1-darkness-urban-penalty-calibration`

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

`hybrid_osrm_v2` samples these tile scores along returned OSRM route corridors.

## Current Raw Inputs

- OpenStreetMap roads
- Natural Earth water geometry
- land-cover raster
- DEM/elevation raster
- protected/conserved area geometry
- Overture Places and Buildings
- light-pollution/nighttime-light raster

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

## Scenic Release Flow

Publish the scenic tile artifact:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.1-darkness-urban-penalty-calibration" `
  -ReleaseTag "scenic-3.1-darkness-urban-penalty-calibration-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
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
