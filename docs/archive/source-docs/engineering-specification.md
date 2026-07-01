# Wayward Engineering Specification

Last reconciled: 2026-06-27

This document describes the system that is active in the repository today. It intentionally excludes removed deployment scaffolding, archived services, and old progress-plan documents.

## Product Scope

Wayward generates scenic driving loops from a start point, time budget, and vibe.

In scope:

- async route job submission and tracking
- three route options: `most_scenic`, `balanced`, `shorter`
- OSRM-backed route generation through `hybrid_osrm_v2`
- precomputed H3 scenic tile scoring
- WebSocket completion/failure notifications with polling fallback
- route detail, route option, scenic region, and cache endpoints
- versioned OSRM and scenic tile releases

Out of scope for the current runtime:

- Kubernetes deployment
- live CDC pipeline
- live incremental data recomputation
- user auth and identity
- multi-region production deployment

## Runtime Architecture

Production compose (`docker-compose.prod.yml`) runs:

- `postgres` with PostGIS
- `zookeeper`
- `kafka`
- `redis`
- `osrm`
- `route-api`
- `route-worker`
- `notification-service`
- `frontend`
- `caddy`

The active production domain is `usewayward.app`.

## Service Responsibilities

| Service | Responsibility |
| --- | --- |
| `route-api` | HTTP API, route job persistence, route details, route feedback endpoints, scenic regions, cache controls |
| `route-worker` | Kafka job consumption, `hybrid_osrm_v2` candidate generation, OSRM Trip calls, route scoring, route persistence |
| `notification-service` | Completion/failure WebSocket notifications |
| `frontend/moodride-web` | Wayward planning UI and route visualization |
| `scenic-scoring-service` | Internal/offline scenic recompute experiments; not part of production compose |

## End-to-End Flow

1. Frontend submits `POST /api/routes`.
2. `route-api` validates the request, persists `route_jobs`, and publishes a `route-jobs` Kafka message.
3. `route-worker` consumes the job and marks it `PROCESSING`.
4. The worker loads H3 scenic tiles, builds route candidates, and calls local OSRM `/trip`.
5. Returned OSRM geometries are sampled against `scenic_score_tiles`.
6. The worker scores candidates and persists route options.
7. The job is marked `COMPLETED` or `FAILED`.
8. `notification-service` pushes the result to `/topic/job/{jobId}`.
9. Frontend fetches route detail from `GET /api/routes/route/{routeId}`.

## Routing Algorithm

Current algorithm version: `hybrid_osrm_v2`.

The worker keeps OSRM responsible for legal drivable geometry. Wayward decides which candidate waypoints to try and how to score the returned route corridor.

The v2 score considers:

- landscape quality
- vibe fit
- drive quality
- route shape and budget fit
- scenic moments and continuity
- urban pressure
- start/end quality
- strategy fit for vibe-specific geometry

See [Hybrid OSRM v2](HybridOsrmV2.md) for the detailed algorithm contract.

## API Surface

Route APIs:

- `POST /api/routes`
- `GET /api/routes/{jobId}`
- `GET /api/routes/jobs/{jobId}`
- `GET /api/routes/route/{routeId}`
- `POST /api/routes/{routeId}/rating`

Scenic region API:

- `GET /api/scenic-regions?lat=&lng=&radiusKm=&limit=&vibe=`

Internal cache APIs:

- `POST /api/internal/cache/warm`
- `GET /api/internal/cache/policy`
- `POST /api/internal/cache/flush`

The canonical public surface is `/api/*`. Older `/routes/*` aliases are compatibility routes only.

## Event Interfaces

Active Kafka topics:

- `route-jobs`
- `route-completions`
- `route.jobs.dlq`
- `route-rated`
- `drive-completed`
- `scenic-tiles-refreshed`
- `cdc-tile-updates`

`cdc-tile-updates` remains an event contract for cache invalidation compatibility. There is no active CDC service in the current runtime.

## Data Model

Flyway migrations live under:

- `services/route-api/src/main/resources/db/migration`

Core tables:

- `route_jobs`
- `routes`
- `route_waypoints`
- `road_segments`
- `scenic_score_tiles`
- `route_duration_calibrations`

`scenic_score_tiles` stores the precomputed scenic feature vector used by runtime routing. Important fields include:

- `water_score`
- `green_score`
- `elevation_score`
- `solitude_score`
- `curve_score`
- `poi_score`
- `park_score`
- `overture_poi_score`
- `building_density_score`
- `darkness_score`
- `urban_penalty_score`

## Data Lifecycle

Runtime routing reads precomputed tile scores. It does not calculate land cover, elevation, Overture buildings, parks, or light-pollution signals from raw data during a user request.

Current scenic release version:

- `3.1-darkness-urban-penalty-calibration`

Current data path:

1. Import raw geospatial sources into PostGIS with setup scripts.
2. Run `scripts/setup/run-data-enrichment-v31.ps1`.
3. Recompute `scenic_score_tiles` with `scripts/setup/data-quality-enrichment-v31.sql`.
4. Publish scenic tile release with `scripts/deploy/publish_scenic_release.ps1`.
5. Deploy scenic release with `.github/workflows/deploy-scenic-release.yml`.
6. Restart route services so caches refresh.

## Deployment

Production app deploys use:

- `.github/workflows/deploy-prod.yml`
- `docker-compose.prod.yml`
- `Caddyfile`
- `scripts/deploy/deploy_prod.sh`
- `scripts/deploy/rollback_prod.sh`

Data deploys use:

- `.github/workflows/deploy-data-release.yml`
- `.github/workflows/deploy-scenic-release.yml`
- `scripts/deploy/deploy_data_release.sh`
- `scripts/deploy/deploy_scenic_release.sh`

## Operational Notes

- Production is a single-node Docker Compose deployment.
- Kafka is single-broker with ZooKeeper.
- OSRM datasets and scenic tile releases are versioned artifacts.
- Large OSRM datasets should be built off-VM and deployed as release assets.
- WebSocket is an assist path; polling remains the fallback.
- Route feedback endpoints exist, but frontend personalization is not yet fully productized.

## Key References

- [Hybrid OSRM v2](HybridOsrmV2.md)
- [Deployment Pipeline](DeploymentPipeline.md)
- [Service Ownership](ServiceOwnership.md)
- [Route Quality Eval](RouteQualityEval.md)
- [Release QA Baseline](ReleaseQABaseline.md)
