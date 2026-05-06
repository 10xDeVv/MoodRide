# Route Export & UI Polish Progress Tracker

Last updated: 2026-04-25
Owner: Codex + aadeb
Plan source: `docs/RouteExportAndUIPolish.md`

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| Phase 1 - Fix scoring display | Completed | Live validation passed on job `f397109a-4196-4740-abc4-10f6b8acc222` with differentiated scores and correct profile ordering. |
| Phase 2A - Start Drive handoff | In Progress | Implemented in UI; URL-construction validation passed (Google/Apple URLs with sampled waypoints), mobile app handoff still pending manual device check. |
| Phase 2B - GPX export | Completed | Added GPX export button and client-side GPX generation/download; generated GPX parses correctly with expected trackpoint count. |
| Phase 3A - Hide debug info | Implemented | Added `Debug` toggle and moved job/channel/backend diagnostics behind it. |
| Phase 3B - Scenic highlights cleanup | Implemented | Removed waypoint instruction highlights and replaced with route/vibe-oriented user text. |
| Phase 3C - Scenic regions cleanup | Implemented | Nearby Scenic Regions panel is now debug-only. |

## Detailed Checklist

### Phase 1 - Fix Scoring Display
- [x] Confirm symptom from live API response (three route options returning score `30.0`)
- [x] Query DB directly for the most recent 3-option job
- [x] Trace backend scorer in `RoutePlanner` and locate flat fallback score path
- [x] Implement fallback score differentiation when scenic corridor coverage is missing
- [x] Add/adjust test coverage for non-identical scenic scores in multi-option generation
- [x] Validate with a newly generated route job that option scores now differ

### Phase 2A - Start Drive
- [x] Remove old Mapbox directions handoff
- [x] Add waypoint sampling utility
- [x] Build Google Maps URL handoff
- [x] Build Apple Maps URL handoff for iOS user agents
- [x] Wire `Start Drive` button to new flow
- [x] Desktop verification run
- [ ] Mobile verification run

### Phase 2B - GPX Export
- [x] Implement GPX XML generation in frontend
- [x] Add `Export GPX` button
- [x] Style GPX export as secondary action
- [x] Validate generated GPX structure and trackpoint count

### Phase 3A - Hide Debug Information
- [x] Add `showDebug` toggle state (default hidden)
- [x] Move job/debug metadata behind toggle
- [x] Add `Debug` toggle control in UI

### Phase 3B - Scenic Highlights
- [x] Remove `Continue to waypoint N` user-facing output
- [x] Replace with concise route summary + vibe-oriented highlights

### Phase 3C - Nearby Scenic Regions
- [x] Move panel behind debug toggle

## Next Validation Pass

1. Generate a new route from UI and confirm:
   - route option scenic scores are no longer all `30.00`
   - `Most Scenic` has highest score and `Shorter` is lower
2. Click `Start Drive` and confirm Google Maps URL contains sampled waypoints.
3. Click `Export GPX` and open the file in a GPX-compatible viewer.

## Verification Log (This Session)

- `npm run lint` in `frontend/moodride-web` passed.
- `npm run build` in `frontend/moodride-web` passed.
- `mvn -Dmaven.repo.local=... -pl services/route-worker -am test -Dtest=RoutePlannerTest -Dsurefire.failIfNoSpecifiedTests=false` passed.
- `docker exec moodride-postgres psql ...` confirmed pre-fix persisted rows were flat at `scenic_score = 0.3` for recent 3-option jobs.
- Restarted route-worker from freshly built jar and validated new live job `f397109a-4196-4740-abc4-10f6b8acc222`:
  - API route options: `52.28`, `42.77`, `40.13` (Most Scenic > Balanced > Shorter)
  - DB rows persisted with matching `scenic_score` values and `route_profile` values (`most_scenic`, `balanced`, `shorter`)
- Start Drive URL sampling check produced valid Google/Apple navigation URLs with 15 sampled points for route `7a2e08ff-dfd4-4e7c-a0d6-deba61a50793`.
- GPX generation check produced valid XML file at `.tmp-verify/most_scenic_loop_validation.gpx` with `1433` parsed `trkpt` nodes matching route coordinate count.
