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
- `NEXT_PUBLIC_WS_BASE_URL=https://usewayward.app/ws`

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
3. uploads `docker-compose.prod.yml`, `docker-compose.admin.yml`, `Caddyfile`, and deploy scripts
4. runs `scripts/deploy/deploy_prod.sh` on the VM
5. health-checks production
6. rolls back on failed health check

The admin compose file is uploaded for operator use, but the normal production deploy does not start the admin profile.

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

## Admin Visibility

Production includes an optional admin profile for internal visibility:

- Dozzle: browser UI for Docker logs
- CloudBeaver: browser UI for browsing Postgres tables

These tools are intentionally bound to `127.0.0.1` on the production VM. They are not routed through Caddy and are not public.

Start the tools on the VM:

```bash
cd /opt/moodride
./scripts/deploy/manage_admin_tools.sh start
```

Open an SSH tunnel from your local machine:

```bash
ssh -L 8088:127.0.0.1:8088 -L 8978:127.0.0.1:8978 <prod-user>@<prod-host>
```

Then open:

- logs: `http://localhost:8088`
- database browser: `http://localhost:8978`

In CloudBeaver, connect to Postgres with:

- host: `postgres`
- port: `5432`
- database: value of `POSTGRES_DB`
- user: value of `POSTGRES_USER`
- password: value of `POSTGRES_PASSWORD`

Useful commands on the VM:

```bash
./scripts/deploy/manage_admin_tools.sh status
./scripts/deploy/manage_admin_tools.sh logs
./scripts/deploy/manage_admin_tools.sh stop
```

Do not expose Dozzle or CloudBeaver directly to the internet. Dozzle can read Docker container metadata and logs through the Docker socket, and CloudBeaver can inspect production data. Keep access behind SSH.

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
  -Repo "10xDeVv/Wayward" `
  -DeployToProduction `
  -WaitForProductionDeploy
```

Publishing creates the GitHub Release asset. `-DeployToProduction` immediately triggers the production scenic deploy workflow so production Postgres consumes that asset. `-WaitForProductionDeploy` keeps the command open until the workflow succeeds or fails.

Manual deploy, if the release asset already exists:

```text
.github/workflows/deploy-scenic-release.yml
```

The scenic deploy applies score updates into `scenic_score_tiles` and restarts route services so caches refresh.

After deploy, verify the active scenic release in CloudBeaver or psql:

```sql
SELECT
  scoring_version,
  COUNT(*) AS tiles,
  COUNT(*) FILTER (WHERE road_stress_score > 0) AS road_stress,
  COUNT(*) FILTER (WHERE water_visibility_score > 0) AS water_visibility,
  COUNT(*) FILTER (WHERE tree_canopy_score > 0) AS tree_canopy,
  COUNT(*) FILTER (WHERE scenic_poi_score > 0) AS scenic_poi,
  COUNT(*) FILTER (WHERE viewpoint_score > 0) AS viewpoint,
  COUNT(*) FILTER (WHERE bridge_coastal_score > 0) AS bridge_coastal
FROM scenic_score_tiles
GROUP BY scoring_version
ORDER BY tiles DESC;
```

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
