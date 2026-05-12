# MoodRide Engineering Specification (As-Built)

Last reconciled: 2026-05-11

## 1) Document Intent
This file is the current, code-aligned engineering specification for MoodRide. It replaces the previous oversized historical spec and focuses on the system that actually runs today.

Use this document as the technical source of truth for:
- runtime architecture
- contracts between services
- API and event interfaces
- production deployment model
- operational constraints and risks

## 2) Product Scope (Current)
MoodRide generates scenic driving loops from a start point and time budget, then returns route options with real-time status updates.

In-scope now:
- async route job submission and tracking
- 3 route option profiles (`most_scenic`, `balanced`, `shorter`)
- OSRM-backed loop generation via route-worker
- websocket completion/failure notifications
- route rating capture (1-5)
- scenic region discovery endpoint
- cache warmup + cache invalidation hooks
- Docker-based deployment to single VM

Out-of-scope now:
- multi-region deployment
- active-active HA
- user auth/identity management
- full Kubernetes production rollout
- continuous live data recomputation running on prod VM

## 3) Runtime Architecture

### 3.1 Production runtime (current)
Production compose (`docker-compose.prod.yml`) runs:
- `postgres` (PostGIS)
- `zookeeper`
- `kafka`
- `redis`
- `osrm`
- `route-api`
- `route-worker`
- `notification-service`
- `frontend` (Next.js)
- `caddy` (TLS termination + reverse proxy)

Domain in use: `app.moodrides.com`.

### 3.2 Service responsibilities
- `route-api`: public HTTP API; route job creation/status/detail/rating; scenic regions; cache admin endpoints.
- `route-worker`: consumes route jobs, generates candidates, persists routes/waypoints, publishes completion/failure events.
- `notification-service`: consumes completion events and pushes websocket notifications (`/topic/job/{jobId}`).
- `postgres`: system of record for jobs/routes/tiles/segments.
- `redis`: app cache backend.
- `kafka`: async job queue and event transport.
- `osrm`: round-trip routing engine from preprocessed dataset.

### 3.3 Optional/offline services present in repo
The codebase includes services not currently wired into production compose:
- `ingestion-service`
- `scenic-scoring-service`
- `cdc-service`

These are intentionally treated as batch/offline tools for now (data refresh/recompute and CDC support), and are not part of the always-on production runtime stack.

## 4) End-to-End Flow
1. Frontend submits route request to `POST /api/routes`.
2. `route-api` validates request, persists `route_jobs` row, publishes `route-jobs` Kafka message.
3. `route-worker` consumes job, marks status `PROCESSING`, generates route candidates using `hybrid_osrm_v1` path.
4. Worker persists route records + waypoints, marks job `COMPLETED`, emits `route-completions` event.
5. `notification-service` consumes event and pushes websocket message on `/topic/job/{jobId}`.
6. Frontend fetches route detail from `GET /api/routes/route/{routeId}`.

Failure path:
- job retries are tracked on `route_jobs.retry_count`
- failures can be emitted to `route.jobs.dlq`
- websocket failure notification includes polling fallback URL

## 5) Routing Algorithm (Current Implementation)
Primary algorithm path in worker is hybrid OSRM:
- score nearby H3 tiles using weighted preference vector
- build waypoint ring variants
- call OSRM trip API for loop candidates
- compute scenic density along returned path
- deduplicate and select top options
- persist options as route profiles

Current algorithm version persisted on jobs: `hybrid_osrm_v1`.

Fallback behavior implemented:
- synthetic radial waypoint variants when initial candidate set is weak
- beam-search implementation (`beam_v1`) still exists in code but is not the primary route option generator path

## 6) API Surface (Current)

### 6.1 Public route APIs
- `POST /api/routes` (also aliased as `/routes` and `/routes/generate`)
- `GET /api/routes/{jobId}`
- `GET /api/routes/jobs/{jobId}`
- `GET /api/routes/route/{routeId}`
- `POST /api/routes/{routeId}/rating`

API policy:
- canonical public surface is `/api/*`
- `/routes/*` aliases are currently retained for compatibility and may be removed in a later cleanup pass

### 6.2 Scenic region API
- `GET /api/scenic-regions?lat=&lng=&radiusKm=&limit=&vibe=`

### 6.3 Internal cache APIs
- `POST /api/internal/cache/warm`
- `GET /api/internal/cache/policy`
- `POST /api/internal/cache/flush`

### 6.4 Request/response contract notes
- Route request accepts either `vibes[]` or single `vibe`, plus optional `preferenceVector`.
- Job status returns `routeOptions` list and may return an inferred primary `routeId`.
- Route detail returns geometry, segment scores/colors, highlights, algorithm metadata, route options, and user rating fields.

## 7) Event Interfaces
Current key Kafka topics and payload contracts:
- `route-jobs`: job trigger (job UUID payload)
- `route-completions`: completion/failure event payload (`RouteCompletionEvent`)
- `route.jobs.dlq`: failed job payloads for triage
- `route-rated`, `drive-completed`: user feedback events from route-api
- `scenic-tiles-refreshed`, `cdc-tile-updates` used by cache invalidation consumers

## 8) Data Model (Current Highlights)
The schema is managed by Flyway migrations under `services/route-api/src/main/resources/db/migration`.

Core persisted entities:
- `route_jobs`
  - lifecycle timestamps (`submittedAt`, `startedAt`, `completedAt`, `failedAt`)
  - status enum (`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`, `TIMEOUT`)
  - retry fields (`retryCount`, `maxRetries`)
  - algorithm metadata (`algorithmVersion`, `beamCandidates`)
  - preference vector JSON text
- `routes`
  - route profile, geometry, distance/duration, scenic score
  - rating fields (user rating + rated timestamp)
- `route_waypoints`
  - ordered waypoint sequence and per-segment distances
- `road_segments`
- `scenic_score_tiles`
  - composite + component score fields (including traffic and component breakdown)

## 9) Caching + Invalidation
- Route details cached (`routeResults` cache key by route ID).
- Scenic tile/popularity metadata cached.
- Warmup scheduler and internal warm endpoint exist in route-api.
- Cache invalidation consumers in route-api and route-worker react to:
  - scenic refresh events
  - CDC tile update events

## 10) Frontend Contract
Frontend (`Next.js 14`) uses:
- `NEXT_PUBLIC_API_BASE_URL`
- `NEXT_PUBLIC_WS_BASE_URL`

Behavioral contract:
- submit route
- poll status with websocket assist
- load chosen route detail
- switch between generated options
- submit rating

## 11) Deployment + Operations

### 11.1 Current production deployment model
- single GCP VM
- Docker Compose runtime
- Caddy handles TLS for `app.moodrides.com`

### 11.2 CI/CD model
GitHub Actions workflows now present:
- app deploy workflow (image build/push + remote deploy script)
- data release deploy workflow (versioned OSRM artifact rollout)
- scenic release deploy workflow (versioned scenic tile rollout into Postgres)

Compose now parameterizes image source/tag via:
- `GHCR_NAMESPACE`
- `IMAGE_TAG`

### 11.3 OSRM data lifecycle
- preprocess heavy datasets off-VM (local machine)
- publish versioned data artifact
- deploy artifact to VM and switch `OSRM_DATASET_BASENAME`

Current production state:
- `OSRM_DATASET_BASENAME=canada-latest` is deployed for nationwide routing.

### 11.4 Scenic data lifecycle
- run nationwide recompute locally using `scripts/setup/data-quality-upgrade-batched.sql` (single-pass target: `2.6-raster-data-quality-upgrade-national-batched`)
- publish versioned scenic release artifact (`publish_scenic_release.ps1`)
- deploy scenic release via `.github/workflows/deploy-scenic-release.yml`
- restart `route-api` + `route-worker` to refresh runtime caches

Current production state:
- `scenic_score_tiles` is fully on `2.6-raster-data-quality-upgrade-national-batched` (211,510 tiles)

### 11.5 Release QA baseline
- run `scripts/deploy/run_release_qa_baseline.ps1` after release
- baseline currently validates 3 regions (Ontario, BC, Maritimes) × 3 vibe profiles and persists JSON/Markdown artifacts under `artifacts/release-qa`

## 12) Non-Functional Targets (Current Practical)
These are practical targets for current architecture, not theoretical long-term goals:
- route submission API: low-latency request acceptance
- route generation: async, seconds-scale for happy path
- graceful degradation under transient Kafka/OSRM issues through retries + DLQ
- no hard dependency on websocket delivery because poll fallback exists

## 13) Known Risks / Constraints
- Production is single-node; broker/db/app all co-located.
- Kafka currently uses ZooKeeper mode and single-broker topology.
- Large national OSRM datasets can stress memory/disk if not managed as versioned releases.
- Some internal docs still describe optional components as if always-on prod components.
- Kubernetes manifests are currently future/archival, not an active near-term deployment target.
- Data refresh currently uses release-driven manual updates; transition to scheduled recompute jobs is planned in the near term.

## 14) Open Question
1. What is the expected production SLO target (for example, p95 async route completion window) once nationwide data scope is live?

## 15) Reference Documents
- `docs/ApiAliasDeprecationPlan.md`
- `docs/DeploymentPreparation.md`
- `docs/Deployment.md`
- `docs/DeploymentPipeline.md`
- `docs/DataQualityUpgrade.md`
- `docs/AdditionalDataQualityUpgrade.md`
- `docs/RegionalDemWorkflow.md`
- `docs/HybridRoutingProgress.md`
- `docs/RouteExportAndUIPolishProgress.md`

