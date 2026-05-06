# Data Quality Upgrade Progress Tracker

Last updated: 2026-04-28  
Owner: Codex + aadeb  
Plan source: `docs/DataQualityUpgrade.md`

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| Phase 0 - Execution scaffolding | Completed | Added runnable scripts: `import-raster-to-postgis.ps1`, `run-data-quality-upgrade.ps1`, `data-quality-upgrade.sql`. |
| Phase 1 - Data acquisition + raster load | In Progress | `landcover_raster` loaded (184,549 rows, SRID 3979) and `elevation_raster` loaded (20,475 rows, SRID 4326, Maritimes subset). Remaining: final spot-check + class legend confirmation. |
| Phase 2 - Component score computation | In Progress | Scoped DEM-only recompute completed for remaining tiles; national coverage still pending. |
| Phase 3 - Composite recompute + validation | In Progress | Scoped DEM-only composite recompute completed; national coverage still pending. |

## Detailed Checklist

### Phase 0 - Scaffolding (Completed)
- [x] Add reusable raster import script (`scripts/setup/import-raster-to-postgis.ps1`)
- [x] Add upgrade SQL (`scripts/setup/data-quality-upgrade.sql`)
- [x] Add orchestration entrypoint (`scripts/setup/run-data-quality-upgrade.ps1`)
- [x] Add progress tracking document (`docs/DataQualityUpgradeProgress.md`)

### Phase 1 - Data Acquisition and Loading
- [ ] Confirm final land-cover raster legend/version to use for class mapping
- [x] Download/prepare Copernicus DEM raster(s) for operational area (Maritimes subset)
- [x] Import `landcover_raster`
- [x] Import `elevation_raster`
- [ ] Spot-check raster values at known locations

### Phase 2 - Component Score Computation
- [ ] Review/tune `landcover_class_weights` defaults in `data-quality-upgrade.sql`
- [x] Execute chunked scoped recompute using `scripts/setup/data-quality-upgrade-scoped-batched.sql`
- [x] Optimize scoped target selection to DEM footprint union (avoid raster-tile EXISTS scan per H3 tile)
- [x] Scoped recompute finished for remaining DEM tiles (resume run, chunk_size=200)
- [x] Compute `green_score` from raster class proportions (DEM-scoped)
- [x] Compute `solitude_score` from urban proportion + road density (DEM-scoped)
- [x] Compute `elevation_score` from DEM elevation stddev (DEM-scoped)
- [x] Confirm non-trivial variance in each component (DEM-scoped)

### Phase 3 - Composite Recompute and Validation
- [x] Recompute `scenic_score` from component scores (DEM-scoped tiles)
- [ ] Compare before/after score distribution
- [ ] Spot-check known scenic vs urban/industrial locations
- [ ] Confirm preference vectors produce route differences

## Runbook Commands

```powershell
# Dry run to preview commands:
.\scripts\setup\run-data-quality-upgrade.ps1 `
  -ElevationInputPath "C:\path\to\elevation_merged.tif" `
  -Password "<postgres-password>" `
  -DryRun

# Full execution:
.\scripts\setup\run-data-quality-upgrade.ps1 `
  -ElevationInputPath "C:\path\to\elevation_merged.tif" `
  -Password "<postgres-password>"

# Scoped batched recompute over DEM-covered tiles (recommended now):
psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=500 -f scripts/setup/data-quality-upgrade-scoped-batched.sql
```

## Validation Log

- 2026-04-26: Scaffolding created; end-to-end data run not executed yet in this session.
- 2026-04-27: Confirmed raster loads present (`landcover_raster`: 184,549 rows SRID 3979, `elevation_raster`: 20,475 rows SRID 4326).
- 2026-04-27: Monolithic recompute canceled due to long-running no-commit behavior; switched to chunked scoped execution plan.
- 2026-04-27: Added optimized batched script path using precomputed DEM footprint in `scripts/setup/data-quality-upgrade-scoped-batched.sql`.
- 2026-04-27: Run started with `chunk_size=100`; target set built successfully (`24,282` DEM-covered tiles), currently executing batch `1..100`.
- 2026-04-28: Resume run completed remaining `9,282` DEM-scoped tiles (chunk_size=200). Final scoped stats: green>0 `9,166`, solitude>0 `9,282`, elevation>0 `9,116`, avg_scenic `0.665141` (stddev `0.066092`).
- 2026-04-28: Full DEM-scoped totals (24,282 tiles): green>0 `24,009`, solitude>0 `24,282`, elevation>0 `23,873`, avg_green `0.533798` (stddev `0.185206`), avg_elevation `0.931839` (stddev `0.230583`), avg_scenic `0.664639` (stddev `0.066652`).
