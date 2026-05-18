# MoodRide Scenic Route Flow

This document describes how MoodRide generates a scenic route today, based on the current implementation in `route-api`, `route-worker`, and `scenic-scoring-service`.

## 1. Entry Point

The main entrypoint is `POST /api/routes` in [RouteController](../services/route-api/src/main/java/com/moodride/routeapi/controller/RouteController.java).

The frontend submits a payload shaped like this:

```json
{
  "userId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "lat": 45.5152,
  "lng": -122.6784,
  "timeBudgetMinutes": 90,
  "vibes": ["coastal", "mountain"],
  "routeMode": "drive",
  "preferenceVector": {
    "water": 0.8,
    "greenery": 0.6,
    "elevation": 0.4,
    "solitude": 0.5,
    "curves": 0.5,
    "poi": 0.2
  }
}
```

`RouteRequest` accepts either `vibes[]` or a single `vibe`. The API stores the first validated vibe in `route_jobs.vibe` for legacy compatibility and stores the full list in `route_jobs.vibes_json`.

## 2. Service Flow

1. The client posts to `POST /api/routes`.
2. [RouteService](../services/route-api/src/main/java/com/moodride/routeapi/service/RouteService.java) validates vibes, creates a `route_jobs` row, and marks it `QUEUED`.
3. The route API publishes the job id to Kafka on `RouteJobEvent.TOPIC`.
4. [RouteJobConsumer](../services/route-worker/src/main/java/com/moodride/routeworker/consumer/RouteJobConsumer.java) consumes the job, loads the `RouteJob`, marks it `PROCESSING`, and invokes [RouteGenerationService](../services/route-worker/src/main/java/com/moodride/routeworker/service/RouteGenerationService.java).
5. `RouteGenerationService` calls [RoutePlanner](../services/route-worker/src/main/java/com/moodride/routeworker/algorithm/RoutePlanner.java), which builds scenic waypoint rings and asks OSRM for real driving loops.
6. The worker persists up to three completed `routes` and `route_waypoints` rows for `most_scenic`, `balanced`, and `shorter`.
7. The worker publishes a completion event on `RouteCompletionEvent.TOPIC`.
8. The frontend polls `GET /api/routes/{jobId}` and then fetches `GET /api/routes/route/{routeId}` once the job completes.

## 3. Route Generation Logic

The current implementation uses OSRM's trip endpoint against the local Canada OSRM dataset.

The route worker:

- scores nearby H3 scenic tiles using the selected vibes and numeric `preferenceVector`
- divides nearby candidate tiles into directional sectors
- builds waypoint rings from the best tile per sector
- asks OSRM for a round trip from the start through those waypoints
- samples the returned OSRM geometry and computes scenic density from nearby H3 tiles
- filters out candidates that exceed the hard effective time budget
- selects up to three route options with profile-specific scoring and diversity penalties

The current hard budget cap is at most `15%` over the requested time. If no route can be generated inside that window, the job fails with a no-feasible-route reason instead of returning a misleading over-budget route.

## 4. Waypoint / Candidate Selection

Intermediate waypoints are chosen from scenic tiles directly.

[RoutePlanner](../services/route-worker/src/main/java/com/moodride/routeworker/algorithm/RoutePlanner.java) selects nearby candidate tiles by H3 ring, scores them with the request's effective component weights, groups them by sector, and builds waypoint rings. If tile-derived rings do not produce enough valid OSRM routes, it falls back to synthetic radial rings and then smaller budget-rescue rings.

## 5. Scenic Scoring

There are two separate scoring layers:

### Tile scoring

[ScenicScoringProcessor](../services/scenic-scoring-service/src/main/java/com/moodride/scenicscoringservice/processor/ScenicScoringProcessor.java) and [ScenicTileRecomputeService](../services/scenic-scoring-service/src/main/java/com/moodride/scenicscoringservice/service/ScenicTileRecomputeService.java) compute H3 tile scores.

The tile score is a weighted blend of normalized signals:

- `waterProximity` = 0.25
- `elevationVariance` = 0.20
- `naturalLandUse` = 0.20
- `trafficAdjustedRoadDensity` = 0.10
- `poiDensity` = 0.15
- `visualComplexity` = 0.10

The tile recompute pipeline sources values from `road_segments`, water/land-use tables, and traffic tiles, then upserts into `scenic_score_tiles`.

### Route scoring

The route worker computes route-level scenic density by sampling the returned OSRM path, mapping samples to H3 scenic tiles, and averaging the request-weighted tile scores. The stored `Route.scenicScore` is a normalized decimal and is later rendered as a percentage in the API response.

Vibes translate into default component weights:

- `coastal`: high water, greenery, solitude
- `riverside`: high water and greenery
- `mountain`: high elevation, curves, solitude
- `forest`: high greenery and solitude
- `countryside`: balanced greenery, solitude, curves, water
- `open_roads`: high curves/open-driving signal with moderate greenery

The optional `preferenceVector` overrides those defaults for `water`, `greenery`, `elevation`, `solitude`, `curves`, and `poi`.

## 6. Database Usage

Route generation currently touches these tables:

- `route_jobs`
- `road_segments`
- `scenic_score_tiles`
- `routes`
- `route_waypoints`

Typical access patterns:

```sql
SELECT *
FROM scenic_score_tiles
WHERE h3_index IN (?, ?, ?, ...);
```

Route generation itself avoids expensive spatial joins during request time. The scenic-scoring service performs the heavier geospatial queries during tile recomputation.

## 7. Final Output

The first response from `POST /api/routes` is an accepted job payload:

```json
{
  "jobId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "status": "QUEUED",
  "estimatedCompletionSeconds": 5,
  "statusUrl": "/routes/f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "wsChannel": "job:f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

Once complete, `GET /api/routes/route/{routeId}` returns a `RouteDetailResponse` with:

- route geometry as a GeoJSON `LineString`
- total distance and estimated duration
- scenic score
- quality tier
- waypoints and generated highlights
- route options with `most_scenic`, `balanced`, and `shorter` profile metadata
- route-option explanations based on local baseline lift and weighted component contribution
- created and expiration timestamps

## 8. Performance Notes

The main optimizations in the current implementation are:

- asynchronous job handling through Kafka
- batch scenic tile lookup by H3 index
- route detail caching in `route-api`
- cache invalidation when scenic tiles are refreshed or changed

## 9. Current Limitations / Gaps

The current implementation is intentionally simpler than the design spec.

- It depends on OSRM trip behavior, so sparse road networks can still make small time budgets infeasible.
- It does not yet expose "no good route for this vibe here" as a nuanced product state beyond job failure or low route score.
- Vibe availability is implicit: if a user selects `coastal` in a non-water area, water-heavy scoring still runs but may only find weak candidates.
- Route option diversity is improved but still needs live QA in universally scenic areas like Banff/Rockies.
- Segment-level route scores in the response are synthetic presentation values.

## Bottom Line

MoodRide currently generates scenic driving loops by selecting scenic H3 waypoint candidates, asking local OSRM for real Canada driving loops, scoring returned corridors with request-weighted scenic tiles, and returning up to three diversified route options. The result is asynchronous, cache-aware, data-driven, and preference-aware, but it still needs stronger product handling for vibe mismatch and low-quality areas.

## 10. Implementation Roadmap

### Phase 1 - Fix Core Product Quality

1. Make routes true scenic loops.
  - Add a loop-closing constraint.
  - Start with a simple return-to-origin rule near the end of the time budget.
  - Upgrade later to an A* return leg with a small return tolerance.

2. Fix route scoring.
  - Avoid rewarding long routes just because they collect more scenic score.
  - Use scenic density as the primary signal, then blend in efficiency and penalties.

3. Make routing preference-aware.
  - Use `preferenceVector` in the worker.
  - Support multiple vibes instead of only the first one.
  - Blend vibe weights into edge scoring.

4. Fix ETA realism.
  - Use road-type-based speed buckets instead of a fixed speed heuristic.
  - Make the time budget meaningful for different road classes.

### Phase 2 - Improve Routing Intelligence

5. Penalize bad driving experiences.
  - Add penalties for too many turns, too many intersections, and stop-and-go roads.
  - Combine scenic reward with route penalties.

6. Add diversity and anti-repetition.
  - Track visited edges.
  - Reduce reward for backtracking and repeated segments.

7. Generate better highlights.
  - Extract the top scenic segments from the route.
  - Label highlights like waterfront stretch, forest drive, or open valley.

### Phase 3 - Data Completeness

8. Finish scenic inputs.
  - Water is done.
  - Finish POI summary and land use summary.
  - Add NLCD later as a nice-to-have.
  - Add traffic later as an important enhancement, but not a blocker.

9. Improve scoring calibration.
  - Log route outcomes.
  - Tune weights empirically over time.

### Phase 4 - Hybrid Routing

10. Add external routing as an optional refinement.
   - Use Mapbox or OSRM as a constraint or polish layer.
   - Generate the scenic candidate loop locally, then refine or snap it externally.

### Phase 5 - System Optimization

11. Optimize graph loading.
   - Avoid loading all road segments when a smaller spatial window will do.

12. Cache subgraphs by region.
   - Use H3 clusters or similar partitioning.

13. Tune hybrid candidate generation.
   - Generate more profile-specific waypoint sectors.
   - Prune same-corridor candidates earlier before OSRM calls when possible.

### Final Missing Piece

14. Add route personality.
   - Make scenic routes feel like distinct products: chill drive, curvy fun drive, nature escape, or waterfront loop.
   - Drive the personality from scoring weights instead of adding more data.
