#!/usr/bin/env bash
set -euo pipefail

# One-shot setup for Phase 2 (run on the VM as your non-root user)
# Usage:
#   GHCR_USER=your-gh-user GHCR_TOKEN=xxx ./scripts/setup_phase2.sh

GHCR_USER=${GHCR_USER:-}
GHCR_TOKEN=${GHCR_TOKEN:-}
GHCR_NAMESPACE=${GHCR_NAMESPACE:-${GHCR_USER}}
IMAGE_TAG=${IMAGE_TAG:-v1}

IMAGES=(moodride-route-api moodride-route-worker moodride-notification-service moodride-frontend)

echo "==> Creating project directories under /opt/moodride"
sudo mkdir -p /opt/moodride/data/osrm
sudo chown -R "$(whoami)":"$(whoami)" /opt/moodride

ENV_FILE=/opt/moodride/.env.prod
if [ -f "$ENV_FILE" ]; then
  echo "==> .env.prod already exists at $ENV_FILE (skipping generation)"
else
  echo "==> Generating .env.prod with strong random passwords (edit values as needed)"
  DB_PASSWORD=$(openssl rand -base64 24)
  REDIS_PASSWORD=$(openssl rand -base64 24)
  cat > "$ENV_FILE" <<EOF
DB_NAME=moodride
DB_USER=moodride
DB_PASSWORD=${DB_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
# Add other env vars required by your docker-compose file (SMTP, API keys, etc.)
EOF
  chmod 600 "$ENV_FILE"
  echo "==> Wrote $ENV_FILE (permissions 600). Keep these secrets safe."
fi

if [ -n "$GHCR_USER" ] && [ -n "$GHCR_TOKEN" ]; then
  echo "==> Logging into GHCR as $GHCR_USER and pulling images"
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
  for img in "${IMAGES[@]}"; do
    full=ghcr.io/${GHCR_NAMESPACE}/${img}:${IMAGE_TAG}
    echo "--> Pulling $full"
    docker pull "$full" || echo "Warning: failed to pull $full"
  done
else
  echo "==> GHCR_USER/GHCR_TOKEN not provided — skipping image pull."
fi

COMPOSE_FILE=docker-compose.prod.yml
if [ -f "$COMPOSE_FILE" ]; then
  echo "==> Starting only Postgres service (so you can restore the DB)"
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d postgres
  echo "==> Postgres starting; monitor with: docker compose -f $COMPOSE_FILE logs -f postgres"
else
  echo "==> Could not find $COMPOSE_FILE in current directory. Copy docker-compose.prod.yml to $(pwd) and re-run this script."
fi

echo "==> Done. Next: transfer the DB dump and OSRM files to /opt/moodride, then run the restore steps in the Deployment guide."
