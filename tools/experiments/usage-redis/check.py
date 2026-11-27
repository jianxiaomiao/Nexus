#!/usr/bin/env python3
"""Compare the in-service Usage cache and check other endpoints for stale reads."""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.client import HTTPConnection
import json
import math
import os
from pathlib import Path
import secrets
import statistics
import subprocess
from threading import Barrier
import time
from urllib.parse import urlencode
from uuid import uuid4
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PROJECT = "nexus-usage-cache-" + secrets.token_hex(6)
JAR = ROOT / "nexus-server/target/nexus-server-0.0.1-SNAPSHOT.jar"


def run(args, *, text=None, capture=False, strict=True):
    return subprocess.run(args, text=True, input=text, stdout=subprocess.PIPE if capture else None,
                          check=strict).stdout


def compose(*args, **kwargs):
    return run(["docker", "compose", "-p", PROJECT, "-f", str(HERE / "compose.yaml"), *args], **kwargs)


def wait_for(action, message, seconds=12):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if action():
            return
        time.sleep(0.15)
    raise RuntimeError(message)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def call(connection, method, path, body=None, auth=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if auth:
        headers["Authorization"] = auth
    connection.request(method, path, None if body is None else json.dumps(body), headers)
    response = connection.getresponse()
    return response.status, json.loads(response.read())


def api(connection, method, path, body=None, auth=None):
    status, response = call(connection, method, path, body, auth)
    require(status < 300 and response.get("code") == "SUCCESS",
            f"{method} {path} returned HTTP {status}, code {response.get('code')}")
    return response.get("data")


def open_backend(service):
    compose("up", "-d", "--no-deps", service)
    address = compose("port", service, "8080", capture=True).strip()
    require(address.startswith("127.0.0.1:"), f"Unexpected binding for {service}")
    connection = HTTPConnection("127.0.0.1", int(address.rsplit(":", 1)[-1]), timeout=5)

    def ready():
        try:
            return call(connection, "GET", "/actuator/health")[0] == 200
        except (OSError, ValueError):
            connection.close()
            return False

    try:
        wait_for(ready, f"{service} did not become healthy", seconds=90)
        return connection
    except BaseException:
        connection.close()
        raise


def account(connection):
    email = f"usage-{secrets.token_hex(8)}@example.com"
    password = secrets.token_urlsafe(24)
    api(connection, "POST", "/api/auth/register",
        {"email": email, "password": password, "displayName": "Usage test"})
    login = api(connection, "POST", "/api/auth/login", {"email": email, "password": password})
    return "Bearer " + login["accessToken"]


def seed(application_id, key_id, count):
    rows = ",".join(f"({key_id},{application_id},'uuid.generate',200,5,UTC_TIMESTAMP(3),'{uuid4()}')"
                    for _ in range(count))
    compose("exec", "-T", "mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -BN -u "$MYSQL_USER" "$MYSQL_DATABASE"',
            text="INSERT INTO usage_events (api_key_id,application_id,api_code,http_status_code,duration_ms,"
                 "occurred_at,event_id) VALUES " + rows)


def counter(connection, outcome=None, *, name="nexus.usage.cache.reads"):
    path = f"/actuator/metrics/{name}"
    if outcome is not None:
        path += "?" + urlencode({"tag": f"outcome:{outcome}"})
    status, body = call(connection, "GET", path)
    require(status == 200, f"Usage cache metric {name} {outcome or ''} not visible")
    return int(body["measurements"][0]["value"])


def measure(get, expected, count=100):
    values = []
    for _ in range(count):
        before = time.perf_counter_ns()
        result = get()
        values.append((time.perf_counter_ns() - before) / 1e6)
        require(result == expected, "The response changed within a performance sample")
    values.sort()
    return round(values[math.ceil(0.95 * count) - 1], 3)


def measure_concurrent(backend, path, auth, expected, clients, seconds):
    def start_window():
        nonlocal deadline
        deadline = time.perf_counter() + seconds

    deadline = None
    ready = Barrier(clients, action=start_window, timeout=10)

    def request_loop():
        samples = []
        with closing(HTTPConnection(backend.host, backend.port, timeout=5)) as connection:
            connection.connect()
            ready.wait()
            while time.perf_counter() < deadline:
                before = time.perf_counter_ns()
                result = api(connection, "GET", path, auth=auth)
                samples.append((time.perf_counter_ns() - before) / 1e6)
                require(result == expected, "The response changed within a concurrent sample")
        return samples

    with ThreadPoolExecutor(max_workers=clients) as pool:
        futures = [pool.submit(request_loop) for _ in range(clients)]
        values = sorted(latency for future in futures for latency in future.result())
    elapsed = time.perf_counter() - (deadline - seconds)
    require(bool(values), "No samples were collected")
    return {"requests": len(values), "requests_per_second": round(len(values) / elapsed, 2),
            **{f"p{p}_ms": round(values[math.ceil(p * len(values) / 100) - 1], 3) for p in (50, 95, 99)}}


def compare_concurrent(baseline, cached, path, owner, expected, sample):
    outcomes = ("hit", "miss", "error")
    comparisons = []
    for clients in (1, 8):
        backends = {"baseline": baseline, "cached": cached}
        rounds = {name: [] for name in backends}
        for index in range(3):
            order = ("baseline", "cached") if (index + (clients == 8)) % 2 == 0 else ("cached", "baseline")
            for name in order:
                backend = backends[name]
                measure_concurrent(backend, path, owner, expected, clients, seconds=2)
                start = {outcome: counter(backend, outcome) for outcome in outcomes} if name == "cached" else {}
                metrics = measure_concurrent(backend, path, owner, expected, clients, seconds=11)
                if name == "cached":
                    reads = {outcome: counter(backend, outcome) - start[outcome] for outcome in outcomes}
                    require(sum(reads.values()) == metrics["requests"], "Cache counters did not match requests")
                    metrics["cache_reads"] = reads
                    metrics["hit_rate"] = round(reads["hit"] / metrics["requests"], 4)
                rounds[name].append(metrics)
                print(json.dumps({"events": sample, "clients": clients, "round": index + 1,
                                  "group": name, **metrics}), flush=True)
        fields = ("p50_ms", "p95_ms", "p99_ms", "requests_per_second")
        medians = {name: {field: round(statistics.median(row[field] for row in rows), 3) for field in fields}
                   for name, rows in rounds.items()}
        comparisons.append({"events": sample, "clients": clients, "rounds": rounds, "medians": medians})
    return comparisons


def cache_snapshot(label, read, mutate, refreshed):
    key = "nexus:usage-candidate:" + label
    previous = read()
    saved = compose("exec", "-T", "redis", "redis-cli", "--raw", "SETEX", key, "5",
                    json.dumps(previous), capture=True).strip()
    require(saved == "OK", f"Could not seed Redis for {label}")
    mutate()
    fresh = read()
    cached = compose("exec", "-T", "redis", "redis-cli", "--raw", "GET", key, capture=True).strip()
    require(cached and json.loads(cached) == previous, f"{label}: cache expired before freshness check")
    require(refreshed(previous, fresh), f"{label}: live result did not reflect the change")
    return "stale_with_5s_TTL"


def check(baseline, cached, extended):
    owner = account(cached)
    stranger = account(cached)
    application = api(cached, "POST", "/api/application/create", {"name": "Redis acceptance"}, owner)["id"]
    key = api(cached, "POST", "/api/apiKey/create", {"applicationId": application, "name": "Primary"}, owner)
    key_id = int(key["id"])
    application = int(application)
    machine = "ApiKey " + key["apiKey"]
    filter_args = {"applicationId": application, "apiKeyId": key_id}
    usage_path = "/api/usage?" + urlencode(dict(filter_args, timeRange="LAST_7_DAYS"))

    def usage(connection):
        return api(connection, "GET", usage_path, auth=owner)

    comparisons = []
    count = 0
    samples = (300, 900, 3000, 10000) if extended else (300, 900)
    for sample in samples:
        seed(application, key_id, sample - count)
        count = sample
        expected = usage(baseline)
        require(expected["allTimeCount"] == sample, "Unexpected seeded Usage count")
        wait_for(lambda: usage(cached) == expected, "Usage cache did not refresh after TTL")
        if extended:
            # Load clients open their own connections. The baseline control connection
            # would otherwise sit idle through the full comparison and may expire.
            baseline.close()
            comparisons.extend(compare_concurrent(baseline, cached, usage_path, owner, expected, sample))
            continue
        results = {"baseline": [], "cached": []}
        hits_started = counter(cached, "hit")
        for index in range(3):
            order = ("baseline", "cached") if index % 2 == 0 else ("cached", "baseline")
            for name in order:
                connection = baseline if name == "baseline" else cached
                results[name].append(measure(lambda: usage(connection), expected))
        hits = counter(cached, "hit") - hits_started
        require(hits >= 270, f"Too few warm-cache hits ({hits}/300)")
        comparisons.append({"events": sample, "p95_ms_per_round": results, "redis_hits": hits,
                            "median_p95_ms": {k: statistics.median(v) for k, v in results.items()}})

    reads_before = counter(cached, "hit")
    denied, _ = call(cached, "GET", usage_path, auth=stranger)
    require(denied in (403, 404) and counter(cached, "hit") == reads_before,
            "Non-owner reached the shared Usage cache")

    candidates = {}
    app_path = "/api/application?" + urlencode({"applicationId": application})
    key_path = f"/api/apiKey/{application}?" + urlencode({"apiKeyId": key_id})
    candidates["application_list"] = cache_snapshot(
        "application-list", lambda: api(cached, "GET", app_path, auth=owner),
        lambda: api(cached, "PUT", "/api/application", {"id": application, "name": "Renamed app"}, owner),
        lambda old, new: old["records"][0]["name"] != new["records"][0]["name"] == "Renamed app")
    candidates["api_key_list"] = cache_snapshot(
        "api-key-list", lambda: api(cached, "GET", key_path, auth=owner),
        lambda: api(cached, "PUT", "/api/apiKey", dict(filter_args, name="Renamed key"), owner),
        lambda old, new: old["records"][0]["name"] != new["records"][0]["name"] == "Renamed key")
    events_path = "/api/usage/events?" + urlencode(dict(filter_args, size=1))
    candidates["usage_events"] = cache_snapshot(
        "usage-events", lambda: api(cached, "GET", events_path, auth=owner),
        lambda: seed(application, key_id, 1), lambda old, new: new["total"] == old["total"] + 1)
    candidates["quota"] = cache_snapshot(
        "quota", lambda: api(cached, "GET", f"/api/application/{application}/quota", auth=owner),
        lambda: api(cached, "GET", "/v1/utils/uuid", auth=machine),
        lambda old, new: new["usedCount"] == old["usedCount"] + 1)

    compose("stop", "redis")
    wait_for(lambda: usage(baseline)["allTimeCount"] == count + 2, "Asynchronous Usage event was not recorded")
    errors_before = counter(cached, "error")
    require(usage(cached) == usage(baseline) and counter(cached, "error") > errors_before,
            "Redis outage did not fall back to the live aggregate")
    compose("up", "-d", "--wait", "redis")
    redis_hits = counter(cached, "hit")
    def recovered():
        usage(cached)
        return counter(cached, "hit") > redis_hits
    wait_for(recovered, "Usage cache did not reconnect")
    api(cached, "DELETE", "/api/apiKey", filter_args, owner)
    require(call(cached, "GET", usage_path, auth=owner)[0] == 404, "Deleted Key still revealed cached Usage")

    return {"comparisons": comparisons, "freshness_candidates": candidates,
            "checks": ["identical_json", "ttl_refresh", "non_owner_denied", "redis_outage_fallback",
                       "redis_reconnect", "warm_key_delete_denied"]}


def run_lab(experiment, jar=JAR):
    require(jar.is_file(), "Build nexus-server with Maven before running this experiment")
    with ZipFile(jar) as archive:
        require("BOOT-INF/classes/com/nexus/usage/cache/UsageSummaryRedisCache.class" in archive.namelist(),
                "The packaged JAR predates the Usage Redis implementation. Rebuild nexus-server")
    require(not os.getenv("DOCKER_HOST") and not os.getenv("DOCKER_CONTEXT"),
            "Use the default local Docker context")
    endpoint = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"],
                   capture=True).strip()
    require(endpoint.startswith(("unix://", "npipe://")), "Docker context must be local")
    run(["docker", "info", "--format", "{{.ServerVersion}}"])

    os.environ["USAGE_LAB_JAR"] = str(jar)
    for name in ("DB_PASSWORD", "ROOT_PASSWORD", "JWT_SECRET", "WEBHOOK_KEY"):
        os.environ["USAGE_LAB_" + name] = base64.b64encode(secrets.token_bytes(32)).decode()
    try:
        compose("up", "-d", "--wait", "mysql", "redis")
        with closing(open_backend("baseline")) as baseline, closing(open_backend("cached")) as cached:
            report = experiment(baseline, cached)
        evidence = ROOT / "data/usage-redis-acceptance"
        evidence.mkdir(parents=True, exist_ok=True)
        output = evidence / (PROJECT + ".json")
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(output)
        print(json.dumps(report, indent=2))
        return report
    finally:
        compose("down", "-v", strict=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extended", action="store_true", help="Run sustained load at 1 and 8 clients")
    args = parser.parse_args()
    run_lab(lambda baseline, cached: check(baseline, cached, args.extended))


if __name__ == "__main__":
    main()
