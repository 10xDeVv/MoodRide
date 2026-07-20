#!/usr/bin/env python3
"""Narrow structural assertions for the immutable production release workflow."""

from __future__ import annotations

import base64
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

try:
    import yaml
except ModuleNotFoundError as exc:  # pragma: no cover - operator prerequisite message
    raise SystemExit("PyYAML is required to parse deploy-prod.yml") from exc

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "deploy-prod.yml"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def step_by_name(job: dict, name: str) -> dict:
    for step in job.get("steps", []):
        if step.get("name") == name:
            return step
    raise AssertionError(f"Missing workflow step: {name}")


def run_behavior_fixtures(release_script: str) -> None:
    capture_path = ROOT / "scripts" / "deploy" / "capture_prod_runtime.py"
    spec = importlib.util.spec_from_file_location("capture_prod_runtime_fixture", capture_path)
    require(spec is not None and spec.loader is not None, "Could not load runtime capture fixture")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    capture_revision = "0123456789abcdef0123456789abcdef01234567"
    capture_digest = "a" * 64
    capture_ref = (
        "ghcr.io/acme/moodride-route-worker@sha256:" + capture_digest
    )
    capture_source = "https://github.com/acme/wayward"
    capture_container = {
        "Id": "fixture-route-worker",
        "Image": "sha256:" + ("b" * 64),
        "State": {"Running": True},
        "Config": {"Image": capture_ref},
    }
    capture_image = {
        "RepoDigests": [capture_ref],
        "Config": {
            "Labels": {
                "org.opencontainers.image.revision": capture_revision,
                "org.opencontainers.image.source": capture_source,
            }
        },
    }
    configured_environment = {
        "SPRING_PROFILES_ACTIVE": "prod",
        "MOODRIDE_ALGORITHM_PROFILE": "hybrid_osrm_v2",
        "MOODRIDE_ALGORITHM_MODE": "drive",
        "MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED": "false",
        "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA": "v1",
        "MOODRIDE_SCENIC_SCORING_VERSION": "3.7-fixture",
        "MOODRIDE_ROAD_DATASET_REVISION": "fixture-road-revision",
        "MOODRIDE_ROAD_DATASET_FINGERPRINT": "c" * 64,
        "SPRING_DATASOURCE_URL":
            "jdbc:postgresql://postgres:5432/moodride?reWriteBatchedInserts=true",
        "SPRING_DATASOURCE_USERNAME": "moodride",
        "MOODRIDE_OSRM_BASE_URL": "http://osrm:5000",
        "MOODRIDE_ALGORITHM_OSRM_REQUEST_PARALLELISM": "6",
        "SPRING_DATASOURCE_PASSWORD": "credential-fixture-must-not-be-emitted",
    }
    module.candidate_containers = lambda service: [capture_container]
    module.run = lambda *args: json.dumps([capture_image])
    module.container_env = lambda container: configured_environment
    captured_service = module.capture_service(
        "route-worker",
        {
            "GHCR_NAMESPACE": "acme",
            "ROUTE_WORKER_IMAGE_REF": capture_ref,
        },
        "fixture-route-worker",
        capture_revision,
        capture_source,
    )
    require(captured_service["attributable"],
            "Configured runtime capture fixture was not attributable")
    require(
        captured_service["configured_runtime"]["MOODRIDE_ALGORITHM_PROFILE"]
        == "hybrid_osrm_v2"
        and captured_service["configured_runtime"]["MOODRIDE_ALGORITHM_MODE"]
        == "drive",
        "Runtime environment was not reported as configured algorithm/mode",
    )
    require(
        "runtime_mode" not in captured_service
        and "effective_cache_identity" not in captured_service
        and captured_service["configured_cache_identity"][
            "MOODRIDE_ROAD_DATASET_REVISION"
        ] == "fixture-road-revision",
        "Runtime capture still labels configured container environment as effective",
    )
    captured_service_json = json.dumps(captured_service)
    require(
        "credential-fixture-must-not-be-emitted" not in captured_service_json
        and "SPRING_DATASOURCE_PASSWORD" not in captured_service_json
        and "SPRING_DATASOURCE_URL" not in captured_service_json
        and "SPRING_DATASOURCE_USERNAME" not in captured_service_json
        and "jdbc:postgresql" not in captured_service_json,
        "Runtime capture emitted a datasource credential",
    )
    symmetric_runtime = {
        **module.RUNTIME_POLICY,
        "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA": "v1",
        "datasource_url_matches_compose_identity": True,
        "datasource_username_matches_compose_identity": True,
    }
    symmetric_cache = {
        key: configured_environment[key]
        for key in module.ROUTING_CACHE_IDENTITY_KEYS
    }
    routing_identity_services = [
        {
            "service": "route-api",
            "configured_runtime": dict(symmetric_runtime),
            "configured_cache_identity": dict(symmetric_cache),
        },
        {
            "service": "route-worker",
            "configured_runtime": {
                **symmetric_runtime,
                "MOODRIDE_OSRM_BASE_URL": "http://osrm:5000",
                "MOODRIDE_ALGORITHM_OSRM_REQUEST_PARALLELISM": "6",
            },
            "configured_cache_identity": dict(symmetric_cache),
        },
        {
            "service": "notification-service",
            "configured_runtime": {"SPRING_PROFILES_ACTIVE": "prod"},
        },
    ]
    routing_identity_env = {
        **configured_environment,
        "POSTGRES_DB": "moodride",
        "POSTGRES_USER": "moodride",
    }
    routing_database_identity = {
        "attributable": True,
        "scenic_scoring_version": configured_environment[
            "MOODRIDE_SCENIC_SCORING_VERSION"
        ],
        "road_dataset_fingerprint": configured_environment[
            "MOODRIDE_ROAD_DATASET_FINGERPRINT"
        ],
    }
    require(
        module.verify_cache_identity(
            routing_identity_env,
            routing_identity_services,
            routing_database_identity,
        )
        == [],
        "Symmetric route-api/worker runtime and cache identity was rejected",
    )
    divergent_routing_services = copy.deepcopy(routing_identity_services)
    divergent_routing_services[1]["configured_cache_identity"][
        "MOODRIDE_ROAD_DATASET_FINGERPRINT"
    ] = "d" * 64
    require(
        any(
            "configured cache MOODRIDE_ROAD_DATASET_FINGERPRINT diverges" in reason
            for reason in module.verify_cache_identity(
                routing_identity_env,
                divergent_routing_services,
                routing_database_identity,
            )
        ),
        "Route-api/worker cache identity divergence was not rejected symmetrically",
    )
    replacement_container = {
        **capture_container,
        "Id": "replacement-route-worker",
    }
    module.candidate_containers = lambda service: [replacement_container]
    replaced_service = module.capture_service(
        "route-worker",
        {
            "GHCR_NAMESPACE": "acme",
            "ROUTE_WORKER_IMAGE_REF": capture_ref,
        },
        "fixture-route-worker",
        capture_revision,
        capture_source,
    )
    require(
        not replaced_service["attributable"]
        and any("generation changed" in reason for reason in replaced_service["reasons"]),
        "Mixed-generation service observation was not rejected",
    )
    initial_generation = {
        "services": [
            {
                "service": service,
                "container_id": f"fixture-{service}",
                "running": True,
            }
            for service in module.EXPECTED_COMPOSE_SERVICES
        ]
    }
    final_generation = copy.deepcopy(initial_generation)
    next(
        item
        for item in final_generation["services"]
        if item["service"] == "route-worker"
    )["container_id"] = "replacement-route-worker"
    require(
        any(
            "route-worker: container generation changed" in reason
            for reason in module.verify_compose_generation(
                initial_generation, final_generation
            )
        ),
        "Final Compose recapture did not reject a container replacement",
    )
    module.candidate_containers = lambda service: [capture_container]

    migration_root = ROOT / "services" / "route-api" / "src" / "main" / "resources" / "db" / "migration"
    expected_checksums = {
        "V38__add_road_segment_stable_identity.sql": 1443186875,
        "V39__add_route_job_lifecycle_fencing.sql": -2068215762,
        "V40__add_route_job_dispatch_outbox.sql": -1969470883,
        "V41__add_route_job_terminal_event_outbox.sql": -819117119,
    }
    scripts: list[dict] = []
    rows: list[dict] = []
    for rank, (name, expected_checksum) in enumerate(expected_checksums.items(), start=38):
        checksum = module.flyway_checksum((migration_root / name).read_bytes())
        require(checksum == expected_checksum, f"Flyway checksum fixture diverged for {name}")
        scripts.append({"script": name, "flyway_checksum": checksum, "sha256": "fixture"})
        rows.append({
            "installed_rank": rank,
            "version": str(rank),
            "description": pathlib.PurePath(name).stem.split("__", 1)[1].replace("_", " "),
            "type": "SQL",
            "script": name,
            "checksum": checksum,
            "success": True,
        })
    good_history = {"attributable": True, "rows": rows}
    good_scripts = {"attributable": True, "scripts": scripts}
    require(module.verify_migration_lineage(good_history, good_scripts) == [],
            "Exact Flyway lineage fixture was rejected")
    divergent_rows = [dict(row) for row in rows]
    divergent_rows[-1]["checksum"] += 1
    require(any("checksum" in reason for reason in module.verify_migration_lineage(
        {"attributable": True, "rows": divergent_rows}, good_scripts
    )), "Divergent Flyway checksum fixture was not rejected")
    reordered_rows = [dict(row) for row in rows]
    reordered_rows[1], reordered_rows[2] = reordered_rows[2], reordered_rows[1]
    require(any("increasing" in reason for reason in module.verify_migration_lineage(
        {"attributable": True, "rows": reordered_rows}, good_scripts
    )), "Out-of-order Flyway installed-rank/version fixture was not rejected")

    baselined_scripts = {
        "attributable": True,
        "scripts": [
            {
                "script": "V1__initial_schema.sql",
                "flyway_checksum": 101,
                "sha256": "fixture-v1",
            },
            {
                "script": "V2__baseline_schema.sql",
                "flyway_checksum": 202,
                "sha256": "fixture-v2",
            },
            {
                "script": "V3__post_baseline.sql",
                "flyway_checksum": 303,
                "sha256": "fixture-v3",
            },
        ],
    }
    baselined_rows = [
        {
            "installed_rank": 1,
            "version": "2",
            "description": "<< Flyway Baseline >>",
            "type": "BASELINE",
            "script": "<< Flyway Baseline >>",
            "checksum": None,
            "success": True,
        },
        {
            "installed_rank": 2,
            "version": "3",
            "description": "post baseline",
            "type": "SQL",
            "script": "V3__post_baseline.sql",
            "checksum": 303,
            "success": True,
        },
    ]
    require(
        module.verify_migration_lineage(
            {"attributable": True, "rows": baselined_rows},
            baselined_scripts,
        )
        == [],
        "Flyway baseline-v2 lineage fixture was rejected",
    )
    bad_post_baseline_rows = copy.deepcopy(baselined_rows)
    bad_post_baseline_rows[-1]["checksum"] = 304
    require(
        any(
            "checksum" in reason
            for reason in module.verify_migration_lineage(
                {"attributable": True, "rows": bad_post_baseline_rows},
                baselined_scripts,
            )
        ),
        "Post-baseline Flyway checksum drift was not rejected",
    )

    postgres_snapshot_metadata = {
        "flyway_history": baselined_rows,
        "scenic_versions": ["3.7-fixture"],
        "road_identity_marker": "road-identity-ok",
    }
    postgres_snapshot_sections = {
        "database_fingerprint": b"flyway snapshot row\n",
        "scenic_dataset_fingerprint": b"scenic snapshot row\n",
        "road_dataset_fingerprint": b"road snapshot row\n",
    }
    postgres_snapshot_stream = (
        b"metadata\t"
        + json.dumps(
            postgres_snapshot_metadata,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8").hex().encode("ascii")
        + b"\n"
        + b"".join(
            section.encode("ascii") + b"\t" + payload
            for section, payload in postgres_snapshot_sections.items()
        )
    )
    postgres_snapshot_commands: list[tuple[str, ...]] = []

    class FakePostgresSnapshotProcess:
        def __init__(self, command: tuple[str, ...], **kwargs: object):
            postgres_snapshot_commands.append(command)
            self.stdout = io.BytesIO(postgres_snapshot_stream)
            self.stderr = io.BytesIO()

        def wait(self) -> int:
            return 0

    fixture_candidate_containers = module.candidate_containers
    fixture_popen = module.subprocess.Popen
    module.candidate_containers = lambda service: [{
        "Id": "fixture-postgres",
        "State": {"Running": True},
    }]
    module.subprocess.Popen = FakePostgresSnapshotProcess
    captured_history, captured_database = module.capture_postgres_identity(
        {
            "POSTGRES_DB": "moodride",
            "POSTGRES_USER": "moodride",
        },
        "fixture-postgres",
    )
    require(
        len(postgres_snapshot_commands) == 1
        and "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
        in postgres_snapshot_commands[0][-1]
        and captured_history["attributable"]
        and captured_history["rows"] == baselined_rows
        and captured_database["attributable"]
        and all(
            captured_database[name] == hashlib.sha256(payload).hexdigest()
            for name, payload in postgres_snapshot_sections.items()
        ),
        "Postgres runtime identity was not captured from one coherent snapshot",
    )
    module.candidate_containers = fixture_candidate_containers
    module.subprocess.Popen = fixture_popen

    original_root = module.ROOT
    original_env_file = module.ENV_FILE
    original_candidate_containers = module.candidate_containers
    original_run = module.run
    original_copy_container_file = module.copy_container_file
    with tempfile.TemporaryDirectory(dir=ROOT) as capture_temporary:
        capture_temp = pathlib.Path(capture_temporary)
        canonical_caddyfile = capture_temp / "canonical" / "Caddyfile"
        canonical_caddyfile.parent.mkdir()
        canonical_caddyfile.write_bytes(b"fixture canonical caddy\n")
        wrong_caddyfile = capture_temp / "wrong" / "Caddyfile"
        wrong_caddyfile.parent.mkdir()
        wrong_caddyfile.write_bytes(b"fixture wrong caddy\n")
        canonical_osrm = capture_temp / "data" / "osrm"
        canonical_osrm.mkdir(parents=True)
        (canonical_osrm / "canada-latest.osrm").write_bytes(b"osrm fixture")
        wrong_osrm = capture_temp / "wrong-osrm"
        wrong_osrm.mkdir()
        module.ROOT = capture_temp

        caddy_ref = "caddy@sha256:" + capture_digest
        caddy_container = {
            "Id": "fixture-caddy",
            "Image": "fixture-caddy-image",
            "State": {"Running": True},
            "Config": {"Image": caddy_ref},
            "Mounts": [{
                "Destination": "/etc/caddy/Caddyfile",
                "Source": str(wrong_caddyfile),
                "Type": "bind",
                "RW": False,
            }],
        }
        module.candidate_containers = lambda service: [caddy_container]
        module.run = lambda *args: json.dumps([{"RepoDigests": [caddy_ref]}])
        module.copy_container_file = (
            lambda container_id, path: canonical_caddyfile.read_bytes()
        )
        caddy_mount_drift = module.capture_caddy_identity(
            {
                "CADDY_IMAGE_REF": caddy_ref,
                "CADDYFILE_PATH": str(canonical_caddyfile),
            },
            "fixture-caddy",
        )
        require(
            not caddy_mount_drift["attributable"]
            and any(
                "canonical source" in reason
                for reason in caddy_mount_drift["reasons"]
            ),
            "Wrong Caddyfile bind mount was not rejected",
        )
        caddy_container["Mounts"][0]["Source"] = str(canonical_caddyfile)
        module.copy_container_file = lambda container_id, path: b"stale caddy"
        caddy_content_drift = module.capture_caddy_identity(
            {
                "CADDY_IMAGE_REF": caddy_ref,
                "CADDYFILE_PATH": str(canonical_caddyfile),
            },
            "fixture-caddy",
        )
        require(
            not caddy_content_drift["attributable"]
            and any(
                "content differs" in reason
                for reason in caddy_content_drift["reasons"]
            ),
            "Mounted Caddyfile content drift was not rejected",
        )

        osrm_ref = (
            "ghcr.io/project-osrm/osrm-backend@sha256:" + capture_digest
        )
        osrm_route_path = "/data/canada-latest.osrm"
        osrm_container = {
            "Id": "fixture-osrm",
            "Image": "fixture-osrm-image",
            "State": {"Running": True},
            "Config": {
                "Image": osrm_ref,
                "Cmd": [
                    "osrm-routed",
                    "--algorithm",
                    "mld",
                    osrm_route_path,
                ],
            },
            "Args": ["--algorithm", "mld", osrm_route_path],
            "Mounts": [{
                "Destination": "/data",
                "Source": str(wrong_osrm),
                "Type": "bind",
                "RW": False,
            }],
        }
        module.candidate_containers = lambda service: [osrm_container]
        module.run = lambda *args: json.dumps([{"RepoDigests": [osrm_ref]}])
        osrm_mount_drift = module.capture_osrm_identity(
            {
                "OSRM_IMAGE_REF": osrm_ref,
                "OSRM_DATASET_BASENAME": "canada-latest",
            },
            "fixture-osrm",
        )
        require(
            not osrm_mount_drift["attributable"]
            and any(
                "canonical source" in reason
                for reason in osrm_mount_drift["reasons"]
            ),
            "Wrong OSRM data bind mount was not rejected",
        )

        malformed_env = capture_temp / "non-utf8.env"
        malformed_env.write_bytes(b"IMAGE_TAG=sha-\xff\n")
        module.ENV_FILE = malformed_env
        unicode_stdout = io.StringIO()
        with contextlib.redirect_stdout(unicode_stdout):
            unicode_status = module.main()
        unicode_document = json.loads(unicode_stdout.getvalue())
        require(
            unicode_status == 2
            and unicode_document["schema_version"] == 3
            and unicode_document["attribution_status"] == "unattributable"
            and unicode_document["attribution_reasons"]
            == ["production environment file is not valid UTF-8"],
            "Non-UTF-8 environment failure did not emit consumable JSON",
        )

        original_build_capture_document = module.build_capture_document

        def fail_unexpected_capture() -> dict:
            raise RuntimeError("credential-fixture-must-not-be-emitted")

        module.build_capture_document = fail_unexpected_capture
        unexpected_stdout = io.StringIO()
        with contextlib.redirect_stdout(unexpected_stdout):
            unexpected_status = module.main()
        unexpected_payload = unexpected_stdout.getvalue()
        unexpected_document = json.loads(unexpected_payload)
        require(
            unexpected_status == 2
            and unexpected_document["attribution_status"] == "unattributable"
            and unexpected_document["attribution_reasons"]
            == ["unexpected production runtime capture failure (RuntimeError)"]
            and "credential-fixture-must-not-be-emitted" not in unexpected_payload,
            "Unexpected capture failure was not emitted as secret-safe JSON",
        )
        module.build_capture_document = original_build_capture_document

    module.ROOT = original_root
    module.ENV_FILE = original_env_file
    module.candidate_containers = original_candidate_containers
    module.run = original_run
    module.copy_container_file = original_copy_container_file

    revision = "0123456789abcdef0123456789abcdef01234567"
    digest = "a" * 64
    quality_sha = "b" * 64
    road_sha = "d" * 64
    maximum_bundle_id = f"sha-{'f' * 40}-{'f' * 64}-{'f' * 64}"
    require(len(maximum_bundle_id.encode("ascii")) <= 255,
            "Checksum-versioned control-bundle basename exceeds 255 bytes")
    with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
        temp = pathlib.Path(temporary)
        repositories = {
            "route_api": "moodride-route-api",
            "route_worker": "moodride-route-worker",
            "notification_service": "moodride-notification-service",
            "frontend": "moodride-frontend",
        }
        candidate_digest = "sha256:" + digest
        candidate_images = {
            key: {
                "ref": f"ghcr.io/acme/{repository}@{candidate_digest}",
                "index_digest": candidate_digest,
                "revision": revision,
            }
            for key, repository in repositories.items()
        }
        release_lock = {
            "schema_version": 2,
            "ghcr_namespace": "acme",
            "source_sha": revision,
            "source_url": "https://github.com/acme/wayward",
            "image_tag": f"sha-{revision}",
            "images": {
                key: {
                    "tag": f"ghcr.io/acme/{repository}:sha-{revision}",
                    **candidate_images[key],
                }
                for key, repository in repositories.items()
            },
        }
        release_lock_text = json.dumps(release_lock, separators=(",", ":"), sort_keys=True)
        release_lock_sha = hashlib.sha256(release_lock_text.encode("utf-8")).hexdigest()
        release_lock_file = temp / "canonical-release-lock.json"
        release_lock_file.write_text(
            release_lock_text, encoding="utf-8", newline="\n"
        )
        control_revision = "f" * 40
        control_digest = "sha256:" + ("9" * 64)
        control_images = {
            key: {
                "ref": f"ghcr.io/acme/{repository}@{control_digest}",
                "index_digest": control_digest,
                "revision": control_revision,
            }
            for key, repository in repositories.items()
        }
        cache_policy = {
            "spring_profiles_active": "prod",
            "algorithm_profile": "hybrid_osrm_v2",
            "route_mode": "drive",
            "graph_warmup_enabled": False,
            "road_anchor_cache_schema": "v1",
        }
        control_runtime_identity = {
            "database_identity": {
                "database_fingerprint": "1" * 64,
                "scenic_scoring_version": "3.7-control",
                "scenic_dataset_fingerprint": "2" * 64,
                "road_dataset_fingerprint": "3" * 64,
            },
            "osrm": {
                "image_ref": "ghcr.io/project-osrm/osrm-backend@sha256:" + ("4" * 64),
                "dataset_basename": "canada-latest",
                "file_manifest_sha256": "5" * 64,
            },
            "runtime_algorithm_mode": {
                "algorithm": "hybrid_osrm_v2",
                "mode": "drive",
            },
            "cache_policy": cache_policy,
        }
        candidate_runtime_identity = {
            "database_identity": {
                "database_fingerprint": "a" * 64,
                "scenic_scoring_version": "3.7-control",
                "scenic_dataset_fingerprint": "2" * 64,
                "road_dataset_fingerprint": "3" * 64,
            },
            "osrm": {
                "image_ref": "ghcr.io/project-osrm/osrm-backend@sha256:" + ("4" * 64),
                "dataset_basename": "canada-latest",
                "file_manifest_sha256": "5" * 64,
            },
            "runtime_algorithm_mode": {
                "algorithm": "hybrid_osrm_v2",
                "mode": "drive",
            },
            "cache_policy": cache_policy,
        }
        canonical_quality = {
            "schema_version": 1,
            "verdict": "pass",
            "release_lock_sha256": release_lock_sha,
            "source_sha": revision,
            "image_tag": f"sha-{revision}",
            "scenario_manifest_sha256":
                "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00",
            "scenario_count": 27,
            "route_mode": "drive",
            "artifacts": {
                "control": {
                    "sha256": "6" * 64,
                    "source_sha": control_revision,
                    "release_lock_sha256": "7" * 64,
                    "images": control_images,
                    "runtime_identity": control_runtime_identity,
                },
                "candidate": {
                    "sha256": "8" * 64,
                    "source_sha": revision,
                    "release_lock_sha256": release_lock_sha,
                    "images": candidate_images,
                    "runtime_identity": candidate_runtime_identity,
                },
            },
        }
        quality_with_top_level_images = copy.deepcopy(canonical_quality)
        quality_with_top_level_images["images"] = candidate_images
        quality_with_top_level_digests = copy.deepcopy(canonical_quality)
        quality_with_top_level_digests["image_digests"] = {
            key: image["index_digest"] for key, image in candidate_images.items()
        }
        quality_with_legacy_digests = copy.deepcopy(canonical_quality)
        for artifact in quality_with_legacy_digests["artifacts"].values():
            artifact["image_digests"] = {
                key: image["index_digest"] for key, image in artifact["images"].items()
            }
        quality_with_candidate_mismatch = copy.deepcopy(canonical_quality)
        mismatched_candidate_digest = "sha256:" + ("b" * 64)
        quality_with_candidate_mismatch["artifacts"]["candidate"]["images"]["route_api"].update({
            "ref": f"ghcr.io/acme/moodride-route-api@{mismatched_candidate_digest}",
            "index_digest": mismatched_candidate_digest,
        })
        quality_with_inconsistent_control = copy.deepcopy(canonical_quality)
        quality_with_inconsistent_control["artifacts"]["control"]["images"]["route_api"][
            "index_digest"
        ] = "sha256:" + ("c" * 64)
        quality_with_duplicate_artifacts = copy.deepcopy(canonical_quality)
        quality_with_duplicate_artifacts["artifacts"]["control"]["sha256"] = (
            quality_with_duplicate_artifacts["artifacts"]["candidate"]["sha256"]
        )
        quality_with_shared_aliases = copy.deepcopy(canonical_quality)
        candidate_identity = quality_with_shared_aliases["artifacts"]["candidate"][
            "runtime_identity"
        ]
        for alias in (
            "database_identity",
            "osrm",
            "runtime_algorithm_mode",
            "cache_policy",
        ):
            quality_with_shared_aliases[alias] = copy.deepcopy(candidate_identity[alias])
        quality_missing_control_identity = copy.deepcopy(canonical_quality)
        del quality_missing_control_identity["artifacts"]["control"]["runtime_identity"]["osrm"]
        quality_missing_candidate_identity = copy.deepcopy(canonical_quality)
        del quality_missing_candidate_identity["artifacts"]["candidate"]["runtime_identity"]
        quality_with_scenic_version_mismatch = copy.deepcopy(canonical_quality)
        quality_with_scenic_version_mismatch["artifacts"]["candidate"]["runtime_identity"][
            "database_identity"
        ]["scenic_scoring_version"] = "3.7-other"
        quality_with_scenic_fingerprint_mismatch = copy.deepcopy(canonical_quality)
        quality_with_scenic_fingerprint_mismatch["artifacts"]["candidate"][
            "runtime_identity"
        ]["database_identity"]["scenic_dataset_fingerprint"] = "f" * 64
        quality_with_road_fingerprint_mismatch = copy.deepcopy(canonical_quality)
        quality_with_road_fingerprint_mismatch["artifacts"]["candidate"][
            "runtime_identity"
        ]["database_identity"]["road_dataset_fingerprint"] = "f" * 64
        quality_with_osrm_mismatch = copy.deepcopy(canonical_quality)
        quality_with_osrm_mismatch["artifacts"]["candidate"]["runtime_identity"]["osrm"][
            "file_manifest_sha256"
        ] = "f" * 64
        quality_with_cache_policy_mismatch = copy.deepcopy(canonical_quality)
        quality_with_cache_policy_mismatch["artifacts"]["candidate"]["runtime_identity"][
            "cache_policy"
        ]["graph_warmup_enabled"] = True
        quality_with_algorithm_mismatch = copy.deepcopy(canonical_quality)
        quality_with_algorithm_mismatch["artifacts"]["candidate"]["runtime_identity"][
            "runtime_algorithm_mode"
        ]["algorithm"] = "hybrid_osrm_v3"
        quality_cases = (
            ("canonical", canonical_quality, True),
            ("top-level-images", quality_with_top_level_images, False),
            ("top-level-image-digests", quality_with_top_level_digests, False),
            ("legacy-image-digests", quality_with_legacy_digests, False),
            ("candidate-dispatch-mismatch", quality_with_candidate_mismatch, False),
            ("inconsistent-control-images", quality_with_inconsistent_control, False),
            ("duplicate-artifacts", quality_with_duplicate_artifacts, False),
            ("shared-runtime-aliases", quality_with_shared_aliases, False),
            ("missing-control-runtime-identity", quality_missing_control_identity, False),
            ("missing-candidate-runtime-identity", quality_missing_candidate_identity, False),
            ("scenic-version-mismatch", quality_with_scenic_version_mismatch, False),
            ("scenic-fingerprint-mismatch", quality_with_scenic_fingerprint_mismatch, False),
            ("road-fingerprint-mismatch", quality_with_road_fingerprint_mismatch, False),
            ("osrm-identity-mismatch", quality_with_osrm_mismatch, False),
            ("cache-policy-mismatch", quality_with_cache_policy_mismatch, False),
            ("runtime-algorithm-mismatch", quality_with_algorithm_mismatch, False),
        )
        rendered_release_script = (
            release_script
            .replace("${{ github.repository_owner }}", "acme")
            .replace("${{ github.event_name }}", "workflow_dispatch")
        )
        quality_bash = "bash"
        if os.name == "nt":
            git_bash = pathlib.Path(
                os.environ.get("ProgramFiles", r"C:\Program Files")
            ) / "Git" / "bin" / "bash.exe"
            if git_bash.is_file():
                quality_bash = str(git_bash)
        for case_name, quality, should_pass in quality_cases:
            quality_bytes = json.dumps(
                quality, separators=(",", ":"), sort_keys=True
            ).encode("utf-8")
            quality_file = temp / f"{case_name}.quality.json"
            quality_file.write_bytes(quality_bytes)
            output_file = temp / f"{case_name}.github-output"
            fixture_values = {
                "DISPATCH_OPERATION": "deploy-existing",
                "DISPATCH_IMAGE_TAG": f"sha-{revision}",
                "DISPATCH_RELEASE_LOCK": release_lock_text,
                "DISPATCH_RELEASE_LOCK_SHA256": release_lock_sha,
                "DISPATCH_QUALITY_ACCEPTANCE_B64":
                    base64.b64encode(quality_bytes).decode("ascii"),
                "DISPATCH_QUALITY_ACCEPTANCE_SHA256":
                    hashlib.sha256(quality_bytes).hexdigest(),
                "CONFIGURED_NAMESPACE": "acme",
                "CONFIGURED_API_BASE_URL": "",
                "CONFIGURED_WS_BASE_URL": "",
                "GITHUB_SHA": revision,
                "GITHUB_REPOSITORY": "acme/wayward",
                "GITHUB_OUTPUT": output_file.relative_to(ROOT).as_posix(),
            }
            assignment_prefix = "".join(
                f"{key}={shlex.quote(value)}\n" for key, value in fixture_values.items()
            )
            quality_completed = subprocess.run(
                [quality_bash],
                cwd=ROOT,
                input=(assignment_prefix + rendered_release_script).encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if should_pass:
                require(
                    quality_completed.returncode == 0,
                    "Canonical quality acceptance fixture was rejected: "
                    + quality_completed.stderr.decode("utf-8", errors="replace").strip(),
                )
            else:
                require(
                    quality_completed.returncode != 0,
                    f"Non-canonical quality acceptance fixture was accepted: {case_name}",
                )
            loader_values = {
                "EXPECTED_GITHUB_SOURCE_URL": "https://github.com/acme/wayward",
                "RELEASE_LOCK": release_lock_file.relative_to(ROOT).as_posix(),
                "RELEASE_LOCK_SHA256": release_lock_sha,
                "QUALITY_FILE": quality_file.relative_to(ROOT).as_posix(),
                "QUALITY_SHA256": hashlib.sha256(quality_bytes).hexdigest(),
            }
            loader_prefix = "".join(
                f"{key}={shlex.quote(value)}\n" for key, value in loader_values.items()
            )
            deploy_quality_completed = subprocess.run(
                [quality_bash],
                cwd=ROOT,
                input=(loader_prefix + r"""
set -Eeuo pipefail
export DEPLOY_LIBRARY_ONLY=1
source scripts/deploy/deploy_prod.sh
load_release_lock "$RELEASE_LOCK" "$RELEASE_LOCK_SHA256"
load_quality_acceptance "$QUALITY_FILE" "$QUALITY_SHA256"
""").encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if should_pass:
                require(
                    deploy_quality_completed.returncode == 0,
                    "Deploy loader rejected canonical quality acceptance: "
                    + deploy_quality_completed.stderr.decode(
                        "utf-8", errors="replace"
                    ).strip(),
                )
            else:
                require(
                    deploy_quality_completed.returncode != 0,
                    f"Deploy loader accepted non-canonical quality fixture: {case_name}",
                )
        adoption_runtime_identity = {
            "database_identity": {
                "database_fingerprint": road_sha,
                "scenic_scoring_version": "3.7-fixture",
                "scenic_dataset_fingerprint": road_sha,
                "road_dataset_fingerprint": road_sha,
            },
            "osrm": {
                "image_ref": "ghcr.io/project-osrm/osrm-backend@sha256:" + digest,
                "dataset_basename": "canada-latest",
                "file_manifest_sha256": digest,
            },
            "runtime_algorithm_mode": {
                "algorithm": "hybrid_osrm_v2",
                "mode": "drive",
            },
            "cache_policy": cache_policy,
        }
        adopted_control_lock = copy.deepcopy(release_lock)
        adopted_control_lock["control_adoption"] = {
            "approval_gate": "production-control-adoption",
            "flyway_schema_version": 38,
            "flyway_history_sha256": road_sha,
            "source_sha": revision,
            "images": copy.deepcopy(adopted_control_lock["images"]),
            "runtime_identity": copy.deepcopy(adoption_runtime_identity),
        }
        adopted_control_lock_bytes = json.dumps(
            adopted_control_lock, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        adopted_control_lock_sha = hashlib.sha256(adopted_control_lock_bytes).hexdigest()
        adopted_control_lock_file = temp / "adopted-control-lock.json"
        adopted_control_lock_file.write_bytes(adopted_control_lock_bytes)
        adoption_quality = copy.deepcopy(canonical_quality)
        adoption_quality["artifacts"]["control"].update({
            "source_sha": revision,
            "release_lock_sha256": adopted_control_lock_sha,
            "images": copy.deepcopy(candidate_images),
            "runtime_identity": copy.deepcopy(adoption_runtime_identity),
        })
        adoption_quality_bytes = json.dumps(
            adoption_quality, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        adoption_quality_file = temp / "adopted-control.quality.json"
        adoption_quality_file.write_bytes(adoption_quality_bytes)
        adopted_env = temp / "adopted-control.env"
        adopted_env.write_text(
            "\n".join((
                "GHCR_NAMESPACE=acme",
                f"IMAGE_TAG=sha-{revision}",
                "POSTGRES_DB=moodride",
                "POSTGRES_USER=moodride",
                "POSTGRES_PASSWORD=fixture-postgres-password",
                "REDIS_PASSWORD=fixture-redis-password",
                "MOODRIDE_ANALYTICS_HASH_SECRET=" + ("c" * 64),
                "MOODRIDE_SCENIC_SCORING_VERSION=3.7-fixture",
                "MOODRIDE_ROAD_DATASET_REVISION=fixture-road-revision",
                "MOODRIDE_ROAD_DATASET_FINGERPRINT=" + road_sha,
                "MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA=v1",
                "OSRM_IMAGE_REF=ghcr.io/project-osrm/osrm-backend@sha256:" + digest,
                "CADDY_IMAGE_REF=caddy@sha256:" + digest,
                "OSRM_DATASET_BASENAME=canada-latest",
                "SPRING_PROFILES_ACTIVE=prod",
                "MOODRIDE_ALGORITHM_PROFILE=hybrid_osrm_v2",
                "MOODRIDE_ALGORITHM_MODE=drive",
                "MOODRIDE_CACHE_GRAPH_WARMUP_ENABLED=false",
            )) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        relative_adopted_env = adopted_env.relative_to(ROOT).as_posix()
        fixture_script = (
            f"FIXTURE_REVISION='{revision}'\n"
            f"FIXTURE_DIGEST='{digest}'\n"
            f"FIXTURE_QUALITY_SHA='{quality_sha}'\n"
            f"FIXTURE_ROAD_SHA='{road_sha}'\n"
            f"QUALITY_FILE='{adoption_quality_file.relative_to(ROOT).as_posix()}'\n"
            f"ADOPTION_QUALITY_SHA='{hashlib.sha256(adoption_quality_bytes).hexdigest()}'\n"
            f"ADOPTED_CONTROL_LOCK='{adopted_control_lock_file.relative_to(ROOT).as_posix()}'\n"
            f"ADOPTED_CONTROL_LOCK_SHA256='{adopted_control_lock_sha}'\n"
            f"CONTROL_ENV='{relative_adopted_env}'\n"
            f"FIXTURE_TEMP='{temp.relative_to(ROOT).as_posix()}'\n"
            + r"""#!/usr/bin/env bash
set -Eeuo pipefail
docker() {
  local service format container image argument
  if [ "$1" = "ps" ]; then
    service=""
    for argument in "$@"; do
      case "$argument" in
        label=com.docker.compose.service=*) service="${argument##*=}" ;;
      esac
    done
    [ -n "$service" ] && printf 'fixture-%s\n' "$service"
  elif [ "$1" = "inspect" ]; then
    format="$3"
    container="$4"
    case "$format" in
      *State.Running*) printf 'true\n' ;;
      *'.Config.Cmd'*) printf '/data/canada-latest.osrm\n' ;;
      *'.Image'*) printf 'image-%s\n' "${container#fixture-}" ;;
      *) return 9 ;;
    esac
  elif [ "$1" = "image" ] && [ "$2" = "inspect" ]; then
    format="$4"
    image="$5"
    service="${image#image-}"
    case "$format" in
      *RepoDigests*)
        if [ "$service" = "osrm" ]; then
          printf 'ghcr.io/project-osrm/osrm-backend@sha256:%s\n' "$FIXTURE_DIGEST"
        else
          printf 'ghcr.io/acme/moodride-%s@sha256:%s\n' "$service" "$FIXTURE_DIGEST"
        fi
        ;;
      *org.opencontainers.image.revision*) printf '%s\n' "$FIXTURE_REVISION" ;;
      *org.opencontainers.image.source*) printf 'https://github.com/acme/wayward\n' ;;
      *) return 10 ;;
    esac
  else
    return 11
  fi
}
export DEPLOY_LIBRARY_ONLY=1
source scripts/deploy/deploy_prod.sh
EXPECTED_GITHUB_SOURCE_URL=https://github.com/acme/wayward
validate_configured_running_tag "sha-${FIXTURE_REVISION}" "sha-${FIXTURE_REVISION}"
CONTROL_BUNDLE=/fixture/candidate
PREVIOUS_CONTROL_BUNDLE=/fixture/previous
select_previous_control_bundle
[ "$COMPOSE_FILE" = "/fixture/previous/docker-compose.prod.yml" ]
select_candidate_control_bundle
[ "$COMPOSE_FILE" = "/fixture/candidate/docker-compose.prod.yml" ]
[ "$CADDYFILE_PATH" = "/fixture/candidate/Caddyfile" ]
MOODRIDE_DIR="$PWD/$FIXTURE_TEMP/runtime"
mkdir -p "$MOODRIDE_DIR/.deploy/bundles/.stage"
printf 'trusted\n' > "$MOODRIDE_DIR/.deploy/bundles/.stage/payload"
printf 'fixture caddy\n' > "$MOODRIDE_DIR/.deploy/bundles/.stage/Caddyfile"
(cd "$MOODRIDE_DIR/.deploy/bundles/.stage" && sha256sum Caddyfile payload > bundle.sha256)
fixture_manifest="$(sha256sum "$MOODRIDE_DIR/.deploy/bundles/.stage/bundle.sha256")"
fixture_manifest="${fixture_manifest%% *}"
fixture_bundle_id="adopted-${fixture_manifest}"
fixture_bundle="$MOODRIDE_DIR/.deploy/bundles/${fixture_bundle_id}"
mv "$MOODRIDE_DIR/.deploy/bundles/.stage" "$fixture_bundle"
verify_control_bundle "$fixture_bundle"
printf 'tampered\n' >> "$fixture_bundle/payload"
if (verify_control_bundle "$fixture_bundle" >/dev/null 2>&1); then
  echo "Tampered previous control bundle was accepted." >&2
  exit 14
fi
printf 'trusted\n' > "$fixture_bundle/payload"
verify_control_bundle "$fixture_bundle"
set_env_var CADDYFILE_PATH "$fixture_bundle/Caddyfile" "$CONTROL_ENV"
capture_running_image_refs "$CONTROL_ENV" "$CONTROL_ENV"
ensure_compose_introspection_identity "$CONTROL_ENV"
EXPECTED_CANDIDATE_DATABASE_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CANDIDATE_SCENIC_SCORING_VERSION=3.7-fixture
EXPECTED_CANDIDATE_SCENIC_DATASET_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CANDIDATE_ROAD_DATASET_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CANDIDATE_OSRM_IMAGE_REF="ghcr.io/project-osrm/osrm-backend@sha256:${FIXTURE_DIGEST}"
EXPECTED_CANDIDATE_OSRM_DATASET_BASENAME=canada-latest
EXPECTED_CANDIDATE_OSRM_FILE_MANIFEST_SHA256="${FIXTURE_DIGEST}"
EXPECTED_CANDIDATE_RUNTIME_PROFILE=prod
EXPECTED_CANDIDATE_ALGORITHM_PROFILE=hybrid_osrm_v2
EXPECTED_CANDIDATE_ALGORITHM_MODE=drive
EXPECTED_CANDIDATE_GRAPH_WARMUP_ENABLED=false
EXPECTED_CANDIDATE_ROAD_ANCHOR_CACHE_SCHEMA=v1
EXPECTED_CANDIDATE_RUNTIME_ALGORITHM=hybrid_osrm_v2
validate_pre_drain_env "$CONTROL_ENV" "$fixture_bundle" candidate
candidate_mismatch_env="$MOODRIDE_DIR/candidate-mismatch.env"
cp -p "$CONTROL_ENV" "$candidate_mismatch_env"
set_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT \
  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" \
  "$candidate_mismatch_env"
if validate_pre_drain_env "$candidate_mismatch_env" "$fixture_bundle" candidate >/dev/null 2>&1; then
  echo "Candidate environment runtime-identity mismatch was accepted." >&2
  exit 15
fi
rm -f "$candidate_mismatch_env"
EXPECTED_CONTROL_SOURCE_SHA="${FIXTURE_REVISION}"
EXPECTED_CONTROL_RELEASE_LOCK_SHA256="${FIXTURE_DIGEST}"
EXPECTED_CONTROL_ROUTE_API_IMAGE_REF="ghcr.io/acme/moodride-route-api@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
EXPECTED_CONTROL_ROUTE_WORKER_IMAGE_REF="ghcr.io/acme/moodride-route-worker@sha256:${FIXTURE_DIGEST}"
EXPECTED_CONTROL_NOTIFICATION_SERVICE_IMAGE_REF="ghcr.io/acme/moodride-notification-service@sha256:${FIXTURE_DIGEST}"
EXPECTED_CONTROL_FRONTEND_IMAGE_REF="ghcr.io/acme/moodride-frontend@sha256:${FIXTURE_DIGEST}"
EXPECTED_CONTROL_DATABASE_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CONTROL_SCENIC_SCORING_VERSION=3.7-fixture
EXPECTED_CONTROL_SCENIC_DATASET_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CONTROL_ROAD_DATASET_FINGERPRINT="${FIXTURE_ROAD_SHA}"
EXPECTED_CONTROL_OSRM_IMAGE_REF="ghcr.io/project-osrm/osrm-backend@sha256:${FIXTURE_DIGEST}"
EXPECTED_CONTROL_OSRM_DATASET_BASENAME=canada-latest
EXPECTED_CONTROL_OSRM_FILE_MANIFEST_SHA256="${FIXTURE_DIGEST}"
EXPECTED_CONTROL_RUNTIME_PROFILE=prod
EXPECTED_CONTROL_ALGORITHM_PROFILE=hybrid_osrm_v2
EXPECTED_CONTROL_ALGORITHM_MODE=drive
EXPECTED_CONTROL_GRAPH_WARMUP_ENABLED=false
EXPECTED_CONTROL_ROAD_ANCHOR_CACHE_SCHEMA=v1
EXPECTED_CONTROL_RUNTIME_ALGORITHM=hybrid_osrm_v2
if verify_running_control_artifact_identity "$CONTROL_ENV" >/dev/null 2>&1; then
  echo "Running control image mismatch was accepted." >&2
  exit 16
fi
EXPECTED_CONTROL_ROUTE_API_IMAGE_REF="ghcr.io/acme/moodride-route-api@sha256:${FIXTURE_DIGEST}"
EXPECTED_CONTROL_ARTIFACT_SHA256="${FIXTURE_ROAD_SHA}"
EXPECTED_GITHUB_SOURCE_URL=https://github.com/acme/wayward
QUALITY_ACCEPTANCE_SHA256="${ADOPTION_QUALITY_SHA}"
QUALITY_ACCEPTANCE="$QUALITY_FILE"
mkdir -p "$MOODRIDE_DIR/.deploy/releases"
cp -p "$QUALITY_FILE" "$fixture_bundle/quality-acceptance.json"
control_env_before="$(sha256sum "$CONTROL_ENV")"
control_env_before="${control_env_before%% *}"
if require_adopted_control_prerequisite >/dev/null 2>&1; then
  echo "Pristine V35 host without adopted control evidence was accepted." >&2
  exit 19
fi
[ ! -e "$MOODRIDE_DIR/.deploy/current" ]
[ "$(sha256sum "$CONTROL_ENV" | cut -d' ' -f1)" = "$control_env_before" ]
if compgen -G "$MOODRIDE_DIR/.deploy/releases/accepted-*.json" >/dev/null; then
  echo "Rejected pristine V35 fixture synthesized accepted control evidence." >&2
  exit 20
fi
EXPECTED_CONTROL_RELEASE_LOCK_SHA256="$ADOPTED_CONTROL_LOCK_SHA256"
adopted_lock="$MOODRIDE_DIR/.deploy/releases/accepted-sha-${FIXTURE_REVISION}-${EXPECTED_CONTROL_RELEASE_LOCK_SHA256}.json"
cp -p "$ADOPTED_CONTROL_LOCK" "$adopted_lock"
(cd "$MOODRIDE_DIR/.deploy" && ln -s "bundles/${fixture_bundle_id}" current)
if [ ! -L "$MOODRIDE_DIR/.deploy/current" ]; then
  # Git Bash copies directory symlink targets when native symlinks are disabled.
  # Keep the fixture behavioral by emulating only the exact pointer predicates;
  # every other test/readlink invocation still uses the real builtins.
  function [ {
    case "${1:-}|${2:-}|${3:-}" in
      "-L|$MOODRIDE_DIR/.deploy/current|]") return 0 ;;
    esac
    builtin [ "$@"
  }
  readlink() {
    case "${1:-}|${2:-}" in
      "-f|$MOODRIDE_DIR/.deploy/current") printf '%s\n' "$fixture_bundle" ;;
      *) command readlink "$@" ;;
    esac
  }
fi
require_adopted_control_prerequisite
[ "$PREVIOUS_CONTROL_BUNDLE" = "$fixture_bundle" ]
[ "$CONTROL_ACCEPTED_LOCK" = "$adopted_lock" ]
SYNTHETIC_USER_ID=00000000-0000-0000-0000-000000000037
SYNTHETIC_JOB_TIMEOUT_SECONDS=2
fixture_job_id=11111111-1111-4111-8111-111111111111
fixture_job_algorithm_version=hybrid_osrm_v2
fixture_job_route_mode=drive
fixture_primary_route_mode=drive
fixture_sql_log="$MOODRIDE_DIR/synthetic-sql.log"
fixture_drain_marker="$MOODRIDE_DIR/global-drain-called"
compose_env() {
  local env_file="$1"
  shift
  if [ "$1" != "exec" ] || [ "$3" != "route-api" ]; then
    echo "Synthetic fixture received a non-internal route-api request." >&2
    return 21
  fi
  case "$*" in
    *"${SYNTHETIC_USER_ID}"*'"routeMode":"drive"'*) ;;
    *)
      echo "Synthetic fixture request omitted its reserved user or drive mode." >&2
      return 22
      ;;
  esac
  printf '{"jobId":"%s"}\n' "$fixture_job_id"
}
wait_for_drain() {
  : > "$fixture_drain_marker"
  return 23
}
psql_query() {
  local env_file="$1"
  local sql="$2"
  if [[ "$sql" == *"route_jobs"* ]]; then
    if [[ "$sql" != *"id = '${fixture_job_id}'::uuid"* ]] \
        || [[ "$sql" != *"user_id = '${SYNTHETIC_USER_ID}'::uuid"* ]]; then
      echo "Synthetic helper inspected or deleted a route job outside its exact reserved ID." >&2
      return 24
    fi
  fi
  case "$sql" in
    *"DELETE FROM route_jobs"*)
      printf 'delete-exact-reserved-job\n' >> "$fixture_sql_log"
      printf '%s\n' "$fixture_job_id"
      ;;
    *"SELECT status FROM route_jobs"*)
      printf 'COMPLETED\n'
      ;;
    *"primary_ready_at"*)
      printf '1\n'
      ;;
    *"route_job_terminal_events"*)
      printf '1\n'
      ;;
    *"JOIN routes r ON r.id = j.route_id"*)
      printf '%s|%s|%s\n' \
        "$fixture_job_algorithm_version" \
        "$fixture_job_route_mode" "$fixture_primary_route_mode"
      ;;
    *)
      echo "Unexpected synthetic fixture SQL: $sql" >&2
      return 25
      ;;
  esac
}
: > "$fixture_sql_log"
run_control_route_identity_probe "$CONTROL_ENV"
[ "$CONTROL_EXECUTED_ROUTE_ALGORITHM_VERSION" = "hybrid_osrm_v2" ]
[ "$CONTROL_EXECUTED_ROUTE_MODE" = "drive" ]
[ "$CONTROL_EXECUTED_JOB_ROUTE_MODE" = "drive" ]
[ "$CONTROL_EXECUTED_PRIMARY_ROUTE_MODE" = "drive" ]
[ "$(grep -c '^delete-exact-reserved-job$' "$fixture_sql_log")" -eq 1 ]
[ ! -e "$fixture_drain_marker" ]
: > "$fixture_sql_log"
run_synthetic_route_smoke "$CONTROL_ENV" v41 candidate
[ "$CANDIDATE_EXECUTED_ROUTE_ALGORITHM_VERSION" = "hybrid_osrm_v2" ]
[ "$CANDIDATE_EXECUTED_ROUTE_MODE" = "drive" ]
[ "$CANDIDATE_EXECUTED_JOB_ALGORITHM_VERSION" = "hybrid_osrm_v2" ]
[ "$CANDIDATE_EXECUTED_JOB_ROUTE_MODE" = "drive" ]
[ "$CANDIDATE_EXECUTED_PRIMARY_ROUTE_MODE" = "drive" ]
[ "$(grep -c '^delete-exact-reserved-job$' "$fixture_sql_log")" -eq 1 ]
[ ! -e "$fixture_drain_marker" ]
: > "$fixture_sql_log"
fixture_job_algorithm_version=hybrid_osrm_v3
if run_control_route_identity_probe "$CONTROL_ENV"; then
  echo "Mismatched persisted job algorithm identity was accepted." >&2
  exit 17
fi
[ "$(grep -c '^delete-exact-reserved-job$' "$fixture_sql_log")" -eq 1 ]
[ ! -e "$fixture_drain_marker" ]
: > "$fixture_sql_log"
fixture_job_algorithm_version=hybrid_osrm_v2
fixture_primary_route_mode=bike
if run_control_route_identity_probe "$CONTROL_ENV"; then
  echo "Mismatched job/committed-primary route mode was accepted." >&2
  exit 18
fi
[ "$(grep -c '^delete-exact-reserved-job$' "$fixture_sql_log")" -eq 1 ]
[ ! -e "$fixture_drain_marker" ]
rm -rf "$MOODRIDE_DIR"
"""
        )
        environment = os.environ.copy()
        completed = subprocess.run(
            [quality_bash],
            cwd=ROOT,
            env=environment,
            input=fixture_script.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        require(completed.returncode == 0,
                "Adopted-control identity and V35 rejection fixture failed: "
                + completed.stderr.decode("utf-8", errors="replace").strip())

        osrm_dir = temp / "osrm"
        osrm_dir.mkdir()
        for name, payload in {
            "canada-latest.osrm.A": b"uppercase",
            "canada-latest.osrm+a": b"plus",
            "canada-latest.osrm_2": b"underscore",
            "canada-latest.osrm.10": b"dot",
        }.items():
            (osrm_dir / name).write_bytes(payload)
        python_lines, python_hash = module.build_osrm_file_manifest(
            osrm_dir, "canada-latest"
        )
        shell_manifest = temp / "shell-osrm-files.sha256"
        manifest_script = (
            f"OSRM_FIXTURE_DIR={shlex.quote(osrm_dir.relative_to(ROOT).as_posix())}\n"
            f"OSRM_FIXTURE_OUTPUT={shlex.quote(shell_manifest.relative_to(ROOT).as_posix())}\n"
            + r"""#!/usr/bin/env bash
set -Eeuo pipefail
export DEPLOY_LIBRARY_ONLY=1
source scripts/deploy/deploy_prod.sh
write_osrm_file_manifest "$OSRM_FIXTURE_DIR" canada-latest "$OSRM_FIXTURE_OUTPUT"
"""
        )
        manifest_environment = os.environ.copy()
        manifest_completed = subprocess.run(
            ["bash"],
            cwd=ROOT,
            env=manifest_environment,
            input=manifest_script.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        require(
            manifest_completed.returncode == 0,
            "Shell OSRM manifest fixture failed: "
            + manifest_completed.stderr.decode("utf-8", errors="replace").strip(),
        )
        shell_bytes = shell_manifest.read_bytes()
        require(
            shell_bytes.decode("utf-8").splitlines() == python_lines
            and hashlib.sha256(shell_bytes).hexdigest() == python_hash,
            "Capture and deploy OSRM manifest canonicalization diverged",
        )

        lock_runtime = temp / "cutover-lock-runtime"
        lock_script = (
            f"LOCK_RUNTIME={shlex.quote(lock_runtime.relative_to(ROOT).as_posix())}\n"
            + r"""#!/usr/bin/env bash
set -Eeuo pipefail
export DEPLOY_LIBRARY_ONLY=1
source scripts/deploy/deploy_prod.sh
MOODRIDE_DIR="$PWD/$LOCK_RUNTIME"
mkdir -p "$MOODRIDE_DIR/.deploy"
CUTOVER_LOCK_HELD=0
acquire_cutover_lock
[ -f "$MOODRIDE_DIR/.deploy/prod-cutover.lock" ]
release_cutover_lock
[ -f "$MOODRIDE_DIR/.deploy/prod-cutover.lock" ]
unset DEPLOY_LIBRARY_ONLY
export ROLLBACK_LIBRARY_ONLY=1
source scripts/deploy/rollback_prod.sh
CUTOVER_LOCK_HELD=0
acquire_cutover_lock
[ -f "$MOODRIDE_DIR/.deploy/prod-cutover.lock" ]
release_cutover_lock
[ -f "$MOODRIDE_DIR/.deploy/prod-cutover.lock" ]
"""
        )
        lock_completed = subprocess.run(
            [quality_bash],
            cwd=ROOT,
            env=os.environ.copy(),
            input=lock_script.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        require(
            lock_completed.returncode == 0,
            "Deploy lock release to rollback lock acquire fixture failed: "
            + lock_completed.stderr.decode("utf-8", errors="replace").strip(),
        )


def run_collection_behavior_fixtures(collection_script: str) -> None:
    attempt_id = "4242-3"
    control_source = "a" * 40
    candidate_source = "b" * 40
    control_lock = "c" * 64
    candidate_lock = "d" * 64
    quality_sha = "e" * 64
    control_artifact = "f" * 64
    candidate_artifact = "9" * 64
    manifest_bytes = b"0123456789abcdef  canada-latest.osrm\\n"
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    quality = {
        "artifacts": {
            "control": {
                "sha256": control_artifact,
                "source_sha": control_source,
                "release_lock_sha256": control_lock,
                "runtime_identity": {
                    "osrm": {"file_manifest_sha256": manifest_sha},
                    "runtime_algorithm_mode": {
                        "algorithm": "hybrid_osrm_v2",
                        "mode": "drive",
                    },
                },
            },
            "candidate": {
                "sha256": candidate_artifact,
                "source_sha": candidate_source,
                "release_lock_sha256": candidate_lock,
                "runtime_identity": {
                    "osrm": {"file_manifest_sha256": manifest_sha},
                    "runtime_algorithm_mode": {
                        "algorithm": "hybrid_osrm_v2",
                        "mode": "drive",
                    },
                },
            },
        }
    }
    rendered_script = (
        "scp() { cp \"remote-evidence/${1##*/}\" \"$2\"; }\n"
        + collection_script
        .replace("${{ secrets.PROD_SSH_USER }}", "fixture-user")
        .replace("${{ secrets.PROD_SSH_HOST }}", "fixture-host")
    )
    collection_bash = "bash"
    if os.name == "nt":
        git_bash = pathlib.Path(
            os.environ.get("ProgramFiles", r"C:\Program Files")
        ) / "Git" / "bin" / "bash.exe"
        if git_bash.is_file():
            collection_bash = str(git_bash)

    with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
        temp = pathlib.Path(temporary)
        for case_name, mutation, should_pass in (
            ("current", None, True),
            ("stale-attempt", "stale-attempt", False),
            ("missing-sidecar", "missing-sidecar", False),
            ("manifest-hash-mismatch", "manifest-hash-mismatch", False),
            ("executed-route-mismatch", "executed-route-mismatch", False),
        ):
            case_dir = temp / case_name
            local_evidence = case_dir / "deploy-evidence"
            remote_evidence = case_dir / "remote-evidence"
            local_evidence.mkdir(parents=True)
            remote_evidence.mkdir()
            (local_evidence / "quality-acceptance.json").write_text(
                json.dumps(quality, separators=(",", ":"), sort_keys=True),
                encoding="utf-8",
            )
            for identity, source, lock_sha, artifact_sha, phase in (
                (
                    "control",
                    control_source,
                    control_lock,
                    control_artifact,
                    "control-pre-drain",
                ),
                (
                    "candidate",
                    candidate_source,
                    candidate_lock,
                    candidate_artifact,
                    "candidate-pre-ingress",
                ),
            ):
                common = {
                    "schema_version": 1,
                    "attempt_id": attempt_id,
                    "phase": phase,
                    "generated_at_utc": "2026-07-20T00:00:00Z",
                    "image_tag": f"sha-{source}",
                    "source_sha": source,
                    "release_lock_sha256": lock_sha,
                    "quality_acceptance_sha256": quality_sha,
                    "artifact_sha256": artifact_sha,
                    "artifact_source_sha": source,
                    "osrm_file_manifest_sha256": manifest_sha,
                }
                comparison = {
                    **common,
                    "runtime_identity": identity,
                    "osrm": {
                        "file_manifest_sha256": {
                            "expected": manifest_sha,
                            "actual": manifest_sha,
                        }
                    },
                    "executed_route_identity": {
                        "algorithm": {
                            "expected": "hybrid_osrm_v2",
                            "actual": "hybrid_osrm_v2",
                        },
                        "mode": {"expected": "drive", "actual": "drive"},
                        "job": {
                            "algorithm_version": "hybrid_osrm_v2",
                            "route_mode": "drive",
                        },
                        "primary": {"route_mode": "drive"},
                    },
                }
                metadata = {
                    **common,
                    "evidence_type": "osrm-file-manifest",
                    "manifest_file": f"fixture.{identity}.osrm-files.sha256",
                }
                if mutation == "stale-attempt" and identity == "control":
                    comparison["attempt_id"] = "4242-2"
                if mutation == "executed-route-mismatch" and identity == "candidate":
                    comparison["executed_route_identity"]["job"][
                        "algorithm_version"
                    ] = "hybrid_osrm_v3"
                (remote_evidence / f"last-quality-comparison-{identity}.json").write_text(
                    json.dumps(comparison, separators=(",", ":"), sort_keys=True),
                    encoding="utf-8",
                )
                (remote_evidence / f"last-osrm-files-{identity}.sha256").write_bytes(
                    b"tampered\\n"
                    if mutation == "manifest-hash-mismatch" and identity == "control"
                    else manifest_bytes
                )
                if not (mutation == "missing-sidecar" and identity == "candidate"):
                    (
                        remote_evidence
                        / f"last-osrm-files-{identity}.metadata.json"
                    ).write_text(
                        json.dumps(metadata, separators=(",", ":"), sort_keys=True),
                        encoding="utf-8",
                    )

            fixture_environment = os.environ.copy()
            fixture_environment.update({
                "DEPLOYMENT_ATTEMPT_ID": attempt_id,
                "CANDIDATE_IMAGE_TAG": f"sha-{candidate_source}",
                "CANDIDATE_SOURCE_SHA": candidate_source,
                "CANDIDATE_RELEASE_LOCK_SHA256": candidate_lock,
                "QUALITY_ACCEPTANCE_SHA256": quality_sha,
            })
            completed = subprocess.run(
                [collection_bash],
                cwd=ROOT,
                env=fixture_environment,
                input=(
                    f"cd {shlex.quote(case_dir.relative_to(ROOT).as_posix())}\n"
                    + rendered_script
                ).encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            require(
                (completed.returncode == 0) == should_pass,
                f"Runtime evidence collection fixture {case_name} "
                f"{'failed' if should_pass else 'was accepted'}: "
                + completed.stdout.decode("utf-8", errors="replace").strip()
                + completed.stderr.decode("utf-8", errors="replace").strip(),
            )


def main() -> None:
    workflow = yaml.load(WORKFLOW_PATH.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    require(isinstance(workflow, dict), "Workflow did not parse as a mapping")
    jobs = workflow.get("jobs", {})
    require(
        set(jobs)
        == {"release-values", "verify", "capture-runtime", "publish", "deploy", "rollback-current"},
        "Unexpected deploy-prod job topology",
    )

    inputs = workflow["on"]["workflow_dispatch"]["inputs"]
    operations = inputs["operation"]["options"]
    require(operations == ["publish-only", "deploy-existing", "rollback-current", "capture-runtime"],
            "Release operations changed or reordered")
    require("release_lock" in inputs, "deploy-existing must accept a four-image release lock")
    require("release_lock_sha256" in inputs, "deploy-existing must require the release-lock checksum")
    require("quality_acceptance_b64" in inputs and "quality_acceptance_sha256" in inputs,
            "deploy-existing must require checksum-pinned quality acceptance")

    release_script = step_by_name(jobs["release-values"], "Resolve and validate operation")["run"]
    for token in (
        "DISPATCH_RELEASE_LOCK",
        "DISPATCH_RELEASE_LOCK_SHA256",
        "DISPATCH_QUALITY_ACCEPTANCE_B64",
        "DISPATCH_QUALITY_ACCEPTANCE_SHA256",
        ".source_sha",
        ".images[$key].ref",
        ".images[$key].index_digest",
        "actual_lock_sha256",
        "scenario_manifest_sha256",
        "database_fingerprint",
        "scenic_dataset_fingerprint",
        "road_dataset_fingerprint",
        "runtime_algorithm_mode",
        "cache_policy",
        ".artifacts.control.sha256",
        ".artifacts.candidate.sha256",
        "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00",
        ".scenario_count == 27",
        ".route_mode == \"drive\"",
        "def exact_runtime_identity($expected_algorithm_mode)",
        '(has("database_identity") | not)',
        '(has("osrm") | not)',
        '(has("runtime_algorithm_mode") | not)',
        '(has("cache_policy") | not)',
        ".artifacts.control.runtime_identity",
        ".artifacts.candidate.runtime_identity",
        '"cache_policy,database_identity,osrm,runtime_algorithm_mode"',
        'algorithm: "hybrid_osrm_v2"',
        "== .artifacts.candidate.runtime_identity.database_identity.scenic_scoring_version",
        "== .artifacts.candidate.runtime_identity.database_identity.scenic_dataset_fingerprint",
        "== .artifacts.candidate.runtime_identity.database_identity.road_dataset_fingerprint",
        "== .artifacts.candidate.runtime_identity.osrm",
        "== .artifacts.candidate.runtime_identity.cache_policy",
        "== .artifacts.candidate.runtime_identity.runtime_algorithm_mode",
        ".verdict == \"pass\"",
        "sha256:[0-9a-f]{64}",
        "expected_candidate_images",
        "def exact_image($repository; $revision)",
        "def exact_images($revision)",
        '(has("images") | not)',
        '(has("image_digests") | not)',
        ".artifacts.control as $control",
        "$control.images | exact_images($control.source_sha)",
        ".artifacts.candidate.images == $expected_candidate_images",
        ".artifacts.candidate.images | exact_images($source_sha)",
        ".artifacts.control.sha256 != .artifacts.candidate.sha256",
        '"images,release_lock_sha256,runtime_identity,sha256,source_sha"',
        "Production-environment authorization trusts that approval",
    ):
        require(token in release_script, f"Release-lock validation is missing {token}")
    require("effective_algorithm" not in release_script,
            "Workflow retains asymmetric candidate runtime-algorithm identity")
    require(release_script.count('algorithm: "hybrid_osrm_v2"') >= 2,
            "Workflow does not require the unified control/candidate algorithm identity")

    verify_job = jobs["verify"]
    require(verify_job.get("needs") == "release-values",
            "Release verification is not source-locked to resolved release values")
    verify_condition = " ".join(verify_job.get("if", "").split())
    expected_verify_condition = (
        "needs.release-values.outputs.should_publish == 'true' || "
        "needs.release-values.outputs.should_deploy == 'true' || "
        "needs.release-values.outputs.should_capture == 'true' || "
        "needs.release-values.outputs.should_rollback == 'true'"
    )
    require(
        verify_condition == expected_verify_condition,
        "Release verification job condition is not the exact four-operation gate",
    )
    verify_steps = verify_job["steps"]
    expected_verify_steps = (
        "Checkout exact release source for verification",
        "Assert verification checkout provenance",
        "Set up Java 25 for reactor tests",
        "Run backend reactor tests",
        "Set up Node for frontend verification",
        "Run frontend install, lint, typecheck, and production build",
        "Run production release contract and behavior fixtures",
        "Validate strict production Compose interpolation",
        "Validate production shell syntax",
    )
    require(
        tuple(step.get("name") for step in verify_steps) == expected_verify_steps,
        "Exact-source verification steps changed, disappeared, or run out of order",
    )
    for step in verify_steps:
        require(
            "continue-on-error" not in step,
            f"Verification step can ignore failure: {step.get('name')}",
        )
        require(
            "if" not in step,
            f"Verification step can be conditionally bypassed: {step.get('name')}",
        )
    contract_fixture_step = step_by_name(
        verify_job, "Run production release contract and behavior fixtures"
    )
    require(
        contract_fixture_step.get("shell") == "bash"
        and contract_fixture_step.get("run")
        == "python3 scripts/deploy/check_prod_release_contract.py",
        "Production release contract verify step command is not exact",
    )
    verify_checkout = step_by_name(verify_job, expected_verify_steps[0])
    require(verify_checkout["with"]["ref"] == "${{ needs.release-values.outputs.source_sha }}",
            "Verification checkout is not pinned to source_sha")
    backend_tests = step_by_name(verify_job, "Run backend reactor tests")["run"]
    for backend_token in (
        "mvn -N -q -DforceStdout help:evaluate -Dexpression=mockito.version",
        "'^[0-9]+(\\.[0-9]+){2}",
        "mvn -q -pl services/route-worker -am dependency:go-offline -DskipTests",
        'mockito_agent="$HOME/.m2/repository/org/mockito/mockito-core/'
        '$mockito_version/mockito-core-$mockito_version.jar"',
        '[ ! -f "$mockito_agent" ]',
        'mvn -DargLine="-javaagent:$mockito_agent" test',
    ):
        require(
            backend_token in backend_tests,
            f"JDK 25 backend verification omits {backend_token}",
        )
    require(
        backend_tests.index("help:evaluate -Dexpression=mockito.version")
        < backend_tests.index("dependency:go-offline")
        < backend_tests.index('mockito_agent="$HOME/.m2/repository')
        < backend_tests.index('[ ! -f "$mockito_agent" ]')
        < backend_tests.index('mvn -DargLine="-javaagent:$mockito_agent" test'),
        "Inherited Mockito agent is not resolved and validated before backend tests",
    )
    require(
        "\nmvn test" not in backend_tests,
        "Backend verification still relies on Mockito dynamic self-attachment",
    )
    require(
        "mockito-core/*/" not in backend_tests
        and "mockito_agents" not in backend_tests
        and "nullglob" not in backend_tests,
        "Backend verification scans cached Mockito versions instead of using the inherited version",
    )
    compose_validation = step_by_name(
        verify_job, "Validate strict production Compose interpolation"
    )["run"]
    for token in (
        'fixture_env="$RUNNER_TEMP/compose-fixture.env"',
        'cp .env.prod.template "$fixture_env"',
        '"POSTGRES_PASSWORD": "fixture-postgres-password"',
        '"ROUTE_API_IMAGE_REF": "ghcr.io/acme/moodride-route-api@sha256:"',
        '--env-file "$fixture_env"',
        '"CADDY_IMAGE_REF": "caddy@sha256:"',
    ):
        require(token in compose_validation, f"Validated Compose fixture omits {token}")
    require("--env-file .env.prod.template" not in compose_validation,
            "Strict Compose validation uses the intentionally invalid production template directly")
    require(jobs["publish"].get("needs") == ["release-values", "verify"],
            "Publish is not gated by exact-source verification")
    require(jobs["publish"].get("if") ==
            "needs.release-values.outputs.should_publish == 'true'",
            "Publish verification dependency can be bypassed by its job condition")
    require(jobs["deploy"].get("needs") == ["release-values", "verify", "publish"],
            "Deploy dependencies do not directly gate on verification and publish")
    require("needs.verify.result == 'success'" in jobs["deploy"].get("if", ""),
            "deploy-existing can bypass exact-source verification")

    require(
        jobs["publish"].get("concurrency", {}).get("group")
        == (
            "publish-production-${{ needs.release-values.outputs.source_sha }}"
            "-${{ needs.release-values.outputs.image_tag }}"
        ),
        "Publish concurrency is not scoped to the exact source/image tag",
    )
    publish_steps = jobs["publish"]["steps"]
    publish_names = [step.get("name") for step in publish_steps]
    preflight_index = publish_names.index("Refuse partial or divergent immutable tag set")
    package_index = publish_names.index("Package services")
    require(preflight_index < package_index, "Immutable tag preflight must precede every build")
    lineage_index = publish_names.index("Verify pinned production migration lineage")
    require(lineage_index < preflight_index, "Pinned production lineage must be checked before tag/build decisions")
    lineage_script = publish_steps[lineage_index]["run"]
    for token in ("V38__add_road_segment_stable_identity.sql", "1443186875", "V41__add_route_job_terminal_event_outbox.sql"):
        require(token in lineage_script, f"Pinned migration lineage check omits {token}")
    preflight_script = publish_steps[preflight_index]["run"]
    for token in (
        "existing",
        "missing",
        "org.opencontainers.image.revision",
        "org.opencontainers.image.source",
        "index_digest",
        "should_build=false",
    ):
        require(token in preflight_script, f"Immutable tag preflight is missing {token}")
    for build_name in (
        "Build and push route-api",
        "Build and push route-worker",
        "Build and push notification-service",
        "Build and push frontend",
    ):
        build = step_by_name(jobs["publish"], build_name)
        require(build.get("if") == "steps.preflight.outputs.should_build == 'true'",
                f"{build_name} can overwrite an existing immutable tag")
    require(step_by_name(jobs["publish"], "Upload immutable release lock")["uses"] == "actions/upload-artifact@v4",
            "Release lock is not uploaded as an artifact")
    emit_script = step_by_name(jobs["publish"], "Emit four-image release lock")["run"]
    for token in ("schema_version: 2", "ghcr_namespace", "index_digest", "sha256sum release-lock/release-lock.json"):
        require(token in emit_script, f"Published release lock omits {token}")

    capture_job = jobs["capture-runtime"]
    capture_script = step_by_name(
        capture_job, "Observe production runtime without mutation"
    )["run"]
    for token in (
        "capture_prod_runtime.py",
        "EXPECTED_GITHUB_SOURCE_URL='https://github.com/${{ github.repository }}'",
    ):
        require(token in capture_script, f"Runtime capture invocation omits {token}")
    capture_evidence_upload = step_by_name(
        capture_job, "Upload production runtime evidence"
    )
    require(capture_evidence_upload.get("uses") == "actions/upload-artifact@v4",
            "Runtime evidence is not uploaded as an artifact")
    require(capture_evidence_upload.get("if") == "always()",
            "Failed or unattributable runtime evidence is not uploaded")
    require(
        capture_evidence_upload.get("with", {}).get("path")
        == "production-runtime.json"
        and capture_evidence_upload.get("with", {}).get("if-no-files-found")
        == "error",
        "Runtime evidence upload does not fail closed on a missing document",
    )
    capture_diagnostics_upload = step_by_name(
        capture_job, "Upload production runtime capture diagnostics"
    )
    require(capture_diagnostics_upload.get("uses") == "actions/upload-artifact@v4",
            "Runtime capture diagnostics are not uploaded as an artifact")
    require(capture_diagnostics_upload.get("if") == "always()",
            "Failed runtime capture diagnostics are not uploaded")
    require(
        capture_diagnostics_upload.get("with", {}).get("path")
        == "production-runtime-diagnostics/"
        and capture_diagnostics_upload.get("with", {}).get("if-no-files-found")
        == "error",
        "Runtime diagnostics upload does not fail closed on missing evidence",
    )
    probe = (ROOT / "scripts/deploy/capture_prod_runtime.py").read_text(encoding="utf-8")
    for required in (
        "RepoDigests",
        "org.opencontainers.image.revision",
        "org.opencontainers.image.source",
        "EXPECTED_COMPOSE_SERVICES",
        "compose_runtime",
        "osrm_identity",
        "CADDY_IMAGE_REF",
        "capture_caddy_identity",
        "caddy_identity",
        "configured_runtime",
        "configured_cache_identity",
        "attribution_status",
        "flyway_schema_history",
        "running_route_api_migrations",
        "raise SystemExit(main())",
    ):
        require(required in probe, f"Runtime capture omits {required}")
    require(
        "runtime_mode" not in probe
        and "effective_cache_identity" not in probe
        and "effective_algorithm" not in probe,
        "Runtime capture labels configured container environment as effective/executed",
    )

    deploy_job = jobs["deploy"]
    deploy_names = [step.get("name") for step in deploy_job["steps"]]
    ordered_deploy_steps = (
        "Checkout exact locked source",
        "Verify exact locked checkout",
        "Stage checksum-pinned deployment evidence and control bundle",
        "Upload immutable checksum-versioned control bundle",
        "Run remote digest-pinned deploy",
        "Collect remote live-identity comparison",
        "Archive accepted deployment evidence",
    )
    require(
        [deploy_names.index(name) for name in ordered_deploy_steps]
        == sorted(deploy_names.index(name) for name in ordered_deploy_steps),
        "Deployment checkout, staging, upload, invocation, and evidence steps run out of order",
    )
    remote_deploy = step_by_name(deploy_job, "Run remote digest-pinned deploy")["run"]
    for option in (
        "--release-lock",
        "--release-lock-sha256",
        "--quality-acceptance",
        "--quality-acceptance-sha256",
    ):
        require(option in remote_deploy, f"Remote deploy omits {option}")
    for forbidden in ("--route-api-image-ref", "--route-worker-image-ref",
                      "--notification-service-image-ref", "--frontend-image-ref"):
        require(forbidden not in remote_deploy, f"Remote deploy retains digest bypass {forbidden}")
    attempt_token = (
        "DEPLOYMENT_ATTEMPT_ID='${{ github.run_id }}-${{ github.run_attempt }}'"
    )
    require(attempt_token in remote_deploy,
            "Remote deploy omits the unique workflow run/attempt identity")
    identity_token = "EXPECTED_GITHUB_SOURCE_URL='https://github.com/${{ github.repository }}'"
    bundle_token = 'CONTROL_BUNDLE=\\"\\$bundle\\"'
    invocation_token = '\\"\\$bundle/scripts/deploy/deploy_prod.sh\\"'
    for token in (identity_token, bundle_token, invocation_token):
        require(token in remote_deploy, f"Remote deploy identity invocation omits {token}")
    require(
        remote_deploy.index(attempt_token)
        <
        remote_deploy.index(identity_token)
        < remote_deploy.index(bundle_token)
        < remote_deploy.index(invocation_token),
        "Remote deploy identity and checksum-versioned bundle are assigned after invocation",
    )
    upload_script = step_by_name(jobs["deploy"], "Upload immutable checksum-versioned control bundle")["run"]
    bundle_stage_script = step_by_name(
        jobs["deploy"], "Stage checksum-pinned deployment evidence and control bundle"
    )["run"]
    for dependency in (
        "database_recovery.sh",
        "rollback_v41_v40_v39_to_v38.sql",
        "deploy_scenic_release.sh",
        "capture_prod_runtime.py",
        "accepted-release-lock.json",
    ):
        require(dependency in bundle_stage_script, f"Control-bundle staging omits dependency {dependency}")
    for token in ("bundle.sha256", ".deploy/bundles", "sha256sum --check", "BUNDLE_MANIFEST_SHA256"):
        require(token in upload_script, f"Immutable control-bundle upload omits {token}")
    for token in (
        "docker-compose.prod.yml",
        "Caddyfile",
        "accepted-release-lock.json",
        "quality-acceptance.json",
        "bundle.sha256",
    ):
        require(token in bundle_stage_script, f"Control-bundle staging omits {token}")
    expected_bundle_assignment = (
        'bundle_id="${IMAGE_TAG}-${QUALITY_ACCEPTANCE_SHA256}-${bundle_manifest_sha}"'
    )
    require(expected_bundle_assignment in bundle_stage_script,
            "Control-bundle basename is not source/quality/manifest checksum-versioned")
    require('${#bundle_id}' in bundle_stage_script and "255" in bundle_stage_script,
            "Control-bundle staging does not fail closed above the basename byte limit")
    require("control_artifact_sha" not in bundle_stage_script
            and "candidate_artifact_sha" not in bundle_stage_script,
            "Control-bundle basename still duplicates artifact claims committed by quality acceptance")
    maximum_bundle_id = f"sha-{'f' * 40}-{'f' * 64}-{'f' * 64}"
    require(len(maximum_bundle_id.encode("ascii")) <= 255,
            "Maximum checksum-versioned control-bundle basename exceeds 255 bytes")
    evidence_step = bundle_stage_script
    for token in (
        "quality-acceptance.json",
        "QUALITY_ACCEPTANCE_SHA256",
        "sha256sum --check",
        "route-quality-release-scenarios.json",
        "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00",
    ):
        require(token in evidence_step, f"Deployment evidence staging omits {token}")
    checkout_guard = step_by_name(jobs["deploy"], "Verify exact locked checkout")["run"]
    for token in (
        "scripts/monitoring/route-quality-release-scenarios.json",
        "2fc22496f3ee42bfcc298fd06a3aa1e3a126822fe0b51513b0034342b0d27c00",
    ):
        require(token in checkout_guard, f"Frozen scenario checkout guard omits {token}")
    collection_step = step_by_name(
        deploy_job, "Collect remote live-identity comparison"
    )
    require(collection_step.get("if") == "always()",
            "Side-specific runtime identity evidence is not collected after failure")
    collection_env = collection_step.get("env", {})
    require(
        collection_env.get("DEPLOYMENT_ATTEMPT_ID")
        == "${{ github.run_id }}-${{ github.run_attempt }}"
        and collection_env.get("CANDIDATE_IMAGE_TAG")
        == "${{ needs.release-values.outputs.image_tag }}"
        and collection_env.get("CANDIDATE_SOURCE_SHA")
        == "${{ needs.release-values.outputs.source_sha }}"
        and collection_env.get("CANDIDATE_RELEASE_LOCK_SHA256")
        == "${{ inputs.release_lock_sha256 }}"
        and collection_env.get("QUALITY_ACCEPTANCE_SHA256")
        == "${{ inputs.quality_acceptance_sha256 }}",
        "Runtime evidence validation is not bound to the active deployment inputs",
    )
    collection_script = collection_step["run"]
    for token in (
        "for identity in control candidate; do",
        "last-quality-comparison-${identity}.json",
        "live-quality-comparison-${identity}.json",
        "last-osrm-files-${identity}.sha256",
        "live-osrm-files-${identity}.sha256",
        "last-osrm-files-${identity}.metadata.json",
        "live-osrm-files-${identity}.metadata.json",
    ):
        require(token in collection_script,
                f"Side-specific runtime evidence collection omits {token}")
    require("last-quality-comparison.json" not in collection_script
            and "last-osrm-files.sha256" not in collection_script,
            "Deployment evidence collection relies on overwritten generic runtime files")
    require("|| true" not in collection_script
            and "if ! scp" in collection_script
            and 'exit "$status"' in collection_script,
            "Missing runtime evidence does not fail collection after diagnostics")
    for phase in ("control-pre-drain", "candidate-pre-ingress"):
        require(phase in collection_script,
                f"Runtime evidence validation omits the {phase} phase")
    for common_binding in (
        ".attempt_id == $attempt",
        ".phase == $phase",
        ".image_tag == $image_tag",
        ".source_sha == $source_sha",
        ".release_lock_sha256 == $lock_sha",
        ".quality_acceptance_sha256 == $quality_sha",
        ".artifact_sha256 == $artifact_sha",
        ".artifact_source_sha == $artifact_source",
        ".osrm_file_manifest_sha256 == $osrm_sha",
    ):
        require(
            collection_script.count(common_binding) == 2,
            f"Comparison and OSRM metadata are not both attempt-bound by {common_binding}",
        )
    for manifest_binding in (
        'manifest_sha="$(sha256sum "$manifest_file")"',
        ".osrm.file_manifest_sha256.expected == $osrm_sha",
        ".osrm.file_manifest_sha256.actual == $osrm_sha",
        '.evidence_type == "osrm-file-manifest"',
    ):
        require(
            manifest_binding in collection_script,
            f"Fetched OSRM manifest is not cryptographically bound by {manifest_binding}",
        )
    for executed_route_binding in (
        ".artifacts[$identity].runtime_identity.runtime_algorithm_mode.algorithm",
        ".artifacts[$identity].runtime_identity.runtime_algorithm_mode.mode",
        ".executed_route_identity.algorithm.expected == $expected_algorithm",
        ".executed_route_identity.algorithm.actual == $expected_algorithm",
        ".executed_route_identity.mode.expected == $expected_mode",
        ".executed_route_identity.mode.actual == $expected_mode",
        ".executed_route_identity.job.algorithm_version == $expected_algorithm",
        ".executed_route_identity.job.route_mode == $expected_mode",
        ".executed_route_identity.primary.route_mode == $expected_mode",
    ):
        require(
            executed_route_binding in collection_script,
            f"Runtime evidence collector omits executed-route binding {executed_route_binding}",
        )
    archive_step = step_by_name(deploy_job, "Archive accepted deployment evidence")
    require(archive_step["uses"] == "actions/upload-artifact@v4",
            "Decoded quality acceptance is not archived")
    require(archive_step.get("if") == "always()"
            and archive_step.get("with", {}).get("path") == "deploy-evidence/",
            "Side-specific runtime evidence is not archived after every deploy outcome")
    rollback_step = step_by_name(
        jobs["rollback-current"], "Run fenced rollback from captured predeploy snapshot"
    )["run"]
    for token in (
        "current_link='/opt/moodride/.deploy/current'",
        'readlink -f \\"\\$current_link\\"',
        "/opt/moodride/.deploy/bundles/*",
        "sha256sum --check bundle.sha256",
        'expected_manifest=\\"\\${bundle##*-}\\"',
        '\\"\\$bundle/scripts/deploy/rollback_prod.sh\\"',
        "--expected-current-tag",
        "--expected-current-release-lock-sha256",
    ):
        require(token in rollback_step, f"Fenced rollback operation omits {token}")
    rollback_invocation = rollback_step.rfind("rollback_prod.sh")
    require(
        rollback_step.index("sha256sum --check bundle.sha256")
        < rollback_step.rfind("expected_manifest")
        < rollback_invocation,
        "Rollback invokes control code before verifying the current bundle manifest",
    )
    require("/opt/moodride/scripts/deploy/rollback_prod.sh" not in rollback_step,
            "Workflow rollback invokes the mutable root deployment script")
    require("--tag" not in rollback_step, "Workflow rollback permits an arbitrary target tag")

    compose = (ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
    require("image: ${OSRM_IMAGE_REF:?" in compose,
            "Compose does not strictly require a digest-pinned OSRM image")
    require("image: ${CADDY_IMAGE_REF:?" in compose,
            "Compose does not strictly require a digest-pinned official Caddy image")
    require("caddy:2-alpine" not in compose,
            "Production Compose retains a mutable Caddy image tag")
    for variable in (
        "ROUTE_API_IMAGE_REF",
        "ROUTE_WORKER_IMAGE_REF",
        "NOTIFICATION_SERVICE_IMAGE_REF",
        "FRONTEND_IMAGE_REF",
    ):
        require(f"image: ${{{variable}:?" in compose, f"Compose does not strictly require {variable}")
    require("${MOODRIDE_ROAD_DATASET_FINGERPRINT:?" in compose,
            "Route-worker does not receive the persisted road fingerprint")

    template_text = (ROOT / ".env.prod.template").read_text(encoding="utf-8")
    template_values = {}
    for line in template_text.splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            template_values[key] = value
    required_compose_variables: set[str] = set()
    for match in re.finditer(r"\$\{([^}]+)\}", compose):
        expression = match.group(1)
        if ":-" in expression:
            continue
        required_compose_variables.add(expression.split(":?", 1)[0])
    missing_template_variables = sorted(required_compose_variables - template_values.keys())
    require(not missing_template_variables,
            f"Production env template omits required compose variables: {missing_template_variables}")
    require(template_values["MOODRIDE_ROAD_DATASET_FINGERPRINT"].startswith("replace-with-"),
            "Road fingerprint template must use a distinct non-production placeholder")
    for key in ("ROUTE_API_IMAGE_REF", "ROUTE_WORKER_IMAGE_REF",
                "NOTIFICATION_SERVICE_IMAGE_REF", "FRONTEND_IMAGE_REF"):
        require(template_values[key].startswith("replace-with-"),
                f"{key} template value must fail immutable digest validation")
    require(not re.fullmatch(r"sha-[0-9a-f]{40}", template_values["IMAGE_TAG"]),
            "Template IMAGE_TAG must not masquerade as an attributable production source")
    require(template_values["OSRM_IMAGE_REF"].startswith("replace-with-"),
            "OSRM image template must fail immutable digest validation")
    require(template_values["CADDY_IMAGE_REF"].startswith("replace-with-"),
            "Caddy image template must fail immutable digest validation")
    require(template_values["OSRM_DATASET_BASENAME"] != "canada-latest",
            "Template OSRM basename must not masquerade as accepted production data")

    scripts = "\n".join(
        (ROOT / path).read_text(encoding="utf-8")
        for path in ("scripts/deploy/deploy_prod.sh", "scripts/deploy/rollback_prod.sh", "scripts/deploy/database_recovery.sh")
    )
    require("dropdb" not in scripts, "Production recovery must never drop the sole current database")
    for token in (
        "verify_live_quality_identity",
        "EXPECTED_SCENIC_SCORING_VERSION",
        "EXPECTED_ROAD_DATASET_FINGERPRINT",
        "EXPECTED_OSRM_FILE_MANIFEST_SHA256",
        "EXPECTED_DATABASE_FINGERPRINT",
        "EXPECTED_SCENIC_DATASET_FINGERPRINT",
        "EXPECTED_OSRM_IMAGE_REF",
        "EXPECTED_OSRM_DATASET_BASENAME",
        "CADDY_IMAGE_REF",
        "verify_rendered_caddy_image",
        "verify_running_caddy_image",
        "capture_running_caddyfile_path",
        "last-quality-comparison-${runtime_identity}.json",
        "last-osrm-files-${runtime_identity}.sha256",
    ):
        require(token in scripts, f"Pre-drain live identity gate omits {token}")
    deploy_script = (ROOT / "scripts/deploy/deploy_prod.sh").read_text(encoding="utf-8")
    rollback_script = (ROOT / "scripts/deploy/rollback_prod.sh").read_text(
        encoding="utf-8"
    )

    def require_ordered_operations(body: str, operations: tuple[str, ...], label: str) -> None:
        executable_body = "\n".join(
            line
            for line in body.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
        cursor = 0
        for operation in operations:
            position = executable_body.find(operation, cursor)
            require(
                position >= 0,
                f"{label} mandatory executable operation is missing or reordered: {operation}",
            )
            cursor = position + len(operation)

    deploy_main = deploy_script.split(
        'if [ "${DEPLOY_LIBRARY_ONLY:-0}" = "1" ]; then', 1
    )[1]
    rollback_main = rollback_script.split(
        'if [ "${DEPLOY_LIBRARY_ONLY:-0}" = "1" ] '
        '|| [ "${ROLLBACK_LIBRARY_ONLY:-0}" = "1" ]; then',
        1,
    )[1]
    deploy_lock = re.search(
        r"^acquire_cutover_lock\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    rollback_lock = re.search(
        r"^acquire_cutover_lock\(\) \{\n(?P<body>.*?)^\}",
        rollback_script,
        re.MULTILINE | re.DOTALL,
    )
    deploy_unlock = re.search(
        r"^release_cutover_lock\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    rollback_unlock = re.search(
        r"^release_cutover_lock\(\) \{\n(?P<body>.*?)^\}",
        rollback_script,
        re.MULTILINE | re.DOTALL,
    )
    require(
        all((deploy_lock, rollback_lock, deploy_unlock, rollback_unlock))
        and deploy_lock.group("body") == rollback_lock.group("body")
        and deploy_unlock.group("body") == rollback_unlock.group("body"),
        "Deploy and rollback do not use the same persistent-file flock protocol",
    )
    for script_name, script_text in (
        ("deploy", deploy_script),
        ("rollback", rollback_script),
    ):
        for lock_token in (
            "require_command flock",
            'exec 9>>"$lock_file"',
            "flock -n 9",
            "flock -u 9",
            "exec 9>&-",
        ):
            require(
                lock_token in script_text,
                f"{script_name} persistent cutover lock omits {lock_token}",
            )
        require(
            not re.search(r"mkdir[^\n]*prod-cutover\.lock", script_text)
            and not re.search(r"rm[^\n]*prod-cutover\.lock", script_text)
            and not re.search(r"unlink[^\n]*prod-cutover\.lock", script_text),
            f"{script_name} creates or unlinks the persistent cutover lock path",
        )

    require_ordered_operations(
        deploy_main,
        (
            'load_release_lock "$RELEASE_LOCK" "$RELEASE_LOCK_SHA256"',
            'load_quality_acceptance "$QUALITY_ACCEPTANCE" "$QUALITY_ACCEPTANCE_SHA256"',
            'verify_control_bundle "$CONTROL_BUNDLE"',
            "require_adopted_control_prerequisite",
            "acquire_cutover_lock",
            'capture_running_image_refs "$PREDEPLOY_ENV" "$PREDEPLOY_ENV"',
            'verify_running_control_artifact_identity "$PREDEPLOY_ENV"',
            'verify_control_accepted_lock "$PREDEPLOY_ENV"',
            'validate_pre_drain_env "$PREDEPLOY_ENV" "$PREVIOUS_CONTROL_BUNDLE" control',
            'validate_pre_drain_env "$CANDIDATE_ENV" "$CONTROL_BUNDLE" candidate',
            'validate_candidate_control_bundle "$CANDIDATE_ENV"',
            'verify_candidate_image_revisions "$CANDIDATE_ENV" "$IMAGE_TAG"',
            'verify_candidate_image_revisions "$PREDEPLOY_ENV" "$PREDEPLOY_TAG"',
            'validate_pre_drain_env "$PREDEPLOY_ENV" "$PREVIOUS_CONTROL_BUNDLE" control',
            'validate_pre_drain_env "$CANDIDATE_ENV" "$CONTROL_BUNDLE" candidate',
            'run_control_route_identity_probe "$PREDEPLOY_ENV"',
            'verify_live_quality_identity "$PREDEPLOY_ENV" "$POSTGRES_DB" control',
            'compose_env "$PREDEPLOY_ENV" stop caddy',
            'wait_for_drain "$PREDEPLOY_ENV"',
            'compose_env "$PREDEPLOY_ENV" stop route-api route-worker notification-service frontend',
            'assert_no_active_jobs "$PREDEPLOY_ENV"',
            'durable_copy "$CANDIDATE_ENV" "$ENV_FILE"',
            'compose_env "$ENV_FILE" up -d --no-deps route-api',
            'run_internal_http_healthcheck "Candidate route-api"',
            "verify_forward_schema",
            'compose_env "$ENV_FILE" up -d --no-deps route-worker notification-service frontend',
            'run_internal_http_healthcheck "Candidate frontend"',
            'run_internal_sockjs_healthcheck "Candidate WebSocket handshake"',
            'run_synthetic_route_smoke "$ENV_FILE" v41 candidate',
            'verify_live_quality_identity "$ENV_FILE" "$POSTGRES_DB" candidate',
            'durable_replace "${ROLLBACK_SNAPSHOT}.tmp" "$ROLLBACK_SNAPSHOT"',
            'switch_control_bundle_pointer "$CONTROL_BUNDLE"',
            'compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy',
        ),
        "Deploy cutover",
    )
    require_ordered_operations(
        rollback_main,
        (
            "acquire_cutover_lock",
            'verify_control_bundle "$CURRENT_CONTROL_BUNDLE" 1',
            'sha256sum --check "$(basename "$ROLLBACK_SNAPSHOT").sha256"',
            'load_expected_github_source_url "$EXPECTED_CURRENT_TAG"',
            'verify_control_bundle "$TARGET_CONTROL_BUNDLE"',
            'require_file "$TARGET_CONTROL_EVIDENCE"',
            'capture_running_image_refs "$CURRENT_ENV" "$CURRENT_ENV"',
            'verify_current_release_fence "$CURRENT_ENV" "$EXPECTED_CURRENT_TAG"',
            'verify_running_osrm_identity "$CURRENT_ENV"',
            'load_image_lock "$TARGET_IMAGE_LOCK" "$ROLLBACK_TAG" "$TARGET_ENV"',
            'verify_target_control_evidence "$TARGET_CONTROL_EVIDENCE" "$TARGET_ENV" "$ROLLBACK_TAG"',
            'validate_rollback_env "$CURRENT_ENV" "$CURRENT_CONTROL_BUNDLE"',
            'validate_rollback_env "$TARGET_ENV" "$TARGET_CONTROL_BUNDLE"',
            'docker pull "$(get_env_var OSRM_IMAGE_REF "$TARGET_ENV")"',
            'compose_env "$TARGET_ENV" pull osrm route-api route-worker notification-service frontend',
            'verify_target_image_revisions "$TARGET_ENV" "$ROLLBACK_TAG"',
            'docker pull "$(get_env_var OSRM_IMAGE_REF "$CURRENT_ENV")"',
            'verify_target_image_revisions "$CURRENT_ENV" "$ACTUAL_CURRENT_TAG"',
            'verify_running_caddy_image "$CURRENT_ENV"',
            'compose_env "$CURRENT_ENV" stop caddy',
            'wait_for_drain "$CURRENT_ENV"',
            'compose_env "$CURRENT_ENV" stop route-api route-worker notification-service frontend',
            'assert_no_active_jobs "$CURRENT_ENV"',
            'create_validated_recovery_database "$CURRENT_ENV" "$CURRENT_BACKUP"',
            "RECOVERY_ENABLED=1",
            "apply_coordinated_sql",
            'verify_v38_baseline_schema "$CURRENT_ENV"',
            'durable_copy "$TARGET_ENV" "$ENV_FILE"',
            'compose_env "$ENV_FILE" up -d --no-deps --force-recreate osrm',
            'verify_running_osrm_identity "$ENV_FILE"',
            'compose_env "$ENV_FILE" up -d --no-deps route-api',
            'run_internal_http_healthcheck "Rollback route-api"',
            'verify_v38_baseline_schema "$ENV_FILE"',
            'compose_env "$ENV_FILE" up -d --no-deps route-worker notification-service frontend',
            'run_internal_sockjs_healthcheck "Rollback WebSocket handshake"',
            'run_synthetic_route_smoke "$ENV_FILE" v38',
            'switch_control_bundle_pointer "$TARGET_CONTROL_BUNDLE"',
            'durable_replace "$MOODRIDE_DIR/.deploy/last-rollback.${MAIN_PID}.tmp"',
            "RECOVERY_ENABLED=0",
            'compose_env "$ENV_FILE" up -d --no-deps --force-recreate caddy',
            'verify_running_caddy_image "$ENV_FILE"',
            'run_sockjs_healthcheck "Rollback WebSocket handshake"',
        ),
        "Rollback cutover",
    )
    adoption_helper_match = re.search(
        r"^require_adopted_control_prerequisite\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    require(
        adoption_helper_match is not None,
        "Deploy omits the fail-before-mutation adopted-control prerequisite",
    )
    adoption_helper = adoption_helper_match.group("body")
    for adoption_token in (
        '[ ! -L "$MOODRIDE_DIR/.deploy/current" ]',
        "current V35 is not adopted",
        'accepted-${control_tag}-${EXPECTED_CONTROL_RELEASE_LOCK_SHA256}.json',
        'actual_sha256" != "$EXPECTED_CONTROL_RELEASE_LOCK_SHA256',
        'approval_gate == "production-control-adoption"',
        "flyway_schema_version | tonumber) >= 38",
        "flyway_history_sha256",
        ".control_adoption.source_sha == .source_sha",
        ".control_adoption.images == .images",
        ".control_adoption.runtime_identity == $runtime_identity",
    ):
        require(
            adoption_token in adoption_helper,
            f"Adopted-control prerequisite omits {adoption_token}",
        )
    require(
        not re.search(
            r"^(bootstrap_legacy_control_runtime_identity|materialize_legacy_control_lock)\(\)",
            deploy_script,
            re.MULTILINE,
        )
        and "accepted-legacy-" not in deploy_script,
        "Deploy still synthesizes trusted metadata for a legacy V35 control",
    )
    adoption_call = deploy_main.find("\nrequire_adopted_control_prerequisite")
    first_mutation = deploy_main.find("\nmkdir -p .deploy/releases")
    lock_call = deploy_main.find("\nacquire_cutover_lock")
    evidence_clear = deploy_main.find("\nclear_stale_runtime_evidence")
    require(
        0 <= adoption_call < first_mutation < lock_call < evidence_clear,
        "V35 adoption prerequisite does not fail before deploy mutation",
    )
    control_helper_match = re.search(
        r"^run_control_route_identity_probe\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    synthetic_helper_match = re.search(
        r"^run_synthetic_route_smoke\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    delete_helper_match = re.search(
        r"^delete_synthetic_route_job\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    quality_helper_match = re.search(
        r"^verify_live_quality_identity\(\) \{\n(?P<body>.*?)^\}",
        deploy_script,
        re.MULTILINE | re.DOTALL,
    )
    require(
        all((
            control_helper_match,
            synthetic_helper_match,
            delete_helper_match,
            quality_helper_match,
        )),
        "Executed-route identity helper functions are missing",
    )
    control_helper = control_helper_match.group("body")
    synthetic_helper = synthetic_helper_match.group("body")
    delete_helper = delete_helper_match.group("body")
    quality_helper = quality_helper_match.group("body")
    require(
        "wait_for_drain" not in control_helper
        and "wait_for_drain" not in synthetic_helper,
        "Control synthetic route probe performs a forbidden global drain",
    )
    require(
        "ORDER BY submitted_at" not in synthetic_helper
        and "DELETE FROM route_jobs" not in synthetic_helper,
        "Synthetic route helper scans or broadly deletes reserved/public jobs",
    )
    require(
        "WHERE id = '${job_id}'::uuid" in delete_helper
        and "AND user_id = '${SYNTHETIC_USER_ID}'::uuid" in delete_helper
        and "RETURNING id" in delete_helper,
        "Synthetic route cleanup is not fenced to its exact reserved-user job",
    )
    for persisted_identity_token in (
        "wait_for_synthetic_route_job",
        "delete_synthetic_route_job",
        'assert_live_identity_value "$job_algorithm_version" "$expected_algorithm"',
        'assert_live_identity_value "$job_route_mode" "$expected_mode"',
        'assert_live_identity_value "$primary_route_mode" "$expected_mode"',
        'assert_live_identity_value "$primary_route_mode" "$job_route_mode"',
        "record_executed_route_identity",
    ):
        require(
            persisted_identity_token in synthetic_helper,
            f"Synthetic route identity validation omits {persisted_identity_token}",
        )
    require(
        synthetic_helper.index("wait_for_synthetic_route_job")
        < synthetic_helper.index("delete_synthetic_route_job")
        < synthetic_helper.index(
            'assert_live_identity_value "$job_algorithm_version" "$expected_algorithm"'
        )
        < synthetic_helper.index(
            'assert_live_identity_value "$job_route_mode" "$expected_mode"'
        )
        < synthetic_helper.index("record_executed_route_identity"),
        "Persisted route identity is not captured, cleaned, validated, then recorded",
    )
    require(
        quality_helper.index(
            'assert_live_identity_value "$executed_algorithm_version"'
        ) < quality_helper.index("jq -n"),
        "Executed route identity is emitted before it is validated",
    )
    for attempt_evidence_token in (
        "DEPLOYMENT_ATTEMPT_ID=${DEPLOYMENT_ATTEMPT_ID:-}",
        "'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'",
        "clear_stale_runtime_evidence()",
        "generated_at_utc",
        "last-osrm-files-${runtime_identity}.metadata.json",
        'durable_replace "$comparison_temp" "$comparison_file"',
        'durable_replace "$manifest_metadata_temp" "$manifest_metadata_file"',
        "osrm_file_manifest_sha256",
        "executed_route_identity",
        "configured_algorithm",
        "configured_mode",
        "SELECT j.algorithm_version, lower(j.route_mode), lower(r.route_mode)",
    ):
        require(
            attempt_evidence_token in deploy_script,
            f"Attempt-bound deploy evidence omits {attempt_evidence_token}",
        )
    require(
        "acquire_cutover_lock\nclear_stale_runtime_evidence\ntrap cleanup EXIT"
        in deploy_script,
        "Stale runtime evidence is not cleared immediately after taking the cutover lock",
    )
    require(
        'durable_copy "$comparison_file" '
        '"$MOODRIDE_DIR/.deploy/last-quality-comparison.json"' not in deploy_script
        and 'durable_copy "$manifest_file" '
        '"$MOODRIDE_DIR/.deploy/last-osrm-files.sha256"' not in deploy_script,
        "Deploy still publishes ambiguous generic latest runtime evidence",
    )
    for canonical_quality_token in (
        '(has("images") | not)',
        '(has("image_digests") | not)',
        '(has("database_identity") | not)',
        '(has("osrm") | not)',
        '(has("runtime_algorithm_mode") | not)',
        '(has("cache_policy") | not)',
        ".artifacts.control as $control",
        "$control.images | exact_images($control.source_sha)",
        ".artifacts.control.runtime_identity",
        ".artifacts.candidate.images == $expected_candidate_images",
        ".artifacts.candidate.runtime_identity",
        ".artifacts.control.sha256 != .artifacts.candidate.sha256",
        '"images,release_lock_sha256,runtime_identity,sha256,source_sha"',
        ".artifacts.control.runtime_identity as $control_runtime",
        ".artifacts.candidate.runtime_identity as $candidate_runtime",
        "$control_runtime.database_identity.scenic_scoring_version ==",
        "$candidate_runtime.database_identity.scenic_scoring_version",
        "$control_runtime.database_identity.scenic_dataset_fingerprint ==",
        "$candidate_runtime.database_identity.scenic_dataset_fingerprint",
        "$control_runtime.database_identity.road_dataset_fingerprint ==",
        "$candidate_runtime.database_identity.road_dataset_fingerprint",
        "$control_runtime.osrm == $candidate_runtime.osrm",
        "$control_runtime.cache_policy == $candidate_runtime.cache_policy",
        "$control_runtime.runtime_algorithm_mode ==",
        "$candidate_runtime.runtime_algorithm_mode",
    ):
        require(canonical_quality_token in deploy_script,
                f"Deploy quality gate omits {canonical_quality_token}")
    require("effective_algorithm" not in deploy_script,
            "Deploy gate retains asymmetric candidate runtime-algorithm identity")
    for guard_token in (
        'CONTROL_BUNDLE_NAME="$(basename "$CONTROL_BUNDLE")"',
        'CONTROL_BUNDLE_MANIFEST_COMPONENT="${CONTROL_BUNDLE_NAME##*-}"',
        '"${IMAGE_TAG}-${QUALITY_ACCEPTANCE_SHA256}-${CONTROL_BUNDLE_MANIFEST_COMPONENT}"',
        "'^[0-9a-f]{64}$'",
    ):
        require(guard_token in deploy_script,
                f"Deploy path guard omits exact shortened bundle identity token {guard_token}")
    release_load_call = deploy_script.rfind("\nload_release_lock ")
    quality_load_call = deploy_script.rfind("\nload_quality_acceptance ")
    bundle_guard_call = deploy_script.rfind('\nCONTROL_BUNDLE_NAME="$(basename "$CONTROL_BUNDLE")"')
    bundle_verify_call = deploy_script.rfind('\nverify_control_bundle "$CONTROL_BUNDLE"')
    require(
        0 <= release_load_call < quality_load_call < bundle_guard_call < bundle_verify_call,
        "Release lock, quality identity, exact bundle path, and manifest are not verified in order",
    )
    control_probe_call = deploy_script.rfind(
        '\nrun_control_route_identity_probe "$PREDEPLOY_ENV"'
    )
    control_gate_call = deploy_script.rfind(
        '\nverify_live_quality_identity "$PREDEPLOY_ENV" "$POSTGRES_DB" control'
    )
    candidate_gate_call = deploy_script.rfind(
        '\nverify_live_quality_identity "$ENV_FILE" "$POSTGRES_DB" candidate'
    )
    ingress_stop = deploy_script.index('echo "Stopping public ingress')
    candidate_smoke = deploy_script.index(
        '\nrun_synthetic_route_smoke "$ENV_FILE" v41 candidate'
    )
    pointer_switch = deploy_script.index(
        '\nswitch_control_bundle_pointer "$CONTROL_BUNDLE"'
    )
    caddy_recreate = deploy_script.index(
        'echo "Recreating Caddy only after durable candidate acceptance'
    )
    require(
        0 <= control_probe_call < control_gate_call < ingress_stop,
        "Control executed-route probe must pass before comparison and ingress stop",
    )
    require(
        candidate_smoke < candidate_gate_call < pointer_switch < caddy_recreate,
        "Candidate runtime identity must pass after internal smoke and before pointer/Caddy",
    )
    for token in ("pg_restore", "capture_database_catalog", "validate_release_invariants", "quarantine"):
        require(token in scripts, f"Safe full-restore recovery contract is missing {token}")

    scenic = (ROOT / "scripts/deploy/deploy_scenic_release.sh").read_text(encoding="utf-8")
    require("--scoring-version is required" in scenic, "Scenic deploy does not require a signed scoring version")
    require("MOODRIDE_ROAD_DATASET_REVISION" in scenic, "Scenic deploy does not guard road identity")
    for token in (
        "wait_for_worker_drain",
        "compose_env stop caddy",
        "compose_env stop route-api route-worker",
        "current_setting('moodride.expected_scoring_version')",
        "has_incomplete_coverage",
        "evict_scenic_anchor_cache_namespaces",
    ):
        require(token in scenic, f"Scenic safe-cutover contract omits {token}")

    require(not list((ROOT / "scripts" / "deploy").glob("__pycache__/*.pyc")),
            "Generated deploy helper bytecode is present")
    run_behavior_fixtures(release_script)
    run_collection_behavior_fixtures(collection_script)
    print("prod-release-contract-ok")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, TypeError, ValueError) as exc:
        print(f"prod-release-contract-failed: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
