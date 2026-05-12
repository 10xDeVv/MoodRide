# Release QA Baseline

Use this after app/data deploys to verify route quality and endpoint health across major regions.

## Scope

- 3 regions:
  - Ontario (Toronto)
  - British Columbia (Vancouver)
  - Maritimes (Fredericton)
- 3 vibe profiles per region:
  - `countryside`
  - `coastal`
  - `mountain`
- Checks:
  - `GET /api/scenic-regions`
  - `POST /api/routes`
  - job polling `GET /api/routes/{jobId}`
  - route detail `GET /api/routes/route/{routeId}`

## Run

```powershell
./scripts/deploy/run_release_qa_baseline.ps1 `
  -BaseUrl "https://app.moodrides.com" `
  -TimeBudgetMinutes 90 `
  -PollIntervalSeconds 4 `
  -JobTimeoutSeconds 300 `
  -OutputDir "artifacts/release-qa"
```

Outputs:

- `artifacts/release-qa/release-qa-<timestamp>.json`
- `artifacts/release-qa/release-qa-<timestamp>.md`

## Pass Criteria

- All scenarios complete (`status=COMPLETED`)
- No `FAILED` or `TIMEOUT` jobs
- Route options returned for each completed scenario

## Notes

- For PowerShell web requests, use this script or `curl.exe` to avoid browser-parsing prompts from `curl` alias behavior.
- Keep each output artifact as a baseline record for regressions between releases.
