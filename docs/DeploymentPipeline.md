# MoodRide Deployment Pipeline (GitHub Actions)

This pipeline removes the need to SSH into the VM for normal app deploys and data refreshes.

## 1. One-time GitHub setup

Add these repository secrets:

- `PROD_SSH_HOST`: VM public IP or DNS.
- `PROD_SSH_USER`: SSH username on VM (example: `adebowale_ca`).
- `PROD_SSH_PRIVATE_KEY`: private key used to SSH into the VM.
- `PROD_ENV_FILE`: full `.env.prod` contents (optional but recommended for first bootstrap).
- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`: frontend build token.

Optional repository variables:

- `GHCR_NAMESPACE`: container namespace in lowercase (example: `10xdevv`).
- `NEXT_PUBLIC_API_BASE_URL`: default `https://app.moodrides.com`.
- `NEXT_PUBLIC_WS_BASE_URL`: default `wss://app.moodrides.com/ws`.

## 2. App deployment flow

Workflow file: `.github/workflows/deploy-prod.yml`.

- On `push` to `main`, the workflow:
  1. Builds/pushes 4 images to GHCR with tag `sha-<commit>`.
  2. Uploads compose/caddy/deploy scripts to VM.
  3. Runs remote deploy script (`deploy_prod.sh`) with health check and rollback on failure.
- You can also run it manually via `workflow_dispatch`:
  - Set `image_tag` to deploy a specific tag.
  - Set `skip_build=true` to deploy an existing tag only.

## 3. Rollback flow

Remote rollback script:

```bash
cd /opt/moodride
./scripts/deploy/rollback_prod.sh --tag <previous-tag>
```

Or rollback to latest env snapshot:

```bash
cd /opt/moodride
./scripts/deploy/rollback_prod.sh
```

## 4. Data release flow (local machine -> GitHub Release -> VM)

### 4A. Build full Canada OSRM dataset locally

Use the build script (resume-friendly):

```powershell
./scripts/deploy/build_osrm_dataset.ps1 `
  -InputPbf "D:\DATA\canada-260405.osm.pbf" `
  -OutputDir "D:\DATA\osrm\canada" `
  -DatasetBasename "canada-latest"
```

If a step fails and you want to resume from a later stage:

```powershell
./scripts/deploy/build_osrm_dataset.ps1 `
  -InputPbf "D:\DATA\canada-260405.osm.pbf" `
  -OutputDir "D:\DATA\osrm\canada" `
  -DatasetBasename "canada-latest" `
  -SkipExtract
```

The script writes logs under `D:\DATA\osrm\canada\logs\`.

### 4B. Publish a data release from your local machine

Run after OSRM preprocessing finishes:

```powershell
./scripts/deploy/publish_data_release.ps1 `
  -DatasetBasename canada-latest `
  -DataDirectory "D:\DATA\osrm\canada" `
  -ReleaseTag "data-canada-latest-20260501" `
  -Repo "10xDeVv/MoodRide"
```

This uploads:

- `osrm-<dataset>.tar.gz`
- `osrm-<dataset>.tar.gz.sha256`

to the GitHub release tag you specify.

### 4C. Deploy that release to production

Run workflow `.github/workflows/deploy-data-release.yml` with:

- `release_tag`: example `data-canada-latest-20260501`
- `dataset_basename`: example `canada-latest`
- `asset_name`: optional (defaults to `osrm-<dataset>.tar.gz`)

The workflow downloads the release asset, copies it to VM, updates `OSRM_DATASET_BASENAME`, and restarts `osrm` + `route-worker`.

## 5. Scenic recompute + release flow (local machine -> GitHub Release -> VM)

### 5A. Run nationwide scenic recompute locally

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-upgrade-batched.sql" `
  -ChunkSize 500 `
  -ExpectedScoringVersion "2.6-raster-data-quality-upgrade-national-batched"
```

This executes the batched raster/DEM scoring SQL over your local `moodride` database.

Important behavior:

- `scripts/setup/data-quality-upgrade-batched.sql` is now a single-pass target to `2.6-raster-data-quality-upgrade-national-batched`.
- The run is resumable. Re-running after interruption continues from remaining tiles not already at `2.6`.

### 5B. Publish scenic tile release from your local machine

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "2.6-raster-data-quality-upgrade-national-batched" `
  -ReleaseTag "scenic-2.6-raster-data-quality-upgrade-national-batched-20260506" `
  -Repo "10xDeVv/MoodRide"
```

This uploads:

- `scenic-tiles-<scoring-version>.tar.gz`
- `scenic-tiles-<scoring-version>.tar.gz.sha256`

### 5C. Deploy scenic release to production

Run workflow `.github/workflows/deploy-scenic-release.yml` with:

- `release_tag`: example `scenic-2.6-raster-data-quality-upgrade-national-batched-20260506`
- `scoring_version`: example `2.6-raster-data-quality-upgrade-national-batched`
- `asset_name`: optional (defaults from `scoring_version`)

The workflow downloads the scenic asset, uploads it to VM, applies score updates into `scenic_score_tiles`, and restarts `route-api` + `route-worker`.

## 6. Post-deploy release QA baseline

Run a production QA sweep after app/data deploys:

```powershell
./scripts/deploy/run_release_qa_baseline.ps1 `
  -BaseUrl "https://app.moodrides.com" `
  -TimeBudgetMinutes 90 `
  -OutputDir "artifacts/release-qa"
```

Outputs:

- `artifacts/release-qa/release-qa-<timestamp>.json`
- `artifacts/release-qa/release-qa-<timestamp>.md`

## 7. Required `.env.prod` keys

Make sure `.env.prod` contains at least:

- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `REDIS_PASSWORD`
- `GHCR_NAMESPACE` (lowercase)
- `IMAGE_TAG`
- `MOODRIDE_CORS_ALLOWED_ORIGINS=https://app.moodrides.com`
- `OSRM_DATASET_BASENAME`

## 8. Backup locations

- Runtime deployment dump used for restore:
  - `backups/moodride_runtime_backup.dump`
- Full pre-cleanup archive (cold/local storage):
  - `D:\Backups\MoodRide\moodride_full_pre_cleanup_2026-04-30.dump`
