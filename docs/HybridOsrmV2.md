# Hybrid OSRM v2

`hybrid_osrm_v2` is the current Wayward route generation algorithm. It keeps OSRM responsible for legal road geometry, while Wayward decides what kinds of places should shape the loop and how good the returned corridor is for the user's vibe.

The important change from v1 is that v2 is a clearer contract, not a random constant tweak. It separates:

- landscape quality
- vibe fit
- driving quality
- route shape and budget fit
- scenic moments and continuity
- urban/start/end penalties
- duration calibration for radius and waypoint count

## Runtime Flow

1. The route worker consumes a `route-jobs` Kafka message and loads the `RouteJob`.
2. `RouteGenerationService` calls `RoutePlanner.generateRouteOptions`.
3. `RoutePlanner` resolves the requested vibe or blended vibes through `VibeCatalog`.
4. Nearby H3 scenic tiles are loaded from `scenic_score_tiles`.
5. The planner looks up any mature duration calibration bucket for route mode, broad H3 region, time-budget bucket, and geometry strategy.
6. Tiles are scored with the request preference weights and the active vibe profile.
7. V2 builds waypoint candidates in three ways:
   - sector rings from high-scoring nearby tiles
   - intent-anchor variants from high-fit tiles with radius and separation constraints
   - strategy-specific variants for water-following, open-space, quiet, photo-peak, curvy/elevation, or balanced routes
8. Each waypoint variant is sent to OSRM Trip:
   - default local base URL: `http://127.0.0.1:5002`
   - endpoint shape: `/trip/v1/{profile}/{lng,lat;...}`
   - request options include `roundtrip=true`, `source=first`, `overview=full`, `geometries=polyline`
9. OSRM returns a drivable loop geometry, distance, and duration.
10. V2 samples the returned corridor, maps samples back to H3 scenic tiles, computes route quality, filters over-budget candidates, deduplicates, and selects up to three profiles:
   - `most_scenic`
   - `balanced`
   - `shorter`
11. Persisted successful route options update the duration calibration aggregate for future requests.

OSRM is not a paid external API in the default Wayward stack. It is a local container/service built from the prepared OSM dataset.

## V2 Route Score

The persisted route score still lands in `Route.scenicScore`, but v2 treats that value as route quality, not only raw scenic density.

Current v2 score:

```text
route_score =
  landscape_score      * 0.38
+ vibe_fit_score       * 0.24
+ drive_quality_score  * 0.14
+ route_shape_score    * 0.10
+ scenic_moments_score * 0.14
- urban_penalty        * 0.10
- start_end_penalty    * 0.06
- strategy_mismatch    * 0.08
- backtracking_penalty * 0.08
```

The components mean:

- `landscape_score`: average request-weighted tile score along the returned OSRM corridor.
- `vibe_fit_score`: how well corridor tiles match the active vibe's target and anti-components.
- `drive_quality_score`: curvature plus lower urban pressure.
- `route_shape_score`: budget fit, useful budget utilization, and loop closure.
- `scenic_moments_score`: peak scenic value, continuous good stretches, and consistency.
- `urban_penalty`: corridor urban pressure from `urban_penalty_score`, building density, and road density.
- `start_end_penalty`: edge pressure when the beginning/end of the loop are low-scenic or high-urban.
- `corridor_urban_pressure`: explicit alias for corridor-level urban pressure used by contract checks.
- `edge_urban_pressure`: explicit alias for start/end urban pressure used by contract checks.
- `road_stress_score`: corridor-level road-class stress, where lower values mean calmer/local roads and higher values mean faster or more major road context.
- `tree_canopy_score`: corridor-level tree-cover proxy from land-cover classes, used mostly for forest/nature explanations and scoring.
- `strategy_mismatch`: soft penalty when the returned OSRM corridor does not match the active geometry strategy.
- `backtracking_penalty`: route-craft penalty for repeated corridor cells, out-and-back overlap, poor leg separation, and near-duplicate/self-overlapping geometry.

These weights are intentionally named constants so they can be tuned against route feedback and QA baselines later.

Strategy corridor fit uses graded membership, not binary yes/no tile labels. For example, a quiet or countryside corridor earns partial credit from solitude, greenery, low urban pressure, and darkness even when a tile does not clear a single hard threshold. Open, quiet, and curvy strategies also consider the strongest meaningful stretch of the corridor, so an otherwise good escape route is not treated as a total mismatch only because the start/end near the city are less scenic.

Strict vibe families have an additional quality gate after OSRM returns candidate loops. Mountain, winding roads, and adventure must produce options with enough curve/elevation corridor share and strategy fit. Open roads, countryside, quiet, minimal traffic, Sunday Cruise, and clear my head must produce options with enough low-pressure/quiet corridor share and acceptable urban pressure. If the planner cannot produce three options within the requested budget, the job fails with vibe unavailable rather than returning a route that contradicts the selected vibe.

When a vibe is unavailable, the worker records a normal failed route job with a user-facing reason. The route API turns that into structured guidance:

- `failureCode`: currently `vibe_unavailable` for strong vibe/region mismatches.
- `userMessage`: the plain-language reason from route generation.
- `suggestedVibes`: fallback vibes such as `scenic`, `open_roads`, or `relaxing`.
- `suggestedActions`: practical next steps such as trying a fallback vibe, increasing the time budget, or moving the start point farther from downtown.

The web planner renders these as a `Vibe Unavailable` state with fallback vibe buttons. This is part of the v2 contract: honest failure is preferable to returning a route that contradicts the selected mood.

Each generated route also stores a JSON score breakdown in `routes.score_breakdown_json`. Route detail and route option API responses expose this as `scoreBreakdown` for QA and future route explanations. Current keys include:

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
- `strategy_fit_score`
- `strategy_mismatch_penalty`
- `repeated_corridor_cell_share`
- `reverse_overlap_share`
- `leg_separation_score`
- `self_intersection_or_near_duplicate_score`
- `backtracking_penalty`
- `water_corridor_share`
- `open_space_corridor_share`
- `quiet_corridor_share`
- `photo_peak_score`
- `curve_elevation_corridor_share`
- `requested_avg_radius_km`
- `requested_waypoint_count`
- `duration_fit_ratio`
- `duration_calibration_bucket_minutes`
- `corridor_tile_samples`
- `target_minutes`
- `duration_minutes`
- `geometry_strategy_code`

The route API translates this score breakdown into a first-class explanation payload on each route option:

- `summary`: human-readable text for the product UI.
- `humanReasons`: supporting explanation sentences.
- `contractFlags`: machine-readable route contract pass/fail checks.
- `contractWarnings`: plain-language warnings for failed contracts.

Current contract checks cover time budget fit, loop closure, repeated-road/backtracking risk, leg separation, corridor urban pressure, edge/start-end urban pressure, scenic peak strength, water share for coastal/riverside-style vibes, elevation/curve share for mountain/winding/adventure vibes, quiet share for rural/relaxing vibes, tree-canopy signal for forest/nature vibes, and photo/POI signal for photo-worthy/date-night/discovery vibes. The route-quality eval script promotes failed contract flags into scenario flags so tuning can be based on repeatable evidence instead of visual guessing.

Urban pressure is intentionally split. `corridor_urban_pressure_ok` answers whether the drive itself stays too urban. `edge_urban_pressure_ok` answers whether the beginning or end is contaminated by the start location. The legacy `urban_pressure_ok` flag now mirrors corridor pressure so city-start routes are not broadly punished only because the first or last blocks are urban.

Repeated-road v2 is intentionally a scoring/ranking signal, not an availability gate. Strict vibe gates answer whether Wayward can honestly offer a vibe in that area. Backtracking penalties answer whether a completed candidate feels good enough to prefer over another candidate. The planner subtracts a global backtracking penalty from v2 score, then applies stronger profile-specific ranking pressure to `most_scenic`, moderate pressure to `balanced`, and lighter pressure to `shorter`.

Geometry strategy codes:

- `0`: `WATER_FOLLOWING` for coastal, riverside, sunset/golden-hour style routes.
- `1`: `OPEN_SPACE_ESCAPE` for open roads.
- `2`: `PHOTO_PEAKS` for photo-worthy, photo run, date night, and hidden gems.
- `3`: `QUIET_LOW_PRESSURE` for quiet, minimal traffic, relaxing, countryside, forest, nature escape, scenic reset, and similar calm routes.
- `4`: `CURVY_ELEVATION` for mountain, winding roads, and adventure.
- `5`: `BALANCED_VARIETY` for balanced scenic variety when no stricter strategy applies.

## Duration Calibration

`route_duration_calibrations` learns how OSRM durations compare with the waypoint geometry Wayward requested.

The calibration key is:

- route mode, such as `drive`, `walk`, or `bike`
- broad regional H3 cell at resolution 5
- time-budget bucket rounded to 15 minutes
- geometry strategy, such as `WATER_FOLLOWING` or `OPEN_SPACE_ESCAPE`

For each persisted successful route option, the worker records:

- requested average waypoint radius
- requested waypoint count
- target minutes
- OSRM duration minutes

The aggregate stores a bounded `radius_multiplier`, a `learned_waypoint_count`, average requested geometry, average duration ratio, sample count, and update time. The planner only applies a bucket after at least three samples, and clamps radius multipliers to `0.75` through `1.25`, so early outliers do not immediately reshape routing.

This is deliberately conservative. Calibration nudges the main scenic/intent/strategy waypoint generation; budget and diversity rescue rings remain heuristic fallback paths.

## H3 Scenic Tiles

The tile scores are not inherent labels in the raw datasets. Wayward computes and stores them in `scenic_score_tiles`.

Examples:

- `water_score`: derived from proximity or relationship to water geometry; v3.3 can lift this with water visibility, crossing, and coastal-road evidence.
- `green_score`: derived from land-cover classes such as forest, shrub, grassland, cropland, and urban.
- `elevation_score`: derived from DEM terrain variation.
- `solitude_score`: derived from low urban proportion, low road/building density, and darkness.
- `curve_score`: derived from winding-road character in road geometry.
- `poi_score` and `overture_poi_score`: derived from aggregated POI/place data.
- `scenic_poi_score`: derived from weighted scenic/discovery Overture Places categories such as lookouts, parks, natural features, landmarks, museums, galleries, wineries, and historic places.
- `viewpoint_score`: focused photo/viewpoint signal derived from higher-intent Overture categories such as lookouts, waterfalls, lighthouses, mountains, beaches, bridges, monuments, and landmarks.
- `bridge_coastal_score`: bridge, pier, marina, lighthouse, beach, waterfall, and water-road moment signal derived from v3.3 water-road metrics plus a small Overture hint subset.
- `park_score`: derived from protected or conserved area geometry.
- `building_density_score`: derived from Overture building footprints.
- `darkness_score`: derived from light-pollution/nighttime-light data.
- `urban_penalty_score`: derived from building density and urban pressure.
- `road_stress_score`: derived from OSM road class, speed limit, surface, and segment length.
- `water_visibility_score`: derived from road length close enough to water to plausibly feel water-adjacent.
- `water_crossing_score`: derived from water intersections and optional OSM bridge/waterway/ford hints.
- `coastal_road_score`: derived from roads that run close to water rather than merely being in a water-proximate tile.
- `tree_canopy_score`: derived from land-cover forest and woody classes as a canopy proxy.

Each H3 tile is a precomputed scenic feature vector. Runtime routing samples those vectors; it does not recompute land cover, DEM, Overture buildings, darkness, scenic-place categories, or park geometry from scratch.

## What Scenic Means

Wayward should treat `scenic` as a balanced default, not a universal truth. A user may mean:

- nature: trees, parks, water, terrain, low urban density
- driving pleasure: curves, elevation changes, lower urban pressure
- views: water, ridgelines, open space, photo-worthy peaks
- discovery: POIs, small-town moments, unusual stops
- calm: quiet roads, greenery, darkness, low building density

The current `scenic` vibe is a balanced blend across water, greenery, elevation, solitude, curves, and a lower POI contribution. More specific vibes such as `coastal`, `open_roads`, `photo_worthy`, `quiet`, and `date_night` should change both score weights and route-shaping strategy.

## Comparison

| Algorithm | How it builds routes | Strengths | Weaknesses | Current role |
| --- | --- | --- | --- | --- |
| `beam_v1` | Expanded an internal road graph step-by-step with beam width, iteration, and timeout limits. | Full control over search objective and pruning. | Hard to guarantee legal/drivable quality, expensive to tune, more sensitive to graph quality, and slower to productize. | Removed from the active codebase; kept here only as historical comparison. |
| `hybrid_osrm_v1` | Picks scenic H3 tiles by sector, builds waypoint rings, asks OSRM Trip for loops, then averages scenic density along the returned corridor. | Practical, fast, uses OSRM for legal road geometry, works with precomputed scenic tiles. | Scenic score is mostly average density, anchor intent is limited, diversity happens mostly after OSRM returns routes, start/end quality and scenic continuity are weakly represented. | Replaced by v2. |
| `hybrid_osrm_v2` | Builds calibrated sector rings plus intent-anchor and strategy variants, asks OSRM Trip for loops, then scores corridors with landscape, vibe fit, drive quality, route shape, scenic moments, urban pressure, start/end penalties, and strategy corridor fit. | Clearer contract, better per-vibe shaping, better distinction between average scenery and memorable stretches, stronger urban/start/end penalties, soft validation that OSRM actually returned the intended corridor type, learns radius/waypoint fit from successful OSRM durations, still benefits from OSRM road solving. | Still heuristic until user feedback calibration is trained; duration calibration is aggregate/bucketed rather than personalized. | Current default algorithm. |

## Improvements Over V1

- Better anchor selection: v2 uses intent anchors for all vibes, not only generic sector winners.
- Better option diversity input: more distinct waypoint geometries are offered to OSRM before post-selection penalties run.
- Better corridor scoring: v2 rewards peaks, continuity, consistency, low urban pressure, and good loop shape instead of only average scenic density.
- Better semantics: route quality is split into named ingredients, so tuning can target actual product behavior.
- Better product fit: vibes now change route-shaping geometry strategy, not only score weights.
- Better corridor validation: v2 now reports and softly penalizes strategy mismatch, such as a coastal route with too little water corridor share.
- Better honesty for rural vibes: countryside and Sunday Cruise now prefer farther quiet-escape anchors and fail as unavailable if returned corridors remain too urban.
- Better time-budget fitting: v2 now persists duration calibration buckets that can nudge radius and waypoint count after enough local samples.

## Remaining V2 Work

The current implementation includes v2 candidate generation, per-vibe geometry strategies, graded strategy-specific soft corridor validation, rural/country unavailable gates, duration calibration, v2 route-quality scoring, persisted score breakdowns, QA evaluation flags, and frontend/API guidance for unavailable vibes.

For the current release, `hybrid_osrm_v2` is functionally complete as Wayward's default route-generation contract. Remaining work is product calibration and quality hardening, not the core v2 build:

- Tune strategy-fit and route-contract thresholds from archived QA runs and user feedback, then decide which mismatches should become hard filters instead of soft penalties.
- Add route feedback calibration so thumbs-up/down and completed drives can tune component weights.
- Tune duration-calibration sample thresholds and bucket sizes after more real route history exists.
- Add more specific unavailable guidance per vibe family, for example mountain in flat regions versus countryside near dense downtown starts.
- Keep archiving fixed-start QA runs and compare against `artifacts/route-quality-eval-local-v2-rural-escape-full/route-quality-eval-20260608-183929.md`.

## Operational Notes

- `h3-resolution`, `tile-selection-ring-*`, `tile-selection-limit`, `sector-count`, `corridor-sample-meters`, and `max-duration-overrun-ratio` affect v2.
- OSRM timeout and base URL settings still matter because v2 depends on OSRM Trip for candidate geometry.
- Route-worker `moodride.cache.graph-warmup.enabled` defaults to `false`. That warmup path loads the legacy internal road graph and is not needed by `hybrid_osrm_v2`, which delegates road solving to OSRM.
