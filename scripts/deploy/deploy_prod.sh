#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export LC_ALL=C
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage:
  deploy_prod.sh --release-lock <accepted-release-lock.json> \
    --release-lock-sha256 <64-lowercase-hex> \
    --quality-acceptance <accepted-quality.json> \
    --quality-acceptance-sha256 <64-lowercase-hex>

The accepted lock is the sole image input. Its checksum, source SHA, immutable tag,
four repository tags, image-index digests, and OCI revisions are validated before
any production state changes.

Environment variables:
  MOODRIDE_DIR       (default: /opt/moodride)
  COMPOSE_FILE       (default: docker-compose.prod.yml)
  ENV_FILE           (default: .env.prod)
  BACKUP_DIR         (default: <MOODRIDE_DIR>/.deploy/db-backups; host path, not a Docker volume)
  DEPLOYMENT_ATTEMPT_ID        (required; workflow run/attempt identity)
  DRAIN_TIMEOUT_SECONDS       (default: 1800)
  DRAIN_POLL_SECONDS          (default: 5)
  HEALTHCHECK_TIMEOUT_SECONDS (default: 360)
  SYNTHETIC_JOB_TIMEOUT_SECONDS (default: 600)
  API_HEALTHCHECK_URL         (default: https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1)
  FRONTEND_HEALTHCHECK_URL    (default: https://usewayward.app/)
  WS_HEALTHCHECK_URL          (default: https://usewayward.app/ws/info)
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
clear_stale_runtime_evidence() {
  local deploy_dir="$MOODRIDE_DIR/.deploy"
  [ "${CUTOVER_LOCK_HELD:-0}" -eq 1 ] \
    || {
      fail "Runtime evidence may only be cleared while holding the production cutover lock."
      return 1
    }
  rm -f -- \
    "$deploy_dir/last-quality-comparison-control.json" \
    "$deploy_dir/last-quality-comparison-candidate.json" \
    "$deploy_dir/last-osrm-files-control.sha256" \
    "$deploy_dir/last-osrm-files-candidate.sha256" \
    "$deploy_dir/last-osrm-files-control.metadata.json" \
    "$deploy_dir/last-osrm-files-candidate.metadata.json" \
    "$deploy_dir/last-quality-comparison.json" \
    "$deploy_dir/last-osrm-files.sha256"
  sync -f "$deploy_dir"
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
  case "$secret" in
    ''|replace-with-64-character-random-hex-secret)
      secret="$(openssl rand -hex 32 | tr -d '\r\n')"
      if ! printf '%s' "$secret" | grep -Eq '^[0-9a-f]{64}$'; then
        fail "Generated MOODRIDE_ANALYTICS_HASH_SECRET is not exactly 64 lowercase hexadecimal characters."
        return 1
      fi
      set_env_var MOODRIDE_ANALYTICS_HASH_SECRET "$secret" "$file"
      ;;
    replace-with-*|*placeholder*)
      fail "MOODRIDE_ANALYTICS_HASH_SECRET is an unrecognized template placeholder."
      return 1
      ;;
  esac
  if ! printf '%s' "$secret" | grep -Eq '^[0-9a-f]{64}$'; then
    fail "MOODRIDE_ANALYTICS_HASH_SECRET must be exactly 64 lowercase hexadecimal characters."
    return 1
  fi
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
is_template_placeholder() {
  case "$1" in
    replace-with-*|*placeholder*) return 0 ;;
    *) return 1 ;;
  esac
}


reject_template_placeholder() {
  local key="$1"
  local value="$2"
  if is_template_placeholder "$value"; then
    fail "Production environment value is still a template placeholder: $key"
    return 1
  fi
}

select_expected_runtime_identity() {
  local identity="$1"
  local prefix name source_variable
  case "$identity" in
    control) prefix="EXPECTED_CONTROL" ;;
    candidate) prefix="EXPECTED_CANDIDATE" ;;
    *)
      fail "Unsupported quality runtime identity: $identity"
      return 1
      ;;
  esac
  for name in DATABASE_FINGERPRINT SCENIC_SCORING_VERSION \
      SCENIC_DATASET_FINGERPRINT ROAD_DATASET_FINGERPRINT OSRM_IMAGE_REF \
      OSRM_DATASET_BASENAME OSRM_FILE_MANIFEST_SHA256 RUNTIME_PROFILE \
      ALGORITHM_PROFILE ALGORITHM_MODE GRAPH_WARMUP_ENABLED \
      ROAD_ANCHOR_CACHE_SCHEMA RUNTIME_ALGORITHM; do
    source_variable="${prefix}_${name}"
    printf -v "EXPECTED_${name}" '%s' "${!source_variable}"
  done
  EXPECTED_RUNTIME_IDENTITY="$identity"
}


validate_pre_drain_env() {
  local env_file="$1"
  local control_bundle="$2"
  local runtime_identity="$3"
  local key value namespace
  select_expected_runtime_identity "$runtime_identity"
  for key in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD REDIS_PASSWORD \
      MOODRIDE_ANALYTICS_HASH_SECRET MOODRIDE_SCENIC_SCORING_VERSION \
      MOODRIDE_ROAD_DATASET_REVISION MOODRIDE_ROAD_DATASET_FINGERPRINT \
      MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA GHCR_NAMESPACE OSRM_IMAGE_REF \
      OSRM_DATASET_BASENAME SPRING_PROFILES_ACTIVE MOODRIDE_ALGORITHM_PROFILE \
      MOODRIDE_ALGORITHM_MODE MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED IMAGE_TAG \
      ROUTE_API_IMAGE_REF ROUTE_WORKER_IMAGE_REF NOTIFICATION_SERVICE_IMAGE_REF \
      FRONTEND_IMAGE_REF CADDY_IMAGE_REF CADDYFILE_PATH; do
    value="$(get_env_var "$key" "$env_file")"
    if [ -z "$value" ]; then
      fail "Required production environment value is missing: $key"
      return 1
    fi
    reject_template_placeholder "$key" "$value" || return 1
  done
  if ! printf '%s' "$(get_env_var POSTGRES_PASSWORD "$env_file")" \
      | grep -q '[^[:space:]]'; then
    fail "POSTGRES_PASSWORD must not be blank."
    return 1
  fi
  if ! printf '%s' "$(get_env_var REDIS_PASSWORD "$env_file")" \
      | grep -q '[^[:space:]]'; then
    fail "REDIS_PASSWORD must not be blank."
    return 1
  fi
  if ! printf '%s' "$(get_env_var MOODRIDE_ANALYTICS_HASH_SECRET "$env_file")" \
      | grep -Eq '^[0-9a-f]{64}$'; then
    fail "MOODRIDE_ANALYTICS_HASH_SECRET must be exactly 64 lowercase hexadecimal characters."
    return 1
  fi
  if ! printf '%s' "$(get_env_var IMAGE_TAG "$env_file")" \
      | grep -Eq '^sha-[0-9a-f]{40}$'; then
    fail "IMAGE_TAG must identify exactly one 40-character lowercase source revision."
    return 1
  fi
  namespace="$(get_env_var GHCR_NAMESPACE "$env_file")"
  if ! printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$'; then
    fail "GHCR_NAMESPACE is invalid."
    return 1
  fi
  validate_image_reference "$namespace" moodride-route-api \
    "$(get_env_var ROUTE_API_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-route-worker \
    "$(get_env_var ROUTE_WORKER_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-notification-service \
    "$(get_env_var NOTIFICATION_SERVICE_IMAGE_REF "$env_file")" || return 1
  validate_image_reference "$namespace" moodride-frontend \
    "$(get_env_var FRONTEND_IMAGE_REF "$env_file")" || return 1
  if ! printf '%s' "$(get_env_var OSRM_IMAGE_REF "$env_file")" \
      | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$'; then
    fail "OSRM_IMAGE_REF must pin the exact OSRM repository digest."
    return 1
  fi
  if ! printf '%s' "$(get_env_var CADDY_IMAGE_REF "$env_file")" \
      | grep -Eq '^caddy@sha256:[0-9a-f]{64}$'; then
    fail "CADDY_IMAGE_REF must pin the exact official Caddy repository digest."
    return 1
  fi
  if ! printf '%s' "$(get_env_var OSRM_DATASET_BASENAME "$env_file")" \
      | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$'; then
    fail "OSRM_DATASET_BASENAME is invalid."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_SCENIC_SCORING_VERSION "$env_file")" != \
      "$EXPECTED_SCENIC_SCORING_VERSION" ]; then
    fail "MOODRIDE_SCENIC_SCORING_VERSION differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$env_file")" != \
      "$EXPECTED_ROAD_DATASET_FINGERPRINT" ]; then
    fail "MOODRIDE_ROAD_DATASET_FINGERPRINT differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var OSRM_IMAGE_REF "$env_file")" != "$EXPECTED_OSRM_IMAGE_REF" ]; then
    fail "OSRM_IMAGE_REF differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var OSRM_DATASET_BASENAME "$env_file")" != \
      "$EXPECTED_OSRM_DATASET_BASENAME" ]; then
    fail "OSRM_DATASET_BASENAME differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var SPRING_PROFILES_ACTIVE "$env_file")" != "$EXPECTED_RUNTIME_PROFILE" ]; then
    fail "SPRING_PROFILES_ACTIVE differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_ALGORITHM_PROFILE "$env_file")" != \
      "$EXPECTED_ALGORITHM_PROFILE" ] \
     || [ "$(get_env_var MOODRIDE_ALGORITHM_PROFILE "$env_file")" != \
      "$EXPECTED_RUNTIME_ALGORITHM" ]; then
    fail "MOODRIDE_ALGORITHM_PROFILE differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_ALGORITHM_MODE "$env_file")" != \
      "$EXPECTED_ALGORITHM_MODE" ]; then
    fail "MOODRIDE_ALGORITHM_MODE differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED "$env_file")" != \
      "$EXPECTED_GRAPH_WARMUP_ENABLED" ]; then
    fail "MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "$env_file")" != \
      "$EXPECTED_ROAD_ANCHOR_CACHE_SCHEMA" ]; then
    fail "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA differs from the $runtime_identity quality identity."
    return 1
  fi
  if [ "$(get_env_var CADDYFILE_PATH "$env_file")" != "$control_bundle/Caddyfile" ]; then
    fail "CADDYFILE_PATH does not select the verified control bundle."
    return 1
  fi
  require_file "$control_bundle/Caddyfile"
}


configure_candidate_runtime_environment() {
  local env_file="$1"
  local road_revision
  select_expected_runtime_identity candidate
  road_revision="$(get_env_var MOODRIDE_ROAD_DATASET_REVISION "$env_file")"
  if [ "$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$env_file")" != \
      "$EXPECTED_ROAD_DATASET_FINGERPRINT" ]; then
    road_revision="road-${EXPECTED_ROAD_DATASET_FINGERPRINT}"
  fi
  set_env_var MOODRIDE_SCENIC_SCORING_VERSION "$EXPECTED_SCENIC_SCORING_VERSION" "$env_file"
  set_env_var MOODRIDE_ROAD_DATASET_REVISION "$road_revision" "$env_file"
  set_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$EXPECTED_ROAD_DATASET_FINGERPRINT" "$env_file"
  set_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "$EXPECTED_ROAD_ANCHOR_CACHE_SCHEMA" "$env_file"
  set_env_var OSRM_IMAGE_REF "$EXPECTED_OSRM_IMAGE_REF" "$env_file"
  set_env_var OSRM_DATASET_BASENAME "$EXPECTED_OSRM_DATASET_BASENAME" "$env_file"
  set_env_var SPRING_PROFILES_ACTIVE "$EXPECTED_RUNTIME_PROFILE" "$env_file"
  set_env_var MOODRIDE_ALGORITHM_PROFILE "$EXPECTED_ALGORITHM_PROFILE" "$env_file"
  set_env_var MOODRIDE_ALGORITHM_MODE "$EXPECTED_ALGORITHM_MODE" "$env_file"
  set_env_var MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED "$EXPECTED_GRAPH_WARMUP_ENABLED" "$env_file"
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
  if [ "$count" -ne 1 ]; then
    fail "Expected one production container for $service, found $count."
    return 1
  fi
  printf '%s\n' "$container_id"
}
compose_env() {
  local env_file="$1"
  shift
  docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$env_file" "$@"
}
select_previous_control_bundle() {
  COMPOSE_FILE="$PREVIOUS_CONTROL_BUNDLE/docker-compose.prod.yml"
  CADDYFILE_PATH="$PREVIOUS_CONTROL_BUNDLE/Caddyfile"
  export CADDYFILE_PATH
}

select_candidate_control_bundle() {
  COMPOSE_FILE="$CONTROL_BUNDLE/docker-compose.prod.yml"
  CADDYFILE_PATH="$CONTROL_BUNDLE/Caddyfile"
  export CADDYFILE_PATH
}

verify_control_bundle() {
  local bundle="$1"
  local bundles_root canonical_bundle canonical_root manifest_sha expected_manifest
  bundles_root="$MOODRIDE_DIR/.deploy/bundles"
  require_file "$bundle/bundle.sha256"
  canonical_bundle="$(cd -P -- "$bundle" && pwd)"
  canonical_root="$(cd -P -- "$bundles_root" && pwd)"
  case "$canonical_bundle" in
    "$canonical_root"/*) ;;
    *) fail "Control bundle resolves outside the immutable bundle root: $bundle" ;;
  esac
  manifest_sha="$(sha256sum "$canonical_bundle/bundle.sha256")"
  manifest_sha="${manifest_sha%% *}"
  expected_manifest="${canonical_bundle##*/}"
  expected_manifest="${expected_manifest##*-}"
  [ "$manifest_sha" = "$expected_manifest" ] \
    || fail "Control bundle manifest digest does not match its checksum-versioned path."
  (cd "$canonical_bundle" && LC_ALL=C sha256sum --check bundle.sha256)
}

validate_candidate_control_bundle() {
  local env_file="$1"
  local script caddy_image
  for script in "$CONTROL_BUNDLE"/scripts/deploy/*.sh; do
    bash -n "$script" || return 1
  done
  caddy_image="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  if ! printf '%s' "$caddy_image" | grep -Eq '^caddy@sha256:[0-9a-f]{64}$'; then
    fail "Candidate Caddy image is not pinned to the official repository digest."
    return 1
  fi
  docker run --rm --entrypoint caddy \
    -v "$CONTROL_BUNDLE/Caddyfile:/etc/caddy/Caddyfile:ro" \
    "$caddy_image" validate --config /etc/caddy/Caddyfile >/dev/null
}
verify_rendered_caddy_image() {
  local env_file="$1"
  local expected_ref
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  if ! compose_env "$env_file" config --format json \
      | jq -e --arg expected_ref "$expected_ref" \
        '.services.caddy.image == $expected_ref' >/dev/null; then
    fail "Rendered Compose selects a mutable or unexpected Caddy image."
    return 1
  fi
}

verify_running_caddy_image() {
  local env_file="$1"
  local container_id image_id expected_ref actual_ref configured_ref
  expected_ref="$(get_env_var CADDY_IMAGE_REF "$env_file")"
  container_id="$(running_container_for_service caddy)" || return 1
  if [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" != "true" ]; then
    fail "Caddy is not running."
    return 1
  fi
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  configured_ref="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  actual_ref="$(resolve_repo_digest "$image_id" caddy)" || return 1
  if [ "$configured_ref" != "$expected_ref" ]; then
    fail "Running Caddy configuration is not the accepted digest reference."
    return 1
  fi
  if [ "$actual_ref" != "$expected_ref" ]; then
    fail "Running Caddy RepoDigest does not equal the accepted digest."
    return 1
  fi
}


validate_image_reference() {
  local namespace="$1"
  local repository="$2"
  local image_ref="$3"
  local digest
  case "$image_ref" in
    "ghcr.io/${namespace}/${repository}@sha256:"*) ;;
    *)
      fail "Image reference must pin ghcr.io/${namespace}/${repository} by digest."
      return 1
      ;;
  esac
  digest="${image_ref##*@sha256:}"
  if ! printf '%s' "$digest" | grep -Eq '^[0-9a-f]{64}$'; then
    fail "Image reference for $repository has an invalid sha256 digest."
    return 1
  fi
}


load_release_lock() {
  local lock_file="$1"
  local expected_checksum="$2"
  local actual_checksum source_sha image_tag namespace image_keys
  local entry key repository variable tag_ref digest_ref index_digest revision

  require_file "$lock_file"
  printf '%s' "$expected_checksum" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Release-lock checksum must be 64 lowercase hexadecimal characters."
  actual_checksum="$(sha256sum "$lock_file")"
  actual_checksum="${actual_checksum%% *}"
  [ "$actual_checksum" = "$expected_checksum" ] \
    || fail "Accepted release-lock checksum does not match its exact bytes."

  [ "$(jq -er '.schema_version' "$lock_file")" = "2" ] \
    || fail "Unsupported release-lock schema."
  source_sha="$(jq -er '.source_sha' "$lock_file")"
  printf '%s' "$source_sha" | grep -Eq '^[0-9a-f]{40}$' \
    || fail "Release-lock source_sha is invalid."
  image_tag="$(jq -er '.image_tag' "$lock_file")"
  [ "$image_tag" = "sha-${source_sha}" ] \
    || fail "Release-lock image_tag does not identify its exact source_sha."
  namespace="$(jq -er '.ghcr_namespace' "$lock_file")"
  printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    || fail "Release-lock GHCR namespace is invalid."
  [ "$(jq -er '.source_url' "$lock_file")" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
    || fail "Release-lock source_url does not equal the expected GitHub repository."
  image_keys="$(jq -er '.images | keys | join(",")' "$lock_file")"
  [ "$image_keys" = "frontend,notification_service,route_api,route_worker" ] \
    || fail "Release-lock must contain exactly the four production application images."

  IMAGE_TAG="$image_tag"
  GHCR_NAMESPACE="$namespace"
  for entry in \
    "route_api|moodride-route-api|ROUTE_API_IMAGE_REF" \
    "route_worker|moodride-route-worker|ROUTE_WORKER_IMAGE_REF" \
    "notification_service|moodride-notification-service|NOTIFICATION_SERVICE_IMAGE_REF" \
    "frontend|moodride-frontend|FRONTEND_IMAGE_REF"; do
    IFS='|' read -r key repository variable <<< "$entry"
    tag_ref="$(jq -er --arg key "$key" '.images[$key].tag' "$lock_file")"
    digest_ref="$(jq -er --arg key "$key" '.images[$key].ref' "$lock_file")"
    index_digest="$(jq -er --arg key "$key" '.images[$key].index_digest' "$lock_file")"
    revision="$(jq -er --arg key "$key" '.images[$key].revision' "$lock_file")"
    [ "$tag_ref" = "ghcr.io/${namespace}/${repository}:${image_tag}" ] \
      || fail "Release-lock tag for $repository is outside the accepted immutable release."
    validate_image_reference "$namespace" "$repository" "$digest_ref"
    [ "$index_digest" = "${digest_ref##*@}" ] \
      || fail "Release-lock image-index digest for $repository does not match its digest reference."
    [ "$revision" = "$source_sha" ] \
      || fail "Release-lock revision for $repository does not match source_sha."
    printf -v "$variable" '%s' "$digest_ref"
  done
}


load_expected_runtime_identity() {
  local quality_file="$1"
  local identity="$2"
  local prefix="$3"
  local index name
  local -a names values
  names=(
    DATABASE_FINGERPRINT
    SCENIC_SCORING_VERSION
    SCENIC_DATASET_FINGERPRINT
    ROAD_DATASET_FINGERPRINT
    OSRM_IMAGE_REF
    OSRM_DATASET_BASENAME
    OSRM_FILE_MANIFEST_SHA256
    RUNTIME_PROFILE
    ALGORITHM_PROFILE
    ALGORITHM_MODE
    GRAPH_WARMUP_ENABLED
    ROAD_ANCHOR_CACHE_SCHEMA
    RUNTIME_ALGORITHM
  )
  mapfile -t values < <(
    jq -er --arg identity "$identity" '
      .artifacts[$identity].runtime_identity
      | [
          .database_identity.database_fingerprint,
          .database_identity.scenic_scoring_version,
          .database_identity.scenic_dataset_fingerprint,
          .database_identity.road_dataset_fingerprint,
          .osrm.image_ref,
          .osrm.dataset_basename,
          .osrm.file_manifest_sha256,
          .cache_policy.spring_profiles_active,
          .cache_policy.algorithm_profile,
          .cache_policy.route_mode,
          (.cache_policy.graph_warmup_enabled | tostring),
          .cache_policy.road_anchor_cache_schema,
          .runtime_algorithm_mode.algorithm
        ][]
    ' "$quality_file"
  )
  if [ "${#values[@]}" -ne "${#names[@]}" ]; then
    fail "Could not load the exact $identity runtime identity."
    return 1
  fi
  for index in "${!names[@]}"; do
    name="${names[$index]}"
    printf -v "${prefix}_${name}" '%s' "${values[$index]}"
  done
}


load_quality_acceptance() {
  local quality_file="$1"
  local expected_checksum="$2"
  local actual_checksum expected_candidate_images
  require_file "$quality_file" || return 1
  printf '%s' "$expected_checksum" | grep -Eq '^[0-9a-f]{64}$' \
    || {
      fail "Quality-acceptance checksum must be 64 lowercase hexadecimal characters."
      return 1
    }
  actual_checksum="$(sha256sum "$quality_file")" || return 1
  actual_checksum="${actual_checksum%% *}"
  [ "$actual_checksum" = "$expected_checksum" ] \
    || {
      fail "Accepted quality-acceptance checksum does not match its exact bytes."
      return 1
    }
  expected_candidate_images="$(jq -c '
    .images
    | with_entries(.value = {
        ref: .value.ref,
        index_digest: .value.index_digest,
        revision: .value.revision
      })
  ' "$RELEASE_LOCK")"
  jq -e \
    --arg lock_sha "$RELEASE_LOCK_SHA256" \
    --arg source_sha "${IMAGE_TAG#sha-}" \
    --arg image_tag "$IMAGE_TAG" \
    --arg namespace "$GHCR_NAMESPACE" \
    --argjson expected_candidate_images "$expected_candidate_images" '
      def exact_image($repository; $revision):
        ((keys | join(",")) == "index_digest,ref,revision")
        and (.ref | test(
          "^ghcr\\.io/" + $namespace + "/" + $repository + "@sha256:[0-9a-f]{64}$"
        ))
        and .index_digest == (.ref | split("@")[1])
        and .revision == $revision;
      def exact_images($revision):
        ((keys | join(",")) == "frontend,notification_service,route_api,route_worker")
        and (.route_api | exact_image("moodride-route-api"; $revision))
        and (.route_worker | exact_image("moodride-route-worker"; $revision))
        and (.notification_service | exact_image("moodride-notification-service"; $revision))
        and (.frontend | exact_image("moodride-frontend"; $revision));
      def exact_runtime_identity($expected_algorithm_mode):
        ((keys | join(",")) ==
          "cache_policy,database_identity,osrm,runtime_algorithm_mode")
        and (.database_identity
          | ((keys | join(",")) ==
              "database_fingerprint,road_dataset_fingerprint,scenic_dataset_fingerprint,scenic_scoring_version")
          and (.database_fingerprint | test("^[0-9a-f]{64}$"))
          and (.scenic_scoring_version | test(
            "^3\\.7([._+-][A-Za-z0-9][A-Za-z0-9._+-]*)?$"
          ))
          and (.scenic_dataset_fingerprint | test("^[0-9a-f]{64}$"))
          and (.road_dataset_fingerprint | test("^[0-9a-f]{64}$")))
        and (.osrm
          | ((keys | join(",")) ==
              "dataset_basename,file_manifest_sha256,image_ref")
          and (.image_ref | test(
            "^ghcr\\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$"
          ))
          and .dataset_basename == "canada-latest"
          and (.file_manifest_sha256 | test("^[0-9a-f]{64}$")))
        and .cache_policy == {
          spring_profiles_active: "prod",
          algorithm_profile: "hybrid_osrm_v2",
          route_mode: "drive",
          graph_warmup_enabled: false,
          road_anchor_cache_schema: "v1"
        }
        and .runtime_algorithm_mode == $expected_algorithm_mode;
      .schema_version == 1
      and .verdict == "pass"
      and .release_lock_sha256 == $lock_sha
      and .source_sha == $source_sha
      and .image_tag == $image_tag
      and (has("images") | not)
      and (has("image_digests") | not)
      and (has("database_identity") | not)
      and (has("osrm") | not)
      and (has("runtime_algorithm_mode") | not)
      and (has("cache_policy") | not)
      and .scenario_manifest_sha256 == "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00"
      and .scenario_count == 27
      and .route_mode == "drive"
      and ((.artifacts | keys | join(",")) == "candidate,control")
      and (.artifacts.control.sha256 | test("^[0-9a-f]{64}$"))
      and (.artifacts.control.source_sha | test("^[0-9a-f]{40}$"))
      and (.artifacts.control.release_lock_sha256 | test("^[0-9a-f]{64}$"))
      and (.artifacts.control as $control
        | $control.images | exact_images($control.source_sha))
      and (.artifacts.control.runtime_identity
        | exact_runtime_identity({
            algorithm: "hybrid_osrm_v2",
            mode: "drive"
          }))
      and (.artifacts.candidate.sha256 | test("^[0-9a-f]{64}$"))
      and .artifacts.control.sha256 != .artifacts.candidate.sha256
      and .artifacts.candidate.source_sha == $source_sha
      and .artifacts.candidate.release_lock_sha256 == $lock_sha
      and .artifacts.candidate.images == $expected_candidate_images
      and (.artifacts.candidate.images | exact_images($source_sha))
      and (.artifacts.candidate.runtime_identity
        | exact_runtime_identity({
            algorithm: "hybrid_osrm_v2",
            mode: "drive"
          }))
      and (.artifacts.control.runtime_identity as $control_runtime
        | .artifacts.candidate.runtime_identity as $candidate_runtime
        | $control_runtime.database_identity.scenic_scoring_version ==
            $candidate_runtime.database_identity.scenic_scoring_version
        and $control_runtime.database_identity.scenic_dataset_fingerprint ==
            $candidate_runtime.database_identity.scenic_dataset_fingerprint
        and $control_runtime.database_identity.road_dataset_fingerprint ==
            $candidate_runtime.database_identity.road_dataset_fingerprint
        and $control_runtime.osrm == $candidate_runtime.osrm
        and $control_runtime.cache_policy == $candidate_runtime.cache_policy
        and $control_runtime.runtime_algorithm_mode ==
            $candidate_runtime.runtime_algorithm_mode)
      and ((.artifacts.control | keys | join(",")) ==
        "images,release_lock_sha256,runtime_identity,sha256,source_sha")
      and ((.artifacts.candidate | keys | join(",")) ==
        "images,release_lock_sha256,runtime_identity,sha256,source_sha")
    ' "$quality_file" >/dev/null \
    || {
      fail "Quality acceptance violates the exact frozen release contract."
      return 1
    }

  EXPECTED_CONTROL_ARTIFACT_SHA256="$(jq -er '.artifacts.control.sha256' "$quality_file")"
  EXPECTED_CANDIDATE_ARTIFACT_SHA256="$(jq -er '.artifacts.candidate.sha256' "$quality_file")"
  EXPECTED_CONTROL_SOURCE_SHA="$(jq -er '.artifacts.control.source_sha' "$quality_file")"
  EXPECTED_CONTROL_RELEASE_LOCK_SHA256="$(
    jq -er '.artifacts.control.release_lock_sha256' "$quality_file"
  )"
  EXPECTED_CANDIDATE_SOURCE_SHA="$(jq -er '.artifacts.candidate.source_sha' "$quality_file")"
  EXPECTED_CANDIDATE_RELEASE_LOCK_SHA256="$(
    jq -er '.artifacts.candidate.release_lock_sha256' "$quality_file"
  )"
  EXPECTED_CONTROL_IMAGES_JSON="$(jq -ec '.artifacts.control.images' "$quality_file")"
  EXPECTED_CONTROL_ROUTE_API_IMAGE_REF="$(
    jq -er '.artifacts.control.images.route_api.ref' "$quality_file"
  )"
  EXPECTED_CONTROL_ROUTE_WORKER_IMAGE_REF="$(
    jq -er '.artifacts.control.images.route_worker.ref' "$quality_file"
  )"
  EXPECTED_CONTROL_NOTIFICATION_SERVICE_IMAGE_REF="$(
    jq -er '.artifacts.control.images.notification_service.ref' "$quality_file"
  )"
  EXPECTED_CONTROL_FRONTEND_IMAGE_REF="$(
    jq -er '.artifacts.control.images.frontend.ref' "$quality_file"
  )"
  load_expected_runtime_identity "$quality_file" control EXPECTED_CONTROL || return 1
  load_expected_runtime_identity "$quality_file" candidate EXPECTED_CANDIDATE || return 1
}


psql_copy_sha256() {
  local env_file="$1"
  local database="$2"
  local sql="$3"
  local result
  result="$(
    compose_env "$env_file" exec -T postgres \
      psql --username "$POSTGRES_USER" --dbname "$database" \
        --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
        --command "$sql" | sha256sum
  )"
  result="${result%% *}"
  printf '%s' "$result" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Could not compute live production database identity."
  printf '%s\n' "$result"
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


effective_service_env_value() {
  local service="$1"
  local key="$2"
  local container_id line count value
  container_id="$(running_container_for_service "$service")"
  [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" = "true" ] \
    || fail "Live identity gate requires running service $service."
  line=""
  count=0
  while IFS= read -r value; do
    case "$value" in
      "${key}="*)
        line="${value#*=}"
        count=$((count + 1))
        ;;
    esac
  done < <(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id")
  [ "$count" -eq 1 ] || fail "Service $service does not expose exactly one effective $key."
  printf '%s\n' "$line"
}

assert_live_identity_value() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [ "$actual" != "$expected" ]; then
    fail "$message"
    return 1
  fi
}


verify_live_quality_identity() {
  local env_file="$1"
  local database="$2"
  local runtime_identity="$3"
  local database_fingerprint scenic_fingerprint scenic_version road_fingerprint
  local osrm_basename osrm_container osrm_image_id osrm_image_ref osrm_repository
  local osrm_config_cmd osrm_args expected_osrm_config_cmd expected_osrm_args
  local manifest_file manifest_temp manifest_metadata_file manifest_metadata_temp
  local manifest_hash service notification_runtime_profile comparison_file comparison_temp
  local actual_runtime_profile actual_algorithm_profile actual_algorithm_mode
  local actual_graph_warmup actual_anchor_schema actual_scenic actual_road_revision
  local actual_road_fingerprint actual_datasource_url actual_datasource_username expected_datasource_url
  local phase generated_at_utc evidence_image_tag evidence_source_sha
  local evidence_release_lock_sha256 artifact_sha256 artifact_source_sha
  local executed_algorithm_version executed_route_mode
  local job_algorithm_version
  local job_route_mode primary_route_mode
  select_expected_runtime_identity "$runtime_identity"
  comparison_file="${QUALITY_COMPARISON_FILE%.json}.${runtime_identity}.json"
  case "$runtime_identity" in
    control)
      phase="control-pre-drain"
      evidence_image_tag="sha-${EXPECTED_CONTROL_SOURCE_SHA}"
      evidence_source_sha="$EXPECTED_CONTROL_SOURCE_SHA"
      evidence_release_lock_sha256="$EXPECTED_CONTROL_RELEASE_LOCK_SHA256"
      artifact_sha256="$EXPECTED_CONTROL_ARTIFACT_SHA256"
      artifact_source_sha="$EXPECTED_CONTROL_SOURCE_SHA"
      executed_algorithm_version="${CONTROL_EXECUTED_ROUTE_ALGORITHM_VERSION:-}"
      executed_route_mode="${CONTROL_EXECUTED_ROUTE_MODE:-}"
      job_algorithm_version="${CONTROL_EXECUTED_JOB_ALGORITHM_VERSION:-}"
      job_route_mode="${CONTROL_EXECUTED_JOB_ROUTE_MODE:-}"
      primary_route_mode="${CONTROL_EXECUTED_PRIMARY_ROUTE_MODE:-}"
      ;;
    candidate)
      phase="candidate-pre-ingress"
      evidence_image_tag="sha-${EXPECTED_CANDIDATE_SOURCE_SHA}"
      evidence_source_sha="$EXPECTED_CANDIDATE_SOURCE_SHA"
      evidence_release_lock_sha256="$EXPECTED_CANDIDATE_RELEASE_LOCK_SHA256"
      artifact_sha256="$EXPECTED_CANDIDATE_ARTIFACT_SHA256"
      artifact_source_sha="$EXPECTED_CANDIDATE_SOURCE_SHA"
      executed_algorithm_version="${CANDIDATE_EXECUTED_ROUTE_ALGORITHM_VERSION:-}"
      executed_route_mode="${CANDIDATE_EXECUTED_ROUTE_MODE:-}"
      job_algorithm_version="${CANDIDATE_EXECUTED_JOB_ALGORITHM_VERSION:-}"
      job_route_mode="${CANDIDATE_EXECUTED_JOB_ROUTE_MODE:-}"
      primary_route_mode="${CANDIDATE_EXECUTED_PRIMARY_ROUTE_MODE:-}"
      ;;
  esac
  assert_live_identity_value "$executed_algorithm_version" "$EXPECTED_RUNTIME_ALGORITHM" \
    "Executed $runtime_identity synthetic route algorithm differs from its accepted runtime identity." \
    || return 1
  assert_live_identity_value "$executed_route_mode" "$EXPECTED_ALGORITHM_MODE" \
    "Executed $runtime_identity synthetic route mode differs from its accepted runtime identity." \
    || return 1
  assert_live_identity_value "$job_algorithm_version" "$EXPECTED_RUNTIME_ALGORITHM" \
    "Executed $runtime_identity route job algorithm differs from its accepted runtime identity." \
    || return 1
  assert_live_identity_value "$job_route_mode" "$EXPECTED_ALGORITHM_MODE" \
    "Executed $runtime_identity route job mode differs from its accepted runtime identity." \
    || return 1
  assert_live_identity_value "$primary_route_mode" "$EXPECTED_ALGORITHM_MODE" \
    "Executed $runtime_identity committed primary mode differs from its accepted runtime identity." \
    || return 1
  assert_live_identity_value "$primary_route_mode" "$job_route_mode" \
    "Executed $runtime_identity job request and committed primary route modes diverge." \
    || return 1
  assert_live_identity_value "$(get_env_var IMAGE_TAG "$env_file")" "$evidence_image_tag" \
    "Live $runtime_identity IMAGE_TAG differs from its accepted artifact source." \
    || return 1

  database_fingerprint="$(psql_copy_sha256 "$env_file" "$database" "
COPY (
  SELECT jsonb_build_array(installed_rank, version, description, type, script, checksum, success)::text
  FROM public.flyway_schema_history
  ORDER BY installed_rank
) TO STDOUT;
  ")"
  scenic_fingerprint="$(psql_copy_sha256 "$env_file" "$database" "
COPY (
  SELECT row_to_json(scenic_score_tiles)::text
  FROM public.scenic_score_tiles
  ORDER BY h3_index
) TO STDOUT;
  ")"
  scenic_version="$(psql_query_db "$env_file" "$database" \
    "SELECT DISTINCT btrim(scoring_version) FROM public.scenic_score_tiles;")"
  scenic_version="$(printf '%s' "$scenic_version" | tr -d '\r\n')"
  road_fingerprint="$(psql_copy_sha256 "$env_file" "$database" "
COPY (
  SELECT payload
  FROM (
    SELECT stable_identity_key,
           jsonb_build_array(
             stable_identity_key, osm_way_id,
             encode(ST_AsEWKB(ST_Normalize(geometry), 'XDR'), 'hex'),
             h3_tile_index, length_meters, speed_limit_kmh, road_type, surface,
             curvature, elevation_change
           )::text AS payload
    FROM public.road_segments
    WHERE stable_identity_key IS NOT NULL AND btrim(stable_identity_key) <> ''
  ) canonical_road_segments
  ORDER BY stable_identity_key COLLATE \"C\", payload COLLATE \"C\"
) TO STDOUT;
  ")"

  for service in route-api route-worker; do
    actual_runtime_profile="$(effective_service_env_value "$service" SPRING_PROFILES_ACTIVE)"
    actual_algorithm_profile="$(effective_service_env_value "$service" MOODRIDE_ALGORITHM_PROFILE)"
    actual_algorithm_mode="$(effective_service_env_value "$service" MOODRIDE_ALGORITHM_MODE)"
    actual_graph_warmup="$(effective_service_env_value "$service" MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED)"
    actual_anchor_schema="$(effective_service_env_value "$service" MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA)"
    actual_scenic="$(effective_service_env_value "$service" MOODRIDE_SCENIC_SCORING_VERSION)"
    actual_road_revision="$(effective_service_env_value "$service" MOODRIDE_ROAD_DATASET_REVISION)"
    actual_road_fingerprint="$(effective_service_env_value "$service" MOODRIDE_ROAD_DATASET_FINGERPRINT)"
    actual_datasource_url="$(effective_service_env_value "$service" SPRING_DATASOURCE_URL)"
    actual_datasource_username="$(effective_service_env_value "$service" SPRING_DATASOURCE_USERNAME)"
    expected_datasource_url="jdbc:postgresql://postgres:5432/${database}"
    if [ "$service" = "route-worker" ]; then
      expected_datasource_url="${expected_datasource_url}?reWriteBatchedInserts=true"
      assert_live_identity_value \
        "$(effective_service_env_value "$service" MOODRIDE_OSRM_BASE_URL)" \
        "http://osrm:5000" \
        "Live route-worker OSRM endpoint differs from the accepted compose identity." \
        || return 1
    fi
    assert_live_identity_value "$actual_runtime_profile" "$EXPECTED_RUNTIME_PROFILE" \
      "Live $service Spring profile differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_algorithm_profile" "$EXPECTED_ALGORITHM_PROFILE" \
      "Live $service cache-policy algorithm differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_algorithm_profile" "$EXPECTED_RUNTIME_ALGORITHM" \
      "Live $service runtime algorithm differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_algorithm_mode" "$EXPECTED_ALGORITHM_MODE" \
      "Live $service routing mode differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_graph_warmup" "$EXPECTED_GRAPH_WARMUP_ENABLED" \
      "Live $service graph-warmup policy differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_anchor_schema" "$EXPECTED_ROAD_ANCHOR_CACHE_SCHEMA" \
      "Live $service road-anchor cache schema differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_scenic" "$EXPECTED_SCENIC_SCORING_VERSION" \
      "Live $service scenic scoring identity differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_road_revision" \
      "$(get_env_var MOODRIDE_ROAD_DATASET_REVISION "$env_file")" \
      "Live $service road dataset revision differs from synchronized identity." \
      || return 1
    assert_live_identity_value "$actual_road_fingerprint" "$EXPECTED_ROAD_DATASET_FINGERPRINT" \
      "Live $service road dataset fingerprint differs from the $runtime_identity quality identity." \
      || return 1
    assert_live_identity_value "$actual_datasource_url" "$expected_datasource_url" \
      "Live $service datasource URL differs from the database being fingerprinted." \
      || return 1
    assert_live_identity_value "$actual_datasource_username" "$POSTGRES_USER" \
      "Live $service datasource username differs from the database gate identity." \
      || return 1
  done
  notification_runtime_profile="$(
    effective_service_env_value notification-service SPRING_PROFILES_ACTIVE
  )"
  assert_live_identity_value "$notification_runtime_profile" "$EXPECTED_RUNTIME_PROFILE" \
    "Live notification-service Spring profile differs from the $runtime_identity quality identity." \
    || return 1


  osrm_basename="$(get_env_var OSRM_DATASET_BASENAME "$env_file")"
  assert_live_identity_value "$osrm_basename" "$EXPECTED_OSRM_DATASET_BASENAME" \
    "Live OSRM dataset basename differs from the $runtime_identity quality identity." \
    || return 1
  osrm_container="$(running_container_for_service osrm)" || return 1
  assert_live_identity_value \
    "$(docker inspect --format '{{.State.Running}}' "$osrm_container")" "true" \
    "Live OSRM container is not running." || return 1
  osrm_image_id="$(docker inspect --format '{{.Image}}' "$osrm_container")" || return 1
  osrm_repository="${EXPECTED_OSRM_IMAGE_REF%@sha256:*}"
  osrm_image_ref="$(resolve_repo_digest "$osrm_image_id" "$osrm_repository")" || return 1
  assert_live_identity_value "$osrm_image_ref" "$EXPECTED_OSRM_IMAGE_REF" \
    "Live OSRM RepoDigest differs from the $runtime_identity quality identity." \
    || return 1
  assert_live_identity_value \
    "$(docker inspect --format '{{.Config.Image}}' "$osrm_container")" \
    "$EXPECTED_OSRM_IMAGE_REF" \
    "Live OSRM configured image differs from the $runtime_identity quality identity." \
    || return 1
  osrm_config_cmd="$(docker inspect --format '{{json .Config.Cmd}}' "$osrm_container")"
  osrm_args="$(docker inspect --format '{{json .Args}}' "$osrm_container")"
  expected_osrm_config_cmd="$(jq -cn --arg path "/data/${osrm_basename}.osrm" \
    '["osrm-routed", "--algorithm", "mld", $path]')"
  expected_osrm_args="$(jq -cn --arg path "/data/${osrm_basename}.osrm" \
    '["--algorithm", "mld", $path]')"
  assert_live_identity_value "$osrm_config_cmd" "$expected_osrm_config_cmd" \
    "Live OSRM Config.Cmd is not the exact configured MLD route command." \
    || return 1
  assert_live_identity_value "$osrm_args" "$expected_osrm_args" \
    "Live OSRM Args are not the exact configured MLD route arguments." \
    || return 1


  generated_at_utc="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  manifest_file="$MOODRIDE_DIR/.deploy/releases/${release_id}.${runtime_identity}.osrm-files.sha256"
  manifest_temp="${manifest_file}.${MAIN_PID:-$$}.tmp"
  manifest_metadata_file="${manifest_file}.metadata.json"
  manifest_metadata_temp="${manifest_metadata_file}.${MAIN_PID:-$$}.tmp"
  rm -f "$manifest_temp" "$manifest_metadata_temp"
  write_osrm_file_manifest "$MOODRIDE_DIR/data/osrm" "$osrm_basename" "$manifest_temp"
  durable_replace "$manifest_temp" "$manifest_file"
  manifest_hash="$(sha256sum "$manifest_file")"
  manifest_hash="${manifest_hash%% *}"

  jq -n \
    --arg attempt_id "$DEPLOYMENT_ATTEMPT_ID" \
    --arg phase "$phase" \
    --arg generated_at_utc "$generated_at_utc" \
    --arg image_tag "$evidence_image_tag" \
    --arg source_sha "$evidence_source_sha" \
    --arg release_lock_sha256 "$evidence_release_lock_sha256" \
    --arg quality_acceptance_sha256 "$QUALITY_ACCEPTANCE_SHA256" \
    --arg artifact_sha256 "$artifact_sha256" \
    --arg artifact_source_sha "$artifact_source_sha" \
    --arg runtime_identity "$runtime_identity" \
    --arg expected_runtime_algorithm "$EXPECTED_RUNTIME_ALGORITHM" \
    --arg expected_database "$EXPECTED_DATABASE_FINGERPRINT" \
    --arg actual_database "$database_fingerprint" \
    --arg expected_scenic_version "$EXPECTED_SCENIC_SCORING_VERSION" \
    --arg actual_scenic_version "$scenic_version" \
    --arg expected_scenic "$EXPECTED_SCENIC_DATASET_FINGERPRINT" \
    --arg actual_scenic "$scenic_fingerprint" \
    --arg expected_road "$EXPECTED_ROAD_DATASET_FINGERPRINT" \
    --arg actual_road "$road_fingerprint" \
    --arg expected_osrm_image "$EXPECTED_OSRM_IMAGE_REF" \
    --arg actual_osrm_image "$osrm_image_ref" \
    --arg expected_osrm_basename "$EXPECTED_OSRM_DATASET_BASENAME" \
    --arg actual_osrm_basename "$osrm_basename" \
    --arg expected_osrm_manifest "$EXPECTED_OSRM_FILE_MANIFEST_SHA256" \
    --arg actual_osrm_manifest "$manifest_hash" \
    --argjson osrm_config_cmd "$osrm_config_cmd" \
    --argjson osrm_args "$osrm_args" \
    --arg notification_runtime_profile "$notification_runtime_profile" \
    --arg runtime_profile "$actual_runtime_profile" \
    --arg configured_algorithm "$actual_algorithm_profile" \
    --arg configured_mode "$actual_algorithm_mode" \
    --arg expected_configured_mode "$EXPECTED_ALGORITHM_MODE" \
    --arg executed_algorithm_version "$executed_algorithm_version" \
    --arg executed_route_mode "$executed_route_mode" \
    --arg job_algorithm_version "$job_algorithm_version" \
    --arg job_route_mode "$job_route_mode" \
    --arg primary_route_mode "$primary_route_mode" \
    --arg graph_warmup "$actual_graph_warmup" \
    --arg anchor_schema "$actual_anchor_schema" \
    '{schema_version: 1, attempt_id: $attempt_id, phase: $phase,
      generated_at_utc: $generated_at_utc, image_tag: $image_tag,
      source_sha: $source_sha, release_lock_sha256: $release_lock_sha256,
      quality_acceptance_sha256: $quality_acceptance_sha256,
      artifact_sha256: $artifact_sha256, artifact_source_sha: $artifact_source_sha,
      osrm_file_manifest_sha256: $actual_osrm_manifest,
      runtime_identity: $runtime_identity,
      database: {expected: $expected_database, actual: $actual_database},
      scenic_version: {expected: $expected_scenic_version, actual: $actual_scenic_version},
      scenic: {expected: $expected_scenic, actual: $actual_scenic},
      road: {expected: $expected_road, actual: $actual_road},
      executed_route_identity: {
        algorithm: {
          expected: $expected_runtime_algorithm, actual: $executed_algorithm_version},
        mode: {expected: $expected_configured_mode, actual: $executed_route_mode},
        job: {algorithm_version: $job_algorithm_version, route_mode: $job_route_mode},
        primary: {route_mode: $primary_route_mode}},
      routing: {configured_spring_profile: $runtime_profile,
        configured_algorithm: {expected: $expected_runtime_algorithm, actual: $configured_algorithm},
        configured_mode: {expected: $expected_configured_mode, actual: $configured_mode},
        graph_warmup_enabled: $graph_warmup, road_anchor_cache_schema: $anchor_schema},
      notification: {spring_profile: $notification_runtime_profile},
      osrm: {image: {expected: $expected_osrm_image, actual: $actual_osrm_image},
        basename: {expected: $expected_osrm_basename, actual: $actual_osrm_basename},
        config_cmd: $osrm_config_cmd, args: $osrm_args,
        file_manifest_sha256: {expected: $expected_osrm_manifest, actual: $actual_osrm_manifest}}}' \
    > "${comparison_file}.${MAIN_PID:-$$}.tmp"
  comparison_temp="${comparison_file}.${MAIN_PID:-$$}.tmp"
  durable_replace "$comparison_temp" "$comparison_file"
  jq -n \
    --arg attempt_id "$DEPLOYMENT_ATTEMPT_ID" \
    --arg phase "$phase" \
    --arg generated_at_utc "$generated_at_utc" \
    --arg image_tag "$evidence_image_tag" \
    --arg source_sha "$evidence_source_sha" \
    --arg release_lock_sha256 "$evidence_release_lock_sha256" \
    --arg quality_acceptance_sha256 "$QUALITY_ACCEPTANCE_SHA256" \
    --arg artifact_sha256 "$artifact_sha256" \
    --arg artifact_source_sha "$artifact_source_sha" \
    --arg manifest_file "$(basename "$manifest_file")" \
    --arg osrm_file_manifest_sha256 "$manifest_hash" \
    '{schema_version: 1, evidence_type: "osrm-file-manifest",
      attempt_id: $attempt_id, phase: $phase, generated_at_utc: $generated_at_utc,
      image_tag: $image_tag, source_sha: $source_sha,
      release_lock_sha256: $release_lock_sha256,
      quality_acceptance_sha256: $quality_acceptance_sha256,
      artifact_sha256: $artifact_sha256, artifact_source_sha: $artifact_source_sha,
      manifest_file: $manifest_file,
      osrm_file_manifest_sha256: $osrm_file_manifest_sha256}' \
    > "$manifest_metadata_temp"
  durable_replace "$manifest_metadata_temp" "$manifest_metadata_file"

  assert_live_identity_value "$database_fingerprint" "$EXPECTED_DATABASE_FINGERPRINT" \
    "Live gate-routing Flyway identity differs from the $runtime_identity quality identity." \
    || return 1
  assert_live_identity_value "$scenic_version" "$EXPECTED_SCENIC_SCORING_VERSION" \
    "Live scenic scoring version differs from the $runtime_identity quality identity." \
    || return 1
  assert_live_identity_value "$scenic_fingerprint" "$EXPECTED_SCENIC_DATASET_FINGERPRINT" \
    "Live scenic content fingerprint differs from the $runtime_identity quality identity." \
    || return 1
  assert_live_identity_value "$road_fingerprint" "$EXPECTED_ROAD_DATASET_FINGERPRINT" \
    "Live road dataset fingerprint differs from the $runtime_identity quality identity." \
    || return 1
  assert_live_identity_value "$manifest_hash" "$EXPECTED_OSRM_FILE_MANIFEST_SHA256" \
    "Live OSRM canonical sidecar manifest differs from the $runtime_identity quality identity." \
    || return 1
  durable_copy "$comparison_file" \
    "$MOODRIDE_DIR/.deploy/last-quality-comparison-${runtime_identity}.json"
  durable_copy "$manifest_file" \
    "$MOODRIDE_DIR/.deploy/last-osrm-files-${runtime_identity}.sha256"
  durable_copy "$manifest_metadata_file" \
    "$MOODRIDE_DIR/.deploy/last-osrm-files-${runtime_identity}.metadata.json"
}

resolve_repo_digest() {
  local image="$1"
  local repository_ref="$2"
  local repo_digest candidate
  repo_digest=""
  while IFS= read -r candidate; do
    case "$candidate" in
      "${repository_ref}@sha256:"*)
        if [ -n "$repo_digest" ]; then
          fail "Image $image has multiple RepoDigests for $repository_ref."
          return 1
        fi
        repo_digest="$candidate"
        ;;
    esac
  done < <(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image")
  if [ -z "$repo_digest" ]; then
    fail "Image $image has no RepoDigest for $repository_ref."
    return 1
  fi
  if ! printf '%s' "${repo_digest##*@sha256:}" | grep -Eq '^[0-9a-f]{64}$'; then
    fail "Image $image returned an invalid RepoDigest."
    return 1
  fi
  printf '%s\n' "$repo_digest"
}
resolve_application_repo_digest() {
  local image="$1"
  local repository="$2"
  local repo_digest candidate suffix
  repo_digest=""
  while IFS= read -r candidate; do
    case "$candidate" in
      ghcr.io/*/"${repository}@sha256:"*)
        suffix="${candidate##*@sha256:}"
        printf '%s' "$suffix" | grep -Eq '^[0-9a-f]{64}$' || continue
        if [ -n "$repo_digest" ]; then
          fail "Image $image has multiple attributable RepoDigests for $repository."
          return 1
        fi
        repo_digest="$candidate"
        ;;
    esac
  done < <(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image")
  if [ -z "$repo_digest" ]; then
    fail "Image $image has no attributable GHCR RepoDigest for $repository."
    return 1
  fi
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
    *) fail "Unsupported release repository: $repository" ;;
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
    *) fail "Unsupported release repository: $repository" ;;
  esac
}
expected_control_image_ref() {
  case "$1" in
    moodride-route-api) printf '%s\n' "$EXPECTED_CONTROL_ROUTE_API_IMAGE_REF" ;;
    moodride-route-worker) printf '%s\n' "$EXPECTED_CONTROL_ROUTE_WORKER_IMAGE_REF" ;;
    moodride-notification-service)
      printf '%s\n' "$EXPECTED_CONTROL_NOTIFICATION_SERVICE_IMAGE_REF"
      ;;
    moodride-frontend) printf '%s\n' "$EXPECTED_CONTROL_FRONTEND_IMAGE_REF" ;;
    *) fail "Unsupported control artifact repository: $1" ;;
  esac
}

verify_running_control_artifact_identity() {
  local env_file="$1"
  local repository actual_ref expected_ref
  [ "$(get_env_var IMAGE_TAG "$env_file")" = "sha-${EXPECTED_CONTROL_SOURCE_SHA}" ] \
    || {
      fail "Running application revision does not equal the accepted control artifact source."
      return 1
    }
  printf '%s' "$EXPECTED_CONTROL_RELEASE_LOCK_SHA256" | grep -Eq '^[0-9a-f]{64}$' \
    || {
      fail "Control artifact release-lock checksum is invalid."
      return 1
    }
  for repository in moodride-route-api moodride-route-worker \
      moodride-notification-service moodride-frontend; do
    actual_ref="$(image_ref_for_repository "$env_file" "$repository")" || return 1
    expected_ref="$(expected_control_image_ref "$repository")" || return 1
    [ "$actual_ref" = "$expected_ref" ] \
      || {
        fail "Running $repository RepoDigest does not equal the accepted control artifact."
        return 1
      }
  done
  echo "Running application RepoDigests, revisions, and OCI source match the accepted control artifact."
}
verify_control_accepted_lock() {
  local env_file="$1"
  local control_tag lock_file actual_checksum
  control_tag="sha-${EXPECTED_CONTROL_SOURCE_SHA}"
  [ "$(get_env_var IMAGE_TAG "$env_file")" = "$control_tag" ] \
    || {
      fail "Running control source does not select its accepted release-lock."
      return 1
    }
  lock_file="$MOODRIDE_DIR/.deploy/releases/accepted-${control_tag}-${EXPECTED_CONTROL_RELEASE_LOCK_SHA256}.json"
  require_file "$lock_file" || return 1
  require_file "${lock_file}.sha256" || return 1
  actual_checksum="$(sha256sum "$lock_file")" || return 1
  actual_checksum="${actual_checksum%% *}"
  [ "$actual_checksum" = "$EXPECTED_CONTROL_RELEASE_LOCK_SHA256" ] \
    || {
      fail "Current accepted control release-lock bytes differ from quality acceptance."
      return 1
    }
  (
    cd "$(dirname "$lock_file")"
    sha256sum --check "$(basename "$lock_file").sha256"
  ) >/dev/null || return 1
  jq -e \
    --arg source_sha "$EXPECTED_CONTROL_SOURCE_SHA" \
    --arg image_tag "$control_tag" \
    --argjson expected_images "$EXPECTED_CONTROL_IMAGES_JSON" '
      .schema_version == 2
      and .source_sha == $source_sha
      and .image_tag == $image_tag
      and (.images
        | with_entries(.value = {
            ref: .value.ref,
            index_digest: .value.index_digest,
            revision: .value.revision
          })) == $expected_images
    ' "$lock_file" >/dev/null \
    || {
      fail "Current accepted control release-lock does not bind its artifact source and images."
      return 1
    }
  CURRENT_ACCEPTED_CONTROL_LOCK="$lock_file"
}




verify_candidate_image_revisions() {
  local env_file="$1"
  local tag="$2"
  local namespace expected_revision repository image_ref repository_ref tag_ref tag_digest revision source
  namespace="$(get_env_var GHCR_NAMESPACE "$env_file")"
  printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    || fail "Candidate GHCR_NAMESPACE is missing or invalid."
  expected_revision="${tag#sha-}"

  for repository in moodride-route-api moodride-route-worker moodride-notification-service moodride-frontend; do
    image_ref="$(image_ref_for_repository "$env_file" "$repository")"
    validate_image_reference "$namespace" "$repository" "$image_ref"
    repository_ref="ghcr.io/${namespace}/${repository}"
    tag_ref="${repository_ref}:${tag}"
    docker pull "$tag_ref" >/dev/null
    docker pull "$image_ref" >/dev/null
    tag_digest="$(resolve_repo_digest "$tag_ref" "$repository_ref")"
    [ "$tag_digest" = "$image_ref" ] \
      || fail "Immutable tag $tag_ref does not resolve to the accepted release digest."
    revision="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
      "$image_ref")"
    [ "$revision" = "$expected_revision" ] \
      || fail "Candidate image $repository does not carry the exact expected revision label."
    source="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.source" }}' \
      "$image_ref")"
    [ "$source" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
      || fail "Candidate image $repository does not carry the exact expected GitHub OCI source label."
  done
  echo "Candidate tag, digest pins, and revision labels match release $tag."
}

capture_running_image_refs() {
  local env_file="$1"
  local output_env="$2"
  local namespace service repository container_id image_id image_ref repository_ref
  local inferred_namespace revision source common_revision common_source running osrm_basename
  namespace="$(get_env_var GHCR_NAMESPACE "$env_file")"
  if is_template_placeholder "$namespace" \
      || ! printf '%s' "$namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$'; then
    namespace=""
  fi
  common_revision=""
  common_source=""

  for service in route-api route-worker notification-service frontend; do
    repository="moodride-${service}"
    container_id="$(running_container_for_service "$service")"
    [ -n "$container_id" ] || fail "Cannot snapshot RepoDigest: service $service has no container."
    running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
    [ "$running" = "true" ] || fail "Cannot snapshot rollback state: service $service is not running."
    image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
    [ -n "$image_id" ] || fail "Cannot snapshot image ID for service $service."
    if [ -z "$namespace" ]; then
      image_ref="$(resolve_application_repo_digest "$image_id" "$repository")"
      inferred_namespace="${image_ref#ghcr.io/}"
      inferred_namespace="${inferred_namespace%%/*}"
      printf '%s' "$inferred_namespace" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
        || fail "Could not infer a valid GHCR namespace from the running $service image."
      namespace="$inferred_namespace"
    else
      repository_ref="ghcr.io/${namespace}/${repository}"
      image_ref="$(resolve_repo_digest "$image_id" "$repository_ref")"
    fi
    validate_image_reference "$namespace" "$repository" "$image_ref"
    revision="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image_id")"
    printf '%s' "$revision" | grep -Eq '^[0-9a-f]{40}$' \
      || fail "Running image for $service lacks an exact 40-character OCI revision."
    source="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.source" }}' "$image_id")"
    if [ -n "${EXPECTED_GITHUB_SOURCE_URL:-}" ]; then
      [ "$source" = "$EXPECTED_GITHUB_SOURCE_URL" ] \
        || fail "Running image for $service does not match the expected GitHub OCI source."
    elif [ "${DEPLOY_LIBRARY_ONLY:-0}" != "1" ]; then
      fail "EXPECTED_GITHUB_SOURCE_URL is required to attribute running application images."
    fi
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
  set_env_var GHCR_NAMESPACE "$namespace" "$output_env"

  service="osrm"
  container_id="$(running_container_for_service "$service")"
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] || fail "Cannot snapshot rollback state: OSRM is not running."
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  image_ref="$(resolve_repo_digest "$image_id" "ghcr.io/project-osrm/osrm-backend")"
  printf '%s' "$image_ref" \
    | grep -Eq '^ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}$' \
    || fail "Running OSRM image is not attributable to its digest-pinned repository."
  set_env_var OSRM_IMAGE_REF "$image_ref" "$output_env"
  osrm_basename="$(get_env_var OSRM_DATASET_BASENAME "$output_env")"
  if is_template_placeholder "$osrm_basename" \
      || ! printf '%s' "$osrm_basename" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$'; then
    osrm_basename=""
    while IFS= read -r command_arg; do
      case "$command_arg" in
        /data/*.osrm)
          [ -z "$osrm_basename" ] \
            || fail "Running OSRM Config.Cmd names multiple dataset routes."
          command_arg="${command_arg##*/}"
          osrm_basename="${command_arg%.osrm}"
          ;;
      esac
    done < <(docker inspect --format '{{range .Config.Cmd}}{{println .}}{{end}}' "$container_id")
    printf '%s' "$osrm_basename" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
      || fail "Could not raw-inspect the running OSRM dataset basename."
    set_env_var OSRM_DATASET_BASENAME "$osrm_basename" "$output_env"
  fi
  set_env_var IMAGE_TAG "sha-${common_revision}" "$output_env"
  echo "Snapshotted exact running RepoDigests and source revision for rollback."
}
capture_running_caddyfile_path() {
  local output_env="$1"
  local expected_running_path="$2"
  local selected_bundle="$3"
  local container_id image_id image_ref running mount_source candidate count
  container_id="$(running_container_for_service caddy)"
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] || fail "Cannot snapshot rollback state: Caddy is not running."
  image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
  image_ref="$(resolve_repo_digest "$image_id" caddy)"
  printf '%s' "$image_ref" | grep -Eq '^caddy@sha256:[0-9a-f]{64}$' \
    || fail "Running Caddy image is not attributable to the official repository digest."
  set_env_var CADDY_IMAGE_REF "$image_ref" "$output_env"
  mount_source=""
  count=0
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    mount_source="$candidate"
    count=$((count + 1))
  done < <(docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/etc/caddy/Caddyfile"}}{{println .Source}}{{end}}{{end}}' \
    "$container_id")
  [ "$count" -eq 1 ] || fail "Expected one running Caddyfile mount, found $count."
  case "$mount_source" in
    /*) ;;
    *) fail "Running Caddyfile mount source is not absolute." ;;
  esac
  [ "$(readlink -f "$mount_source")" = "$(readlink -f "$expected_running_path")" ] \
    || fail "Running Caddyfile mount does not match the selected previous control plane."
  set_env_var CADDYFILE_PATH "$selected_bundle/Caddyfile" "$output_env"
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

save_image_lock() {
  local env_file="$1"
  local tag="$2"
  local destination="$3"
  local temp="${destination}.tmp"
  local manifest_temp="${destination}.osrm-files.tmp"
  local repository image_ref osrm_basename osrm_manifest_sha checksum
  osrm_basename="$(get_env_var OSRM_DATASET_BASENAME "$env_file")"
  write_osrm_file_manifest "$MOODRIDE_DIR/data/osrm" "$osrm_basename" "$manifest_temp"
  osrm_manifest_sha="$(sha256sum "$manifest_temp")"
  osrm_manifest_sha="${osrm_manifest_sha%% *}"
  rm -f "$manifest_temp"
  {
    printf 'IMAGE_TAG=%s\n' "$tag"
    printf 'GHCR_NAMESPACE=%s\n' "$(get_env_var GHCR_NAMESPACE "$env_file")"
    for repository in moodride-route-api moodride-route-worker moodride-notification-service moodride-frontend; do
      image_ref="$(image_ref_for_repository "$env_file" "$repository")"
      case "$repository" in
        moodride-route-api) printf 'ROUTE_API_IMAGE_REF=%s\n' "$image_ref" ;;
        moodride-route-worker) printf 'ROUTE_WORKER_IMAGE_REF=%s\n' "$image_ref" ;;
        moodride-notification-service) printf 'NOTIFICATION_SERVICE_IMAGE_REF=%s\n' "$image_ref" ;;
        moodride-frontend) printf 'FRONTEND_IMAGE_REF=%s\n' "$image_ref" ;;
      esac
    done
    printf 'OSRM_IMAGE_REF=%s\n' "$(get_env_var OSRM_IMAGE_REF "$env_file")"
    printf 'OSRM_DATASET_BASENAME=%s\n' "$osrm_basename"
    printf 'OSRM_FILE_MANIFEST_SHA256=%s\n' "$osrm_manifest_sha"
    printf 'CADDY_IMAGE_REF=%s\n' "$(get_env_var CADDY_IMAGE_REF "$env_file")"
  } > "$temp"
  chmod 600 "$temp"
  if [ -e "$destination" ]; then
    if cmp -s "$destination" "$temp"; then
      rm -f "$temp"
    else
      rm -f "$temp"
      fail "Immutable image lock already exists with different contents: $destination"
    fi
  else
    durable_replace "$temp" "$destination"
  fi
  sync -f "$destination"
  checksum="$(sha256sum "$destination")"
  checksum="${checksum%% *}"
  printf '%s  %s\n' "$checksum" "$(basename "$destination")" > "${destination}.sha256.tmp"
  durable_replace "${destination}.sha256.tmp" "${destination}.sha256"
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
    || fail "Cutover refused: $active_count active route jobs remain after consumers stopped."
  [ "$terminal_count" -eq 0 ] \
    || fail "Cutover refused: $terminal_count undelivered V41 terminal events remain after consumers stopped."
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

submit_synthetic_route_job() {
  local env_file="$1"
  local payload response job_id
  payload="{\"userId\":\"${SYNTHETIC_USER_ID}\",\"lat\":45.9636,\"lng\":-66.6431,\"timeBudgetMinutes\":30,\"vibes\":[\"countryside\"],\"routeMode\":\"drive\"}"

  if ! response="$(compose_env "$env_file" exec -T route-api \
      wget -q -T 30 -O - --header='Content-Type: application/json' \
        --post-data="$payload" http://127.0.0.1:8080/api/routes 2>/dev/null)"; then
    fail "Synthetic route request was not accepted by the internal route-api."
    return 1
  fi
  if ! job_id="$(printf '%s' "$response" | jq -er '.jobId | strings')"; then
    fail "Synthetic route response did not contain a string jobId."
    return 1
  fi
  if ! printf '%s' "$job_id" | grep -Eq '^[0-9a-fA-F-]{36}$'; then
    fail "Synthetic route response did not contain a UUID jobId."
    return 1
  fi
  printf '%s\n' "$job_id"
}

delete_synthetic_route_job() {
  local env_file="$1"
  local job_id="$2"
  local deleted_job_id
  deleted_job_id="$(psql_query "$env_file" \
    "DELETE FROM route_jobs
     WHERE id = '${job_id}'::uuid
       AND user_id = '${SYNTHETIC_USER_ID}'::uuid
     RETURNING id;")"
  deleted_job_id="$(printf '%s' "$deleted_job_id" | tr -d '[:space:]')"
  [ "$deleted_job_id" = "$job_id" ] \
    || {
      fail "Synthetic route cleanup did not delete exactly its reserved-user job."
      return 1
    }
}

wait_for_synthetic_route_job() {
  local env_file="$1"
  local lifecycle="$2"
  local job_id="$3"
  local job_algorithm_output_variable="$4"
  local job_mode_output_variable="$5"
  local primary_mode_output_variable="$6"
  local status start_ts now saw_primary primary_ready_recorded
  local terminal_delivery_count persisted_identity
  local observed_job_algorithm observed_job_mode observed_primary_mode extra

  saw_primary=0
  start_ts="$(date +%s)"
  while true; do
    status="$(psql_query "$env_file" \
      "SELECT status FROM route_jobs
       WHERE id = '${job_id}'::uuid
         AND user_id = '${SYNTHETIC_USER_ID}'::uuid;")"
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
        return 1
        ;;
      QUEUED|PROCESSING)
        ;;
      *)
        fail "Synthetic route job returned unexpected status: $status"
        return 1
        ;;
    esac

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "$SYNTHETIC_JOB_TIMEOUT_SECONDS" ]; then
      fail "Synthetic route job timed out in status $status."
      return 1
    fi
    sleep 1
  done

  if [ "$saw_primary" -ne 1 ] && [ "$lifecycle" = "v41" ]; then
    primary_ready_recorded="$(psql_query "$env_file" \
      "SELECT CASE WHEN primary_ready_at IS NOT NULL THEN 1 ELSE 0 END
       FROM route_jobs
       WHERE id = '${job_id}'::uuid
         AND user_id = '${SYNTHETIC_USER_ID}'::uuid;")"
    primary_ready_recorded="$(printf '%s' "$primary_ready_recorded" | tr -d '[:space:]')"
    [ "$primary_ready_recorded" = "1" ] && saw_primary=1
  fi
  if [ "$lifecycle" = "v41" ] && [ "$saw_primary" -ne 1 ]; then
    fail "Synthetic route job completed without proving the PRIMARY_READY transition."
    return 1
  fi

  if [ "$lifecycle" = "v41" ]; then
    start_ts="$(date +%s)"
    while true; do
      terminal_delivery_count="$(psql_query "$env_file" \
        "SELECT COUNT(*) FROM route_job_terminal_events
         WHERE job_id = '${job_id}'::uuid
           AND event_type = 'COMPLETION'
           AND terminal_status = 'COMPLETED'
           AND delivered_at IS NOT NULL;")"
      terminal_delivery_count="$(
        printf '%s' "$terminal_delivery_count" | tr -d '[:space:]'
      )"
      case "$terminal_delivery_count" in
        ''|*[!0-9]*)
          fail "Synthetic route smoke could not verify V41 terminal delivery."
          return 1
          ;;
      esac
      if [ "$terminal_delivery_count" -eq 1 ]; then
        break
      fi
      if [ "$terminal_delivery_count" -ne 0 ]; then
        fail "Synthetic route smoke found duplicate V41 completion deliveries."
        return 1
      fi
      now="$(date +%s)"
      if [ $((now - start_ts)) -ge "$SYNTHETIC_JOB_TIMEOUT_SECONDS" ]; then
        fail "Synthetic route completion was not durably delivered through the V41 outbox."
        return 1
      fi
      sleep 1
    done
  fi

  persisted_identity="$(psql_query "$env_file" \
    "SELECT j.algorithm_version, lower(j.route_mode), lower(r.route_mode)
     FROM route_jobs j
     JOIN routes r ON r.id = j.route_id AND r.job_id = j.id
     WHERE j.id = '${job_id}'::uuid
       AND j.user_id = '${SYNTHETIC_USER_ID}'::uuid;")"
  persisted_identity="$(printf '%s' "$persisted_identity" | tr -d '\r\n')"
  IFS='|' read -r observed_job_algorithm observed_job_mode \
    observed_primary_mode extra <<< "$persisted_identity"
  if [ -z "$observed_job_algorithm" ] || [ -z "$observed_job_mode" ] \
      || [ -z "$observed_primary_mode" ] || [ -n "${extra:-}" ]; then
    fail "Synthetic route job did not expose an unambiguous committed-primary algorithm/mode identity."
    return 1
  fi
  printf -v "$job_algorithm_output_variable" '%s' "$observed_job_algorithm"
  printf -v "$job_mode_output_variable" '%s' "$observed_job_mode"
  printf -v "$primary_mode_output_variable" '%s' "$observed_primary_mode"
}

record_executed_route_identity() {
  local runtime_identity="$1"
  local job_algorithm_version="$2"
  local job_route_mode="$3"
  local primary_route_mode="$4"
  case "$runtime_identity" in
    control)
      CONTROL_EXECUTED_ROUTE_ALGORITHM_VERSION="$job_algorithm_version"
      CONTROL_EXECUTED_ROUTE_MODE="$primary_route_mode"
      CONTROL_EXECUTED_JOB_ALGORITHM_VERSION="$job_algorithm_version"
      CONTROL_EXECUTED_JOB_ROUTE_MODE="$job_route_mode"
      CONTROL_EXECUTED_PRIMARY_ROUTE_MODE="$primary_route_mode"
      ;;
    candidate)
      CANDIDATE_EXECUTED_ROUTE_ALGORITHM_VERSION="$job_algorithm_version"
      CANDIDATE_EXECUTED_ROUTE_MODE="$primary_route_mode"
      CANDIDATE_EXECUTED_JOB_ALGORITHM_VERSION="$job_algorithm_version"
      CANDIDATE_EXECUTED_JOB_ROUTE_MODE="$job_route_mode"
      CANDIDATE_EXECUTED_PRIMARY_ROUTE_MODE="$primary_route_mode"
      ;;
    *)
      fail "Unsupported executed route runtime identity: $runtime_identity"
      return 1
      ;;
  esac
}

run_synthetic_route_smoke() {
  local env_file="$1"
  local lifecycle="$2"
  local runtime_identity="$3"
  local job_id job_algorithm_version job_route_mode primary_route_mode
  local expected_algorithm expected_mode
  select_expected_runtime_identity "$runtime_identity"
  expected_algorithm="$EXPECTED_RUNTIME_ALGORITHM"
  expected_mode="$EXPECTED_ALGORITHM_MODE"

  job_id="$(submit_synthetic_route_job "$env_file")" || return 1
  if ! wait_for_synthetic_route_job "$env_file" "$lifecycle" "$job_id" \
      job_algorithm_version job_route_mode primary_route_mode; then
    delete_synthetic_route_job "$env_file" "$job_id" || return 1
    return 1
  fi
  delete_synthetic_route_job "$env_file" "$job_id" || return 1
  assert_live_identity_value "$job_algorithm_version" "$expected_algorithm" \
    "Persisted synthetic route job algorithm differs from the $runtime_identity runtime identity." \
    || return 1
  assert_live_identity_value "$job_route_mode" "$expected_mode" \
    "Persisted synthetic route job mode differs from the $runtime_identity runtime identity." \
    || return 1
  assert_live_identity_value "$primary_route_mode" "$expected_mode" \
    "Committed primary route mode differs from the $runtime_identity runtime identity." \
    || return 1
  assert_live_identity_value "$primary_route_mode" "$job_route_mode" \
    "Synthetic route job request and committed primary route modes diverge." \
    || return 1
  record_executed_route_identity "$runtime_identity" \
    "$job_algorithm_version" "$job_route_mode" "$primary_route_mode"

  if [ "$lifecycle" = "v41" ]; then
    echo "Candidate synthetic route proved its committed-primary algorithm/mode, PRIMARY_READY, COMPLETED, and durable terminal delivery; its job was cleaned up."
  else
    echo "Control synthetic route proved its committed-primary algorithm/mode and completed route; its job was cleaned up."
  fi
}

run_control_route_identity_probe() {
  run_synthetic_route_smoke "$1" control control
}

verify_service_running() {
  local env_file="$1"
  local service="$2"
  local container_id running
  container_id="$(compose_env "$env_file" ps -q "$service")"
  [ -n "$container_id" ] || fail "Service $service has no container after cutover."
  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  [ "$running" = "true" ] || fail "Service $service is not running after cutover."
}


verify_forward_schema() {
  local result
  validate_release_invariants "$ENV_FILE" "$POSTGRES_DB"
  result="$(psql_query "$ENV_FILE" "
    SELECT CASE WHEN
      (SELECT COUNT(*) FROM flyway_schema_history
       WHERE version = '39' AND description = 'add route job lifecycle fencing'
         AND checksum = -2068215762 AND success IS TRUE) = 1
      AND (SELECT COUNT(*) FROM flyway_schema_history
       WHERE version = '40' AND description = 'add route job dispatch outbox'
         AND checksum = -1969470883 AND success IS TRUE) = 1
      AND (SELECT COUNT(*) FROM flyway_schema_history
       WHERE version = '41' AND description = 'add route job terminal event outbox'
         AND checksum = -819117119 AND success IS TRUE) = 1
      THEN 'schema-v41-ok' ELSE 'schema-v41-divergent' END;
  ")"
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  [ "$result" = "schema-v41-ok" ] \
    || fail "Candidate V39/V40/V41 description/checksum lineage is divergent."
}

restore_predeploy_release() {
  echo "Candidate cutover failed; stopping every candidate application service." >&2
  compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend >/dev/null 2>&1 || true
  if [ -n "${PREVIOUS_CONTROL_BUNDLE:-}" ] \
      && [ -f "$PREVIOUS_CONTROL_BUNDLE/docker-compose.prod.yml" ]; then
    select_previous_control_bundle
  fi

  if ! promote_validated_recovery_database "$ENV_FILE" "$RECOVERY_DATABASE" \
      "$RECOVERY_QUARANTINE_DATABASE" "$PREDEPLOY_BACKUP" \
      "$PREDEPLOY_HISTORY" "$PREDEPLOY_CATALOG"; then
    echo "Automatic recovery could not promote the validated replacement; previous images were not started." >&2
    return 1
  fi

  durable_copy "$PREDEPLOY_ENV" "$ENV_FILE" || return 1
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
     || ! run_internal_http_healthcheck "Restored WebSocket handshake" "$ENV_FILE" notification-service \
          http://127.0.0.1:8084/ws/info "$HEALTHCHECK_TIMEOUT_SECONDS"; then
    compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend >/dev/null 2>&1 || true
    return 1
  fi
  if [ "${PREDEPLOY_LAST_RELEASE_PRESENT:-0}" -eq 1 ]; then
    durable_copy "$PREDEPLOY_LAST_RELEASE" "$MOODRIDE_DIR/.deploy/last-release" || return 1
  else
    rm -f "$MOODRIDE_DIR/.deploy/last-release" || return 1
    sync -f "$MOODRIDE_DIR/.deploy" || return 1
  fi
  switch_control_bundle_pointer "$PREVIOUS_CONTROL_BUNDLE" || return 1
  CURRENT_POINTER_SWITCHED=0

  if ! compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy \
     || ! verify_service_running "$ENV_FILE" caddy \
     || ! verify_running_caddy_image "$ENV_FILE" \
     || ! run_http_healthcheck "Restored API" "$API_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS" \
     || ! run_http_healthcheck "Restored frontend" "$FRONTEND_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS" \
     || ! run_http_healthcheck "Restored WebSocket handshake" "$WS_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"; then
    compose_env "$ENV_FILE" stop caddy route-api route-worker notification-service frontend >/dev/null 2>&1 || true
    return 1
  fi
  return 0
}

cleanup() {
  if [ "${CUTOVER_LOCK_HELD:-0}" -eq 1 ]; then
    flock -u 9 >/dev/null 2>&1 || true
    exec 9>&-
    CUTOVER_LOCK_HELD=0
  fi
}

deployment_error() {
  local status="$1"
  if [ "${BASHPID:-$$}" != "${MAIN_PID:-$$}" ]; then
    return "$status"
  fi
  trap - ERR INT TERM
  set +e
  if [ "${RECOVERY_ENABLED:-0}" -eq 1 ]; then
    if restore_predeploy_release; then
      echo "The validated replacement database and exact previous image digests were restored; failed candidate data is quarantined at $RECOVERY_QUARANTINE_DATABASE." >&2
    else
      echo "AUTOMATIC RECOVERY FAILED. Application consumers remain stopped; current and recovery databases were preserved for operator recovery." >&2
    fi
  elif [ "${FAIL_CLOSED_ON_ERROR:-0}" -eq 1 ]; then
    compose_env "$ENV_FILE" stop caddy >/dev/null 2>&1 || true
    compose_env "$ENV_FILE" stop route-api route-worker notification-service frontend >/dev/null 2>&1 || true
    echo "Post-switch verification failed. Ingress and application consumers are stopped; the coordinated candidate schema is retained because public writes may have begun." >&2
  else
    echo "Deployment stopped before candidate startup. Ingress remains stopped if the drain had begun." >&2
  fi
  exit "$status"
}

if [ "${DEPLOY_LIBRARY_ONLY:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi

RELEASE_LOCK=""
RELEASE_LOCK_SHA256=""
QUALITY_ACCEPTANCE=""
QUALITY_ACCEPTANCE_SHA256=""
IMAGE_TAG=""
GHCR_NAMESPACE=""
ROUTE_API_IMAGE_REF=""
ROUTE_WORKER_IMAGE_REF=""
NOTIFICATION_SERVICE_IMAGE_REF=""
FRONTEND_IMAGE_REF=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --release-lock)
      [ "$#" -ge 2 ] || fail "--release-lock requires a value."
      RELEASE_LOCK="$2"
      shift 2
      ;;
    --release-lock-sha256)
      [ "$#" -ge 2 ] || fail "--release-lock-sha256 requires a value."
      RELEASE_LOCK_SHA256="$2"
      shift 2
      ;;
    --quality-acceptance)
      [ "$#" -ge 2 ] || fail "--quality-acceptance requires a value."
      QUALITY_ACCEPTANCE="$2"
      shift 2
      ;;
    --quality-acceptance-sha256)
      [ "$#" -ge 2 ] || fail "--quality-acceptance-sha256 requires a value."
      QUALITY_ACCEPTANCE_SHA256="$2"
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

[ -n "$RELEASE_LOCK" ] || fail "--release-lock is required."
[ -n "$RELEASE_LOCK_SHA256" ] || fail "--release-lock-sha256 is required."
[ -n "$QUALITY_ACCEPTANCE" ] || fail "--quality-acceptance is required."
[ -n "$QUALITY_ACCEPTANCE_SHA256" ] || fail "--quality-acceptance-sha256 is required."

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
CONTROL_BUNDLE=${CONTROL_BUNDLE:-"$(cd -- "$SCRIPT_DIR/../.." && pwd)"}
COMPOSE_FILE=${COMPOSE_FILE:-$CONTROL_BUNDLE/docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}
BACKUP_DIR=${BACKUP_DIR:-$MOODRIDE_DIR/.deploy/db-backups}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-1800}
DRAIN_POLL_SECONDS=${DRAIN_POLL_SECONDS:-5}
HEALTHCHECK_TIMEOUT_SECONDS=${HEALTHCHECK_TIMEOUT_SECONDS:-360}
SYNTHETIC_JOB_TIMEOUT_SECONDS=${SYNTHETIC_JOB_TIMEOUT_SECONDS:-600}
SYNTHETIC_USER_ID=00000000-0000-0000-0000-000000000037
API_HEALTHCHECK_URL=${API_HEALTHCHECK_URL:-https://usewayward.app/api/scenic-regions?lat=45.94\&lng=-66.63\&radius=1}
FRONTEND_HEALTHCHECK_URL=${FRONTEND_HEALTHCHECK_URL:-https://usewayward.app/}
WS_HEALTHCHECK_URL=${WS_HEALTHCHECK_URL:-https://usewayward.app/ws/info}
DEPLOYMENT_ATTEMPT_ID=${DEPLOYMENT_ATTEMPT_ID:-}
printf '%s' "$DEPLOYMENT_ATTEMPT_ID" \
  | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' \
  || fail "DEPLOYMENT_ATTEMPT_ID must be a nonblank normalized workflow run/attempt identity."
EXPECTED_GITHUB_SOURCE_URL=${EXPECTED_GITHUB_SOURCE_URL:-}
printf '%s' "$EXPECTED_GITHUB_SOURCE_URL" \
  | grep -Eq '^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' \
  || fail "EXPECTED_GITHUB_SOURCE_URL must be the exact release repository URL."

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
case "$CONTROL_BUNDLE" in
  /*) ;;
  *) fail "CONTROL_BUNDLE must be an absolute checksum-versioned bundle path." ;;
esac
require_file "$COMPOSE_FILE"
require_file "$ENV_FILE"
require_file "$SCRIPT_DIR/database_recovery.sh"
load_release_lock "$RELEASE_LOCK" "$RELEASE_LOCK_SHA256"
load_quality_acceptance "$QUALITY_ACCEPTANCE" "$QUALITY_ACCEPTANCE_SHA256"
CONTROL_BUNDLE_NAME="$(basename "$CONTROL_BUNDLE")"
CONTROL_BUNDLE_MANIFEST_COMPONENT="${CONTROL_BUNDLE_NAME##*-}"
case "$CONTROL_BUNDLE_NAME" in
  "${IMAGE_TAG}-${QUALITY_ACCEPTANCE_SHA256}-"*) ;;
  *) fail "Control bundle path is not bound to source SHA and quality acceptance." ;;
esac
[ "$CONTROL_BUNDLE_NAME" = \
    "${IMAGE_TAG}-${QUALITY_ACCEPTANCE_SHA256}-${CONTROL_BUNDLE_MANIFEST_COMPONENT}" ] \
  || fail "Control bundle path has unexpected components beyond source, quality, and manifest."
printf '%s' "$CONTROL_BUNDLE_MANIFEST_COMPONENT" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "Control bundle path does not end in a lowercase sha256 manifest digest."
verify_control_bundle "$CONTROL_BUNDLE"
CANDIDATE_COMPOSE_FILE="$CONTROL_BUNDLE/docker-compose.prod.yml"
CANDIDATE_CADDYFILE_PATH="$CONTROL_BUNDLE/Caddyfile"
case "$BACKUP_DIR" in
  /*) ;;
  *) fail "BACKUP_DIR must be an absolute host path outside Docker volumes." ;;
esac
case "$BACKUP_DIR" in
  /var/lib/docker/volumes|/var/lib/docker/volumes/*)
    fail "BACKUP_DIR must not be inside Docker managed volumes."
    ;;
esac

mkdir -p .deploy/releases .deploy/image-locks .deploy/bundles "$BACKUP_DIR"
MAIN_PID="${BASHPID:-$$}"
CUTOVER_LOCK_HELD=0
exec 9>"$MOODRIDE_DIR/.deploy/prod-cutover.lock"
if ! flock -n 9; then
  fail "Another production cutover holds $MOODRIDE_DIR/.deploy/prod-cutover.lock."
fi
CUTOVER_LOCK_HELD=1
clear_stale_runtime_evidence
trap cleanup EXIT
RECOVERY_ENABLED=0
FAIL_CLOSED_ON_ERROR=0
CURRENT_POINTER_SWITCHED=0

ACCEPTED_LOCK="$MOODRIDE_DIR/.deploy/releases/accepted-${IMAGE_TAG}-${RELEASE_LOCK_SHA256}.json"
if [ -e "$ACCEPTED_LOCK" ]; then
  cmp -s "$RELEASE_LOCK" "$ACCEPTED_LOCK" \
    || fail "Persisted accepted release-lock bytes conflict with the checksum-qualified path."
  sync -f "$ACCEPTED_LOCK"
else
  cp -p "$RELEASE_LOCK" "${ACCEPTED_LOCK}.${MAIN_PID}.tmp"
  chmod 600 "${ACCEPTED_LOCK}.${MAIN_PID}.tmp"
  durable_replace "${ACCEPTED_LOCK}.${MAIN_PID}.tmp" "$ACCEPTED_LOCK"
fi
printf '%s  %s\n' "$RELEASE_LOCK_SHA256" "$(basename "$ACCEPTED_LOCK")" \
  > "${ACCEPTED_LOCK}.sha256.tmp"
durable_replace "${ACCEPTED_LOCK}.sha256.tmp" "${ACCEPTED_LOCK}.sha256"

trap 'deployment_error $?' ERR
trap 'deployment_error 130' INT
trap 'deployment_error 143' TERM
ensure_analytics_hash_secret "$ENV_FILE"
sync -f "$ENV_FILE"
sync -f "$(dirname "$ENV_FILE")"

PREDEPLOY_TAG="$(get_env_var IMAGE_TAG "$ENV_FILE")"
[ -n "$PREDEPLOY_TAG" ] || fail "Previous IMAGE_TAG is missing from $ENV_FILE."
printf '%s' "$PREDEPLOY_TAG" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$' \
  || fail "Previous IMAGE_TAG is not a valid Docker tag."
[ "$IMAGE_TAG" != "$PREDEPLOY_TAG" ] \
  || fail "Candidate IMAGE_TAG equals the currently configured tag; no cutover is required."
POSTGRES_USER="$(get_env_var POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(get_env_var POSTGRES_DB "$ENV_FILE")"
[ -n "$POSTGRES_USER" ] || fail "POSTGRES_USER is missing from $ENV_FILE."
[ -n "$POSTGRES_DB" ] || fail "POSTGRES_DB is missing from $ENV_FILE."
reject_template_placeholder POSTGRES_USER "$POSTGRES_USER"
reject_template_placeholder POSTGRES_DB "$POSTGRES_DB"

release_id="$(date -u +'%Y%m%dT%H%M%SZ')-${IMAGE_TAG}"
QUALITY_COMPARISON_FILE="$MOODRIDE_DIR/.deploy/releases/${release_id}.quality-comparison.json"
PREDEPLOY_ENV="$MOODRIDE_DIR/.deploy/releases/${release_id}.env.backup"
CANDIDATE_ENV="$MOODRIDE_DIR/.deploy/releases/${release_id}.candidate.env"
PREDEPLOY_BACKUP="$BACKUP_DIR/${release_id}.predeploy.dump"
PREDEPLOY_HISTORY="$BACKUP_DIR/${release_id}.predeploy.flyway-history"
PREDEPLOY_CATALOG="$BACKUP_DIR/${release_id}.predeploy.catalog"
PREVIOUS_IMAGE_LOCK="$MOODRIDE_DIR/.deploy/releases/${release_id}.previous-images.env"
PREVIOUS_CONTROL_BUNDLE_FILE="$MOODRIDE_DIR/.deploy/releases/${release_id}.previous-control-bundle"
PREDEPLOY_LAST_RELEASE="$MOODRIDE_DIR/.deploy/releases/${release_id}.last-release.backup"
PREDEPLOY_LAST_RELEASE_PRESENT=0
if [ -f "$MOODRIDE_DIR/.deploy/last-release" ]; then
  durable_copy "$MOODRIDE_DIR/.deploy/last-release" "$PREDEPLOY_LAST_RELEASE"
  PREDEPLOY_LAST_RELEASE_PRESENT=1
fi
RECOVERY_DATABASE="wayward_recovery_$(date -u +'%Y%m%d%H%M%S')_${MAIN_PID}"
RECOVERY_QUARANTINE_DATABASE="wayward_quarantine_$(date -u +'%Y%m%d%H%M%S')_${MAIN_PID}"

if [ -L "$MOODRIDE_DIR/.deploy/current" ]; then
  PREVIOUS_CONTROL_BUNDLE="$(readlink -f "$MOODRIDE_DIR/.deploy/current")"
  RUNNING_CADDYFILE_EXPECTED="$PREVIOUS_CONTROL_BUNDLE/Caddyfile"
elif [ -e "$MOODRIDE_DIR/.deploy/current" ]; then
  fail "The current control-bundle pointer exists but is not a symbolic link."
else
  PREVIOUS_CONTROL_BUNDLE="$MOODRIDE_DIR"
  RUNNING_CADDYFILE_EXPECTED="$MOODRIDE_DIR/Caddyfile"
fi
require_file "$PREVIOUS_CONTROL_BUNDLE/docker-compose.prod.yml"
if [ "$PREVIOUS_CONTROL_BUNDLE" = "$MOODRIDE_DIR" ]; then
  legacy_stage="$MOODRIDE_DIR/.deploy/bundles/.legacy-${MAIN_PID}.tmp"
  rm -rf "$legacy_stage"
  mkdir -p "$legacy_stage/scripts/deploy"
  cp -p "$MOODRIDE_DIR/docker-compose.prod.yml" "$legacy_stage/"
  cp -p "$MOODRIDE_DIR/Caddyfile" "$legacy_stage/"
  for control_file in deploy_prod.sh rollback_prod.sh database_recovery.sh \
      rollback_v41_v40_v39_to_v38.sql deploy_scenic_release.sh capture_prod_runtime.py; do
    cp -p "$MOODRIDE_DIR/scripts/deploy/$control_file" "$legacy_stage/scripts/deploy/"
  done
  (
    cd "$legacy_stage"
    LC_ALL=C sha256sum docker-compose.prod.yml Caddyfile scripts/deploy/* > bundle.sha256
    sha256sum --check bundle.sha256
    sync -f bundle.sha256
  )
  legacy_bundle_sha="$(sha256sum "$legacy_stage/bundle.sha256")"
  legacy_bundle_sha="${legacy_bundle_sha%% *}"
  PREVIOUS_CONTROL_BUNDLE="$MOODRIDE_DIR/.deploy/bundles/${PREDEPLOY_TAG}-legacy-${legacy_bundle_sha}"
  if [ -d "$PREVIOUS_CONTROL_BUNDLE" ]; then
    verify_control_bundle "$PREVIOUS_CONTROL_BUNDLE"
    rm -rf "$legacy_stage"
  else
    mv "$legacy_stage" "$PREVIOUS_CONTROL_BUNDLE"
    sync -f "$MOODRIDE_DIR/.deploy/bundles"
  fi
fi
verify_control_bundle "$PREVIOUS_CONTROL_BUNDLE"
PREVIOUS_COMPOSE_FILE="$PREVIOUS_CONTROL_BUNDLE/docker-compose.prod.yml"
PREVIOUS_CADDYFILE_PATH="$PREVIOUS_CONTROL_BUNDLE/Caddyfile"
select_previous_control_bundle

durable_copy "$ENV_FILE" "$PREDEPLOY_ENV"
chmod 600 "$PREDEPLOY_ENV"
capture_running_image_refs "$PREDEPLOY_ENV" "$PREDEPLOY_ENV"
capture_running_caddyfile_path "$PREDEPLOY_ENV" "$RUNNING_CADDYFILE_EXPECTED" \
  "$PREVIOUS_CONTROL_BUNDLE"
verify_running_control_artifact_identity "$PREDEPLOY_ENV"
verify_control_accepted_lock "$PREDEPLOY_ENV"
ACTUAL_PREDEPLOY_TAG="$(get_env_var IMAGE_TAG "$PREDEPLOY_ENV")"
validate_configured_running_tag "$PREDEPLOY_TAG" "$ACTUAL_PREDEPLOY_TAG"
[ "$IMAGE_TAG" != "$ACTUAL_PREDEPLOY_TAG" ] \
  || fail "Candidate release equals the actual running source revision; no cutover is required."
PREDEPLOY_TAG="$ACTUAL_PREDEPLOY_TAG"
ensure_compose_introspection_identity "$PREDEPLOY_ENV"
durable_copy "$PREDEPLOY_ENV" "$CANDIDATE_ENV"

save_image_lock "$PREDEPLOY_ENV" "$PREDEPLOY_TAG" "$PREVIOUS_IMAGE_LOCK"
printf '%s\n' "$PREDEPLOY_TAG" > "$MOODRIDE_DIR/.deploy/releases/${release_id}.previous-tag.tmp"
durable_replace "$MOODRIDE_DIR/.deploy/releases/${release_id}.previous-tag.tmp" \
  "$MOODRIDE_DIR/.deploy/releases/${release_id}.previous-tag"
set_env_var IMAGE_TAG "$IMAGE_TAG" "$CANDIDATE_ENV"
set_env_var GHCR_NAMESPACE "$GHCR_NAMESPACE" "$CANDIDATE_ENV"
candidate_namespace="$(get_env_var GHCR_NAMESPACE "$CANDIDATE_ENV")"
validate_image_reference "$candidate_namespace" moodride-route-api "$ROUTE_API_IMAGE_REF"
validate_image_reference "$candidate_namespace" moodride-route-worker "$ROUTE_WORKER_IMAGE_REF"
validate_image_reference "$candidate_namespace" moodride-notification-service "$NOTIFICATION_SERVICE_IMAGE_REF"
validate_image_reference "$candidate_namespace" moodride-frontend "$FRONTEND_IMAGE_REF"
set_env_var ROUTE_API_IMAGE_REF "$ROUTE_API_IMAGE_REF" "$CANDIDATE_ENV"
set_env_var ROUTE_WORKER_IMAGE_REF "$ROUTE_WORKER_IMAGE_REF" "$CANDIDATE_ENV"
set_env_var NOTIFICATION_SERVICE_IMAGE_REF "$NOTIFICATION_SERVICE_IMAGE_REF" "$CANDIDATE_ENV"
set_env_var FRONTEND_IMAGE_REF "$FRONTEND_IMAGE_REF" "$CANDIDATE_ENV"
set_env_var CADDYFILE_PATH "$CANDIDATE_CADDYFILE_PATH" "$CANDIDATE_ENV"
ensure_compose_introspection_identity "$CANDIDATE_ENV"
configure_candidate_runtime_environment "$CANDIDATE_ENV"

validate_pre_drain_env "$PREDEPLOY_ENV" "$PREVIOUS_CONTROL_BUNDLE" control
validate_pre_drain_env "$CANDIDATE_ENV" "$CONTROL_BUNDLE" candidate
select_previous_control_bundle
compose_env "$PREDEPLOY_ENV" config >/dev/null
verify_rendered_caddy_image "$PREDEPLOY_ENV"
select_candidate_control_bundle
compose_env "$CANDIDATE_ENV" config >/dev/null
verify_rendered_caddy_image "$CANDIDATE_ENV"
validate_candidate_control_bundle "$CANDIDATE_ENV"
printf '%s\n' "$PREVIOUS_CONTROL_BUNDLE" > "${PREVIOUS_CONTROL_BUNDLE_FILE}.tmp"
durable_replace "${PREVIOUS_CONTROL_BUNDLE_FILE}.tmp" "$PREVIOUS_CONTROL_BUNDLE_FILE"

# Pull and verify both sides before stopping intake so recovery never depends on a
# registry operation after the schema boundary has moved.
echo "Pulling candidate immutable images: $IMAGE_TAG"
select_candidate_control_bundle
docker pull "$(get_env_var CADDY_IMAGE_REF "$CANDIDATE_ENV")" >/dev/null
compose_env "$CANDIDATE_ENV" pull route-api route-worker notification-service frontend
verify_candidate_image_revisions "$CANDIDATE_ENV" "$IMAGE_TAG"
echo "Confirming previous release images are recoverable: $PREDEPLOY_TAG"
select_previous_control_bundle
docker pull "$(get_env_var CADDY_IMAGE_REF "$PREDEPLOY_ENV")" >/dev/null
compose_env "$PREDEPLOY_ENV" pull route-api route-worker notification-service frontend
verify_candidate_image_revisions "$PREDEPLOY_ENV" "$PREDEPLOY_TAG"
select_expected_runtime_identity control
synchronize_dataset_release_identity "$PREDEPLOY_ENV" "$POSTGRES_DB"
validate_pre_drain_env "$PREDEPLOY_ENV" "$PREVIOUS_CONTROL_BUNDLE" control
validate_pre_drain_env "$CANDIDATE_ENV" "$CONTROL_BUNDLE" candidate
run_control_route_identity_probe "$PREDEPLOY_ENV"
verify_live_quality_identity "$PREDEPLOY_ENV" "$POSTGRES_DB" control

# Caddy is the public intake boundary. Keep old route-api running with the old worker
# so V40 dispatch recovery can publish any unsent QUEUED jobs during the drain.
echo "Stopping public ingress; old route-api and route-worker remain active for drain."
select_previous_control_bundle
compose_env "$PREDEPLOY_ENV" stop caddy
wait_for_drain "$PREDEPLOY_ENV"

echo "Stopping old application services before the image/schema boundary."
select_previous_control_bundle
compose_env "$PREDEPLOY_ENV" stop route-api route-worker notification-service
assert_no_active_jobs "$PREDEPLOY_ENV"
evict_scenic_anchor_cache_namespaces "$PREDEPLOY_ENV"

capture_flyway_history "$PREDEPLOY_ENV" "$PREDEPLOY_HISTORY"
capture_database_catalog "$PREDEPLOY_ENV" "$POSTGRES_DB" "$PREDEPLOY_CATALOG"
validate_release_invariants "$PREDEPLOY_ENV" "$POSTGRES_DB"
create_recovery_backup "$PREDEPLOY_ENV" "$PREDEPLOY_BACKUP"
create_validated_recovery_database "$PREDEPLOY_ENV" "$PREDEPLOY_BACKUP" \
  "$RECOVERY_DATABASE" "$PREDEPLOY_HISTORY" "$PREDEPLOY_CATALOG"

# Recovery is enabled only after a complete scratch restore independently passed
# Flyway history, catalog, and release-invariant validation. Neither the validated
# replacement nor the current database is deleted by an error or cleanup trap.
RECOVERY_ENABLED=1
select_candidate_control_bundle
durable_copy "$CANDIDATE_ENV" "$ENV_FILE"

echo "Starting candidate route-api alone for Flyway and internal API readiness."
compose_env "$ENV_FILE" up -d --no-deps route-api
run_internal_http_healthcheck "Candidate route-api" "$ENV_FILE" route-api \
  http://127.0.0.1:8080/actuator/health "$HEALTHCHECK_TIMEOUT_SECONDS"
verify_forward_schema

echo "Starting candidate consumers behind closed ingress."
compose_env "$ENV_FILE" up -d --no-deps route-worker notification-service frontend
sleep 5
verify_service_running "$ENV_FILE" route-api
verify_service_running "$ENV_FILE" route-worker
verify_service_running "$ENV_FILE" notification-service
verify_service_running "$ENV_FILE" frontend
run_internal_http_healthcheck "Candidate frontend" "$ENV_FILE" frontend \
  http://127.0.0.1:3000/ "$HEALTHCHECK_TIMEOUT_SECONDS"
run_internal_http_healthcheck "Candidate WebSocket handshake" "$ENV_FILE" notification-service \
  http://127.0.0.1:8084/ws/info "$HEALTHCHECK_TIMEOUT_SECONDS"
run_synthetic_route_smoke "$ENV_FILE" v41 candidate
verify_live_quality_identity "$ENV_FILE" "$POSTGRES_DB" candidate


# Persist every rollback and accepted-release record before changing the durable
# control-plane pointer. If any write or the pointer switch fails, recovery is
# still permitted because public ingress remains closed.
ROLLBACK_SNAPSHOT="$MOODRIDE_DIR/.deploy/releases/rollback-${IMAGE_TAG}-${RELEASE_LOCK_SHA256}.env"
PREVIOUS_IMAGE_LOCK_SHA256="$(sha256sum "$PREVIOUS_IMAGE_LOCK")"
PREVIOUS_IMAGE_LOCK_SHA256="${PREVIOUS_IMAGE_LOCK_SHA256%% *}"
PREVIOUS_BUNDLE_MANIFEST_SHA256="$(sha256sum "$PREVIOUS_CONTROL_BUNDLE/bundle.sha256")"
PREVIOUS_BUNDLE_MANIFEST_SHA256="${PREVIOUS_BUNDLE_MANIFEST_SHA256%% *}"
CURRENT_BUNDLE_MANIFEST_SHA256="$(sha256sum "$CONTROL_BUNDLE/bundle.sha256")"
CURRENT_BUNDLE_MANIFEST_SHA256="${CURRENT_BUNDLE_MANIFEST_SHA256%% *}"
{
  printf 'CURRENT_TAG=%s\n' "$IMAGE_TAG"
  printf 'CURRENT_RELEASE_LOCK_SHA256=%s\n' "$RELEASE_LOCK_SHA256"
  printf 'CURRENT_CONTROL_BUNDLE=%s\n' "$CONTROL_BUNDLE"
  printf 'CURRENT_BUNDLE_MANIFEST_SHA256=%s\n' "$CURRENT_BUNDLE_MANIFEST_SHA256"
  printf 'PREVIOUS_TAG=%s\n' "$PREDEPLOY_TAG"
  printf 'PREVIOUS_IMAGE_LOCK=%s\n' "$PREVIOUS_IMAGE_LOCK"
  printf 'PREVIOUS_IMAGE_LOCK_SHA256=%s\n' "$PREVIOUS_IMAGE_LOCK_SHA256"
  printf 'PREVIOUS_CONTROL_BUNDLE=%s\n' "$PREVIOUS_CONTROL_BUNDLE"
  printf 'PREVIOUS_BUNDLE_MANIFEST_SHA256=%s\n' "$PREVIOUS_BUNDLE_MANIFEST_SHA256"
} > "${ROLLBACK_SNAPSHOT}.tmp"
chmod 600 "${ROLLBACK_SNAPSHOT}.tmp"
durable_replace "${ROLLBACK_SNAPSHOT}.tmp" "$ROLLBACK_SNAPSHOT"
ROLLBACK_SNAPSHOT_SHA256="$(sha256sum "$ROLLBACK_SNAPSHOT")"
ROLLBACK_SNAPSHOT_SHA256="${ROLLBACK_SNAPSHOT_SHA256%% *}"
printf '%s  %s\n' "$ROLLBACK_SNAPSHOT_SHA256" "$(basename "$ROLLBACK_SNAPSHOT")" \
  > "${ROLLBACK_SNAPSHOT}.sha256.tmp"
durable_replace "${ROLLBACK_SNAPSHOT}.sha256.tmp" "${ROLLBACK_SNAPSHOT}.sha256"
save_image_lock "$CANDIDATE_ENV" "$IMAGE_TAG" \
  "$MOODRIDE_DIR/.deploy/image-locks/${IMAGE_TAG}.env"
printf '%s\n' "$release_id" > "$MOODRIDE_DIR/.deploy/last-release.tmp"
durable_replace "$MOODRIDE_DIR/.deploy/last-release.tmp" \
  "$MOODRIDE_DIR/.deploy/last-release"
switch_control_bundle_pointer "$CONTROL_BUNDLE"
CURRENT_POINTER_SWITCHED=1

# The candidate is now the durable database and control-plane owner. Recovery
# must be disabled before opening ingress so no accepted public write can be
# erased. A later failure leaves the candidate pointer intact and fails closed.
RECOVERY_ENABLED=0
FAIL_CLOSED_ON_ERROR=1
select_candidate_control_bundle
echo "Recreating Caddy only after durable candidate acceptance and internal verification."
compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy
verify_service_running "$ENV_FILE" caddy
verify_running_caddy_image "$ENV_FILE"
run_http_healthcheck "Production API" "$API_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"
run_http_healthcheck "Production frontend" "$FRONTEND_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"
run_http_healthcheck "Production WebSocket handshake" "$WS_HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"
FAIL_CLOSED_ON_ERROR=0
echo "Deployment completed with immutable image tag: $IMAGE_TAG"
