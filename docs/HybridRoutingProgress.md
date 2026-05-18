# Hybrid Routing Progress Tracker

Last updated: 2026-05-18 (route-worker time-budget and option-diversity tuning)
Source plan: `docs/Hybrid Routing Execution.md`

## Current Status
- Phase: Week 3 active (multi-option response delivered)
- Route worker runs hybrid-only route generation (no beam-search fallback path).

## Execution Checklist

### Week 1 - Foundation
- [x] Add component scores to schema
- [x] Update scoring pipeline
- [x] Recompute tiles (all 211,510)
- [x] Run OSRM via Docker
- [x] Test `/trip` endpoint
- [x] Add OSRM client in `route-worker` (trip endpoint call + polyline decode)

### Week 2 - Core Logic
- [x] Remove beam search completely
- [x] Add tile-based waypoint ring generation (sector selection + 4/6/8 variants)
- [x] Add route corridor scoring (polyline sampling + H3 lookup + scenic density)

### Week 3 - Product
- [x] Wire API preferences into worker job payload persistence (`route_jobs.preference_vector`)
- [x] Return 3 route options (most scenic / balanced / shorter)
- [x] Replace hardcoded route algorithm metadata (`beam_v1`) with persisted generation metadata

## Completed In This Iteration
- Hardened route-worker time-budget behavior:
  - route generation now uses a hard effective duration cap of at most `15%` over the requested budget
  - over-budget-only candidate sets are rejected instead of being returned to users
  - added smaller budget-rescue waypoint rings before failing a request
  - reduced `moodride.algorithm.max-duration-overrun-ratio` default/config from `1.25` to `1.15`
  - added `NoFeasibleRouteException` for valid "no good route available within this budget" outcomes
  - route-worker marks no-feasible jobs failed without retrying the same impossible generation
- Improved route-option selection:
  - `most_scenic`, `balanced`, and `shorter` now use profile-specific scoring functions instead of simple sorted comparators
  - route selection applies corridor similarity, duration-gap, and distance-gap checks before picking the secondary options
  - exact duplicates are still removed, but same-corridor candidates are now penalized or skipped when alternatives exist
- Updated route-worker test coverage:
  - over-budget-only candidates now assert `NoFeasibleRouteException`
  - no-OSRM-candidate path now asserts the same no-feasible failure mode
  - verification: `mvn '-Dmaven.repo.local=C:/Users/aadeb/OneDrive/Desktop/MoodRide/.m2/repository' -pl services/route-worker -am test` passed
- Fixed hybrid option score flattening (`30.00` for all profiles):
  - root cause: route-worker used `h3-resolution=9` while scenic tiles are stored at resolution `7`, so corridor tile lookups often missed and fell back to constant `0.30`
  - route-worker now falls back to H3 resolution `7` for tile selection and corridor scoring when configured resolution misses
  - route-worker corridor scoring now also tries a 1-ring neighbor expansion before using constant fallback
  - aligned route-worker defaults to `h3-resolution=7` in both config class and `application.yml`
  - added `RoutePlannerTest.generateRouteFallsBackToDefaultH3ResolutionForScenicScoring` coverage
  - verification: `mvn --% -Dmaven.repo.local=... -pl services/route-worker -am -Dtest=RoutePlannerTest -Dsurefire.failIfNoSpecifiedTests=false test` passed
- Fixed scenic-region score rendering in web client:
  - root cause: backend returns `compositeScore` while frontend expected `scenicScore`
  - fix: normalized `/api/scenic-regions` payload in frontend API client so `compositeScore` is mapped to `scenicScore`
  - file: `frontend/moodride-web/src/lib/api.ts`
  - verification: `npm run lint` passed
- Implemented frontend multi-option consumption in `moodride-web`:
  - added `routeOptions` typings to both `RouteJobStatusResponse` and `RouteDetailResponse`
  - updated completion handling to resolve a primary route from `routeId` or `routeOptions` (prefers `most_scenic`)
  - added route option selector UI in `RoutePlanner` for `most_scenic` / `balanced` / `shorter`
  - added on-demand route-detail loading per option with in-session caching to avoid repeat fetches
  - synchronized per-route rating state when switching between route options
- Added frontend styles for route option cards in `globals.css`.
- Validation:
  - `npm run lint` passed
  - `npm run build` passed
  - Live web UX proof captured (user-run):
    - Job: `0e358b49-9857-4035-880b-c2655fab94fd`
    - Final status: `COMPLETED` via websocket/polling flow
    - Route options rendered in UI with selectable profiles:
      - `most_scenic` (`39.3 km`, `55 min`, `score 30.00`)
      - `balanced` (`36.3 km`, `50 min`, `score 30.00`)
      - `shorter` (`29.7 km`, `42 min`, `score 30.00`)
    - Route details/map/highlights/rating panel all rendered for selected option (`most_scenic`)
- Hardened multi-option profile persistence and mapping:
  - added migration `V17__persist_route_option_profile.sql` to add `routes.route_profile`, backfill existing route rows per job, and enforce allowed values
  - route-worker now persists option profile labels on generated routes (`most_scenic`, `balanced`, `shorter`)
  - route-api now builds route options using persisted profile labels (with legacy fallback), and resolves primary route id from `most_scenic` when available
  - extended `RouteServiceTest` coverage for profile-ordered responses independent of generation timestamp ordering
- Implemented multi-option route generation and response wiring:
  - `route-worker` now generates and persists up to 3 distinct hybrid options per job
  - profiles are emitted in order: `most_scenic`, `balanced`, `shorter`
  - job primary `routeId` remains the first option (most scenic) for backward compatibility
- Added schema migration to enable multi-option persistence per job:
  - `V16__allow_multiple_routes_per_job.sql`
  - drops `routes_job_id_key` uniqueness and adds `idx_routes_job_generated_at (job_id, generated_at)`
- Updated route API contract responses to include route options:
  - `RouteJobStatusResponse.routeOptions`
  - `RouteDetailResponse.routeOptions`
  - each option includes profile label, route id/url, scenic score, distance, and duration
- Added API/worker test coverage for the option flow:
  - `RouteServiceTest` validates option metadata in job status/detail responses
  - `RoutePlannerTest` validates 3 distinct option selection behavior
- Verified end-to-end multi-option proof after V16 schema fix:
  - Job: `2e47ece5-826d-4ee0-b284-217ebd5aa60e`
  - Final status: `COMPLETED`
  - Job status `routeOptions`: `3` (`most_scenic`, `balanced`, `shorter`)
  - Primary route detail: `71656515-cfbd-452f-aba1-e7b10a53607f`
  - Route detail metadata: `algorithmVersion=hybrid_osrm_v1`, `routeOptions=3`
  - DB validation: `routes` contains `3` rows for the job id
- Added explicit component-score schema support and backfill migration:
  - `water_score`, `green_score`, `elevation_score`, `solitude_score`, `curve_score`, `poi_score`
  - migration file: `V15__add_component_scores_to_scenic_tiles.sql`
- Updated scenic scoring pipeline write paths to persist component scores across ingestion and recompute flows.
- Rebuilt and reinstalled shared dependencies (`data-models` + ingestion chain) to resolve runtime classpath mismatch during batch execution.
- Applied component-score migration to Postgres and validated schema constraints/ranges.
- Executed full scenic recompute job (`POST /api/ingestion/jobs/scenic-score` with all tiles):
  - final status: `COMPLETED`
  - final step stats: `read=211492`, `write=211428`, `commitCount=211493`
  - `scenic_score_tiles` count validated at `211510`
  - component-score validation:
    - null component rows: `0`
    - rows with any non-zero component: `211510`
    - fully-zero component rows: `0`
- Verified OSRM trip endpoint availability against local OSRM:
  - `GET /trip/v1/driving/...` returned `200`
  - response contained `code=Ok`, `trips=1`, with distance/duration values
- Removed beam fallback execution from `RoutePlanner`; route generation now fails fast when no hybrid candidate is available.
- Updated `RoutePlanner` hybrid selection flow:
  - when no in-budget route exists, it now tries smaller budget-rescue variants and then fails cleanly if the request is still infeasible
  - when no hybrid route can be produced within the hard budget window, route generation fails without loading graph/beam components
- Added synthetic waypoint fallback generation for hybrid mode:
  - if tile-derived waypoint rings produce no valid OSRM route, route-worker now attempts synthetic radial waypoint variants
  - successful synthetic fallback still persists `algorithmVersion=hybrid_osrm_v1` and keeps `beamCandidates` null
- Added route-worker unit tests for `RoutePlanner` hybrid behavior:
  - synthetic fallback candidate generation path
  - over-budget-only candidate rejection path
  - failure path when OSRM produces no candidates
  - verified passing with `mvn --% -Dmaven.repo.local=... -pl services/route-worker -am -Dtest=RoutePlannerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Added route-worker Kafka consumer flexibility for local recovery runs:
  - listener `group-id` override (`moodride.kafka.listener.group-id`)
  - `auto-offset-reset` override passthrough from `spring.kafka.consumer.auto-offset-reset`
- Reduced listener default concurrency to `1` via config (`moodride.kafka.listener.concurrency`) to lower rebalance churn and memory pressure.
- Added `route_jobs.preference_vector` Flyway migration: `V13__add_route_job_preference_vector.sql`.
- Added preference vector persistence to shared `RouteJob` model.
- Added route API normalization and serialization of `preferenceVector` into `RouteJob`.
- Implemented `OsrmTripClient` in worker:
  - `GET /trip/v1/driving/...` call
  - basic error handling
  - polyline decoding into route coordinates
- Reworked `RoutePlanner` to:
  - map vibe plus optional preference overrides to weighted scenic preferences
  - select nearby H3 tiles and score by weighted components
  - generate sector-based waypoint rings (8/6/4 variants)
  - score returned OSRM route corridors using H3-sampled scenic density
  - pick best candidate by scenic density and budget fit
  - use synthetic radial waypoint variants when tile-derived variants fail
- Added worker configuration keys for hybrid and OSRM tuning in `application.yml`.
- Added OSRM Docker wiring:
  - `osrm-prepare` one-shot preprocessing service
  - `osrm` runtime service on host port `5002`
  - root and `infrastructure/docker` compose stacks
  - startup script now includes OSRM infra bootstrap
- Added per-job algorithm metadata persistence and response wiring:
  - new `route_jobs.algorithm_version` and `route_jobs.beam_candidates`
  - worker writes `hybrid_osrm_v1` for generated routes (beam fallback removed)
  - route API now returns these values instead of hardcoded beam metadata
- Verified compile for `route-api` and `route-worker` (`mvn ... -pl services/route-api,services/route-worker -am compile`).
- Verified end-to-end proof with isolated validation worker (`hybrid-only=true`, latest offsets, unique group id):
  - Job: `40630212-1288-45fc-8d31-b6f24167f5b8`
  - Final status: `COMPLETED`
  - Route: `dbe9b7b8-19f4-4cdd-88a1-f2af623ab617`
  - Route detail metadata: `algorithmVersion=hybrid_osrm_v1`, `beamCandidates=null`
- Verified post-beam-removal end-to-end proof with isolated validation worker (latest offsets, unique group id):
  - Job: `d1a8d576-bd53-4a71-a219-19ffcf504b2d`
  - Final status: `COMPLETED`
  - Route: `233587fa-7ffd-42fe-9a49-52db7892ef45`
  - Route detail metadata: `algorithmVersion=hybrid_osrm_v1`, `beamCandidates=null`

## Known Gaps / Next Build Slice
- Execution-plan checklist items are completed.
- UX proof is complete for multi-option rendering and completed-state flows.
- Remaining quality hardening:
  - Re-run live route jobs after worker deploy and confirm short budgets do not return large over-budget loops.
  - Re-run Banff/Rockies release QA and confirm route-option spread improves versus the previous `1.19-1.55` point spread.
  - If Banff spread is still too low, add waypoint-sector exclusion per profile so secondary options are forced into different bearings/corridors.
