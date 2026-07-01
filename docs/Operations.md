# Wayward Operations

Last reconciled: 2026-07-01

This document is the practical runbook for local development, production deployment, data releases, QA, and cleanup.

## Local Development

Prerequisites:

- Java 21+ or Java 25
- Maven 3.9+
- Docker Desktop
- Node.js 18+

Start local dependencies:

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

- frontend: `http://localhost:3000`
- route API: `http://localhost:8080`
- OSRM: `http://localhost:5002`

## Production Domain

Production serves:

- `https://usewayward.app`
- `https://www.usewayward.app`

DNS records:

| Type | Host | Value |
| --- | --- | --- |
| `A` | `@` | production VM public IPv4 |
| `CNAME` | `www` | `usewayward.app` |

`.app` domains require HTTPS in modern browsers. DNS must point to the VM and ports `80` and `443` must be reachable so Caddy can issue certificates.

## GitHub Secrets And Variables

Required repository secrets:

- `PROD_SSH_HOST`
- `PROD_SSH_USER`
- `PROD_SSH_PRIVATE_KEY`
- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`

Optional but useful:

- `PROD_ENV_FILE`
- `GHCR_USERNAME`
- `GHCR_PAT`

Optional repository variables:

- `GHCR_NAMESPACE`
- `NEXT_PUBLIC_API_BASE_URL=https://usewayward.app`
- `NEXT_PUBLIC_WS_BASE_URL=wss://usewayward.app/ws`

Minimum `.env.prod` keys:

- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `REDIS_PASSWORD`
- `GHCR_NAMESPACE`
- `IMAGE_TAG`
- `MOODRIDE_CORS_ALLOWED_ORIGINS=https://usewayward.app,https://www.usewayward.app`
- `OSRM_DATASET_BASENAME`

## App Deployment

Workflow:

```text
.github/workflows/deploy-prod.yml
```

On push to `main`, the workflow:

1. builds service and frontend images
2. pushes images to GHCR
3. uploads `docker-compose.prod.yml`, `Caddyfile`, and deploy scripts
4. runs `scripts/deploy/deploy_prod.sh` on the VM
5. health-checks production
6. rolls back on failed health check

Manual deploy of an existing image:

- run `Deploy Production Stack`
- set `skip_build=true`
- set `image_tag=sha-<commit>`

Rollback on the VM:

```bash
cd /opt/moodride
./scripts/deploy/rollback_prod.sh --tag <previous-tag>
```

The deploy script explicitly recreates Caddy so domain/TLS config changes are loaded.

## OSRM Data Release

Build a dataset locally:

```powershell
./scripts/deploy/build_osrm_dataset.ps1 `
  -InputPbf "D:\DATA\canada-260405.osm.pbf" `
  -OutputDir "D:\DATA\osrm\canada" `
  -DatasetBasename "canada-latest"
```

Publish the release:

```powershell
./scripts/deploy/publish_data_release.ps1 `
  -DatasetBasename "canada-latest" `
  -DataDirectory "D:\DATA\osrm\canada" `
  -ReleaseTag "data-canada-latest-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/Wayward"
```

Deploy with:

```text
.github/workflows/deploy-data-release.yml
```

## Scenic Tile Release

Audit data-quality readiness:

```powershell
./scripts/setup/audit-scenic-data-quality-v32.ps1 `
  -Database moodride `
  -Username postgres `
  -OutputDir artifacts/scenic-data-quality
```

Run a scenic recompute:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v37.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.6-viewpoint-calibration" `
  -ExpectedScoringVersion "3.7-bridge-coastal-calibration"
```

Publish scenic tiles:

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.7-bridge-coastal-calibration" `
  -ReleaseTag "scenic-3.7-bridge-coastal-calibration-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/Wayward"
```

Deploy with:

```text
.github/workflows/deploy-scenic-release.yml
```

The scenic deploy applies score updates into `scenic_score_tiles` and restarts route services so caches refresh.

## Release QA

After app or data deploys, run:

```powershell
./scripts/deploy/run_release_qa_baseline.ps1 `
  -BaseUrl "https://usewayward.app" `
  -TimeBudgetMinutes 90 `
  -OutputDir "artifacts/release-qa"
```

For route-engine tuning:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "https://usewayward.app" `
  -OutputDir "artifacts/route-quality-eval"
```

Quick smoke:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "https://usewayward.app" `
  -MaxScenarios 2
```

## Verification Commands

Frontend:

```powershell
cd frontend/moodride-web
npm run lint
npm run build
```

Backend route API and worker:

```powershell
mvn -pl services/route-api,services/route-worker -am test
```

Cache policy parity:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/monitoring/verify-cache-policy-parity.ps1
```

Production smoke:

```powershell
Invoke-WebRequest -UseBasicParsing "https://usewayward.app" -TimeoutSec 15
Invoke-WebRequest -UseBasicParsing "https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1" -TimeoutSec 15
Invoke-WebRequest -UseBasicParsing "https://usewayward.app/api/analytics/summary?days=30" -TimeoutSec 15
```

## Repository Hygiene

Do not commit:

- raw data
- OSRM archives
- scenic release tarballs
- database dumps
- generated logs
- IDE files
- local restore artifacts
- `.next`, `target`, or other build outputs

Ignored local folders such as `legacy/`, `portfolio/`, `.idea/`, `.tmp-verify/`, and generated data archives may exist on a developer machine, but they are not part of the launch repo.

## Archived Docs

Older implementation notes, evidence docs, and route-quality rerun summaries live under:

```text
docs/archive/
```

They are retained for history and regression evidence. The official documentation set is:

- `README.md`
- `docs/Architecture.md`
- `docs/RouteEngine.md`
- `docs/Operations.md`
