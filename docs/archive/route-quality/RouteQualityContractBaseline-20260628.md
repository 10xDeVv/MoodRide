# Route Quality Contract Baseline - 2026-06-28

Baseline run:

- Base URL: `https://usewayward.app`
- Scenario file: `scripts/monitoring/route-quality-scenarios.json`
- Output directory: `artifacts/route-quality-eval/contract-baseline-20260628`
- JSON: `artifacts/route-quality-eval/contract-baseline-20260628/route-quality-eval-20260628-005053.json`
- CSV: `artifacts/route-quality-eval/contract-baseline-20260628/route-quality-eval-20260628-005053.csv`
- Markdown: `artifacts/route-quality-eval/contract-baseline-20260628/route-quality-eval-20260628-005053.md`

Note: production had not yet been redeployed with the new first-class `contractFlags` API fields. This baseline used the evaluator's fallback contract checks from `scoreBreakdown`, route geometry, duration, and scenario vibes.

## Summary

- Scenarios: 27
- Completed: 23
- Non-completed: 4
- Clean scenarios:
  - `banff-mountain-winding-90`
  - `banff-adventure-90`
  - `banff-winding-90`
- Honest unavailable scenarios:
  - `regina-mountain-mismatch-60`
  - `calgary-countryside-60`
  - `calgary-country-60`
  - `calgary-sunday-60`

## Contract Decision Matrix

| Contract family | Current decision | Why |
| --- | --- | --- |
| Coastal water share | Accept/pass | No completed coastal/sunset/photo/date scenarios failed `water_share_ok`. |
| Mountain/winding curve/elevation | Release blocker | Banff mountain/adventure/winding passed. A completed mountain/winding/adventure route must have enough `strategy_fit_score` and `curve_elevation_corridor_share`; otherwise return `vibe_unavailable` instead of presenting a weak route as mountain-like. |
| Quiet/open roads urban pressure | Release blocker | `regina-open-roads-60`, `winnipeg-quiet-open-roads-60`, and `regina-low-traffic-60` returned routes while failing urban pressure; quiet share also failed on multiple profiles. Quiet, open-roads, countryside, minimal-traffic, and clear-my-head should reject or heavily penalize city-heavy corridors. |
| Countryside/country/Sunday unavailable | Accept for now | Calgary rural/country/Sunday failing as `vibe_unavailable` is better than returning urban routes dressed as rural drives. |
| Global urban pressure | Warning/calibration target | `urban_pressure_ok` failed in 19 completed scenarios across many vibes, which suggests the threshold or `urban_penalty_score` calibration may be too aggressive for city starts. Treat it as a blocker only for low-pressure vibes until recalibrated. |
| Repeated-road risk | Warning, blocker on primary option | Most failures are on `balanced`/`shorter`. Treat as warning there; treat `most_scenic` failures as blocker candidates. |
| Photo/date peak/POI signal | Pass in baseline, add explicit tracking | Existing rows show `v2PhotoPeakScore=1` for photo/date/hidden-gems scenarios. `photo_poi_signal_ok` was added after this run and should be included in the next baseline rerun. |

## Release Policy

Treat these contract failures as release blockers:

- `mountain`, `adventure`, or `winding_roads` completing while `elevation_curve_share_ok=false`, or with weak `strategy_fit_score`.
- `coastal` or `riverside` completing while `water_share_ok=false`.
- `quiet`, `open_roads`, `minimal_traffic`, `countryside`, or `clear_my_head` completing with failed `urban_pressure_ok` or `quiet_share_ok` on the primary route.

Treat these as warnings unless they repeat on the primary option or break the vibe's core promise:

- `scenic`, `photo_worthy`, `date_night`, or `coastal` urban pressure when the water/photo/scenic contract still passes.
- repeated-road risk on `balanced` or `shorter`.
- generic city-start urban pressure across broad scenic vibes, pending `urban_penalty_score` calibration.

The first baseline above is the pre-`photo_poi_signal_ok` baseline. After production receives the explicit photo contract fields, rerun the full scenario set and save the next artifact beside it so the delta is reviewable.

## Highest Priority Debug Targets

1. `winnipeg-quiet-open-roads-60`
   - Fails `quiet_share_ok` and `urban_pressure_ok` across all route profiles.
   - Also has weak strategy fit / strategy mismatch on `shorter`.

2. `regina-open-roads-60`
   - Fails `urban_pressure_ok` across all profiles.
   - Fails `quiet_share_ok` on `balanced` and `shorter`.

3. `regina-low-traffic-60`
   - Fails `urban_pressure_ok` across all profiles.
   - Fails `quiet_share_ok` on `shorter`.

4. `toronto-mountain-mismatch-60`
   - Completed even though all profiles failed `elevation_curve_share_ok`.
   - Should fail as `vibe_unavailable` unless the returned corridor clears the mountain contract.

5. `vancouver-coastal-60` and `vancouver-sunset-60`
   - Water strategy fit is good, but `most_scenic` fails repeated-road risk.
   - Good target for diversity/backtracking tuning without changing the water strategy.

## Next Tuning Rule

Do not randomly change scoring constants. Use the baseline flags:

- If a vibe-specific contract fails on `most_scenic`, tune generation/filtering first.
- If only `balanced` or `shorter` fails repeated-road risk, treat it as a route-option diversity warning.
- If urban pressure fails across many unrelated vibes, inspect `urban_penalty_score` calibration before making the route planner avoid cities more aggressively.
- If mismatch scenarios return completed routes with failed contracts, add stricter unavailable gates rather than letting product copy imply the vibe succeeded.
