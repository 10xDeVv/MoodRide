# Wayward Operations

Last reconciled: 2026-07-19

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

Production environment values are defined by `.env.prod.template`; the template is intentionally non-deployable until every `replace-with-*` value is replaced. At minimum, production must provide:

- Postgres and Redis database names, users, and secrets.
- `GHCR_NAMESPACE`, the exact `IMAGE_TAG=sha-<40-lowercase-hex>`, and digest-pinned `ROUTE_API_IMAGE_REF`, `ROUTE_WORKER_IMAGE_REF`, `NOTIFICATION_SERVICE_IMAGE_REF`, and `FRONTEND_IMAGE_REF`.
- Digest-pinned `CADDY_IMAGE_REF` and `OSRM_IMAGE_REF`, plus the exact `OSRM_DATASET_BASENAME`.
- Scenic scoring, road dataset revision/fingerprint, cache schema/policy, algorithm profile/mode, CORS, and public URL values required by `docker-compose.prod.yml`.

The release scripts reject missing values, template placeholders, mutable image tags, duplicate runtime environment keys, identity mismatches, and secrets with the wrong format. `MOODRIDE_ANALYTICS_HASH_SECRET` must be exactly 64 lowercase hexadecimal characters. Generate it exactly once only when absent, persist it with protected permissions in `.env.prod`, and never print or regenerate it during later deploys. Its first introduction intentionally replaces the historical known fallback and therefore creates an analytics-pseudonym cohort boundary; do not join before/after unique-client cohorts across that boundary.

## App Deployment

Workflow:

```text
.github/workflows/deploy-prod.yml
```

Production is one application stack on one VM. Every deployment is therefore a maintenance-window guarded full-stack cutover; there is no traffic-isolated canary or percentage split.

Pushes to `main` run `publish-only`. Before publishing, the workflow checks out the exact source SHA and runs the backend reactor tests, frontend lint/type/build, release-contract behavior fixtures, strict Compose interpolation, and shell syntax checks. It then publishes all four app images under immutable `sha-<40-lowercase-hex-commit>` tags and emits a checksum-pinned four-image release lock. A main push does not contact the VM or change production.

Use an explicit `Publish and Deploy Production Stack` workflow dispatch for exactly one operation:

- `publish-only` — verify and publish all four immutable app images for the selected source without deploying.
- `capture-runtime` — make a read-only observation of the running production containers, OCI revisions/sources, exact image digests, Flyway lineage, data/cache configuration, and OSRM/Caddy identities. It accepts no release inputs and uploads evidence even when attribution fails.
- `deploy-existing` — verify and deploy an already-published image set. Supply the exact `image_tag`, release-lock JSON, SHA-256 of those exact JSON bytes, base64-encoded quality-acceptance JSON, and SHA-256 of the decoded quality bytes. It never rebuilds.
- `rollback-current` — fence the currently running source with its exact tag and release-lock bytes/checksum, then restore the previous image, control-bundle, Caddy, OSRM, environment, and database state captured by the accepted deployment. The caller cannot choose an arbitrary rollback target.

The production-environment approval is the trust boundary for the caller-supplied quality document. Before approval, retain and review the referenced upstream evaluator artifacts. The document must:

- carry a pass verdict for the frozen 27-scenario drive manifest whose SHA-256 is `2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00`;
- identify independent control and candidate source SHAs, release locks, image refs/index digests/revisions, artifact hashes, database/Flyway fingerprints, and runtime identities;
- prove both sides used the same scenic release/content, road dataset, OSRM image/dataset/file manifest, cache policy, `hybrid_osrm_v2` algorithm, and drive mode; only the schema/Flyway fingerprint may differ;
- bind the candidate identities to the dispatched release lock and the control identities to the actually running pre-deploy stack.

Deployment files are uploaded into a checksum-versioned immutable control bundle under `/opt/moodride/.deploy/bundles`. Compose, Caddy, scripts, release lock, and quality acceptance are validated from that bundle. The accepted `/opt/moodride/.deploy/current` symlink changes atomically only after rollback metadata is durable and candidate validation passes. The mutable project-root scripts are not a deployment or rollback control plane.

The closed-ingress cutover performs:

1. Acquire the production cutover lock; validate the candidate bundle, exact app/Caddy/OSRM digests, release lock, quality document, backups, and both runtime identities.
2. Submit one reserved internal control route and verify its committed primary route records the accepted algorithm and mode. Compare the running control images, lock, DB/data/cache/OSRM identities, and attempt-bound evidence before maintenance begins.
3. Stop Caddy so no new public request can enter; keep the old API and worker running temporarily. Drain every `QUEUED`, `PROCESSING`, and `PRIMARY_READY` job, then stop the old app services and assert that no active job remains.
4. Snapshot Flyway history and create a checksum- and catalog-verified Postgres custom-format dump in the absolute host `BACKUP_DIR`, outside Docker-managed volumes. Restore and validate it in a scratch database before the live schema transition.
5. Start candidate `route-api` alone so Flyway and API readiness establish the new schema boundary. Start the candidate worker, notification service, and frontend behind closed ingress.
6. Run internal health/WebSocket checks and a reserved candidate route that proves `PRIMARY_READY`, completion, durable terminal delivery, and the persisted algorithm/mode. Compare candidate DB/data/cache/OSRM identities to the accepted candidate evidence.
7. Persist the rollback snapshot and accepted metadata, atomically switch the current bundle pointer, recreate Caddy from its exact digest and bundle Caddyfile, then run public API, frontend, and WebSocket health checks. Archive side-specific, deployment-attempt-bound control and candidate evidence.

Do not manually reopen ingress after a failed gate. Before ingress reopens, automatic recovery restores the validated prior database and exact prior image/control bundle. After public writes could have resumed, the scripts fail closed for explicit coordinated recovery.

Use the `rollback-current` workflow operation for rollback. Do not run `/opt/moodride/scripts/deploy/rollback_prod.sh` and do not pass a target tag: that mutable path and arbitrary-target interface are intentionally unsupported. The workflow verifies `/opt/moodride/.deploy/current`, then invokes the checksum-verified rollback script inside that immutable bundle with the exact current tag and release-lock checksum. Rollback closes Caddy, drains jobs, validates an off-volume dump, applies the guarded schema/Flyway rollback, starts and smoke-tests the compatible target stack behind closed ingress, and only then reopens the exact target Caddy image/config. A failed switch restores the complete pre-rollback database and original image/control bundle or leaves ingress and consumers stopped.

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

Release QA is ordered:

1. Run the **local current-source gate** against the exact API and worker artifacts proposed for release.
2. Review and accept that gate's provenance, route outputs, quality flags, maps, and latency evidence.
3. Only then deploy the accepted artifacts and run production checks as supplemental environment verification.

Do not start production supplemental checks while the local gate is incomplete, blocked by a prerequisite, or not accepted for release. A startup check, a bounded route request, a successful script exit, or technical completion with unresolved quality-contract failures does not substitute for an accepted local gate.

### Evidence classes

Keep these evidence classes separate in reports and release decisions:

| Class | What it establishes | What it does not establish |
| --- | --- | --- |
| **Startup/wiring smoke** | The API starts, the worker starts, dependencies connect, and a job can traverse the API/Kafka/worker wiring far enough to produce a status or an attributed prerequisite failure. | Route generation, OSRM execution, route quality, geometry quality, or first-result latency. |
| **Route-generation verification** | At least one current-source job reaches `COMPLETED`, returns a non-empty route ID and geometry, and worker logs show that scenic lookup, candidate generation, and OSRM were actually reached. | Performance or quality over the frozen scenario set. |
| **Cache microbenchmark** | The cost of the scenic-tile cache path under the stated cache state and data size. | End-to-end route latency, first-result latency, route completion, or route quality. |
| **Full matched quality evaluation** | Every selected scenario from the frozen manifest ran under an attributed source/runtime/data plane, with technical completion, flags, maps, and latency artifacts available for review. | A quality pass unless the recorded quality criteria, flag dispositions, and map review also pass. |

`COMPLETED`, `completedCount`, and “returned a route” are **technical completion** only. Never relabel them as a usable-route rate or quality-pass rate. Quality acceptance additionally requires the recorded contract flags, route geometry maps, and release criteria to be reviewed and accepted.

### Required local current-source gate

Use an immutable copy of the frozen 27-scenario manifest. The hash-verified archived copy currently available is:

```text
artifacts/route-quality-eval/final-matched-legacy-0fda4ca7/route-quality-eval-20260716-021141-scenarios.json
SHA-256: 2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00
```

Verify the bytes before starting and stop on any mismatch. Do not silently fall back to `scripts/monitoring/route-quality-scenarios.json` or compare runs produced from different manifests.

```powershell
$scenarioFile = (Resolve-Path "artifacts/route-quality-eval/final-matched-legacy-0fda4ca7/route-quality-eval-20260716-021141-scenarios.json").Path
$expectedManifestSha256 = "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00"
$actualManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $scenarioFile).Hash.ToLowerInvariant()
if ($actualManifestSha256 -ne $expectedManifestSha256) {
  throw "Frozen scenario manifest hash mismatch: $actualManifestSha256"
}
```

Before submitting the first job, create a run directory and a machine-readable provenance sidecar. The sidecar and retained artifacts must identify:

- the full source commit, whether the worktree was clean, and the intended release identifier
- API and worker artifact paths plus SHA-256 hashes
- API and worker container tags, immutable image IDs/digests, and build commit; for a host-JAR run, record `image: not-applicable` rather than omitting the field
- the exact runtime mode, active route mode/configuration, base URL, Java/container runtime versions, host resource limits, and cold/warm cache policy
- the frozen manifest path, copied manifest, SHA-256, scenario count, and selected scenario IDs
- the Postgres endpoint role, restored dump or scenic release identifier, scoring/schema version, and coverage checks used to attribute the DB data plane; do not record credentials
- the OSRM image digest or binary version, dataset/release identity and checksum, routing profile, and endpoint used to attribute the OSRM data plane
- start/finish timestamps and the path to raw API, worker, and dependency logs; the worker log must cover startup through the last job's terminal state
- the evaluator JSON, CSV, and Markdown output paths; the checked-in runner writes these three files but does not embed the complete provenance above

Run the evaluator against the local current-source services, not production:

```powershell
$gateDir = "artifacts/route-quality-eval/local-release-gate-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force -Path $gateDir | Out-Null
Copy-Item -LiteralPath $scenarioFile -Destination (Join-Path $gateDir "frozen-scenarios.json")

powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "http://localhost:8080" `
  -ScenarioFile $scenarioFile `
  -RouteMode "drive" `
  -OutputDir $gateDir
```

The resulting evidence bundle is not complete until it contains all of the following:

- the provenance sidecar, frozen manifest copy, and manifest hash output
- unedited evaluator JSON and CSV plus the generated Markdown summary
- raw worker logs and any API/OSRM/DB diagnostics needed to attribute failures
- a rendered geometry map for every completed scenario/profile, generated from that run's JSON coordinates and named by run, scenario, and profile
- technical-completion counts split into `COMPLETED`, `FAILED`, `TIMEOUT`, and runner `ERROR`, with failure reasons preserved
- unaggregated scenario/option quality flags, flag counts, and the operator's explicit map/flag disposition
- raw per-job lifecycle timestamps and revisions plus derived latency JSON/CSV, including the population, exclusions, percentile method, p50, and p95

The checked-in `run-route-quality-eval.ps1` polls terminal status and records `elapsedSeconds` and route-detail `computationTimeMs`; it does not by itself measure first-result latency or generate maps. Capture a companion lifecycle trace and maps. A gate assembled only from the runner's JSON/CSV/Markdown is incomplete.

For successful jobs, the lifecycle trace must show monotonic revisions through `QUEUED -> PROCESSING -> PRIMARY_READY -> COMPLETED`; `FAILED` and `TIMEOUT` are allowed terminal branches and must retain their reasons. Retain worker lease/fencing evidence and verify that stale revisions were not accepted. If browser-visible first-result latency is claimed, also retain a browser/network trace showing WebSocket-first delivery, the 2.5-second wait before the one-in-flight 1.5-second polling fallback, the slim exact-primary fetch and paint before rich details, and stale-revision rejection.

Label latency populations precisely:

- submit to `PRIMARY_READY`: server first-result availability
- `PRIMARY_READY` to primary paint: client delivery/fetch/paint
- submit to primary paint: user-visible first result
- submit to `COMPLETED`: terminal rich-result completion

The current planner still computes the candidate pool before `PRIMARY_READY`. Progressive delivery removes response/enrichment delay after selection; it is not staged route computation. The browser baseline below measures the pre-deploy production stack; no matched post-deploy cohort exists yet, so do not infer a new current-source end-to-end p50/p95 or a causal improvement from terminal polling, frontend fallback cadence, or cache timings.

### Current local-gate status

As of 2026-07-19, the OSRM and scenic-data prerequisites have been restored; they are no longer the current blocker. The local data plane used the signed `3.7-bridge-coastal-calibration` scenic release and `ghcr.io/project-osrm/osrm-backend:v5.27.1` at digest `sha256:855614a38f464b0558a2ad6eaa7cb8c139f39887da9b38b485ce453c6e6e6124`.

The current-source local gate is recorded at:

```text
artifacts/route-quality-eval/current-source-full-gate-20260719/provenance.json
artifacts/route-quality-eval/current-source-full-gate-20260719/route-quality-eval-20260719-202022.json
```

All 27 scenarios reached a terminal outcome: 16 technically completed and 11 ended `vibe_unavailable`. Every one of the 16 completed scenarios retained route-quality contract failures, so the gate recorded zero clean contract-qualified completions and a release-quality failure. Technical completion remains distinct from quality acceptance.

This was a local run of checksum-recorded host JARs from a dirty worktree (`49` unstaged and `16` untracked files at capture), not an immutable-image run and not production evidence. It does not establish which container image should be released, a live production revision, or production latency.

The remaining release gates are:

1. isolate and review the coordinated full-stack source revision, publish all app images under its immutable SHA tag, and verify their revision labels;
2. run and accept the required gate against those exact immutable images, including explicit disposition of the 11 unavailable scenarios and every quality-contract failure;
3. dispatch the single-instance maintenance cutover and verify the live container image digests and revision labels; and
4. run the matched post-deploy production cohort described below.

The earlier failed prerequisite smoke remains historical diagnostic evidence only:

```text
artifacts/route-quality-eval/first-latency-current-source-smoke-20260719/route-quality-eval-20260719-173108.json
```

Do not present that smoke or the later dirty-host gate as production evidence.

### Historical evidence and comparison rules

The historical canonical path `scripts/monitoring/benchmarks/m0-route-quality-scenarios-20260710.json` is not present. The archived selected manifest above is hash-verified and can seed a new reproducible harness, but using it with the current runner creates a **new** run. It does not retroactively establish the historical runner revision or provenance.

The checked-in runner lacks the commit, artifact/image, runtime-mode, manifest-copy, and diagnostics fields present in the July control artifacts. `compare-route-quality-evals.ps1` also matches scenario IDs without enforcing provenance. Before comparing runs, independently verify the manifest hash and scenario set, runner artifact/revision, route mode and timeouts, DB and OSRM data-plane identities, cache policy, host/resource conditions, and every intentionally varied field. If more than the declared experimental variable differs, report the runs separately rather than attributing the delta to the route engine.

The hash-matched historical controls are technical route-return and processing-latency evidence only:

| Commit | Runtime | Technical completion | Job p50 | Job p95 |
| --- | --- | ---: | ---: | ---: |
| `c54e7ae` | `hybrid_osrm_v2` / Legacy | 14/27 | 1132 ms | 2389 ms |
| `c54e7ae` | PURE Design B anchor | 2/27 | 1991 ms | 4760 ms |
| `c54e7ae` | PURE Design B road-chain | 0/27 | n/a | n/a |
| `0fda4ca` | `hybrid_osrm_v2` / Legacy | 14/27 | 1242 ms | 2834 ms |
| `0fda4ca` | PURE Design B anchor | 4/27 | 2585 ms | 3426 ms |

These matched controls reject PURE Design B for production on availability/latency evidence and support `hybrid_osrm_v2` / Legacy as the better production foundation. They do not prove Legacy is a final or superior architecture, functionally complete, or a clean usable-quality pass. Successful Legacy controls still contain repeated-corridor, backtracking, urban-pressure, edge-pressure, and low-spread failures that require quality hardening.

The measured scenic-cache results in the evidence sidecar used real authenticated Redis 7.0-alpine and 1,500 deterministic tiles: serial `GET` 3213 ms, one `MGET` 90 ms, cold SQL plus pipelined Redis fill 685 ms, Redis-warm service lookup 140 ms, and local-warm lookup 2 ms. Preserve these as cache microbenchmark/integration evidence only; do not use them as route or first-result p50/p95.

### Browser-visible production baseline and matched follow-up

The pre-deploy browser baseline is:

```text
artifacts/latency-release/baseline-cohort-browser-20260719.json
```

It contains 25 of 25 completed routes across five payloads and five repetitions. All observations are payload-matched and attributed to the currently submitted job. Browser click-to-visible latency is p50 `10,167 ms`, p90 `13,873 ms`, and p95 `14,849 ms`.

Its source provenance is intentionally limited: `runtimeCommitVerified` is `false`. Commit `305773a8dcece7542ccca9de6500bb6fd472e559` is inferred from the latest successful deploy workflow run, not verified from the running containers, and the live image digest and revision label were not observed. Do not turn that inference into a runtime-verified attribution.

After deployment, repeat the same 25 payload/repetition observations and attribution checks. Because the new stack serves all production traffic after the single-instance cutover, call that evidence the **guarded full-rollout cohort**. It is not a traffic-isolated canary or percentage sample. Do not make causal before/after latency claims until that matched post-deploy cohort exists and its runtime image digests, revision labels, payloads, cache conditions, and measurement method are verified.

### Production supplemental checks

After the immutable local gate is accepted, deploy the exact gated images through the maintenance cutover, verify their live digests and revision labels, and complete the guarded full-rollout cohort above. Then run:

```powershell
./scripts/deploy/run_release_qa_baseline.ps1 `
  -BaseUrl "https://usewayward.app" `
  -TimeBudgetMinutes 90 `
  -OutputDir "artifacts/release-qa"
```

The baseline is a production environment check with its own scenario set and JSON/Markdown outputs; it does not replace or become comparable to the frozen local evaluation.

For a supplemental production run over the frozen manifest, re-verify the manifest hash, use a separate output directory, and record production image plus DB/OSRM identities:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\monitoring\run-route-quality-eval.ps1 `
  -BaseUrl "https://usewayward.app" `
  -ScenarioFile $scenarioFile `
  -RouteMode "drive" `
  -OutputDir "artifacts/route-quality-eval/production-supplemental"
```

A bounded `-MaxScenarios 2` run is only route-generation verification when it reaches `COMPLETED` with non-empty geometries and corresponding worker/OSRM evidence. It is never a full matched quality evaluation.

## Verification Commands

These focused commands supplement, but do not replace, the release gate above.

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

Cache-policy parity verifies the shared `scenicTiles`, `roadSegments`, and `regionalPopularity` data-plane contracts plus both intentionally distinct 24-hour route contracts: API `routeDetailsV2` rich details and worker `routeResults`. It is a configuration/contract check, not a cache microbenchmark or route-latency test.

Production endpoint/wiring smoke:

```powershell
Invoke-WebRequest -UseBasicParsing "https://usewayward.app" -TimeoutSec 15
Invoke-WebRequest -UseBasicParsing "https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1" -TimeoutSec 15
Invoke-WebRequest -UseBasicParsing "https://usewayward.app/api/analytics/summary?days=30" -TimeoutSec 15
```

These HTTP requests verify endpoint reachability and selected data wiring only. They do not submit a route job and therefore are not route-generation or quality verification.

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
