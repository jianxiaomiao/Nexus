#!/usr/bin/env python3
"""Restore the stopped V8 dump into a temporary schema before switching release."""

import re
import subprocess
import sys
import uuid
from pathlib import Path


def mysql(sql, schema=None):
    command = ['mysql', '--protocol=socket', '--batch', '--skip-column-names']
    if schema:
        command.append(schema)
    result = subprocess.run(command, input=sql.encode(), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('backup restore verification failed: ' + result.stderr.decode(errors='replace'))
    return result.stdout.decode().strip()


def prepare_dump(text, probe):
    # Only database-selection statements change. Do not replace business data.
    text, creates = re.subn(r'(?m)^(CREATE DATABASE .*?)`nexus`',
                           lambda m: m[1] + '`' + probe + '`', text)
    text, uses = re.subn(r'(?m)^USE `nexus`;', 'USE `' + probe + '`;', text)
    if creates != 1 or uses != 1:
        raise RuntimeError('expected exactly one CREATE DATABASE and USE nexus in dump')
    if re.search(r'`nexus`\s*\.', text):
        raise RuntimeError('dump contains schema-qualified references; inspect before restoring')
    return text


def main():
    dump = Path(sys.argv[1]).read_text(encoding='utf-8')
    probe = 'nexus_v8_restore_probe_' + uuid.uuid4().hex
    if mysql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + probe + "'") != '0':
        raise RuntimeError('temporary restore schema unexpectedly exists')
    prepared = prepare_dump(dump, probe)
    try:
        mysql(prepared)
        tables = mysql('SHOW TABLES', 'nexus').splitlines()
        if tables != mysql('SHOW TABLES', probe).splitlines():
            raise RuntimeError('restored table names differ from source V8')
        for table in tables:
            if not re.fullmatch('[a-zA-Z0-9_]+', table):
                raise RuntimeError('unexpected source table name')
            query = 'SELECT COUNT(*) FROM `' + table + '`'
            if mysql(query, 'nexus') != mysql(query, probe):
                raise RuntimeError('restored row count mismatch: ' + table)
        history = ('SELECT version, checksum, success FROM flyway_schema_history '
                   'ORDER BY installed_rank')
        if mysql(history, 'nexus') != mysql(history, probe):
            raise RuntimeError('restored Flyway checksums/history differ')
        print('PASS: V8 backup restored to an isolated schema; table/row counts and Flyway checksums match.')
    finally:
        if not re.fullmatch('nexus_v8_restore_probe_[0-9a-f]{32}', probe):
            raise RuntimeError('unsafe temporary restore schema')
        mysql('DROP DATABASE IF EXISTS `' + probe + '`')


if __name__ == '__main__':
    main()
