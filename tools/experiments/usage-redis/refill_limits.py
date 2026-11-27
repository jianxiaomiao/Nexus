#!/usr/bin/env python3
"""Replay the same arrival trace with different per-instance Usage refill limits."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
import os
from threading import Barrier
import time

from check import account, api, compose, counter, open_backend, require, run_lab
from coalescing import clear_cache, connection, metric, request, summarize, targets
from mixed import AGGREGATES, Target, latencies, sql_counts, sql_delta

LIMIT_SETTING = "NEXUS_USAGE_CACHE_MAX_CONCURRENT_LOADS"
LIMITS = (1, 2, 4)
SUMMARY_RATE = 96
QUOTA_RATE = 20
SECONDS = 10
ROUNDS = 3
SUMMARY_CLIENTS = 64
METRICS = ("loads", "load.rejections", "wait.timeouts", "joined")
SUCCESS = "200:SUCCESS"
BUSY = "503:USAGE_SUMMARY_UNAVAILABLE"


def fixed_window(backend, summaries, quota, permits, seconds=SECONDS, *, summary_clients=SUMMARY_CLIENTS):
    # Workers use distinct keep-alive connections and predetermined slots.
    summary_workers = summary_clients if summaries else 0
    quota_workers = 8
    started = None

    def start_window():
        nonlocal started
        started = time.perf_counter()

    ready = Barrier(summary_workers + quota_workers + 1, action=start_window, timeout=15)

    def send(worker, workers, rate, fixtures):
        results, delays = [], []
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            for slot in range(worker, seconds * rate, workers):
                due = started + slot / rate
                time.sleep(max(0, due - time.perf_counter()))
                delays.append((time.perf_counter() - due) * 1000)
                results.append(request(conn, *fixtures[slot % len(fixtures)]))
        return results, delays

    def sample_pool():
        pending, active = [], []
        with closing(connection(backend)) as conn:
            conn.connect()
            ready.wait()
            while time.perf_counter() < started + seconds:
                pending.append(metric(conn, "hikaricp.connections.pending")["VALUE"])
                active.append(metric(conn, "nexus.usage.cache.load.active")["VALUE"])
                time.sleep(0.05)
        require(max(active) <= permits, "Observed more active aggregates than permits")
        return {"samples": len(pending), "peak_pending": max(pending),
                "pending_samples": sum(value > 0 for value in pending), "peak_active_refills": max(active)}

    def collect(jobs):
        results = [result.result() for result in jobs]
        samples = [sample for responses, _ in results for sample in responses]
        dispatch = [delay for _, delays in results for delay in delays]
        return {**summarize(samples), "dispatch_p95_ms": latencies(dispatch)["p95_ms"],
                "dispatch_max_ms": round(max(dispatch), 3)}

    backend.close()
    acquire_before = metric(backend, "hikaricp.connections.acquire")
    counts_before = {name: counter(backend, name=f"nexus.usage.cache.{name}") for name in METRICS}
    hits_before = counter(backend, "hit")
    sql_before = sql_counts()
    with ThreadPoolExecutor(max_workers=summary_workers + quota_workers + 1) as pool:
        summary_jobs = [pool.submit(send, n, summary_workers, SUMMARY_RATE, summaries)
                        for n in range(summary_workers)]
        quota_jobs = [pool.submit(send, n, quota_workers, QUOTA_RATE, [quota]) for n in range(quota_workers)]
        pool_job = pool.submit(sample_pool)
        summary = collect(summary_jobs) if summary_jobs else None
        quota_result = collect(quota_jobs)
        hikari = pool_job.result()
    acquire_after = metric(backend, "hikaricp.connections.acquire")
    acquired = acquire_after["COUNT"] - acquire_before["COUNT"]
    hikari["mean_acquire_ms"] = round((acquire_after["TOTAL_TIME"] - acquire_before["TOTAL_TIME"]) * 1000 / acquired, 3)
    metrics = {name: counter(backend, name=f"nexus.usage.cache.{name}") - before
               for name, before in counts_before.items()}
    metrics["hits"] = counter(backend, "hit") - hits_before
    statements = sql_delta(sql_before, sql_counts())
    require(all(statements[kind]["calls"] == metrics["loads"] for kind in AGGREGATES),
            f"Aggregate SQL did not match admitted loads: {metrics}, {statements}")
    return {"seconds": seconds, "summary": summary, "quota": quota_result, "pool": hikari,
            "cache": metrics, "usage_sql": statements}


def prepare_fixture(baseline):
    items = targets(baseline)
    lookups = []
    for target in items:
        path = target.path(timeRange="LAST_30_DAYS")
        lookups.append((target, path, target.fetch(baseline, path)))
    owner = account(baseline)
    app = int(api(baseline, "POST", "/api/application/create", {"name": "Quota probe"}, owner)["id"])
    quota_path = f"/api/application/{app}/quota"
    quota = (Target(owner, app, 0), quota_path, api(baseline, "GET", quota_path, auth=owner))
    return lookups, quota


def outage_recovery(cached, lookups, quota, permits, *, summary_clients=SUMMARY_CLIENTS):
    compose("stop", "redis")
    outage = fixed_window(cached, lookups, quota, permits, summary_clients=summary_clients)
    compose("up", "-d", "--wait", "redis")
    # Redis has no persistence in this lab. The trace sees a cold cache after restart.
    recovery = fixed_window(cached, lookups, quota, permits, summary_clients=summary_clients)
    return {"outage": outage, "recovery": recovery}


def dispatch_valid(window):
    return all(trace["dispatch_p95_ms"] < 25 and trace["dispatch_max_ms"] < 100
               for trace in (window["quota"], window["summary"]) if trace is not None)


def experiment(baseline, initial_cached, limits, rounds):
    lookups, quota = prepare_fixture(baseline)
    default_limit = int(metric(initial_cached, "nexus.usage.cache.load.limit")["VALUE"])
    initial_cached.close()

    recorded = []
    for permits in limits:
        os.environ[LIMIT_SETTING] = str(permits)
        with closing(open_backend("cached")) as cached:
            require(metric(cached, "nexus.usage.cache.load.limit")["VALUE"] == permits,
                    f"Did not apply limit {permits} to the cached service")
            clear_cache()
            fixed_window(cached, lookups, quota, permits, seconds=4)
            control = fixed_window(cached, [], quota, permits)
            record = {"limit": permits, "quota_only": control, "rounds": []}
            for index in range(rounds):
                pair = outage_recovery(cached, lookups, quota, permits)
                record["rounds"].append(pair)
                outage, recovery = pair["outage"], pair["recovery"]
                print(json.dumps({"limit": permits, "round": index + 1,
                                  "outage_503": outage["summary"]["outcomes"].get(BUSY, 0),
                                  "recovery_503": recovery["summary"]["outcomes"].get(BUSY, 0),
                                  "outage_quota_p99_ms": outage["quota"]["p99_ms"],
                                  "recovery_quota_p99_ms": recovery["quota"]["p99_ms"]}), flush=True)
            recorded.append(record)
    return {"rates": {"summary": SUMMARY_RATE, "quota": QUOTA_RATE},
            "observed_default_limit": default_limit, "results": recorded}


def verify(report):
    for record in report["results"]:
        windows = [record["quota_only"]]
        for round_result in record["rounds"]:
            require(round_result["outage"]["cache"]["hits"] == 0, "Outage window unexpectedly had Redis hits")
            require(round_result["recovery"]["cache"]["hits"] > 0, "Recovery window lacked Redis hits")
            windows.extend(round_result.values())
        for window in windows:
            result = window["quota"]
            require(result["outcomes"] == {SUCCESS: SECONDS * QUOTA_RATE}, f"Quota trace did not complete: {result}")
            if window["summary"] is not None:
                result = window["summary"]
                require(result["requests"] == SECONDS * SUMMARY_RATE and set(result["outcomes"]) <= {SUCCESS, BUSY},
                        f"Invalid summary trace: {result}")
            require(dispatch_valid(window), f"Client trace late: {window}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limits", nargs="+", type=int, choices=LIMITS, default=LIMITS)
    parser.add_argument("--rounds", type=int, default=ROUNDS)
    args = parser.parse_args()
    require(args.rounds > 0, "At least one round is required")
    os.environ.pop(LIMIT_SETTING, None)
    report = run_lab(lambda baseline, cached: experiment(baseline, cached, args.limits, args.rounds))
    verify(report)
