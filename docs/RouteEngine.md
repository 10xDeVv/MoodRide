# Wayward Route Engine

Last reconciled: 2026-07-19

Wayward's active route algorithm is `hybrid_osrm_v2`. It keeps OSRM responsible for legal drivable geometry while Wayward decides which candidate places should shape the loop and how well the returned route satisfies the selected vibe.

## High-Level Flow

Route submission precedes this worker flow. `route-api` returns HTTP `202 Accepted` after one database transaction commits the `QUEUED` job and its durable dispatch outbox record. An asynchronous dispatcher publishes the record to Kafka, records acknowledgement only after the broker ACK, and retries durable unacknowledged records. Submission acceptance therefore does not wait for immediate broker acknowledgement or worker receipt.

1. `route-worker` consumes a Kafka `route-jobs` message.
2. `RouteGenerationService` calls `RoutePlanner.generateRouteOptions`.
3. The planner resolves selected vibes through `VibeCatalog`.
4. Nearby H3 scenic tiles are loaded through `ScenicTileLookupService`.
5. Road-aware anchors are selected through `RoadSegmentAnchorService` when road data is available.
6. Candidate waypoint variants are generated.
7. Each variant is sent to local OSRM `/trip`.
8. Returned geometries are sampled against H3 scenic tiles.
9. Candidates are scored, filtered, deduped, and selected as:
   - `most_scenic`
   - `balanced`
   - `shorter`
10. Score breakdowns and route explanations are persisted and returned by the API.

First-result delivery does not split this planning work into stages. The worker computes the full candidate pool—including candidate generation, OSRM calls, scoring, filtering, deduplication, and option selection—before it publishes `PRIMARY_READY`. The revisioned lifecycle is `QUEUED -> PROCESSING -> PRIMARY_READY -> COMPLETED`, or the terminal state `FAILED`/`TIMEOUT`, with lease fencing and stale-revision rejection. `PRIMARY_READY` lets the client fetch and paint the slim exact primary before rich details, but this only removes post-planning response/enrichment delay. It is not staged route computation, and no new end-to-end p50/p95 latency is claimed here.

OSRM is not a paid external endpoint in the default stack. It is a local service/container using prepared OSM data.

## Candidate Generation

The v2 planner builds candidate waypoints from multiple strategies:

- high-scoring sector rings around the start point
- intent anchors from high-fit scenic tiles
- water-following variants
- open-space escape variants
- photo/viewpoint peak variants
- quiet low-pressure variants
- curvy/elevation variants
- balanced-variety variants
- budget and diversity rescue variants

Scenic H3 tile centers are no longer the only anchor source. When possible, scenic intent is translated into nearby road segment anchor points, favoring lower-stress and better-shaped roads.

## Scoring Contract

The persisted `Route.scenicScore` is route quality, not only raw scenery.

## Score Scales

Wayward uses two score scales:

- Internal algorithm and database scores are normalized from `0.0` to `1.0`.
- Public API/frontend route scores are shown as `0` to `100` and should be called `Scenic Match`.

Examples:

| Internal | Public | Meaning |
| --- | --- | --- |
| `0.25` | `25` | weak scenic/vibe fit |
| `0.50` | `50` | moderate scenic/vibe fit |
| `0.80` | `80` | strong scenic/vibe fit |

Tile/component scores such as `water_score`, `tree_canopy_score`, and `road_stress_score` remain `0.0` to `1.0`. Route responses multiply the stored route score by `100.0` so users and analytics see one readable `0-100` scale.

The v2 score blends:

- landscape quality
- vibe fit
- drive quality
- route shape and budget fit
- scenic moments and continuity
- urban pressure
- start/end quality
- strategy mismatch penalty
- backtracking/repeated-road penalty

Important score-breakdown keys include:

- `final_score`
- `landscape_score`
- `vibe_fit_score`
- `drive_quality_score`
- `route_shape_score`
- `scenic_moments_score`
- `urban_penalty`
- `start_end_penalty`
- `corridor_urban_pressure`
- `edge_urban_pressure`
- `road_stress_score`
- `water_visibility_score`
- `water_crossing_score`
- `coastal_road_score`
- `tree_canopy_score`
- `scenic_poi_score`
- `viewpoint_score`
- `bridge_coastal_score`
- `strategy_fit_score`
- `strategy_mismatch_penalty`
- `backtracking_penalty`
- `duration_fit_ratio`
- `geometry_strategy_code`

## Vibe Contracts

Wayward treats vibes as product promises, not just score labels.

Examples:

- `coastal` and `riverside` need meaningful water signal.
- `mountain`, `winding_roads`, and `adventure` need enough elevation/curve signal.
- `quiet`, `open_roads`, `minimal_traffic`, `countryside`, and `clear_my_head` need lower-pressure corridors.
- `forest` and `nature_escape` benefit from tree canopy/green signal.
- `photo_worthy`, `date_night`, `hidden_gems`, and `sunset` use scenic POI, viewpoint, and bridge/coastal moment signals.

If strict vibe families cannot be honestly satisfied nearby, the worker can return `vibe_unavailable` instead of pretending a weak route matches the request. The API turns this into:

- `failureCode`
- `userMessage`
- `suggestedVibes`
- `suggestedActions`

## Route Explanations

Every route option can expose a first-class explanation payload:

- `summary`: plain-language reason for the option
- `humanReasons`: supporting sentences
- `componentAverages`: route component values
- `componentLifts`: local baseline comparison
- `weightedContributions`: what influenced the score most
- `contractFlags`: machine-readable pass/fail checks
- `contractWarnings`: plain-language warnings

This helps both users and debugging. A route that claims to be coastal but has weak water share can be caught by route-quality contracts.

## Scenic Tiles

Tile scores are computed offline. They are not labels inherent in raw datasets.

`scenic_score_tiles` stores a feature vector per H3 tile, including:

- `water_score`
- `green_score`
- `elevation_score`
- `solitude_score`
- `curve_score`
- `poi_score`
- `park_score`
- `overture_poi_score`
- `building_density_score`
- `darkness_score`
- `urban_penalty_score`
- `road_stress_score`
- `water_visibility_score`
- `water_crossing_score`
- `coastal_road_score`
- `tree_canopy_score`
- `scenic_poi_score`
- `viewpoint_score`
- `bridge_coastal_score`

Runtime route generation samples these tile vectors along returned OSRM corridors.

### Scenic Tile Lookup Cache

Runtime lookup checks a local LRU first. For remaining tile IDs it uses one Redis `MGET`, resolves all Redis misses with one bulk SQL query, and pipelines Redis fill writes.

This scenic cache is one of the shared data-plane contracts, alongside road-segment metadata and regional popularity. Route payload caches intentionally differ: the API's versioned rich-detail DTO uses `routeDetailsV2::route:detail:v2:<routeId>`, while the worker's internal persisted result uses `routeResults::route:result:<routeId>`. The parity checker validates both distinct route contracts as well as the shared data-plane contracts; it does not require route payloads to share a cache shape.

Measured cache integration evidence used a real authenticated Redis 7.0-alpine instance and 1,500 deterministic tiles:

| Cache operation or state | Measured time |
| --- | ---: |
| Serial Redis `GET` baseline | 3,213 ms |
| Single Redis `MGET` | 90 ms |
| Cold SQL lookup plus pipelined Redis fill | 685 ms |
| Redis-warm service lookup | 140 ms |
| Local-warm service lookup | 2 ms |

These are scenic-tile cache microbenchmark/integration timings only. They establish cache round-trip behavior, not route-generation or end-to-end first-result latency, and do not support a new route p50/p95 claim.

## Scenic Data Releases

Current data-quality train:

- `3.1`: darkness and urban penalty calibration
- `3.2`: road stress / road class
- `3.3`: water visibility, water crossings, coastal road signal
- `3.4`: tree canopy proxy
- `3.5`: scenic POI / discovery places
- `3.6`: viewpoints and photo landmarks
- `3.7`: bridge/coastal moments

The current local scenic baseline is:

- `3.7-bridge-coastal-calibration`

Next data-quality candidate:

- seasonal suitability / access warnings

Seasonality should start as warnings or metadata, not a heavy scoring factor, because seasonal/access tags can be sparse.

## Raw Inputs

Offline enrichment uses:

- OpenStreetMap roads
- Natural Earth water geometry
- land-cover raster
- DEM/elevation raster
- protected/conserved area geometry
- Overture Places and Buildings
- light-pollution/nighttime-light raster

Runtime services should not read or recompute these raw datasets during user route generation.

## Duration Calibration

`route_duration_calibrations` learns how requested waypoint geometry maps to OSRM durations.

The calibration bucket uses:

- route mode
- broad H3 region
- time-budget bucket
- geometry strategy

The planner only applies mature buckets after enough samples and clamps radius changes, so a few weird routes cannot reshape future routing too aggressively.

## Route Quality Evaluation

`scripts/monitoring/run-route-quality-eval.ps1` runs fixed scenarios against local or production APIs.

It checks:

- completion status
- option count
- duration/budget fit
- score spread
- geometry separation
- explanations
- route contract flags
- score-breakdown metrics
- weak-vibe signals
- repeated-road/backtracking risk

Use the CSV first, sort by `flags`, then inspect the JSON for detail.

Evaluation vocabulary matters:

- A **route-return count** is the number of frozen scenarios that technically completed and returned route output.
- A **quality-pass rate** would require those returned routes to clear the defined geometry, contract, spread, and behavior checks.

The matched historical artifacts below establish route-return counts, not a clean quality-pass rate. A technical completion must not be reported as a usable-route or quality-pass result.

The important tuning rule is:

```text
Do not tune constants blindly.
Tune against repeated route-quality failures and real route behavior.
```

## Historical Comparison

| Algorithm | Current role |
| --- | --- |
| `beam_v1` | Removed from active codebase. Historical comparison only. |
| `hybrid_osrm_v1` | Replaced by v2. Useful as a conceptual predecessor. |
| `hybrid_osrm_v2` | Current default route-generation contract. |

`hybrid_osrm_v2`/Legacy is the selected production foundation, but it is not documented as functionally complete or as a proven final architecture.

### Matched Frozen Controls

All rows use the same 27-scenario selected manifest, SHA-256 `2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00`.

| Source control | Route-engine configuration | Route returns | Job-processing p50 / p95 |
| --- | --- | ---: | ---: |
| `c54e7ae6a33bf6014225b9f714fe48037c9e9443` | Legacy | 14/27 | 1,132 / 2,389 ms |
| `c54e7ae6a33bf6014225b9f714fe48037c9e9443` | PURE Design B anchor | 2/27 | 1,991 / 4,760 ms |
| `c54e7ae6a33bf6014225b9f714fe48037c9e9443` | PURE Design B road-chain | 0/27 | n/a |
| `0fda4ca7a98b02d067dea4d0d14417afe12fc3f1` | Legacy | 14/27 | 1,242 / 2,834 ms |
| `0fda4ca7a98b02d067dea4d0d14417afe12fc3f1` | PURE Design B anchor | 4/27 | 2,585 / 3,426 ms |

These are job-processing latency percentiles from the named controls, not end-to-end first-result measurements. On this matched evidence, retain `hybrid_osrm_v2`/Legacy as the production foundation and reject PURE Design B for production: Legacy returned routes in more frozen scenarios and had lower job-processing p50/p95 in both matched anchor comparisons. This is a bounded release decision, not proof of a final architecture or launch-quality routing.

Legacy still needs quality hardening. Its route-return scenarios include repeated-corridor, backtracking, urban-pressure, edge-pressure, and low-spread failures, so the 14/27 counts are not clean usable-route results.

### Evidence Limits and Current Release Status

The archived selected manifest is hash-verified, but the historical canonical path `scripts/monitoring/benchmarks/m0-route-quality-scenarios-20260710.json` is absent. The checked-in runner also lacks the historical commit, image, runtime-mode, manifest-copy, and diagnostics provenance fields. Reusing the archived manifest with that runner would create a new reconstructed harness; it would not retroactively establish provenance for the historical runs.

The earlier OSRM/scenic prerequisite blockage has been resolved. The current local source gate at `artifacts/route-quality-eval/current-source-full-gate-20260719/provenance.json` ran all 27 scenarios with restored scenic data and an identified OSRM image/dataset: 16 technically completed and 11 ended `vibe_unavailable`. All 16 completions retained route-quality contract failures, leaving zero clean contract-qualified completions.

That gate used host JARs from a dirty worktree rather than immutable labeled images. It is local source-level evidence, not production evidence. The immutable-image gate and production deployment have not been completed.

The pre-deploy browser artifact `artifacts/latency-release/baseline-cohort-browser-20260719.json` records 25 of 25 completed, payload-matched, current-job-attributed routes with click-to-visible p50 `10,167 ms`, p90 `13,873 ms`, and p95 `14,849 ms`. Its commit is inferred from the latest successful deployment workflow run: `runtimeCommitVerified` is `false`, and the live container digest and revision label were not observed. Do not claim runtime-verified commit provenance or a causal before/after latency change until a matched, runtime-attributed post-deploy cohort exists.

The scenic-cache microbenchmark and historical-control reconciliation sidecar remains `artifacts/route-quality-eval/first-latency-release-20260719/evidence.json`.
