#!/usr/bin/env python3
"""Upgrade a populated Usage lab to V17 and replay the fixed Redis outage trace."""

import argparse
from contextlib import closing
from hashlib import sha256
import json
import os
from pathlib import Path
import time
from zipfile import ZipFile

from check import JAR, ROOT, call, compose, open_backend, require, run_lab
from coalescing import ROWS_PER_KEY, clear_cache, metric
from mixed import mysql
from refill_limits import (BUSY, LIMIT_SETTING, QUOTA_RATE, ROUNDS, SUMMARY_CLIENTS, SUMMARY_RATE, dispatch_valid,
                           fixed_window, outage_recovery, prepare_fixture, verify)

INDEX = "idx_usage_summary_cover"
MIGRATION = "V17__add_usage_summary_covering_index.sql"
COLUMNS = ("api_key_id", "application_id", "occurred_at", "api_code")
TARGET = "USAGE_LAB_FLYWAY_TARGET"
PERMITS = 4


def sql(query):
    return mysql(query, root=True, capture=True).strip()


def history():
    return [row.split("\t") for row in sql("""
        SELECT installed_rank, version, script, checksum, execution_time, success
        FROM flyway_schema_history ORDER BY installed_rank
    """).splitlines()]


def indexes():
    return [row.split("\t") for row in sql("""
        SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, IS_VISIBLE
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'usage_events'
        ORDER BY INDEX_NAME, SEQ_IN_INDEX
    """).splitlines()]


def usage_snapshot():
    rows = sql("""
        SELECT SHA2(CONCAT_WS(CHAR(31), id, api_key_id, application_id, api_code,
                             http_status_code, duration_ms, occurred_at, event_id), 256)
        FROM usage_events ORDER BY id
    """)
    return {"rows": len(rows.splitlines()), "sha256": sha256(rows.encode()).hexdigest()}


def ddl_timing():
    rows = sql(f"""
        SELECT COUNT_STAR, SUM_ERRORS, ROUND(SUM_TIMER_WAIT / 1e9, 3)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME = DATABASE()
          AND LOWER(REPLACE(DIGEST_TEXT, CHAR(96), '')) LIKE 'alter table usage_events % {INDEX} %'
    """).splitlines()
    require(len(rows) == 1, f"Unexpected DDL digest rows: {rows}")
    executions, errors, elapsed = rows[0].split("\t")
    require((executions, errors) == ("1", "0"), f"Expected one successful DDL statement: {rows}")
    return float(elapsed)


def measure(cached, lookups, quota, label, rounds, summary_clients):
    require(metric(cached, "nexus.usage.cache.load.limit")["VALUE"] == PERMITS,
            "Cached lab must use the code default of four permits")
    clear_cache()
    fixed_window(cached, lookups, quota, PERMITS, seconds=4, summary_clients=summary_clients)
    control = fixed_window(cached, [], quota, PERMITS)
    late_controls = []
    if not dispatch_valid(control):
        late_controls.append(control)
        control = fixed_window(cached, [], quota, PERMITS)
    record = {"limit": PERMITS, "quota_only": control, "rounds": [],
              "late_pairs": [], "late_controls": late_controls}
    for attempt in range(1, rounds + 4):
        pair = outage_recovery(cached, lookups, quota, PERMITS, summary_clients=summary_clients)
        valid = all(dispatch_valid(window) for window in pair.values())
        record["rounds" if valid else "late_pairs"].append(pair)
        print(json.dumps({"schema": label, "attempt": attempt, "valid_dispatch": valid,
                          "max_dispatch_ms": max(trace["dispatch_max_ms"] for w in pair.values()
                                                 for trace in (w["summary"], w["quota"])),
                          **{stage + "_503": w["summary"]["outcomes"].get(BUSY, 0)
                             for stage, w in pair.items()}}), flush=True)
        if len(record["rounds"]) == rounds:
            break
    return record


def experiment(baseline, initial_cached, rounds, summary_clients, jar_sha256):
    require(sql("SELECT DATABASE()") == "nexus_usage_cache_lab", "Unexpected database")
    status, registry = call(baseline, "GET", "/actuator/metrics")
    require(status == 200 and "nexus.usage.cache.load.limit" not in registry["names"],
            "Default cache-disabled service unexpectedly exposes a refill limit")
    initial_history = history()
    require(initial_history[-1][1] == "16", "Expected a V16 schema before the upgrade")
    initial_indexes = indexes()
    require(all(row[0] != INDEX for row in initial_indexes), "Candidate index already exists")
    lookups, quota = prepare_fixture(baseline)
    before = usage_snapshot()
    require(before["rows"] == 8 * ROWS_PER_KEY, f"Unexpected Usage fixture: {before}")
    # Both trials start a fresh cached process which uses the same packaged code.
    initial_cached.close()
    compose("stop", "cached")
    with closing(open_backend("cached")) as cached:
        for target, path, expected in lookups:
            require(target.fetch(cached, path) == expected, "Baseline Usage summary differed from the fixture")
        old = measure(cached, lookups, quota, "V16", rounds, summary_clients)
    require(usage_snapshot() == before, "The baseline HTTP trace changed historical Usage data")

    compose("stop", "cached")
    os.environ[TARGET] = "17"
    start = time.perf_counter()
    with closing(open_backend("cached")) as cached:
        ready_seconds = round(time.perf_counter() - start, 3)
        upgraded_history = history()
        require(upgraded_history[:-1] == initial_history, "Upgrade changed previous Flyway history")
        version = upgraded_history[-1]
        require(version[1] == "17" and version[2] == MIGRATION and version[-1] == "1",
                f"Unexpected Flyway history: {version}")
        upgraded_indexes = indexes()
        added = [row for row in upgraded_indexes if row[0] == INDEX]
        require(added == [[INDEX, str(pos), column, "1", "YES"]
                          for pos, column in enumerate(COLUMNS, 1)], f"Unexpected index: {added}")
        require([row for row in upgraded_indexes if row[0] != INDEX] == initial_indexes,
                "Upgrade changed an existing Usage index")
        require(usage_snapshot() == before, "Upgrade changed historical Usage data")
        for target, path, expected in lookups:
            require(target.fetch(cached, path) == expected, "Existing Usage summary changed after migration")
        ddl_ms = ddl_timing()
        print(json.dumps({"migration": version[2], "rows": before["rows"],
                          "flyway_ms": int(version[4]), "mysql_ddl_ms": ddl_ms,
                          "boot_ready_seconds": ready_seconds}), flush=True)
        new = measure(cached, lookups, quota, "V17", rounds, summary_clients)

    compose("stop", "cached")
    with closing(open_backend("cached")):
        require(history() == upgraded_history and ddl_timing() == ddl_ms,
                "Flyway migration ran again on subsequent startup")
    require(usage_snapshot() == before, "Post-upgrade trace changed historical Usage data")
    return {"mysql_version": sql("SELECT VERSION()"), "jar_sha256": jar_sha256,
            "rates": {"summary": SUMMARY_RATE, "quota": QUOTA_RATE},
            "summary_clients": summary_clients,
            "fixture": {"keys": len(lookups), "rows_per_key": ROWS_PER_KEY, **before},
            "upgrade": {"history_before": initial_history, "history_after": upgraded_history,
                        "indexes_before": initial_indexes, "indexes_after": upgraded_indexes,
                        "flyway_ms": int(version[4]), "mysql_ddl_ms": ddl_ms, "boot_ready_seconds": ready_seconds,
                        "unchanged_events_and_summaries": True, "restart_skipped_applied_migration": True,
                        "default_cache_disabled": True},
            "results": {"V16": old, "V17": new}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--rounds", type=int, default=ROUNDS)
    parser.add_argument("--summary-clients", type=int, default=SUMMARY_CLIENTS,
                        help="Concurrent HTTP connections available to the fixed-rate summary trace")
    args = parser.parse_args()
    require(args.rounds > 0, "At least one round is required")
    require(args.summary_clients > 0, "At least one summary client is required")
    jar = args.jar.resolve()
    source = ROOT / "nexus-server/src/main/resources/db/migration" / MIGRATION
    with ZipFile(jar) as archive:
        require(archive.read(f"BOOT-INF/classes/db/migration/{MIGRATION}") == source.read_bytes(),
                "Packaged migration differs from the workspace")
    jar_hash = sha256()
    with jar.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            jar_hash.update(block)
    os.environ[TARGET] = "16"
    os.environ.pop(LIMIT_SETTING, None)
    report = run_lab(lambda baseline, cached: experiment(baseline, cached, args.rounds, args.summary_clients,
                                                        jar_hash.hexdigest()), jar)
    verify({"results": list(report["results"].values())})
    require(all(len(result["rounds"]) == args.rounds for result in report["results"].values()),
            "Could not collect enough valid outage/recovery pairs")
