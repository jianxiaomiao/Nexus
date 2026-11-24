"""Run FlowOps and Nexus acceptance in an isolated local MySQL 8.4 container."""
import argparse
import base64
from contextlib import ExitStack
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time

from nexus_flowops import api, expect


ROOT = Path(__file__).resolve().parents[2]
FLOWOPS_PORT = 8080
FLOWOPS_WEB_PORT = 5173
NEXUS_PORT = 18080
RELAY_PORT = 18081
FLOWOPS_URL = f"http://127.0.0.1:{FLOWOPS_PORT}/flowOps"
NEXUS_URL = f"http://127.0.0.1:{NEXUS_PORT}"
FLOWOPS_SCHEMA = "flowops_test_nexus"
NEXUS_SCHEMA = "nexus_test"


def command(*args, env=None, capture=False, timeout=None):
    return subprocess.run(args, env=env, check=True, text=True,
                          capture_output=capture, timeout=timeout).stdout


def check_ports():
    for port in (FLOWOPS_PORT, FLOWOPS_WEB_PORT, NEXUS_PORT, RELAY_PORT):
        with socket.socket() as listener:
            try:
                listener.bind(("127.0.0.1", port))
            except OSError as error:
                raise RuntimeError(f"Cannot bind test port {port}: {error.strerror}") from error


def wait_until(description, ready, process=None, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"{description} exited with {process.returncode}")
        if ready():
            return
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {description}")


def responds(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def http_ready(url, status):
    try:
        code, _ = api(url, None)
        return code == status
    except OSError:
        return False


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def start(stack, log, args, env, cwd):
    with log.open("w") as output:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    stack.callback(stop, process)
    return process


def run(label, args, cwd, env, log):
    print(f"> {label}", flush=True)
    with log.open("w") as output:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"{label} failed with exit {result.returncode}; see {log}")
    print(f"PASS {label}", flush=True)


def load_exports(path):
    settings = {}
    for line in path.read_text().splitlines():
        verb, assignment = shlex.split(line)
        key, value = assignment.split("=", 1)
        if verb != "export" or not key.startswith(("FLOWOPS_E2E_", "NEXUS_SUPPORT_", "NEXUS_FLOWOPS_TEST_")):
            raise RuntimeError("Unexpected FlowOps acceptance export")
        settings[key] = value
    return settings


def create_mysql(stack, work, flow_password, nexus_password):
    docker = shutil.which("docker")
    root_password = secrets.token_hex(24)
    name = "nexus-flowops-acceptance-" + secrets.token_hex(5)
    env = {**os.environ, "MYSQL_ROOT_PASSWORD": root_password, "MYSQL_PWD": root_password}
    container = command(docker, "run", "--rm", "-d", "--name", name, "--publish", "127.0.0.1::3306",
                        "--env", "MYSQL_ROOT_PASSWORD", "mysql:8.4", "--default-time-zone=+00:00",
                        env=env, capture=True).strip()
    stack.callback(subprocess.run, (docker, "rm", "-f", "-v", container), capture_output=True)
    port = command(docker, "port", container, "3306/tcp", capture=True).strip()
    match = re.fullmatch(r"127\.0\.0\.1:(\d+)", port)
    if not match:
        raise RuntimeError("MySQL did not bind an isolated IPv4 loopback port")
    root_mysql = [docker, "exec", "-i", "--env", "MYSQL_PWD", container,
                  "mysql", "--protocol=TCP", "--host=127.0.0.1", "--user=root"]
    wait_until("acceptance MySQL", lambda: subprocess.run(
        [*root_mysql, "--execute=SELECT 1"], env=env, capture_output=True, timeout=5).returncode == 0)
    sql = f"""
        CREATE DATABASE {FLOWOPS_SCHEMA};
        CREATE USER 'flowops_accept'@'%' IDENTIFIED BY '{flow_password}';
        GRANT ALL ON {FLOWOPS_SCHEMA}.* TO 'flowops_accept'@'%';
        CREATE DATABASE {NEXUS_SCHEMA};
        CREATE USER 'nexus_test_user'@'%' IDENTIFIED BY '{nexus_password}';
        GRANT ALL ON {NEXUS_SCHEMA}.* TO 'nexus_test_user'@'%';
    """
    subprocess.run(root_mysql, input=sql, env=env, check=True, text=True, capture_output=True)
    # The existing FlowOps acceptance script invokes mysql on PATH. Use the
    # container's CLI, constrained to its disposable schema and test user.
    mysql = work / "mysql"
    mysql.write_text(f"""#!/bin/sh
exec {shlex.quote(docker)} exec -i --env MYSQL_PWD {shlex.quote(container)} mysql \\
  --protocol=TCP --host=127.0.0.1 --user=flowops_accept --database={FLOWOPS_SCHEMA} \\
  --batch --skip-column-names
""")
    mysql.chmod(0o700)
    print(f"PASS isolated MySQL on 127.0.0.1:{match.group(1)}", flush=True)
    return match.group(1)


def jdbc(port, schema):
    return (f"jdbc:mysql://127.0.0.1:{port}/{schema}"
            "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")


def nexus_login():
    username = f"flowops-{secrets.token_hex(6)}@example.test"
    password = secrets.token_urlsafe(24)
    user = {"email": username, "password": password}
    expect(api(f"{NEXUS_URL}/api/auth/register", None, {**user, "displayName": "FlowOps Acceptance"}), 201)
    return expect(api(f"{NEXUS_URL}/api/auth/login", None, user), 200)["data"]["accessToken"]


def maven():
    installed = shutil.which("mvn")
    bundled = Path("/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn")
    if installed:
        return installed
    if bundled.is_file():
        return str(bundled)
    raise RuntimeError("Maven is unavailable (install mvn or use the IntelliJ IDEA Maven installation)")


def test_environment():
    excluded = ("SPRING_", "SERVER_", "FLOWOPS_", "NEXUS_", "MANAGEMENT_", "LOGGING_")
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(excluded) and key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
    if sys.platform == "darwin":
        env["JAVA_HOME"] = command("/usr/libexec/java_home", "-F", "-v", "21", capture=True).strip()
    return env


def configure_node(env):
    current_path = env.get("PATH", os.defpath)
    paths = [current_path]
    if sys.platform == "darwin":
        plugins = Path("/Applications/HBuilderX.app/Contents/HBuilderX/plugins")
        paths.append(os.pathsep.join((str(plugins / "node"), str(plugins / "npm"), current_path)))

    for path in paths:
        if not all(shutil.which(tool, path=path) for tool in ("node", "npm")):
            continue
        candidate = {**env, "PATH": path}
        version = command("node", "--version", env=candidate, capture=True).strip()
        match = re.fullmatch(r"v([0-9]+)\.([0-9]+)\.[0-9]+", version)
        if match:
            major, minor = map(int, match.groups())
            if (major == 22 and minor >= 18) or (major, minor) >= (24, 11):
                npm_version = command("npm", "--version", env=candidate, capture=True).strip()
                print(f"PASS Node.js {version}, npm {npm_version}", flush=True)
                return candidate

    raise RuntimeError("FlowOps requires Node.js 22.18+ (22.x) or 24.11+, and npm. "
                       "Use terminal PATH or the Node.js and npm bundled with HBuilderX on macOS.")


def configure_flowops_test_vector_store(backend):
    # The ticket endpoints share a controller with knowledge recommendations.
    # Supply its vector store only in the staged acceptance application.
    config = backend / "src/main/java/com/example/demo/config/TicketAcceptanceVectorStore.java"
    with config.open("x", encoding="utf-8") as source:
        source.write("""package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("test")
class TicketAcceptanceVectorStore {
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
""")


def run_acceptance(flowops, work):
    for executable in ("docker", "htpasswd"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"{executable} must be available in the terminal PATH")
    mvn = maven()
    env = configure_node(test_environment())
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in env else "java"
    docker_host = os.environ.get("DOCKER_HOST")
    if os.environ.get("DOCKER_CONTEXT") or not docker_host:
        context = command("docker", "context", "show", capture=True).strip()
        docker_host = command("docker", "context", "inspect", context, "--format",
                              "{{.Endpoints.docker.Host}}", capture=True).strip()
    if not docker_host.startswith("unix://"):
        raise RuntimeError("Use a local Docker context with a Unix socket")
    version = command("docker", "info", "--format", "{{.ServerVersion}}", capture=True).strip()
    print(f"PASS Docker Engine {version}", flush=True)
    check_ports()

    ignore = shutil.ignore_patterns("target", "node_modules", "dist", "test-results", "playwright-report",
                                    "logger", ".git", ".env*", ".idea", ".m2repo", "application-local.yml",
                                    "application-local.properties")
    staging = work / "staging"
    staging.mkdir()
    flow_backend = staging / "flow-backend"
    flow_web = staging / "flow-web"
    nexus_backend = staging / "nexus-backend"
    shutil.copytree(flowops / "demo", flow_backend, ignore=ignore)
    configure_flowops_test_vector_store(flow_backend)
    shutil.copytree(flowops / "web", flow_web, ignore=ignore)
    shutil.copytree(ROOT / "nexus-server", nexus_backend, ignore=ignore)

    flow_password = secrets.token_hex(24)
    nexus_password = secrets.token_hex(24)
    exports = staging / "flowops.env"
    accept = ROOT / "tools/acceptance/nexus_flowops.py"
    with ExitStack() as stack:
        stack.callback(exports.unlink, missing_ok=True)
        run("locked FlowOps backend build", [mvn, "-B", "-DskipTests", "package"], flow_backend, env,
            work / "flow-build.log")
        run("Nexus backend build", [mvn, "-B", "-DskipTests", "package"], nexus_backend, env, work / "nexus-build.log")
        run("locked FlowOps Web dependencies", ["npm", "ci", "--no-audit", "--no-fund"], flow_web, env,
            work / "flow-npm.log")
        run("locked FlowOps Web build", ["npm", "run", "build"], flow_web, env, work / "flow-web-build.log")
        run("Playwright Chromium", ["npm", "exec", "--", "playwright", "install", "chromium"], flow_web, env,
            work / "chromium.log")
        check_ports()
        port = create_mysql(stack, staging, flow_password, nexus_password)
        flow_env = {**env, "SPRING_PROFILES_ACTIVE": "test", "SERVER_ADDRESS": "127.0.0.1",
                    "SERVER_PORT": str(FLOWOPS_PORT), "FLOWOPS_LOG_PATH": str(work / "flow-logs"),
                    "FLOWOPS_TEST_DB_URL": jdbc(port, FLOWOPS_SCHEMA), "FLOWOPS_TEST_DB_USERNAME": "flowops_accept",
                    "FLOWOPS_TEST_DB_PASSWORD": flow_password, "FLOWOPS_TEST_JWT_SECRET": secrets.token_hex(32),
                    "FLOWOPS_TEST_RABBITMQ_USERNAME": "unused", "FLOWOPS_TEST_RABBITMQ_PASSWORD": "unused",
                    "FLOWOPS_TEST_REDIS_ENABLED": "false", "FLOWOPS_TEST_MESSAGING_ENABLED": "false",
                    "MANAGEMENT_HEALTH_REDIS_ENABLED": "false", "MANAGEMENT_HEALTH_RABBIT_ENABLED": "false"}
        flow_process = start(stack, work / "flow-backend.log",
                             [java, "-jar", "target/demo-0.0.1-SNAPSHOT.jar",
                              "--spring.ai.vectorstore.type=none",
                              "--spring.ai.ollama.init.pull-model-strategy=never"], flow_env, flow_backend)
        wait_until("FlowOps", lambda: http_ready(f"{FLOWOPS_URL}/actuator/health", 200), flow_process)
        seed_env = {**env, "FLOWOPS_ACCEPTANCE_URL": FLOWOPS_URL, "FLOWOPS_ACCEPTANCE_SCHEMA": FLOWOPS_SCHEMA,
                    "FLOWOPS_ACCEPTANCE_MYSQL_LOGIN_PATH": "docker-fixture", "FLOWOPS_ACCEPTANCE_MYSQL_PORT": port,
                    "FLOWOPS_ACCEPTANCE_ENV_FILE": str(exports), "PATH": str(staging) + os.pathsep + env["PATH"],
                    "MYSQL_PWD": flow_password}
        run("FlowOps V5, API, tenant isolation and ticket claims",
            [sys.executable, str(flowops / "tools/acceptance/nexus_tickets.py")], work, seed_env, work / "flow-api.log")
        fixture = load_exports(exports)
        if not all(fixture.get("FLOWOPS_E2E_" + name) for name in ("MACHINE_CREDENTIAL", "TENANT_CODE", "USERNAME", "PASSWORD")):
            raise RuntimeError("FlowOps workbench credentials are missing")
        run("FlowOps browser workbench", ["npm", "run", "test:e2e", "--", "nexus-tickets.spec.ts"], flow_web,
            {**env, **fixture, "FLOWOPS_E2E_BASE_URL": f"http://127.0.0.1:{FLOWOPS_WEB_PORT}"}, work / "flow-browser.log")

        nexus_env = {**env, **fixture, "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(NEXUS_PORT),
                     "SPRING_DATASOURCE_URL": jdbc(port, NEXUS_SCHEMA), "SPRING_DATASOURCE_USERNAME": "nexus_test_user",
                     "SPRING_DATASOURCE_PASSWORD": nexus_password, "NEXUS_AUTH_JWT_ISSUER": "nexus-flowops-acceptance",
                     "NEXUS_AUTH_JWT_SECRET": base64.b64encode(secrets.token_bytes(32)).decode(), "NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION": "v1",
                     "NEXUS_WEBHOOK_CRYPTO_KEYS_V1": base64.b64encode(secrets.token_bytes(32)).decode(),
                     "NEXUS_WEBHOOK_SCHEDULING_ENABLED": "false", "NEXUS_LOG_DIR": str(work / "nexus-logs"),
                     "NEXUS_SCREENSHOT_JOB_RESULT_DIR": str(staging / "screenshot-results")}
        nexus_args = [java, "-Duser.timezone=UTC", "-jar", "target/nexus-server-0.0.1-SNAPSHOT.jar",
                      "--nexus.auth.jwt.access-token-ttl=30m"]
        nexus = start(stack, work / "nexus.log", nexus_args, nexus_env, nexus_backend)
        wait_until("Nexus", lambda: http_ready(f"{NEXUS_URL}/api/support/tickets", 401), nexus)
        delivery_env = {**env, **fixture, "NEXUS_FLOWOPS_TEST_NEXUS_URL": NEXUS_URL,
                        "NEXUS_FLOWOPS_TEST_NEXUS_USER": nexus_login(), "NEXUS_FLOWOPS_TEST_NEXUS_OTHER_USER": nexus_login()}
        run("Nexus delivery and FlowOps ticket match", [sys.executable, str(accept), "--deliver-only"],
            ROOT, delivery_env, work / "delivery.log")

        stop(nexus)
        relay_log = work / "relay.log"
        relay = start(stack, relay_log, [sys.executable, str(accept), "--drop-first-ack", str(RELAY_PORT)],
                      delivery_env, ROOT)
        wait_until("ACK-loss relay", lambda: responds(RELAY_PORT), relay)
        fault_env = {**nexus_env, "NEXUS_SUPPORT_FLOWOPS_ENDPOINT":
                     f"http://127.0.0.1:{RELAY_PORT}/flowOps/integration/v1/tickets"}
        nexus = start(stack, work / "nexus-ack-loss.log", nexus_args, fault_env, nexus_backend)
        wait_until("Nexus with ACK-loss relay", lambda: http_ready(f"{NEXUS_URL}/api/support/tickets", 401), nexus)
        run("Nexus delivery with the first ACK dropped", [sys.executable, str(accept), "--deliver-only"],
            ROOT, delivery_env, work / "delivery-ack-loss.log")
        relay_trace = relay_log.read_text()
        dropped = set(re.findall(r"Dropped FlowOps ACK after HTTP 201, event=([a-f0-9-]+)", relay_trace))
        replayed = set(re.findall(r"Forwarded replay ACK after HTTP 200, event=([a-f0-9-]+)", relay_trace))
        if len(dropped & replayed) != 1:
            raise RuntimeError("Expected the same event to lose HTTP 201 and replay with HTTP 200")
        print("PASS same event received a replay ACK after losing the original", flush=True)
        run("Nexus MySQL Worker lease takeover",
            [mvn, "-B", "-Dtest=SupportTicketLeaseIntegrationTest", "test"], nexus_backend,
            {**nexus_env, "NEXUS_SUPPORT_LEASE_ACCEPTANCE": "true"}, work / "lease.log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flowops", type=Path, default=ROOT.parent / "flowops")
    options = parser.parse_args()
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix="nexus-flowops-acceptance-"))
    print(f"Local acceptance logs: {work}", flush=True)
    try:
        run_acceptance(options.flowops.resolve(), work)
    except (RuntimeError, OSError, subprocess.SubprocessError, AssertionError) as error:
        print(f"Acceptance incomplete: {error}", file=sys.stderr)
        return 1
    finally:
        shutil.rmtree(work / "staging", ignore_errors=True)
    print(f"PASS FlowOps and Nexus local acceptance; evidence: {work}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
