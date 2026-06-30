# Wayward Deployment Pipeline (GitHub Actions)

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
  -Repo "10xDeVv/Wayward"
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

### 5A. Audit scenic data-quality readiness

Before changing scenic scoring versions, run the read-only data-quality audit:

```powershell
./scripts/setup/audit-scenic-data-quality-v32.ps1 `
  -Database moodride `
  -Username postgres `
  -OutputDir artifacts/scenic-data-quality
```

Use the audit output to decide whether a new scoring train has enough evidence for road stress, water visibility, scenic viewpoints, tree canopy, bridge/coastal-road detection, or seasonal suitability. Do not increase route weights for a signal until the audit shows the source data is present and non-flat in the target region.

### 5B. Run nationwide scenic recompute locally

Stable 3.1 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v31.sql" `
  -ChunkSize 50000 `
  -ExpectedScoringVersion "3.1-darkness-urban-penalty-calibration"
```

Road-stress 3.2 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v32.sql" `
  -ChunkSize 50000 `
  -ExpectedScoringVersion "3.2-road-stress-calibration"
```

Water-visibility 3.3 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v33.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.2-road-stress-calibration" `
  -ExpectedScoringVersion "3.3-water-visibility-calibration"
```

Tree-canopy 3.4 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v34.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.3-water-visibility-calibration" `
  -ExpectedScoringVersion "3.4-tree-canopy-calibration"
```

Scenic-POI 3.5 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v35.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.4-tree-canopy-calibration" `
  -ExpectedScoringVersion "3.5-scenic-poi-calibration"
```

Viewpoint 3.6 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v36.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.5-scenic-poi-calibration" `
  -ExpectedScoringVersion "3.6-viewpoint-calibration"
```

Bridge/coastal 3.7 release train:

```powershell
./scripts/deploy/run_nationwide_scenic_recompute.ps1 `
  -SqlScriptPath "scripts/setup/data-quality-enrichment-v37.sql" `
  -ChunkSize 50000 `
  -SourceScoringVersion "3.6-viewpoint-calibration" `
  -ExpectedScoringVersion "3.7-bridge-coastal-calibration"
```

This executes the selected versioned scenic scoring SQL over your local `moodride` database.

Important behavior:

- Use the SQL script that matches the release train you are publishing.
- Current release train:
  - `scripts/setup/data-quality-enrichment-v37.sql` for `3.7-bridge-coastal-calibration`
- Next release train in development:
  - seasonal suitability / access warning metadata
- `data-quality-enrichment-v33.sql` requires non-empty `natural_earth_Water_Bodies`; re-import water geometry first if the audit reports 0 rows.
- `data-quality-enrichment-v34.sql` requires `landcover_raster` or `nlcd_land_cover_cells`; it derives a land-cover tree-canopy proxy, not true canopy height.
- `data-quality-enrichment-v35.sql` requires `overture_places`; it derives scenic/discovery place signal from weighted Overture categories, not from generic POI density.
- `data-quality-enrichment-v36.sql` requires `overture_places`; it derives a focused viewpoint/photo-landmark signal.
- `data-quality-enrichment-v37.sql` requires v3.3 water-road metrics and `overture_places`; it derives bridge/coastal-road-moment signal without re-running the full water spatial scan.
- Older versioned SQL files are kept only for reproducing previous scenic releases.
- The run is resumable. Re-running after interruption continues from remaining tiles not already at the expected scoring version.

### 5C. Publish scenic tile release from your local machine

```powershell
./scripts/deploy/publish_scenic_release.ps1 `
  -ScoringVersion "3.7-bridge-coastal-calibration" `
  -ReleaseTag "scenic-3.7-bridge-coastal-calibration-$(Get-Date -Format 'yyyyMMdd-HHmm')" `
  -Repo "10xDeVv/Wayward"
```

This uploads:

- `scenic-tiles-<scoring-version>.tar.gz`
- `scenic-tiles-<scoring-version>.tar.gz.sha256`

### 5D. Deploy scenic release to production

Run workflow `.github/workflows/deploy-scenic-release.yml` with:

- `release_tag`: example `scenic-3.7-bridge-coastal-calibration-20260630-1230`
- `scoring_version`: example `3.7-bridge-coastal-calibration`
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

- Keep production database and dataset backups outside the repository.
- Do not commit generated dumps, OSRM archives, scenic release tarballs, or local restore artifacts.
