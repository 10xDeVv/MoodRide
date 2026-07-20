# Wayward Architecture

Last reconciled: 2026-07-19

Wayward generates scenic driving loops from a start point, time budget, and vibe. The system is built around an async route-generation pipeline: the web app submits a job, backend services generate and score route options, and the user receives `most_scenic`, `balanced`, and `shorter` choices.

## Runtime Services

Production runs with Docker Compose on one VM:

| Component | Role |
| --- | --- |
| `frontend` | Next.js web app, WebSocket-first job tracking, progressive Mapbox route rendering, analytics dashboard |
| `route-api` | Public REST API, transactionally durable job/outbox acceptance, revisioned job status, exact route details, scenic regions, analytics, cache controls |
| `route-worker` | Lease-fenced route-job consumer; builds the candidate pool, persists the primary and remaining route options, and publishes lifecycle events |
| `notification-service` | WebSocket lifecycle notifications for primary-ready and terminal states, with frontend polling as fallback |
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
2. `route-api` validates the request and commits a `route_jobs` row in `QUEUED` together with its durable dispatch outbox record. A successful database commit is the HTTP `202 Accepted` boundary; it does not require an immediate Kafka acknowledgement. An asynchronous dispatcher publishes pending records, marks dispatch acknowledged only after the broker acknowledges them, and leaves failed attempts durable for retry.
3. `route-worker` consumes the message, locks and claims the job, changes it to `PROCESSING`, and receives a time-bounded lease token. A heartbeat renews that lease. Route persistence and lifecycle transitions require the same unexpired token, so a stale or duplicate worker cannot commit over the current owner.
4. The worker loads precomputed H3 scenic tiles and nearby road anchors, builds candidate waypoint sets, calls local OSRM `/trip`, samples returned geometries against `scenic_score_tiles`, and scores and filters the candidate pool.
5. The first selected profile is persisted with its route geometry and waypoints. The same fenced transaction publishes it on the job as `PRIMARY_READY`, advances the lifecycle and option revisions, and records the exact primary route id.
6. `notification-service` forwards the revisioned `PRIMARY_READY` event on the job channel. The WebSocket-first frontend fetches the slim exact primary from `GET /api/routes/route/{routeId}/primary` and paints it without waiting for the rich route response.
7. The worker continues by persisting the remaining selected profiles. These commits advance `optionRevision` and `optionCount`; they do not replace the published primary id.
8. Once the option set is committed, the fenced worker marks the job `COMPLETED` with `optionsComplete=true`. Pre-primary errors can instead finish as `FAILED` or `TIMEOUT`; retry and timeout recovery also respect the active lease.
9. Revisioned terminal events are published. On `COMPLETED`, the frontend fetches `GET /api/routes/route/{routeId}` for rich details and merges only a response that is current for the active job, selection, lifecycle revision, and option revision. If that richer fetch fails, an already painted primary remains usable and can be retried.

The lifecycle is `QUEUED -> PROCESSING -> PRIMARY_READY -> COMPLETED`, with `FAILED` and `TIMEOUT` as terminal error states. `stateRevision` orders lifecycle changes; `optionRevision` orders changes to the visible option set. The frontend rejects stale or regressive WebSocket, polling, primary, and detail responses.

WebSocket delivery is preferred. If no usable lifecycle update arrives, the frontend waits 2.5 seconds before starting fallback polling; a WebSocket transport failure starts it immediately. Polling allows only one status request in flight and schedules the next request 1.5 seconds after the prior request settles.

`PRIMARY_READY` is progressive delivery, not staged planner computation. The current planner still computes and scores the candidate pool before committing the primary. This lifecycle removes the wait for remaining option persistence and rich-detail delivery; it does not establish a new production end-to-end p50 or p95 latency.

## Public API Surface

Route APIs:

- `POST /api/routes` — atomically commit a `QUEUED` job plus its dispatch outbox record and return `202 Accepted`; Kafka acknowledgement and retry are asynchronous
- `GET /api/routes/{jobId}` — resolve a job status or persisted route through the compatibility-shaped lookup
- `GET /api/routes/jobs/{jobId}` — read the revisioned job lifecycle, primary id, option count, and completion state
- `GET /api/routes/route/{routeId}/primary` — read the slim exact published primary while the job is `PRIMARY_READY` or `COMPLETED`
- `GET /api/routes/route/{routeId}` — read rich details for an exact persisted route
- `POST /api/routes/{routeId}/rating`

Lifecycle consumers must treat `stateRevision` and `optionRevision` as monotonic cursors rather than relying on event arrival order. `routeId` in a primary-ready job identifies the published primary; `optionsComplete` distinguishes an early readable option set from the terminal complete set.

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

## Database Table Guide

Core runtime tables:

- `route_jobs`: one row per route request. Tracks status, start point, time budget, vibe, and failure reason.
- `routes`: generated route options. The stored `scenic_score` is normalized `0.0-1.0`; API responses expose it as `0-100` Scenic Match.
- `route_waypoints`: persisted points/instructions for each generated route option.
- `road_segments`: imported road geometry and road metadata used for road-aware anchors, graph lookup, road stress, and scenic-road context.
- `scenic_score_tiles`: H3 scenic feature vectors used by the route worker at runtime.

Calibration and analytics tables:

- `route_duration_calibrations`: learned radius/waypoint timing hints. A small row count is normal early on because buckets only appear after matching route jobs are generated.
- `route_weight_calibrations`: older per-vibe multiplier table. It is not the main v2 routing contract; sparse rows are expected unless explicit feedback calibration is enabled.
- `analytics_events` and `route_analytics_daily`: privacy-safe product analytics and daily rollups.

Derived/import helper tables:

- Tables such as `water_tile_summary` or `land_use_summary` are data-pipeline staging/summary artifacts. They help compute scenic tile features but are not directly queried for every route request.
- `spatial_ref_sys` is a normal PostGIS system table. It stores coordinate reference system definitions and should be left alone.

Some enrichment columns may be all zero if the matching data release has not populated them in that database, or if the source data for that signal was unavailable/sparse in the imported region. The schema can exist before the data is meaningful.

## Caching

Route payload caches are intentionally service-specific. The route API caches its versioned rich-detail DTO contract under `routeDetailsV2::route:detail:v2:<routeId>` for 24 hours. The worker caches its internal persisted-route result under `routeResults::route:result:<routeId>` for 24 hours. They represent different shapes and must not be forced into one shared route-cache namespace.

Data-plane cache contracts remain shared between the route API and worker: scenic tiles use `scenicTiles::scenic:tile:<h3Index>` for 8 days, road-segment metadata uses `roadSegments::segment:meta:<h3Index>` for 7 days, and regional popularity uses `regionalPopularity::popular:routes:<regionKey>` for 24 hours. `scripts/monitoring/verify-cache-policy-parity.ps1` validates both the three shared contracts and the two intentionally distinct route contracts.

`ScenicTileLookupService` implements a batch contract for each normalized H3-index request:

1. Check the worker-local access-ordered LRU, bounded at 25,000 scenic tiles.
2. Send one Redis `MGET` for all local misses, not one network call per tile.
3. Fetch all Redis misses with one bulk Postgres `WHERE h3_index IN (...)` query.
4. Put database hits into the local LRU and fill Redis with pipelined TTL-bearing writes.

Redis read or fill failures are fail-open for this lookup path: reads continue through the bulk SQL source of truth, while missing database rows remain absent from the result. Scenic-tile invalidation removes the requested keys from both the local and Redis layers; full local clears are also supported.

The measured cache evidence is deliberately narrower than route latency. In a real authenticated Redis 7.0 integration with 1,500 deterministic tiles, serial Redis `GET` took 3,213 ms, one `MGET` took 90 ms, cold SQL plus pipelined fill took 685 ms, a Redis-warm service lookup took 140 ms, and a local-warm lookup took 2 ms. These are scenic-cache microbenchmarks from `artifacts/route-quality-eval/first-latency-release-20260719/evidence.json`, not production traffic, end-to-end route generation, or evidence for a new route p50/p95.

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

The single-VM topology is the current architecture, not a claim of unlimited capacity. It remains appropriate because route generation is asynchronous, expensive scenic signals are precomputed, scenic lookups batch remote work, OSRM runs locally instead of depending on a paid external routing API, and deploys and rollbacks remain simple.

Because production has one application stack, releases cannot isolate a candidate with percentage traffic. A guarded release is a full-maintenance cutover: close Caddy ingress, drain the old stack, verify an off-volume database dump, start `route-api` and Flyway before consumers, smoke-test the worker path behind closed ingress, and reopen only after internal verification. Rollback must coordinate the database state and compatible image set rather than changing one service independently.

Progressive delivery improves the boundary between a committed primary and later option/detail delivery. It does not shorten the candidate-building phase: queue delay, the full candidate-pool computation, OSRM calls, scenic scoring, and first-profile persistence are still on the path to `PRIMARY_READY`.

Likely first bottlenecks:

- route-worker candidate generation and OSRM CPU/memory under concurrent jobs
- the single Kafka broker and asynchronous outbox-dispatch acknowledgement/retry backlog; the HTTP `202` submission path ends at the committed database boundary
- cold Postgres scenic-tile misses and road-anchor reads; batching removes per-tile round trips but not source-of-truth work
- the shared Redis instance under many workers; each worker-local LRU is fast but process-local and starts cold
- worker throughput, lease churn, and retry pressure when processing exceeds capacity
- large OSRM dataset disk and memory footprint

Scaling path:

1. Measure queue age, claim/lease loss, time to `PRIMARY_READY`, time to terminal state, worker lag, failure reasons, and scenic-cache source before changing capacity.
2. Increase route-worker concurrency or instance count only while monitoring contention at OSRM, Redis, and Postgres.
3. Move OSRM to a larger VM or dedicated routing host when routing CPU or memory is the limiting resource.
4. Split OSRM datasets by region if memory or cold-start time becomes painful.
5. Scale Redis or add Postgres read capacity/materialized or scoped tile lookup tables only when cache-source and database measurements justify it.
6. Move Kafka to a managed or multi-broker deployment when dispatch throughput or broker durability requirements exceed the single broker.
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
