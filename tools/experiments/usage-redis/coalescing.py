#!/usr/bin/env python3
"""Measure cold-key coalescing and Redis outage behavior against real SQL counters."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
from socket import timeout as SocketTimeout
from threading import Barrier
import time

from check import JAR, account, api, call, compose, counter, require, run_lab, seed, wait_for
from mixed import Target, latencies, sql_counts, sql_delta

CLIENTS = 32
SECONDS = 8
ROWS_PER_KEY = 10_000


def connection(backend):
    return HTTPConnection(backend.host, backend.port, timeout=15)


def targets(backend):
    owner = account(backend)
    app = int(api(backend, "POST", "/api/application/create", {"name": "Coalescing lab"}, owner)["id"])
    result = []
    for index in range(8):
        key = api(backend, "POST", "/api/apiKey/create", {"applicationId": str(app), "name": f"Lab {index}"}, owner)
        target = Target(owner, app, int(key["id"]))
        seed(app, target.key, ROWS_PER_KEY)
        result.append(target)
    return result


def request(conn, target, path, expected=None):
    start = time.perf_counter_ns()
    try:
        status, body = call(conn, "GET", path, auth=target.owner)
    except SocketTimeout:
        conn.close()
        return (time.perf_counter_ns() - start) / 1e6, "client:timeout"
    if status == 200:
        require(body["code"] == "SUCCESS" and (expected is None or body["data"] == expected),
                "Unexpected response body")
    return (time.perf_counter_ns() - start) / 1e6, f"{status}:{body['code']}"


def summarize(samples):
    codes = dict(Counter(code for _, code in samples))
    ok = codes.get("200:SUCCESS", 0)
    return {"requests": len(samples), "outcomes": codes, "error_rate": (len(samples) - ok) / len(samples),
            **latencies([elapsed for elapsed, _ in samples])}


def redis(*args):
    return compose("exec", "-T", "redis", "redis-cli", "--raw", *args, capture=True).strip()


def cache_keys():
    return redis("--scan", "--pattern", "nexus:acceptance:usage-summary:v1:*").splitlines()


def clear_cache():
    keys = cache_keys()
    if keys:
        redis("DEL", *keys)


def burst(backend, fixture):
    ready = Barrier(CLIENTS, timeout=8)

    def worker(_):
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            return request(conn, *fixture)

    before = sql_counts()
    with ThreadPoolExecutor(max_workers=CLIENTS) as pool:
        samples = list(pool.map(worker, range(CLIENTS)))
    return {**summarize(samples), "usage_sql": sql_delta(before, sql_counts())}


def metric(conn, name):
    status, body = call(conn, "GET", f"/actuator/metrics/{name}")
    require(status == 200, f"Metric unavailable: {name}")
    return {row["statistic"]: row["value"] for row in body["measurements"]}


def redis_snapshot():
    def info(section):
        return dict(line.split(":", 1) for line in redis("INFO", section).splitlines()
                    if line and not line.startswith("#"))

    cpu = info("cpu")
    stats = info("commandstats")
    histogram = json.loads(compose("exec", "-T", "redis", "redis-cli", "--json",
                                   "LATENCY", "HISTOGRAM", "GET", capture=True))
    hist = histogram.get("get", {}) if isinstance(histogram, dict) else {}
    return {"cpu_seconds": sum(float(cpu[f"used_cpu_{kind}"]) for kind in ("sys", "user")),
            "commands": {name: {key: int(value) for key, value in
                               (part.split("=") for part in stats.get(f"cmdstat_{name}", "calls=0,usec=0").split(","))
                               if key in ("calls", "usec")} for name in ("get", "set")},
            "get_histogram_usec": hist.get("histogram_usec", {})}


def redis_delta(before, after, seconds):
    commands = {name: {key: value - before["commands"][name][key] for key, value in values.items()}
                for name, values in after["commands"].items()}
    if commands["get"]["calls"]:
        commands["get"]["mean_usec"] = round(commands["get"]["usec"] / commands["get"]["calls"], 3)
        old_buckets = before["get_histogram_usec"]
        new_buckets = after["get_histogram_usec"]
        for percentile in (95, 99):
            for bucket, cumulative in sorted(new_buckets.items(), key=lambda item: int(item[0])):
                old_count = max((count for boundary, count in old_buckets.items()
                                 if int(boundary) <= int(bucket)), default=0)
                if cumulative - old_count >= commands["get"]["calls"] * percentile / 100:
                    commands["get"][f"p{percentile}_usec_upper"] = int(bucket)
                    break
    return {"cpu_one_core_pct": round(100 * (after["cpu_seconds"] - before["cpu_seconds"]) / seconds, 3),
            "commands": commands}


def load(backend, fixtures, probe_target, redis_online):
    # The unrelated quota request stream uses a fixed schedule in every phase.
    probe_path = f"/api/application/{probe_target.application}/quota"
    started = deadline = None

    def start_window():
        nonlocal started, deadline
        started = time.perf_counter()
        deadline = started + SECONDS

    workers = CLIENTS if fixtures else 0
    ready = Barrier(workers + 2, action=start_window, timeout=10)

    def summary_worker(index):
        samples = []
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            batch_index = index
            while time.perf_counter() < deadline:
                samples.append(request(conn, *fixtures[batch_index % len(fixtures)]))
                batch_index += 1
        return samples

    def quota_worker():
        samples, delays = [], []
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            offered = SECONDS * 20
            for index in range(offered):
                due = started + index * 0.05
                time.sleep(max(0, due - time.perf_counter()))
                if time.perf_counter() >= deadline:
                    break
                delays.append((time.perf_counter() - due) * 1000)
                samples.append(request(conn, probe_target, probe_path))
        return {**summarize(samples), "not_sent": offered - len(samples),
                **{"dispatch_" + key: value for key, value in latencies(delays).items()}}

    def pool_worker():
        samples = []
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            while time.perf_counter() < deadline:
                samples.append(metric(conn, "hikaricp.connections.pending")["VALUE"])
                time.sleep(0.05)
        return {"samples": len(samples), "peak_pending": max(samples),
                "nonzero_samples": sum(value > 0 for value in samples)}

    backend.close()
    acquire_before = metric(backend, "hikaricp.connections.acquire")
    sql_before = sql_counts()
    redis_before = redis_snapshot() if redis_online else None
    with ThreadPoolExecutor(max_workers=workers + 2) as pool:
        futures = [pool.submit(summary_worker, index) for index in range(workers)]
        quota_future = pool.submit(quota_worker)
        pool_future = pool.submit(pool_worker)
        summaries = [sample for future in futures for sample in future.result()]
        quota, pool_wait = quota_future.result(), pool_future.result()
    elapsed = time.perf_counter() - started
    redis_stats = redis_delta(redis_before, redis_snapshot(), elapsed) if redis_online else None
    acquire_after = metric(backend, "hikaricp.connections.acquire")
    acquired = acquire_after["COUNT"] - acquire_before["COUNT"]
    pool_wait["acquires"] = int(acquired)
    pool_wait["mean_acquire_ms"] = round(1000 * (acquire_after["TOTAL_TIME"] - acquire_before["TOTAL_TIME"]) / acquired, 3)
    return {"summary": {**summarize(summaries), "completed_per_second": round(len(summaries) / elapsed, 2)}
            if summaries else None, "quota": quota, "pool": pool_wait,
            "usage_sql": sql_delta(sql_before, sql_counts()), "redis": redis_stats}


def experiment(baseline, cached):
    test_targets = targets(baseline)
    control_owner = account(baseline)
    control_app = int(api(baseline, "POST", "/api/application/create", {"name": "Quota control"}, control_owner)["id"])
    control = Target(control_owner, control_app, 0)
    presets = ("TODAY", "YESTERDAY", "LAST_7_DAYS", "LAST_30_DAYS", "THIS_MONTH", "LAST_MONTH")
    fixtures = [(target, target.path(timeRange=preset), target.fetch(baseline, target.path(timeRange=preset)))
                for target in test_targets for preset in presets]
    main = fixtures[3]
    shared = [row for row in fixtures if "LAST_30_DAYS" in row[1]]
    cold, expired = [], []
    for _ in range(3):
        clear_cache()
        cold.append(burst(cached, main))
        current = cache_keys()
        require(len(current) == 1, "Cold burst did not fill exactly one Redis key")
        require(redis("PEXPIRE", current[0], "1") == "1", "Could not expire the synthetic key")
        wait_for(lambda: redis("EXISTS", current[0]) == "0", "Redis key did not expire")
        expired.append(burst(cached, main))

    results = {}
    quota_only = load(cached, [], control, True)
    results["quota_only"] = quota_only
    for name, traffic in (("hot", [main]), ("multi_online", shared)):
        results[name] = load(cached, traffic, control, True)
    compose("stop", "redis")
    results["multi_outage"] = load(cached, shared, control, False)
    compose("up", "-d", "--wait", "redis")
    cached.close()
    hits_before = counter(cached, "hit")

    def recovered():
        request(cached, *main)
        return counter(cached, "hit") > hits_before

    wait_for(recovered, "Redis recovery did not restore cache hits")
    results["multi_recovered"] = load(cached, shared, control, True)
    clear_cache()
    low_hit_before = redis_snapshot()
    before = sql_counts()
    low_hit = [request(cached, *fixture) for fixture in fixtures]
    low_hit_stats = {**summarize(low_hit), "usage_sql": sql_delta(before, sql_counts()),
                     "redis": redis_delta(low_hit_before, redis_snapshot(), 1)["commands"]}
    # Probe one of the widest response windows by refilling it immediately before STRLEN.
    request(cached, *main)
    raw_sizes = [(int(redis("STRLEN", key)), int(redis("MEMORY", "USAGE", key) or 0)) for key in cache_keys()]
    sizes = [(value, memory) for value, memory in raw_sizes if value and memory]
    require(bool(sizes), "No Redis value sizes were measured")
    return {"clients": CLIENTS, "seconds": SECONDS, "rows_per_key": ROWS_PER_KEY,
            "cold": cold, "expired": expired, "loads": results, "low_hit": low_hit_stats,
            "redis_value_sizes": {"measured": len(sizes), "max_payload_bytes": max(x[0] for x in sizes),
                                  "max_memory_bytes": max(x[1] for x in sizes)}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expect-single-load", action="store_true")
    parser.add_argument("--jar", type=Path, default=JAR)
    args = parser.parse_args()
    report = run_lab(experiment, args.jar.resolve())
    if args.expect_single_load:
        for batch in report["cold"] + report["expired"]:
            require(batch["outcomes"] == {"200:SUCCESS": CLIENTS}
                    and all(batch["usage_sql"][kind]["calls"] == 1 for kind in ("count", "daily", "by_api")),
                    f"Expected one aggregate load and {CLIENTS} successful requests: {batch}")
