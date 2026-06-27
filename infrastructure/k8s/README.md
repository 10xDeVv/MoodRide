# Wayward Kubernetes Assets

This tree contains production-oriented manifests for app services and shared gateway/CDC infrastructure.

## Apply order

1. `shared/namespace.yaml`
2. shared config, gateway, and connector manifests
3. service folders (`route-api`, `route-worker`, `notification-service`, `cdc-service`)
4. network policies

## Validation

- `kubectl apply --dry-run=client -f infrastructure/k8s`
- verify all secret templates are materialized before production apply

