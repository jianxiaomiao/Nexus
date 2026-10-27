#!/usr/bin/env python3
"""Read-only gates for this one V8 to V9 release."""

import re
import sys
import zipfile
from pathlib import Path


def main():
    previous, candidate = map(Path, sys.argv[1:])
    prefix = 'BOOT-INF/classes/db/migration/'
    with zipfile.ZipFile(previous / 'server/nexus-server.jar') as old, \
            zipfile.ZipFile(candidate / 'server/nexus-server.jar') as new:
        old_sql = {name for name in old.namelist() if name.startswith(prefix) and name.endswith('.sql')}
        new_sql = {name for name in new.namelist() if name.startswith(prefix) and name.endswith('.sql')}
        if len(old_sql) != 8 or new_sql - old_sql != {prefix + 'V9__create_screenshot_job.sql'}:
            raise RuntimeError('expected V1-V8 in old JAR and exactly additive V9 in candidate')
        for name in old_sql:
            if old.read(name) != new.read(name):
                raise RuntimeError('historical migration bytes changed: ' + name)
        for archive in (old, new):
            libraries = [name for name in archive.namelist()
                         if name.startswith('BOOT-INF/lib/playwright-') and name.endswith('.jar')]
            if libraries != ['BOOT-INF/lib/playwright-1.63.0.jar']:
                raise RuntimeError('Playwright dependency differs from fixed 1.63.0')

    # Check names without exposing values. systemd's existing EnvironmentFile
    # supplies the secrets; the scripts never source it as shell code.
    text = Path('/etc/nexus/nexus.env').read_text()
    names = re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*=', text, re.MULTILINE)
    required = {'NEXUS_DB_URL', 'NEXUS_DB_USERNAME', 'NEXUS_DB_PASSWORD',
                'NEXUS_FLYWAY_USERNAME', 'NEXUS_FLYWAY_PASSWORD',
                'NEXUS_DB_POOL_MAX_SIZE', 'NEXUS_DB_POOL_MIN_IDLE',
                'NEXUS_JWT_SECRET', 'NEXUS_LOG_DIR'}
    if required - set(names):
        raise RuntimeError('required environment variable names are missing: ' + str(sorted(required - set(names))))
    if len(names) != len(set(names)):
        raise RuntimeError('duplicate variable names in EnvironmentFile')
    if any(name.startswith(('SERVER_', 'MANAGEMENT_', 'SPRING_')) for name in names):
        raise RuntimeError('EnvironmentFile contains framework overrides; review before cutover')
    print('PASS: exact V1-V8 bytes unchanged, only V9 added, Playwright 1.63.0 unchanged, environment names verified.')


if __name__ == '__main__':
    main()
