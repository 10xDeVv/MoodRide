# Route Quality Bridge/Coastal Rerun - 2026-06-30

This rerun validates the v3.6 viewpoint and v3.7 bridge/coastal data-quality upgrades against the route-quality scenario set.

## Run

- Scenic scoring version: `3.7-bridge-coastal-calibration`
- Local artifact directory: `artifacts/route-quality-eval/local-bridge-coastal-v37-20260630-full`
- Markdown report: `route-quality-eval-20260629-234526.md`
- Scenarios: 27
- Completed scenarios: 19
- Unavailable scenarios: 8

## Result

The v3.6/v3.7 signals are safe to keep.

- Availability stayed at the expected 19 completed / 8 unavailable shape.
- The strict mismatch cases still fail honestly, including Toronto mountain, Regina mountain, Regina open roads, Winnipeg quiet/open roads, Calgary countryside, Calgary country, Calgary Sunday, and Regina low traffic.
- Photo-oriented routes completed without `photo_poi_signal_ok` failures.
- Completed route options reported non-flat new signals:
  - average `v2ViewpointScore`: `0.5012`
  - average `v2BridgeCoastalScore`: `0.0770`
- The remaining failures are the known urban-pressure, repeated-road, and route-craft diagnostics.

## Data Stats

Local v3.6 recompute:

- tiles: `211,510`
- viewpoint non-zero tiles: `14,092`
- average `viewpoint_score`: `0.025467`
- max `viewpoint_score`: `1.0`

Local v3.7 recompute:

- tiles: `211,510`
- bridge/coastal non-zero tiles: `6,480`
- average `bridge_coastal_score`: `0.009562`
- max `bridge_coastal_score`: `0.807931`

## Notes

Quebec City hidden gems completed but now reports `weak_vibe_signal:viewpoint+scenic_poi+solitude+curves`. That is useful diagnostic signal: the route is available, but hidden-gems intent is not especially strong across all target components.

Banff coastal still completes. This remains a scenario-definition review item if the intention is "ocean coastal only" rather than "strong water/coastal-style route."
