#!/usr/bin/env python3
"""Disposable MySQL, random local credentials, no connection to an existing database."""
from pathlib import Path
import base64
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

project = Path(__file__).resolve().parents[1]
mysql_home = Path(os.environ.get('NEXUS_TEST_MYSQL_HOME', str(Path.home() / 'Library/Application Support/NexusDev/mysql-8.4.11')))
java_home = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home')))
maven = os.environ.get('NEXUS_TEST_MAVEN', '/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn')
output = project / 'target/robustness'
output.mkdir(parents=True, exist_ok=True)
workspace = Path(tempfile.mkdtemp(prefix='nexus-robustness-', dir='/private/tmp'))
server = None
try:
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    log = open(workspace / 'mysql.log', 'wb')
    subprocess.run([str(mysql_home / 'bin/mysqld'), '--no-defaults', '--initialize-insecure',
                    '--basedir=' + str(mysql_home), '--datadir=' + str(workspace / 'data')], stdout=log, stderr=log, check=True)
    server = subprocess.Popen([str(mysql_home / 'bin/mysqld'), '--no-defaults', '--basedir=' + str(mysql_home),
        '--datadir=' + str(workspace / 'data'), '--bind-address=127.0.0.1', '--port=' + str(port),
        '--mysqlx=OFF', '--socket=' + str(workspace / 'mysql.sock'), '--pid-file=' + str(workspace / 'mysql.pid')], stdout=log, stderr=log)
    cli = [str(mysql_home / 'bin/mysql'), '--no-defaults', '--protocol=SOCKET', '--socket=' + str(workspace / 'mysql.sock'), '-uroot']
    for _ in range(60):
        if server.poll() is not None:
            raise RuntimeError('Disposable MySQL did not start; existing databases were not contacted')
        if subprocess.run(cli + ['-e', 'SELECT 1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('Disposable MySQL startup timeout')
    password = secrets.token_urlsafe(32)
    root_password = secrets.token_urlsafe(32)
    sql = ("CREATE DATABASE nexus_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
           "CREATE USER 'nexus_test_user'@'127.0.0.1' IDENTIFIED BY '" + password + "';"
           "GRANT ALL ON nexus_test.* TO 'nexus_test_user'@'127.0.0.1';"
           "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + root_password + "';")
    subprocess.run(cli, input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    env = os.environ.copy()
    secret = base64.b64encode(secrets.token_bytes(48)).decode()
    jdbc = f'jdbc:mysql://127.0.0.1:{port}/nexus_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
    env.update(JAVA_HOME=str(java_home), SPRING_DATASOURCE_URL=jdbc, SPRING_DATASOURCE_USERNAME='nexus_test_user',
        SPRING_DATASOURCE_PASSWORD=password, SPRING_FLYWAY_URL=jdbc, SPRING_FLYWAY_USER='nexus_test_user',
        SPRING_FLYWAY_PASSWORD=password, NEXUS_AUTH_JWT_ISSUER='nexus-robustness-test',
        NEXUS_AUTH_JWT_ACCESS_TOKEN_TTL='30m', NEXUS_AUTH_JWT_SECRET=secret, NEXUS_JWT_SECRET=secret,
        NEXUS_WEBHOOK_CRYPTO_KEYS_V1=base64.b64encode(secrets.token_bytes(32)).decode(),
        NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION='v1', NEXUS_LOG_DIR=str(output / 'logs'),
        NEXUS_SCREENSHOT_JOB_RESULT_DIR=str(workspace / 'results'))
    selected = os.environ.get('NEXUS_ROBUSTNESS_TESTS',
        'ScreenshotJobServiceTests,ScreenshotSubmissionTests,ScreenshotSubmissionIntegrationTests,ApiKeyServiceTests,'
        'ApplicationServiceImplTests,ApplicationControllerTests,ApiKeyControllerTests,ApplicationWebhookControllerTests,'
        'ScreenshotJobOpenApiControllerTests,ApplicationUpdateFlowIntegrationTests,ApiKeyManagementFlowIntegrationTests,'
        'ApplicationWebhookServiceIntegrationTests,ShortLinkFlowIntegrationTests,UtcPersistenceIntegrationTests')
    agent = Path.home() / '.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar'
    print('Disposable MySQL ready; running isolated robustness checks.', flush=True)
    with open(output / 'verification.log', 'wb') as report:
        result = subprocess.run([maven, '-o', '-q', '-DargLine=-javaagent:' + str(agent), '-Dtest=' + selected, 'test'],
                                cwd=project, env=env, stdout=report, stderr=subprocess.STDOUT)
    for xml in sorted((project / 'target/surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(xml).getroot()
        if suite.get('name', '').split('.')[-1] in selected.split(','):
            print(suite.get('name').split('.')[-1], {key: suite.get(key) for key in ['tests', 'failures', 'errors', 'skipped']}, flush=True)
    raise SystemExit(result.returncode)
finally:
    if server is not None:
        server.terminate()
        try:
            server.wait(timeout=30)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=10)
    shutil.rmtree(workspace)
