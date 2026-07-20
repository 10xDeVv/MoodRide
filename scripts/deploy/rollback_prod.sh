#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage:
  rollback_prod.sh --expected-current-tag <sha-40-hex> \
    --expected-current-release-lock-sha256 <64-lowercase-hex>

The rollback target is never supplied by the caller. It is read from the exact
previous-image snapshot captured by the currently deployed release. The expected
current release tag and accepted-lock checksum fence against runtime drift.

Environment variables:
  MOODRIDE_DIR       (default: /opt/moodride)
  COMPOSE_FILE       (default: docker-compose.prod.yml)
  ENV_FILE           (default: .env.prod)
  BACKUP_DIR         (default: <MOODRIDE_DIR>/.deploy/db-backups; host path, not a Docker volume)
  ROLLBACK_SQL       (default: <MOODRIDE_DIR>/scripts/deploy/rollback_v41_v40_v39_to_v38.sql)
  DRAIN_TIMEOUT_SECONDS       (default: 1800)
  DRAIN_POLL_SECONDS          (default: 5)
  HEALTHCHECK_TIMEOUT_SECONDS (default: 360)
  SYNTHETIC_JOB_TIMEOUT_SECONDS (default: 600)
  API_HEALTHCHECK_URL         (default: https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1)
  FRONTEND_HEALTHCHECK_URL    (default: https://usewayward.app/)
  WS_HEALTHCHECK_URL          (default: https://usewayward.app/ws)
EOF
}

fail() {
  echo "$*" >&2
  return 1
}

require_file() {
  [ -f "$1" ] || fail "Required file not found: $1"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

durable_replace() {
  local temp="$1"
  local destination="$2"
  sync -f "$temp"
  mv -f "$temp" "$destination"
  sync -f "$destination"
  sync -f "$(dirname "$destination")"
}

durable_copy() {
  local source="$1"
  local destination="$2"
  local temp="${destination}.${MAIN_PID:-$$}.tmp"
  rm -f "$temp"
  cp -p "$source" "$temp"
  durable_replace "$temp" "$destination"
}

acquire_cutover_lock() {
  local lock_file="$MOODRIDE_DIR/.deploy/prod-cutover.lock"
  exec 9>>"$lock_file"
  if ! flock -n 9; then
    exec 9>&-
    fail "Another production cutover holds $lock_file."
    return 1
  fi
  CUTOVER_LOCK_HELD=1
}

release_cutover_lock() {
  if [ "${CUTOVER_LOCK_HELD:-0}" -eq 1 ]; then
    flock -u 9 >/dev/null 2>&1 || true
    exec 9>&-
    CUTOVER_LOCK_HELD=0
  fi
}

switch_control_bundle_pointer() {
  local bundle="$1"
  local pointer="$MOODRIDE_DIR/.deploy/current"
  local temp="${pointer}.${MAIN_PID:-$$}.tmp"
  verify_control_bundle "$bundle"
  rm -f "$temp"
  ln -s "$bundle" "$temp"
  mv -Tf "$temp" "$pointer"
  sync -f "$MOODRIDE_DIR/.deploy"
}

require_positive_integer() {
  case "$2" in
    ''|*[!0-9]*|0) fail "$1 must be a positive integer." ;;
  esac
}

set_env_var() {
  local key="$1"
  local value="$2"
  local file="$3"
  local temp="${file}.update.tmp"
  rm -f "$temp"
  cp -p "$file" "$temp"
  if grep -q "^${key}=" "$temp"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$temp"
  else
    printf '%s=%s\n' "$key" "$value" >> "$temp"
  fi
  chmod 600 "$temp"
  mv "$temp" "$file"
}

get_env_var() {
  local key="$1"
  local file="$2"
  grep -E "^${key}=" "$file" | tail -n 1 | cut -d'=' -f2- || true
}

ensure_analytics_hash_secret() {
  local file="$1"
  local secret
  secret="$(get_env_var MOODRIDE_ANALYTICS_HASH_SECRET "$file")"
  if [ -z "$secret" ]; then
    secret="$(openssl rand -hex 32 | tr -d '\r\n')"
    set_env_var MOODRIDE_ANALYTICS_HASH_SECRET "$secret" "$file"
  fi
  printf '%s' "$secret" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "MOODRIDE_ANALYTICS_HASH_SECRET must be exactly 64 lowercase hexadecimal characters."
  case "$secret" in
    replace-with-*|*placeholder*) fail "MOODRIDE_ANALYTICS_HASH_SECRET is still a template placeholder." ;;
  esac
  chmod 600 "$file"
  unset secret
}
validate_configured_running_tag() {
  local configured_tag="$1"
  local actual_tag="$2"
  case "$configured_tag" in
    "$actual_tag") return 0 ;;
    sha-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
      case "$actual_tag" in
        "${configured_tag}"*) return 0 ;;
      esac
      fail "Legacy configured IMAGE_TAG does not prefix the actual running source revision."
      ;;
    *) fail "Configured IMAGE_TAG does not equal the actual running source revision." ;;
  esac
}

reject_template_placeholder() {
  local key="$1"
  local value="$2"
  case "$value" in
    replace-with-*|*placeholder*)
      fail "Rollback environment value is still a template placeholder: $key"
      return 1
      ;;
  esac
}

validate_rollback_env() {
  local env_file="$1"
  local control_bundle="$2"
  local key value namespace
  for key in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD REDIS_PASSWORD \
      MOODRIDE_ANALYTICS_HASH_SECRET MOODRIDE_SCENIC_SCORING_VERSION \
      MOODRIDE_ROAD_DATASET_REVISION MOODRIDE_ROAD_DATASET_FINGERPRINT \
      MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA GHCR_NAMESPACE OSRM_IMAGE_REF \
      OSRM_DATASET_BASENAME OSRM_FILE_MANIFEST_SHA256 SPRING_PROFILES_ACTIVE \
      MOODRIDE_ALGORITHM_PROFILE MOODRIDE_ALGORITHM_MODE \
      MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED IMAGE_TAG ROUTE_API_IMAGE_REF \
      ROUTE_WORKER_IMAGE_REF NOTIFICATION_SERVICE_IMAGE_REF FRONTEND_IMAGE_REF \
      CADDY_IMAGE_REF CADDYFILE_PATH; do
    value="$(get_env_var "$key" "$env_file")"
    [ -n "$value" ] \
      || {
        fail "Required rollback environment value is missing: $key"
        return 1
      }
    reject_template_placeholder "$key" "$value" || return 1
  done
  printf '%s' "$(get_env_var IMAGE_TAG "$env_file")" | grep -Eq '^sha-[0-9a-f]{40}$' \
    || {
      fail "Rollback IMAGE_TAG must identify an exact source revision."
      return 1
    }
  printf '%s' "$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$env_file")" \
    | grep -Eq '^[0-9a-f]{64}$' \
    || {
      fail "Rollback road dataset fingerprint must be a sha256 value."
      return 1
    }
  printf '%s' "$(get_env_var OSRM_FILE_MANIFEST_SHA256 "$env_file")" \
    | grep -Eq '^[0-9a-f]{64}$' \
    || {
      fail "Rollback OSRM dataset manifest identity must be a sha256 value."
      return 1
    }
  namespace="$(get_env_var GHCR_NAMESPACE "$env_file")"
  printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    || {
      fail "Rollback GHCR namespace is invalid."
      return 1
    }
  validate_image_reference "$namespace" moodride-route-api \
    "$(get_env_var ROUTE_API_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-route-worker \
    "$(get_env_var ROUTE_WORKER_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-notification-service \
    "$(get_env_var NOTIFICATION_SERVICE_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-frontend \
    "$(get_env_var FRONTEND_IMAGE_REF "$env_file")" || return 1
  printf '%s' "$(get_env_var OSRM_IMAGE_REF "$env_file")" \
    | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$' \
    || {
      fail "Rollback OSRM image is not pinned to the exact repository digest."
      return 1
    }
  validate_caddy_image_reference "$(get_env_var CADDY_IMAGE_REF "$env_file")" \
    || return 1
  [ "$(get_env_var CADDYFILE_PATH "$env_file")" = "$control_bundle/Caddyfile" ] \
    || {
      fail "Rollback Caddyfile path does not select the verified control bundle."
      return 1
    }
  require_file "$control_bundle/Caddyfile"
}



running_container_for_service() {
  local service="$1"
  local ids container_id count candidate
  ids="$(docker ps -aq \
    --filter "label=com.docker.compose.service=${service}" \
    --filter "label=com.docker.compose.project.working_dir=${MOODRIDE_DIR}")"
  container_id=""
  count=0
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    container_id="$candidate"
    count=$((count + 1))
  done <<< "$ids"
  [ "$count" -eq 1 ] \
    || fail "Expected one production container for $service, found $count."
  printf '%s\n' "$container_id"
}
compose_env() {
  local env_file="$1"
  shift
  docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$env_file" "$@"
}
select_current_control_bundle() {
  COMPOSE_FILE="$CURRENT_CONTROL_BUNDLE/docker-compose.prod.yml"
  CADDYFILE_PATH="$CURRENT_CONTROL_BUNDLE/Caddyfile"
  export CADDYFILE_PATH
}

select_target_control_bundle() {
  COMPOSE_FILE="$TARGET_CONTROL_BUNDLE/docker-compose.prod.yml"
  CADDYFILE_PATH="$TARGET_CONTROL_BUNDLE/Caddyfile"
  export CADDYFILE_PATH
}

verify_control_bundle() {
  local requested_bundle="$1"
  local require_quality="${2:-0}"
  local bundles_root canonical_root canonical_bundle manifest manifest_sha
  local expected_manifest manifest_line entry canonical_entry
  local saw_compose=0
  local saw_caddy=0
  local saw_rollback_sql=0
  local saw_quality=0

  bundles_root="$MOODRIDE_DIR/.deploy/bundles"
  [ -d "$bundles_root" ] \
    || { fail "Immutable control-bundle root is missing: $bundles_root"; return 1; }
  [ -d "$requested_bundle" ] \
    || { fail "Control bundle is not a directory: $requested_bundle"; return 1; }
  [ ! -L "$requested_bundle" ] \
    || { fail "Control bundle must not be a symlink: $requested_bundle"; return 1; }
  canonical_root="$(cd -P -- "$bundles_root" && pwd)" \
    || { fail "Could not canonicalize immutable control-bundle root."; return 1; }
  canonical_bundle="$(cd -P -- "$requested_bundle" && pwd)" \
    || { fail "Could not canonicalize control bundle: $requested_bundle"; return 1; }
  case "$canonical_bundle" in
    "$canonical_root"/*) ;;
    *)
      fail "Control bundle resolves outside the immutable bundle root: $requested_bundle"
      return 1
      ;;
  esac
  [ "${canonical_bundle%/*}" = "$canonical_root" ] \
    || { fail "Control bundle must be a direct child of the immutable bundle root."; return 1; }
  [ "$requested_bundle" = "$canonical_bundle" ] \
    || { fail "Control bundle path must be canonical and contain no symlink components."; return 1; }

  manifest="$canonical_bundle/bundle.sha256"
  [ -f "$manifest" ] \
    || { fail "Control bundle manifest is missing: $manifest"; return 1; }
  [ ! -L "$manifest" ] \
    || { fail "Control bundle manifest must not be a symlink: $manifest"; return 1; }
  canonical_entry="$(readlink -f -- "$manifest")" \
    || { fail "Could not canonicalize control bundle manifest."; return 1; }
  [ "$canonical_entry" = "$manifest" ] \
    || { fail "Control bundle manifest path is not canonical."; return 1; }
  manifest_sha="$(sha256sum "$manifest")" \
    || { fail "Could not hash control bundle manifest."; return 1; }
  manifest_sha="${manifest_sha%% *}"
  expected_manifest="${canonical_bundle##*/}"
  expected_manifest="${expected_manifest##*-}"
  printf '%s' "$expected_manifest" | grep -Eq '^[0-9a-f]{64}$' \
    || { fail "Control bundle basename is not bound to a SHA-256 manifest digest."; return 1; }
  [ "$manifest_sha" = "$expected_manifest" ] \
    || { fail "Control bundle manifest digest does not match its checksum-versioned basename."; return 1; }

  while IFS= read -r manifest_line; do
    if [[ ! "$manifest_line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]([^[:space:]]+)$ ]]; then
      fail "Control bundle manifest contains a malformed entry."
      return 1
    fi
    entry="${BASH_REMATCH[1]}"
    case "$entry" in
      /*|.*|*/.*|*//*|*\\*)
        fail "Control bundle manifest contains an unsafe relative path: $entry"
        return 1
        ;;
    esac
    [ -f "$canonical_bundle/$entry" ] \
      || { fail "Control bundle manifest entry is not a regular file: $entry"; return 1; }
    [ ! -L "$canonical_bundle/$entry" ] \
      || { fail "Control bundle manifest entry must not be a symlink: $entry"; return 1; }
    canonical_entry="$(readlink -f -- "$canonical_bundle/$entry")" \
      || { fail "Could not canonicalize control bundle manifest entry: $entry"; return 1; }
    [ "$canonical_entry" = "$canonical_bundle/$entry" ] \
      || { fail "Control bundle manifest entry escapes through a symlink: $entry"; return 1; }
    case "$entry" in
      docker-compose.prod.yml) saw_compose=1 ;;
      Caddyfile) saw_caddy=1 ;;
      scripts/deploy/rollback_v41_v40_v39_to_v38.sql) saw_rollback_sql=1 ;;
      quality-acceptance.json) saw_quality=1 ;;
    esac
  done < "$manifest"

  [ "$saw_compose" -eq 1 ] \
    || { fail "Control bundle manifest omits docker-compose.prod.yml."; return 1; }
  [ "$saw_caddy" -eq 1 ] \
    || { fail "Control bundle manifest omits Caddyfile."; return 1; }
  [ "$saw_rollback_sql" -eq 1 ] \
    || { fail "Control bundle manifest omits the coordinated rollback SQL."; return 1; }
  if [ "$require_quality" -eq 1 ] && [ "$saw_quality" -ne 1 ]; then
    fail "Current control bundle manifest omits quality acceptance."
    return 1
  fi
  (cd "$canonical_bundle" && LC_ALL=C sha256sum --check --strict bundle.sha256) \
    || { fail "Control bundle checksum verification failed."; return 1; }

  VERIFIED_CONTROL_BUNDLE="$canonical_bundle"
  VERIFIED_BUNDLE_MANIFEST_SHA256="$manifest_sha"
}

validate_image_reference() {
  local namespace="$1"
  local repository="$2"
  local image_ref="$3"
  local digest
  case "$image_ref" in
    "ghcr.io/${namespace}/${repository}@sha256:"*) ;;
    *) fail "Rollback image reference must pin ghcr.io/${namespace}/${repository} by digest." ;;
  esac
  digest="${image_ref##*@sha256:}"
  printf '%s' "$digest" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Rollback image reference for $repository has an invalid sha256 digest."
}

resolve_repo_digest() {
  local image="$1"
  local repository_ref="$2"
  local repo_digest candidate
  repo_digest=""
  while IFS= read -r candidate; do
    case "$candidate" in
      "${repository_ref}@sha256:"*)
        [ -z "$repo_digest" ] || fail "Image $image has multiple RepoDigests for $repository_ref."
        repo_digest="$candidate"
        ;;
    esac
  done < <(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image")
  [ -n "$repo_digest" ] || fail "Image $image has no RepoDigest for $repository_ref."
  printf '%s' "${repo_digest##*@sha256:}" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Image $image returned an invalid RepoDigest."
  printf '%s\n' "$repo_digest"
}

image_ref_for_repository() {
  local env_file="$1"
  local repository="$2"
  case "$repository" in
    moodride-route-api) get_env_var ROUTE_API_IMAGE_REF "$env_file" ;;
    moodride-route-worker) get_env_var ROUTE_WORKER_IMAGE_REF "$env_file" ;;
    moodride-notification-service) get_env_var NOTIFICATION_SERVICE_IMAGE_REF "$env_file" ;;
    moodride-frontend) get_env_var FRONTEND_IMAGE_REF "$env_file" ;;
    *) fail "Unsupported rollback repository: $repository" ;;
  esac
}

set_image_ref_for_repository() {
  local repository="$1"
  local image_ref="$2"
  local env_file="$3"
  case "$repository" in
    moodride-route-api) set_env_var ROUTE_API_IMAGE_REF "$image_ref" "$env_file" ;;
    moodride-route-worker) set_env_var ROUTE_WORKER_IMAGE_REF "$image_ref" "$env_file" ;;
    moodride-notification-service) set_env_var NOTIFICATION_SERVICE_IMAGE_REF "$image_ref" "$env_file" ;;
    moodride-frontend) set_env_var FRONTEND_IMAGE_REF "$image_ref" "$env_file" ;;
    *) fail "Unsupported rollback repository: $repository" ;;
  esac
}
validate_caddy_image_reference() {
  local image_ref="$1"
  printf '%s' "$image_ref" | grep -Eq '^caddy@sha256:[0-9a-f]{64}$' \
    || { fail "CADDY_IMAGE_REF must pin the exact official Caddy repository digest."; return 1; }
}

verify_rendered_caddy_image() {
  local env_file="$1"
  local expected_ref
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  validate_caddy_image_reference "$expected_ref" || return 1
  compose_env "$env_file" config --format json \
    | jq -e --arg expected_ref "$expected_ref" \
      '.services.caddy.image == $expected_ref' >/dev/null \
    || { fail "Rendered rollback Compose selects a mutable or unexpected Caddy image."; return 1; }
}

verify_local_caddy_image() {
  local env_file="$1"
  local expected_ref image_id actual_ref
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  validate_caddy_image_reference "$expected_ref" || return 1
  image_id="$(docker image inspect --format '{{.Id}}' "$expected_ref")" \
    || { fail "Exact Caddy rollback image is unavailable locally."; return 1; }
  actual_ref="$(resolve_repo_digest "$image_id" caddy)"
  [ "$actual_ref" = "$expected_ref" ] \
    || { fail "Local Caddy RepoDigest does not equal the checksummed rollback image lock."; return 1; }
}

verify_local_osrm_image() {
  local env_file="$1"
  local expected_ref image_id actual_ref
  expected_ref="$(get_env_var OSRM_IMAGE_REF "$env_file")"
  printf '%s' "$expected_ref" \
    | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$' \
    || {
      fail "Rollback OSRM image reference is not an exact repository digest."
      return 1
    }
  image_id="$(docker image inspect --format '{{.Id}}' "$expected_ref")" \
    || {
      fail "Exact OSRM rollback image is unavailable locally."
      return 1
    }
  actual_ref="$(resolve_repo_digest "$image_id" ghcr.io/project-osrm/osrm-backend)"
  [ "$actual_ref" = "$expected_ref" ] \
    || {
      fail "Local OSRM RepoDigest does not equal the checksummed rollback image lock."
      return 1
    }
}

verify_running_osrm_identity() {
  local env_file="$1"
  local container_id image_id configured_ref actual_ref expected_ref basename
  local config_cmd args expected_config_cmd expected_args manifest_file manifest_hash
  expected_ref="$(get_env_var OSRM_IMAGE_REF "$env_file")"
  basename="$(get_env_var OSRM_DATASET_BASENAME "$env_file")"
  container_id="$(running_container_for_service osrm)"
  [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" = "true" ] \
    || {
      fail "Rollback OSRM container is not running."
      return 1
    }
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  configured_ref="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  actual_ref="$(resolve_repo_digest "$image_id" ghcr.io/project-osrm/osrm-backend)"
  [ "$configured_ref" = "$expected_ref" ] && [ "$actual_ref" = "$expected_ref" ] \
    || {
      fail "Running OSRM does not use the exact checksummed rollback image."
      return 1
    }
  config_cmd="$(docker inspect --format '{{json .Config.Cmd}}' "$container_id")"
  args="$(docker inspect --format '{{json .Args}}' "$container_id")"
  expected_config_cmd="$(jq -cn --arg path "/data/${basename}.osrm" \
    '["osrm-routed", "--algorithm", "mld", $path]')"
  expected_args="$(jq -cn --arg path "/data/${basename}.osrm" \
    '["--algorithm", "mld", $path]')"
  [ "$config_cmd" = "$expected_config_cmd" ] && [ "$args" = "$expected_args" ] \
    || {
      fail "Running OSRM command does not select the exact rollback MLD dataset."
      return 1
    }
  manifest_file="${env_file}.running-osrm-files.tmp"
  write_osrm_file_manifest "$MOODRIDE_DIR/data/osrm" "$basename" "$manifest_file"
  manifest_hash="$(sha256sum "$manifest_file")"
  manifest_hash="${manifest_hash%% *}"
  rm -f "$manifest_file"
  [ "$manifest_hash" = "$(get_env_var OSRM_FILE_MANIFEST_SHA256 "$env_file")" ] \
    || {
      fail "Running OSRM sidecar manifest differs from the checksummed rollback dataset."
      return 1
    }
}

verify_running_caddy_image() {
  local env_file="$1"
  local container_id image_id expected_ref configured_ref actual_ref
  local expected_caddyfile mount_source candidate count canonical_mount canonical_expected
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  validate_caddy_image_reference "$expected_ref" || return 1
  container_id="$(running_container_for_service caddy)"
  [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" = "true" ] \
    || { fail "Caddy is not running."; return 1; }
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  configured_ref="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  actual_ref="$(resolve_repo_digest "$image_id" caddy)"
  [ "$configured_ref" = "$expected_ref" ] \
    || { fail "Running Caddy configuration is not the checksummed digest reference."; return 1; }
  [ "$actual_ref" = "$expected_ref" ] \
    || { fail "Running Caddy RepoDigest does not equal the checksummed digest reference."; return 1; }

  expected_caddyfile="$(get_env_var CADDYFILE_PATH "$env_file")"
  [ "$expected_caddyfile" = "$CADDYFILE_PATH" ] \
    || { fail "Running Caddy environment is not bound to the selected control bundle."; return 1; }
  mount_source=""
  count=0
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    mount_source="$candidate"
    count=$((count + 1))
  done < <(docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/etc/caddy/Caddyfile"}}{{println .Source}}{{end}}{{end}}' \
    "$container_id")
  [ "$count" -eq 1 ] \
    || { fail "Expected one running Caddyfile mount, found $count."; return 1; }
  canonical_mount="$(readlink -f -- "$mount_source")" \
    || { fail "Could not canonicalize running Caddyfile mount."; return 1; }
  canonical_expected="$(readlink -f -- "$expected_caddyfile")" \
    || { fail "Could not canonicalize selected Caddyfile."; return 1; }
  [ "$canonical_mount" = "$canonical_expected" ] \
    || { fail "Running Caddyfile mount does not match the selected control bundle."; return 1; }
}

capture_running_image_refs() {
  local env_file="$1"
  local output_env="$2"
  local namespace service repository container_id image_id image_ref repository_ref
  local revision source common_revision common_source running configured_ref expected_ref actual_ref
  namespace="$(get_env_var GHCR_NAMESPACE "$env_file")"
  printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    || fail "Current GHCR_NAMESPACE is missing or invalid."
  common_revision=""
  common_source=""
  for service in route-api route-worker notification-service frontend; do
    repository="moodride-${service}"
    container_id="$(running_container_for_service "$service")"
    [ -n "$container_id" ] || fail "Cannot snapshot RepoDigest: service $service has no container."
    running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
    [ "$running" = "true" ] || fail "Cannot snapshot recovery state: service $service is not running."
    image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
    [ -n "$image_id" ] || fail "Cannot snapshot image ID for service $service."
    repository_ref="ghcr.io/${namespace}/${repository}"
    image_ref="$(resolve_repo_digest "$image_id" "$repository_ref")"
    validate_image_reference "$namespace" "$repository" "$image_ref"
    revision="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image_id")"
    printf '%s' "$revision" | grep -Eq '^[0-9a-f]{40}$' \
      || fail "Running image for $service lacks an exact 40-character OCI revision."
    source="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.source" }}' "$image_id")"
    [ "$source" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
      || fail "Running image for $service does not match the expected GitHub OCI source."
    if [ -z "$common_revision" ]; then
      common_revision="$revision"
      common_source="$source"
    else
      [ "$revision" = "$common_revision" ] \
        || fail "Running application images do not share one source revision."
      [ "$source" = "$common_source" ] \
        || fail "Running application images do not share one source repository."
    fi
    set_image_ref_for_repository "$repository" "$image_ref" "$output_env"
  done
  service="osrm"
  container_id="$(running_container_for_service "$service")"
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] || fail "Cannot snapshot recovery state: OSRM is not running."
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  image_ref="$(resolve_repo_digest "$image_id" "ghcr.io/project-osrm/osrm-backend")"
  printf '%s' "$image_ref" \
    | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$' \
    || fail "Running OSRM image is not attributable to its digest-pinned repository."
  set_env_var OSRM_IMAGE_REF "$image_ref" "$output_env"
  if [ -z "$(get_env_var OSRM_DATASET_BASENAME "$output_env")" ]; then
    osrm_basename=""
    while IFS= read -r command_arg; do
      case "$command_arg" in
        /data/*.osrm)
          command_arg="${command_arg##*/}"
          osrm_basename="${command_arg%.osrm}"
          ;;
      esac
    done < <(docker inspect --format '{{range .Config.Cmd}}{{println .}}{{end}}' "$container_id")
    printf '%s' "$osrm_basename" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
      || fail "Could not raw-inspect the running OSRM dataset basename."
    set_env_var OSRM_DATASET_BASENAME "$osrm_basename" "$output_env"
  fi
  service="caddy"
  container_id="$(running_container_for_service "$service")"
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] \
    || { fail "Cannot snapshot recovery state: Caddy is not running."; return 1; }
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  validate_caddy_image_reference "$expected_ref" || return 1
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  configured_ref="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  actual_ref="$(resolve_repo_digest "$image_id" caddy)"
  [ "$configured_ref" = "$expected_ref" ] \
    || { fail "Running Caddy configuration differs from the configured recovery digest."; return 1; }
  [ "$actual_ref" = "$expected_ref" ] \
    || { fail "Running Caddy RepoDigest differs from the configured recovery digest."; return 1; }
  set_env_var CADDY_IMAGE_REF "$actual_ref" "$output_env"
  set_env_var IMAGE_TAG "sha-${common_revision}" "$output_env"
  echo "Snapshotted exact current RepoDigests and source revision for recovery."
}


ensure_compose_introspection_identity() {
  local env_file="$1"
  [ -n "$(get_env_var MOODRIDE_SCENIC_SCORING_VERSION "$env_file")" ] \
    || set_env_var MOODRIDE_SCENIC_SCORING_VERSION "3.7-introspection-placeholder" "$env_file"
  [ -n "$(get_env_var MOODRIDE_ROAD_DATASET_REVISION "$env_file")" ] \
    || set_env_var MOODRIDE_ROAD_DATASET_REVISION "road-introspection-placeholder" "$env_file"
  [ -n "$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$env_file")" ] \
    || set_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT \
      "0000000000000000000000000000000000000000000000000000000000000000" "$env_file"
  [ -n "$(get_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "$env_file")" ] \
    || set_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "v1" "$env_file"
  [ -n "$(get_env_var SPRING_PROFILES_ACTIVE "$env_file")" ] \
    || set_env_var SPRING_PROFILES_ACTIVE "prod" "$env_file"
  [ -n "$(get_env_var MOODRIDE_ALGORITHM_PROFILE "$env_file")" ] \
    || set_env_var MOODRIDE_ALGORITHM_PROFILE "hybrid_osrm_v2" "$env_file"
  [ -n "$(get_env_var MOODRIDE_ALGORITHM_MODE "$env_file")" ] \
    || set_env_var MOODRIDE_ALGORITHM_MODE "drive" "$env_file"
  [ -n "$(get_env_var MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED "$env_file")" ] \
    || set_env_var MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED "false" "$env_file"
}

write_osrm_file_manifest() {
  local data_dir="$1"
  local basename="$2"
  local output_file="$3"
  local names_file="${output_file}.names.tmp"
  local manifest_tmp="${output_file}.tmp"
  local file name digest
  local -a files
  printf '%s' "$basename" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
    || fail "OSRM dataset basename contains unsafe characters."
  shopt -s nullglob
  files=("$data_dir"/"${basename}.osrm"*)
  shopt -u nullglob
  [ "${#files[@]}" -gt 0 ] || fail "Active OSRM dataset file set is empty."
  : > "$names_file"
  for file in "${files[@]}"; do
    [ -f "$file" ] || fail "Active OSRM dataset entry is not a regular file: $file"
    name="${file##*/}"
    printf '%s' "$name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
      || fail "OSRM sidecar name is not a normalized ASCII relative filename."
    printf '%s\n' "$name" >> "$names_file"
  done
  LC_ALL=C sort "$names_file" -o "$names_file"
  : > "$manifest_tmp"
  while IFS= read -r name; do
    digest="$(sha256sum "$data_dir/$name")"
    digest="${digest%% *}"
    printf '%s  %s\n' "$digest" "$name" >> "$manifest_tmp"
  done < "$names_file"
  rm -f "$names_file"
  mv "$manifest_tmp" "$output_file"
}

load_image_lock() {
  local lock_file="$1"
  local expected_tag="$2"
  local target_env="$3"
  local lock_tag namespace repository image_ref checksum_file checksum_result
  local osrm_ref osrm_basename osrm_manifest manifest_temp actual_manifest caddy_ref
  require_file "$lock_file"
  checksum_file="${lock_file}.sha256"
  require_file "$checksum_file"
  checksum_result="$(cd "$(dirname "$lock_file")" && sha256sum --check "$(basename "$checksum_file")")" \
    || fail "Rollback image lock checksum verification failed."
  [ -n "$checksum_result" ] || fail "Rollback image lock checksum result is empty."
  lock_tag="$(get_env_var IMAGE_TAG "$lock_file")"
  [ "$lock_tag" = "$expected_tag" ] \
    || fail "Rollback image lock tag does not match requested target."
  namespace="$(get_env_var GHCR_NAMESPACE "$lock_file")"
  printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    || fail "Rollback image lock namespace is invalid."
  set_env_var GHCR_NAMESPACE "$namespace" "$target_env"
  set_env_var IMAGE_TAG "$expected_tag" "$target_env"
  for repository in moodride-route-api moodride-route-worker moodride-notification-service moodride-frontend; do
    image_ref="$(image_ref_for_repository "$lock_file" "$repository")"
    validate_image_reference "$namespace" "$repository" "$image_ref"
    set_image_ref_for_repository "$repository" "$image_ref" "$target_env"
  done
  osrm_ref="$(get_env_var OSRM_IMAGE_REF "$lock_file")"
  printf '%s' "$osrm_ref" \
    | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$' \
    || fail "Rollback image lock OSRM reference is invalid."
  osrm_basename="$(get_env_var OSRM_DATASET_BASENAME "$lock_file")"
  printf '%s' "$osrm_basename" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
    || fail "Rollback image lock OSRM dataset basename is invalid."
  osrm_manifest="$(get_env_var OSRM_FILE_MANIFEST_SHA256 "$lock_file")"
  printf '%s' "$osrm_manifest" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Rollback image lock OSRM manifest identity is invalid."
  manifest_temp="${target_env}.osrm-files.tmp"
  write_osrm_file_manifest "$MOODRIDE_DIR/data/osrm" "$osrm_basename" "$manifest_temp"
  actual_manifest="$(sha256sum "$manifest_temp")"
  actual_manifest="${actual_manifest%% *}"
  rm -f "$manifest_temp"
  [ "$actual_manifest" = "$osrm_manifest" ] \
    || fail "Rollback target OSRM canonical sidecar manifest differs from the checksummed image lock."
  set_env_var OSRM_IMAGE_REF "$osrm_ref" "$target_env"
  set_env_var OSRM_DATASET_BASENAME "$osrm_basename" "$target_env"
  set_env_var OSRM_FILE_MANIFEST_SHA256 "$osrm_manifest" "$target_env"
  caddy_ref="$(get_env_var CADDY_IMAGE_REF "$lock_file")"
  validate_caddy_image_reference "$caddy_ref" \
    || { fail "Rollback image lock Caddy reference is invalid."; return 1; }
  set_env_var CADDY_IMAGE_REF "$caddy_ref" "$target_env"
}


verify_current_release_fence() {
  local env_file="$1"
  local expected_tag="$2"
  local expected_checksum="$3"
  local lock_file actual_checksum source_sha entry key repository image_ref locked_ref
  local quality_file osrm_ref osrm_basename expected_manifest manifest_file actual_manifest

  [ "$expected_tag" = "$(get_env_var IMAGE_TAG "$env_file")" ] \
    || fail "Current runtime source differs from the operator-confirmed rollback release."
  lock_file="$MOODRIDE_DIR/.deploy/releases/accepted-${expected_tag}-${expected_checksum}.json"
  require_file "$lock_file"
  actual_checksum="$(sha256sum "$lock_file")"
  actual_checksum="${actual_checksum%% *}"
  [ "$actual_checksum" = "$expected_checksum" ] \
    || fail "Persisted current release-lock bytes do not match the operator-confirmed checksum."
  [ "$(jq -er '.schema_version' "$lock_file")" = "2" ] \
    || fail "Persisted current release-lock schema is unsupported."
  [ "$(jq -er '.source_url' "$lock_file")" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
    || fail "Persisted current release-lock source URL differs from the expected repository."
  source_sha="${expected_tag#sha-}"
  [ "$(jq -er '.source_sha' "$lock_file")" = "$source_sha" ] \
    || fail "Persisted current release-lock source does not match the running release."
  [ "$(jq -er '.image_tag' "$lock_file")" = "$expected_tag" ] \
    || fail "Persisted current release-lock tag does not match the running release."
  for entry in \
    "route_api|moodride-route-api" \
    "route_worker|moodride-route-worker" \
    "notification_service|moodride-notification-service" \
    "frontend|moodride-frontend"; do
    IFS='|' read -r key repository <<< "$entry"
    image_ref="$(image_ref_for_repository "$env_file" "$repository")"
    locked_ref="$(jq -er --arg key "$key" '.images[$key].ref' "$lock_file")"
    [ "$image_ref" = "$locked_ref" ] \
      || fail "Running digest for $repository differs from the operator-confirmed current release-lock."
  done
  quality_file="$CURRENT_CONTROL_BUNDLE/quality-acceptance.json"
  require_file "$quality_file"
  [ "$(jq -er '.source_sha' "$quality_file")" = "$source_sha" ] \
    || fail "Current quality acceptance source does not match expected-current SHA."
  [ "$(jq -er '.release_lock_sha256' "$quality_file")" = "$expected_checksum" ] \
    || fail "Current quality acceptance is not bound to expected-current release-lock bytes."
  osrm_ref="$(jq -er '.artifacts.candidate.runtime_identity.osrm.image_ref' "$quality_file")"
  osrm_basename="$(
    jq -er '.artifacts.candidate.runtime_identity.osrm.dataset_basename' "$quality_file"
  )"
  expected_manifest="$(
    jq -er '.artifacts.candidate.runtime_identity.osrm.file_manifest_sha256' "$quality_file"
  )"
  [ "$(get_env_var OSRM_IMAGE_REF "$env_file")" = "$osrm_ref" ] \
    || fail "Running OSRM image differs from the expected-current quality fence."
  [ "$(get_env_var OSRM_DATASET_BASENAME "$env_file")" = "$osrm_basename" ] \
    || fail "Running OSRM dataset basename differs from the expected-current quality fence."
  manifest_file="${env_file}.current-osrm-files.tmp"
  write_osrm_file_manifest "$MOODRIDE_DIR/data/osrm" "$osrm_basename" "$manifest_file"
  actual_manifest="$(sha256sum "$manifest_file")"
  actual_manifest="${actual_manifest%% *}"
  rm -f "$manifest_file"
  [ "$actual_manifest" = "$expected_manifest" ] \
    || fail "Running OSRM canonical sidecar manifest differs from the expected-current quality fence."
  set_env_var OSRM_FILE_MANIFEST_SHA256 "$expected_manifest" "$env_file"
  verify_running_caddy_image "$env_file" || return 1
}

load_expected_github_source_url() {
  local expected_tag="$1"
  local expected_checksum="$2"
  local lock_file actual_checksum locked_source
  lock_file="$MOODRIDE_DIR/.deploy/releases/accepted-${expected_tag}-${expected_checksum}.json"
  require_file "$lock_file"
  actual_checksum="$(sha256sum "$lock_file")"
  actual_checksum="${actual_checksum%% *}"
  [ "$actual_checksum" = "$expected_checksum" ] \
    || fail "Current accepted release-lock checksum diverged before source attribution."
  locked_source="$(jq -er '.source_url' "$lock_file")"
  printf '%s' "$locked_source" \
    | grep -Eq '^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' \
    || fail "Current accepted release-lock contains an invalid source URL."
  if [ -n "${EXPECTED_GITHUB_SOURCE_URL:-}" ] \
      && [ "$EXPECTED_GITHUB_SOURCE_URL" != "$locked_source" ]; then
    fail "Configured rollback source URL differs from the checksummed current release-lock."
    return 1
  fi
  EXPECTED_GITHUB_SOURCE_URL="$locked_source"
}

verify_target_control_evidence() {
  local evidence_file="$1"
  local target_env="$2"
  local target_tag="$3"
  local repository key actual_ref locked_ref
  [ "$(jq -er '.schema_version' "$evidence_file")" = "2" ] \
    || fail "Rollback target control evidence schema is unsupported."
  [ "$(jq -er '.source_sha' "$evidence_file")" = "${target_tag#sha-}" ] \
    || fail "Rollback target control evidence source differs from the image lock."
  [ "$(jq -er '.source_url' "$evidence_file")" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
    || fail "Rollback target control evidence repository differs from the current release."
  [ "$(jq -er '.image_tag' "$evidence_file")" = "$target_tag" ] \
    || fail "Rollback target control evidence tag differs from the image lock."
  for repository in moodride-route-api moodride-route-worker \
      moodride-notification-service moodride-frontend; do
    case "$repository" in
      moodride-route-api) key=route_api ;;
      moodride-route-worker) key=route_worker ;;
      moodride-notification-service) key=notification_service ;;
      moodride-frontend) key=frontend ;;
    esac
    actual_ref="$(image_ref_for_repository "$target_env" "$repository")"
    locked_ref="$(jq -er --arg key "$key" '.images[$key].ref' "$evidence_file")"
    [ "$actual_ref" = "$locked_ref" ] \
      || fail "Rollback target $repository differs from its checksummed control evidence."
  done
}

verify_target_image_revisions() {
  local env_file="$1"
  local tag="$2"
  local expected_revision repository image_ref revision source
  expected_revision="${tag#sha-}"
  for repository in moodride-route-api moodride-route-worker moodride-notification-service moodride-frontend; do
    image_ref="$(image_ref_for_repository "$env_file" "$repository")"
    revision="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
      "$image_ref")"
    [ "$revision" = "$expected_revision" ] \
      || fail "Rollback digest for $repository does not match its exact recorded source revision."
    source="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.source" }}' \
      "$image_ref")"
    [ "$source" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
      || fail "Rollback digest for $repository does not match the expected GitHub OCI source."
  done
  verify_local_caddy_image "$env_file" || return 1
  verify_local_osrm_image "$env_file" || return 1
  echo "Rollback digest pins match recorded source revision $tag."
}

psql_query_db() {
  local env_file="$1"
  local database="$2"
  local sql="$3"
  compose_env "$env_file" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname "$database" \
      --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
      --command "$sql"
}

psql_query() {
  local env_file="$1"
  local sql="$2"
  psql_query_db "$env_file" "$POSTGRES_DB" "$sql"
}
source "$SCRIPT_DIR/database_recovery.sh"

active_job_count() {
  local env_file="$1"
  local count
  count="$(psql_query "$env_file" "SELECT COUNT(*) FROM route_jobs WHERE status IN ('QUEUED', 'PROCESSING', 'PRIMARY_READY');")"
  count="$(printf '%s' "$count" | tr -d '[:space:]')"
  case "$count" in
    ''|*[!0-9]*) fail "Could not read a numeric active route-job count." ;;
  esac
  printf '%s\n' "$count"
}

pending_terminal_event_count() {
  local env_file="$1"
  local table_present count
  table_present="$(psql_query "$env_file" \
    "SELECT CASE WHEN to_regclass('route_job_terminal_events') IS NULL THEN 0 ELSE 1 END;")"
  table_present="$(printf '%s' "$table_present" | tr -d '[:space:]')"
  case "$table_present" in
    0)
      printf '0\n'
      return 0
      ;;
    1) ;;
    *) fail "Could not determine whether the V41 terminal outbox exists." ;;
  esac
  count="$(psql_query "$env_file" \
    "SELECT COUNT(*) FROM route_job_terminal_events WHERE delivered_at IS NULL;")"
  count="$(printf '%s' "$count" | tr -d '[:space:]')"
  case "$count" in
    ''|*[!0-9]*) fail "Could not read a numeric pending terminal-event count." ;;
  esac
  printf '%s\n' "$count"
}

wait_for_drain() {
  local env_file="$1"
  local start_ts now active_count terminal_count
  start_ts="$(date +%s)"
  while true; do
    active_count="$(active_job_count "$env_file")"
    terminal_count="$(pending_terminal_event_count "$env_file")"
    if [ "$active_count" -eq 0 ] && [ "$terminal_count" -eq 0 ]; then
      echo "Route-job and terminal-outbox drain completed."
      return 0
    fi

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$DRAIN_TIMEOUT_SECONDS" ]; then
      echo "Drain timed out with $active_count active route jobs and $terminal_count pending V41 terminal events. Ingress remains stopped." >&2
      return 1
    fi

    echo "Waiting for $active_count active route jobs and $terminal_count terminal events to drain."
    sleep "$DRAIN_POLL_SECONDS"
  done
}

assert_no_active_jobs() {
  local env_file="$1"
  local active_count terminal_count
  active_count="$(active_job_count "$env_file")"
  terminal_count="$(pending_terminal_event_count "$env_file")"
  [ "$active_count" -eq 0 ] \
    || fail "Rollback refused: $active_count active route jobs remain after consumers stopped."
  [ "$terminal_count" -eq 0 ] \
    || fail "Rollback refused: $terminal_count undelivered V41 terminal events remain after consumers stopped."
}

run_http_healthcheck() {
  local name="$1"
  local url="$2"
  local timeout_seconds="$3"
  local start_ts now code
  start_ts="$(date +%s)"

  while true; do
    code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 10 --max-time 20 "$url" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
      echo "$name healthcheck passed."
      return 0
    fi

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$timeout_seconds" ]; then
      echo "$name healthcheck failed after ${timeout_seconds}s (last HTTP ${code:-n/a})." >&2
      return 1
    fi

    sleep 5
  done
}

run_sockjs_healthcheck() {
  local name="$1"
  local base_url="${2%/}"
  local timeout_seconds="$3"
  local start_ts now response
  start_ts="$(date +%s)"
  while true; do
    response="$(curl --fail --silent --show-error --connect-timeout 10 --max-time 20 \
      "${base_url}/info?t=$(date +%s)" 2>/dev/null || true)"
    if printf '%s' "$response" | jq -e '
        type == "object"
        and .websocket == true
        and (.cookie_needed | type == "boolean")
        and (.origins | type == "array")
        and (.entropy | type == "number")
      ' >/dev/null 2>&1; then
      echo "$name SockJS/WebSocket readiness passed."
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$timeout_seconds" ]; then
      echo "$name SockJS/WebSocket readiness failed after ${timeout_seconds}s." >&2
      return 1
    fi
    sleep 5
  done
}

run_internal_sockjs_healthcheck() {
  local name="$1"
  local env_file="$2"
  local service="$3"
  local base_url="${4%/}"
  local timeout_seconds="$5"
  local start_ts now response
  start_ts="$(date +%s)"
  while true; do
    response="$(compose_env "$env_file" exec -T "$service" \
      wget -q -T 20 -O - "${base_url}/info?t=$(date +%s)" 2>/dev/null || true)"
    if printf '%s' "$response" | jq -e '
        type == "object"
        and .websocket == true
        and (.cookie_needed | type == "boolean")
        and (.origins | type == "array")
        and (.entropy | type == "number")
      ' >/dev/null 2>&1; then
      echo "$name internal SockJS/WebSocket readiness passed."
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$timeout_seconds" ]; then
      echo "$name internal SockJS/WebSocket readiness failed after ${timeout_seconds}s." >&2
      return 1
    fi
    sleep 5
  done
}

run_internal_http_healthcheck() {
  local name="$1"
  local env_file="$2"
  local service="$3"
  local url="$4"
  local timeout_seconds="$5"
  local start_ts now
  start_ts="$(date +%s)"

  while true; do
    if compose_env "$env_file" exec -T "$service" \
        wget -q -T 20 -O /dev/null "$url" >/dev/null 2>&1; then
      echo "$name internal healthcheck passed."
      return 0
    fi

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$timeout_seconds" ]; then
      echo "$name internal healthcheck failed after ${timeout_seconds}s." >&2
      return 1
    fi
    sleep 5
  done
}

run_synthetic_route_smoke() {
  local env_file="$1"
  local lifecycle="$2"
  local payload response job_id status start_ts now saw_primary route_count primary_ready_recorded terminal_delivery_count

  psql_query "$env_file" \
    "DELETE FROM route_jobs WHERE user_id = '${SYNTHETIC_USER_ID}'::uuid;" >/dev/null
  payload="{\"userId\":\"${SYNTHETIC_USER_ID}\",\"lat\":45.9636,\"lng\":-66.6431,\"timeBudgetMinutes\":30,\"vibes\":[\"countryside\"]}"

  if ! response="$(compose_env "$env_file" exec -T route-api \
      wget -q -T 30 -O - --header='Content-Type: application/json' \
        --post-data="$payload" http://127.0.0.1:8080/api/routes 2>/dev/null)"; then
    fail "Synthetic route request was not accepted by the internal route-api."
  fi
  printf '%s' "$response" | grep -q '"jobId"' \
    || fail "Synthetic route response did not contain a jobId."

  job_id="$(psql_query "$env_file" \
    "SELECT id FROM route_jobs WHERE user_id = '${SYNTHETIC_USER_ID}'::uuid ORDER BY submitted_at DESC LIMIT 1;")"
  job_id="$(printf '%s' "$job_id" | tr -d '[:space:]')"
  printf '%s' "$job_id" | grep -Eq '^[0-9a-fA-F-]{36}$' \
    || fail "Synthetic route job was not persisted with a UUID."

  saw_primary=0
  start_ts="$(date +%s)"
  while true; do
    status="$(psql_query "$env_file" \
      "SELECT status FROM route_jobs WHERE id = '${job_id}'::uuid;")"
    status="$(printf '%s' "$status" | tr -d '[:space:]')"
    case "$status" in
      PRIMARY_READY)
        saw_primary=1
        ;;
      COMPLETED)
        break
        ;;
      FAILED|TIMEOUT|'')
        fail "Synthetic route job ended without completion (status: ${status:-missing})."
        ;;
      QUEUED|PROCESSING)
        ;;
      *)
        fail "Synthetic route job returned unexpected status: $status"
        ;;
    esac

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$SYNTHETIC_JOB_TIMEOUT_SECONDS" ]; then
      fail "Synthetic route job timed out in status $status."
    fi
    sleep 1
  done

  if [ "$saw_primary" -ne 1 ] && [ "$lifecycle" != "v38" ]; then
    primary_ready_recorded="$(psql_query "$env_file" \
      "SELECT CASE WHEN primary_ready_at IS NOT NULL THEN 1 ELSE 0 END FROM route_jobs WHERE id = '${job_id}'::uuid;")"
    primary_ready_recorded="$(printf '%s' "$primary_ready_recorded" | tr -d '[:space:]')"
    [ "$primary_ready_recorded" = "1" ] && saw_primary=1
  fi
  if [ "$lifecycle" != "v38" ] && [ "$saw_primary" -ne 1 ]; then
    fail "Synthetic route job completed without proving the PRIMARY_READY transition."
  fi

  route_count="$(psql_query "$env_file" \
    "SELECT COUNT(*) FROM routes WHERE job_id = '${job_id}'::uuid;")"
  route_count="$(printf '%s' "$route_count" | tr -d '[:space:]')"
  case "$route_count" in
    ''|*[!0-9]*) fail "Synthetic route smoke could not count persisted routes." ;;
  esac
  [ "$route_count" -gt 0 ] || fail "Synthetic route job completed without a persisted route."

  if [ "$lifecycle" = "v41" ]; then
    start_ts="$(date +%s)"
    while true; do
      terminal_delivery_count="$(psql_query "$env_file" \
        "SELECT COUNT(*) FROM route_job_terminal_events WHERE job_id = '${job_id}'::uuid AND event_type = 'COMPLETION' AND terminal_status = 'COMPLETED' AND delivered_at IS NOT NULL;")"
      terminal_delivery_count="$(printf '%s' "$terminal_delivery_count" | tr -d '[:space:]')"
      case "$terminal_delivery_count" in
        ''|*[!0-9]*) fail "Synthetic route smoke could not verify V41 terminal delivery." ;;
      esac
      if [ "$terminal_delivery_count" -eq 1 ]; then
        break
      fi
      [ "$terminal_delivery_count" -eq 0 ] \
        || fail "Synthetic route smoke found duplicate V41 completion deliveries."
      now="$(date +%s)"
      if [ $((now - start_ts)) -ge "$SYNTHETIC_JOB_TIMEOUT_SECONDS" ]; then
        fail "Synthetic route completion was not durably delivered through the V41 outbox."
      fi
      sleep 1
    done
  fi

  psql_query "$env_file" \
    "DELETE FROM route_jobs WHERE id = '${job_id}'::uuid AND user_id = '${SYNTHETIC_USER_ID}'::uuid;" >/dev/null
  wait_for_drain "$env_file"
  if [ "$lifecycle" = "v41" ]; then
    echo "Synthetic route smoke proved PRIMARY_READY, COMPLETED, and durable V41 terminal delivery; fixtures were cleaned up."
  else
    echo "Synthetic V38-baseline route smoke completed with a persisted primary and was cleaned up."
  fi
}

verify_service_running() {
  local env_file="$1"
  local service="$2"
  local container_id running
  container_id="$(compose_env "$env_file" ps -q "$service")"
  [ -n "$container_id" ] || fail "Service $service has no container after rollback."
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] || fail "Service $service is not running after rollback."
}


verify_v38_baseline_schema() {
  local env_file="$1"
  local result
  validate_release_invariants "$env_file" "$POSTGRES_DB"
  result="$(psql_query "$env_file" "
    SELECT CASE WHEN
      (SELECT COUNT(*) FROM flyway_schema_history
       WHERE version = '38' AND description = 'add road segment stable identity'
         AND checksum = 1443186875 AND success IS TRUE) = 1
      AND NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version IN ('39', '40', '41')
      )
      AND NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE success IS TRUE AND installed_rank > (
          SELECT installed_rank FROM flyway_schema_history
          WHERE version = '38' AND description = 'add road segment stable identity'
            AND checksum = 1443186875 AND success IS TRUE
        )
      )
      THEN 'schema-v38-baseline-ok' ELSE 'schema-v38-baseline-divergent' END;
  ")"
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  [ "$result" = "schema-v38-baseline-ok" ] \
    || fail "V38 baseline schema/history verification failed."
}

apply_coordinated_sql() {
  compose_env "$ENV_FILE" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      --no-psqlrc --set ON_ERROR_STOP=1 --file=- < "$ROLLBACK_SQL"
}

restore_pre_rollback_release() {
  echo "Rollback switch failed; stopping every target application service." >&2
  compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend osrm \
    >/dev/null 2>&1 || true
  select_current_control_bundle

  if ! promote_validated_recovery_database "$ENV_FILE" "$RECOVERY_DATABASE" \
      "$RECOVERY_QUARANTINE_DATABASE" "$CURRENT_BACKUP" \
      "$CURRENT_HISTORY" "$CURRENT_CATALOG"; then
    echo "Recovery could not promote the validated pre-rollback replacement; original images were not started." >&2
    return 1
  fi

  durable_copy "$CURRENT_ENV" "$ENV_FILE" || return 1
  if ! compose_env "$ENV_FILE" up -d --no-deps --force-recreate osrm \
     || ! verify_running_osrm_identity "$ENV_FILE"; then
    return 1
  fi
  if ! compose_env "$ENV_FILE" up -d --no-deps route-api; then
    return 1
  fi
  if ! run_internal_http_healthcheck "Restored route-api" "$ENV_FILE" route-api \
      http://127.0.0.1:8080/actuator/health "$HEALTHCHECK_TIMEOUT_SECONDS"; then
    return 1
  fi
  if ! compose_env "$ENV_FILE" up -d --no-deps route-worker notification-service frontend; then
    return 1
  fi
  sleep 5
  if ! verify_service_running "$ENV_FILE" route-api \
     || ! verify_service_running "$ENV_FILE" route-worker \
     || ! verify_service_running "$ENV_FILE" notification-service \
     || ! verify_service_running "$ENV_FILE" frontend \
     || ! run_internal_http_healthcheck "Restored frontend" "$ENV_FILE" frontend \
          http://127.0.0.1:3000/ "$HEALTHCHECK_TIMEOUT_SECONDS" \
     || ! run_internal_sockjs_healthcheck "Restored WebSocket handshake" "$ENV_FILE" \
          notification-service http://127.0.0.1:8084/ws "$HEALTHCHECK_TIMEOUT_SECONDS"; then
    compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend osrm \
      >/dev/null 2>&1 || true
    return 1
  fi
  if [ "${TARGET_POINTER_SWITCHED:-0}" -eq 1 ]; then
    switch_control_bundle_pointer "$CURRENT_CONTROL_BUNDLE" || return 1
    TARGET_POINTER_SWITCHED=0
  fi
  if [ "${PRE_ROLLBACK_LAST_ROLLBACK_PRESENT:-0}" -eq 1 ]; then
    durable_copy "$PRE_ROLLBACK_LAST_ROLLBACK" "$MOODRIDE_DIR/.deploy/last-rollback" \
      || return 1
  else
    rm -f "$MOODRIDE_DIR/.deploy/last-rollback" || return 1
    sync -f "$MOODRIDE_DIR/.deploy" || return 1
  fi
  if ! compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy \
     || ! verify_service_running "$ENV_FILE" caddy \
     || ! verify_running_caddy_image "$ENV_FILE" \
     || ! run_http_healthcheck "Restored API" "$API_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS" \
     || ! run_http_healthcheck "Restored frontend" "$FRONTEND_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS" \
     || ! run_sockjs_healthcheck "Restored WebSocket handshake" "$WS_HEALTHCHECK_URL" \
          "$HEALTHCHECK_TIMEOUT_SECONDS"; then
    compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend osrm \
      >/dev/null 2>&1 || true
    return 1
  fi
  return 0
}

cleanup() {
  release_cutover_lock
}

rollback_error() {
  local status="$1"
  if [ "${BASHPID:-$$}" != "${MAIN_PID:-$$}" ]; then
    return "$status"
  fi
  trap - ERR INT TERM
  set +e
  if [ "${RECOVERY_ENABLED:-0}" -eq 1 ]; then
    if restore_pre_rollback_release; then
      echo "The validated pre-rollback replacement and exact original image digests were restored; failed rollback data is quarantined at $RECOVERY_QUARANTINE_DATABASE." >&2
    else
      echo "ROLLBACK RECOVERY FAILED. Application consumers remain stopped; current and recovery databases were preserved for operator recovery." >&2
    fi
  elif [ "${FAIL_CLOSED_ON_ERROR:-0}" -eq 1 ]; then
    compose_env "$ENV_FILE" stop caddy >/dev/null 2>&1 || true
    compose_env "$ENV_FILE" stop route-api route-worker notification-service frontend osrm \
      >/dev/null 2>&1 || true
    echo "Post-switch rollback verification failed. Ingress and application consumers are stopped; the coordinated V38 baseline is retained because public writes may have begun." >&2
  else
    echo "Rollback failed before the schema switch completed. Ingress remains stopped if the drain had begun." >&2
  fi
  exit "$status"
}

if [ "${DEPLOY_LIBRARY_ONLY:-0}" = "1" ] || [ "${ROLLBACK_LIBRARY_ONLY:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi

EXPECTED_CURRENT_TAG=""
EXPECTED_CURRENT_RELEASE_LOCK_SHA256=""
ROLLBACK_TAG=""
TARGET_IMAGE_LOCK=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-current-tag)
      [ "$#" -ge 2 ] || fail "--expected-current-tag requires a value."
      EXPECTED_CURRENT_TAG="$2"
      shift 2
      ;;
    --expected-current-release-lock-sha256)
      [ "$#" -ge 2 ] || fail "--expected-current-release-lock-sha256 requires a value."
      EXPECTED_CURRENT_RELEASE_LOCK_SHA256="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done
printf '%s' "$EXPECTED_CURRENT_TAG" | grep -Eq '^sha-[0-9a-f]{40}$' \
  || fail "--expected-current-tag must be an exact immutable sha-40-hex tag."
printf '%s' "$EXPECTED_CURRENT_RELEASE_LOCK_SHA256" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "--expected-current-release-lock-sha256 must be 64 lowercase hexadecimal characters."

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
ENV_FILE=${ENV_FILE:-.env.prod}
BACKUP_DIR=${BACKUP_DIR:-$MOODRIDE_DIR/.deploy/db-backups}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-1800}
DRAIN_POLL_SECONDS=${DRAIN_POLL_SECONDS:-5}
HEALTHCHECK_TIMEOUT_SECONDS=${HEALTHCHECK_TIMEOUT_SECONDS:-360}
SYNTHETIC_JOB_TIMEOUT_SECONDS=${SYNTHETIC_JOB_TIMEOUT_SECONDS:-600}
SYNTHETIC_USER_ID=00000000-0000-0000-0000-000000000035
API_HEALTHCHECK_URL=${API_HEALTHCHECK_URL:-https://usewayward.app/api/scenic-regions?lat=45.94\&lng=-66.63\&radius=1}
FRONTEND_HEALTHCHECK_URL=${FRONTEND_HEALTHCHECK_URL:-https://usewayward.app/}
WS_HEALTHCHECK_URL=${WS_HEALTHCHECK_URL:-https://usewayward.app/ws}
EXPECTED_GITHUB_SOURCE_URL=${EXPECTED_GITHUB_SOURCE_URL:-}
if [ -n "$EXPECTED_GITHUB_SOURCE_URL" ]; then
  printf '%s' "$EXPECTED_GITHUB_SOURCE_URL" \
    | grep -Eq '^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' \
    || fail "EXPECTED_GITHUB_SOURCE_URL must be an exact GitHub repository URL when provided."
fi

require_positive_integer DRAIN_TIMEOUT_SECONDS "$DRAIN_TIMEOUT_SECONDS"
require_positive_integer DRAIN_POLL_SECONDS "$DRAIN_POLL_SECONDS"
require_positive_integer HEALTHCHECK_TIMEOUT_SECONDS "$HEALTHCHECK_TIMEOUT_SECONDS"
require_positive_integer SYNTHETIC_JOB_TIMEOUT_SECONDS "$SYNTHETIC_JOB_TIMEOUT_SECONDS"
require_command docker
require_command curl
require_command cksum
require_command cmp
require_command openssl
require_command sha256sum
require_command jq
require_command flock
require_command sync
require_command readlink

docker compose version >/dev/null
cd "$MOODRIDE_DIR"
case "$BACKUP_DIR" in
  /*) ;;
  *) fail "BACKUP_DIR must be an absolute host path outside Docker volumes." ;;
esac
case "$BACKUP_DIR" in
  /var/lib/docker/volumes|/var/lib/docker/volumes/*)
    fail "BACKUP_DIR must not be inside Docker managed volumes."
    ;;
esac

mkdir -p .deploy/releases .deploy/image-locks "$BACKUP_DIR"
MAIN_PID="${BASHPID:-$$}"
CUTOVER_LOCK_HELD=0
RECOVERY_ENABLED=0
FAIL_CLOSED_ON_ERROR=0
TARGET_POINTER_SWITCHED=0
acquire_cutover_lock
trap cleanup EXIT
trap 'rollback_error $?' ERR
trap 'rollback_error 130' INT
trap 'rollback_error 143' TERM
[ -d "$MOODRIDE_DIR/.deploy/bundles" ] \
  || fail "Immutable production control-bundle root is missing."
[ -L "$MOODRIDE_DIR/.deploy/current" ] \
  || fail "Current production control-bundle pointer is missing."
CURRENT_CONTROL_BUNDLE="$(readlink -f "$MOODRIDE_DIR/.deploy/current")"
verify_control_bundle "$CURRENT_CONTROL_BUNDLE" 1
CURRENT_CONTROL_BUNDLE="$VERIFIED_CONTROL_BUNDLE"
select_current_control_bundle
require_file "$CURRENT_CONTROL_BUNDLE/quality-acceptance.json"
require_file "$ENV_FILE"
require_file "$CURRENT_CONTROL_BUNDLE/scripts/deploy/database_recovery.sh"
ensure_analytics_hash_secret "$ENV_FILE"

CURRENT_TAG="$(get_env_var IMAGE_TAG "$ENV_FILE")"
[ -n "$CURRENT_TAG" ] || fail "Current IMAGE_TAG is missing from $ENV_FILE."
printf '%s' "$CURRENT_TAG" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$' \
  || fail "Current IMAGE_TAG is not a valid Docker tag."

ROLLBACK_SNAPSHOT="$MOODRIDE_DIR/.deploy/releases/rollback-${EXPECTED_CURRENT_TAG}-${EXPECTED_CURRENT_RELEASE_LOCK_SHA256}.env"
require_file "$ROLLBACK_SNAPSHOT"
require_file "${ROLLBACK_SNAPSHOT}.sha256"
(cd "$(dirname "$ROLLBACK_SNAPSHOT")" && sha256sum --check "$(basename "$ROLLBACK_SNAPSHOT").sha256")
[ "$(get_env_var CURRENT_TAG "$ROLLBACK_SNAPSHOT")" = "$EXPECTED_CURRENT_TAG" ] \
  || fail "Rollback snapshot is not bound to expected-current source SHA."
[ "$(get_env_var CURRENT_RELEASE_LOCK_SHA256 "$ROLLBACK_SNAPSHOT")" = \
  "$EXPECTED_CURRENT_RELEASE_LOCK_SHA256" ] \
  || fail "Rollback snapshot is not bound to expected-current release-lock bytes."
[ "$(get_env_var CURRENT_CONTROL_BUNDLE "$ROLLBACK_SNAPSHOT")" = "$CURRENT_CONTROL_BUNDLE" ] \
  || fail "Rollback snapshot current bundle differs from the atomic current pointer."
current_bundle_manifest_sha="$(sha256sum "$CURRENT_CONTROL_BUNDLE/bundle.sha256")"
current_bundle_manifest_sha="${current_bundle_manifest_sha%% *}"
[ "$current_bundle_manifest_sha" = \
  "$(get_env_var CURRENT_BUNDLE_MANIFEST_SHA256 "$ROLLBACK_SNAPSHOT")" ] \
  || fail "Rollback snapshot current bundle manifest checksum diverged."
load_expected_github_source_url "$EXPECTED_CURRENT_TAG" \
  "$EXPECTED_CURRENT_RELEASE_LOCK_SHA256"
ROLLBACK_TAG="$(get_env_var PREVIOUS_TAG "$ROLLBACK_SNAPSHOT")"
TARGET_IMAGE_LOCK="$(get_env_var PREVIOUS_IMAGE_LOCK "$ROLLBACK_SNAPSHOT")"
TARGET_CONTROL_BUNDLE="$(get_env_var PREVIOUS_CONTROL_BUNDLE "$ROLLBACK_SNAPSHOT")"
TARGET_CONTROL_EVIDENCE="$(get_env_var PREVIOUS_CONTROL_EVIDENCE "$ROLLBACK_SNAPSHOT")"
case "$TARGET_IMAGE_LOCK" in
  "$MOODRIDE_DIR/.deploy/"*) ;;
  *) fail "Rollback snapshot image lock path is outside release state." ;;
esac
case "$TARGET_CONTROL_EVIDENCE" in
  "$MOODRIDE_DIR/.deploy/releases/"*) ;;
  *) fail "Rollback snapshot control evidence path is outside immutable release state." ;;
esac
verify_control_bundle "$TARGET_CONTROL_BUNDLE"
TARGET_CONTROL_BUNDLE="$VERIFIED_CONTROL_BUNDLE"
require_file "$TARGET_IMAGE_LOCK"
target_lock_sha="$(sha256sum "$TARGET_IMAGE_LOCK")"
target_lock_sha="${target_lock_sha%% *}"
[ "$target_lock_sha" = "$(get_env_var PREVIOUS_IMAGE_LOCK_SHA256 "$ROLLBACK_SNAPSHOT")" ] \
  || fail "Rollback snapshot previous image-lock bytes diverged."
target_bundle_manifest_sha="$VERIFIED_BUNDLE_MANIFEST_SHA256"
[ "$target_bundle_manifest_sha" = \
  "$(get_env_var PREVIOUS_BUNDLE_MANIFEST_SHA256 "$ROLLBACK_SNAPSHOT")" ] \
  || fail "Rollback snapshot previous control-bundle manifest diverged."
require_file "$TARGET_CONTROL_EVIDENCE"
require_file "${TARGET_CONTROL_EVIDENCE}.sha256"
target_evidence_sha="$(sha256sum "$TARGET_CONTROL_EVIDENCE")"
target_evidence_sha="${target_evidence_sha%% *}"
[ "$target_evidence_sha" = \
  "$(get_env_var PREVIOUS_CONTROL_EVIDENCE_SHA256 "$ROLLBACK_SNAPSHOT")" ] \
  || fail "Rollback snapshot previous control evidence bytes diverged."
(
  cd "$(dirname "$TARGET_CONTROL_EVIDENCE")"
  sha256sum --check "$(basename "$TARGET_CONTROL_EVIDENCE").sha256"
) >/dev/null
ROLLBACK_SQL="$TARGET_CONTROL_BUNDLE/scripts/deploy/rollback_v41_v40_v39_to_v38.sql"
require_file "$ROLLBACK_SQL"

printf '%s' "$ROLLBACK_TAG" | grep -Eq '^sha-[0-9a-f]{40}$' \
  || fail "Rollback tag must be an exact immutable sha-40-hex tag."
[ "$ROLLBACK_TAG" != "$CURRENT_TAG" ] || fail "Rollback target equals the currently configured image tag."

POSTGRES_USER="$(get_env_var POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(get_env_var POSTGRES_DB "$ENV_FILE")"
[ -n "$POSTGRES_USER" ] || fail "POSTGRES_USER is missing from $ENV_FILE."
[ -n "$POSTGRES_DB" ] || fail "POSTGRES_DB is missing from $ENV_FILE."

rollback_id="$(date -u +'%Y%m%dT%H%M%SZ')-rollback-${ROLLBACK_TAG}"
CURRENT_ENV="$MOODRIDE_DIR/.deploy/releases/${rollback_id}.env.backup"
TARGET_ENV="$MOODRIDE_DIR/.deploy/releases/${rollback_id}.target.env"
CURRENT_BACKUP="$BACKUP_DIR/${rollback_id}.pre-rollback.dump"
CURRENT_HISTORY="$BACKUP_DIR/${rollback_id}.pre-rollback.flyway-history"
CURRENT_CATALOG="$BACKUP_DIR/${rollback_id}.pre-rollback.catalog"
RECOVERY_DATABASE="wayward_recovery_$(date -u +'%Y%m%d%H%M%S')_${MAIN_PID}"
RECOVERY_QUARANTINE_DATABASE="wayward_quarantine_$(date -u +'%Y%m%d%H%M%S')_${MAIN_PID}"
PRE_ROLLBACK_LAST_ROLLBACK="$MOODRIDE_DIR/.deploy/releases/${rollback_id}.last-rollback.backup"
PRE_ROLLBACK_LAST_ROLLBACK_PRESENT=0
if [ -f "$MOODRIDE_DIR/.deploy/last-rollback" ]; then
  durable_copy "$MOODRIDE_DIR/.deploy/last-rollback" "$PRE_ROLLBACK_LAST_ROLLBACK"
  PRE_ROLLBACK_LAST_ROLLBACK_PRESENT=1
fi
durable_copy "$ENV_FILE" "$CURRENT_ENV"
durable_copy "$ENV_FILE" "$TARGET_ENV"
chmod 600 "$CURRENT_ENV" "$TARGET_ENV"
set_env_var CADDYFILE_PATH "$CURRENT_CONTROL_BUNDLE/Caddyfile" "$CURRENT_ENV"
set_env_var CADDYFILE_PATH "$TARGET_CONTROL_BUNDLE/Caddyfile" "$TARGET_ENV"
capture_running_image_refs "$CURRENT_ENV" "$CURRENT_ENV"
ACTUAL_CURRENT_TAG="$(get_env_var IMAGE_TAG "$CURRENT_ENV")"
validate_configured_running_tag "$CURRENT_TAG" "$ACTUAL_CURRENT_TAG"
[ "$ROLLBACK_TAG" != "$ACTUAL_CURRENT_TAG" ] \
  || fail "Rollback target equals the actual running source revision."
CURRENT_TAG="$ACTUAL_CURRENT_TAG"
verify_current_release_fence "$CURRENT_ENV" "$EXPECTED_CURRENT_TAG" \
  "$EXPECTED_CURRENT_RELEASE_LOCK_SHA256"
verify_running_osrm_identity "$CURRENT_ENV"
load_image_lock "$TARGET_IMAGE_LOCK" "$ROLLBACK_TAG" "$TARGET_ENV"
ensure_compose_introspection_identity "$CURRENT_ENV"
ensure_compose_introspection_identity "$TARGET_ENV"
EXPECTED_SCENIC_SCORING_VERSION="$(get_env_var MOODRIDE_SCENIC_SCORING_VERSION "$CURRENT_ENV")"
EXPECTED_ROAD_DATASET_FINGERPRINT="$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$CURRENT_ENV")"
synchronize_dataset_release_identity "$CURRENT_ENV" "$POSTGRES_DB" "$TARGET_ENV"
verify_target_control_evidence "$TARGET_CONTROL_EVIDENCE" "$TARGET_ENV" "$ROLLBACK_TAG"
validate_rollback_env "$CURRENT_ENV" "$CURRENT_CONTROL_BUNDLE"
validate_rollback_env "$TARGET_ENV" "$TARGET_CONTROL_BUNDLE"
select_current_control_bundle
compose_env "$CURRENT_ENV" config >/dev/null
verify_rendered_caddy_image "$CURRENT_ENV"
select_target_control_bundle
compose_env "$TARGET_ENV" config >/dev/null
verify_rendered_caddy_image "$TARGET_ENV"

# Ensure both target and recovery images are locally available before intake stops.
echo "Pulling rollback target images: $ROLLBACK_TAG"
select_target_control_bundle
docker pull "$(get_env_var CADDY_IMAGE_REF "$TARGET_ENV")" >/dev/null
docker pull "$(get_env_var OSRM_IMAGE_REF "$TARGET_ENV")" >/dev/null
compose_env "$TARGET_ENV" pull osrm route-api route-worker notification-service frontend
verify_target_image_revisions "$TARGET_ENV" "$ROLLBACK_TAG"
echo "Confirming current release images are recoverable: $CURRENT_TAG"
select_current_control_bundle
docker pull "$(get_env_var CADDY_IMAGE_REF "$CURRENT_ENV")" >/dev/null
docker pull "$(get_env_var OSRM_IMAGE_REF "$CURRENT_ENV")" >/dev/null
compose_env "$CURRENT_ENV" pull osrm route-api route-worker notification-service frontend
verify_target_image_revisions "$CURRENT_ENV" "$ACTUAL_CURRENT_TAG"

# Caddy is the public intake boundary. Keep current route-api running with the worker
# so V40 dispatch recovery can publish any unsent QUEUED jobs during the drain.
echo "Stopping public ingress; current route-api and route-worker remain active for drain."
verify_running_caddy_image "$CURRENT_ENV"
compose_env "$CURRENT_ENV" stop caddy
wait_for_drain "$CURRENT_ENV"

echo "Stopping current application services before database rollback."
compose_env "$CURRENT_ENV" stop route-api route-worker notification-service frontend
assert_no_active_jobs "$CURRENT_ENV"
evict_scenic_anchor_cache_namespaces "$CURRENT_ENV"

capture_flyway_history "$CURRENT_ENV" "$CURRENT_HISTORY"
capture_database_catalog "$CURRENT_ENV" "$POSTGRES_DB" "$CURRENT_CATALOG"
validate_release_invariants "$CURRENT_ENV" "$POSTGRES_DB"
create_recovery_backup "$CURRENT_ENV" "$CURRENT_BACKUP"
create_validated_recovery_database "$CURRENT_ENV" "$CURRENT_BACKUP" \
  "$RECOVERY_DATABASE" "$CURRENT_HISTORY" "$CURRENT_CATALOG"

# Enable recovery only after the full scratch restore independently validates.
# Error and cleanup traps preserve both the current and validated replacement DBs.
RECOVERY_ENABLED=1
echo "Applying coordinated V41/V40/V39 schema and Flyway-history rollback."
apply_coordinated_sql
verify_v38_baseline_schema "$CURRENT_ENV"
select_target_control_bundle
durable_copy "$TARGET_ENV" "$ENV_FILE"
echo "Recreating the exact rollback OSRM image and checksummed dataset before consumers."
compose_env "$ENV_FILE" up -d --no-deps --force-recreate osrm
verify_running_osrm_identity "$ENV_FILE"

echo "Starting rollback route-api alone for internal readiness at the Flyway V38 baseline."
compose_env "$ENV_FILE" up -d --no-deps route-api
run_internal_http_healthcheck "Rollback route-api" "$ENV_FILE" route-api \
  http://127.0.0.1:8080/actuator/health "$HEALTHCHECK_TIMEOUT_SECONDS"
verify_v38_baseline_schema "$ENV_FILE"

echo "Starting rollback consumers behind closed ingress."
compose_env "$ENV_FILE" up -d --no-deps route-worker notification-service frontend
sleep 5
verify_service_running "$ENV_FILE" route-api
verify_service_running "$ENV_FILE" route-worker
verify_service_running "$ENV_FILE" notification-service
verify_service_running "$ENV_FILE" frontend
run_internal_http_healthcheck "Rollback frontend" "$ENV_FILE" frontend \
  http://127.0.0.1:3000/ "$HEALTHCHECK_TIMEOUT_SECONDS"
run_internal_sockjs_healthcheck "Rollback WebSocket handshake" "$ENV_FILE" \
  notification-service http://127.0.0.1:8084/ws "$HEALTHCHECK_TIMEOUT_SECONDS"
run_synthetic_route_smoke "$ENV_FILE" v38

# Persist the target control plane and rollback marker while ingress is closed.
# Recovery remains enabled until both durable updates succeed.
printf '%s\n' "$rollback_id" > "$MOODRIDE_DIR/.deploy/last-rollback.${MAIN_PID}.tmp"
sync -f "$MOODRIDE_DIR/.deploy/last-rollback.${MAIN_PID}.tmp"
TARGET_POINTER_SWITCHED=1
switch_control_bundle_pointer "$TARGET_CONTROL_BUNDLE"
durable_replace "$MOODRIDE_DIR/.deploy/last-rollback.${MAIN_PID}.tmp" \
  "$MOODRIDE_DIR/.deploy/last-rollback"

# The V38 database, exact images, target control pointer, and marker are now
# durable. Disable recovery before reopening ingress so public writes survive.
RECOVERY_ENABLED=0
FAIL_CLOSED_ON_ERROR=1
echo "Recreating Caddy only after internal rollback and durable pointer verification."
compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy
verify_service_running "$ENV_FILE" caddy
verify_running_caddy_image "$ENV_FILE"
run_http_healthcheck "Rollback API" "$API_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"
run_http_healthcheck "Rollback frontend" "$FRONTEND_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"
run_sockjs_healthcheck "Rollback WebSocket handshake" "$WS_HEALTHCHECK_URL" \
  "$HEALTHCHECK_TIMEOUT_SECONDS"
FAIL_CLOSED_ON_ERROR=0
echo "Rollback completed with immutable image tag: $ROLLBACK_TAG"
