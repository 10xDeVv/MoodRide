#!/usr/bin/env python3
"""Capture and verify production runtime attribution without changing Docker or env state."""

from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import zipfile
import zlib
from typing import Any

ROOT = pathlib.Path(os.environ.get("MOODRIDE_ROOT", "/opt/moodride"))
ENV_FILE = ROOT / ".env.prod"
APPLICATION_SERVICES = ("route-api", "route-worker", "notification-service", "frontend")
SPRING_APPLICATION_SERVICES = ("route-api", "route-worker", "notification-service")
ROUTING_SERVICES = ("route-api", "route-worker")
ROUTING_CACHE_IDENTITY_KEYS = (
    "MOODRIDE_SCENIC_SCORING_VERSION",
    "MOODRIDE_ROAD_DATASET_REVISION",
    "MOODRIDE_ROAD_DATASET_FINGERPRINT",
    "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA",
)
EXPECTED_COMPOSE_SERVICES = (
    "postgres",
    "zookeeper",
    "kafka",
    "redis",
    "osrm",
    *APPLICATION_SERVICES,
    "caddy",
)
IMAGE_REF_KEYS = {
    "route-api": "ROUTE_API_IMAGE_REF",
    "route-worker": "ROUTE_WORKER_IMAGE_REF",
    "notification-service": "NOTIFICATION_SERVICE_IMAGE_REF",
    "frontend": "FRONTEND_IMAGE_REF",
}
IMAGE_REPOSITORIES = {
    "route-api": "moodride-route-api",
    "route-worker": "moodride-route-worker",
    "notification-service": "moodride-notification-service",
    "frontend": "moodride-frontend",
}
IDENTITY_KEYS = (
    "POSTGRES_DB",
    "IMAGE_TAG",
    *IMAGE_REF_KEYS.values(),
    "MOODRIDE_SCENIC_SCORING_VERSION",
    "MOODRIDE_ROAD_DATASET_REVISION",
    "MOODRIDE_ROAD_DATASET_FINGERPRINT",
    "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA",
    "GHCR_NAMESPACE",
    "OSRM_IMAGE_REF",
    "OSRM_DATASET_BASENAME",
    "CADDY_IMAGE_REF",
    "CADDYFILE_PATH",
    "SPRING_PROFILES_ACTIVE",
    "MOODRIDE_ALGORITHM_PROFILE",
    "MOODRIDE_ALGORITHM_MODE",
    "MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED",
)
SHA_TAG = re.compile(r"^sha-([0-9a-f]{40})$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SIGNED_SCENIC_VERSION = re.compile(r"^3\.7(?:[._+-][A-Za-z0-9][A-Za-z0-9._+-]*)?$")
RUNTIME_POLICY = {
    "SPRING_PROFILES_ACTIVE": "prod",
    "MOODRIDE_ALGORITHM_PROFILE": "hybrid_osrm_v2",
    "MOODRIDE_ALGORITHM_MODE": "drive",
    "MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED": "false",
}
ROUTING_RUNTIME_IDENTITY_KEYS = (
    *RUNTIME_POLICY,
    "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA",
)
SYMMETRIC_ROUTING_RUNTIME_KEYS = (
    *ROUTING_RUNTIME_IDENTITY_KEYS,
    "datasource_url_matches_compose_identity",
    "datasource_username_matches_compose_identity",
)
GITHUB_SOURCE_URL = re.compile(
    r"^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"
)


class CaptureError(RuntimeError):
    """A safe, operator-actionable capture failure."""


def safe_capture_exception(exc: Exception) -> str:
    if isinstance(exc, subprocess.CalledProcessError):
        return f"capture subprocess failed with exit {exc.returncode}"
    if isinstance(exc, KeyError):
        return "required capture field is absent"
    if isinstance(exc, UnicodeDecodeError):
        return "captured data is not valid UTF-8"
    return str(exc)

POSTGRES_IDENTITY_COPY_SQL = r"""
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
COPY (
  WITH
  flyway_history_json AS (
    SELECT COALESCE(
             jsonb_agg(to_jsonb(history) ORDER BY installed_rank),
             '[]'::jsonb
           ) AS rows
    FROM (
      SELECT installed_rank, version, description, type, script, checksum,
             installed_on, success
      FROM flyway_schema_history
    ) history
  ),
  scenic_versions_json AS (
    SELECT COALESCE(jsonb_agg(version ORDER BY version), '[]'::jsonb) AS versions
    FROM (
      SELECT DISTINCT btrim(scoring_version) AS version
      FROM scenic_score_tiles
    ) scenic_versions
  ),
  road_identity AS (
    SELECT CASE WHEN
      EXISTS (SELECT 1 FROM road_segments)
      AND NOT EXISTS (
        SELECT 1
        FROM road_segments
        WHERE stable_identity_key IS NULL OR btrim(stable_identity_key) = ''
      )
    THEN 'road-identity-ok' ELSE 'road-identity-divergent' END AS marker
  ),
  metadata AS (
    SELECT encode(
             convert_to(
               jsonb_build_object(
                 'flyway_history', flyway_history_json.rows,
                 'scenic_versions', scenic_versions_json.versions,
                 'road_identity_marker', road_identity.marker
               )::text,
               'UTF8'
             ),
             'hex'
           ) AS payload
    FROM flyway_history_json, scenic_versions_json, road_identity
  ),
  database_rows AS (
    SELECT row_number() OVER (ORDER BY installed_rank)::bigint AS row_order,
           jsonb_build_array(
             installed_rank, version, description, type, script, checksum, success
           )::text AS payload
    FROM flyway_schema_history
  ),
  scenic_rows AS (
    SELECT row_number() OVER (ORDER BY h3_index)::bigint AS row_order,
           row_to_json(scenic_score_tiles)::text AS payload
    FROM scenic_score_tiles
  ),
  canonical_road_segments AS (
    SELECT stable_identity_key,
           jsonb_build_array(
             stable_identity_key,
             osm_way_id,
             encode(ST_AsEWKB(ST_Normalize(geometry), 'XDR'), 'hex'),
             h3_tile_index,
             length_meters,
             speed_limit_kmh,
             road_type,
             surface,
             curvature,
             elevation_change
           )::text AS payload
    FROM road_segments
    WHERE stable_identity_key IS NOT NULL
      AND btrim(stable_identity_key) <> ''
  ),
  road_rows AS (
    SELECT row_number() OVER (
             ORDER BY stable_identity_key COLLATE "C", payload COLLATE "C"
           )::bigint AS row_order,
           payload
    FROM canonical_road_segments
  )
  SELECT section, payload
  FROM (
    SELECT 0 AS section_order, 0::bigint AS row_order,
           'metadata'::text AS section, payload
    FROM metadata
    UNION ALL
    SELECT 1, row_order, 'database_fingerprint', payload
    FROM database_rows
    UNION ALL
    SELECT 2, row_order, 'scenic_dataset_fingerprint', payload
    FROM scenic_rows
    UNION ALL
    SELECT 3, row_order, 'road_dataset_fingerprint', payload
    FROM road_rows
  ) snapshot_rows
  ORDER BY section_order, row_order
) TO STDOUT;
COMMIT;
""".strip()


def run(*args: str) -> str:
    completed = subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return completed.stdout.strip()


def run_bytes(*args: str) -> bytes:
    completed = subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return completed.stdout


def load_env_values() -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = ENV_FILE.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise CaptureError("production environment file is not valid UTF-8") from exc
    except OSError as exc:
        raise CaptureError(
            f"production environment file could not be read ({type(exc).__name__})"
        ) from exc
    for line_number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise CaptureError(
                f"production environment line {line_number} is malformed"
            )
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise CaptureError(
                f"production environment line {line_number} has an invalid key"
            )
        if key in values:
            raise CaptureError(
                f"production environment line {line_number} duplicates a key"
            )
        values[key] = value
    return values


def nonsecret_env(values: dict[str, str]) -> dict[str, str | None]:
    return {key: values.get(key) for key in IDENTITY_KEYS}


def candidate_containers(service: str) -> list[dict[str, Any]]:
    ids = run("docker", "ps", "-aq", "--filter", f"label=com.docker.compose.service={service}").splitlines()
    matches: list[dict[str, Any]] = []
    for container_id in ids:
        if not container_id:
            continue
        inspected = json.loads(run("docker", "inspect", container_id))[0]
        labels = inspected.get("Config", {}).get("Labels") or {}
        working_dir = labels.get("com.docker.compose.project.working_dir")
        if not working_dir or pathlib.PurePosixPath(working_dir) != pathlib.PurePosixPath(ROOT):
            continue
        matches.append(inspected)
    return matches


def container_env(container: dict[str, Any]) -> dict[str, str]:
    values: dict[str, str] = {}
    for entry in container.get("Config", {}).get("Env") or []:
        if "=" not in entry:
            continue
        key, value = entry.split("=", 1)
        if key in values:
            raise ValueError(f"container environment contains duplicate key {key}")
        values[key] = value
    return values


def capture_compose_container_states() -> dict[str, Any]:
    services: list[dict[str, Any]] = []
    reasons: list[str] = []
    for service in EXPECTED_COMPOSE_SERVICES:
        try:
            containers = candidate_containers(service)
            if len(containers) != 1:
                services.append({"service": service, "running": False, "container_id": None})
                reasons.append(f"{service}: expected one production container, found {len(containers)}")
                continue
            container = containers[0]
            container_id = container.get("Id")
            running = bool(container.get("State", {}).get("Running"))
            services.append({
                "service": service,
                "running": running,
                "container_id": container_id if isinstance(container_id, str) else None,
            })
            if not isinstance(container_id, str) or not container_id:
                reasons.append(f"{service}: container ID is absent")
            if not running:
                reasons.append(f"{service}: container is stopped")
        except (
            OSError,
            subprocess.CalledProcessError,
            json.JSONDecodeError,
            KeyError,
            IndexError,
            TypeError,
            ValueError,
        ) as exc:
            services.append({"service": service, "running": False, "container_id": None})
            reasons.append(
                f"{service}: container state is not attributable ({type(exc).__name__})"
            )
    return {"attributable": not reasons, "services": services, "reasons": reasons}


def compose_container_ids(snapshot: dict[str, Any]) -> dict[str, str | None]:
    return {
        item["service"]: item.get("container_id")
        for item in snapshot.get("services", [])
        if isinstance(item, dict) and isinstance(item.get("service"), str)
    }


def container_for_observation(
    service: str, initial_container_id: str | None
) -> dict[str, Any]:
    if not initial_container_id:
        raise RuntimeError(f"{service} initial container ID is unavailable")
    containers = candidate_containers(service)
    if len(containers) != 1:
        raise RuntimeError(
            f"{service} container generation changed: expected one container, "
            f"found {len(containers)}"
        )
    container = containers[0]
    observed_id = container.get("Id")
    if observed_id != initial_container_id:
        raise RuntimeError(
            f"{service} container generation changed from the initial snapshot"
        )
    return container


def verify_compose_generation(
    initial: dict[str, Any], final: dict[str, Any]
) -> list[str]:
    reasons: list[str] = []
    initial_ids = compose_container_ids(initial)
    final_ids = compose_container_ids(final)
    for service in EXPECTED_COMPOSE_SERVICES:
        initial_id = initial_ids.get(service)
        final_id = final_ids.get(service)
        if not initial_id or not final_id:
            reasons.append(
                f"{service}: container generation could not be verified across capture"
            )
        elif initial_id != final_id:
            reasons.append(f"{service}: container generation changed during capture")
    return reasons


def stable_file_sha256(path: pathlib.Path) -> str:
    before = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    after = path.stat()
    before_identity = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    after_identity = (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    )
    if before_identity != after_identity:
        raise RuntimeError(f"canonical source changed while hashing: {path.name}")
    return digest.hexdigest()


def validate_read_only_bind_mount(
    container: dict[str, Any],
    destination: str,
    expected_source: pathlib.Path,
) -> tuple[dict[str, Any], list[str]]:
    reasons: list[str] = []
    canonical_expected = expected_source.resolve(strict=True)
    mounts = container.get("Mounts")
    if not isinstance(mounts, list):
        raise RuntimeError("container mount metadata is absent")
    matches = [
        mount
        for mount in mounts
        if isinstance(mount, dict) and mount.get("Destination") == destination
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one container mount at {destination}, found {len(matches)}"
        )
    mount = matches[0]
    source = mount.get("Source")
    canonical_source: pathlib.Path | None = None
    if not isinstance(source, str) or not source:
        reasons.append(f"{destination} bind mount source is absent")
    else:
        try:
            canonical_source = pathlib.Path(source).resolve(strict=True)
        except OSError:
            reasons.append(f"{destination} bind mount source cannot be canonicalized")
    if mount.get("Type") != "bind":
        reasons.append(f"{destination} is not a bind mount")
    if mount.get("RW") is not False:
        reasons.append(f"{destination} bind mount is not read-only")
    if canonical_source is not None and canonical_source != canonical_expected:
        reasons.append(
            f"{destination} bind mount differs from the configured canonical source"
        )
    return {
        "destination": destination,
        "expected_source": str(canonical_expected),
        "source_matches": canonical_source == canonical_expected,
        "type": mount.get("Type"),
        "read_only": mount.get("RW") is False,
    }, reasons


def copy_container_file(container_id: str, path: str) -> bytes:
    tar_payload = run_bytes("docker", "cp", f"{container_id}:{path}", "-")
    with tarfile.open(fileobj=io.BytesIO(tar_payload), mode="r:*") as archive:
        files = [member for member in archive.getmembers() if member.isfile()]
        if len(files) != 1:
            raise RuntimeError(
                f"container file capture for {path} returned {len(files)} files"
            )
        extracted = archive.extractfile(files[0])
        if extracted is None:
            raise RuntimeError(f"container file capture for {path} has no payload")
        return extracted.read()


def build_osrm_file_manifest(
    data_dir: pathlib.Path, basename: str
) -> tuple[list[str], str]:
    """Hash normalized relative sidecar names in C-locale byte order."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", basename):
        raise ValueError("OSRM dataset basename contains unsafe characters")
    directory_before = data_dir.stat()
    files = list(data_dir.glob(f"{basename}.osrm*"))
    if not files or any(not path.is_file() for path in files):
        raise ValueError("active OSRM dataset file set is empty or invalid")
    names = [path.name for path in files]
    if any(
        not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", name)
        or "/" in name
        or "\\" in name
        for name in names
    ):
        raise ValueError(
            "OSRM sidecar name is not a normalized ASCII relative filename"
        )
    files.sort(key=lambda path: os.fsencode(path.name))
    manifest_lines: list[str] = []
    captured_stats: dict[str, tuple[int, int, int, int, int]] = {}
    for path in files:
        digest = stable_file_sha256(path)
        stat = path.stat()
        captured_stats[path.name] = (
            stat.st_dev,
            stat.st_ino,
            stat.st_size,
            stat.st_mtime_ns,
            stat.st_ctime_ns,
        )
        manifest_lines.append(f"{digest}  {path.name}")
    final_files = list(data_dir.glob(f"{basename}.osrm*"))
    final_files.sort(key=lambda path: os.fsencode(path.name))
    if [path.name for path in final_files] != [path.name for path in files]:
        raise RuntimeError("OSRM dataset file set changed during capture")
    for path in final_files:
        stat = path.stat()
        final_identity = (
            stat.st_dev,
            stat.st_ino,
            stat.st_size,
            stat.st_mtime_ns,
            stat.st_ctime_ns,
        )
        if captured_stats.get(path.name) != final_identity:
            raise RuntimeError("OSRM dataset content changed during capture")
    directory_after = data_dir.stat()
    if (
        directory_before.st_dev,
        directory_before.st_ino,
        directory_before.st_mtime_ns,
        directory_before.st_ctime_ns,
    ) != (
        directory_after.st_dev,
        directory_after.st_ino,
        directory_after.st_mtime_ns,
        directory_after.st_ctime_ns,
    ):
        raise RuntimeError("OSRM data directory changed during capture")
    manifest = "".join(f"{line}\n" for line in manifest_lines).encode("utf-8")
    return manifest_lines, hashlib.sha256(manifest).hexdigest()


def capture_caddy_identity(
    configured: dict[str, str], initial_container_id: str | None
) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        container = container_for_observation("caddy", initial_container_id)
        container_id = str(container["Id"])
        if not container.get("State", {}).get("Running"):
            reasons.append("Caddy container is stopped")
        expected_ref = configured.get("CADDY_IMAGE_REF", "")
        if not re.fullmatch(r"caddy@sha256:[0-9a-f]{64}", expected_ref):
            reasons.append(
                "configured CADDY_IMAGE_REF is not the required official digest reference"
            )
        config = container.get("Config") or {}
        if config.get("Image") != expected_ref:
            reasons.append(
                "Caddy container configured image differs from .env digest reference"
            )
        image = json.loads(
            run("docker", "image", "inspect", str(container.get("Image")))
        )[0]
        repo_digests = image.get("RepoDigests") or []
        if expected_ref not in repo_digests:
            reasons.append("Caddy image RepoDigests do not contain the configured digest")

        caddyfile_value = configured.get("CADDYFILE_PATH", "")
        if not caddyfile_value:
            raise RuntimeError("configured CADDYFILE_PATH is absent")
        caddyfile = pathlib.Path(caddyfile_value)
        mount_identity, mount_reasons = validate_read_only_bind_mount(
            container, "/etc/caddy/Caddyfile", caddyfile
        )
        reasons.extend(mount_reasons)
        configured_sha256 = stable_file_sha256(caddyfile.resolve(strict=True))
        mounted_payload = copy_container_file(
            container_id, "/etc/caddy/Caddyfile"
        )
        mounted_sha256 = hashlib.sha256(mounted_payload).hexdigest()
        if mounted_sha256 != configured_sha256:
            reasons.append(
                "mounted Caddyfile content differs from the configured canonical source"
            )
        return {
            "attributable": not reasons,
            "reasons": reasons,
            "container_id": container_id,
            "image_ref": expected_ref,
            "configured_image_ref": config.get("Image"),
            "repo_digests": repo_digests,
            "caddyfile_mount": mount_identity,
            "configured_caddyfile_sha256": configured_sha256,
            "mounted_caddyfile_sha256": mounted_sha256,
        }
    except (
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        tarfile.TarError,
        KeyError,
        RuntimeError,
        TypeError,
        ValueError,
    ) as exc:
        return {
            "attributable": False,
            "container_id": initial_container_id,
            "reasons": [safe_capture_exception(exc)],
        }


def capture_osrm_identity(
    configured: dict[str, str], initial_container_id: str | None
) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        container = container_for_observation("osrm", initial_container_id)
        container_id = str(container["Id"])
        if not container.get("State", {}).get("Running"):
            reasons.append("OSRM container is stopped")
        expected_ref = configured.get("OSRM_IMAGE_REF", "")
        if not re.fullmatch(
            r"ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}", expected_ref
        ):
            reasons.append(
                "configured OSRM_IMAGE_REF is not the required digest-pinned repository"
            )
        config = container.get("Config") or {}
        if config.get("Image") != expected_ref:
            reasons.append(
                "OSRM container configured image differs from .env digest reference"
            )
        image = json.loads(
            run("docker", "image", "inspect", str(container.get("Image")))
        )[0]
        repo_digests = image.get("RepoDigests") or []
        if expected_ref not in repo_digests:
            reasons.append("OSRM image RepoDigests do not contain the configured digest")

        basename = configured.get("OSRM_DATASET_BASENAME", "")
        if basename != "canada-latest":
            reasons.append(
                "OSRM_DATASET_BASENAME is not the accepted canada-latest dataset"
            )
        route_path = f"/data/{basename}.osrm"
        expected_cmd = ["osrm-routed", "--algorithm", "mld", route_path]
        expected_args = ["--algorithm", "mld", route_path]
        config_cmd = config.get("Cmd")
        runtime_args = container.get("Args")
        if config_cmd != expected_cmd:
            reasons.append(
                "OSRM Config.Cmd is not the exact configured MLD route command"
            )
        if runtime_args != expected_args:
            reasons.append(
                "OSRM runtime Args are not the exact configured MLD route arguments"
            )
        canonical_data_dir = ROOT / "data" / "osrm"
        mount_identity, mount_reasons = validate_read_only_bind_mount(
            container, "/data", canonical_data_dir
        )
        reasons.extend(mount_reasons)
        manifest_lines, manifest_sha256 = build_osrm_file_manifest(
            canonical_data_dir, basename
        )
        return {
            "attributable": not reasons,
            "reasons": reasons,
            "container_id": container_id,
            "image_ref": expected_ref,
            "repo_digests": repo_digests,
            "dataset_basename": basename,
            "route_path": route_path,
            "config_cmd": config_cmd,
            "args": runtime_args,
            "data_mount": mount_identity,
            "file_manifest": manifest_lines,
            "file_manifest_sha256": manifest_sha256,
        }
    except (
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        KeyError,
        RuntimeError,
        TypeError,
        ValueError,
    ) as exc:
        return {
            "attributable": False,
            "container_id": initial_container_id,
            "reasons": [safe_capture_exception(exc)],
        }


def capture_service(
    service: str,
    configured: dict[str, str],
    initial_container_id: str | None,
    expected_revision: str | None,
    expected_source_url: str | None = None,
) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        container = container_for_observation(service, initial_container_id)
        image_id = container.get("Image")
        image = json.loads(run("docker", "image", "inspect", str(image_id)))[0]
        repo_digests = [item for item in image.get("RepoDigests") or [] if item]
        image_labels = image.get("Config", {}).get("Labels") or {}
        revision = image_labels.get("org.opencontainers.image.revision")
        source = image_labels.get("org.opencontainers.image.source")
        configured_runtime_ref = container.get("Config", {}).get("Image")
        expected_ref = configured.get(IMAGE_REF_KEYS[service])
        namespace = configured.get("GHCR_NAMESPACE")
        expected_repository = IMAGE_REPOSITORIES[service]
        expected_prefix = f"ghcr.io/{namespace}/{expected_repository}@" if namespace else ""
        running = bool(container.get("State", {}).get("Running"))

        if not running:
            reasons.append("container is stopped")
        if not image_id:
            reasons.append("container image ID is absent")
        if (
            not expected_ref
            or not expected_prefix
            or not expected_ref.startswith(expected_prefix)
            or not DIGEST.fullmatch(expected_ref.removeprefix(expected_prefix))
        ):
            reasons.append("configured .env digest reference is absent, invalid, or outside GHCR_NAMESPACE")
        if not configured_runtime_ref or configured_runtime_ref != expected_ref:
            reasons.append("container configured image does not equal the .env digest reference")
        if not repo_digests:
            reasons.append("image RepoDigests are absent")
        elif expected_ref not in repo_digests:
            reasons.append("image RepoDigests do not contain the configured .env digest reference")
        if not revision:
            reasons.append("OCI revision label is absent")
        elif expected_revision is None or revision != expected_revision:
            reasons.append("OCI revision does not equal the configured IMAGE_TAG source")
        if expected_source_url is None:
            reasons.append("exact expected GitHub source URL was not provided")
        elif source != expected_source_url:
            reasons.append("OCI source label does not equal the exact expected GitHub source URL")

        configured_env = container_env(container)
        configured_runtime: dict[str, str | bool | None] = {}
        if service in SPRING_APPLICATION_SERVICES:
            configured_runtime["SPRING_PROFILES_ACTIVE"] = configured_env.get(
                "SPRING_PROFILES_ACTIVE"
            )
        if service in ROUTING_SERVICES:
            for key in ROUTING_RUNTIME_IDENTITY_KEYS:
                configured_runtime[key] = configured_env.get(key)
            expected_database = configured.get("POSTGRES_DB")
            expected_url = f"jdbc:postgresql://postgres:5432/{expected_database}"
            if service == "route-worker":
                expected_url += "?reWriteBatchedInserts=true"
            configured_runtime[
                "datasource_url_matches_compose_identity"
            ] = bool(expected_database) and (
                configured_env.get("SPRING_DATASOURCE_URL") == expected_url
            )
            expected_username = configured.get("POSTGRES_USER")
            configured_runtime[
                "datasource_username_matches_compose_identity"
            ] = bool(expected_username) and (
                configured_env.get("SPRING_DATASOURCE_USERNAME")
                == expected_username
            )
            if service == "route-worker":
                configured_runtime["MOODRIDE_OSRM_BASE_URL"] = configured_env.get(
                    "MOODRIDE_OSRM_BASE_URL"
                )
                configured_runtime[
                    "MOODRIDE_ALGORITHM_OSRM_REQUEST_PARALLELISM"
                ] = configured_env.get("MOODRIDE_ALGORITHM_OSRM_REQUEST_PARALLELISM")
        configured_cache_identity: dict[str, str | None] = {}
        if service in ROUTING_SERVICES:
            for key in ROUTING_CACHE_IDENTITY_KEYS:
                configured_cache_identity[key] = configured_env.get(key)

        result: dict[str, Any] = {
            "service": service,
            "container_id": container.get("Id"),
            "running": running,
            "configured_image_ref": configured_runtime_ref,
            "expected_env_image_ref": expected_ref,
            "image_id": image_id,
            "repo_digests": repo_digests,
            "oci_revision": revision,
            "oci_source": source,
            "attributable": not reasons,
            "reasons": reasons,
        }
        if configured_runtime:
            result["configured_runtime"] = configured_runtime
        if configured_cache_identity:
            result["configured_cache_identity"] = configured_cache_identity
        return result
    except (
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        KeyError,
        IndexError,
        TypeError,
        RuntimeError,
        ValueError,
    ) as exc:
        return {
            "service": service,
            "container_id": initial_container_id,
            "attributable": False,
            "reasons": [safe_capture_exception(exc)],
        }


def capture_postgres_identity(
    configured: dict[str, str], initial_container_id: str | None
) -> tuple[dict[str, Any], dict[str, Any]]:
    try:
        database = configured["POSTGRES_DB"]
        username = configured["POSTGRES_USER"]
        container = container_for_observation("postgres", initial_container_id)
        if not container.get("State", {}).get("Running"):
            raise RuntimeError("initial production postgres container is not running")
        process = subprocess.Popen(
            (
                "docker", "exec", str(container["Id"]),
                "psql", "--username", username, "--dbname", database,
                "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
                "--set", "ON_ERROR_STOP=1",
                "--command", POSTGRES_IDENTITY_COPY_SQL,
            ),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        section_names = {
            b"database_fingerprint": "database_fingerprint",
            b"scenic_dataset_fingerprint": "scenic_dataset_fingerprint",
            b"road_dataset_fingerprint": "road_dataset_fingerprint",
        }
        digests = {
            name: hashlib.sha256()
            for name in section_names.values()
        }
        byte_counts = {name: 0 for name in section_names.values()}
        metadata_hex: bytes | None = None
        stream_error: str | None = None
        assert process.stdout is not None
        for raw_line in process.stdout:
            if not raw_line.endswith(b"\n"):
                stream_error = "PostgreSQL snapshot stream contains an unterminated row"
                continue
            section, separator, payload = raw_line[:-1].partition(b"\t")
            if not separator:
                stream_error = "PostgreSQL snapshot stream contains an unframed row"
                continue
            if section == b"metadata":
                if metadata_hex is not None:
                    stream_error = "PostgreSQL snapshot stream repeats metadata"
                metadata_hex = payload
                continue
            section_name = section_names.get(section)
            if section_name is None:
                stream_error = "PostgreSQL snapshot stream contains an unknown section"
                continue
            framed_payload = payload + b"\n"
            digests[section_name].update(framed_payload)
            byte_counts[section_name] += len(framed_payload)
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(
                f"PostgreSQL identity snapshot failed with exit {return_code}"
            )
        if stream_error is not None:
            raise RuntimeError(stream_error)
        if metadata_hex is None:
            raise RuntimeError("PostgreSQL identity snapshot omitted metadata")
        metadata = json.loads(
            bytes.fromhex(metadata_hex.decode("ascii")).decode("utf-8")
        )
        if not isinstance(metadata, dict):
            raise RuntimeError("PostgreSQL identity snapshot metadata is malformed")
        history = metadata.get("flyway_history")
        if not isinstance(history, list) or not history:
            raise RuntimeError("Flyway history is empty")
        scenic_versions = metadata.get("scenic_versions")
        if (
            not isinstance(scenic_versions, list)
            or len(scenic_versions) != 1
            or not isinstance(scenic_versions[0], str)
            or not scenic_versions[0]
        ):
            raise RuntimeError(
                "database does not expose exactly one nonblank scenic scoring version"
            )
        if metadata.get("road_identity_marker") != "road-identity-ok":
            raise RuntimeError(
                "database road dataset lacks complete V38 stable identity coverage"
            )
        if any(byte_count == 0 for byte_count in byte_counts.values()):
            raise RuntimeError(
                "database, scenic, or road identity snapshot stream is empty"
            )
        if not SIGNED_SCENIC_VERSION.fullmatch(scenic_versions[0]):
            raise RuntimeError(
                "database scenic scoring version is not a signed 3.7 release"
            )
        flyway_history = {
            "attributable": True,
            "container_id": initial_container_id,
            "rows": history,
            "reasons": [],
        }
        database_identity = {
            "attributable": True,
            "container_id": initial_container_id,
            "scenic_scoring_version": scenic_versions[0],
            **{
                name: digest.hexdigest()
                for name, digest in digests.items()
            },
            "road_fingerprint_stream_bytes": byte_counts[
                "road_dataset_fingerprint"
            ],
            "reasons": [],
        }
        return flyway_history, database_identity
    except (
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        KeyError,
        RuntimeError,
        TypeError,
        UnicodeDecodeError,
        ValueError,
    ) as exc:
        reason = safe_capture_exception(exc)
        return (
            {
                "attributable": False,
                "container_id": initial_container_id,
                "reasons": [reason],
                "rows": [],
            },
            {
                "attributable": False,
                "container_id": initial_container_id,
                "reasons": [reason],
            },
        )


def flyway_checksum(payload: bytes) -> int:
    checksum = 0
    for line in payload.decode("utf-8-sig").splitlines():
        checksum = zlib.crc32(line.encode("utf-8"), checksum)
    return checksum if checksum < 2**31 else checksum - 2**32


def capture_running_migration_scripts(
    initial_container_id: str | None,
) -> dict[str, Any]:
    try:
        container = container_for_observation("route-api", initial_container_id)
        if not container.get("State", {}).get("Running"):
            raise RuntimeError("initial production route-api container is not running")
        container_id = str(container["Id"])
        tar_payload = run_bytes("docker", "cp", f"{container_id}:/app/app.jar", "-")
        with tarfile.open(fileobj=io.BytesIO(tar_payload), mode="r|*") as archive:
            jar_payload = b""
            for member in archive:
                if not member.isfile():
                    continue
                extracted = archive.extractfile(member)
                if extracted is not None:
                    jar_payload = extracted.read()
                    break
            if not jar_payload:
                raise RuntimeError("docker cp app.jar archive has no file payload")
        with zipfile.ZipFile(io.BytesIO(jar_payload)) as application:
            migration_paths = sorted(
                path for path in application.namelist()
                if path.startswith("BOOT-INF/classes/db/migration/")
                and re.fullmatch(r"(?:V[^/]+__[^/]+|R__[^/]+)\.sql", pathlib.PurePosixPath(path).name)
            )
            if not migration_paths:
                raise RuntimeError("running route-api exposed no migration scripts")
            scripts: list[dict[str, Any]] = []
            for path in migration_paths:
                payload = application.read(path)
                scripts.append({
                    "script": pathlib.PurePosixPath(path).name,
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "flyway_checksum": flyway_checksum(payload),
                })
        return {
            "attributable": True,
            "container_id": container_id,
            "scripts": scripts,
            "reasons": [],
        }
    except (
        OSError,
        UnicodeDecodeError,
        subprocess.CalledProcessError,
        tarfile.TarError,
        zipfile.BadZipFile,
        KeyError,
        RuntimeError,
    ) as exc:
        return {
            "attributable": False,
            "container_id": initial_container_id,
            "reasons": [safe_capture_exception(exc)],
            "scripts": [],
        }


def numeric_flyway_version(value: Any) -> tuple[int, ...] | None:
    version_text = str(value)
    if not re.fullmatch(r"\d+(?:\.\d+)*", version_text):
        return None
    return tuple(int(part) for part in version_text.split("."))


def migration_script_version(script_name: str) -> tuple[int, ...] | None:
    match = re.fullmatch(r"V(\d+(?:_\d+)*)__.+\.sql", script_name)
    if match is None:
        return None
    return tuple(int(part) for part in match.group(1).split("_"))


def verify_migration_lineage(
    history: dict[str, Any], migrations: dict[str, Any]
) -> list[str]:
    if not history.get("attributable") or not migrations.get("attributable"):
        return ["Flyway history or running migration scripts could not be attributed"]
    reasons: list[str] = []
    script_entries: dict[str, dict[str, Any]] = {}
    for item in migrations.get("scripts", []):
        if not isinstance(item, dict):
            reasons.append("running route-api contains malformed migration metadata")
            continue
        script_name = item.get("script")
        if not isinstance(script_name, str) or not script_name:
            reasons.append("running route-api contains a migration without a script name")
            continue
        if script_name in script_entries:
            reasons.append(
                f"running route-api contains duplicate migration name: {script_name}"
            )
        script_entries[script_name] = item

    history_entries: dict[str, list[dict[str, Any]]] = {}
    baseline_rows: list[dict[str, Any]] = []
    for row in history.get("rows", []):
        if not isinstance(row, dict):
            reasons.append("Flyway history contains a malformed row")
            continue
        script_name = row.get("script")
        is_baseline = (
            row.get("type") == "BASELINE"
            or script_name == "<< Flyway Baseline >>"
        )
        if is_baseline:
            baseline_rows.append(row)
            if row.get("type") != "BASELINE":
                reasons.append("Flyway baseline row does not have BASELINE type")
            if script_name != "<< Flyway Baseline >>":
                reasons.append("Flyway baseline row has an unexpected script marker")
            if row.get("description") != "<< Flyway Baseline >>":
                reasons.append("Flyway baseline row has an unexpected description")
            if row.get("checksum") is not None:
                reasons.append("Flyway baseline row unexpectedly has a checksum")
            if row.get("success") is not True:
                reasons.append("Flyway baseline row is unsuccessful")
            continue
        if not isinstance(script_name, str) or not script_name:
            reasons.append(
                f"Flyway rank {row.get('installed_rank')} has no script attribution"
            )
            continue
        history_entries.setdefault(script_name, []).append(row)
        script = script_entries.get(script_name)
        if script is None:
            reasons.append(
                f"applied Flyway script is absent from running route-api: {script_name}"
            )
            continue
        expected_description = (
            pathlib.PurePath(script_name)
            .stem.split("__", 1)[-1]
            .replace("_", " ")
        )
        if row.get("description") != expected_description:
            reasons.append(
                f"Flyway description does not match running script: {script_name}"
            )
        if (
            not isinstance(row.get("checksum"), int)
            or row.get("checksum") != script.get("flyway_checksum")
        ):
            reasons.append(
                f"Flyway checksum does not match running script: {script_name}"
            )
        if row.get("type") != "SQL":
            reasons.append(f"Flyway row is not an SQL migration: {script_name}")
        if row.get("success") is not True:
            reasons.append(f"Flyway row is unsuccessful: {script_name}")
        script_version = migration_script_version(script_name)
        if script_name.startswith("V"):
            if script_version is None or numeric_flyway_version(
                row.get("version")
            ) != script_version:
                reasons.append(
                    f"Flyway version does not match running script: {script_name}"
                )
        elif row.get("version") is not None:
            reasons.append(
                f"repeatable Flyway migration unexpectedly has a version: {script_name}"
            )

    if len(baseline_rows) > 1:
        reasons.append("Flyway history contains multiple baseline rows")
    baseline_version: tuple[int, ...] | None = None
    if baseline_rows:
        baseline_version = numeric_flyway_version(baseline_rows[0].get("version"))
        if baseline_version is None:
            reasons.append("Flyway baseline version is not an ordered numeric lineage")

    prior_rank: int | None = None
    prior_version: tuple[int, ...] | None = None
    for row in history.get("rows", []):
        if not isinstance(row, dict):
            continue
        rank = row.get("installed_rank")
        if not isinstance(rank, int) or (
            prior_rank is not None and rank <= prior_rank
        ):
            reasons.append("Flyway installed_rank lineage is not strictly increasing")
        else:
            prior_rank = rank
        version = row.get("version")
        if version is None:
            continue
        version_key = numeric_flyway_version(version)
        if version_key is None:
            reasons.append(
                f"Flyway version is not an ordered numeric lineage: {version}"
            )
            continue
        if prior_version is not None and version_key <= prior_version:
            reasons.append(
                "Flyway version lineage is not strictly increasing by installed rank"
            )
        prior_version = version_key

    for script_name in script_entries:
        rows = history_entries.get(script_name, [])
        script_version = migration_script_version(script_name)
        is_baselined_migration = (
            baseline_version is not None
            and script_version is not None
            and script_version <= baseline_version
        )
        if not rows and not is_baselined_migration:
            reasons.append(
                f"running route-api migration is not applied: {script_name}"
            )
        elif len(rows) > 1:
            reasons.append(
                f"Flyway history contains duplicate script attribution: {script_name}"
            )
    return reasons


def is_template_placeholder(value: str | None) -> bool:
    return bool(value) and (
        value.startswith("replace-with-") or "placeholder" in value.lower()
    )


def verify_cache_identity(
    configured: dict[str, str],
    services: list[dict[str, Any]],
    database_identity: dict[str, Any],
) -> list[str]:
    reasons: list[str] = []
    by_service = {item["service"]: item for item in services}
    scenic = configured.get("MOODRIDE_SCENIC_SCORING_VERSION")
    if not scenic or not SIGNED_SCENIC_VERSION.fullmatch(scenic):
        reasons.append("configured scenic scoring version is not a signed 3.7 release")
    road_revision = configured.get("MOODRIDE_ROAD_DATASET_REVISION")
    road_fingerprint = configured.get("MOODRIDE_ROAD_DATASET_FINGERPRINT")
    anchor_schema = configured.get("MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA")
    configured_runtimes: dict[str, dict[str, str | bool | None]] = {}
    configured_caches: dict[str, dict[str, str | None]] = {}
    expected_database = configured.get("POSTGRES_DB")
    expected_username = configured.get("POSTGRES_USER")
    for key, expected in RUNTIME_POLICY.items():
        if configured.get(key) != expected:
            reasons.append(f"configured {key} is not the accepted {expected!r}")
    for key, value in (
        ("POSTGRES_DB", expected_database),
        ("POSTGRES_USER", expected_username),
        ("MOODRIDE_ROAD_DATASET_REVISION", road_revision),
    ):
        if not value or is_template_placeholder(value):
            reasons.append(f"configured {key} is absent or a template placeholder")
    for name in ROUTING_SERVICES:
        service = by_service.get(name, {})
        configured_cache = service.get("configured_cache_identity", {})
        configured_caches[name] = configured_cache
        for key, expected in (
            ("MOODRIDE_SCENIC_SCORING_VERSION", scenic),
            ("MOODRIDE_ROAD_DATASET_REVISION", road_revision),
            ("MOODRIDE_ROAD_DATASET_FINGERPRINT", road_fingerprint),
            ("MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA", anchor_schema),
        ):
            if not expected or configured_cache.get(key) != expected:
                reasons.append(f"{name} configured {key} does not match .env")
        configured_runtime = service.get("configured_runtime", {})
        configured_runtimes[name] = configured_runtime
        for key, expected in (
            *RUNTIME_POLICY.items(),
            ("MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA", "v1"),
        ):
            if configured_runtime.get(key) != expected:
                reasons.append(
                    f"{name} configured {key} is not the accepted {expected!r}"
                )
        if configured_runtime.get(
            "datasource_url_matches_compose_identity"
        ) is not True:
            reasons.append(
                f"{name} configured datasource URL does not equal compose database identity"
            )
        if configured_runtime.get(
            "datasource_username_matches_compose_identity"
        ) is not True:
            reasons.append(
                f"{name} configured datasource username does not equal compose identity"
            )
    for key in SYMMETRIC_ROUTING_RUNTIME_KEYS:
        if configured_runtimes.get("route-api", {}).get(key) != configured_runtimes.get(
            "route-worker", {}
        ).get(key):
            reasons.append(f"route-api and route-worker configured {key} diverges")
    for key in ROUTING_CACHE_IDENTITY_KEYS:
        if configured_caches.get("route-api", {}).get(key) != configured_caches.get(
            "route-worker", {}
        ).get(key):
            reasons.append(
                f"route-api and route-worker configured cache {key} diverges"
            )
    notification_runtime = by_service.get("notification-service", {}).get(
        "configured_runtime", {}
    )
    if notification_runtime.get("SPRING_PROFILES_ACTIVE") != "prod":
        reasons.append(
            "notification-service configured SPRING_PROFILES_ACTIVE is not 'prod'"
        )
    worker_runtime = configured_runtimes.get("route-worker", {})
    if worker_runtime.get("MOODRIDE_OSRM_BASE_URL") != "http://osrm:5000":
        reasons.append("route-worker configured OSRM endpoint differs from compose identity")
    if worker_runtime.get("MOODRIDE_ALGORITHM_OSRM_REQUEST_PARALLELISM") != "6":
        reasons.append(
            "route-worker configured OSRM request parallelism differs from accepted policy"
        )
    if not database_identity.get("attributable"):
        reasons.append("database scenic/road identity could not be attributed")
    else:
        if database_identity.get("scenic_scoring_version") != scenic:
            reasons.append("database scenic scoring version does not match .env/runtime")
        if database_identity.get("road_dataset_fingerprint") != road_fingerprint:
            reasons.append("database road dataset fingerprint does not match .env/runtime")
    return reasons


def unattributable_document(reason: str) -> dict[str, Any]:
    unavailable = {"attributable": False, "reasons": [reason]}
    return {
        "schema_version": 3,
        "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": "production-runtime-observation",
        "attribution_status": "unattributable",
        "attribution_reasons": [reason],
        "expected_source_revision": None,
        "expected_github_source_url": None,
        "compose_env_identity": nonsecret_env({}),
        "compose_runtime": {
            **unavailable,
            "services": [],
            "final_services": [],
        },
        "database_cache_identity": dict(unavailable),
        "osrm_identity": dict(unavailable),
        "caddy_identity": dict(unavailable),
        "flyway_schema_history": {
            **unavailable,
            "rows": [],
        },
        "running_route_api_migrations": {
            **unavailable,
            "scripts": [],
        },
        "services": [],
    }


def build_capture_document() -> dict[str, Any]:
    configured = load_env_values()
    tag_match = SHA_TAG.fullmatch(configured.get("IMAGE_TAG", ""))
    expected_revision = tag_match.group(1) if tag_match else None
    expected_source_value = os.environ.get("EXPECTED_GITHUB_SOURCE_URL", "")
    expected_source_url = (
        expected_source_value
        if GITHUB_SOURCE_URL.fullmatch(expected_source_value)
        else None
    )
    initial_compose = capture_compose_container_states()
    initial_ids = compose_container_ids(initial_compose)
    services = [
        capture_service(
            service,
            configured,
            initial_ids.get(service),
            expected_revision,
            expected_source_url,
        )
        for service in APPLICATION_SERVICES
    ]
    flyway_history, database_identity = capture_postgres_identity(
        configured, initial_ids.get("postgres")
    )
    migration_scripts = capture_running_migration_scripts(
        initial_ids.get("route-api")
    )
    osrm_identity = capture_osrm_identity(
        configured, initial_ids.get("osrm")
    )
    caddy_identity = capture_caddy_identity(
        configured, initial_ids.get("caddy")
    )
    final_compose = capture_compose_container_states()
    generation_reasons = verify_compose_generation(
        initial_compose, final_compose
    )
    compose_reasons = [
        *initial_compose.get("reasons", []),
        *(
            f"final snapshot: {reason}"
            for reason in final_compose.get("reasons", [])
        ),
        *generation_reasons,
    ]
    compose_runtime = {
        "attributable": not compose_reasons,
        "services": initial_compose.get("services", []),
        "final_services": final_compose.get("services", []),
        "reasons": compose_reasons,
    }

    reasons: list[str] = list(compose_reasons)
    reasons.extend(
        f"osrm: {reason}" for reason in osrm_identity.get("reasons", [])
    )
    reasons.extend(
        f"caddy: {reason}" for reason in caddy_identity.get("reasons", [])
    )
    reasons.extend(
        f"Flyway history: {reason}"
        for reason in flyway_history.get("reasons", [])
    )
    reasons.extend(
        f"running route-api migrations: {reason}"
        for reason in migration_scripts.get("reasons", [])
    )
    reasons.extend(
        f"database identity: {reason}"
        for reason in database_identity.get("reasons", [])
    )
    if expected_revision is None:
        reasons.append("configured IMAGE_TAG is absent or not sha-<40 lowercase hex>")
    namespace = configured.get("GHCR_NAMESPACE", "")
    if (
        not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", namespace)
        or is_template_placeholder(namespace)
    ):
        reasons.append(
            "configured GHCR_NAMESPACE is absent, invalid, or a template placeholder"
        )
    for key in ("POSTGRES_DB", "POSTGRES_USER", "MOODRIDE_ROAD_DATASET_REVISION"):
        value = configured.get(key)
        if not value or is_template_placeholder(value):
            reasons.append(f"configured {key} is absent or a template placeholder")
    for service in services:
        reasons.extend(
            f"{service['service']}: {reason}"
            for reason in service.get("reasons", [])
        )
    sources = {
        service.get("oci_source")
        for service in services
        if service.get("oci_source")
    }
    if expected_source_url is None:
        reasons.append(
            "EXPECTED_GITHUB_SOURCE_URL must be the exact GitHub repository URL"
        )
    elif sources != {expected_source_url}:
        reasons.append(
            "application images do not share the exact expected GitHub OCI source URL"
        )
    reasons.extend(verify_migration_lineage(flyway_history, migration_scripts))
    reasons.extend(
        verify_cache_identity(configured, services, database_identity)
    )

    return {
        "schema_version": 3,
        "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": "production-runtime-observation",
        "attribution_status": "attributable" if not reasons else "unattributable",
        "attribution_reasons": reasons,
        "expected_source_revision": expected_revision,
        "expected_github_source_url": expected_source_url,
        "compose_env_identity": nonsecret_env(configured),
        "compose_runtime": compose_runtime,
        "database_cache_identity": database_identity,
        "osrm_identity": osrm_identity,
        "caddy_identity": caddy_identity,
        "flyway_schema_history": flyway_history,
        "running_route_api_migrations": migration_scripts,
        "services": services,
    }


def main() -> int:
    try:
        document = build_capture_document()
        payload = json.dumps(document, indent=2, sort_keys=True)
    except Exception as exc:
        if isinstance(exc, CaptureError):
            reason = str(exc)
        else:
            reason = (
                "unexpected production runtime capture failure "
                f"({type(exc).__name__})"
            )
        document = unattributable_document(reason)
        payload = json.dumps(document, indent=2, sort_keys=True)
    print(payload)
    return 0 if document["attribution_status"] == "attributable" else 2


if __name__ == "__main__":
    raise SystemExit(main())
