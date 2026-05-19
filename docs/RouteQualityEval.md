# Route Quality Eval

`scripts/monitoring/run-route-quality-eval.ps1` runs a fixed scenic-routing benchmark suite against a MoodRide API and writes machine-readable route-quality diagnostics.

The goal is to tune the routing engine with evidence instead of one-off manual checks.

## What It Measures

- route job completion status
- route option count
- duration and time-budget deviation
- distance and scenic-score spread between options
- route-option geometry separation
- route explanation availability
- leading explanation components
- vibe target signal strength
- warning flags for budget, diversity, and weak-vibe issues

## Default Benchmark Set

The default scenarios live in:

```powershell
scripts/monitoring/route-quality-scenarios.json
```

The set intentionally includes both expected-good and expected-weak cases, such as:

- Vancouver coastal
- Banff mountain/winding
- Regina open roads
- Toronto mountain mismatch
- Banff coastal mismatch
- Fredericton riverside/relaxing

Those mismatch cases are important. They tell us whether the engine should eventually say, "No strong mountain/coastal route nearby within this time budget," instead of pretending the vibe is strong.

## Run Against Production

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "https://app.moodrides.com" `
  -OutputDir "artifacts/route-quality-eval"
```

## Quick Smoke Run

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "https://app.moodrides.com" `
  -MaxScenarios 2
```

## Run Specific Scenarios

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -ScenarioIds "banff-mountain-winding-90","regina-mountain-mismatch-60"
```

## Dry Run

Use this to validate scenario selection without submitting route jobs:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 -DryRun
```

## Outputs

Each run writes three files under the output directory:

- `route-quality-eval-<runId>.json`: full structured results
- `route-quality-eval-<runId>.csv`: spreadsheet-friendly option rows
- `route-quality-eval-<runId>.md`: human-readable summary

## Important Flags

- `over_budget:<profiles>`: one or more route options exceeded budget tolerance
- `under_budget:<profiles>`: one or more options are suspiciously short for the requested budget
- `low_scenic_spread`: route options have very similar scenic scores
- `low_duration_spread`: route options have very similar durations
- `low_geometry_separation`: route options appear to share the same corridor
- `missing_explanations`: route options lack explanation payloads
- `same_leading_component:<component>`: every option is being explained by the same top signal
- `weak_vibe_signal:<components>`: selected vibe does not show strong matching component signal

## How To Use Results

Use the CSV first. Sort by `flags`, then inspect:

- budget issues before scoring issues
- low geometry separation before copy/explanation issues
- weak vibe signals in known mismatch scenarios
- same-leading-component cases, especially if every option says water

The JSON is for deeper inspection and future automation.
