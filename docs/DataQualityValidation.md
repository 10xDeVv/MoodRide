# Data Quality Validation

Last updated: 2026-05-18

This document explains what changed between the deployed `2.6` and `2.7` scenic data releases and what still needs validation.

## 2.6 to 2.7 Summary

`2.6` already included the land-cover upgrade for `green_score` and `solitude_score`. The main `2.7` change was completing national DEM coverage, then recomputing `elevation_score` and composite `scenic_score` for all scenic tiles.

| Metric | 2.6 | 2.7 | Change |
|---|---:|---:|---:|
| Scenic tiles | 211,510 | 211,510 | no coverage change |
| Average scenic score | 0.420848 | 0.491143 | +0.070295 |
| Scenic score stddev | 0.160241 | 0.201429 | +0.041188 |
| Elevation non-zero tiles | 43,365 | 122,172 | +78,807 |
| Average elevation score | 0.198625 | 0.550103 | +0.351478 |

Tile-level diff:

```text
scenic_score increased: 78,810 tiles
scenic_score decreased: 2 tiles
scenic_score unchanged: 132,697 tiles
green_score changed: 0 tiles
solitude_score changed: 0 tiles
```

Interpretation: `2.7` did not change greenery/solitude versus `2.6`; it expanded the elevation signal nationally. The user-visible effect should be strongest in places that previously had no DEM coverage.

## Regional Comparison

| Region | Tiles | Avg scenic delta | Avg old elevation | Avg new elevation | Avg green | Avg solitude | Avg 2.7 scenic |
|---|---:|---:|---:|---:|---:|---:|---:|
| Vancouver / Coast Mountains | 1,399 | 0.00 | 0.95 | 0.95 | 0.41 | 0.87 | 0.66 |
| Banff / Rockies | 2,310 | 0.00 | 0.91 | 0.91 | 0.35 | 0.86 | 0.56 |
| Toronto urban | 896 | 0.19 | 0.00 | 0.94 | 0.20 | 0.82 | 0.60 |
| Ottawa / Gatineau | 1,991 | 0.19 | 0.00 | 0.94 | 0.37 | 0.90 | 0.64 |
| Saskatchewan prairie | 11,189 | 0.00 | 0.00 | 0.00 | 0.12 | 0.92 | 0.24 |
| Fundy / Atlantic | 4,114 | 0.19 | 0.00 | 0.93 | 0.53 | 0.92 | 0.66 |

This confirms the expected broad behavior:

- Western mountainous regions were already DEM-backed before `2.7`, so their score deltas are small.
- Prairie tiles remain low elevation, which is correct.
- Ontario/Atlantic tiles changed substantially because those regions gained DEM coverage in `2.7`.

## Calibration Finding

The upgrade is real, but it is not automatically the final scoring calibration.

Some flat or urban eastern areas now show high `elevation_score` values. This can happen because Copernicus GLO-30 is a digital surface model, so buildings, tree canopy, bridges, and other surface features can add local height variance. The current score also normalizes elevation standard deviation with `/100.0`, which may be too aggressive for some H3 tile sizes and regions.

Recommended `2.8` calibration:

- Suppress or downweight `elevation_score` where land cover is heavily urban/developed.
- Consider blending elevation with land-cover context, for example reduce elevation contribution when `green_score` is low and urban proportion is high.
- Add route-option explanations showing component averages (`water`, `green`, `elevation`, `solitude`, `curves`, `poi`) so users and QA can see why a route was chosen.
- Run the release QA baseline after calibration and compare regional score distributions again.

## Route QA

Use:

```powershell
./scripts/deploy/run_release_qa_baseline.ps1 `
  -BaseUrl "https://app.moodrides.com" `
  -TimeBudgetMinutes 90 `
  -PollIntervalSeconds 4 `
  -JobTimeoutSeconds 300 `
  -OutputDir "artifacts/release-qa"
```

The script tests six regions and four vibe profiles with the current numeric `preferenceVector` schema.
