# Wayward Route Engine

Last reconciled: 2026-07-01

Wayward's active route algorithm is `hybrid_osrm_v2`. It keeps OSRM responsible for legal drivable geometry while Wayward decides which candidate places should shape the loop and how well the returned route satisfies the selected vibe.

## High-Level Flow

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

V2 is functionally complete as the current route engine. Future work is calibration, feedback learning, and quality hardening.
