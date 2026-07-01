#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/moodride}"
COMPOSE_PROD="${COMPOSE_PROD:-$APP_DIR/docker-compose.prod.yml}"
COMPOSE_ADMIN="${COMPOSE_ADMIN:-$APP_DIR/docker-compose.admin.yml}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env.prod}"

action="${1:-status}"

cd "$APP_DIR"

if [ ! -f "$COMPOSE_PROD" ]; then
  echo "Missing production compose file: $COMPOSE_PROD" >&2
  exit 1
fi

if [ ! -f "$COMPOSE_ADMIN" ]; then
  echo "Missing admin compose file: $COMPOSE_ADMIN" >&2
  exit 1
fi

compose() {
  docker compose \
    -f "$COMPOSE_PROD" \
    -f "$COMPOSE_ADMIN" \
    --env-file "$ENV_FILE" \
    --profile admin \
    "$@"
}

case "$action" in
  start)
    compose up -d dozzle cloudbeaver
    cat <<'EOF'
Admin tools are running on VM-local ports only.

Open an SSH tunnel from your machine:
  ssh -L 8088:127.0.0.1:8088 -L 8978:127.0.0.1:8978 <prod-user>@<prod-host>

Then open:
  Logs:       http://localhost:8088
  DB browser: http://localhost:8978
EOF
    ;;
  stop)
    compose stop dozzle cloudbeaver
    ;;
  restart)
    compose up -d --force-recreate dozzle cloudbeaver
    ;;
  status)
    compose ps dozzle cloudbeaver
    ;;
  logs)
    compose logs --tail 120 -f dozzle cloudbeaver
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|logs}" >&2
    exit 1
    ;;
esac
