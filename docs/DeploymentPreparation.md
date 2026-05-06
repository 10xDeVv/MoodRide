
# Doc 1: `DeploymentPrep.md`


# MoodRide – Deployment Preparation

## 🧭 Purpose

Clean up the project for production deployment. Remove unused services and configurations,
fix security issues, reduce resource footprint, and prepare Docker images.

**Do all of this locally before touching any cloud infrastructure.**

---

## 📊 Current State

| Resource | Current Size | Target Size |
|---|---|---|
| Postgres | 92 GB | ~5-10 GB |
| Kafka | 40 GB | < 1 GB |
| Docker images | 5 GB | ~3 GB |
| Total disk footprint | ~150 GB | ~15-20 GB |

---

## Phase 1: Remove Unused Services

### 1A: Remove Kong

Kong is not running and Caddy will handle reverse proxy + SSL.

**Files to delete:**
- [ ] `infrastructure/docker/kong/kong.yml`
- [ ] `infrastructure/docker/kong/kong.prod.yml`
- [ ] `infrastructure/k8s/shared/kong-config.configmap.yaml`
- [ ] `infrastructure/k8s/shared/kong-jwt-secret.template.yaml`
- [ ] `infrastructure/k8s/shared/kong.deployment.yaml`
- [ ] `infrastructure/k8s/shared/kong.ingress.yaml`
- [ ] `infrastructure/k8s/shared/kong.service.yaml`
- [ ] `infrastructure/docker/secrets/kong-jwt.env.template`

**Compose changes:**
- [ ] Remove `kong` service from `infrastructure/docker/docker-compose.yml`
- [ ] Remove `kong` service from `infrastructure/docker/docker-compose.prod.yml`

**Frontend changes:**
- [ ] Update `NEXT_PUBLIC_API_BASE_URL` to point directly to route-api via Caddy (no Kong path)

### 1B: Remove Debezium + CDC Service

CDC consumer is 7 million messages behind. The data it tracks changes only during
offline recomputes. Replace with manual cache flush after recompute.

**Files to delete:**
- [ ] `infrastructure/docker/debezium/connector-config.json`
- [ ] `infrastructure/docker/debezium/healthcheck.sh`
- [ ] `infrastructure/docker/debezium/register-connector.sh`
- [ ] `infrastructure/k8s/shared/debezium-connect.deployment.yaml`
- [ ] `infrastructure/k8s/shared/debezium-connect.service.yaml`
- [ ] `infrastructure/k8s/shared/debezium-connector-register.job.yaml`
- [ ] `infrastructure/k8s/shared/debezium-connector.configmap.yaml`
- [ ] `infrastructure/k8s/cdc-service/` (entire directory)
- [ ] `infrastructure/docker/prometheus/cdc-alert-rules.yml`
- [ ] `infrastructure/k8s/shared/cdc-alerts.prometheusrule.yaml`

**Compose changes:**
- [ ] Remove `debezium` service from root `docker-compose.yml`
- [ ] Remove `debezium-init` service from root `docker-compose.yml`
- [ ] Remove `debezium` service from `infrastructure/docker/docker-compose.yml`

**Kafka topic cleanup:**
- [ ] Delete CDC topics:
```bash
docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic moodride.cdc.road_segments

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic moodride.cdc.scenic_score_tiles

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic debezium_configs

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic debezium_offsets

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic debezium_status
```

**Add replacement cache flush endpoint to route-api:**
- [ ] Add `POST /api/internal/cache/flush` endpoint that clears all Redis keys
- [ ] Use this after running offline recomputes instead of CDC

### 1C: Remove Jaeger

Not needed for initial deployment. Can add later.

**Compose changes:**
- [ ] Remove `jaeger` service from `infrastructure/docker/docker-compose.yml`

### 1D: Keep Prometheus + Grafana (But Don't Deploy Initially)

Keep all files in the codebase. They stay in `infrastructure/docker/` for future use.
They will NOT be included in the production compose file.

**No file changes needed.** Just don't include them in the production compose.

---

## Phase 2: Database Cleanup

### 2A: Drop Raw OSM Import Tables

These tables total ~85 GB and are only used by ingestion-service (confirmed: route-api
and route-worker have zero references to planet_osm_*).

```sql
-- Run against local database
-- WARNING: This is irreversible. Ingestion-service will need a re-import
-- if you ever need to regenerate road_segments from scratch.

DROP TABLE IF EXISTS planet_osm_nodes CASCADE;
DROP TABLE IF EXISTS planet_osm_ways CASCADE;
DROP TABLE IF EXISTS planet_osm_rels CASCADE;
DROP TABLE IF EXISTS planet_osm_polygon CASCADE;
DROP TABLE IF EXISTS planet_osm_line CASCADE;
DROP TABLE IF EXISTS planet_osm_point CASCADE;
DROP TABLE IF EXISTS planet_osm_roads CASCADE;
DROP TABLE IF EXISTS osm2pgsql_properties CASCADE;

-- Reclaim disk space
VACUUM FULL;
REINDEX DATABASE moodride;
```

**Checklist:**
- [ ] Take a pg_dump backup of the full database before dropping anything
- [ ] Run the DROP statements
- [ ] Run VACUUM FULL
- [ ] Verify database size dropped to ~5-10 GB
- [ ] Verify route-api and route-worker still function correctly
- [ ] Generate a test route to confirm end-to-end flow works

### 2B: Export Runtime Data Only

After cleanup, create a clean dump of only the tables needed in production:

```bash
pg_dump -h localhost -p 5433 -U postgres -d moodride \
  --table=scenic_score_tiles \
  --table=road_segments \
  --table=landuse_tile_summary \
  --table=water_tile_summary \
  --table=poi_tile_summary \
  --table=routes \
  --table=route_jobs \
  --table=route_waypoints \
  --table=flyway_schema_history \
  --table=spatial_ref_sys \
  -F c -f backups/moodride_runtime_backup.dump
```

This dump is what gets transferred to the production server.

Backup location policy:
- Runtime deploy dump: `backups/moodride_runtime_backup.dump`
- Full pre-cleanup archive: `D:\Backups\MoodRide\moodride_full_pre_cleanup_2026-04-30.dump`

---

## Phase 3: Kafka Cleanup

### 3A: Delete Stale Topics

Beyond CDC topics (already deleted in 1B), clean up topics that aren't used in production:

```bash
# Keep these (used at runtime):
# - route-jobs
# - route-completions
# - route.jobs.dlq

# Delete these (only used by services we're removing or not using):
docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic scenic-tile-updates

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic scenic.tiles.refreshed

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic traffic.tiles.updated

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic user.events.route_rated

docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic user.events.drive_completed
```

> **Note:** If route-worker consumes `scenic.tiles.refreshed`, check before deleting.
> Search route-worker source for the topic name. If it's referenced, keep it.

**Checklist:**
- [ ] Verify which topics route-api, route-worker, and notification-service actually consume/produce
- [ ] Delete only confirmed unused topics
- [ ] Verify Kafka volume dropped significantly after topic deletion

### 3B: Configure Retention

Set aggressive retention on remaining topics so Kafka doesn't accumulate data:

```bash
# 24 hour retention, 1 GB max per topic
docker exec kafka kafka-configs --bootstrap-server localhost:9092 \
  --alter --topic route-jobs \
  --add-config retention.ms=86400000,retention.bytes=1073741824

docker exec kafka kafka-configs --bootstrap-server localhost:9092 \
  --alter --topic route-completions \
  --add-config retention.ms=86400000,retention.bytes=1073741824

docker exec kafka kafka-configs --bootstrap-server localhost:9092 \
  --alter --topic route.jobs.dlq \
  --add-config retention.ms=604800000,retention.bytes=1073741824
```

DLQ gets 7 days retention (you want to inspect failed jobs). Everything else gets 24 hours.

---

## Phase 4: Security Fixes

### 4A: Remove Hardcoded Credentials

**Files with hardcoded passwords that need to be parameterized:**

- [ ] `infrastructure/docker/postgres/init.sql` — replace hardcoded password with environment variable
- [ ] `infrastructure/docker/debezium/connector-config.json` — already deleted in Phase 1
- [ ] `infrastructure/docker/.env.template` — update dev defaults to clearly marked placeholders

**For production, all secrets come from environment variables or a `.env` file that is NOT committed to git.**

Create `infrastructure/docker/.env.prod` (gitignored):
```bash
# Database
DB_USER=moodride
DB_PASSWORD=<generate-strong-password>
POSTGRES_DB=moodride

# Redis
REDIS_PASSWORD=<generate-strong-password>

# Mapbox (frontend)
NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN=<your-token>

# API URLs (set after DNS is configured)
NEXT_PUBLIC_API_BASE_URL=https://moodrides.com
NEXT_PUBLIC_WS_BASE_URL=wss://moodrides.com
```

- [ ] Add `.env.prod` to `.gitignore`
- [ ] Update `.env.prod.template` with placeholder values only

### 4B: Fix Redis Security

Current config has `bind 0.0.0.0` and `protected-mode no`. In production, Redis should
only be accessible from other containers on the Docker network, never from the host or internet.

- [ ] Update `infrastructure/docker/redis/redis.conf`:
```
bind 0.0.0.0
protected-mode yes
requirepass ${REDIS_PASSWORD}
```

- [ ] Update all services that connect to Redis to include the password in their config

### 4C: Lock Down Port Exposure

In the production compose, NO infrastructure ports are published to the host except
Caddy (80 + 443). Everything else communicates over the Docker internal network.

This is already handled in the production compose file (Phase 5), but verify:
- [ ] Postgres: no published port (containers access via `postgres:5432` internally)
- [ ] Kafka: no published port (containers access via `kafka:29092` internally)
- [ ] Redis: no published port (containers access via `redis:6379` internally)
- [ ] OSRM: no published port (worker accesses via `osrm:5000` internally)
- [ ] Only Caddy exposes 80 and 443

---

## Phase 5: Build Production Docker Images

### 5A: Java Services

Add a `Dockerfile` to each service that needs to run in production.

**services/route-api/Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
```

**services/route-worker/Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-Xmx1g", "-jar", "app.jar"]
```

**services/notification-service/Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
```

> **Note:** route-worker gets 1 GB heap because it loads scenic tiles into memory
> for scoring. notification-service is lightweight and needs minimal memory.

**Build all JARs and images:**
```bash
# Build JARs
mvn clean package -DskipTests

# Build Docker images
docker build -t moodride/route-api:v1 services/route-api/
docker build -t moodride/route-worker:v1 services/route-worker/
docker build -t moodride/notification-service:v1 services/notification-service/
```

- [ ] Add Dockerfile to route-api
- [ ] Add Dockerfile to route-worker
- [ ] Add Dockerfile to notification-service
- [ ] Build JARs successfully
- [ ] Build all three Docker images successfully
- [ ] Test each image runs locally: `docker run --rm moodride/route-api:v1`

### 5B: Frontend

**frontend/moodride-web/Dockerfile:**
```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_WS_BASE_URL
ARG NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
```

> **Important:** Next.js bakes `NEXT_PUBLIC_*` env vars into the build at build time.
> You must pass them as build args, not runtime env vars.

**next.config.js must include `output: 'standalone'`** for the standalone build to work:
```js
/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // ... other config
}
module.exports = nextConfig
```

```bash
docker build \
  --build-arg NEXT_PUBLIC_API_BASE_URL=https://moodrides.com \
  --build-arg NEXT_PUBLIC_WS_BASE_URL=wss://moodrides.com \
  --build-arg NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN=<your-token> \
  -t moodride/frontend:v1 \
  frontend/moodride-web/
```

- [ ] Ensure `output: 'standalone'` is in next.config.js
- [ ] Add Dockerfile to frontend/moodride-web/
- [ ] Build frontend image with correct build args
- [ ] Test image runs locally

### 5C: OSRM Data Preparation

Decide which provinces to support and prepare the OSRM data:

**Option A: New Brunswick only (current, smallest)**
- Already prepared, 170 MB processed
- Good for: testing deployment flow

**Option B: Select provinces (recommended)**
```bash
# Download individual province extracts from Geofabrik
wget https://download.geofabrik.de/north-america/canada/new-brunswick-latest.osm.pbf
wget https://download.geofabrik.de/north-america/canada/ontario-latest.osm.pbf
wget https://download.geofabrik.de/north-america/canada/british-columbia-latest.osm.pbf

# Merge with osmium
osmium merge new-brunswick-latest.osm.pbf ontario-latest.osm.pbf british-columbia-latest.osm.pbf -o canada-select.osm.pbf

# Process for OSRM
docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v5.27.1 \
  osrm-extract -p /opt/car.lua /data/canada-select.osm.pbf
docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v5.27.1 \
  osrm-partition /data/canada-select.osrm
docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v5.27.1 \
  osrm-customize /data/canada-select.osrm
```

**Expected sizes for selected provinces:**

| Province | PBF Size | Processed OSRM (est.) |
|---|---|---|
| New Brunswick | 63 MB | 170 MB |
| Ontario | ~800 MB | ~2 GB |
| British Columbia | ~600 MB | ~1.5 GB |
| **Combined** | **~1.5 GB** | **~4 GB** |

- [ ] Decide which provinces to support
- [ ] Download OSM extracts
- [ ] Merge if multiple provinces
- [ ] Run OSRM preprocessing
- [ ] Test OSRM serves correctly with new data
- [ ] Note: this processed data gets copied to the production server

---

## Phase 6: Create Production Compose File

**`docker-compose.prod.yml`** in project root:

```yaml
version: '3.8'

services:
  postgres:
    image: postgis/postgis:15-3.3
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - internal

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.1
    restart: unless-stopped
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
      KAFKA_HEAP_OPTS: "-Xmx256m"
    volumes:
      - zookeeper_data:/var/lib/zookeeper/data
    networks:
      - internal

  kafka:
    image: confluentinc/cp-kafka:7.5.1
    restart: unless-stopped
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_LOG_RETENTION_HOURS: 24
      KAFKA_LOG_RETENTION_BYTES: 1073741824
      KAFKA_HEAP_OPTS: "-Xmx512m"
    volumes:
      - kafka_data:/var/lib/kafka/data
    networks:
      - internal

  redis:
    image: redis:7.0-alpine
    restart: unless-stopped
    command: >
      redis-server
      --maxmemory 256mb
      --maxmemory-policy allkeys-lru
      --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis_data:/data
    networks:
      - internal

  osrm:
    image: ghcr.io/project-osrm/osrm-backend:v5.27.1
    restart: unless-stopped
    command: osrm-routed --algorithm mld /data/canada-select.osrm
    volumes:
      - osrm_data:/data
    networks:
      - internal

  route-api:
    image: moodride/route-api:v1
    restart: unless-stopped
    depends_on:
      - postgres
      - kafka
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${DB_NAME}
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
    networks:
      - internal

  route-worker:
    image: moodride/route-worker:v1
    restart: unless-stopped
    depends_on:
      - postgres
      - kafka
      - redis
      - osrm
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${DB_NAME}
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
      MOODRIDE_OSRM_BASE_URL: http://osrm:5000
    networks:
      - internal

  notification-service:
    image: moodride/notification-service:v1
    restart: unless-stopped
    depends_on:
      - kafka
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
    networks:
      - internal

  frontend:
    image: moodride/frontend:v1
    restart: unless-stopped
    networks:
      - internal

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - route-api
      - notification-service
      - frontend
    networks:
      - internal
      - external

networks:
  internal:
    driver: bridge
  external:
    driver: bridge

volumes:
  postgres_data:
  kafka_data:
  zookeeper_data:
  redis_data:
  osrm_data:
  caddy_data:
  caddy_config:
```

**Caddyfile:**
```
moodrides.com {
    handle /api/* {
        reverse_proxy route-api:8080
    }

    handle /ws/* {
        reverse_proxy notification-service:8084
    }

    handle {
        reverse_proxy frontend:3000
    }
}
```

- [ ] Create `docker-compose.prod.yml`
- [ ] Create `Caddyfile`
- [ ] Create `.env.prod` (gitignored) with real credentials
- [ ] Test full production stack locally: `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d`
- [ ] Verify all services start and communicate
- [ ] Verify Caddy routes requests correctly (test with /etc/hosts pointing moodrides.com to 127.0.0.1)

---

## Phase 7: Local Validation

Before deploying anywhere, verify the full production stack works locally:

- [ ] All containers start without errors
- [ ] `docker compose -f docker-compose.prod.yml ps` shows all services healthy
- [ ] Submit a route request through the frontend
- [ ] Route generates successfully
- [ ] All 3 options display
- [ ] Map renders correctly
- [ ] WebSocket notifications work (or polling fallback)
- [ ] Start Drive button works (opens Google Maps)
- [ ] No hardcoded localhost URLs leaking into production config

---

## Checklist Summary

| Phase | Tasks | Estimate |
|---|---|---|
| 1. Remove unused services | Delete Kong, Debezium, CDC, Jaeger files + configs | 2-3 hours |
| 2. Database cleanup | Drop planet_osm_*, VACUUM, export runtime dump | 1-2 hours |
| 3. Kafka cleanup | Delete stale topics, set retention | 30 min |
| 4. Security fixes | Parameterize secrets, lock down Redis, restrict ports | 1-2 hours |
| 5. Build Docker images | Dockerfiles for 3 services + frontend, OSRM data prep | 3-4 hours |
| 6. Production compose | Create docker-compose.prod.yml + Caddyfile | 1-2 hours |
| 7. Local validation | Full end-to-end test with production stack | 1-2 hours |
| **Total** | | **~10-15 hours** |

---

## 🛑 What NOT to Do in This Phase
- ❌ Change any application logic
- ❌ Modify the routing algorithm
- ❌ Add new features
- ❌ Touch the scoring pipeline
- ❌ Deploy to cloud (that's the next doc)

## ✅ After This Phase
- Project is clean: no unused services, no stale data
- Database is ~5-10 GB instead of 92 GB
- Kafka is < 1 GB instead of 40 GB
- Docker images are built and tested
- Production compose runs successfully locally
- Ready for cloud deployment
