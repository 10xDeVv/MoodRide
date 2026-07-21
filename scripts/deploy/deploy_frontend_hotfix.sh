#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export LC_ALL=C

usage() {
  cat <<'EOF'
Usage:
  deploy_frontend_hotfix.sh \
    --manifest <frontend-hotfix-manifest.json> \
    --manifest-checksum <frontend-hotfix-manifest.sha256> \
    --script-sha256 <64-lowercase-hex> \
    --evidence <deployment-evidence.json> \
    --diagnostics <deployment-diagnostics.log>

Environment variables:
  MOODRIDE_DIR                 (default: /opt/moodride)
  COMPOSE_FILE                 (default: docker-compose.prod.yml)
  ENV_FILE                     (default: .env.prod)
  PUBLIC_HEALTH_URL            (default: https://usewayward.app/)
  HEALTHCHECK_TIMEOUT_SECONDS  (default: 120)
  HEALTHCHECK_INTERVAL_SECONDS (default: 2)
EOF
}

fail() {
  FAILURE_REASON="$*"
  printf '%s\n' "$*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

require_file() {
  [ -f "$1" ] || fail "Required file not found: $1"
}

require_absolute_path() {
  case "$2" in
    /*) ;;
    *) fail "$1 must be an absolute path." ;;
  esac
}

require_nonnegative_integer() {
  case "$2" in
    ''|*[!0-9]*) fail "$1 must be a non-negative integer." ;;
  esac
}

require_positive_integer() {
  case "$2" in
    ''|*[!0-9]*|0) fail "$1 must be a positive integer." ;;
  esac
}

utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

durable_replace() {
  local temporary="$1"
  local destination="$2"
  chmod 600 "$temporary" || return 1
  sync -f "$temporary" || return 1
  mv -f "$temporary" "$destination" || return 1
  sync -f "$destination" || return 1
  sync -f "$(dirname "$destination")" || return 1
}

durable_copy() {
  local source="$1"
  local destination="$2"
  local temporary="${destination}.$$.$RANDOM.tmp"
  rm -f -- "$temporary" || return 1
  cp -p -- "$source" "$temporary" || return 1
  durable_replace "$temporary" "$destination" || return 1
}

MANIFEST_PATH=""
MANIFEST_CHECKSUM_PATH=""
EXPECTED_SCRIPT_SHA256=""
EVIDENCE_PATH=""
DIAGNOSTICS_PATH=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest)
      MANIFEST_PATH="${2:-}"
      shift 2
      ;;
    --manifest-checksum)
      MANIFEST_CHECKSUM_PATH="${2:-}"
      shift 2
      ;;
    --script-sha256)
      EXPECTED_SCRIPT_SHA256="${2:-}"
      shift 2
      ;;
    --evidence)
      EVIDENCE_PATH="${2:-}"
      shift 2
      ;;
    --diagnostics)
      DIAGNOSTICS_PATH="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      usage >&2
      exit 2
      ;;
  esac
done

[ -n "$MANIFEST_PATH" ] || { usage >&2; fail "--manifest is required."; exit 2; }
[ -n "$MANIFEST_CHECKSUM_PATH" ] || { usage >&2; fail "--manifest-checksum is required."; exit 2; }
[ -n "$EXPECTED_SCRIPT_SHA256" ] || { usage >&2; fail "--script-sha256 is required."; exit 2; }
[ -n "$EVIDENCE_PATH" ] || { usage >&2; fail "--evidence is required."; exit 2; }
[ -n "$DIAGNOSTICS_PATH" ] || { usage >&2; fail "--diagnostics is required."; exit 2; }
require_absolute_path manifest "$MANIFEST_PATH"
require_absolute_path manifest-checksum "$MANIFEST_CHECKSUM_PATH"
require_absolute_path evidence "$EVIDENCE_PATH"
require_absolute_path diagnostics "$DIAGNOSTICS_PATH"

for prerequisite in basename chmod cmp cp curl date dirname docker flock grep install mktemp mv python3 rm sha256sum sleep sort sync tee touch; do
  require_command "$prerequisite"
done

install -d -m 700 "$(dirname "$EVIDENCE_PATH")" "$(dirname "$DIAGNOSTICS_PATH")"
touch "$DIAGNOSTICS_PATH"
chmod 600 "$DIAGNOSTICS_PATH"
exec > >(tee -a "$DIAGNOSTICS_PATH") 2>&1

STARTED_AT="$(utc_now)"
FINISHED_AT=""
FAILURE_REASON=""
PREFLIGHT_HEALTH="not-run"
PUBLIC_HEALTH="not-run"
ROLLBACK_PUBLIC_HEALTH="not-run"
NON_FRONTEND_FENCE="not-run"
ROLLBACK_STATE="not-required"
MUTATION_STARTED=0
POINTER_CHANGED=0
PREFLIGHT_CONTAINER=""
BEFORE_FRONTEND_ID=""
BEFORE_FRONTEND_REF=""
BEFORE_FRONTEND_REVISION=""
BEFORE_FRONTEND_SOURCE=""
AFTER_FRONTEND_ID=""
AFTER_FRONTEND_REF=""
AFTER_FRONTEND_REVISION=""
AFTER_FRONTEND_SOURCE=""
ROLLBACK_FRONTEND_ID=""
CANDIDATE_OVERRIDE=""
ROLLBACK_OVERRIDE=""
RELEASE_DIR=""
STATE_DIR=""
BEFORE_IDS_FILE=""
AFTER_IDS_FILE=""
ROLLBACK_IDS_FILE=""
POINTER_PATH=""
POINTER_BACKUP=""
POINTER_PREVIOUS_PRESENT=0
MANIFEST_SHA256=""
ACTUAL_SCRIPT_SHA256=""
CONTROL_WORKFLOW_SHA=""
CONTROL_WORKFLOW_SOURCE_URL=""
REPOSITORY_SOURCE=""
EXPECTED_CURRENT_SOURCE_SHA=""
CANDIDATE_SOURCE_SHA=""
DIFF_DIGEST=""
IMAGE_TAG=""
CANDIDATE_IMAGE_REF=""
CANDIDATE_INDEX_DIGEST=""
IMAGE_REPOSITORY=""
RUN_ID=""
RUN_ATTEMPT=""
MANIFEST_SCRIPT_SHA256=""
CUTOVER_LOCK_HELD=0

on_error() {
  local status="$1"
  local line="$2"
  if [ "$status" -ne 0 ] && [ -z "$FAILURE_REASON" ]; then
    FAILURE_REASON="Command failed at deploy_frontend_hotfix.sh line ${line}."
  fi
  return "$status"
}
trap 'on_error "$?" "$LINENO"' ERR

compose_base() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose_override() {
  local override="$1"
  shift
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$override" "$@"
}

resolve_repo_digest() {
  local image="$1"
  local repository="$2"
  local candidate=""
  local resolved=""
  local digest=""
  local prefix="${repository}@sha256:"
  while IFS= read -r candidate; do
    case "$candidate" in
      "${repository}@sha256:"*)
        if [ -n "$resolved" ]; then
          fail "Image $image has multiple RepoDigests for $repository."
          return 1
        fi
        resolved="$candidate"
        ;;
    esac
  done < <(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image")
  digest="${resolved#"$prefix"}"
  if [ "$resolved" != "${prefix}${digest}" ] || [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    fail "Image $image has no unique valid RepoDigest for $repository."
    return 1
  fi
  printf '%s\n' "$resolved"
}

image_label() {
  local image="$1"
  local label="$2"
  docker image inspect --format "{{ index .Config.Labels \"${label}\" }}" "$image"
}

unique_compose_container() {
  local service="$1"
  local ids=()
  mapfile -t ids < <(compose_base ps -q "$service")
  if [ "${#ids[@]}" -ne 1 ] || [ -z "${ids[0]}" ]; then
    fail "Production Compose service $service does not have exactly one running container."
    return 1
  fi
  if [ "$(docker inspect --format '{{.State.Running}}' "${ids[0]}")" != "true" ]; then
    fail "Production Compose service $service is not running."
    return 1
  fi
  printf '%s\n' "${ids[0]}"
}

verify_unique_frontend_membership() {
  local expected_id="$1"
  local project
  local ids=()
  project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$expected_id")"
  if [ -z "$project" ] || [ "$project" = "<no value>" ]; then
    fail "Running frontend lacks a Compose project label."
    return 1
  fi
  mapfile -t ids < <(docker ps --no-trunc --quiet \
    --filter "label=com.docker.compose.project=${project}" \
    --filter "label=com.docker.compose.service=frontend")
  if [ "${#ids[@]}" -ne 1 ] || [ "${ids[0]}" != "$expected_id" ]; then
    fail "Production Compose project does not have one unique running frontend container."
    return 1
  fi
}

verify_frontend_identity() {
  local expected_ref="$1"
  local expected_revision="$2"
  local expected_source="$3"
  local container_id image_id actual_ref revision source
  container_id="$(unique_compose_container frontend)" || return 1
  verify_unique_frontend_membership "$container_id" || return 1
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  actual_ref="$(resolve_repo_digest "$image_id" "$IMAGE_REPOSITORY")" || return 1
  revision="$(image_label "$image_id" org.opencontainers.image.revision)"
  source="$(image_label "$image_id" org.opencontainers.image.source)"
  [ "$actual_ref" = "$expected_ref" ] || {
    fail "Running frontend RepoDigest does not match the required exact digest."
    return 1
  }
  [ "$revision" = "$expected_revision" ] || {
    fail "Running frontend OCI revision does not match the required source SHA."
    return 1
  }
  [ "$source" = "$expected_source" ] || {
    fail "Running frontend OCI source does not match the production repository source."
    return 1
  }
  printf '%s\t%s\t%s\t%s\n' "$container_id" "$actual_ref" "$revision" "$source"
}

capture_non_frontend_ids() {
  local output="$1"
  local temporary="${output}.tmp"
  local services=()
  local service id
  : > "$temporary"
  mapfile -t services < <(compose_base config --services)
  [ "${#services[@]}" -gt 1 ] || {
    fail "Production Compose did not expose the expected service set."
    rm -f -- "$temporary"
    return 1
  }
  for service in "${services[@]}"; do
    [[ "$service" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || {
      fail "Compose returned an invalid service name."
      rm -f -- "$temporary"
      return 1
    }
    [ "$service" = frontend ] && continue
    id="$(unique_compose_container "$service")" || {
      rm -f -- "$temporary"
      return 1
    }
    printf '%s\t%s\n' "$service" "$id" >> "$temporary"
  done
  sort -o "$temporary" "$temporary"
  mv -f "$temporary" "$output"
}

check_non_frontend_fence() {
  local output="$1"
  capture_non_frontend_ids "$output" || return 1
  if ! cmp -s "$BEFORE_IDS_FILE" "$output"; then
    NON_FRONTEND_FENCE="failed"
    fail "A non-frontend production container ID changed during the frontend-only cutover."
    return 1
  fi
  NON_FRONTEND_FENCE="passed"
}

http_wayward_probe() {
  local label="$1"
  local url="$2"
  local output="$3"
  local start now code
  start="$(date +%s)"
  while true; do
    code="$(curl --silent --show-error \
      --connect-timeout "$HEALTHCHECK_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$HEALTHCHECK_REQUEST_TIMEOUT_SECONDS" \
      --output "$output" --write-out '%{http_code}' "$url" || true)"
    if [ "$code" = 200 ] && grep -Fq Wayward "$output"; then
      printf '%s passed with HTTP 200 and Wayward page content.\n' "$label"
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start)) -ge "$HEALTHCHECK_TIMEOUT_SECONDS" ]; then
      fail "$label failed: expected HTTP 200 with Wayward page content (last HTTP ${code:-unavailable})."
      return 1
    fi
    sleep "$HEALTHCHECK_INTERVAL_SECONDS"
  done
}

create_override() {
  local image_ref="$1"
  local purpose="$2"
  local temporary checksum destination
  temporary="$(mktemp "$OVERRIDE_DIR/.${purpose}.XXXXXX")" || return 1
  printf 'services:\n  frontend:\n    image: %s\n' "$image_ref" > "$temporary" || {
    rm -f -- "$temporary" || true
    return 1
  }
  checksum="$(sha256sum "$temporary")" || {
    rm -f -- "$temporary" || true
    return 1
  }
  checksum="${checksum%% *}"
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || {
    rm -f -- "$temporary" || true
    fail "Could not checksum the frontend-only Compose override."
    return 1
  }
  destination="$OVERRIDE_DIR/frontend-${checksum}.compose.yml"
  if [ -e "$destination" ]; then
    if ! cmp -s "$temporary" "$destination"; then
      rm -f -- "$temporary" || true
      fail "Checksum-addressed frontend override has conflicting bytes."
      return 1
    fi
    rm -f -- "$temporary" || return 1
  else
    durable_replace "$temporary" "$destination" || return 1
  fi
  printf '%s\n' "$destination" || return 1
}

write_pointer() {
  local status="$1"
  local temporary="${POINTER_PATH}.$$.$RANDOM.tmp"
  python3 - "$temporary" "$status" "$MANIFEST_SHA256" "$CANDIDATE_IMAGE_REF" \
    "$CANDIDATE_OVERRIDE" "$RELEASE_DIR/deployment-evidence.json" <<'PY' || { rm -f -- "$temporary" || true; return 1; }
import json
import pathlib
import sys

path, status, manifest_sha, image_ref, override_file, evidence_file = sys.argv[1:]
data = {
    "schema_version": "moodride.frontend-hotfix-pointer/v1",
    "status": status,
    "manifest_sha256": "sha256:" + manifest_sha,
    "image_ref": image_ref,
    "override_file": override_file,
    "evidence_file": evidence_file,
}
pathlib.Path(path).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
  durable_replace "$temporary" "$POINTER_PATH" || return 1
}

restore_pointer() {
  if [ "$POINTER_CHANGED" -ne 1 ]; then
    return 0
  fi
  rm -f -- "$POINTER_PATH" || return 1
  sync -f "$(dirname "$POINTER_PATH")" || return 1
  if [ "$POINTER_PREVIOUS_PRESENT" -eq 1 ]; then
    durable_copy "$POINTER_BACKUP" "$POINTER_PATH" || return 1
  fi
  POINTER_CHANGED=0
}

write_evidence() {
  local exit_code="$1"
  local temporary="${EVIDENCE_PATH}.$$.$RANDOM.tmp"
  EVIDENCE_FAILURE_REASON="$FAILURE_REASON" python3 - \
    "$temporary" "$STARTED_AT" "$FINISHED_AT" "$exit_code" \
    "$MANIFEST_PATH" "$MANIFEST_SHA256" "$ACTUAL_SCRIPT_SHA256" \
    "$CONTROL_WORKFLOW_SHA" "$CONTROL_WORKFLOW_SOURCE_URL" \
    "$EXPECTED_CURRENT_SOURCE_SHA" "$CANDIDATE_SOURCE_SHA" "$DIFF_DIGEST" \
    "$IMAGE_TAG" "$CANDIDATE_IMAGE_REF" "$CANDIDATE_INDEX_DIGEST" "$IMAGE_REPOSITORY" "$REPOSITORY_SOURCE" \
    "$BEFORE_FRONTEND_ID" "$BEFORE_FRONTEND_REF" "$BEFORE_FRONTEND_REVISION" "$BEFORE_FRONTEND_SOURCE" \
    "$AFTER_FRONTEND_ID" "$AFTER_FRONTEND_REF" "$AFTER_FRONTEND_REVISION" "$AFTER_FRONTEND_SOURCE" \
    "$BEFORE_IDS_FILE" "$AFTER_IDS_FILE" "$ROLLBACK_IDS_FILE" \
    "$PREFLIGHT_HEALTH" "$PUBLIC_HEALTH" "$ROLLBACK_PUBLIC_HEALTH" "$NON_FRONTEND_FENCE" \
    "$ROLLBACK_STATE" "$ROLLBACK_FRONTEND_ID" "$CANDIDATE_OVERRIDE" "$ROLLBACK_OVERRIDE" \
    "$POINTER_PATH" "$RUN_ID" "$RUN_ATTEMPT" <<'PY' || { rm -f -- "$temporary" || true; return 1; }
import json
import os
import pathlib
import sys

(
    output, started, finished, exit_code,
    manifest_path, manifest_sha, script_sha, control_sha, control_url,
    expected_sha, candidate_sha, diff_digest,
    image_tag, image_ref, image_digest, repository, repository_source,
    before_id, before_ref, before_revision, before_source,
    after_id, after_ref, after_revision, after_source,
    before_ids_path, after_ids_path, rollback_ids_path,
    preflight_health, public_health, rollback_health, fence,
    rollback_state, rollback_frontend_id, candidate_override, rollback_override,
    pointer_path, run_id, run_attempt,
) = sys.argv[1:]

def ids(path):
    result = {}
    candidate = pathlib.Path(path) if path else None
    if candidate and candidate.is_file():
        for line in candidate.read_text(encoding="utf-8").splitlines():
            service, container_id = line.split("\t", 1)
            result[service] = container_id
    return result

data = {
    "schema_version": "moodride.frontend-hotfix-deployment-evidence/v1",
    "timestamps": {"started_at": started, "finished_at": finished},
    "run": {"id": run_id, "attempt": run_attempt},
    "trusted_control": {
        "workflow_sha": control_sha,
        "workflow_source_url": control_url,
        "deployment_script_sha256": "sha256:" + script_sha,
    },
    "manifest": {"path": manifest_path, "sha256": "sha256:" + manifest_sha},
    "source": {
        "expected_current_sha": expected_sha,
        "candidate_sha": candidate_sha,
        "diff_digest": diff_digest,
        "repository_source": repository_source,
    },
    "candidate_image": {
        "tag": image_tag,
        "ref": image_ref,
        "index_digest": image_digest,
        "repository": repository,
    },
    "before": {
        "frontend": {"container_id": before_id, "ref": before_ref, "revision": before_revision, "source": before_source},
        "non_frontend_container_ids": ids(before_ids_path),
    },
    "after": {
        "frontend": {"container_id": after_id, "ref": after_ref, "revision": after_revision, "source": after_source},
        "non_frontend_container_ids": ids(after_ids_path),
    },
    "health": {
        "candidate_preflight": preflight_health,
        "public_https": public_health,
        "rollback_public_https": rollback_health,
    },
    "cutover": {
        "candidate_override": candidate_override,
        "pointer": pointer_path,
        "non_frontend_id_fence": fence,
    },
    "rollback": {
        "state": rollback_state,
        "frontend_container_id": rollback_frontend_id,
        "image_ref": before_ref,
        "override": rollback_override,
        "non_frontend_container_ids": ids(rollback_ids_path),
    },
    "result": {
        "exit_code": int(exit_code),
        "accepted": int(exit_code) == 0,
        "failure_reason": os.environ.get("EVIDENCE_FAILURE_REASON", ""),
    },
}
pathlib.Path(output).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
  durable_replace "$temporary" "$EVIDENCE_PATH" || return 1
}

rollback_frontend() {
  local identity
  ROLLBACK_STATE="attempting"
  ROLLBACK_PUBLIC_HEALTH="not-run"
  if [ "$POINTER_CHANGED" -eq 1 ]; then
    rm -f -- "$POINTER_PATH" || {
      ROLLBACK_STATE="failed-pointer-removal"
      return 1
    }
    sync -f "$(dirname "$POINTER_PATH")" || {
      ROLLBACK_STATE="failed-pointer-sync"
      return 1
    }
  fi
  ROLLBACK_OVERRIDE="$(create_override "$BEFORE_FRONTEND_REF" rollback)" || {
    ROLLBACK_STATE="failed-to-create-override"
    return 1
  }
  if ! compose_override "$ROLLBACK_OVERRIDE" up -d --no-deps --force-recreate frontend; then
    ROLLBACK_STATE="failed-to-recreate-frontend"
    return 1
  fi
  if ! identity="$(verify_frontend_identity "$BEFORE_FRONTEND_REF" "$BEFORE_FRONTEND_REVISION" "$BEFORE_FRONTEND_SOURCE")"; then
    ROLLBACK_STATE="failed-image-identity"
    return 1
  fi
  IFS=$'\t' read -r ROLLBACK_FRONTEND_ID _ _ _ <<< "$identity"
  if ! http_wayward_probe "Rollback public HTTPS health" "$PUBLIC_HEALTH_URL" "$STATE_DIR/rollback-public-body"; then
    ROLLBACK_PUBLIC_HEALTH="failed"
    ROLLBACK_STATE="failed-public-health"
    return 1
  fi
  ROLLBACK_PUBLIC_HEALTH="passed:http-200-wayward"
  if ! check_non_frontend_fence "$ROLLBACK_IDS_FILE"; then
    ROLLBACK_STATE="failed-non-frontend-id-fence"
    return 1
  fi
  if ! restore_pointer; then
    ROLLBACK_STATE="failed-pointer-restore"
    return 1
  fi
  ROLLBACK_STATE="succeeded"
}

rollback_preserving_failure() {
  local cutover_failure="${FAILURE_REASON:-Frontend hotfix failed.}"
  local rollback_failure
  if rollback_frontend; then
    FAILURE_REASON="$cutover_failure"
    return 0
  fi
  rollback_failure="${FAILURE_REASON:-Automatic frontend rollback failed.}"
  FAILURE_REASON="${cutover_failure}; automatic frontend rollback failed (${ROLLBACK_STATE}): ${rollback_failure}"
  return 1
}

finish() {
  local status="$1"
  trap - ERR EXIT
  set +e
  if [ -n "$PREFLIGHT_CONTAINER" ]; then
    docker rm -f "$PREFLIGHT_CONTAINER" >/dev/null 2>&1 || true
    PREFLIGHT_CONTAINER=""
  fi
  if [ "$status" -ne 0 ] && [ "$MUTATION_STARTED" -eq 1 ]; then
    rollback_preserving_failure || true
  fi
  FINISHED_AT="$(utc_now)"
  if ! write_evidence "$status"; then
    if [ "$status" -eq 0 ]; then
      status=1
      FAILURE_REASON="Failed to persist deployment evidence after frontend mutation."
      if [ "$MUTATION_STARTED" -eq 1 ]; then
        rollback_preserving_failure || true
      fi
      FINISHED_AT="$(utc_now)"
      write_evidence "$status" || true
    fi
  fi
  if [ -n "$RELEASE_DIR" ] && [ -f "$EVIDENCE_PATH" ]; then
    if ! durable_copy "$EVIDENCE_PATH" "$RELEASE_DIR/deployment-evidence.json"; then
      if [ "$status" -eq 0 ]; then
        status=1
        FAILURE_REASON="Failed to persist release evidence after frontend mutation."
        if [ "$MUTATION_STARTED" -eq 1 ]; then
          rollback_preserving_failure || true
        fi
        FINISHED_AT="$(utc_now)"
        write_evidence "$status" || true
      fi
    fi
  fi
  if [ "$CUTOVER_LOCK_HELD" -eq 1 ]; then
    flock -u 9 >/dev/null 2>&1 || true
    exec 9>&-
    CUTOVER_LOCK_HELD=0
  fi
  [ -z "$STATE_DIR" ] || rm -rf -- "$STATE_DIR"
  exit "$status"
}
trap 'finish "$?"' EXIT

require_file "$MANIFEST_PATH"
require_file "$MANIFEST_CHECKSUM_PATH"
[[ "$EXPECTED_SCRIPT_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
  fail "--script-sha256 must be exactly 64 lowercase hexadecimal characters."
  exit 1
}
ACTUAL_SCRIPT_SHA256="$(sha256sum "${BASH_SOURCE[0]}")"
ACTUAL_SCRIPT_SHA256="${ACTUAL_SCRIPT_SHA256%% *}"
[ "$ACTUAL_SCRIPT_SHA256" = "$EXPECTED_SCRIPT_SHA256" ] || {
  fail "The executing deployment script does not match its trusted checksum."
  exit 1
}

read -r checksum_name checksum_file checksum_extra < "$MANIFEST_CHECKSUM_PATH" || {
  fail "Could not read the manifest checksum file."
  exit 1
}
[[ "$checksum_name" =~ ^[0-9a-f]{64}$ ]] || {
  fail "Manifest checksum is not exactly 64 lowercase hexadecimal characters."
  exit 1
}
[ "$checksum_file" = frontend-hotfix-manifest.json ] && [ -z "${checksum_extra:-}" ] || {
  fail "Manifest checksum file has an unexpected filename or extra fields."
  exit 1
}
MANIFEST_SHA256="$(sha256sum "$MANIFEST_PATH")"
MANIFEST_SHA256="${MANIFEST_SHA256%% *}"
[ "$MANIFEST_SHA256" = "$checksum_name" ] || {
  fail "Manifest checksum verification failed."
  exit 1
}

mapfile -t MANIFEST_FIELDS < <(python3 - "$MANIFEST_PATH" <<'PY'
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))

def value(*path):
    current = manifest
    for key in path:
        current = current[key]
    if not isinstance(current, str) or not current or "\n" in current or "\r" in current:
        raise SystemExit("Manifest string field is empty or malformed: " + ".".join(path))
    return current

if manifest.get("schema_version") != "moodride.frontend-hotfix-manifest/v1":
    raise SystemExit("Unsupported frontend hotfix manifest schema.")
changed_count = manifest["source"]["changed_path_count"]
if type(changed_count) is not int or changed_count < 1:
    raise SystemExit("Manifest changed_path_count must be a positive integer.")
fields = [
    value("control", "workflow_sha"),
    value("control", "workflow_source_url"),
    value("source", "repository_source"),
    value("source", "expected_current_sha"),
    value("source", "candidate_sha"),
    value("source", "diff_digest"),
    value("image", "tag"),
    value("image", "ref"),
    value("image", "index_digest"),
    value("image", "repository"),
    value("run", "id"),
    value("run", "attempt"),
    value("control", "deployment_script_sha256"),
]
print("\n".join(fields))
PY
)
[ "${#MANIFEST_FIELDS[@]}" -eq 13 ] || {
  fail "Frontend hotfix manifest is missing required fields."
  exit 1
}
CONTROL_WORKFLOW_SHA="${MANIFEST_FIELDS[0]}"
CONTROL_WORKFLOW_SOURCE_URL="${MANIFEST_FIELDS[1]}"
REPOSITORY_SOURCE="${MANIFEST_FIELDS[2]}"
EXPECTED_CURRENT_SOURCE_SHA="${MANIFEST_FIELDS[3]}"
CANDIDATE_SOURCE_SHA="${MANIFEST_FIELDS[4]}"
DIFF_DIGEST="${MANIFEST_FIELDS[5]}"
IMAGE_TAG="${MANIFEST_FIELDS[6]}"
CANDIDATE_IMAGE_REF="${MANIFEST_FIELDS[7]}"
CANDIDATE_INDEX_DIGEST="${MANIFEST_FIELDS[8]}"
IMAGE_REPOSITORY="${MANIFEST_FIELDS[9]}"
RUN_ID="${MANIFEST_FIELDS[10]}"
RUN_ATTEMPT="${MANIFEST_FIELDS[11]}"
MANIFEST_SCRIPT_SHA256="${MANIFEST_FIELDS[12]}"

for exact_sha in "$CONTROL_WORKFLOW_SHA" "$EXPECTED_CURRENT_SOURCE_SHA" "$CANDIDATE_SOURCE_SHA"; do
  [[ "$exact_sha" =~ ^[0-9a-f]{40}$ ]] || {
    fail "Manifest source identities must be exact 40-character lowercase SHAs."
    exit 1
  }
done
[ "$EXPECTED_CURRENT_SOURCE_SHA" != "$CANDIDATE_SOURCE_SHA" ] || { fail "Candidate source must differ from the currently deployed source."; exit 1; }
[[ "$DIFF_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { fail "Manifest diff digest is invalid."; exit 1; }
[[ "$CANDIDATE_INDEX_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { fail "Manifest image index digest is invalid."; exit 1; }
[[ "$IMAGE_REPOSITORY" =~ ^ghcr\.io/[a-z0-9]+([._-][a-z0-9]+)*/moodride-frontend$ ]] || { fail "Manifest frontend repository is invalid."; exit 1; }
candidate_ref_digest="${CANDIDATE_IMAGE_REF#"$IMAGE_REPOSITORY@sha256:"}"
[ "$CANDIDATE_IMAGE_REF" = "${IMAGE_REPOSITORY}@sha256:${candidate_ref_digest}" ] && [[ "$candidate_ref_digest" =~ ^[0-9a-f]{64}$ ]] || { fail "Manifest candidate image ref is invalid."; exit 1; }
[ "$CANDIDATE_IMAGE_REF" = "${IMAGE_REPOSITORY}@${CANDIDATE_INDEX_DIGEST}" ] || { fail "Manifest candidate ref and index digest disagree."; exit 1; }
[[ "$RUN_ID" =~ ^[1-9][0-9]*$ ]] && [[ "$RUN_ATTEMPT" =~ ^[1-9][0-9]*$ ]] || { fail "Manifest run identity is invalid."; exit 1; }
[ "$IMAGE_TAG" = "${IMAGE_REPOSITORY}:frontend-hotfix-${CANDIDATE_SOURCE_SHA}-${RUN_ID}-${RUN_ATTEMPT}" ] || { fail "Manifest image tag is not the unique hotfix tag."; exit 1; }
[[ "$REPOSITORY_SOURCE" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { fail "Manifest repository source URL is invalid."; exit 1; }
[ "$CONTROL_WORKFLOW_SOURCE_URL" = "${REPOSITORY_SOURCE}/blob/${CONTROL_WORKFLOW_SHA}/.github/workflows/deploy-frontend-hotfix.yml" ] || { fail "Manifest control workflow URL is not bound to its exact SHA and repository."; exit 1; }
[ "$MANIFEST_SCRIPT_SHA256" = "$ACTUAL_SCRIPT_SHA256" ] || { fail "Manifest deployment script checksum does not match the executing script."; exit 1; }

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}
PUBLIC_HEALTH_URL=${PUBLIC_HEALTH_URL:-https://usewayward.app/}
HEALTHCHECK_TIMEOUT_SECONDS=${HEALTHCHECK_TIMEOUT_SECONDS:-120}
HEALTHCHECK_INTERVAL_SECONDS=${HEALTHCHECK_INTERVAL_SECONDS:-2}
HEALTHCHECK_CONNECT_TIMEOUT_SECONDS=${HEALTHCHECK_CONNECT_TIMEOUT_SECONDS:-5}
HEALTHCHECK_REQUEST_TIMEOUT_SECONDS=${HEALTHCHECK_REQUEST_TIMEOUT_SECONDS:-10}
require_nonnegative_integer HEALTHCHECK_TIMEOUT_SECONDS "$HEALTHCHECK_TIMEOUT_SECONDS"
require_nonnegative_integer HEALTHCHECK_INTERVAL_SECONDS "$HEALTHCHECK_INTERVAL_SECONDS"
require_positive_integer HEALTHCHECK_CONNECT_TIMEOUT_SECONDS "$HEALTHCHECK_CONNECT_TIMEOUT_SECONDS"
require_positive_integer HEALTHCHECK_REQUEST_TIMEOUT_SECONDS "$HEALTHCHECK_REQUEST_TIMEOUT_SECONDS"
case "$PUBLIC_HEALTH_URL" in
  https://*) ;;
  *) fail "PUBLIC_HEALTH_URL must use HTTPS."; exit 1 ;;
esac
require_absolute_path MOODRIDE_DIR "$MOODRIDE_DIR"
cd "$MOODRIDE_DIR"
require_file "$COMPOSE_FILE"
require_file "$ENV_FILE"
docker compose version >/dev/null

install -d -m 700 .deploy .deploy/frontend-hotfixes .deploy/frontend-hotfixes/overrides .deploy/frontend-hotfixes/releases
exec 9>>"$MOODRIDE_DIR/.deploy/prod-cutover.lock"
if ! flock -n 9; then
  exec 9>&-
  fail "Another production cutover holds $MOODRIDE_DIR/.deploy/prod-cutover.lock."
  exit 1
fi
CUTOVER_LOCK_HELD=1

HOTFIX_ROOT="$MOODRIDE_DIR/.deploy/frontend-hotfixes"
OVERRIDE_DIR="$HOTFIX_ROOT/overrides"
RELEASE_DIR="$HOTFIX_ROOT/releases/$MANIFEST_SHA256"
POINTER_PATH="$HOTFIX_ROOT/current-frontend-hotfix"
install -d -m 700 "$RELEASE_DIR"
STATE_DIR="$(mktemp -d "$HOTFIX_ROOT/.state-${RUN_ID}-${RUN_ATTEMPT}.XXXXXX")"
BEFORE_IDS_FILE="$STATE_DIR/non-frontend.before.tsv"
AFTER_IDS_FILE="$STATE_DIR/non-frontend.after.tsv"
ROLLBACK_IDS_FILE="$STATE_DIR/non-frontend.rollback.tsv"
POINTER_BACKUP="$STATE_DIR/pointer.previous"
if [ -e "$POINTER_PATH" ] || [ -L "$POINTER_PATH" ]; then
  [ -f "$POINTER_PATH" ] && [ ! -L "$POINTER_PATH" ] || { fail "Current frontend hotfix pointer is not a regular file."; exit 1; }
  durable_copy "$POINTER_PATH" "$POINTER_BACKUP"
  POINTER_PREVIOUS_PRESENT=1
fi

durable_copy "$MANIFEST_PATH" "$RELEASE_DIR/frontend-hotfix-manifest.json"
durable_copy "$MANIFEST_CHECKSUM_PATH" "$RELEASE_DIR/frontend-hotfix-manifest.sha256"

current_frontend_id="$(unique_compose_container frontend)" || { fail "Could not identify the current production frontend."; exit 1; }
current_frontend_image_id="$(docker inspect --format '{{.Image}}' "$current_frontend_id")"
current_frontend_ref="$(resolve_repo_digest "$current_frontend_image_id" "$IMAGE_REPOSITORY")" || {
  fail "Current production frontend is not attributable to the manifest repository."
  exit 1
}
if ! before_identity="$(verify_frontend_identity "$current_frontend_ref" "$EXPECTED_CURRENT_SOURCE_SHA" "$REPOSITORY_SOURCE")"; then
  fail "Current production frontend does not match expected_current_source_sha and repository source."
  exit 1
fi
IFS=$'\t' read -r BEFORE_FRONTEND_ID BEFORE_FRONTEND_REF BEFORE_FRONTEND_REVISION BEFORE_FRONTEND_SOURCE <<< "$before_identity"
capture_non_frontend_ids "$BEFORE_IDS_FILE"

printf 'Pulling and verifying candidate frontend digest %s\n' "$CANDIDATE_IMAGE_REF"
docker pull "$CANDIDATE_IMAGE_REF" >/dev/null
candidate_resolved_ref="$(resolve_repo_digest "$CANDIDATE_IMAGE_REF" "$IMAGE_REPOSITORY")"
[ "$candidate_resolved_ref" = "$CANDIDATE_IMAGE_REF" ] || { fail "Pulled candidate does not resolve to the manifest digest."; exit 1; }
candidate_revision="$(image_label "$CANDIDATE_IMAGE_REF" org.opencontainers.image.revision)"
candidate_source="$(image_label "$CANDIDATE_IMAGE_REF" org.opencontainers.image.source)"
[ "$candidate_revision" = "$CANDIDATE_SOURCE_SHA" ] || { fail "Candidate frontend OCI revision does not match candidate_source_sha."; exit 1; }
[ "$candidate_source" = "$REPOSITORY_SOURCE" ] || { fail "Candidate frontend OCI source does not match the production repository."; exit 1; }

PREFLIGHT_CONTAINER="moodride-frontend-hotfix-preflight-${RUN_ID}-${RUN_ATTEMPT}"
if docker inspect "$PREFLIGHT_CONTAINER" >/dev/null 2>&1; then
  fail "Unique candidate preflight container name already exists."
  exit 1
fi
docker run --pull=never -d --rm --name "$PREFLIGHT_CONTAINER" -p 127.0.0.1::3000 "$CANDIDATE_IMAGE_REF" >/dev/null
preflight_binding="$(docker port "$PREFLIGHT_CONTAINER" 3000/tcp)"
[[ "$preflight_binding" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || { fail "Candidate preflight did not bind one ephemeral localhost port."; exit 1; }
if ! http_wayward_probe "Candidate preflight" "http://127.0.0.1:${BASH_REMATCH[1]}/" "$STATE_DIR/preflight-body"; then
  PREFLIGHT_HEALTH="failed"
  exit 1
fi
PREFLIGHT_HEALTH="passed:http-200-wayward"
docker rm -f "$PREFLIGHT_CONTAINER" >/dev/null
PREFLIGHT_CONTAINER=""

CANDIDATE_OVERRIDE="$(create_override "$CANDIDATE_IMAGE_REF" candidate)"
MUTATION_STARTED=1
printf 'Recreating only the production frontend from %s\n' "$CANDIDATE_IMAGE_REF"
compose_override "$CANDIDATE_OVERRIDE" up -d --no-deps --force-recreate frontend
POINTER_CHANGED=1
write_pointer pending-verification

after_identity="$(verify_frontend_identity "$CANDIDATE_IMAGE_REF" "$CANDIDATE_SOURCE_SHA" "$REPOSITORY_SOURCE")"
IFS=$'\t' read -r AFTER_FRONTEND_ID AFTER_FRONTEND_REF AFTER_FRONTEND_REVISION AFTER_FRONTEND_SOURCE <<< "$after_identity"
[ "$AFTER_FRONTEND_ID" != "$BEFORE_FRONTEND_ID" ] || { fail "Compose did not recreate the frontend container."; exit 1; }
if ! http_wayward_probe "Production public HTTPS health" "$PUBLIC_HEALTH_URL" "$STATE_DIR/public-body"; then
  PUBLIC_HEALTH="failed"
  exit 1
fi
PUBLIC_HEALTH="passed:http-200-wayward"
check_non_frontend_fence "$AFTER_IDS_FILE"
write_pointer accepted
printf 'Frontend hotfix accepted at %s; all non-frontend production container IDs are unchanged.\n' "$CANDIDATE_IMAGE_REF"
