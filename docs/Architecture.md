# Wayward Architecture

Last reconciled: 2026-07-01

Wayward generates scenic driving loops from a start point, time budget, and vibe. The system is built around an async route-generation pipeline: the web app submits a job, backend services generate and score route options, and the user receives `most_scenic`, `balanced`, and `shorter` choices.

## Runtime Services

Production runs with Docker Compose on one VM:

| Component | Role |
| --- | --- |
| `frontend` | Next.js web app, Mapbox route visualization, analytics dashboard |
| `route-api` | Public REST API, route jobs, route details, scenic regions, analytics, cache controls |
| `route-worker` | Consumes route jobs, builds route candidates, calls OSRM, scores and persists route options |
| `notification-service` | WebSocket route completion/failure notifications, with frontend polling as fallback |
| `postgres` | PostgreSQL/PostGIS source of truth for jobs, routes, scenic tiles, road segments, analytics |
| `redis` | Cache for route results, scenic tiles, road metadata, and regional popularity |
| `kafka` + `zookeeper` | Async job and event transport |
| `osrm` | Local OSRM routing engine using prepared OSM datasets |
| `caddy` | TLS and reverse proxy for `usewayward.app` and `www.usewayward.app` |
| `dozzle` | Optional admin-only Docker log viewer, bound to VM localhost |
| `cloudbeaver` | Optional admin-only Postgres table browser, bound to VM localhost |

The public production domain is:

- `https://usewayward.app`
- `https://www.usewayward.app`

Internal package names and some infrastructure identifiers still use `moodride`. Those are implementation identifiers, not user-facing product names.

## Request Flow

1. The frontend submits `POST /api/routes`.
2. `route-api` validates the request, stores a `route_jobs` row, and publishes a Kafka `route-jobs` message.
3. `route-worker` consumes the job and marks it `PROCESSING`.
4. The worker loads cached/precomputed H3 scenic tiles and nearby road anchors.
5. The worker builds candidate waypoint sets and calls local OSRM `/trip`.
6. Returned route geometries are sampled against `scenic_score_tiles`.
7. The worker scores, filters, and persists route options.
8. The job is marked `COMPLETED`, `FAILED`, or `TIMEOUT`.
9. `notification-service` publishes a completion/failure event to `/topic/job/{jobId}`.
10. The frontend fetches route detail with `GET /api/routes/route/{routeId}`.

Polling remains the fallback if WebSocket delivery is unavailable.

## Public API Surface

Route APIs:

- `POST /api/routes`
- `GET /api/routes/{jobId}`
- `GET /api/routes/jobs/{jobId}`
- `GET /api/routes/route/{routeId}`
- `POST /api/routes/{routeId}/rating`

Scenic and analytics APIs:

- `GET /api/scenic-regions?lat=&lng=&radiusKm=&limit=&vibe=`
- `POST /api/analytics/events`
- `GET /api/analytics/summary?days=30`

Internal cache APIs:

- `POST /api/internal/cache/warm`
- `GET /api/internal/cache/policy`
- `POST /api/internal/cache/flush`

The canonical public surface is `/api/*`. Older `/routes/*` aliases are compatibility routes only.

## Data Model

Flyway migrations live under:

```text
services/route-api/src/main/resources/db/migration
```

Core tables:

- `route_jobs`
- `routes`
- `route_waypoints`
- `road_segments`
- `scenic_score_tiles`
- `route_duration_calibrations`
- `analytics_events`
- `route_analytics_daily`

`scenic_score_tiles` is the runtime scenic feature store. Route generation reads this table; it does not compute raw land cover, elevation, water, buildings, parks, or light-pollution signals during a user request.

## Caching

The route API and route worker share cache key/policy conventions.

Runtime cache categories include:

- route result cache
- scenic tile cache
- road segment metadata cache
- regional popularity cache

The route worker uses `ScenicTileLookupService` for scenic tile reads. It batch-fetches H3 indexes, checks local memory and Redis, falls back to Postgres for misses, and writes useful results back through the cache path.

Cache invalidation events clear both local and Redis layers where relevant.

## Analytics

Wayward tracks product analytics without accounts or personal identity.

- Browser creates a random anonymous client id in local storage.
- API stores a server-side HMAC hash of that id, not the raw browser id.
- Start location is represented as a coarse `0.5` degree region bucket, not exact coordinates.
- Events include route generation, route completion/failure, selected vibes, selected profile, start-drive clicks, navigation opens, GPX export, and planning actions.

The analytics dashboard compares current aggregate metrics with the last browser-local snapshot for the selected range. This powers the up/down change signals without adding user accounts.

## Admin Visibility

Wayward has an optional admin tools profile for production visibility:

- Dozzle provides a browser UI for Docker container logs.
- CloudBeaver provides a browser UI for browsing Postgres databases and tables.

These tools are not public runtime services. They bind only to `127.0.0.1` on the production VM and should be opened through an SSH tunnel. They are operational tools for debugging, database inspection, and launch support, not user-facing product surfaces.

## System Design And Scaling

Current production is intentionally simple: one VM, Docker Compose, one Postgres, one Redis, one Kafka broker, one OSRM container, and one instance of each app service.

This is good enough for launch because:

- route generation is async
- expensive scenic signals are precomputed
- OSRM runs locally instead of depending on a paid external routing API
- Redis reduces repeated tile and route lookups
- production deploys and rollbacks are automated

Likely first bottlenecks:

- OSRM CPU/memory under many concurrent route jobs
- route-worker throughput
- Postgres read pressure from scenic tile and road segment lookups
- Kafka single-broker durability/throughput
- large OSRM dataset disk/memory footprint

Scaling path:

1. Increase route-worker concurrency and instance count.
2. Add stronger queue visibility: job age, processing time, failure reasons, worker lag.
3. Move OSRM to a larger VM or dedicated routing host.
4. Split OSRM datasets by region if memory or cold-start time becomes painful.
5. Add read replicas or materialized/scoped tile lookup tables if Postgres read pressure grows.
6. Move Kafka to a managed or multi-broker deployment if route volume or reliability requirements increase.
7. Put Caddy behind a cloud load balancer only when multiple app hosts exist.

Do not introduce Kubernetes or multi-region deployment until the single-VM deployment is operationally limiting the product.

## What Is Not Active Runtime

- Kubernetes manifests
- live CDC ingestion service
- live incremental scenic recomputation
- user auth/accounts
- personalized route generation
- multi-region production deployment
- the old beam-search route engine
- public admin/database dashboards

Archived docs and design references may mention older ideas. The active architecture is the one described here.
