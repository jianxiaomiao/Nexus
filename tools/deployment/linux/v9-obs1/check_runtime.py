#!/usr/bin/env python3
"""Bounded, read-only checks of the running V9 deployment."""

import http.client
import json
import math
import re
import subprocess


def output(*command):
    return subprocess.run(command, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE).stdout.decode()


def get(path, port=8081):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main():
    expected = {8080: 'java', 8081: 'java', 3306: 'mysqld', 18080: 'nginx', 18443: 'nginx'}
    observed = set()
    for line in output('ss', '-H', '-lntp').splitlines():
        address = line.split()[3]
        match = re.search(r':(\d+)$', address)
        if not match:
            continue
        port = int(match[1])
        if port in (80, 443):
            raise RuntimeError('unexpected listener on public Web port')
        if port not in expected:
            continue
        if address not in (f'127.0.0.1:{port}', f'[::ffff:127.0.0.1]:{port}'):
            raise RuntimeError('non-loopback listener: ' + address)
        if '"' + expected[port] + '"' not in line:
            raise RuntimeError('unexpected listener process on port ' + str(port))
        observed.add(port)
    if observed != set(expected):
        raise RuntimeError('missing required loopback listeners: ' + str(set(expected) - observed))

    status, body = get('/actuator')
    if status != 200:
        raise RuntimeError('management discovery unavailable')
    links = set(json.loads(body)['_links'])
    if links != {'self', 'health', 'health-path', 'metrics', 'metrics-requiredMetricName', 'prometheus'}:
        raise RuntimeError('unexpected management exposure: ' + str(sorted(links)))
    for path in ('/actuator/health', '/actuator/metrics', '/actuator/prometheus'):
        if get(path, 8080)[0] != 404:
            raise RuntimeError('Actuator reachable on business port: ' + path)
    for path in ('/actuator/env', '/actuator/configprops', '/actuator/heapdump', '/actuator/loggers'):
        if get(path)[0] != 404:
            raise RuntimeError('unexpected management endpoint: ' + path)
    for name, upper in (('nexus.ratelimit.waiters', 100), ('nexus.ratelimit.states', 1000),
                        ('nexus.screenshot.jobs.unfinished', 10), ('nexus.screenshot.slot.busy', 1)):
        status, body = get('/actuator/metrics/' + name)
        value = json.loads(body)['measurements'][0]['value'] if status == 200 else None
        if value is None or not math.isfinite(value) or not 0 <= value <= upper:
            raise RuntimeError('unavailable/out-of-bounds metric: ' + name)
    if 'Status: active' not in output('ufw', 'status', 'verbose'):
        raise RuntimeError('UFW is not active')
    if output('ufw', 'status', 'numbered').count('ALLOW'):
        raise RuntimeError('unexpected UFW Allow rule; current approved baseline has none')
    print('PASS: listener processes/loopback addresses, minimal management exposure, Gauge bounds, and UFW baseline verified.')


if __name__ == '__main__':
    main()
