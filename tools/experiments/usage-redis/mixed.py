#!/usr/bin/env python3
"""Test whether the Usage cache still earns hits with multiple owners and API Keys."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from http.client import HTTPConnection
import json
import math
from statistics import median
from threading import Barrier
import time
from urllib.parse import urlencode

from check import account, api, compose, counter, require, run_lab, seed

RATE = 8
SECONDS = 20
CLIENTS = 8
TTL_SECONDS = 5
BEIJING = timezone(timedelta(hours=8))
OUTCOMES = ("hit", "miss", "error")
AGGREGATES = ("count", "daily", "by_api")
STATEMENTS = (*AGGREGATES, "page")


@dataclass(frozen=True)
class Target:
    owner: str
    application: int
    key: int

    def path(self, *, events=False, **params):
        query = urlencode(dict(applicationId=self.application, apiKeyId=self.key, **params))
        return ("/api/usage/events?" if events else "/api/usage?") + query

    def fetch(self, connection, path):
        return api(connection, "GET", path, auth=self.owner)


def create_targets(connection):
    targets = []
    for _ in range(3):
        owner = account(connection)
        for application_index in range(2):
            application = int(api(connection, "POST", "/api/application/create",
                                  {"name": f"Usage lab {application_index}"}, owner)["id"])
            for index in range(8):
                key = api(connection, "POST", "/api/apiKey/create",
                          {"applicationId": str(application), "name": f"Key {index}"}, owner)
                targets.append(Target(owner, application, int(key["id"])))
    return targets


def mysql(sql, *, root=False, capture=False):
    login = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
             if root else 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER"')
    return compose("exec", "-T", "mysql", "sh", "-c", login + ' -BN "$MYSQL_DATABASE"',
                   text=sql, capture=capture)


def add_history(targets, count, first_day, days, anchor):
    # Day 0 starts at Beijing midnight; SQL timestamps are UTC.
    anchor_utc = anchor.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    digits = " UNION ALL ".join(f"SELECT {digit}" for digit in range(10))
    keys = ",".join(str(target.key) for target in targets)
    mysql(f"""
        INSERT INTO usage_events (api_key_id, application_id, api_code, http_status_code,
                                  duration_ms, occurred_at, event_id)
        WITH digits(d) AS ({digits}),
             numbers AS (
                 SELECT a.d + 10*b.d + 100*c.d + 1000*e.d AS n
                 FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits e
             )
        SELECT k.id, k.application_id, ELT(1 + MOD(n, 4), 'uuid.generate', 'utils.hash',
                   'shortlink.list', 'web.extract'), 200, 5,
               TIMESTAMP('{anchor_utc}') - INTERVAL ({first_day} + MOD(n, {days})) DAY
                   + INTERVAL MOD(n * 137, 86400) SECOND,
               UUID()
        FROM api_keys k CROSS JOIN numbers
        WHERE k.id IN ({keys}) AND n < {count};
    """)


def sql_counts():
    rows = mysql("""
        SELECT CASE WHEN DIGEST_TEXT LIKE '%CONVERT_TZ%' THEN 'daily'
                    WHEN DIGEST_TEXT LIKE '%GROUP BY%' THEN 'by_api'
                    WHEN DIGEST_TEXT LIKE 'SELECT COUNT%' THEN 'count'
                    ELSE 'page' END AS statement_kind,
               SUM(COUNT_STAR), SUM(SUM_ROWS_EXAMINED), SUM(SUM_TIMER_WAIT)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME = DATABASE() AND DIGEST_TEXT LIKE 'SELECT %'
          AND LOWER(REPLACE(DIGEST_TEXT, CHAR(96), '')) LIKE '%from usage_events%'
        GROUP BY statement_kind;
    """, root=True, capture=True)
    totals = dict.fromkeys(STATEMENTS, (0, 0, 0))
    for line in rows.splitlines():
        name, count, examined, elapsed = line.split()
        totals[name] = (int(count), int(examined), int(elapsed))
    return totals


def sql_delta(before, after):
    result = {}
    for name in STATEMENTS:
        delta = [end - start for start, end in zip(before[name], after[name])]
        require(min(delta) >= 0, "MySQL statement counters were reset")
        calls, examined, elapsed = delta
        result[name] = {"calls": calls, "rows_examined": examined, "sql_ms": round(elapsed / 1e9, 3)}
    return result


def latencies(samples):
    samples.sort()
    return {f"p{p}_ms": round(samples[math.ceil(p * len(samples) / 100) - 1], 3)
            for p in (50, 95, 99)}


def measure_schedule(backend, schedule):
    start = None

    def start_window():
        nonlocal start
        start = time.perf_counter()

    ready = Barrier(CLIENTS, action=start_window, timeout=10)

    def worker(client):
        samples = []
        max_lag = 0
        with closing(HTTPConnection(backend.host, backend.port, timeout=5)) as connection:
            connection.connect()
            ready.wait()
            for index in range(client, len(schedule), CLIENTS):
                due = start + index / RATE
                time.sleep(max(0, due - time.perf_counter()))
                before = time.perf_counter()
                max_lag = max(max_lag, before - due)
                target, path, expected = schedule[index]
                actual = target.fetch(connection, path)
                samples.append((time.perf_counter() - before) * 1000)
                require(actual == expected, "Mixed schedule: unexpected Usage response")
        return samples, max_lag

    with ThreadPoolExecutor(max_workers=CLIENTS) as pool:
        results = list(pool.map(worker, range(CLIENTS)))
    samples = [latency for sample, _ in results for latency in sample]
    max_lag_ms = round(max(lag for _, lag in results) * 1000, 3)
    require(len(samples) == len(schedule) and max_lag_ms < 1000 / RATE,
            f"Fixed-rate schedule missed a slot (max dispatch lag {max_lag_ms} ms)")
    return {"requests": len(samples), "dispatch_max_lag_ms": max_lag_ms, **latencies(samples)}


def schedules(fixtures):
    hot, sparse = [], []
    cold_index = 0
    for index in range(RATE * SECONDS):
        hot_index = 8 * (index % 2)
        if index % 5 == 4:
            hot_index = (cold_index + 16) % len(fixtures)
            cold_index += 1
        hot.append(fixtures[hot_index])
        sparse.append(fixtures[index % len(fixtures)])
    return {"hot": hot, "sparse": sparse}


def reads(backend):
    return {outcome: counter(backend, outcome) for outcome in OUTCOMES}


def compare(baseline, cached, schedule, workload, count):
    rounds = {"baseline": [], "cached": []}
    for index in range(3):
        order = ("baseline", "cached") if (index + (workload == "sparse")) % 2 == 0 else ("cached", "baseline")
        for name in order:
            backend = baseline if name == "baseline" else cached
            if name == "cached":
                # Each stage starts after values from earlier stages have expired.
                time.sleep(TTL_SECONDS + 0.1)
                cached.close()
                started = reads(cached)
                loads_before = counter(cached, name="nexus.usage.cache.loads")
            before = sql_counts()
            metrics = measure_schedule(backend, schedule)
            metrics["usage_sql"] = sql_delta(before, sql_counts())
            backfills = metrics["requests"]
            if name == "cached":
                ended = reads(cached)
                metrics["cache_reads"] = {key: ended[key] - started[key] for key in OUTCOMES}
                require(sum(metrics["cache_reads"].values()) == metrics["requests"],
                        "Cache counters did not match the schedule")
                backfills = counter(cached, name="nexus.usage.cache.loads") - loads_before
                require(0 <= backfills <= metrics["cache_reads"]["miss"] + metrics["cache_reads"]["error"],
                        "Aggregate loads outnumber cache misses and read errors")
            require(all(metrics["usage_sql"][kind]["calls"] == backfills for kind in AGGREGATES)
                    and metrics["usage_sql"]["page"]["calls"] == 0,
                    f"MySQL counters did not match expected aggregate calls: {metrics['usage_sql']}")
            rounds[name].append(metrics)
            print(json.dumps({"events_per_key": count, "workload": workload, "round": index + 1,
                              "group": name, **metrics}), flush=True)
    return {"events_per_key": count, "workload": workload, "rounds": rounds,
            "median_p95_ms": {name: median(r["p95_ms"] for r in metrics) for name, metrics in rounds.items()}}


def profile(baseline, target, path):
    baseline.close()
    expected = target.fetch(baseline, path)
    before = sql_counts()
    elapsed = []
    for _ in range(20):
        start = time.perf_counter()
        actual = target.fetch(baseline, path)
        elapsed.append((time.perf_counter() - start) * 1000)
        require(actual == expected, "SQL diagnostic: inconsistent read-only fixture")
    return {**latencies(elapsed), "requests": len(elapsed), "usage_sql": sql_delta(before, sql_counts())}


def freshness(baseline, cached, target, path, expected):
    cached.close()
    time.sleep(TTL_SECONDS + 0.1)
    filled_at = time.perf_counter()
    require(target.fetch(cached, path) == expected, "Unexpected initial snapshot")
    seed(target.application, target.key, 1)
    committed_by = time.perf_counter()
    baseline.close()
    new = target.fetch(baseline, path)
    require(new["allTimeCount"] == expected["allTimeCount"] + 1
            and new["periodCount"] == expected["periodCount"] + 1, "Live query did not see committed write")
    first_poll_stale = None
    while True:
        observed = target.fetch(cached, path)
        if first_poll_stale is None:
            first_poll_stale = observed == expected
        if observed == new:
            break
        require(observed == expected, "Cache returned a partial or invalid snapshot")
        require(time.perf_counter() - filled_at < TTL_SECONDS + 1, "Cache still stale after expiry")
        time.sleep(0.1)
    visible_at = time.perf_counter()
    require(first_poll_stale and visible_at - filled_at <= TTL_SECONDS + 1,
            "The write probe missed the stale or refreshed snapshot")
    return {"initially_stale": first_poll_stale,
            "visible_ms_since_fill_started": round((visible_at - filled_at) * 1000, 3),
            "visible_ms_since_insert_ack": round((visible_at - committed_by) * 1000, 3)}


def check(baseline, cached):
    today = datetime.now(BEIJING).replace(hour=0, minute=0, second=0, microsecond=0)
    require((datetime.now(BEIJING) - today).total_seconds() < 23 * 3600 + 48 * 60,
            "Run outside the final 12 minutes before the Beijing date boundary")
    targets = create_targets(baseline)
    comparisons, diagnostics = [], []
    for count, added, first_day, days in ((900, 900, 1, 30), (10000, 9100, 31, 60)):
        add_history(targets, added, first_day, days, today)
        baseline.close()
        fixtures = []
        for target in targets:
            path = target.path(timeRange="LAST_7_DAYS")
            expected = target.fetch(baseline, path)
            require(expected["allTimeCount"] == count and expected["periodCount"] == 180
                    and len(expected["apiCounts"]) == 4, "Unexpected synthetic Usage distribution")
            fixtures.append((target, path, expected))
        for workload, schedule in schedules(fixtures).items():
            comparisons.append(compare(baseline, cached, schedule, workload, count))
        target = targets[0]
        custom = target.path(timeRange="CUSTOM",
                             customStartTime=(today - timedelta(days=6)).strftime("%Y-%m-%dT%H:%M:%S"),
                             customEndTime=(today + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S"))
        paths = {"preset_7_days": fixtures[0][1], "preset_30_days": target.path(timeRange="LAST_30_DAYS"),
                 "custom_same_7_days": custom, "events_first_page": target.path(events=True, current=1, size=10),
                 "events_last_page": target.path(events=True, current=count // 10, size=10)}
        diagnostics.append({"events_per_key": count,
                            "direct": {label: profile(baseline, target, path) for label, path in paths.items()}})
    return {"events_per_key_in_7_day_range": 180, "keys": len(targets), "owners": 3,
            "target_requests_per_second": RATE, "window_seconds": SECONDS, "max_clients": CLIENTS,
            "comparisons": comparisons, "diagnostics": diagnostics,
            "freshness": freshness(baseline, cached, *fixtures[0])}


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    run_lab(check)
