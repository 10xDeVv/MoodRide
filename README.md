# MoodRide - Scenic Driving Route Generator 🚗🌄

**A distributed microservices platform that generates beautiful scenic driving loops based on time budget and vibe preferences.**

> "Instead of getting you somewhere fast, MoodRide shows you something beautiful."

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-blue.svg)](https://kafka.apache.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.4-blue.svg)](https://postgis.net/)

---

## 📋 Quick Links

- **[Engineering Specification](docs/engineering-specification.md)** - Complete technical specification (122KB)
- **[Implementation Plan](docs/implementation-plan.md)** - Phase-by-phase development guide
- **[Project Structure](PROJECT_STRUCTURE.md)** - Microservices architecture overview

---

## 🎯 What is MoodRide?

MoodRide is a **scenic route generation platform** that inverts traditional navigation:

- ❌ Traditional GPS: **Minimize time** from Point A → Point B
- ✅ MoodRide: **Maximize scenic beauty** over a circular loop with a time budget

### Example Use Case

> "I have 90 minutes free on Saturday morning. Generate me a beautiful coastal drive that starts and ends at my house."

MoodRide generates a loop route optimized for:
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
           │  (Beam Search) │                    │   (WebSocket)   │
           └───────┬────────┘                    └─────────────────┘
                   │
        ┌──────────┼──────────┐
        │          │          │
┌───────▼──────┐  │  ┌───────▼─────────┐
│   PostGIS    │  │  │   Redis Cache   │
│ (Geospatial) │  │  │  (4-layer)      │
└──────────────┘  │  └─────────────────┘
                  │
        ┌─────────▼──────────┐
        │  scenic-scoring    │
        │   (Batch Weekly)   │
        └────────────────────┘
```

### Microservices

| Service | Purpose | Tech |
|---------|---------|------|
| **route-api** | REST API, job management | Spring Boot Web, Kafka Producer |
| **route-worker** | Route generation (beam search) | Kafka Consumer, JGraphT, PostGIS |
| **scenic-scoring** | Weekly scenic tile scoring | Spring Batch, 6 external APIs |
| **ingestion-service** | OSM data ingestion | osm4j, PostGIS |
| **notification-service** | WebSocket real-time delivery | Spring WebSocket |
| **cdc-service** | Cache invalidation (Debezium) | Debezium, Redis |

---

## 🚀 Tech Stack

**Backend:**
- **Java 25** (Virtual Threads, FFM API, Vector API) 🔥
- Spring Boot 3.3+
- PostgreSQL 16 + PostGIS 3.4 (geospatial queries)
- Apache Kafka 3.7 (async job queue)
- Redis 7.2 (4-layer caching)
- Debezium 2.7 (CDC for cache invalidation)

**Frontend:**
- Next.js 14+ (React)
- Mapbox GL JS (map rendering)
- WebSocket (real-time route delivery)

**Data Sources:**
- OpenStreetMap - road network
- NLCD - land use classification
- OpenTopoData - elevation profiles (self-hosted)
- Natural Earth - water bodies
- TomTom API - traffic data (optional)

**Observability:**
- Prometheus + Grafana (metrics)
- Jaeger (distributed tracing)
- Structured JSON logging

---

## 🎨 Key Innovations

1. **NP-Hard Optimization**: Solves Orienteering Problem using **beam search (K=10)** instead of exact ILP
2. **H3 Hexagonal Indexing**: Superior to GeoHash for uniform spatial partitioning
3. **Precomputed Scenic Scores**: Weekly batch eliminates 1,200+ API calls per route request
4. **CDC Cache Invalidation**: Debezium monitors PostgreSQL WAL for near-real-time freshness
5. **Async Job Architecture**: Route generation happens in Kafka workers, not blocking API calls
6. **Java 25 Virtual Threads**: 2-5x throughput on I/O-heavy operations

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

**2. Start all infrastructure services**
```bash
cd infrastructure/docker
docker-compose up -d
```

This starts:
- PostgreSQL 16 + PostGIS
- Redis 7
- Apache Kafka + Zookeeper
- Debezium Connect
- OSRM (route engine for `/trip` loop routing)
- OpenTopoData (elevation API)
- Prometheus + Grafana
- Jaeger (tracing)

On first startup, `osrm-prepare` preprocesses `data/osm-samples/new-brunswick-latest.osm.pbf` into `data/osrm/*.osrm*`.
This one-time step can take a few minutes depending on machine performance.

**3. Build all services**
```bash
cd ../..
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

# Terminal 3 - Frontend
cd frontend/moodride-web
npm install && npm run dev
```

**5. Access the application**
- Frontend: http://localhost:3000
- API: http://localhost:8080
- OSRM: http://localhost:5002
- Grafana: http://localhost:3001 (admin/admin)
- Jaeger: http://localhost:16686

---

## 📁 Project Structure

```
MoodRide/
├── services/              # Microservices
│   ├── route-api/         # REST API (Port 8080)
│   ├── route-worker/      # Beam search worker (Port 8081)
│   ├── scenic-scoring-service/
│   ├── ingestion-service/
│   ├── notification-service/
│   └── cdc-service/
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

## 🧪 Development Workflow

### Completed Through Phase 4
- [x] Microservices structure and parent Maven build
- [x] Docker Compose infrastructure for PostgreSQL/PostGIS, Kafka, and Redis
- [x] Flyway schema and sample data for route generation
- [x] PostGIS-backed graph extraction and scenic route worker
- [x] End-to-end route generation via Kafka worker and database persistence

### Current Focus: Phase 5
- [x] Route submission endpoint
- [x] Job polling endpoint
- [x] Route retrieval endpoint
- [ ] Scenic region preview endpoint
- [ ] WebSocket notification service
- [ ] Timeout watchdog and retry logic

### Phase 2-8: See [implementation-plan.md](docs/implementation-plan.md)

**Total Timeline: 8-10 weeks**

---

## 🔧 Key Commands

```bash
# Build all services
mvn clean install

# Start infrastructure
cd infrastructure/docker && docker-compose up -d

# Run tests
mvn test

# Run specific service
cd services/route-api && mvn spring-boot:run

# Run route-worker with bounded heap (avoids local paging-file crashes)
powershell -ExecutionPolicy Bypass -File scripts/start-route-worker-lowmem.ps1

# Stop all containers
docker-compose down

# View logs
docker-compose logs -f kafka
```

---

## 📊 Monitoring

- **Metrics**: http://localhost:9090 (Prometheus)
- **Dashboards**: http://localhost:3001 (Grafana)
- **Tracing**: http://localhost:16686 (Jaeger)
- **Service Health**: http://localhost:8080/actuator/health

---

## 🔐 Security

- Rate limiting: 10 requests/min per user
- TLS only (HTTPS/WSS)
- Location data soft-deleted after 30 days
- External API keys in environment variables
- Parameterized SQL queries (injection prevention)

---

## 📖 Documentation

- [Engineering Specification](docs/engineering-specification.md) - Complete system design
- [Implementation Plan](docs/implementation-plan.md) - Development roadmap
- [Project Structure](PROJECT_STRUCTURE.md) - Microservices architecture

---

## 🙏 Acknowledgments

- OpenStreetMap contributors for road network data
- PostGIS team for geospatial database extensions
- Uber H3 for hexagonal hierarchical spatial indexing
- Spring Boot and Kafka communities

---

## 🎯 Project Status

**Current Phase**: Phase 5 - Async Job Queue & API In Progress  
**Progress**: Phases 3 and 4 are complete in the local environment, including successful end-to-end route generation.  
**Next Steps**: Finish the remaining phase-5 contract items: scenic region previews, notification delivery, and job timeout/retry behavior.

---

_Built with ❤️ for scenic drives and software engineering excellence._

