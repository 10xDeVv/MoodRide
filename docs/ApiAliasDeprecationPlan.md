# `/routes/*` Alias Deprecation Plan

Last updated: 2026-05-01

## Objective
Keep `/api/*` as the canonical API surface and retire legacy `/routes/*` aliases in a controlled way.

## Scope
Legacy aliases covered by this plan:
- `POST /routes`
- `POST /routes/generate`
- `GET /routes/{jobId}`
- `GET /routes/jobs/{jobId}`
- `GET /routes/route/{routeId}`
- `POST /routes/{routeId}/rating`

Canonical replacements:
- `POST /api/routes`
- `GET /api/routes/{jobId}`
- `GET /api/routes/jobs/{jobId}`
- `GET /api/routes/route/{routeId}`
- `POST /api/routes/{routeId}/rating`

## Current State (as of 2026-05-01)
- Legacy aliases remain functional for compatibility.
- Route API now emits deprecation headers for alias calls:
  - `Deprecation: Fri, 01 May 2026 00:00:00 GMT`
  - `Sunset: Sat, 01 Aug 2026 00:00:00 GMT`
  - `Link: </api/routes>; rel="successor-version"`

## Rollout Timeline
1. Phase A (active now, started May 1, 2026):
- Keep aliases enabled.
- Return deprecation/sunset headers on every alias response.
- Update docs and client guidance to use `/api/*`.

2. Phase B (by June 15, 2026):
- Confirm all known consumers have migrated to `/api/*`.
- Review logs for residual `/routes/*` usage.

3. Phase C (sunset date: August 1, 2026):
- Stop guaranteeing compatibility for `/routes/*`.
- If any critical consumer remains, extend timeline explicitly in docs.

4. Phase D (target removal window: August 1-15, 2026):
- Remove alias mappings from controller annotations.
- Keep `/api/*` only.
- Publish removal note in changelog/release notes.

## Engineering Checklist
1. Compatibility phase:
- Ensure frontend and scripts use `/api/*` only.
- Keep alias headers in `RouteController`.

2. Pre-removal validation:
- Verify no active internal tools depend on `/routes/*`.
- Run route submit/status/detail/rating smoke tests using `/api/*`.

3. Removal patch:
- Update `@RequestMapping` in `RouteController` from `{"/api/routes", "/routes"}` to `"/api/routes"`.
- Remove `/generate` alias path unless still needed.
- Remove deprecation-header helper code once aliases are deleted.

4. Post-removal verification:
- Confirm all endpoint tests pass on `/api/*`.
- Confirm old `/routes/*` paths return expected non-success status.

## Notes
- This plan is API-path cleanup only; it does not change payload contracts.
- Keep this timeline tied to real client migration evidence. If usage remains, shift sunset/removal dates explicitly with new dates.
