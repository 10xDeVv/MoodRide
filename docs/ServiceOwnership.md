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

## Legacy Archive

These services are no longer part of the active Maven build or startup flow:

| Service | Previous responsibility | Current decision |
|---|---|---|
| `ingestion-service` | OSM ingest, legacy scenic batch endpoints, elevation/traffic seed endpoints | Archived locally under `legacy/`; current ingestion is script-driven. |
| `cdc-service` | Debezium-based Redis invalidation and recompute queueing | Archived locally under `legacy/`; current scenic releases are versioned batch deploys, not CDC-driven. |

The `legacy/` directory is gitignored. It is a local reference archive, not a production dependency.

## Data Pipeline Ownership

The current production data path is:

1. Source data lands in PostGIS through scripts.
2. Versioned SQL recompute scripts update `scenic_score_tiles`.
3. Release scripts export scenic tiles.
4. GitHub Actions deploy the release artifact to production.
5. Runtime services read precomputed PostGIS/Redis data.

This keeps expensive enrichment offline and keeps route generation fast, predictable, and independent from third-party API availability.

## Future Decision Point

If Wayward needs incremental live data updates later, revisit one of these options:

1. Rebuild CDC around Debezium and Redis invalidation with clear production ownership.
2. Keep batch releases only and schedule periodic recomputes.
3. Use targeted recompute jobs in `scenic-scoring-service`, but only after its scoring logic matches the latest release SQL.

