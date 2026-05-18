# Data Quality Upgrade Progress Tracker

Last updated: 2026-05-18
Owner: Codex + aadeb
Plan source: `docs/DataQualityUpgrade.md`

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| Phase 0 - Execution scaffolding | Completed | Raster import, regional DEM resume, scenic recompute, scenic release publish, and scenic deploy scripts are in place. |
| Phase 1 - Data acquisition + raster load | Completed | Canada land cover is loaded; national Copernicus DEM is loaded into `elevation_raster` with `310,900` raster tiles. |
| Phase 2 - Component score computation | Completed | `green_score`, `solitude_score`, and `elevation_score` recomputed nationally with raster-backed inputs. |
| Phase 3 - Composite recompute + validation | Completed | `211,510` scenic tiles recomputed at `2.7-raster-data-quality-upgrade-national-batched`, then calibrated at `2.8-urban-aware-elevation-calibration`, published, deployed, and verified in production. |

## Completed Checklist

- [x] Import Canada land cover raster into `landcover_raster`
- [x] Import national Copernicus DEM into `elevation_raster`
- [x] Rebuild one clean raster spatial index after DEM import
- [x] Run final `VACUUM ANALYZE` on `elevation_raster`
- [x] Compute `green_score` from land cover class proportions
- [x] Compute `solitude_score` from urban proportion + road density
- [x] Compute `elevation_score` from DEM elevation standard deviation
- [x] Recompute composite `scenic_score`
- [x] Publish scenic release artifact
- [x] Deploy scenic release to production
- [x] Verify production DB has all rows at `2.8`
- [x] Smoke-test production scenic-region API

## Final Metrics

```text
DEM raster tiles: 310900
DEM extent: lon -141 to -51, lat 41 to 84
Scenic tiles: 211510
Scoring version: 2.8-urban-aware-elevation-calibration
green_non_zero_tiles: 189796
solitude_non_zero_tiles: 211510
elevation_non_zero_tiles: 122173
avg_scenic_score: 0.508183
stddev_scenic_score: 0.199907
min_scenic_score: 0.041511627906976746
max_scenic_score: 0.9218142190162193
```

## Validation Log

- 2026-04-26: Initial scaffolding created for data quality upgrade.
- 2026-04-28: Scoped DEM-backed recompute completed for the early DEM subset.
- 2026-05-12 to 2026-05-18: National DEM import completed region by region using resumable per-tile imports.
- 2026-05-18: `elevation_raster` verified at `310,900` raster tiles covering Canada operating bounds.
- 2026-05-18: National `2.7` scenic recompute completed for `211,510` tiles.
- 2026-05-18: Scenic release `scenic-2.7-raster-data-quality-upgrade-national-batched-20260518-1311` deployed successfully.
- 2026-05-18: Production verification confirmed `211,510` rows at `2.7`; Toronto and Vancouver scenic-region smoke tests returned data.
- 2026-05-18: `2.8` calibration corrected NALCMS class mapping, recomputed green/solitude, downweighted urban DEM surface variance, and deployed `scenic-2.8-urban-aware-elevation-calibration-20260518-1406`.
- 2026-05-18: Production verification confirmed `211,510` rows at `2.8`.
