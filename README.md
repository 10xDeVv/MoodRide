# Wayward

Wayward generates scenic driving loops from a start point, time budget, and vibe. It is not trying to get you somewhere as fast as possible. It is trying to find the better drive nearby.

## Current Stack

- Frontend: Next.js, React, Mapbox GL
- Backend: Java, Spring Boot, Maven
- Async routing: Kafka
- Runtime storage: PostgreSQL/PostGIS
- Cache: Redis
- Routing engine: local OSRM container using `/trip`
- Spatial scoring: H3 scenic tiles stored in PostGIS
- Production deploy: Docker Compose on a single VM, Caddy for TLS

## Active Services

| Service | Role |
| --- | --- |
| `services/route-api` | Public REST API for revisioned route jobs, slim primary results, rich route details, feedback, scenic regions, and cache controls |
| `services/route-worker` | Consumes route jobs, runs `hybrid_osrm_v2`, calls OSRM, scores routes, and persists route options |
| `services/notification-service` | Relays revisioned route-status events, including `PRIMARY_READY`, over WebSocket |
| `frontend/moodride-web` | WebSocket-first Wayward web app with a bounded polling fallback |
| `services/scenic-scoring-service` | Internal/offline scenic recompute experiments; not part of production compose |

## How Routing Works

1. The frontend submits a route request to `POST /api/routes`; `route-api` commits the revisioned `QUEUED` job and its durable dispatch outbox record in one database transaction. That commit is the HTTP `202 Accepted` boundary. Kafka dispatch, broker acknowledgement, and retry happen asynchronously.
2. `route-worker` claims the job with lease fencing, advances it to `PROCESSING`, and batch-loads nearby H3 scenic tiles through the local LRU, one Redis `MGET`, and one bulk SQL miss query with a pipelined Redis fill.
3. The worker builds the full waypoint-candidate pool for the selected vibe and time budget, calls the local OSRM `/trip` endpoint, and scores the returned corridors for landscape quality, vibe fit, drive quality, route shape, scenic moments, urban pressure, and start/end quality.
4. The selected options are persisted as `most_scenic`, `balanced`, and `shorter`; once the exact primary is available, the job advances to `PRIMARY_READY`.
5. A revisioned WebSocket event normally prompts the frontend to fetch the slim exact primary and paint it before loading rich details. If the event does not arrive, the frontend waits 2.5 seconds, then uses a single-in-flight polling fallback at 1.5-second intervals; stale revisions are rejected.
6. The revisioned lifecycle finishes at `COMPLETED`, `FAILED`, or `TIMEOUT`.

The current planner still computes the candidate pool before `PRIMARY_READY`: progressive delivery removes response and enrichment delay, but is not staged route computation. Matched technical-completion and latency controls support retaining `hybrid_osrm_v2` (Legacy) as the better production foundation than PURE Design B; they are not route-quality pass results, and the current engine still needs quality hardening.

## Data Pipeline

Runtime route generation does not calculate water, greenery, elevation, darkness, buildings, parks, or urban pressure from raw datasets. Those signals are computed offline into `scenic_score_tiles`.

Current scenic release train:

- `3.7-bridge-coastal-calibration`

Main data scripts:

- `scripts/setup/run-data-enrichment-v31.ps1`
- `scripts/setup/data-quality-enrichment-v31.sql`
- `scripts/setup/import-osm.ps1`
- `scripts/setup/import-natural-earth-water.ps1`
- `scripts/setup/import-nlcd.ps1`
- `scripts/setup/import-protected-areas.ps1`
- `scripts/setup/import-overture-v30.ps1`
- `scripts/setup/import-light-pollution-samples-v31.ps1`
- `scripts/deploy/publish_scenic_release.ps1`
- `scripts/deploy/deploy_scenic_release.sh`
- `scripts/deploy/build_osrm_dataset.ps1`
- `scripts/deploy/publish_data_release.ps1`
- `scripts/deploy/deploy_data_release.sh`

Older scoring SQL files are kept only when they are useful for reproducing previous releases.

## Local Development

Prerequisites:

- Java 21+ or Java 25
- Maven 3.9+
- Docker Desktop
- Node.js 18+

Start the local Docker services:

```powershell
docker compose up -d postgres redis kafka zookeeper osrm
```

Build backend modules:

```powershell
mvn clean install
```

Run app services in separate terminals:

```powershell
cd services/route-api
mvn spring-boot:run
```

```powershell
cd services/route-worker
mvn spring-boot:run
```

```powershell
cd services/notification-service
mvn spring-boot:run
```

Run the frontend:

```powershell
cd frontend/moodride-web
npm install
npm run dev
```

Default local URLs:

- Frontend: `http://localhost:3000`
- Route API: `http://localhost:8080`
- OSRM: `http://localhost:5002`

## Production

Production uses:

- `docker-compose.prod.yml`
- `Caddyfile`
- `.github/workflows/deploy-prod.yml`
- `scripts/deploy/deploy_prod.sh`
- `scripts/deploy/rollback_prod.sh`

Pushes to `main` publish immutable `sha-<40-hex-commit>` images but do not deploy them. Production changes require a later explicit `Publish and Deploy Production Stack` workflow dispatch using `deploy-existing` for an already published immutable tag, after that exact image set passes the route-quality gate. The workflow cannot publish and deploy in one run.

Production is single-instance, so an app release is a guarded full-stack maintenance cutover, not a traffic-isolated canary or percentage rollout. The cutover closes Caddy ingress, drains active route jobs, creates and verifies an off-volume database dump, starts `route-api` first so Flyway owns the schema transition, starts and smoke-tests the worker stack behind closed ingress, and only then reopens Caddy. Rollback coordinates the compatible image set and database state under the same closed-ingress boundary. Data releases remain separate GitHub release assets deployed by the OSRM/scenic release workflows.

## Official Docs

- [Architecture](docs/Architecture.md): runtime services, request flow, data model, caching, analytics, and scaling path.
- [Route Engine](docs/RouteEngine.md): `hybrid_osrm_v2`, scenic tiles, vibe contracts, route explanations, and quality evaluation.
- [Operations](docs/Operations.md): local development, production deploys, DNS, data releases, QA, rollback, and repo hygiene.

Older implementation notes and route-quality evidence are retained under `docs/archive/` for history, but the three docs above are the official documentation set.

## Repo Layout

```text
.
├── .github/workflows
├── docs
├── frontend/moodride-web
├── scripts
├── services
├── shared
├── docker-compose.yml
├── docker-compose.prod.yml
└── Caddyfile
```

Generated artifacts, local datasets, IDE files, build outputs, OSRM archives, and verification scratch files are ignored by Git.
