# Docker Secrets Strategy

This folder stores local secret templates only. Do not commit real credentials.

## Usage

1. Copy templates and fill values locally:
   - `kong-jwt.env.template` -> `kong-jwt.env`
   - `db.env.template` -> `db.env`
2. Export values into shell or CI before `docker compose`.
3. Prefer secret managers in production (Vault, AWS Secrets Manager, GCP Secret Manager).

## Notes

- `docker-compose.prod.yml` reads env values (`KONG_JWT_*`, `POSTGRES_*`, `REDIS_PASSWORD`).
- Keep generated secret files out of source control.

