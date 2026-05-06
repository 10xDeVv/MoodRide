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
  "preferenceVector": { "avoidTolls": false }
}
```

`RouteRequest` accepts either `vibes[]` or a single `vibe`, but the API only stores the first validated vibe in the job.

## 2. Service Flow

1. The client posts to `POST /api/routes`.
2. [RouteService](../services/route-api/src/main/java/com/moodride/routeapi/service/RouteService.java) validates vibes, creates a `route_jobs` row, and marks it `QUEUED`.
3. The route API publishes the job id to Kafka on `RouteJobEvent.TOPIC`.
4. [RouteJobConsumer](../services/route-worker/src/main/java/com/moodride/routeworker/consumer/RouteJobConsumer.java) consumes the job, loads the `RouteJob`, marks it `PROCESSING`, and invokes [RouteGenerationService](../services/route-worker/src/main/java/com/moodride/routeworker/service/RouteGenerationService.java).
5. `RouteGenerationService` calls [RoutePlanner](../services/route-worker/src/main/java/com/moodride/routeworker/algorithm/RoutePlanner.java), which delegates to [BeamSearchSolver](../services/route-worker/src/main/java/com/moodride/routeworker/algorithm/BeamSearchSolver.java).
6. The worker persists the completed `routes` and `route_waypoints` rows.
7. The worker publishes a completion event on `RouteCompletionEvent.TOPIC`.
8. The frontend polls `GET /api/routes/{jobId}` and then fetches `GET /api/routes/route/{routeId}` once the job completes.

## 3. Route Generation Logic

The current implementation does not call Mapbox, OSRM, or another external routing service.

The route worker builds a local road graph from `road_segments` and performs a beam-search-style expansion:

- start at the road node nearest to the requested `lat`/`lng`
- expand outgoing road edges
- keep candidates whose estimated time stays within the budget
- return the highest scenic-score candidate in the current frontier

The key logic is in [BeamSearchSolver](../services/route-worker/src/main/java/com/moodride/routeworker/algorithm/BeamSearchSolver.java):

```java
int newTime = candidate.getEstimatedMinutes() + (int)(edge.getLengthMeters() / 83.33);
double newDistance = candidate.getTotalDistanceKm() + (edge.getLengthMeters() / 1000.0);
double newScore = candidate.getTotalScenicScore() + (edge.getScenicScore() / 100.0);
```

There is no explicit loop-closing step back to the starting point in the current code.

## 4. Waypoint / Candidate Selection

Intermediate points are not chosen from scenic tiles directly. Scenic tiles are used to annotate road segments with scenic scores when the graph is built.

[GraphService](../services/route-worker/src/main/java/com/moodride/routeworker/service/GraphService.java) does this in two steps:

1. load all `road_segments`
2. load matching `scenic_score_tiles` by H3 index

The H3 score is then attached to each road edge in the graph. Waypoints are simply the node sequence produced by the beam search.

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

The route worker does not compute a complex route-level scenic objective. It sums scenic edge reward as it expands the path:

```java
newScore = candidate.getTotalScenicScore() + (edge.getScenicScore() / 100.0);
```

The final `Route.scenicScore` is stored as a small decimal value and is later rendered as a percentage in the API response.

## 6. Database Usage

Route generation currently touches these tables:

- `route_jobs`
- `road_segments`
- `scenic_score_tiles`
- `routes`
- `route_waypoints`

Typical access patterns:

```sql
SELECT * FROM road_segments;

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
- created and expiration timestamps

## 8. Performance Notes

The main optimizations in the current implementation are:

- asynchronous job handling through Kafka
- cached graph construction in `GraphService`
- batch scenic tile lookup by H3 index
- route detail caching in `route-api`
- cache invalidation when scenic tiles are refreshed or changed

## 9. Current Limitations / Gaps

The current implementation is intentionally simpler than the design spec.

- It does not generate a true scenic loop back to the start.
- It does not call Mapbox, OSRM, or any other external routing provider.
- `preferenceVector` is accepted by the API but not used by the worker.
- Only the first vibe is persisted in the job.
- The estimated travel time uses a fixed speed heuristic.
- The solver does not use the edge weight defined on `RoadSegmentEdge`.
- Segment-level route scores in the response are synthetic presentation values.
- Route scoring is additive, not normalized for route efficiency or scenic density.

## Bottom Line

MoodRide currently generates a scenic route by building a local road graph, attaching precomputed H3 scenic tile scores to edges, and using a time-bounded beam search to maximize scenic reward. The result is asynchronous, cache-aware, and data-driven, but it is not yet a full loop optimizer or preference-aware routing engine.

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

13. Tune beam search.
   - Use dynamic beam width.
   - Prune aggressively when the candidate set grows too large.

### Final Missing Piece

14. Add route personality.
   - Make scenic routes feel like distinct products: chill drive, curvy fun drive, nature escape, or waterfront loop.
   - Drive the personality from scoring weights instead of adding more data.