# MoodRide – Cloud Deployment (GCP)

## 🧭 Prerequisites

- [ ] All steps in `DeploymentPrep.md` are complete
- [ ] Production Docker images built and tested locally
- [ ] Production compose runs end-to-end locally
- [ ] Runtime database dump exported (~5-10 GB)
- [ ] OSRM processed data ready (~4 GB for select provinces)
- [ ] DNS access for app.moodrides.com (or your chosen domain)
- [ ] GCP account with ~$400 credit

> Replace `northamerica-northeast1-a` with your instance zone if different
> (for example `northamerica-northeast1-c`).

---

## 🖥️ Infrastructure

### GCP Instance

| Setting | Value |
|---|---|
| Machine type | e2-standard-4 (4 vCPU, 16 GB RAM) |
| Region | northamerica-northeast1-a (Montreal) |
| OS | Ubuntu 26.04 LTS Minimal |
| Boot disk | 100 GB balanced persistent disk |
| Firewall | Allow HTTP (80) + HTTPS (443) |
| Static IP | Reserve and attach |
| Estimated cost | ~$118.71 USD/month |

> **Recommended:** run with 16 GB (e2-standard-4) for production workloads
> (OSRM + Java services + Postgres + Kafka). This is the configuration
> reflected below and in the cost table.

### Cost Breakdown

| Component | Monthly Cost |
|---|---|
| e2-standard-4 VM | ~$107.71 |
| 100 GB balanced disk | ~$11.00 |
| Static IP (while attached) | $0 |
| Egress (light traffic) | ~$1-2 |
| **Total** | **~$118.71 USD/month** |
| **Runway on $400** | **~3-4 months** |

> **To extend runway:** Schedule the VM to stop during sleeping hours using
> GCP instance schedules. A VM that runs 16 hours/day costs ~33% less.

---

## Phase 1: Provision GCP Instance

### 1A: Create the VM

```bash
# Using gcloud CLI (install from https://cloud.google.com/sdk/docs/install)

gcloud compute instances create moodride-prod \
  --zone=northamerica-northeast1-a \
  --machine-type=e2-standard-4 \
  --image-family=ubuntu-2604-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=100GB \
  --boot-disk-type=pd-balanced \
  --tags=http-server,https-server
```

Or create via GCP Console:
- [ ] Go to Compute Engine → VM Instances → Create Instance
- [ ] Name: `moodride-prod`
- [ ] Region: `northamerica-northeast1` (Montreal)
- [ ] Machine type: `e2-standard-4`
- [ ] Boot disk: Ubuntu 26.04 LTS Minimal, 100 GB balanced
- [ ] Firewall: Allow HTTP + HTTPS traffic
- [ ] Create

### 1B: Reserve Static IP

```bash
gcloud compute addresses create moodride-ip \
  --region=northamerica-northeast1

# Attach to instance
gcloud compute instances delete-access-config moodride-prod \
  --zone=northamerica-northeast1-a \
  --access-config-name="External NAT"

gcloud compute instances add-access-config moodride-prod \
  --zone=northamerica-northeast1-a \
  --address=<STATIC_IP>
```

- [ ] Note the static IP: _______________

### 1C: Configure DNS

Point your domain to the static IP:

```
Type: A
Name: app.moodrides.com (or your domain)
Value: <STATIC_IP>
TTL: 300
```

- [ ] DNS A record created
- [ ] Verify propagation: `dig app.moodrides.com` returns your IP

### 1D: Set Billing Alert

- [ ] Go to GCP Billing → Budgets & Alerts
- [ ] Create budget: $350 threshold
- [ ] Email notification enabled

---

## Phase 2: Server Setup

### 2A: SSH Into Server

```bash
gcloud compute ssh moodride-prod --zone=northamerica-northeast1-a
```

### 2B: Install Docker

```bash
sudo apt update && sudo apt upgrade -y

# Install Docker
sudo apt install -y apt-transport-https ca-certificates curl software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Add your user to docker group
sudo usermod -aG docker $USER
newgrp docker

# Verify
docker --version
docker compose version
```

### 2C: Create Project Directory

```bash
mkdir -p /opt/moodride
cd /opt/moodride
mkdir -p data/osrm
```

---

## Phase 3: Push Docker Images

### 3A: Set Up Container Registry

Using GitHub Container Registry (free for public repos, 500 MB free for private):

```bash
# On your LOCAL machine

# Login to GitHub Container Registry
echo $GITHUB_TOKEN | docker login ghcr.io -u <your-github-username> --password-stdin

# Tag images
docker tag moodride/route-api:v1 ghcr.io/<your-github-username>/moodride-route-api:v1
docker tag moodride/route-worker:v1 ghcr.io/<your-github-username>/moodride-route-worker:v1
docker tag moodride/notification-service:v1 ghcr.io/<your-github-username>/moodride-notification-service:v1
docker tag moodride/frontend:v1 ghcr.io/<your-github-username>/moodride-frontend:v1

# Push
docker push ghcr.io/<your-github-username>/moodride-route-api:v1
docker push ghcr.io/<your-github-username>/moodride-route-worker:v1
docker push ghcr.io/<your-github-username>/moodride-notification-service:v1
docker push ghcr.io/<your-github-username>/moodride-frontend:v1
```

### 3B: Pull Images on Server

```bash
# On the SERVER

# Login to registry
echo $GITHUB_TOKEN | docker login ghcr.io -u <your-github-username> --password-stdin

# Pull all images
docker pull ghcr.io/<your-github-username>/moodride-route-api:v1
docker pull ghcr.io/<your-github-username>/moodride-route-worker:v1
docker pull ghcr.io/<your-github-username>/moodride-notification-service:v1
docker pull ghcr.io/<your-github-username>/moodride-frontend:v1
```

> **Update docker-compose.prod.yml image references** to use the full
> `ghcr.io/<username>/moodride-*:v1` paths.

---

## Phase 4: Transfer Data

### 4A: Transfer Database Dump

```bash
# From LOCAL machine
gcloud compute scp backups/moodride_runtime_backup.dump \
  moodride-prod:/opt/moodride/ \
  --zone=northamerica-northeast1-a
```

### 4B: Transfer OSRM Data

```bash
# From LOCAL machine — transfer the processed OSRM files
# (the .osrm, .osrm.cell_metrics, .osrm.cells, etc.)
gcloud compute scp data/osrm/* \
  moodride-prod:/opt/moodride/data/osrm/ \
  --zone=northamerica-northeast1-a
```

> **This will take a while** if OSRM data is ~4 GB. Consider compressing first:
```bash
# Local
tar czf osrm-data.tar.gz -C data/osrm .
gcloud compute scp osrm-data.tar.gz moodride-prod:/opt/moodride/ --zone=northamerica-northeast1-a

# Server
cd /opt/moodride
tar xzf osrm-data.tar.gz -C data/osrm/
rm osrm-data.tar.gz
```

### 4C: Transfer Deployment Files

```bash
# From LOCAL machine
gcloud compute scp docker-compose.prod.yml \
  moodride-prod:/opt/moodride/ \
  --zone=northamerica-northeast1-a

gcloud compute scp Caddyfile \
  moodride-prod:/opt/moodride/ \
  --zone=northamerica-northeast1-a
```

### 4D: Create .env.prod on Server

```bash
# On SERVER — create the env file directly (don't transfer from local)
cat > /opt/moodride/.env.prod << 'EOF'
POSTGRES_DB=moodride
POSTGRES_USER=moodride
POSTGRES_PASSWORD=<generate-a-strong-password>
REDIS_PASSWORD=<generate-a-different-strong-password>
GHCR_NAMESPACE=<lowercase-ghcr-namespace>
IMAGE_TAG=<image-tag-to-deploy>
MOODRIDE_CORS_ALLOWED_ORIGINS=https://app.moodrides.com
OSRM_DATASET_BASENAME=new-brunswick-latest
EOF

# Restrict permissions
chmod 600 /opt/moodride/.env.prod
```

---

## Phase 5: Restore Database

```bash
# On SERVER

# Start only postgres first
cd /opt/moodride
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d postgres

# Wait for postgres to be ready
sleep 10
docker compose -f docker-compose.prod.yml logs postgres | tail -5

# Create extensions (if not in the dump)
docker compose -f docker-compose.prod.yml exec postgres psql -U moodride -d moodride -c "
  CREATE EXTENSION IF NOT EXISTS postgis;
  CREATE EXTENSION IF NOT EXISTS h3;
"

# Restore the dump
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_restore -U moodride -d moodride --no-owner --no-privileges \
  < /opt/moodride/moodride_runtime_backup.dump

# Verify
docker compose -f docker-compose.prod.yml exec postgres psql -U moodride -d moodride -c "
  SELECT 'scenic_score_tiles' as tbl, count(*) FROM scenic_score_tiles
  UNION ALL
  SELECT 'road_segments', count(*) FROM road_segments
  UNION ALL
  SELECT 'routes', count(*) FROM routes;
"
```

Expected output:
```
        tbl         | count
--------------------+--------
 scenic_score_tiles | 211510
 road_segments      | 3099304
 routes             | 111
```

---

## Phase 6: Launch

```bash
# On SERVER
cd /opt/moodride

# Start everything
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

# Check all services are running
docker compose -f docker-compose.prod.yml ps

# Check logs for errors
docker compose -f docker-compose.prod.yml logs --tail=50 route-api
docker compose -f docker-compose.prod.yml logs --tail=50 route-worker
docker compose -f docker-compose.prod.yml logs --tail=50 notification-service
docker compose -f docker-compose.prod.yml logs --tail=50 caddy

# Caddy should automatically provision SSL certificate
# This may take 30-60 seconds on first start
docker compose -f docker-compose.prod.yml logs caddy | grep "certificate"
```

---

## Phase 7: Verify

### 7A: Basic Health Checks

```bash
# From your local machine or browser

# SSL working?
curl -I https://app.moodrides.com
# Should return 200

# API responding?
curl https://app.moodrides.com/api/scenic-regions?lat=45.94&lng=-66.63&radius=50
# Should return scenic region data

# Route submission contract check (current schema)
curl -X POST https://app.moodrides.com/api/routes \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "00000000-0000-0000-0000-000000000001",
    "lat": 45.4215,
    "lng": -75.6972,
    "timeBudgetMinutes": 90,
    "vibes": ["countryside"],
    "preferenceVector": { "avoidTolls": false }
  }'
# Should return accepted job payload with jobId/status

# Frontend loading?
# Open https://app.moodrides.com in browser
# Should see the MoodRide UI
```

### 7B: Full Flow Test

- [ ] Open https://app.moodrides.com in browser
- [ ] Submit a route request (Fredericton, 60 min, countryside)
- [ ] Route generates successfully
- [ ] All 3 options display with different scores
- [ ] Map renders correctly
- [ ] Click "Start Drive" → opens Google Maps with waypoints
- [ ] Test from phone if possible

### 7C: Resource Monitoring

```bash
# On SERVER

# Check memory usage
docker stats --no-stream

# Check disk usage
df -h /

# Check database size
docker compose -f docker-compose.prod.yml exec postgres psql -U moodride -d moodride -c "
  SELECT pg_size_pretty(pg_database_size('moodride'));
"
```

**Expected memory distribution:**

| Service | Expected RAM |
|---|---|
| OSRM | 2-4 GB (depends on province data) |
| Postgres | 1-2 GB |
| Kafka + Zookeeper | 1 GB |
| route-api | 512 MB |
| route-worker | 1 GB |
| notification-service | 256 MB |
| Redis | 256 MB |
| Frontend | 128 MB |
| Caddy | 64 MB |
| **Total** | **~6-9 GB** |

> **If total exceeds 7.5 GB on 8 GB VM:** Upgrade to e2-standard-4 (16 GB).
> `gcloud compute instances stop moodride-prod --zone=northamerica-northeast1-a`
> Then change machine type in console, then start again.

---

## Phase 8: Post-Deploy

> For automated image deploys + versioned OSRM data releases, use
> [`docs/DeploymentPipeline.md`](./DeploymentPipeline.md).

### 8A: Set Up Basic Monitoring

Simple uptime check without Prometheus:

```bash
# On SERVER — create a simple health check script
cat > /opt/moodride/healthcheck.sh << 'EOF'
#!/bin/bash
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" https://app.moodrides.com/api/scenic-regions?lat=45.94&lng=-66.63&radius=1)
if [ "$RESPONSE" != "200" ]; then
    echo "$(date): HEALTH CHECK FAILED (HTTP $RESPONSE)" >> /opt/moodride/health.log
    cd /opt/moodride
    docker compose -f docker-compose.prod.yml --env-file .env.prod restart route-api
fi
EOF
chmod +x /opt/moodride/healthcheck.sh

# Run every 5 minutes via cron
(crontab -l 2>/dev/null; echo "*/5 * * * * /opt/moodride/healthcheck.sh") | crontab -
```

### 8B: Set Up Log Rotation

```bash
# Docker logs can grow unbounded — configure rotation
cat > /etc/docker/daemon.json << 'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
sudo systemctl restart docker
```

### 8C: Scheduled VM Stop (Optional — Save Money)

If you don't need 24/7 uptime, schedule the VM to stop at night:

- [ ] GCP Console → Compute Engine → VM Instances → moodride-prod
- [ ] Instance Schedules → Create Schedule
- [ ] Start: 8:00 AM EST daily
- [ ] Stop: 12:00 AM EST daily
- [ ] Saves ~33% on compute costs

### 8D: Update Workflow (For Future Changes)

When you need to deploy updates:

```bash
# LOCAL: Build new images
mvn clean package -DskipTests
docker build -t moodride/route-api:v2 services/route-api/
docker tag moodride/route-api:v2 ghcr.io/<username>/moodride-route-api:v2
docker push ghcr.io/<username>/moodride-route-api:v2

# SERVER: Pull and restart
cd /opt/moodride
docker compose -f docker-compose.prod.yml pull route-api
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d route-api
```

---

## 🛑 If Something Goes Wrong

### Service won't start
```bash
docker compose -f docker-compose.prod.yml logs <service-name> --tail=100
```

### Database connection errors
```bash
# Check postgres is running
docker compose -f docker-compose.prod.yml ps postgres
# Check connectivity from route-api container
docker compose -f docker-compose.prod.yml exec route-api ping postgres
```

### SSL certificate not provisioning
```bash
# Check Caddy logs
docker compose -f docker-compose.prod.yml logs caddy
# Verify DNS resolves to your IP
dig app.moodrides.com
```

### Out of memory
```bash
# Check what's using RAM
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}"
# Upgrade VM if needed (stop → change machine type → start)
```

### Out of disk
```bash
df -h /
# Clean up docker
docker system prune -f
docker volume prune -f  # CAREFUL: only if you know which volumes are unused
```

---

## Checklist Summary

| Phase | Tasks | Estimate |
|---|---|---|
| 1. Provision GCP VM | Create instance, static IP, DNS, billing alert | 30 min |
| 2. Server setup | Install Docker, create directories | 15 min |
| 3. Push images | Tag, push to registry, pull on server | 30 min |
| 4. Transfer data | Database dump, OSRM data, config files | 1-2 hours (transfer time) |
| 5. Restore database | Start postgres, restore dump, verify | 30 min |
| 6. Launch | Start all services, verify Caddy SSL | 15 min |
| 7. Verify | Health checks, full flow test, resource monitoring | 30 min |
| 8. Post-deploy | Health check cron, log rotation, update workflow | 30 min |
| **Total** | | **~4-5 hours** |

---

## ✅ After Deployment

You now have:
- Live product at https://app.moodrides.com
- Auto-renewing SSL
- Basic health monitoring
- Clear update workflow

**Next priorities:**
1. Run the test matrix against the live deployment
2. Share the link — get feedback
3. Add Prometheus + Grafana when you want real monitoring
4. Add more provinces to OSRM as needed

