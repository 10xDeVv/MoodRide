# Service Ownership

## Runtime Services

These services are part of the active Wayward runtime:

| Service | Status | Responsibility |
|---|---|---|
| `route-api` | Active | Public REST API, route job creation, route details, cache reads/writes |
| `route-worker` | Active | Async route generation, OSRM calls, route scoring, persisted route options |
| `notification-service` | Active | WebSocket route-completion and failure notifications |

## Internal / Experimental Services

| Service | Status | Responsibility |
|---|---|---|
| `scenic-scoring-service` | Internal | Targeted scenic tile recompute experiments. Keep until it is either aligned with the SQL release pipeline or formally retired. |

## Data Pipeline Ownership

The current production data path is:

1. Source data lands in PostGIS through scripts.
2. Versioned SQL recompute scripts update `scenic_score_tiles`.
3. Release scripts export scenic tiles.
4. GitHub Actions deploy the release artifact to production.
5. Runtime services read precomputed PostGIS/Redis data.

This keeps expensive enrichment offline and keeps route generation fast, predictable, and independent from third-party API availability.

## Future Decision Points

If Wayward needs more automated data refreshes later, choose one clear owner:

1. Keep versioned batch releases and schedule periodic recomputes.
2. Promote `scenic-scoring-service` only after its scoring logic matches the latest SQL release pipeline.

