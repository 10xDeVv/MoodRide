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
    "POSTGRES_USER",
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
GITHUB_SOURCE_URL = re.compile(
    r"^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"
)
ROAD_FINGERPRINT_COPY_SQL = r"""
COPY (
  SELECT payload
  FROM (
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
  ) canonical_road_segments
  ORDER BY stable_identity_key COLLATE "C", payload COLLATE "C"
) TO STDOUT;
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
    except OSError:
        return values
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
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
            running = bool(container.get("State", {}).get("Running"))
            services.append({
                "service": service,
                "running": running,
                "container_id": container.get("Id"),
            })
            if not running:
                reasons.append(f"{service}: container is stopped")
        except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, KeyError, IndexError) as exc:
            services.append({"service": service, "running": False, "container_id": None})
            reasons.append(f"{service}: container state is not attributable ({type(exc).__name__})")
    return {"attributable": not reasons, "services": services, "reasons": reasons}


def build_osrm_file_manifest(data_dir: pathlib.Path, basename: str) -> tuple[list[str], str]:
    """Hash normalized relative sidecar names in C-locale byte order."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", basename):
        raise ValueError("OSRM dataset basename contains unsafe characters")
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
        raise ValueError("OSRM sidecar name is not a normalized ASCII relative filename")
    files.sort(key=lambda path: os.fsencode(path.name))
    manifest_lines: list[str] = []
    for path in files:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        manifest_lines.append(f"{digest.hexdigest()}  {path.name}")
    manifest = "".join(f"{line}\n" for line in manifest_lines).encode("utf-8")
    return manifest_lines, hashlib.sha256(manifest).hexdigest()


def capture_caddy_identity(configured: dict[str, str]) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        containers = candidate_containers("caddy")
        if len(containers) != 1:
            raise RuntimeError(f"expected one production caddy container, found {len(containers)}")
        container = containers[0]
        if not container.get("State", {}).get("Running"):
            reasons.append("Caddy container is stopped")
        expected_ref = configured.get("CADDY_IMAGE_REF", "")
        if not re.fullmatch(r"caddy@sha256:[0-9a-f]{64}", expected_ref):
            reasons.append("configured CADDY_IMAGE_REF is not the required official digest reference")
        config = container.get("Config") or {}
        if config.get("Image") != expected_ref:
            reasons.append("Caddy container configured image differs from .env digest reference")
        image = json.loads(run("docker", "image", "inspect", str(container.get("Image"))))[0]
        repo_digests = image.get("RepoDigests") or []
        if expected_ref not in repo_digests:
            reasons.append("Caddy image RepoDigests do not contain the configured digest")
        return {
            "attributable": not reasons,
            "reasons": reasons,
            "image_ref": expected_ref,
            "configured_image_ref": config.get("Image"),
            "repo_digests": repo_digests,
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
        return {"attributable": False, "reasons": [str(exc)]}


def capture_osrm_identity(configured: dict[str, str]) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        containers = candidate_containers("osrm")
        if len(containers) != 1:
            raise RuntimeError(f"expected one production osrm container, found {len(containers)}")
        container = containers[0]
        if not container.get("State", {}).get("Running"):
            reasons.append("OSRM container is stopped")
        expected_ref = configured.get("OSRM_IMAGE_REF", "")
        if not re.fullmatch(
            r"ghcr\.io/project-osrm/osrm-backend@sha256:[0-9a-f]{64}", expected_ref
        ):
            reasons.append("configured OSRM_IMAGE_REF is not the required digest-pinned repository")
        config = container.get("Config") or {}
        if config.get("Image") != expected_ref:
            reasons.append("OSRM container configured image differs from .env digest reference")
        image = json.loads(run("docker", "image", "inspect", str(container.get("Image"))))[0]
        repo_digests = image.get("RepoDigests") or []
        if expected_ref not in repo_digests:
            reasons.append("OSRM image RepoDigests do not contain the configured digest")

        basename = configured.get("OSRM_DATASET_BASENAME", "")
        if basename != "canada-latest":
            reasons.append("OSRM_DATASET_BASENAME is not the accepted canada-latest dataset")
        route_path = f"/data/{basename}.osrm"
        expected_cmd = ["osrm-routed", "--algorithm", "mld", route_path]
        expected_args = ["--algorithm", "mld", route_path]
        config_cmd = config.get("Cmd")
        runtime_args = container.get("Args")
        if config_cmd != expected_cmd:
            reasons.append("OSRM Config.Cmd is not the exact configured MLD route command")
        if runtime_args != expected_args:
            reasons.append("OSRM runtime Args are not the exact configured MLD route arguments")
        manifest_lines, manifest_sha256 = build_osrm_file_manifest(
            ROOT / "data" / "osrm", basename
        )
        return {
            "attributable": not reasons,
            "reasons": reasons,
            "image_ref": expected_ref,
            "repo_digests": repo_digests,
            "dataset_basename": basename,
            "route_path": route_path,
            "config_cmd": config_cmd,
            "args": runtime_args,
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
        return {"attributable": False, "reasons": [str(exc)]}


def capture_service(
    service: str,
    configured: dict[str, str],
    expected_revision: str | None,
    expected_source_url: str | None = None,
) -> dict[str, Any]:
    reasons: list[str] = []
    try:
        containers = candidate_containers(service)
        if len(containers) != 1:
            return {
                "service": service,
                "attributable": False,
                "reasons": [f"expected one production container, found {len(containers)}"],
            }
        container = containers[0]
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
        configured_runtime: dict[str, str | None] = {}
        if service in SPRING_APPLICATION_SERVICES:
            configured_runtime["SPRING_PROFILES_ACTIVE"] = configured_env.get(
                "SPRING_PROFILES_ACTIVE"
            )
        if service in ROUTING_SERVICES:
            for key in RUNTIME_POLICY:
                configured_runtime[key] = configured_env.get(key)
            configured_runtime["MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA"] = configured_env.get(
                "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA"
            )
            configured_runtime["SPRING_DATASOURCE_URL"] = configured_env.get(
                "SPRING_DATASOURCE_URL"
            )
            configured_runtime["SPRING_DATASOURCE_USERNAME"] = configured_env.get(
                "SPRING_DATASOURCE_USERNAME"
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
            for key in (
                "MOODRIDE_SCENIC_SCORING_VERSION",
                "MOODRIDE_ROAD_DATASET_REVISION",
                "MOODRIDE_ROAD_DATASET_FINGERPRINT",
                "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA",
            ):
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
        ValueError,
    ) as exc:
        return {"service": service, "attributable": False, "reasons": [str(exc)]}


def postgres_query(configured: dict[str, str], sql: str) -> str:
    database = configured["POSTGRES_DB"]
    username = configured["POSTGRES_USER"]
    containers = candidate_containers("postgres")
    if len(containers) != 1 or not containers[0].get("State", {}).get("Running"):
        raise RuntimeError(f"expected one running production postgres container, found {len(containers)}")
    return run(
        "docker", "exec", str(containers[0]["Id"]),
        "psql", "--username", username, "--dbname", database,
        "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
        "--set", "ON_ERROR_STOP=1", "--command", sql,
    )


def postgres_stream_sha256(configured: dict[str, str], sql: str) -> tuple[str, int]:
    database = configured["POSTGRES_DB"]
    username = configured["POSTGRES_USER"]
    containers = candidate_containers("postgres")
    if len(containers) != 1 or not containers[0].get("State", {}).get("Running"):
        raise RuntimeError(f"expected one running production postgres container, found {len(containers)}")
    process = subprocess.Popen(
        (
            "docker", "exec", str(containers[0]["Id"]),
            "psql", "--username", username, "--dbname", database,
            "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
            "--set", "ON_ERROR_STOP=1", "--command", sql,
        ),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    digest = hashlib.sha256()
    byte_count = 0
    assert process.stdout is not None
    for chunk in iter(lambda: process.stdout.read(1024 * 1024), b""):
        digest.update(chunk)
        byte_count += len(chunk)
    stderr = process.stderr.read() if process.stderr is not None else b""
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(
            f"PostgreSQL fingerprint stream failed with exit {return_code}: "
            f"{stderr.decode('utf-8', errors='replace').strip()}"
        )
    return digest.hexdigest(), byte_count


def capture_flyway_history(configured: dict[str, str]) -> dict[str, Any]:
    try:
        query = """
SELECT COALESCE(json_agg(row_to_json(history) ORDER BY installed_rank), '[]'::json)
FROM (
  SELECT installed_rank, version, description, type, script, checksum, installed_on, success
  FROM flyway_schema_history
  ORDER BY installed_rank
) history;
"""
        history = json.loads(postgres_query(configured, query))
        if not isinstance(history, list) or not history:
            raise RuntimeError("Flyway history is empty")
        return {"attributable": True, "rows": history, "reasons": []}
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, KeyError, RuntimeError) as exc:
        return {"attributable": False, "reasons": [str(exc)], "rows": []}


def capture_database_identity(configured: dict[str, str]) -> dict[str, Any]:
    try:
        scenic_versions = json.loads(postgres_query(configured, """
SELECT COALESCE(json_agg(version ORDER BY version), '[]'::json)
FROM (SELECT DISTINCT btrim(scoring_version) AS version FROM scenic_score_tiles) versions;
"""))
        if (
            not isinstance(scenic_versions, list)
            or len(scenic_versions) != 1
            or not isinstance(scenic_versions[0], str)
            or not scenic_versions[0]
        ):
            raise RuntimeError("database does not expose exactly one nonblank scenic scoring version")
        road_identity_marker = postgres_query(configured, """
SELECT CASE WHEN
  EXISTS (SELECT 1 FROM road_segments)
  AND NOT EXISTS (
    SELECT 1 FROM road_segments
    WHERE stable_identity_key IS NULL OR btrim(stable_identity_key) = ''
  )
THEN 'road-identity-ok' ELSE 'road-identity-divergent' END;
""").strip()
        if road_identity_marker != "road-identity-ok":
            raise RuntimeError("database road dataset lacks complete V38 stable identity coverage")
        database_fingerprint, database_fingerprint_bytes = postgres_stream_sha256(
            configured,
            """
COPY (
  SELECT jsonb_build_array(installed_rank, version, description, type, script, checksum, success)::text
  FROM flyway_schema_history
  ORDER BY installed_rank
) TO STDOUT;
""".strip(),
        )
        scenic_fingerprint, scenic_fingerprint_bytes = postgres_stream_sha256(
            configured,
            """
COPY (
  SELECT row_to_json(scenic_score_tiles)::text
  FROM scenic_score_tiles
  ORDER BY h3_index
) TO STDOUT;
""".strip(),
        )
        if database_fingerprint_bytes == 0 or scenic_fingerprint_bytes == 0:
            raise RuntimeError("database or scenic identity stream is empty")
        road_fingerprint, road_fingerprint_bytes = postgres_stream_sha256(
            configured, ROAD_FINGERPRINT_COPY_SQL
        )
        if road_fingerprint_bytes == 0:
            raise RuntimeError("database road fingerprint stream is empty")
        if not SIGNED_SCENIC_VERSION.fullmatch(scenic_versions[0]):
            raise RuntimeError("database scenic scoring version is not a signed 3.7 release")
        return {
            "attributable": True,
            "scenic_scoring_version": scenic_versions[0],
            "database_fingerprint": database_fingerprint,
            "scenic_dataset_fingerprint": scenic_fingerprint,
            "road_dataset_fingerprint": road_fingerprint,
            "road_fingerprint_stream_bytes": road_fingerprint_bytes,
            "reasons": [],
        }
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, KeyError, RuntimeError) as exc:
        return {"attributable": False, "reasons": [str(exc)]}


def flyway_checksum(payload: bytes) -> int:
    checksum = 0
    for line in payload.decode("utf-8-sig").splitlines():
        checksum = zlib.crc32(line.encode("utf-8"), checksum)
    return checksum if checksum < 2**31 else checksum - 2**32


def capture_running_migration_scripts() -> dict[str, Any]:
    try:
        containers = candidate_containers("route-api")
        if len(containers) != 1 or not containers[0].get("State", {}).get("Running"):
            raise RuntimeError(f"expected one running production route-api container, found {len(containers)}")
        container_id = str(containers[0]["Id"])
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
        return {"attributable": True, "scripts": scripts, "reasons": []}
    except (
        OSError,
        UnicodeDecodeError,
        subprocess.CalledProcessError,
        tarfile.TarError,
        zipfile.BadZipFile,
        KeyError,
        RuntimeError,
    ) as exc:
        return {"attributable": False, "reasons": [str(exc)], "scripts": []}


def verify_migration_lineage(history: dict[str, Any], migrations: dict[str, Any]) -> list[str]:
    if not history["attributable"] or not migrations["attributable"]:
        return ["Flyway history or running migration scripts could not be attributed"]
    reasons: list[str] = []
    script_entries: dict[str, dict[str, Any]] = {}
    for item in migrations["scripts"]:
        script_name = item["script"]
        if script_name in script_entries:
            reasons.append(f"running route-api contains duplicate migration name: {script_name}")
        script_entries[script_name] = item

    history_entries: dict[str, list[dict[str, Any]]] = {}
    for row in history["rows"]:
        script_name = row.get("script")
        if not isinstance(script_name, str) or not script_name:
            reasons.append(f"Flyway rank {row.get('installed_rank')} has no script attribution")
            continue
        history_entries.setdefault(script_name, []).append(row)
        script = script_entries.get(script_name)
        if script is None:
            reasons.append(f"applied Flyway script is absent from running route-api: {script_name}")
            continue
        expected_description = pathlib.PurePath(script_name).stem.split("__", 1)[-1].replace("_", " ")
        if row.get("description") != expected_description:
            reasons.append(f"Flyway description does not match running script: {script_name}")
        if not isinstance(row.get("checksum"), int) or row.get("checksum") != script.get("flyway_checksum"):
            reasons.append(f"Flyway checksum does not match running script: {script_name}")
        if row.get("type") != "SQL":
            reasons.append(f"Flyway row is not an SQL migration: {script_name}")
        if row.get("success") is not True:
            reasons.append(f"Flyway row is unsuccessful: {script_name}")
        if script_name.startswith("V"):
            expected_version = script_name.split("__", 1)[0][1:].replace("_", ".")
            if str(row.get("version")) != expected_version:
                reasons.append(f"Flyway version does not match running script: {script_name}")
        elif row.get("version") is not None:
            reasons.append(f"repeatable Flyway migration unexpectedly has a version: {script_name}")

    prior_rank: int | None = None
    prior_version: tuple[int, ...] | None = None
    for row in history["rows"]:
        rank = row.get("installed_rank")
        if not isinstance(rank, int) or (prior_rank is not None and rank <= prior_rank):
            reasons.append("Flyway installed_rank lineage is not strictly increasing")
        elif prior_rank is None or rank > prior_rank:
            prior_rank = rank
        version = row.get("version")
        if version is None:
            continue
        version_text = str(version)
        if not re.fullmatch(r"\d+(?:\.\d+)*", version_text):
            reasons.append(f"Flyway version is not an ordered numeric lineage: {version_text}")
            continue
        version_key = tuple(int(part) for part in version_text.split("."))
        if prior_version is not None and version_key <= prior_version:
            reasons.append("Flyway version lineage is not strictly increasing by installed rank")
        prior_version = version_key

    for script_name in script_entries:
        rows = history_entries.get(script_name, [])
        if not rows:
            reasons.append(f"running route-api migration is not applied: {script_name}")
        elif len(rows) != 1:
            reasons.append(f"Flyway history contains duplicate script attribution: {script_name}")
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
    configured_runtimes: dict[str, dict[str, str | None]] = {}
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
        expected_url = f"jdbc:postgresql://postgres:5432/{expected_database}"
        if name == "route-worker":
            expected_url += "?reWriteBatchedInserts=true"
        if (
            not expected_database
            or configured_runtime.get("SPRING_DATASOURCE_URL") != expected_url
        ):
            reasons.append(
                f"{name} configured datasource URL does not equal compose database identity"
            )
        if (
            not expected_username
            or configured_runtime.get("SPRING_DATASOURCE_USERNAME") != expected_username
        ):
            reasons.append(
                f"{name} configured datasource username does not equal compose identity"
            )
    for key in (*RUNTIME_POLICY, "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA"):
        if configured_runtimes.get("route-api", {}).get(key) != configured_runtimes.get(
            "route-worker", {}
        ).get(key):
            reasons.append(f"route-api and route-worker configured {key} diverges")
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


def main() -> None:
    configured = load_env_values()
    tag_match = SHA_TAG.fullmatch(configured.get("IMAGE_TAG", ""))
    expected_revision = tag_match.group(1) if tag_match else None
    expected_source_value = os.environ.get("EXPECTED_GITHUB_SOURCE_URL", "")
    expected_source_url = (
        expected_source_value if GITHUB_SOURCE_URL.fullmatch(expected_source_value) else None
    )
    compose_runtime = capture_compose_container_states()
    services = [
        capture_service(service, configured, expected_revision, expected_source_url)
        for service in APPLICATION_SERVICES
    ]
    flyway_history = capture_flyway_history(configured)
    migration_scripts = capture_running_migration_scripts()
    database_identity = capture_database_identity(configured)
    osrm_identity = capture_osrm_identity(configured)
    caddy_identity = capture_caddy_identity(configured)

    reasons: list[str] = list(compose_runtime["reasons"])
    reasons.extend(f"osrm: {reason}" for reason in osrm_identity.get("reasons", []))
    reasons.extend(f"caddy: {reason}" for reason in caddy_identity.get("reasons", []))
    if expected_revision is None:
        reasons.append("configured IMAGE_TAG is absent or not sha-<40 lowercase hex>")
    namespace = configured.get("GHCR_NAMESPACE", "")
    if (
        not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", namespace)
        or is_template_placeholder(namespace)
    ):
        reasons.append("configured GHCR_NAMESPACE is absent, invalid, or a template placeholder")
    for key in ("POSTGRES_DB", "POSTGRES_USER", "MOODRIDE_ROAD_DATASET_REVISION"):
        value = configured.get(key)
        if not value or is_template_placeholder(value):
            reasons.append(f"configured {key} is absent or a template placeholder")
    for service in services:
        reasons.extend(f"{service['service']}: {reason}" for reason in service.get("reasons", []))
    sources = {service.get("oci_source") for service in services if service.get("oci_source")}
    if expected_source_url is None:
        reasons.append("EXPECTED_GITHUB_SOURCE_URL must be the exact GitHub repository URL")
    elif sources != {expected_source_url}:
        reasons.append("application images do not share the exact expected GitHub OCI source URL")
    reasons.extend(verify_migration_lineage(flyway_history, migration_scripts))
    reasons.extend(verify_cache_identity(configured, services, database_identity))

    document = {
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
    print(json.dumps(document, indent=2, sort_keys=True))
    if reasons:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
