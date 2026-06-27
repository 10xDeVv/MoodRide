# Wayward Implementation Plan (Living)

Last reconciled: 2026-06-08

## 1) Purpose
This is the active execution plan. It replaces the older audit-heavy plan text and tracks what is done, what is in flight, and what is next.

## 2) Current Baseline

### 2.1 Completed foundations
- Core microservices exist and build:
  - `route-api`, `route-worker`, `notification-service`
  - optional: `ingestion-service`, `scenic-scoring-service`, `cdc-service`
- PostGIS schema + migrations are in place (V1-V22 chain in route-api).
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
- OSRM runtime dataset is nationwide (`canada-latest`) in production.
- Scenic tile data has completed the 2.8 national land-cover/DEM calibration and 2.9 protected-area enrichment locally.
- 3.0 Overture/light-pollution enrichment is implemented in schema, scripts, shared scoring, and route-quality eval flows.
- This shell could not verify GitHub release publication or live DB deployment for `3.0-overture-lightpollution-enrichment` because `gh` was unauthenticated and Docker/Postgres were unavailable.
- Versioned release/deploy workflows exist for both OSRM and scenic tiles.

## 3) Priority Plan (Now)

### P0: Deployment hardening
Goal: reduce manual steps and release risk.

Steps:
1. Add protected `production` environment rules in GitHub.
2. Add post-deploy smoke checks in workflow run summary.
3. Keep CORS/runtime env parity enforced during deploy (`MOODRIDE_CORS_ALLOWED_ORIGINS` and frontend API/WS base URLs).
4. Document operational rollback drills.

Acceptance criteria:
- deploy path is GitHub-run driven (not ad hoc SSH edits).
- rollback to previous `IMAGE_TAG` tested and documented.
- API smoke checks and route submission checks pass after each deploy.

### P0: Release QA baseline gate
Goal: treat release validation as a standard gate, not ad hoc manual checks.

Steps:
1. Run `scripts/deploy/run_release_qa_baseline.ps1` after app/data deploy.
2. Store JSON/Markdown artifacts per run.
3. Track regressions (status failures, option count, score spread drift) across releases.

Acceptance criteria:
- all scenarios complete (`COMPLETED`)
- route options present for each scenario
- no unexplained regional regressions between releases

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
- 3.0 import/recompute scripts are present, but publication/deployment verification needs authenticated release or DB access

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
1. Verify `3.0-overture-lightpollution-enrichment` publication/deployment with authenticated `gh release list` and a production `scenic_score_tiles` scoring-version count.
2. Run/record release QA baseline after each production release.
3. Add deploy smoke checks directly into workflow summaries.
4. Plan first scheduled recompute cadence (monthly OSM + scenic release train).

