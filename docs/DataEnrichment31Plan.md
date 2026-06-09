# MoodRide Data Enrichment 3.1

## Status

Created: 2026-06-09

Completed locally: 2026-06-09

3.1 focused on making the partially present 3.0 signals genuinely useful before adding more unrelated data.

Implementation scaffold added:

- `scripts/setup/data-quality-enrichment-v31.sql`
- `scripts/setup/run-data-enrichment-v31.ps1`
- `scripts/setup/import-light-pollution-samples-v31.ps1`
- `scripts/setup/sample-light-pollution-v31.py`

The v3.1 SQL script requires real light-pollution input by default: either `public.light_pollution_tile_samples` rows or `public.light_pollution_raster` rows. It fails fast when both sources are missing or empty, unless `allow_neutral_darkness=true` is explicitly passed for dry-run/dev use.

For global VIIRS rasters, the preferred path is now direct H3 sampling into `public.light_pollution_tile_samples`; this avoids importing the full global GeoTIFF into PostGIS.

The 2026-06-09 verification found:

- local `scenic_score_tiles` has `211,510` rows at `3.0-overture-lightpollution-enrichment`
- Overture POI and building-density signals are populated
- `light_pollution_raster` has `0` rows
- `darkness_score` is flat at `0.5` for every tile
- `urban_penalty_score` equals `building_density_score` for every tile
- no local or GitHub 3.0 scenic release artifact exists yet

Detailed verification: `artifacts/data-enrichment-30-verification-20260609.md`.

The 2026-06-09 3.1 recompute used:

- `D:\MoodRide\data\VNL_v22_npp-j01_2022_global_vcmslcfg_c202303062300.average_masked.dat.tif`
- direct raster sampling with `-UseDirectRasterSampling`
- `public.light_pollution_tile_samples` with `211,509` sampled rows
- one legacy blank `h3_index` tile excluded from direct sampling and recomputed with neutral darkness fallback

Final local 3.1 verification:

- `scenic_score_tiles`: `211,510` rows at `3.1-darkness-urban-penalty-calibration`
- `light_pollution_tile_samples`: `211,509` rows
- `changed_darkness_tiles`: `211,494`
- `darkness_score`: min `0.0`, avg `0.989350513727248`, max `1.0`, stddev `0.060030308981576345`
- `building_density_score = urban_penalty_score`: `0` rows
- average `urban_penalty_score`: `0.2611273399860389`
- average `scenic_score`: `0.49075907326595486`
- scenic score stddev: `0.1828599124752791`

## Goal

Make quiet, countryside, relaxing, open-roads, night/darkness-sensitive, and rural-escape routing more trustworthy by adding real darkness variance and separating urban pressure from raw building density.

## 3.1 Scope

### 1. Real Darkness Score

Replace the placeholder `darkness_score = 0.5` with a real signal from a nighttime-light or light-pollution raster.

Preferred source options:

- VIIRS nighttime lights, preferably EOG Annual VNL V2.2 GeoTIFF
- Falchi/World Atlas-style light-pollution raster

Source note:

- EOG's VIIRS Nighttime Lights product page lists the annual VNL data as GeoTIFF, EPSG:4326, 15 arc-second resolution, with radiance units of `nW/cm^2/sr`.
- The public product page states VIIRS Nighttime Lights data are licensed under CC BY 4.0.
- The annual VNL V2.2 download endpoint requires EOG sign-in, so the raster must be supplied locally before MoodRide can run the real import.

Expected result:

- `darkness_score` should vary by region
- urban cores should trend lower
- rural/northern/darker areas should trend higher
- quiet/countryside/open-road scoring should gain stronger separation near cities

Script behavior:

- samples `public.light_pollution_raster` at each scenic tile's representative point
- converts higher light-pollution values into lower `darkness_score`
- uses `light_pollution_reference_max` to normalize the raster value
- refuses placeholder neutral darkness unless explicitly allowed

### 2. Better Urban Penalty

`urban_penalty_score` should not be identical to `building_density_score`.

3.1 should blend:

- building density
- road density/intersection pressure proxy
- low darkness/high nighttime-light pressure
- Overture non-scenic place density when available
- low-green pressure
- industrial/commercial land-use or place categories when available in a later extension

Expected result:

- dense residential cores remain penalized
- industrial/commercial corridors get penalized more than quiet small towns
- rural roads near small settlements are not over-penalized only because buildings exist

Current v3.1 SQL formula:

```text
urban_penalty_score =
  building_density_score * 0.45
+ road_density           * 0.20
+ (1 - darkness_score)   * 0.20
+ (1 - green_score)      * 0.15
```

The script also recalibrates `solitude_score`:

```text
solitude_score =
  prior_solitude_score          * 0.45
+ (1 - building_density_score)  * 0.20
+ darkness_score                * 0.20
+ (1 - road_density)            * 0.15
```

### 3. Release Artifact Gate

Do not publish a release named `3.0-overture-lightpollution-enrichment` unless darkness is real.

Preferred release path:

1. Import real light/darkness source.
2. Recompute scores with version `3.1-darkness-urban-penalty-calibration`.
3. Run route-quality evals for quiet/countryside/open-roads/relaxing/date-night scenarios.
4. Publish `scenic-tiles-3.1-darkness-urban-penalty-calibration.tar.gz`.
5. Deploy through the active `Deploy Scenic Release` workflow.

## Validation Queries

After recompute:

```sql
SELECT scoring_version, COUNT(*)
FROM scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;

SELECT
  MIN(darkness_score),
  AVG(darkness_score),
  MAX(darkness_score),
  STDDEV_POP(darkness_score),
  COUNT(*) FILTER (WHERE darkness_score <> 0.5) AS changed_darkness_tiles
FROM scenic_score_tiles;

SELECT
  COUNT(*) FILTER (WHERE building_density_score = urban_penalty_score) AS equal_count,
  COUNT(*) AS total_count
FROM scenic_score_tiles;
```

Pass conditions:

- all tiles use the new 3.1 scoring version
- `darkness_score` has meaningful variance
- most tiles should not have `building_density_score = urban_penalty_score`
- route-quality evals should not regress v2 route success rates

## One-Command Recompute

After downloading the authenticated light-pollution/nighttime-light GeoTIFF, put it somewhere local, for example:

```powershell
data/light-pollution/v31/viirs-annual-vnl-v22-average_masked.tif
```

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -LightPollutionInputPath data/light-pollution/v31/viirs-annual-vnl-v22-average_masked.tif `
  -UseDirectRasterSampling `
  -DockerMemoryLimit 8g
```

The wrapper does three things:

1. Samples the raster into `public.light_pollution_tile_samples`.
2. Runs `scripts/setup/data-quality-enrichment-v31.sql`.
3. Fails the run unless the validation gate passes.

The older full-raster import path is still available by omitting `-UseDirectRasterSampling`, but the direct-sampling path is preferred for global VIIRS rasters. It avoids loading the entire global GeoTIFF into PostGIS and prints sampling progress every `10,000` H3 points by default.

Progress tuning:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -LightPollutionInputPath data/light-pollution/v31/viirs-annual-vnl-v22-average_masked.tif `
  -UseDirectRasterSampling `
  -DockerMemoryLimit 8g `
  -RasterSampleProgressInterval 5000
```

The validation gate checks:

- every `scenic_score_tiles` row moved to `3.1-darkness-urban-penalty-calibration`
- `darkness_score` is no longer flat
- `urban_penalty_score` is not identical to `building_density_score` for every tile

If the raster has a different practical brightness range, tune the normalization reference:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -LightPollutionInputPath data/light-pollution/v31/viirs-annual-vnl-v22-average_masked.tif `
  -UseDirectRasterSampling `
  -DockerMemoryLimit 8g `
  -LightPollutionReferenceMax 80
```

For a script smoke test without changing existing 3.0 tiles, use a non-existent source version:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -SkipImport `
  -SourceScoringVersion smoke-no-rows `
  -AllowNeutralDarknessForDryRun `
  -SkipValidation
```

Do not use `-AllowNeutralDarknessForDryRun` for a real release.

To publish the release after a successful recompute:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup/run-data-enrichment-v31.ps1 `
  -LightPollutionInputPath data/light-pollution/v31/viirs-annual-vnl-v22-average_masked.tif `
  -UseDirectRasterSampling `
  -DockerMemoryLimit 8g `
  -PublishRelease
```

## Recommended First Build Slice

Start with the real darkness import/recompute because it is the clearest current data gap. After that, update `urban_penalty_score` to use darkness plus building density and road/land-use pressure instead of building density alone.

Smoke verification completed on 2026-06-09:

- zero-batch run against the current `3.0-overture-lightpollution-enrichment` version succeeded
- default run without raster rows failed as intended with:
  - `public.light_pollution_raster has 0 rows. Import real light-pollution/nighttime-light data before running 3.1.`
