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
| `services/route-api` | Public REST API, route jobs, route details, route feedback endpoints, scenic regions, cache controls |
| `services/route-worker` | Consumes route jobs, runs `hybrid_osrm_v2`, calls OSRM, scores routes, persists route options |
| `services/notification-service` | Sends route completion/failure notifications over WebSocket |
| `frontend/moodride-web` | Wayward web app |
| `services/scenic-scoring-service` | Internal/offline scenic recompute experiments; not part of production compose |

## How Routing Works

1. The frontend submits a route request to `POST /api/routes`.
2. `route-api` creates a job and publishes it to Kafka.
3. `route-worker` loads nearby precomputed H3 scenic tiles from `scenic_score_tiles`.
4. The worker builds waypoint candidates based on the selected vibe and time budget.
5. The worker calls the local OSRM `/trip` endpoint to get legal drivable loop geometry.
6. Wayward samples the returned route corridor against H3 scenic tiles and scores landscape quality, vibe fit, drive quality, route shape, scenic moments, urban pressure, and start/end quality.
7. The best options are persisted as `most_scenic`, `balanced`, and `shorter`.
8. `notification-service` sends the frontend a completion event, and the frontend fetches route details.

The current route algorithm is `hybrid_osrm_v2`. The old beam-search implementation has been removed from the active codebase.

## Data Pipeline

Runtime route generation does not calculate water, greenery, elevation, darkness, buildings, parks, or urban pressure from raw datasets. Those signals are computed offline into `scenic_score_tiles`.

Current scenic release train:

- `3.1-darkness-urban-penalty-calibration`

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

App deploys are triggered from GitHub Actions. Data releases are published as GitHub release assets, then deployed by the OSRM/scenic release workflows.

## Useful Docs

- [Engineering Specification](docs/engineering-specification.md)
- [Hybrid OSRM v2](docs/HybridOsrmV2.md)
- [Data Pipeline](docs/DataPipeline.md)
- [Deployment Pipeline](docs/DeploymentPipeline.md)
- [Service Ownership](docs/ServiceOwnership.md)
- [Route Quality Eval](docs/RouteQualityEval.md)
- [Release QA Baseline](docs/ReleaseQABaseline.md)
- [Vibe Profile Calibration](docs/VibeProfileCalibration.md)

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
