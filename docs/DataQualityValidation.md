# Data Quality Validation

Last updated: 2026-05-18

This document explains what changed between the deployed `2.6`, `2.7`, and `2.8` scenic data releases and what still needs validation.

## Current Production Release

Production is currently on:

```text
2.8-urban-aware-elevation-calibration | 211510
```

`2.8` is a calibration release on top of the completed national DEM work from `2.7`. It corrects the NALCMS land-cover class mapping, recomputes greenery and solitude with the corrected urban class, then downweights DEM elevation variance in urban/built-up tiles.

Reference: the Canada land-cover raster is part of the [NALCMS 30 m product described by Natural Resources Canada](https://atlas.gc.ca/land-cover/Atlas_LandCover_EN.html). The [NALCMS class table](https://developers.google.com/earth-engine/datasets/catalog/USGS_NLCD_RELEASES_2020_REL_NALCMS) identifies class `10` as grassland and class `17` as urban/built-up.

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

## 2.7 to 2.8 Calibration

`2.7` proved the national DEM upgrade worked, but validation exposed two calibration issues:

- Some flat or urban eastern areas showed high `elevation_score` values. Copernicus GLO-30 is a digital surface model, so buildings, tree canopy, bridges, and other surface features can add local height variance.
- The Canada land-cover class table was too generic. It treated NALCMS class `10` as urban, but class `10` is grassland; NALCMS class `17` is urban/built-up.

`2.8` fixes those issues.

| Metric | 2.7 | 2.8 | Change |
|---|---:|---:|---:|
| Scenic tiles | 211,510 | 211,510 | no coverage change |
| Average scenic score | 0.491143 | 0.508183 | +0.017040 |
| Scenic score stddev | 0.201429 | 0.199907 | -0.001522 |
| Green non-zero tiles | 187,904 | 189,796 | +1,892 |
| Average green score | 0.330282 | 0.486400 | +0.156118 |
| Elevation non-zero tiles | 122,173 | 122,173 | no coverage change |
| Average elevation score | 0.550105 | 0.497642 | -0.052463 |

Interpretation: `2.8` keeps national coverage, makes grasslands correctly count as natural/green instead of urban, and reduces urban DSM elevation overboost without removing real terrain signal from mountainous/non-urban areas.

## Route Explanation Validation

The route API now returns route-option explanations with:

- `componentAverages`: route-sampled averages for `water`, `greenery`, `elevation`, `solitude`, `curves`, and `poi`
- `baselineAverages`: local-area averages from nearby scenic tiles around the route origin
- `componentLifts`: route average minus local baseline, so explanations can say a route is greener or more mountainous than its nearby area
- `componentWeights`: the effective weights produced from the selected vibes and `preferenceVector`
- `weightedContributions`: normalized component influence after applying the user/vibe weights
- `leadingComponents`: the strongest three explanation signals, ranked by positive weighted lift when available and otherwise by weighted contribution
- `summary`: a user-facing explanation string
- `sampleTileCount`: how many scenic tiles were sampled along the route geometry
- `baselineTileCount`: how many nearby scenic tiles were used for the local baseline

The frontend displays this under "Why this option" so QA and users can see whether a route is being selected for water, greenery, elevation, solitude, curves, or stops. The visible bar uses weighted contribution, while the secondary text shows raw route average plus lift versus the local area. This avoids the earlier false signal where high raw water averages dominated every explanation even when water was common across the whole region.

Current Banff/Rockies QA note: route-option scenic spread is still low there. That is not primarily an explanation-data issue; the same candidate loop family scores similarly across vibe profiles in an already-scenic mountain area. The next ranking-side improvement should diversify candidate generation or apply stronger profile-specific contrast so `most_scenic`, `balanced`, and `shorter` differ more visibly in universally scenic regions.

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
