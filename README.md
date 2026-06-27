# Wayward - Scenic Driving Route Generator 🚗🌄

**A distributed microservices platform that generates beautiful scenic driving loops based on time budget and vibe preferences.**

> "Instead of getting you somewhere fast, Wayward shows you something beautiful."

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Kafka-7.6-blue.svg)](https://kafka.apache.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.4-blue.svg)](https://postgis.net/)

---

## 📋 Quick Links

- **[Engineering Specification](docs/engineering-specification.md)** - System design, contracts, runtime scope
- **[Deployment Pipeline](docs/DeploymentPipeline.md)** - CI/CD and release flows
- **[Release QA Baseline](docs/ReleaseQABaseline.md)** - Post-deploy validation script
- **[Data Quality Upgrade](docs/DataQualityUpgrade.md)** - Land cover + DEM scoring plan
- **[Data Enrichment 3.0 Plan](docs/DataEnrichment30Plan.md)** - Overture + light-pollution enrichment plan
- **[Service Ownership](docs/ServiceOwnership.md)** - Active vs legacy service ownership
- **[Hybrid Routing Progress](docs/HybridRoutingProgress.md)** - Current route-generation status
- **[Route Export & UI Polish Progress](docs/RouteExportAndUIPolishProgress.md)** - UX polish status
- **[API Alias Deprecation Plan](docs/ApiAliasDeprecationPlan.md)** - `/routes/*` sunset plan
- **[Implementation Plan](docs/implementation-plan.md)** - Roadmap
- **[Project Structure](PROJECT_STRUCTURE.md)** - Microservices architecture overview

---

## 🎯 What is Wayward?

Wayward is a **scenic route generation platform** that inverts traditional navigation:

- ❌ Traditional GPS: **Minimize time** from Point A → Point B
- ✅ Wayward: **Maximize scenic beauty** over a circular loop with a time budget

### Example Use Case

> "I have 90 minutes free on Saturday morning. Generate me a beautiful coastal drive that starts and ends at my house."

Wayward generates a loop route optimized for:
- 🌊 **Coastal views** (water proximity)
- ⛰️ **Elevation changes** (mountain roads)
- 🌲 **Natural scenery** (forests, parks)
- 🛣️ **Road curvature** (winding roads)
- 🏞️ **Points of interest** (scenic overlooks, landmarks)

---

## 🏗️ Microservices Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Next.js Web    │────▶│   route-api      │────▶│   Kafka Queue   │
│  (Frontend)     │     │   (REST API)     │     │                 │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                            │
                   ┌───────────────────────────────────────┤
                   │                                       │
           ┌───────▼────────┐                    ┌────────▼────────┐
           │  route-worker  │                    │  notification   │
           │ (hybrid OSRM)  │                    │   (WebSocket)   │
           └───────┬────────┘                    └─────────────────┘
                   │
        ┌──────────┼──────────┐
        │          │          │
┌───────▼──────┐  │  ┌───────▼─────────┐
│   PostGIS    │  │  │   Redis Cache   │
│ (Scenic DB)  │  │  │  (4-layer)      │
└──────────────┘  │  └─────────────────┘
                  │
        ┌─────────▼──────────┐
        │       OSRM         │
        │  (Trip / Loop)     │
        └────────────────────┘
```

### Microservices

| Service | Purpose | Runtime |
|---------|---------|---------|
| **route-api** | REST API, job management, route detail | Always-on |
| **route-worker** | Hybrid OSRM loop generation + scoring | Always-on |
| **notification-service** | WebSocket completion/failure updates | Always-on |
| **scenic-scoring-service** | Targeted scenic tile recompute experiments | Offline/internal |

`ingestion-service` and `cdc-service` were moved out of the active build and archived locally under `legacy/`. Current production data upgrades are script-driven batch/offline pipelines with versioned scenic releases.

---

## 🚀 Tech Stack

**Backend:**
- **Java 25** (Virtual Threads, FFM API, Vector API) 🔥
- Spring Boot 3.3+
- PostgreSQL 15/16 + PostGIS 3.3/3.4
- Apache Kafka (Confluent 7.5/7.6)
- Redis 7.x (4-layer caching)
- OSRM (loop routing via `/trip`)
- H3 (spatial indexing)

**Frontend:**
- Next.js 14+ (React)
- Mapbox GL JS (map rendering)
- WebSocket (real-time route delivery)

**Data Sources:**
- OpenStreetMap - road network
- Natural Earth - water bodies
- Canada Land Cover - land use
- Copernicus DEM - elevation
- Protected/conserved areas - park proximity and park boost
- Overture Places/Buildings - POI quality and urban-density signals
- Light pollution / nighttime lights - darkness and solitude signal
- OpenTopoData - elevation profiles (optional)

**Observability:**
- Prometheus + Grafana (optional)
- Structured JSON logging

---

## 🎨 Key Innovations

1. **Hybrid OSRM Loop Generation**: intent-aware waypoint rings + corridor-quality scoring (`hybrid_osrm_v2`)
2. **H3 Scenic Intelligence**: Component scores (water/green/elevation/solitude/curve/poi/park/urban/darkness) + preferences
3. **Multi-Option Routes**: `most_scenic` / `balanced` / `shorter` profiles persisted per job
4. **Async Job Architecture**: Kafka workers + WebSocket completion/failure updates
5. **Versioned Data Releases**: OSRM + scenic tiles released through GitHub Actions
6. **Release QA Baseline**: Regression tracking after app/data deploys

---

## 🚀 Getting Started

### Prerequisites

- Java 25 (or Java 21 LTS)
- Maven 3.9+
- Docker & Docker Compose
- Node.js 18+
- Git

### Quick Start (5 minutes)

**1. Clone the repository**
```bash
git clone <repository-url>
cd MoodRide
```

**2. Start core infrastructure**
```bash
docker compose up -d
```

This starts:
- PostgreSQL + PostGIS (host port `5433`)
- Redis
- Kafka + Zookeeper
- OSRM (route engine for `/trip` loop routing)

On first startup, `osrm-prepare` preprocesses `data/osm-samples/new-brunswick-latest.osm.pbf` into `data/osrm/*.osrm*`.
This one-time step can take a few minutes depending on machine performance.

**Optional full infra stack (OpenTopoData + Prometheus + Grafana):**
```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```
Note: this stack exposes Postgres on `5432`, so set `SPRING_DATASOURCE_URL` accordingly when running services.

**3. Build all services**
```bash
mvn clean install
```

**4. Run services** (separate terminals)
```bash
# Terminal 1 - API
cd services/route-api
mvn spring-boot:run

# Terminal 2 - Worker
cd services/route-worker
mvn spring-boot:run
# (low-memory alternative on this machine)
powershell -ExecutionPolicy Bypass -File scripts/start-route-worker-lowmem.ps1

# Terminal 3 - WebSocket notifications
cd services/notification-service
mvn spring-boot:run

# Terminal 4 - Frontend
cd frontend/moodride-web
npm install && npm run dev
```

**5. Access the application**
- Frontend: http://localhost:3000
- API: http://localhost:8080
- OSRM: http://localhost:5002
- Grafana: http://localhost:3001 (admin/admin, optional)

---

## 📁 Project Structure

```
MoodRide/
├── services/              # Microservices
│   ├── route-api/         # REST API (Port 8080)
│   ├── route-worker/      # Hybrid OSRM worker (Port 8081)
│   ├── scenic-scoring-service/
│   ├── notification-service/
│   └── ...
├── shared/                # Shared libraries
│   ├── geo-commons/       # H3, JTS utilities
│   ├── event-models/      # Kafka schemas
│   └── data-models/       # JPA entities
├── frontend/
│   └── moodride-web/      # Next.js app
├── infrastructure/
│   └── docker/            # Docker Compose
├── data/                  # OSM files (gitignored)
└── docs/                  # Documentation
```

See [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) for detailed breakdown.

---

## ✅ Current Status (June 2026)

- Hybrid OSRM routing (`hybrid_osrm_v2`) is the default generator; beam-search fallback removed.
- Multi-option routes are persisted and exposed in API + UI (`most_scenic`, `balanced`, `shorter`).
- Start Drive (Google/Apple) + GPX export shipped; mobile handoff validation still pending.
- `/routes/*` aliases are in deprecation window; `/api/*` is canonical (sunset Aug 1, 2026).
- Release QA baseline script and artifacts are in place for each deploy.
- Core land-cover + DEM upgrade is complete nationally; 2.9 protected-area enrichment is locally artifacted.
- 3.0 Overture/light-pollution enrichment is implemented in schema, scripts, shared scoring, and route-quality evals. Confirm the GitHub release/deploy state with authenticated `gh` access before treating publication as verified from a fresh machine.

See [Hybrid OSRM v2](docs/HybridOsrmV2.md), [HybridRoutingProgress](docs/HybridRoutingProgress.md), [RouteExportAndUIPolishProgress](docs/RouteExportAndUIPolishProgress.md), [DataQualityUpgradeProgress](docs/DataQualityUpgradeProgress.md), and [DataEnrichment30Plan](docs/DataEnrichment30Plan.md).

---

## 🚢 Operations & Releases

- App deploys: GitHub Actions flow in [DeploymentPipeline](docs/DeploymentPipeline.md).
- Data releases: OSRM dataset + scenic tiles published to GitHub Releases and deployed via workflows.
- Release QA: run [ReleaseQABaseline](docs/ReleaseQABaseline.md) after app/data deploys; artifacts land in `artifacts/release-qa`.

---

## 🔧 Key Commands

```bash
# Build all services
mvn clean install

# Start core infrastructure
docker compose up -d

# Start full infrastructure (optional)
cd infrastructure/docker && docker compose up -d

# Run tests
mvn test

# Run specific service
cd services/route-api && mvn spring-boot:run

# Run route-worker with bounded heap (avoids local paging-file crashes)
powershell -ExecutionPolicy Bypass -File scripts/start-route-worker-lowmem.ps1

# Stop all containers
docker compose down

# View logs
docker compose logs -f kafka

# Release QA baseline (post-deploy)
powershell -ExecutionPolicy Bypass -File scripts/deploy/run_release_qa_baseline.ps1 -BaseUrl "https://app.moodrides.com"
```

---

## 📊 Monitoring (Optional)

- **Metrics**: http://localhost:9090 (Prometheus)
- **Dashboards**: http://localhost:3001 (Grafana)
- **Service Health**: http://localhost:8080/actuator/health

---

## 🔐 Security

- TLS termination via Caddy in production compose
- Secrets loaded via environment or `.env.prod` (see [infrastructure/docker/secrets/README.md](infrastructure/docker/secrets/README.md))
- Parameterized SQL queries (injection prevention)

---

## 📖 Documentation

- [Engineering Specification](docs/engineering-specification.md) - System design
- [Deployment Pipeline](docs/DeploymentPipeline.md) - CI/CD and release flows
- [Data Quality Upgrade](docs/DataQualityUpgrade.md) - Land cover + DEM scoring
- [Release QA Baseline](docs/ReleaseQABaseline.md) - Post-deploy validation

---

## 🙏 Acknowledgments

- OpenStreetMap contributors for road network data
- PostGIS team for geospatial database extensions
- Uber H3 for hexagonal hierarchical spatial indexing
- Spring Boot and Kafka communities

---

_Built with ❤️ for scenic drives and software engineering excellence._

