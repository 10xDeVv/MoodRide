#!/usr/bin/env python3
"""Focused structural and behavioral contract checks for the frontend-only hotfix lane."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from typing import Any

sys.dont_write_bytecode = True

try:
    import yaml
except ModuleNotFoundError as exc:  # pragma: no cover - operator prerequisite
    raise SystemExit("PyYAML is required to parse deploy-frontend-hotfix.yml") from exc

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_WORKFLOW = ROOT / ".github" / "workflows" / "deploy-frontend-hotfix.yml"
DEFAULT_SCRIPT = ROOT / "scripts" / "deploy" / "deploy_frontend_hotfix.sh"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def load_workflow(path: pathlib.Path) -> dict[str, Any]:
    parsed = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    require(isinstance(parsed, dict), "Workflow YAML must parse to a mapping")
    return parsed


def step_by_name(job: dict[str, Any], name: str) -> dict[str, Any]:
    for step in job.get("steps", []):
        if step.get("name") == name:
            return step
    raise AssertionError(f"Missing workflow step: {name}")


def recursively_has_key(value: Any, forbidden: str) -> bool:
    if isinstance(value, dict):
        return forbidden in value or any(recursively_has_key(item, forbidden) for item in value.values())
    if isinstance(value, list):
        return any(recursively_has_key(item, forbidden) for item in value)
    return False


def run_source_gate_fixture(validation: str, changed_path: str, should_pass: bool) -> None:
    if os.name == "nt":
        allowed = changed_path.startswith("frontend/moodride-web/src/") or changed_path.startswith(
            "frontend/moodride-web/public/"
        )
        require(
            allowed == should_pass,
            f"Windows source-gate fallback disagrees for {changed_path}",
        )
        return
    with tempfile.TemporaryDirectory(prefix="frontend-hotfix-source-gate-") as temporary:
        root = pathlib.Path(temporary)
        candidate = root / "candidate"
        control = root / "control"
        candidate.mkdir()
        control.mkdir()

        def git(directory: pathlib.Path, *args: str) -> str:
            result = subprocess.run(
                ["git", "-C", str(directory), *args],
                text=True,
                capture_output=True,
                check=False,
            )
            require(
                result.returncode == 0,
                f"Could not prepare source-gate fixture: git {' '.join(args)}\n{result.stderr}",
            )
            return result.stdout.strip()

        for repository in (candidate, control):
            git(repository, "init")
            git(repository, "config", "user.email", "frontend-hotfix-fixture@invalid")
            git(repository, "config", "user.name", "Frontend Hotfix Fixture")
            (repository / "fixture-base.txt").write_text("base\n", encoding="utf-8")
            git(repository, "add", "fixture-base.txt")
            git(repository, "commit", "-m", "fixture base")

        expected_current_sha = git(candidate, "rev-parse", "HEAD")
        control_sha = git(control, "rev-parse", "HEAD")
        changed_file = candidate / changed_path
        changed_file.parent.mkdir(parents=True, exist_ok=True)
        changed_file.write_text("candidate\n", encoding="utf-8")
        git(candidate, "add", "--", changed_path)
        git(candidate, "commit", "-m", "fixture candidate")
        candidate_sha = git(candidate, "rev-parse", "HEAD")

        environment = os.environ.copy()
        environment.update(
            {
                "CANDIDATE_SOURCE_SHA": candidate_sha,
                "EXPECTED_CURRENT_SOURCE_SHA": expected_current_sha,
                "CONTROL_WORKFLOW_SHA": control_sha,
                "CONTROL_WORKFLOW_REF": "refs/heads/main",
                "GITHUB_OUTPUT": str(root / "github-output"),
            }
        )
        result = subprocess.run(
            ["bash", "-c", validation],
            cwd=root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        if should_pass:
            require(
                result.returncode == 0,
                f"Actual source gate rejected allowed path {changed_path}:\n{result.stderr}",
            )
        else:
            require(
                result.returncode != 0,
                f"Actual source gate accepted forbidden path {changed_path}",
            )


def check_workflow(path: pathlib.Path) -> None:
    workflow_text = path.read_text(encoding="utf-8")
    workflow = load_workflow(path)
    trigger = workflow.get("on")
    require(isinstance(trigger, dict), "Workflow trigger must be a mapping")
    require(set(trigger) == {"workflow_dispatch"}, "Frontend hotfix workflow must be workflow_dispatch only")
    dispatch = trigger["workflow_dispatch"]
    require(isinstance(dispatch, dict), "workflow_dispatch must declare exact inputs")
    inputs = dispatch.get("inputs")
    require(isinstance(inputs, dict), "workflow_dispatch inputs are missing")
    require(
        set(inputs) == {"candidate_source_sha", "expected_current_source_sha"},
        "Workflow must expose only candidate_source_sha and expected_current_source_sha",
    )
    for input_name in ("candidate_source_sha", "expected_current_source_sha"):
        require(inputs[input_name].get("required") == "true", f"{input_name} must be required")
        require(inputs[input_name].get("type") == "string", f"{input_name} must be a string")

    jobs = workflow.get("jobs")
    require(isinstance(jobs, dict) and set(jobs) == {"deploy-frontend-hotfix"}, "Workflow must have one hotfix job")
    job = jobs["deploy-frontend-hotfix"]
    require(job.get("environment") == "production", "Hotfix job must use the production environment")
    concurrency = job.get("concurrency")
    require(isinstance(concurrency, dict), "Hotfix job must declare concurrency")
    require(concurrency.get("group") == "deploy-production", "Hotfix must share the unified deploy-production group")
    require(concurrency.get("cancel-in-progress") == "false", "Production cutover must not be cancelled in progress")
    require(not recursively_has_key(workflow, "continue-on-error"), "continue-on-error is forbidden in the hotfix lane")

    steps = job.get("steps")
    require(isinstance(steps, list) and steps, "Hotfix job has no steps")
    names = [step.get("name") for step in steps]
    expected_names = [
        "Checkout trusted control source",
        "Checkout candidate source",
        "Validate source relationship and frontend-only diff",
        "Run focused frontend hotfix contract checker",
        "Resolve production image values",
        "Set up Docker Buildx",
        "Log in to GHCR",
        "Build and push the single frontend image",
        "Create checksummed frontend hotfix manifest",
        "Upload frontend hotfix manifest artifact",
        "Configure production SSH",
        "Log in to GHCR on production",
        "Create unique immutable production stage",
        "Upload checksummed trusted deployment bytes",
        "Verify and execute immutable frontend-only deployment",
        "Collect frontend hotfix deployment evidence",
        "Upload frontend hotfix deployment evidence",
        "Enforce frontend hotfix deployment result",
    ]
    require(names == expected_names, "Hotfix workflow step allowlist or trust ordering changed")

    control_checkout = step_by_name(job, "Checkout trusted control source")
    candidate_checkout = step_by_name(job, "Checkout candidate source")
    for checkout in (control_checkout, candidate_checkout):
        require(checkout.get("uses") == "actions/checkout@v4", "Both source trees must use actions/checkout@v4")
        checkout_with = checkout.get("with", {})
        require(checkout_with.get("fetch-depth") == "0", "Both checkouts require full history")
        require(checkout_with.get("persist-credentials") == "false", "Checkouts must not persist credentials")
    require(control_checkout.get("with", {}).get("ref") == "${{ github.sha }}", "Control checkout must bind exact GITHUB_SHA")
    require(control_checkout.get("with", {}).get("path") == "control", "Control checkout requires an isolated directory")
    require(
        candidate_checkout.get("with", {}).get("ref") == "${{ inputs.candidate_source_sha }}",
        "Candidate checkout must bind only candidate_source_sha",
    )
    require(candidate_checkout.get("with", {}).get("path") == "candidate", "Candidate checkout requires an isolated directory")

    for step in steps:
        run = step.get("run")
        if isinstance(run, str):
            require("${{ inputs." not in run, f"Dispatch input interpolated directly into shell in step {step.get('name')}")
            require(
                re.search(r"\bdocker\s+(?:compose|run|stop|rm|restart|kill|create)\b", run) is None,
                f"Workflow step directly mutates Docker runtime: {step.get('name')}",
            )
            require(
                re.search(r"(^|[\s;&|])(?:npm|node|npx)(?:[\s;&|]|$)", run) is None,
                f"Candidate code must execute only inside Docker BuildKit: {step.get('name')}",
            )
        condition = step.get("if")
        if condition is not None:
            require(
                condition == "always()" and step.get("name") in {
                    "Collect frontend hotfix deployment evidence",
                    "Upload frontend hotfix deployment evidence",
                    "Enforce frontend hotfix deployment result",
                },
                f"Unexpected conditional/bypass in step {step.get('name')}",
            )

    validation = step_by_name(job, "Validate source relationship and frontend-only diff").get("run", "")
    for marker in (
        "^[0-9a-f]{40}$",
        '"refs/heads/main"',
        'git -C control rev-parse HEAD',
        'git -C candidate rev-parse HEAD',
        "merge-base --is-ancestor",
        "rev-list --min-parents=2",
        "diff --name-only --no-renames -z",
        "diff-tree -r --no-commit-id --no-renames --raw -z",
        "moodride-frontend-hotfix-diff-v1",
        "frontend/moodride-web/src/?*|frontend/moodride-web/public/?*",
        "frontend/moodride-web/Dockerfile",
        "frontend/moodride-web/package-lock.json",
        "frontend/moodride-web/next.config.*",
        "frontend/moodride-web/scripts/?*",
        "frontend/moodride-web/.github/?*",
    ):
        require(marker in validation, f"Source/path gate lost required marker: {marker}")
    require("frontend/moodride-web/?*) ;;" not in validation, "Broad arbitrary-frontend path gate is forbidden")
    require("if [ ! -s release/changed-paths.zlist ]" in validation, "Candidate must contain at least one changed path")

    allowed_path_fixtures = (
        "frontend/moodride-web/src/components/RoutePlanner.tsx",
        "frontend/moodride-web/public/wayward.svg",
    )
    forbidden_path_fixtures = (
        "frontend/moodride-web/Dockerfile",
        "frontend/moodride-web/package.json",
        "frontend/moodride-web/package-lock.json",
        "frontend/moodride-web/next.config.ts",
        "frontend/moodride-web/scripts/release.sh",
        "frontend/moodride-web/.github/workflows/build.yml",
        "frontend/moodride-web/tsconfig.json",
        ".github/workflows/deploy-frontend-hotfix.yml",
        "services/route-api/pom.xml",
    )
    for allowed_path in allowed_path_fixtures:
        run_source_gate_fixture(validation, allowed_path, should_pass=True)
    for forbidden_path in forbidden_path_fixtures:
        run_source_gate_fixture(validation, forbidden_path, should_pass=False)

    checker_step = step_by_name(job, "Run focused frontend hotfix contract checker").get("run", "")
    require("control/scripts/deploy/check_frontend_hotfix_contract.py" in checker_step, "Workflow must run the trusted checker")
    require("control/.github/workflows/deploy-frontend-hotfix.yml" in checker_step, "Checker must inspect trusted workflow bytes")
    require("control/scripts/deploy/deploy_frontend_hotfix.sh" in checker_step, "Checker must inspect trusted script bytes")
    require(
        not any(step.get("working-directory") for step in steps),
        "Hotfix workflow must not execute candidate files directly in the runner workspace",
    )

    image_builds = [step for step in steps if str(step.get("uses", "")).startswith("docker/build-push-action@")]
    require(len(image_builds) == 1, "Hotfix workflow must build exactly one image")
    image_build = image_builds[0]
    build_with = image_build.get("with", {})
    require(build_with.get("context") == "candidate/frontend/moodride-web", "Image context must be candidate frontend only")
    require(build_with.get("file") == "candidate/frontend/moodride-web/Dockerfile", "Image must use candidate frontend Dockerfile")
    require(build_with.get("push") == "true", "Frontend image must be pushed")
    require(
        build_with.get("tags") == "${{ steps.image.outputs.tag }}",
        "Frontend image build must consume only the validated image tag output",
    )
    image_values = step_by_name(job, "Resolve production image values").get("run", "")
    require(
        'image_repository="ghcr.io/${namespace}/moodride-frontend"' in image_values,
        "Resolved image repository must be exactly moodride-frontend in the validated GHCR namespace",
    )
    for forbidden_image in ("moodride-route-api", "moodride-route-worker", "moodride-notification-service"):
        require(forbidden_image not in workflow_text, f"Hotfix workflow must not build or deploy {forbidden_image}")
    labels = str(build_with.get("labels", ""))
    require("org.opencontainers.image.source=" in labels, "Candidate image source label is required")
    require("org.opencontainers.image.revision=" in labels, "Candidate image revision label is required")
    build_args = str(build_with.get("build-args", ""))
    for build_arg in ("NEXT_PUBLIC_API_BASE_URL", "NEXT_PUBLIC_WS_BASE_URL", "NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN"):
        require(build_arg in build_args, f"Production frontend build argument missing: {build_arg}")

    manifest = step_by_name(job, "Create checksummed frontend hotfix manifest").get("run", "")
    for marker in (
        "moodride.frontend-hotfix-manifest/v1",
        "workflow_sha",
        "workflow_source_url",
        "deployment_script_sha256",
        "expected_current_sha",
        "candidate_sha",
        "diff_digest",
        "index_digest",
        "frontend-hotfix-manifest.sha256",
        "deploy_frontend_hotfix.${script_sha256}.sh",
    ):
        require(marker in manifest, f"Manifest lost required immutable binding: {marker}")

    remote_upload = step_by_name(job, "Upload checksummed trusted deployment bytes").get("run", "")
    remote_execute = step_by_name(job, "Verify and execute immutable frontend-only deployment").get("run", "")
    require("scp release/frontend-hotfix-manifest.json" in remote_upload, "Manifest must be uploaded before execution")
    for descriptor in ("/proc/self/fd/3", "/proc/self/fd/4", "/proc/self/fd/5"):
        require(descriptor in remote_execute, f"Verified open descriptor is missing: {descriptor}")
    require(
        "sha256sum /proc/self/fd/3" in remote_execute
        and "sha256sum /proc/self/fd/5" in remote_execute
        and "exec bash /proc/self/fd/5" in remote_execute,
        "Remote deployment must verify and execute the same already-open bytes",
    )
    require("--manifest-checksum /proc/self/fd/4" in remote_execute, "Immutable checksum sidecar descriptor is missing")
    require("--script-sha256" in remote_execute, "Immutable script invocation checksum is missing")
    require("set +e" in remote_execute and "exit_code=$deploy_exit_code" in remote_execute, "Remote status must be captured for unconditional evidence")
    enforce = step_by_name(job, "Enforce frontend hotfix deployment result").get("run", "")
    require('exit "$DEPLOY_EXIT_CODE"' in enforce, "Captured deployment failure must be re-emitted")
    require("frontend-hotfix-staging/${RUN_ID}-${RUN_ATTEMPT}-${MANIFEST_SHA256}" in workflow_text, "Remote stage must be unique and immutable")


def check_shell(path: pathlib.Path) -> None:
    shell = path.read_text(encoding="utf-8")
    resolved = path.resolve()
    try:
        shell_argument = resolved.relative_to(ROOT).as_posix()
        shell_cwd = ROOT
    except ValueError:
        shell_argument = resolved.as_posix()
        shell_cwd = None
    syntax = subprocess.run(
        ["bash", "-n", shell_argument],
        cwd=shell_cwd,
        text=True,
        capture_output=True,
        check=False,
    )
    require(syntax.returncode == 0, f"Deployment script failed bash syntax parsing:\n{syntax.stderr}")
    for marker in (
        "set -Eeuo pipefail",
        "umask 077",
        '.deploy/prod-cutover.lock',
        "flock -n 9",
        "moodride.frontend-hotfix-manifest/v1",
        "^[0-9a-f]{40}$",
        "resolve_repo_digest",
        "org.opencontainers.image.revision",
        "org.opencontainers.image.source",
        "docker pull \"$CANDIDATE_IMAGE_REF\"",
        "moodride-frontend-hotfix-preflight-",
        "127.0.0.1::3000",
        "HTTP 200 with Wayward page content",
        ".deploy/frontend-hotfixes",
        "current-frontend-hotfix",
        "frontend-${checksum}.compose.yml",
        'compose_override "$CANDIDATE_OVERRIDE" up -d --no-deps --force-recreate frontend',
        'compose_override "$ROLLBACK_OVERRIDE" up -d --no-deps --force-recreate frontend',
        "capture_non_frontend_ids",
        "check_non_frontend_fence",
        "A non-frontend production container ID changed",
        "rollback_frontend",
        'create_override "$BEFORE_FRONTEND_REF" rollback',
        "PUBLIC_HEALTH_URL",
        "moodride.frontend-hotfix-deployment-evidence/v1",
    ):
        require(marker in shell, f"Deployment script lost required safety marker: {marker}")
    require("exec 9>>\"$MOODRIDE_DIR/.deploy/prod-cutover.lock\"" in shell, "Unified lock must be opened persistently")
    require("rm -f -- \"$MOODRIDE_DIR/.deploy/prod-cutover.lock\"" not in shell, "Unified lock file must never be unlinked")
    require("compose_base up" not in shell, "Base Compose may never be used for a stack-wide up")
    require("docker compose down" not in shell, "Hotfix script may not stop the Compose project")
    require(not re.search(r"compose_(?:base|override).*\b(?:stop|down|restart|rm)\b", shell), "Hotfix script contains a destructive Compose command")
    require("set_env_var" not in shell and "source \"$ENV_FILE\"" not in shell, "Hotfix must not mutate or source production backend/database identities")
    require("--remove-orphans" not in shell, "Frontend cutover may not remove production services")
    forbidden_service_command = re.compile(
        r"compose_(?:base|override)[^\n]*(?:up|stop|restart|rm)[^\n]*\b(?:caddy|route-api|route-worker|notification-service|postgres|redis|kafka|zookeeper|osrm)\b"
    )
    require(not forbidden_service_command.search(shell), "Hotfix script may not mutate a non-frontend service")
    require(shell.count("--no-deps --force-recreate frontend") == 2, "Only candidate and exact rollback frontend recreates are allowed")
    require("-f \"$COMPOSE_FILE\" -f \"$override\"" in shell, "Legacy base Compose plus checksum override is required")
    require("BEFORE_FRONTEND_REVISION" in shell and "EXPECTED_CURRENT_SOURCE_SHA" in shell, "Stale current-source gate is missing")
    require("BEFORE_FRONTEND_REF" in shell and "ROLLBACK_OVERRIDE" in shell, "Rollback must use the exact previous RepoDigest")
    require("POINTER_PREVIOUS_PRESENT" in shell and "restore_pointer" in shell, "Pointer rollback state must be preserved atomically")


DOCKER_STUB = r'''#!/usr/bin/env python3
import json
import os
import pathlib
import re
import sys

state_path = pathlib.Path(os.environ["HOTFIX_FIXTURE_STATE"])
state = json.loads(state_path.read_text(encoding="utf-8"))
args = sys.argv[1:]

def save():
    state_path.write_text(json.dumps(state), encoding="utf-8")

def log(message):
    with pathlib.Path(os.environ["HOTFIX_FIXTURE_LOG"]).open("a", encoding="utf-8") as handle:
        handle.write(message + "\n")

def image_for(value):
    for ref in (state["prior_ref"], state["candidate_ref"]):
        image = state["images"][ref]
        if value == ref or value == image["id"]:
            return image
    raise SystemExit(1)

def current_frontend_id():
    if state["current_ref"] == state["prior_ref"]:
        return state.get("prior_frontend_id", "frontend-prior-id")
    return "frontend-candidate-id"

if not args:
    raise SystemExit(2)

if args[0] == "compose":
    remaining = args[1:]
    if remaining == ["version"]:
        print("Docker Compose fixture")
        raise SystemExit(0)
    files = []
    index = 0
    while index < len(remaining):
        if remaining[index] in ("--env-file", "-f"):
            if remaining[index] == "-f":
                files.append(remaining[index + 1])
            index += 2
            continue
        break
    command = remaining[index:]
    if command == ["config", "--services"]:
        print("\n".join(state["services"]))
        raise SystemExit(0)
    if len(command) == 3 and command[:2] == ["ps", "-q"]:
        service = command[2]
        if service == "frontend":
            print(current_frontend_id())
        else:
            print(state["ids"][service])
        raise SystemExit(0)
    if command and command[0] == "up":
        if command[-1] != "frontend" or "--no-deps" not in command or "--force-recreate" not in command:
            log("FORBIDDEN_COMPOSE_UP " + " ".join(command))
            raise SystemExit(91)
        override = pathlib.Path(files[-1]).read_text(encoding="utf-8")
        match = re.search(r"^\s*image:\s*(\S+)\s*$", override, re.MULTILINE)
        if not match:
            raise SystemExit(92)
        image_ref = match.group(1)
        log("FRONTEND_UP " + image_ref)
        if image_ref == state["candidate_ref"]:
            state["current_ref"] = state["candidate_ref"]
            if state["mode"] == "changed_nonfrontend_id":
                state["ids"]["route-api"] = "route-api-id-changed"
        elif image_ref == state["prior_ref"]:
            state["current_ref"] = state["prior_ref"]
            state["prior_frontend_id"] = "frontend-rollback-id"
        else:
            raise SystemExit(93)
        save()
        raise SystemExit(0)
    raise SystemExit("Unsupported compose fixture command: " + repr(command))

if args[0] == "pull":
    ref = args[1]
    if ref != state["candidate_ref"]:
        raise SystemExit(94)
    log("PULL " + ref)
    if state["mode"] == "candidate_oci_mismatch":
        state["images"][state["candidate_ref"]]["revision"] = "f" * 40
        save()
    raise SystemExit(0)

if args[0:2] == ["image", "inspect"]:
    fmt = args[args.index("--format") + 1]
    image = image_for(args[-1])
    if "RepoDigests" in fmt:
        print(image["ref"])
    elif "org.opencontainers.image.revision" in fmt:
        print(image["revision"])
    elif "org.opencontainers.image.source" in fmt:
        print(image["source"])
    else:
        raise SystemExit("Unsupported image inspect format: " + fmt)
    raise SystemExit(0)

if args[0] == "inspect":
    target = args[-1]
    if target.startswith("moodride-frontend-hotfix-preflight-"):
        if not state.get("preflight_running", False):
            raise SystemExit(1)
        print("{}")
        raise SystemExit(0)
    fmt = args[args.index("--format") + 1] if "--format" in args else ""
    frontend_id = current_frontend_id()
    if target == frontend_id:
        if ".State.Running" in fmt:
            print("true")
        elif "com.docker.compose.project" in fmt:
            print("moodride")
        elif fmt == "{{.Image}}":
            print(image_for(state["current_ref"])["id"])
        else:
            raise SystemExit("Unsupported frontend inspect format: " + fmt)
        raise SystemExit(0)
    if target in state["ids"].values():
        if ".State.Running" in fmt:
            print("true")
            raise SystemExit(0)
    raise SystemExit(1)

if args[0] == "ps":
    print(current_frontend_id())
    raise SystemExit(0)

if args[0] == "run":
    require_ref = args[-1]
    if require_ref != state["candidate_ref"] or "127.0.0.1::3000" not in args:
        raise SystemExit(95)
    state["preflight_running"] = True
    save()
    log("PREFLIGHT_RUN " + require_ref)
    print("preflight-fixture-id")
    raise SystemExit(0)

if args[0] == "port":
    if not state.get("preflight_running", False):
        raise SystemExit(96)
    print("127.0.0.1:38080")
    raise SystemExit(0)

if args[0] == "rm":
    state["preflight_running"] = False
    save()
    log("PREFLIGHT_REMOVE")
    print(args[-1])
    raise SystemExit(0)

raise SystemExit("Unsupported docker fixture command: " + repr(args))
'''

CURL_STUB = r'''#!/usr/bin/env python3
import json
import os
import pathlib
import sys

args = sys.argv[1:]
state = json.loads(pathlib.Path(os.environ["HOTFIX_FIXTURE_STATE"]).read_text(encoding="utf-8"))
output = pathlib.Path(args[args.index("--output") + 1])
url = args[-1]
status = "200"
body = "<html><title>Wayward</title><body>Wayward</body></html>"
if url.startswith("http://127.0.0.1:") and state["mode"] == "preflight_failure":
    status = "503"
    body = "not ready"
elif url.startswith("https://") and state["mode"] == "swap_public_failure" and state["current_ref"] == state["candidate_ref"]:
    status = "503"
    body = "not ready"
output.write_text(body, encoding="utf-8")
sys.stdout.write(status)
'''


def manifest_for_fixture(
    path: pathlib.Path,
    script_sha: str,
    expected_sha: str,
    candidate_sha: str,
    source: str,
    repository: str,
    candidate_digest: str,
) -> pathlib.Path:
    manifest = {
        "schema_version": "moodride.frontend-hotfix-manifest/v1",
        "control": {
            "workflow_sha": "3" * 40,
            "workflow_source_url": source + "/blob/" + "3" * 40 + "/.github/workflows/deploy-frontend-hotfix.yml",
            "deployment_script_sha256": script_sha,
        },
        "source": {
            "repository_source": source,
            "expected_current_sha": expected_sha,
            "candidate_sha": candidate_sha,
            "diff_digest": "sha256:" + "4" * 64,
            "changed_path_count": 1,
        },
        "image": {
            "repository": repository,
            "tag": f"{repository}:frontend-hotfix-{candidate_sha}-9001-2",
            "ref": f"{repository}@sha256:{candidate_digest}",
            "index_digest": "sha256:" + candidate_digest,
        },
        "run": {"id": "9001", "attempt": "2"},
        "created_at": "2026-07-20T00:00:00Z",
    }
    manifest_path = path / "frontend-hotfix-manifest.json"
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    manifest_path.write_bytes(manifest_bytes)
    (path / "frontend-hotfix-manifest.sha256").write_text(
        hashlib.sha256(manifest_bytes).hexdigest() + "  frontend-hotfix-manifest.json\n",
        encoding="ascii",
    )
    return manifest_path


def run_fixture(script: pathlib.Path, mode: str) -> tuple[subprocess.CompletedProcess[str], pathlib.Path, dict[str, Any], str, str]:
    temporary = tempfile.TemporaryDirectory(prefix=f"frontend-hotfix-{mode}-")
    root = pathlib.Path(temporary.name)
    # Keep the TemporaryDirectory alive by attaching it to the returned CompletedProcess.
    production = root / "production"
    stage = root / "stage"
    stub_bin = root / "bin"
    production.mkdir()
    stage.mkdir()
    stub_bin.mkdir()
    (production / "docker-compose.prod.yml").write_text(
        textwrap.dedent(
            """\
            services:
              postgres: {image: postgres:15}
              redis: {image: redis:7}
              zookeeper: {image: zookeeper:3}
              kafka: {image: kafka:3}
              osrm: {image: osrm:5}
              route-api: {image: route-api:old}
              route-worker: {image: route-worker:old}
              notification-service: {image: notification:old}
              frontend: {image: frontend:old}
              caddy: {image: caddy:2}
            """
        ),
        encoding="utf-8",
    )
    (production / ".env.prod").write_text("FIXTURE_ONLY=1\n", encoding="utf-8")
    docker_path = stub_bin / "docker"
    curl_path = stub_bin / "curl"
    docker_path.write_text(DOCKER_STUB, encoding="utf-8")
    curl_path.write_text(CURL_STUB, encoding="utf-8")
    docker_path.chmod(0o700)
    curl_path.chmod(0o700)

    expected_sha = "1" * 40
    candidate_sha = "2" * 40
    source = "https://github.com/acme/wayward"
    repository = "ghcr.io/acme/moodride-frontend"
    prior_digest = "a" * 64
    candidate_digest = "b" * 64
    prior_ref = f"{repository}@sha256:{prior_digest}"
    candidate_ref = f"{repository}@sha256:{candidate_digest}"
    script_sha = hashlib.sha256(script.read_bytes()).hexdigest()
    manifest_path = manifest_for_fixture(stage, script_sha, expected_sha, candidate_sha, source, repository, candidate_digest)
    state = {
        "mode": mode,
        "services": [
            "postgres", "redis", "zookeeper", "kafka", "osrm", "route-api", "route-worker",
            "notification-service", "frontend", "caddy",
        ],
        "ids": {
            "postgres": "postgres-id-1", "redis": "redis-id-1", "zookeeper": "zookeeper-id-1",
            "kafka": "kafka-id-1", "osrm": "osrm-id-1", "route-api": "route-api-id-1",
            "route-worker": "route-worker-id-1", "notification-service": "notification-id-1", "caddy": "caddy-id-1",
        },
        "prior_ref": prior_ref,
        "candidate_ref": candidate_ref,
        "current_ref": prior_ref,
        "prior_frontend_id": "frontend-prior-id",
        "preflight_running": False,
        "images": {
            prior_ref: {"ref": prior_ref, "id": "sha256:" + "c" * 64, "revision": expected_sha, "source": source},
            candidate_ref: {"ref": candidate_ref, "id": "sha256:" + "d" * 64, "revision": candidate_sha, "source": source},
        },
    }
    if mode == "stale_current_source":
        state["images"][prior_ref]["revision"] = "e" * 40
    state_path = root / "state.json"
    log_path = root / "docker.log"
    state_path.write_text(json.dumps(state), encoding="utf-8")
    log_path.write_text("", encoding="utf-8")
    evidence_path = stage / "deployment-evidence.json"
    diagnostics_path = stage / "deployment-diagnostics.log"
    environment = os.environ.copy()
    environment.update(
        {
            "PATH": str(stub_bin) + os.pathsep + environment.get("PATH", ""),
            "HOTFIX_FIXTURE_STATE": str(state_path),
            "HOTFIX_FIXTURE_LOG": str(log_path),
            "MOODRIDE_DIR": str(production),
            "COMPOSE_FILE": "docker-compose.prod.yml",
            "ENV_FILE": ".env.prod",
            "PUBLIC_HEALTH_URL": "https://usewayward.fixture/",
            "HEALTHCHECK_TIMEOUT_SECONDS": "0",
            "HEALTHCHECK_INTERVAL_SECONDS": "0",
        }
    )
    result = subprocess.run(
        [
            "bash", str(script),
            "--manifest", str(manifest_path),
            "--manifest-checksum", str(stage / "frontend-hotfix-manifest.sha256"),
            "--script-sha256", script_sha,
            "--evidence", str(evidence_path),
            "--diagnostics", str(diagnostics_path),
        ],
        cwd=root,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
        timeout=30,
    )
    final_state = json.loads(state_path.read_text(encoding="utf-8"))
    log = log_path.read_text(encoding="utf-8")
    evidence = json.loads(evidence_path.read_text(encoding="utf-8")) if evidence_path.is_file() else {}
    setattr(result, "_fixture_directory", temporary)
    return result, production, evidence, log, candidate_ref


def run_behavior_fixtures(script: pathlib.Path) -> None:
    success, success_root, success_evidence, success_log, candidate_ref = run_fixture(script, "success")
    require(success.returncode == 0, f"Success fixture failed:\n{success.stdout}\n{success.stderr}")
    require(success_evidence.get("result", {}).get("accepted") is True, "Success evidence did not accept candidate")
    require(success_evidence.get("after", {}).get("frontend", {}).get("ref") == candidate_ref, "Success did not bind candidate digest")
    require(success_evidence.get("cutover", {}).get("non_frontend_id_fence") == "passed", "Success did not prove non-frontend ID fence")
    pointer = success_root / ".deploy" / "frontend-hotfixes" / "current-frontend-hotfix"
    pointer_data = json.loads(pointer.read_text(encoding="utf-8"))
    require(pointer_data["status"] == "accepted" and pointer_data["image_ref"] == candidate_ref, "Accepted pointer is not digest reproducible")
    require("FORBIDDEN_COMPOSE_UP" not in success_log, "Success fixture attempted a non-frontend Compose mutation")

    preflight, _, preflight_evidence, preflight_log, _ = run_fixture(script, "preflight_failure")
    require(preflight.returncode != 0, "Candidate preflight failure fixture unexpectedly succeeded")
    require("FRONTEND_UP" not in preflight_log, "Preflight failure mutated production frontend")
    require(preflight_evidence.get("health", {}).get("candidate_preflight") == "failed", "Preflight failure evidence missing")
    require(preflight_evidence.get("rollback", {}).get("state") == "not-required", "Preflight failure must not roll back an unmutated frontend")

    swap, _, swap_evidence, swap_log, swap_candidate_ref = run_fixture(script, "swap_public_failure")
    require(swap.returncode != 0, "Swap/public-health failure fixture unexpectedly succeeded")
    prior_ref = swap_evidence.get("before", {}).get("frontend", {}).get("ref")
    require(
        "FRONTEND_UP " + swap_candidate_ref in swap_log and "FRONTEND_UP " + str(prior_ref) in swap_log,
        "Swap failure did not recreate candidate then exact prior digest",
    )
    require(swap_evidence.get("rollback", {}).get("state") == "succeeded", "Swap failure did not complete exact rollback")
    require(swap_evidence.get("rollback", {}).get("image_ref") == prior_ref, "Rollback evidence lost exact prior RepoDigest")
    require(swap_evidence.get("health", {}).get("rollback_public_https") == "passed:http-200-wayward", "Rollback public health was not proven")

    stale, _, stale_evidence, stale_log, _ = run_fixture(script, "stale_current_source")
    require(stale.returncode != 0, "Stale expected-current fixture unexpectedly succeeded")
    require("FRONTEND_UP" not in stale_log and "PREFLIGHT_RUN" not in stale_log, "Stale expected source was not rejected before candidate execution")
    require(stale_evidence.get("result", {}).get("accepted") is False, "Stale source evidence incorrectly accepted")

    mismatch, _, mismatch_evidence, mismatch_log, _ = run_fixture(script, "candidate_oci_mismatch")
    require(mismatch.returncode != 0, "Candidate OCI mismatch fixture unexpectedly succeeded")
    require("FRONTEND_UP" not in mismatch_log and "PREFLIGHT_RUN" not in mismatch_log, "OCI mismatch reached candidate execution/mutation")
    require("OCI revision" in mismatch_evidence.get("result", {}).get("failure_reason", ""), "OCI mismatch reason missing from evidence")

    changed, _, changed_evidence, changed_log, changed_candidate_ref = run_fixture(script, "changed_nonfrontend_id")
    require(changed.returncode != 0, "Changed non-frontend ID fixture unexpectedly succeeded")
    changed_prior_ref = changed_evidence.get("before", {}).get("frontend", {}).get("ref")
    require(
        "FRONTEND_UP " + changed_candidate_ref in changed_log and "FRONTEND_UP " + str(changed_prior_ref) in changed_log,
        "Non-frontend fence failure did not attempt exact frontend rollback",
    )
    require(changed_evidence.get("cutover", {}).get("non_frontend_id_fence") == "failed", "Changed service ID fence was not recorded")
    require(changed_evidence.get("result", {}).get("accepted") is False, "Changed non-frontend ID was accepted")
    before_ids = changed_evidence.get("before", {}).get("non_frontend_container_ids", {})
    after_ids = changed_evidence.get("after", {}).get("non_frontend_container_ids", {})
    require(before_ids.get("route-api") != after_ids.get("route-api"), "Changed route-api fixture was not observable in evidence")
    require("FORBIDDEN_COMPOSE_UP" not in changed_log, "Fixture observed a backend/Caddy/DB Compose mutation")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workflow", type=pathlib.Path, default=DEFAULT_WORKFLOW)
    parser.add_argument("--script", type=pathlib.Path, default=DEFAULT_SCRIPT)
    parser.add_argument("--skip-fixtures", action="store_true", help="Run static parsing only (workflow self-check use)")
    arguments = parser.parse_args()
    require(arguments.workflow.is_file(), f"Workflow not found: {arguments.workflow}")
    require(arguments.script.is_file(), f"Deployment script not found: {arguments.script}")
    require(shutil.which("bash") is not None, "bash is required for shell parsing and fixtures")
    check_workflow(arguments.workflow)
    check_shell(arguments.script)
    if not arguments.skip_fixtures and os.name != "nt":
        run_behavior_fixtures(arguments.script)
    print("Frontend hotfix contract checks passed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, ValueError, subprocess.TimeoutExpired) as exc:
        print(f"Frontend hotfix contract check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
