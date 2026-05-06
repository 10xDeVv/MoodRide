# MoodRide Implementation Plan (Living)

Last reconciled: 2026-05-01

## 1) Purpose
This is the active execution plan. It replaces the older audit-heavy plan text and tracks what is done, what is in flight, and what is next.

## 2) Current Baseline

### 2.1 Completed foundations
- Core microservices exist and build:
  - `route-api`, `route-worker`, `notification-service`
  - optional: `ingestion-service`, `scenic-scoring-service`, `cdc-service`
- PostGIS schema + migrations are in place (V1-V17 chain in route-api).
- Async route job pipeline works end-to-end (`route-jobs` -> worker -> `route-completions`).
- Frontend integration works against live API and websocket notifications.
- Production deployment on GCP VM is live for `app.moodrides.com`.
- CI/CD workflows added for:
  - image-based app deploy
  - versioned data release deploy
- Operating model decisions locked:
  - `ingestion-service`, `scenic-scoring-service`, and `cdc-service` are batch/offline tools for now
  - Kubernetes is future/archival (not near-term)
  - data updates remain release-driven/manual now, with planned near-term move to scheduled recompute

### 2.2 Current runtime in production
- postgres, kafka, zookeeper, redis, osrm
- route-api, route-worker, notification-service, frontend, caddy

### 2.3 Current data scope
- OSRM runtime dataset currently limited to New Brunswick baseline (as deployed).
- Canada-wide source data is available locally and planned for rollout via versioned data pipeline.

## 3) Priority Plan (Now)

### P0: Nationwide data rollout (Canada)
Goal: move from provincial scope to nationwide routing while preserving production stability.

Steps:
1. Complete nationwide OSRM preprocessing on local machine.
2. Publish release artifact with `publish_data_release.ps1`.
3. Deploy artifact through `deploy-data-release` workflow.
4. Validate route generation and memory/disk headroom on VM.

Acceptance criteria:
- `OSRM_DATASET_BASENAME` switched to national dataset in prod.
- `/api/scenic-regions` and route generation endpoints remain healthy.
- no sustained OOM/restart loops in worker/osrm.

### P0: Data-quality upgrade completion
Goal: land-cover + DEM + solitude refinements become production-default scoring inputs.

Steps:
1. Finish long-running DEM enrichment pipeline locally.
2. Complete tile recompute / validation queries.
3. Publish refreshed scenic tile data release package.
4. Deploy with rollback-ready checkpoint.

Acceptance criteria:
- component score distributions show meaningful variance.
- slider behavior differences are observable and consistent.
- route-option differentiation remains stable after refresh.

### P1: Deployment hardening
Goal: reduce manual steps and release risk.

Steps:
1. Add protected `production` environment rules in GitHub.
2. Add post-deploy smoke checks in workflow run summary.
3. Document operational rollback drills.

Acceptance criteria:
- deploy path is GitHub-run driven (not ad hoc SSH edits).
- rollback to previous `IMAGE_TAG` tested and documented.

## 4) Secondary Plan (Next)

### P1: API contract cleanup
Potential cleanup:
- keep `/api/*` as canonical public API
- retain `/routes/*` aliases temporarily for backward compatibility
- deprecate/remove aliases in a planned cleanup once clients are confirmed

Outcome required:
- one canonical public route contract for frontend and external clients.
- execution checklist and dates documented in `docs/ApiAliasDeprecationPlan.md`.

### P1: Transition to scheduled recompute jobs
Current state:
- release-driven manual data updates

Near-term target:
- scheduled recompute pipeline for regular data refreshes and scoring updates

Outcome required:
- scheduled jobs with failure alerting, versioned outputs, and controlled deploy gates into production

### P2: Observability standardization
- establish baseline dashboards for API latency, worker throughput, queue depth, and route completion rate.
- normalize health checks and alerts.

## 5) Backlog (Post-Scale)
- move from ZooKeeper-based Kafka to KRaft or managed broker.
- evaluate separating data pipeline compute from app runtime permanently.
- formal SLO definition for async route completion windows.
- Kubernetes remains future/archival until explicitly reactivated.

## 6) Working Agreements for This Plan
- Any claim in this plan must be traceable to code or verified runtime evidence.
- Historical validation logs belong in `docs/verification/*`, not in this plan.
- This file should stay concise and execution-focused.

## 7) Open Question
1. What SLO do you want to enforce for async route completion (for example p95 under N seconds) once nationwide data is deployed?

## 8) Immediate Next Action
Begin Canada-wide OSRM release path with the existing pipeline:
1. finalize local preprocessing
2. publish tagged data release
3. deploy through `deploy-data-release` workflow
4. run smoke + resource checks

