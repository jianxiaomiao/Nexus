#!/usr/bin/env python3
"""Compare cloned Usage tables with and without the candidate covering index."""

import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
from tempfile import TemporaryDirectory
import textwrap
from zipfile import ZipFile

from check import HERE, JAR, PROJECT, ROOT, account, api, compose, require, run, run_lab
from mixed import latencies

TABLES = {"A": "usage_events", "B": "usage_index_cover"}
INDEX = "idx_lab_usage_cover"
COLUMNS = "id, api_key_id, application_id, api_code, http_status_code, duration_ms, occurred_at, event_id"
END = datetime.fromisoformat("2026-11-25T16:00:00")
WINDOWS = (1, 7, 30)
SEC = 8
PHASES = {"reads": (20, 0), "writes": (0, 80), "mixed": (20, 80)}
JRE = "eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13"


def mysql(sql):
    return compose("exec", "-T", "mysql", "sh", "-c",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -BN --raw "$MYSQL_DATABASE"',
                   text=sql, capture=True).strip()


def setup(baseline):
    require(mysql("SELECT DATABASE()") == "nexus_usage_cache_lab", "Unexpected test schema")
    sizes = (("dense", [10000] * 8, 1), ("history", [12000] * 4, 90),
             ("skew", [30000, 3000, 3000], 10), ("small", [1000, 1000], 30))
    owner = account(baseline)
    fixtures = []
    for label, counts, spread in sizes:
        app = int(api(baseline, "POST", "/api/application/create", {"name": label}, owner)["id"])
        for index, count in enumerate(counts):
            key = api(baseline, "POST", "/api/apiKey/create",
                      {"applicationId": str(app), "name": f"Lab {index}"}, owner)
            fixtures.append(dict(shape=label, application_id=app, key_id=int(key["id"]),
                                 count=count, days=spread))
    sql_rows = " UNION ALL ".join(
        f"SELECT {row['application_id']}, {row['key_id']}, {row['count']}, {row['days']}" for row in fixtures)
    digits = " UNION ALL ".join(f"SELECT {n}" for n in range(10))
    mysql(f"""
        INSERT INTO usage_events ({COLUMNS})
        WITH digits(d) AS ({digits}),
             numbers AS (
                 SELECT a.d + 10*b.d + 100*c.d + 1000*e.d + 10000*f.d AS n
                 FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits e CROSS JOIN digits f
             ),
             fixture(app, api_key, events, days) AS ({sql_rows})
        SELECT NULL, api_key, app, ELT(1 + MOD(n, 4), 'uuid.generate', 'utils.hash',
                   'shortlink.list', 'web.extract'), 200, 5,
               TIMESTAMP('{END - timedelta(days=1)}') - INTERVAL MOD(n, days) DAY
                   + INTERVAL MOD(n * 137, 86400) SECOND,
               UUID()
        FROM fixture CROSS JOIN numbers WHERE n < events ORDER BY app, api_key, n;
    """)
    original = mysql("SHOW CREATE TABLE usage_events").split("\t", 1)[1]
    clone = original.replace("CREATE TABLE `usage_events`", "CREATE TABLE `usage_index_cover`", 1)
    clone = re.sub(r"CONSTRAINT `([^`]+)`", r"CONSTRAINT `lab_\1`", clone)
    require(clone.startswith("CREATE TABLE `usage_index_cover`"), "Clone name was not changed")
    mysql(clone)
    mysql(f"ALTER TABLE usage_index_cover ADD INDEX {INDEX} (api_key_id, application_id, occurred_at, api_code);"
          f"INSERT INTO usage_index_cover ({COLUMNS}) SELECT {COLUMNS} FROM usage_events ORDER BY id;"
          "ANALYZE TABLE usage_events, usage_index_cover;")
    require(row_counts() == {"A": sum(row["count"] for row in fixtures),
                             "B": sum(row["count"] for row in fixtures)}, "Initial event tables differ")
    return fixtures


def statements():
    path = ROOT / "nexus-server/src/main/java/com/nexus/usage/mapper/UsageEventMapper.java"
    sqls = re.findall(r'@Select\("""\n(.*?)\n\s*"""\)', path.read_text(), flags=re.S)
    require(len(sqls) == 2, "Expected two mapper aggregation queries")
    return {kind: textwrap.dedent(sql) for kind, sql in zip(("daily", "by_api"), sqls)}


def row_counts():
    return {name: int(mysql(f"SELECT COUNT(*) FROM {table}")) for name, table in TABLES.items()}


def query(sql, table, fixture, days):
    params = {"apiKeyId": fixture["key_id"], "applicationId": fixture["application_id"],
              "startUtc": f"'{END - timedelta(days=days)}'", "endUtc": f"'{END}'"}
    return re.sub(r"#\{(\w+)\}", lambda match: str(params[match[1]]), sql.replace("usage_events", table))


def plan_matrix(fixtures, mapper):
    data = []
    first_keys = {shape: next(row for row in fixtures if row["shape"] == shape)
                  for shape in dict.fromkeys(row["shape"] for row in fixtures)}
    for label, fixture in first_keys.items():
        for days in WINDOWS:
            for kind, sql in mapper.items():
                plans = {}
                rows = []
                for variant, table in TABLES.items():
                    statement = query(sql, table, fixture, days)
                    rows.append(mysql(statement))
                    analyze = mysql("EXPLAIN ANALYZE " + statement)
                    explain = json.loads(mysql("EXPLAIN FORMAT=JSON " + statement))
                    match = next((line.strip() for line in analyze.splitlines()
                                  if f" on {table}" in line and "actual time=" in line), None)
                    require(match is not None, f"Unable to locate table access in {analyze}")
                    scanned = int(re.search(r"actual time=[^)]* rows=(\d+) loops=", match)[1])
                    plans[variant] = {"scanned_rows": scanned, "access": match, "analyze": analyze, "explain": explain}
                require(rows[0] == rows[1], f"Aggregate results differed for {label}, {days}, {kind}")
                data.append({"shape": label, "days": days, "query": kind, "plans": plans})
    return data


def stats(table):
    results = mysql(f"""
        SELECT CASE WHEN query_text LIKE 'insert into %' THEN 'insert'
                    WHEN query_text LIKE '%convert_tz%' THEN 'daily'
                    WHEN query_text LIKE '%group by%' THEN 'by_api'
                    WHEN query_text LIKE 'select count%' THEN 'count'
                    ELSE 'other' END AS label,
               SUM(COUNT_STAR), SUM(SUM_ROWS_EXAMINED), SUM(SUM_TIMER_WAIT)
        FROM (
            SELECT LOWER(REPLACE(DIGEST_TEXT, CHAR(96), '')) AS query_text,
                   COUNT_STAR, SUM_ROWS_EXAMINED, SUM_TIMER_WAIT
            FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME = DATABASE()
        ) AS digests
        WHERE query_text LIKE 'select %from {table} %' OR query_text LIKE 'insert into {table} %'
        GROUP BY label;
        SELECT 'redo', VARIABLE_VALUE, 0, 0 FROM performance_schema.global_status
            WHERE VARIABLE_NAME = 'Innodb_os_log_written';
    """)
    totals = dict.fromkeys(("count", "daily", "by_api", "insert"), (0, 0, 0))
    for line in results.splitlines():
        label, count, rows, duration = line.split("\t")
        totals[label] = (int(count), int(rows), int(duration))
    require("redo" in totals, "Missing MySQL redo counter")
    return totals


def prepare_driver(folder, fixtures, mapper):
    run(["javac", "-d", str(folder), str(HERE / "UsageIndexTraffic.java")])
    with ZipFile(JAR) as jar:
        candidates = [name for name in jar.namelist() if name.startswith("BOOT-INF/lib/mysql-connector-j-")]
        require(len(candidates) == 1, "Use the Connector/J packaged with Nexus")
        (folder / "mysql-connector.jar").write_bytes(jar.read(candidates[0]))
    (folder / "keys.tsv").write_text("".join(f"{row['application_id']}\t{row['key_id']}\n" for row in fixtures))
    for kind, sql in mapper.items():
        (folder / f"{kind}.sql").write_text(re.sub(r"#\{\w+\}", "?", sql))


def summarize(records, expected):
    require(sorted(row[0] for row in records) == list(range(expected)), "Missed or duplicated a scheduled operation")
    delays = [row[1] / 1e6 for row in records]
    return {"requests": expected, **latencies([row[2] / 1e6 for row in records]),
            "dispatch_p95_ms": latencies(delays)["p95_ms"], "dispatch_max_ms": round(max(delays), 3)}


def window(folder, variant, mode, event_batch):
    table = TABLES[variant]
    read_rate, write_rate = PHASES[mode]
    before = stats(table)
    output = run(["docker", "run", "--rm", "--network", PROJECT + "_default",
                  "-v", f"{folder}:/probe:ro", "-e", "USAGE_LAB_DB_PASSWORD", "--entrypoint", "java",
                  JRE, "-cp", "/probe:/probe/mysql-connector.jar", "UsageIndexTraffic", "/probe", table,
                  str(SEC), str(event_batch), str(read_rate), str(write_rate)], capture=True)
    after = stats(table)
    reads, writes = [], []
    for line in output.splitlines():
        kind, *record = line.split("\t")
        {"read": reads, "write": writes}[kind].append(tuple(map(int, record)))
    expected_by_kind = {"count": SEC * read_rate, "daily": SEC * read_rate,
                        "by_api": SEC * read_rate, "insert": SEC * write_rate}
    statements_stats = {}
    for kind, expected in expected_by_kind.items():
        n, examined, duration = (a - b for a, b in zip(after[kind], before[kind]))
        require(n == expected, f"{table} {mode} {kind}: expected {expected} queries, saw {n}")
        statements_stats[kind] = {"calls": n, "rows_examined": examined, "sql_ms": round(duration / 1e9, 3)}
    report = {"table": variant, "mode": mode, "statements": statements_stats,
              "redo_bytes": after["redo"][0] - before["redo"][0]}
    if reads:
        report["summary"] = summarize(reads, SEC * read_rate)
        report["daily"] = latencies([row[3] / 1e6 for row in reads])
        report["by_api"] = latencies([row[4] / 1e6 for row in reads])
    if writes:
        report["write"] = summarize(writes, SEC * write_rate)
    print(json.dumps({k: v for k, v in report.items() if k != "statements"}), flush=True)
    return report


def index_sizes():
    return mysql("SELECT table_name,index_name,stat_value * @@innodb_page_size FROM mysql.innodb_index_stats "
                 "WHERE database_name=DATABASE() AND table_name IN ('usage_events','usage_index_cover') "
                 "AND stat_name='size' ORDER BY table_name,index_name")


def experiment(baseline, rounds, phases):
    fixtures = setup(baseline)
    mapper = statements()
    schema = {variant: mysql(f"SHOW CREATE TABLE {table}") for variant, table in TABLES.items()}
    plans = plan_matrix(fixtures, mapper)
    require(len(plans) == 24, "Expected 24 plan pairs")
    expected_total = row_counts()["A"]
    reports = []
    # The prepared plans warm both variants. Each phase alternates whether A or B runs first.
    with TemporaryDirectory(prefix="usage-index-", dir=ROOT / "data/usage-redis-acceptance") as driver_dir:
        folder = Path(driver_dir)
        prepare_driver(folder, fixtures, mapper)
        for index in range(rounds):
            for phase_index, mode in enumerate(phases):
                order = ("A", "B") if (index + phase_index) % 2 == 0 else ("B", "A")
                batch = 1000 + index * len(PHASES) + phase_index
                reports.append({"round": index + 1, "mode": mode,
                                "variants": {variant: window(folder, variant, mode, batch) for variant in order}})
                expected_total += PHASES[mode][1] * SEC
                require(row_counts() == {"A": expected_total, "B": expected_total}, "Different write counts after phase")
    result_diffs = mysql("SELECT COUNT(*) FROM usage_events AS A LEFT JOIN usage_index_cover AS B USING(event_id) "
                         "WHERE B.event_id IS NULL OR A.api_key_id <> B.api_key_id "
                         "OR A.application_id <> B.application_id OR A.api_code <> B.api_code "
                         "OR A.occurred_at <> B.occurred_at OR A.http_status_code <> B.http_status_code "
                         "OR A.duration_ms <> B.duration_ms")
    require(result_diffs == "0", "The two tables do not contain the same events")
    return {"mysql_version": mysql("SELECT VERSION()"), "fixtures": fixtures,
            "schema": schema, "initial_plans": plans, "workloads": reports,
            "events_per_variant": expected_total, "index_page_bytes": index_sizes()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--phase", choices=PHASES, help="Run only one phase, for a paired retest")
    args = parser.parse_args()
    require(args.rounds > 0, "At least one round is required")
    (ROOT / "data/usage-redis-acceptance").mkdir(parents=True, exist_ok=True)
    phases = (args.phase,) if args.phase else tuple(PHASES)
    report = run_lab(lambda baseline, _cached: experiment(baseline, args.rounds, phases))
    for run_result in report["workloads"]:
        for variant, measurement in run_result["variants"].items():
            for source in ("summary", "write"):
                if source in measurement:
                    row = measurement[source]
                    require(row["dispatch_p95_ms"] < 25 and row["dispatch_max_ms"] < 150,
                            f"Load schedule lagged in {run_result['mode']}/{variant}: {row}")
