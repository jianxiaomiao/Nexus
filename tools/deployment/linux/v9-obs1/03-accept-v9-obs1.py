#!/usr/bin/env python3
"""Run one real loopback V9 acceptance flow and remove its exact data."""

from __future__ import annotations

import http.client
import hashlib
import json
import os
import secrets
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


RELEASE = "V2026.10.27.1__v9-obs1"
STATE_FILE = Path(f"/opt/nexus/state/deployment-{RELEASE}.env")
PASS_MARKER = Path(f"/opt/nexus/state/{RELEASE}.acceptance-passed")
CA_FILE = "/etc/nginx/tls-lab/ca.crt"
BROWSER_ROOT = Path("/opt/nexus/browsers/playwright-1.63.0")
ARTICLE_URL = "https://blog.csdn.net/qq_46429858/article/details/115696591"


def fail(message: str) -> None:
    raise RuntimeError(message)


def run(command: list[str], *, input_bytes: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(command, input=input_bytes, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode != 0:
        fail(f"command failed ({command[0]}): {result.stderr.decode(errors='replace').strip()}")
    return result


def mysql(sql: str) -> str:
    return run([
        "mysql", "--protocol=socket", "--batch", "--skip-column-names",
        "nexus", "-e", sql,
    ]).stdout.decode().strip()


def load_state() -> dict[str, str]:
    if not STATE_FILE.is_file():
        fail(f"deployment state is missing: {STATE_FILE}")
    result: dict[str, str] = {}
    for line in STATE_FILE.read_text(encoding="utf-8").splitlines():
        if line and "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    if result.get("STATUS") != "PENDING" or result.get("RELEASE") != RELEASE:
        fail(f"deployment is not pending for {RELEASE}")
    return result


def metric(name: str, query: str = '') -> dict[str, float]:
    connection = http.client.HTTPConnection('127.0.0.1', 8081, timeout=10)
    try:
        connection.request('GET', '/actuator/metrics/' + name + query)
        response = connection.getresponse()
        payload = response.read()
        if response.status != 200:
            fail('management metric is unavailable: ' + name)
        return {item['statistic']: float(item['value'])
                for item in json.loads(payload)['measurements']}
    finally:
        connection.close()


class LoopbackHTTPSConnection(http.client.HTTPSConnection):
    def connect(self) -> None:
        raw = socket.create_connection(("127.0.0.1", 18443), self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname="nexus.test")


class Client:
    def __init__(self) -> None:
        context = ssl.create_default_context(cafile=CA_FILE)
        self.context = context

    def request(self, method: str, path: str, body: object | None = None,
                authorization: str | None = None) -> tuple[int, dict[str, str], bytes]:
        headers = {"Accept": "application/json"}
        encoded = None
        if body is not None:
            encoded = json.dumps(body, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        if authorization is not None:
            headers["Authorization"] = authorization
        connection = LoopbackHTTPSConnection("nexus.test", 18443,
                                             timeout=45, context=self.context)
        try:
            connection.request(method, path, body=encoded, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            response_headers = {name.lower(): value for name, value in response.getheaders()}
            return response.status, response_headers, payload
        finally:
            connection.close()

    def api(self, method: str, path: str, body: object | None = None,
            authorization: str | None = None, expected: int = 200) -> dict[str, object]:
        status, _, payload = self.request(method, path, body, authorization)
        try:
            document = json.loads(payload)
        except json.JSONDecodeError:
            fail(f"{method} {path} returned non-JSON HTTP {status}")
        if status != expected:
            fail(f"{method} {path} returned HTTP {status}, code={document.get('code')}")
        if expected < 300 and document.get("code") != "SUCCESS":
            fail(f"{method} {path} returned unexpected code={document.get('code')}")
        data = document.get("data")
        if expected < 300 and not isinstance(data, dict):
            fail(f"{method} {path} returned no object data")
        return document


def browser_processes() -> list[Path]:
    found: list[Path] = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            executable = (proc / "exe").resolve(strict=True)
        except (FileNotFoundError, PermissionError, OSError):
            continue
        try:
            executable.relative_to(BROWSER_ROOT)
        except ValueError:
            continue
        found.append(executable)
    return found


def wait_usage(api_key_id: int) -> list[tuple[str, int]]:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        rows = mysql(
            "SELECT api_code, http_status_code FROM usage_events "
            f"WHERE api_key_id={api_key_id} ORDER BY id"
        )
        parsed: list[tuple[str, int]] = []
        if rows:
            for line in rows.splitlines():
                code, status = line.split("\t", 1)
                parsed.append((code, int(status)))
        if ("uuid.generate", 200) in parsed and ("web.screenshot", 201) in parsed:
            return parsed
        time.sleep(0.25)
    fail("Usage did not record uuid.generate=200 and web.screenshot=201 within 15 seconds")


def cleanup(application_id: int, user_id: int, key_ids: list[int], job_id: str | None) -> None:
    if job_id is not None:
        result = Path("/var/lib/nexus/screenshot-jobs") / f"{job_id}.png"
        result.unlink(missing_ok=True)
    statements = ["START TRANSACTION;"]
    if key_ids:
        key_list = ",".join(str(value) for value in key_ids)
        statements.extend([
            f"DELETE FROM screenshot_jobs WHERE api_key_id IN ({key_list});",
            f"DELETE FROM usage_events WHERE api_key_id IN ({key_list});",
            f"DELETE FROM short_links WHERE api_key_id IN ({key_list});",
        ])
    if application_id > 0:
        statements.extend([
            f"DELETE FROM application_quotas WHERE application_id={application_id};",
            f"DELETE FROM api_keys WHERE application_id={application_id};",
            f"DELETE FROM applications WHERE id={application_id};",
        ])
    if user_id > 0:
        statements.append(f"DELETE FROM users WHERE id={user_id};")
    statements.append("COMMIT;")
    sql = "".join(statements)
    mysql(sql)
    counts = []
    if user_id > 0:
        counts.append(f"(SELECT COUNT(*) FROM users WHERE id={user_id})")
    if application_id > 0:
        counts.extend([
            f"(SELECT COUNT(*) FROM applications WHERE id={application_id})",
            f"(SELECT COUNT(*) FROM api_keys WHERE application_id={application_id})",
            f"(SELECT COUNT(*) FROM application_quotas WHERE application_id={application_id})",
        ])
    if key_ids:
        key_list = ",".join(str(value) for value in key_ids)
        counts.extend([
            f"(SELECT COUNT(*) FROM usage_events WHERE api_key_id IN ({key_list}))",
            f"(SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id IN ({key_list}))",
        ])
    remaining = mysql("SELECT " + " + ".join(counts)) if counts else "0"
    if remaining != "0":
        fail(f"acceptance cleanup left {remaining} database rows")


def main() -> None:
    if os.geteuid() != 0:
        fail("run this script as root")
    state = load_state()
    if PASS_MARKER.exists():
        fail(f"acceptance marker already exists: {PASS_MARKER}")
    if Path("/opt/nexus/current").resolve().name != RELEASE:
        fail("current is not the candidate release")
    if run(["systemctl", "is-active", "nexus.service"]).stdout.strip() != b"active":
        fail("nexus.service is not active")
    if run(["systemctl", "is-active", "nginx.service"]).stdout.strip() != b"active":
        fail("nginx.service is not active")
    if run(["sysctl", "-n", "kernel.apparmor_restrict_unprivileged_userns"]).stdout.strip() != b"1":
        fail("the global AppArmor userns restriction is not enabled")
    if b"nexus-playwright-headless-shell" not in run(["aa-status"]).stdout:
        fail("the Nexus Playwright AppArmor profile is not loaded")

    started_at = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
    suffix = uuid.uuid4().hex
    email = f"nexus-v9-obs1-accept-{suffix}@example.test"
    password = secrets.token_urlsafe(24)
    client = Client()
    bearer = ""
    jwt = ""
    key_a = ""
    key_b = ""
    application_id = 0
    user_id = 0
    key_ids: list[int] = []
    job_id: str | None = None
    terminal_status: str | None = None
    observer_stop = threading.Event()
    browser_seen = threading.Event()

    def observe_browser() -> None:
        while not observer_stop.wait(0.1):
            if browser_processes():
                browser_seen.set()

    observer = threading.Thread(target=observe_browser, name="chromium-observer", daemon=True)

    try:
        failures_before = metric('nexus.usage.record.failures')['COUNT']
        queue_before = metric('nexus.screenshot.queue.delay')['COUNT']
        execution_query = '?tag=mode:async&tag=outcome:succeeded'
        execution_before = metric('nexus.screenshot.execution', execution_query)['COUNT']
        client.api("POST", "/api/auth/register", {
            "email": email,
            "displayName": "V9 OBS-1 acceptance",
            "password": password,
        }, expected=201)
        user_id = int(mysql(f"SELECT id FROM users WHERE email='{email}'"))

        login = client.api("POST", "/api/auth/login", {"email": email, "password": password})
        jwt = str(login["data"]["accessToken"])
        bearer = "Bearer " + jwt

        application = client.api("POST", "/api/application/create", {
            "name": f"v9-obs1-accept-{suffix}",
        }, bearer)
        application_id = int(application["data"]["id"])

        created_a = client.api("POST", "/api/apiKey/create", {
            "applicationId": application_id,
            "name": "V9 acceptance Key A",
        }, bearer)
        created_b = client.api("POST", "/api/apiKey/create", {
            "applicationId": application_id,
            "name": "V9 acceptance Key B",
        }, bearer)
        key_ids = [int(created_a["data"]["id"]), int(created_b["data"]["id"])]
        key_a = str(created_a["data"]["apiKey"])
        key_b = str(created_b["data"]["apiKey"])

        quota = client.api("GET", f"/api/application/{application_id}/quota",
                           authorization=bearer)
        if int(quota["data"]["usedCount"]) != 0:
            fail("fresh Application quota was not zero")

        uuid_result = client.api("GET", "/v1/utils/uuid",
                                 authorization="ApiKey " + key_a)
        if not uuid_result["data"].get("uuid"):
            fail("UUID response had no UUID")
        quota = client.api("GET", f"/api/application/{application_id}/quota",
                           authorization=bearer)
        if int(quota["data"]["usedCount"]) != 1:
            fail("UUID did not consume exactly one quota unit")

        observer.start()
        created = client.api("POST", "/v1/web/screenshot-jobs", {"url": ARTICLE_URL},
                             authorization="ApiKey " + key_a, expected=201)
        job_id = str(created["data"]["jobId"])
        if str(created["data"]["status"]) != "QUEUED":
            fail("new screenshot job was not QUEUED")

        hidden = client.api("GET", f"/v1/web/screenshot-jobs/{job_id}",
                            authorization="ApiKey " + key_b, expected=404)
        if hidden.get("code") != "SCREENSHOT_JOB_NOT_FOUND":
            fail("cross-Key screenshot lookup did not preserve ownership hiding")

        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            status_document = client.api("GET", f"/v1/web/screenshot-jobs/{job_id}",
                                         authorization="ApiKey " + key_a)
            terminal_status = str(status_document["data"]["status"])
            if terminal_status in ("SUCCEEDED", "FAILED", "EXPIRED"):
                break
            if terminal_status not in ("QUEUED", "RUNNING"):
                fail(f"unexpected screenshot job status: {terminal_status}")
            time.sleep(0.25)
        else:
            fail("screenshot job did not reach a terminal state within 90 seconds")

        observer_stop.set()
        observer.join(timeout=2)
        if terminal_status != "SUCCEEDED":
            fail(f"screenshot job ended as {terminal_status}")
        if not browser_seen.is_set():
            fail("the fixed Playwright browser executable was not observed")

        result_status, result_headers, png = client.request(
            "GET", f"/v1/web/screenshot-jobs/{job_id}/result",
            authorization="ApiKey " + key_a)
        if result_status != 200 or result_headers.get("content-type") != "image/png":
            fail(f"screenshot result was not HTTP 200 image/png: {result_status}")
        if result_headers.get("cache-control") != "no-store":
            fail("screenshot result did not use Cache-Control: no-store")
        if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png) < 1000:
            fail("screenshot result was not a non-trivial PNG")
        if struct.unpack('>II', png[16:24]) != (1280, 720):
            fail('screenshot PNG was not 1280x720')
        if hashlib.sha256(png).hexdigest() != status_document['data']['resultSha256']:
            fail('downloaded PNG SHA-256 differs from task metadata')
        if len(png) != int(status_document['data']['resultSizeBytes']):
            fail('downloaded PNG size differs from task metadata')

        quota = client.api("GET", f"/api/application/{application_id}/quota",
                           authorization=bearer)
        if int(quota["data"]["usedCount"]) != 2:
            fail("UUID plus asynchronous screenshot did not consume exactly two quota units")

        usage = wait_usage(key_ids[0])
        if sorted(usage) != sorted([("uuid.generate", 200), ("web.screenshot", 201)]):
            fail(f"unexpected Usage rows for acceptance Key: {usage}")
        # The Worker commits SUCCEEDED before its finally block records execution
        # and releases the Gate. Wait for that boundary before checking counts.
        deadline = time.monotonic() + 5
        while metric('nexus.screenshot.slot.busy')['VALUE'] != 0:
            if time.monotonic() >= deadline:
                fail('screenshot Gate did not return to idle')
            time.sleep(0.1)
        if metric('nexus.usage.record.failures')['COUNT'] != failures_before:
            fail('Usage failure count grew during acceptance')
        if metric('nexus.screenshot.queue.delay')['COUNT'] != queue_before + 1:
            fail('queue delay did not record exactly one first claim')
        if metric('nexus.screenshot.execution', execution_query)['COUNT'] != execution_before + 1:
            fail('async execution did not record exactly one successful attempt')
        if metric('nexus.screenshot.jobs.unfinished')['VALUE'] != 0:
            fail('screenshot unfinished count did not return to zero')

        prometheus_status, _, prometheus = client.request("GET", "/actuator/prometheus")
        if prometheus_status != 404:
            fail("Actuator unexpectedly became reachable through Nginx")
        management = run(["curl", "--noproxy", "*", "--silent", "--show-error", "--fail",
                          "http://127.0.0.1:8081/actuator/prometheus"]).stdout
        for metric_name in (b"nexus_screenshot_execution_seconds_count",
                       b"nexus_screenshot_queue_delay_seconds_count",
                       b"nexus_screenshot_jobs_unfinished",
                       b"nexus_usage_record_failures_total"):
            if metric_name not in management:
                fail(f"management output is missing {metric_name.decode()}")

        deadline = time.monotonic() + 15
        while browser_processes() and time.monotonic() < deadline:
            time.sleep(0.25)
        if browser_processes():
            fail("Chromium processes remained after the completed screenshot job")

        journal = run(["journalctl", "-u", "nexus.service", "--since", started_at,
                       "--no-pager", "-o", "cat"]).stdout
        logs = journal
        for log_path in (Path('/var/log/nexus/nexus.log'),
                         Path('/var/log/nginx/nexus-loopback-access.log'),
                         Path('/var/log/nginx/nexus-loopback-error.log')):
            if log_path.is_file():
                logs += log_path.read_bytes()
        for secret in (password.encode(), jwt.encode(), key_a.encode(), key_b.encode()):
            if secret and secret in logs:
                fail("a generated credential appeared in the Nexus journal")

        cleanup(application_id, user_id, key_ids, job_id)
        result_path = Path("/var/lib/nexus/screenshot-jobs") / f"{job_id}.png"
        if result_path.exists():
            fail("acceptance PNG remained after cleanup")

        marker = (
            f"RELEASE={RELEASE}\n"
            f"NEXUS_PID={run(['systemctl', 'show', 'nexus.service', '-p', 'MainPID', '--value']).stdout.decode().strip()}\n"
            f"PASSED_AT_UTC={datetime.now(timezone.utc).isoformat()}\n"
            "UUID_QUOTA_DELTA=1\n"
            "SCREENSHOT_QUOTA_DELTA=1\n"
            "USAGE_UUID_STATUS=200\n"
            "USAGE_SCREENSHOT_STATUS=201\n"
            f"PNG_BYTES={len(png)}\n"
        )
        descriptor = os.open(PASS_MARKER, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(marker)
        os.chown(PASS_MARKER, 0, 0)

        print(f"PASS: {RELEASE} UUID and asynchronous screenshot completed through TLS loopback; ")
        print(f"      V9 ownership, PNG, quota=2, Usage 200/201, fixed Chromium, AppArmor, credential logs, and exact cleanup passed; PNG bytes={len(png)}.")
        print("Next: run 04-promote-release.sh.")
    except Exception:
        observer_stop.set()
        if observer.is_alive():
            observer.join(timeout=2)
        # Preserve failed acceptance facts; do not race a queued Worker or Usage
        # writer by deleting their FK parents. The pre-promotion rollback restores
        # the V8 DB and quarantines the result directory after stopping the JVM.
        print("WARNING: failed acceptance data was preserved for diagnosis; no pass marker was written.", file=sys.stderr)
        raise
    finally:
        password = ""
        bearer = ""
        jwt = ""
        key_a = ""
        key_b = ""


if __name__ == "__main__":
    try:
        import fcntl
        with open('/opt/nexus/state/deployment.lock', 'a') as deployment_lock:
            fcntl.flock(deployment_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            main()
    except Exception as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        raise SystemExit(1)
