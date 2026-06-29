# Route Quality Scenic POI Rerun - 2026-06-29

This rerun validates the v3.5 scenic POI data-quality upgrade against the route-quality scenario set.

## Run

- Scenic scoring version: `3.5-scenic-poi-calibration`
- Local artifact directory: `artifacts/route-quality-eval/local-scenic-poi-v35-20260629-full`
- Markdown report: `route-quality-eval-20260629-160536.md`
- Scenarios: 27
- Completed scenarios: 19
- Unavailable scenarios: 8

## Result

The v3.5 scenic POI signal is safe to keep.

- Availability stayed at the expected 19 completed / 8 unavailable shape.
- The strict mismatch cases still fail honestly, including Toronto mountain, Regina mountain, Regina open roads, Winnipeg quiet/open roads, Calgary countryside, Calgary country, Calgary Sunday, and Regina low traffic.
- Photo-oriented routes completed without `photo_poi_signal_ok` failures.
- Completed route options reported a non-flat scenic POI signal, with an average `v2ScenicPoiScore` of `0.7179`.
- The remaining failures are the known urban-pressure and repeated-road diagnostics, not scenic POI regressions.

## Notes

Banff coastal completed. This appears to be accepted by the current coastal contract because the route has enough local water/scenic signal, but it is worth visually reviewing later if the scenario is meant to represent an impossible ocean-coastal mismatch rather than any strong water route.

The next scenic-data-quality candidate after v3.5 is bridge/coastal-road detection or explicit viewpoint ingestion, depending on whether the needed OSM point/line source tables are added.
