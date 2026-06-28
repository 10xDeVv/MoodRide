# Route Quality Contract Rerun - 2026-06-28

Local rerun after strict vibe contract gates:

- Base URL: `http://localhost:8080`
- Scenario file: `scripts/monitoring/route-quality-scenarios.json`
- Baseline compared against: `artifacts/route-quality-eval/contract-baseline-20260628/route-quality-eval-20260628-005053.*`
- New artifacts:
  - JSON: `artifacts/route-quality-eval/local-contract-gates-20260628-full/route-quality-eval-20260628-132049.json`
  - CSV: `artifacts/route-quality-eval/local-contract-gates-20260628-full/route-quality-eval-20260628-132049.csv`
  - Markdown: `artifacts/route-quality-eval/local-contract-gates-20260628-full/route-quality-eval-20260628-132049.md`

## Summary

| Metric | Baseline | Local contract rerun | Change |
| --- | ---: | ---: | ---: |
| Scenarios | 27 | 27 | 0 |
| Completed | 23 | 19 | -4 |
| Non-completed | 4 | 8 | +4 |
| `vibe_unavailable` | 4 | 8 | +4 |
| `urban_pressure_ok` failures | 20 | 16 | -4 |
| `quiet_share_ok` failures | 3 | 0 | -3 |
| `elevation_curve_share_ok` failures | 1 | 0 | -1 |
| `weak_strategy_fit` flags | 2 | 0 | -2 |

## Status Changes

These scenarios changed from completed routes with failed contracts to honest `vibe_unavailable`:

- `toronto-mountain-mismatch-60`
- `regina-open-roads-60`
- `winnipeg-quiet-open-roads-60`
- `regina-low-traffic-60`

This is the expected result of the stricter route-promise gate. The planner now rejects weak mountain and low-pressure/open-road corridors instead of returning completed routes that contradict the selected vibe.

## Calibration Read

Good signs:

- `banff-mountain-winding-90`, `banff-adventure-90`, and `banff-winding-90` still complete cleanly with no flags.
- Coastal/photo/date/scenic routes still complete; urban pressure remains a warning-class signal for those broad vibes.
- No completed route now fails `elevation_curve_share_ok`, `quiet_share_ok`, or `weak_strategy_fit`.

Remaining tuning targets:

- `urban_pressure_ok` still fails broadly on city-start scenic/coastal/photo routes. Keep this as a calibration warning, not a universal blocker.
- repeated-road risk still appears on some `balanced`/`shorter` options and on `most_scenic` for Vancouver coastal/sunset.
- Low-pressure prairie scenarios now fail honestly; the next product decision is whether to search farther outward before failing, or keep the unavailable response.
