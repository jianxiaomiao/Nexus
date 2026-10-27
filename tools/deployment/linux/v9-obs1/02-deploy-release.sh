#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly release_name='V2026.10.27.1__v9-obs1'
readonly previous_release='V2026.10.22.1__ubuntu-initial'
readonly state_file="/opt/nexus/state/deployment-${release_name}.env"
readonly management_base='http://127.0.0.1:8081'
readonly business_base='http://127.0.0.1:8080'
readonly tls_base='https://nexus.test:18443'
readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

cutover_started=0
completed=0
backup_ready=0

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

on_exit() {
    local exit_code=$?
    if (( exit_code != 0 && cutover_started == 1 && completed == 0 )); then
        echo 'Deployment did not pass. Stopping Nexus and Nginx while preserving evidence.' >&2
        systemctl stop nginx.service >/dev/null 2>&1 || true
        systemctl stop nexus.service >/dev/null 2>&1 || true
        if (( backup_ready == 1 )); then
            echo "Run 05-rollback-release.sh --restore-v8 after reviewing logs and $state_file." >&2
        else
            # No configuration/current/schema changes have happened yet.
            echo 'Cutover stopped before changing configuration/schema; restarting the unchanged V8 services.' >&2
            systemctl start nexus.service && systemctl start nginx.service || true
        fi
    fi
    exit "$exit_code"
}
trap on_exit EXIT

if (( EUID != 0 )); then
    fail 'run this script as root'
fi
if (( $# != 2 )); then
    fail "usage: $0 /path/to/application-prod.yml /path/to/nginx-tls-loopback.conf"
fi
exec 9>/opt/nexus/state/deployment.lock
flock -n 9 || fail 'another deployment operation is running'

prod_config=$(readlink -f -- "$1")
nginx_config=$(readlink -f -- "$2")
release_root="/opt/nexus/releases/$release_name"
previous_root="/opt/nexus/releases/$previous_release"

[[ -f "$prod_config" ]] || fail "production configuration is missing: $prod_config"
[[ -f "$nginx_config" ]] || fail "Nginx configuration is missing: $nginx_config"
[[ -d "$release_root" ]] || fail "candidate release is not staged: $release_root"
[[ -d "$previous_root" ]] || fail "previous release is missing: $previous_root"
[[ ! -e "$state_file" ]] || fail "deployment state already exists: $state_file"
[[ "$(readlink -f -- /opt/nexus/current)" == "$previous_root" ]] || fail 'current is not the expected previous release'
[[ "$(tr -d '\r\n' </opt/nexus/state/last-successful.txt)" == "$previous_release" ]] ||
    fail 'last-successful is not the expected previous release'
[[ ! -e /opt/nexus/state/rollback-release.txt ]] || fail 'rollback-release unexpectedly already exists'

python3 "$script_dir/verify_release.py" --directory "$release_root" --release "$release_name" >/dev/null
python3 "$script_dir/verify_release.py" --directory "$previous_root" --release "$previous_release" --allow-dirty >/dev/null
[[ "$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e "SELECT CONCAT(COUNT(*), ':', MAX(CAST(version AS UNSIGNED)), ':', SUM(success=0)) FROM flyway_schema_history")" == '8:8:0' ]] ||
    fail 'database must contain exactly successful V1-V8 before cutover'
[[ "$(mysql --protocol=socket --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nexus' AND table_name='screenshot_jobs'")" == 0 ]] ||
    fail 'screenshot_jobs already exists before cutover'
[[ ! -e /etc/nginx/sites-enabled/default ]] || fail 'unexpected default Nginx site is enabled'
[[ -r /etc/nexus/nexus.env ]] || fail 'Nexus environment file is missing'
python3 "$script_dir/preflight.py" "$previous_root" "$release_root"
systemd-analyze verify /etc/systemd/system/nexus.service
openssl x509 -in /etc/nginx/tls-lab/nexus.test.crt -noout -checkend 86400

grep -q '^management:$' "$prod_config" || fail 'production configuration has no management section'
grep -q '^    address: 127\.0\.0\.1$' "$prod_config" || fail 'management address is not loopback'
grep -q '^    port: 8081$' "$prod_config" || fail 'management port is not 8081'
grep -q '^        include: health,metrics,prometheus$' "$prod_config" || fail 'management exposure is not minimal'
for variable in NEXUS_DB_URL NEXUS_DB_USERNAME NEXUS_DB_PASSWORD \
        NEXUS_FLYWAY_USERNAME NEXUS_FLYWAY_PASSWORD NEXUS_DB_POOL_MAX_SIZE \
        NEXUS_DB_POOL_MIN_IDLE NEXUS_JWT_SECRET NEXUS_SCREENSHOT_JOB_RESULT_DIR \
        NEXUS_LOG_DIR; do
    grep -q "\${$variable}" "$prod_config" || fail "production configuration is missing placeholder $variable"
done

grep -q 'listen 127\.0\.0\.1:18080;' "$nginx_config" || fail 'Nginx HTTP listener is not the expected loopback port'
grep -q 'listen 127\.0\.0\.1:18443 ssl;' "$nginx_config" || fail 'Nginx TLS listener is not the expected loopback port'
grep -q 'location = /actuator' "$nginx_config" || fail 'Nginx exact Actuator denial is missing'
grep -q 'location \^~ /actuator/' "$nginx_config" || fail 'Nginx Actuator subtree denial is missing'
grep -q 'proxy_pass http://127\.0\.0\.1:8080;' "$nginx_config" || fail 'Nginx Java upstream is unexpected'

[[ "$(systemctl is-active nexus.service)" == active ]] || fail 'nexus.service is not active before cutover'
[[ "$(systemctl is-active nginx.service)" == active ]] || fail 'nginx.service is not active before cutover'
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]] || fail 'nexus.service must remain disabled'
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]] || fail 'nginx.service must remain disabled'

if ufw status | grep -Eq '(^|[[:space:]])(80|443|8080|8081|18080|18443|3306)(/tcp)?[[:space:]]+ALLOW'; then
    fail 'UFW unexpectedly allows a protected Nexus port'
fi
if ss -H -lntp | awk '$4 ~ /:(80|443|8081)$/ {found=1} END {exit !found}'; then
    ss -H -lntp | awk '$4 ~ /:(80|443|8081)$/ {print}' >&2
    fail 'a new or public listener exists before cutover'
fi

install -o root -g root -m 0700 -d /opt/nexus/backups
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_dir="/opt/nexus/backups/${release_name}-${timestamp}"
install -o root -g root -m 0700 -d "$backup_dir"

cutover_started=1
systemctl stop nginx.service
systemctl stop nexus.service
[[ "$(systemctl is-active nginx.service)" == inactive ]]
[[ "$(systemctl is-active nexus.service)" == inactive ]]
if ss -H -lntp | awk '$4 ~ /:(8080|8081|18080|18443)$/ {found=1} END {exit !found}'; then
    ss -H -lntp | awk '$4 ~ /:(8080|8081|18080|18443)$/ {print}' >&2
    fail 'a Nexus listener remained after stopping services'
fi

cp --preserve=mode,ownership,timestamps /etc/nexus/application-prod.yml "$backup_dir/application-prod.yml"
cp --preserve=mode,ownership,timestamps /etc/nexus/nexus.env "$backup_dir/nexus.env"
cp --preserve=mode,ownership,timestamps /etc/nginx/sites-available/nexus-loopback "$backup_dir/nginx-loopback.conf"
printf '%s\n' "$previous_root" >"$backup_dir/current-target.txt"
chmod 0600 "$backup_dir/current-target.txt"

mysqldump --protocol=socket --single-transaction --routines --triggers --events \
    --set-gtid-purged=OFF --default-character-set=utf8mb4 --databases nexus \
    >"$backup_dir/nexus-v8.sql"
chmod 0600 "$backup_dir/nexus-v8.sql"
[[ -s "$backup_dir/nexus-v8.sql" ]] || fail 'database backup is empty'
grep -q 'CREATE DATABASE' "$backup_dir/nexus-v8.sql" || fail 'database backup has no CREATE DATABASE statement'
grep -q 'flyway_schema_history' "$backup_dir/nexus-v8.sql" || fail 'database backup has no Flyway history'
(cd "$backup_dir" && sha256sum application-prod.yml nexus.env nginx-loopback.conf current-target.txt nexus-v8.sql >SHA256SUMS)
chmod 0600 "$backup_dir/SHA256SUMS"
python3 "$script_dir/verify_v8_backup.py" "$backup_dir/nexus-v8.sql"

# Persist recovery information before the first configuration or pointer change.
cat >"$state_file" <<STATE
STATUS=PENDING
RELEASE=$release_name
PREVIOUS_RELEASE=$previous_release
BACKUP_DIR=$backup_dir
STARTED_AT_UTC=$timestamp
STATE
chown root:root "$state_file"
chmod 0600 "$state_file"
backup_ready=1

# Keep secrets byte-for-byte and set only the new non-secret absolute result path.
python3 - <<'PY'
from pathlib import Path
import os, re
path = Path('/etc/nexus/nexus.env')
text = path.read_text()
lines = [line for line in text.splitlines()
         if not re.match(r'^\s*NEXUS_SCREENSHOT_JOB_RESULT_DIR\s*=', line)]
lines.append('NEXUS_SCREENSHOT_JOB_RESULT_DIR=/var/lib/nexus/screenshot-jobs')
temporary = path.with_name('nexus.env.v9-new')
with temporary.open('x') as stream:
    stream.write('\n'.join(lines) + '\n')
temporary.chmod(0o600)
os.chown(temporary, 0, 0)
os.replace(temporary, path)
PY

install -o root -g nexus -m 0640 -- "$prod_config" /etc/nexus/application-prod.yml
install -o root -g root -m 0644 -- "$nginx_config" /etc/nginx/sites-available/nexus-loopback
ln -sfn ../sites-available/nexus-loopback /etc/nginx/sites-enabled/nexus-loopback
nginx -t

next_link="/opt/nexus/.current-${release_name}"
[[ ! -e "$next_link" ]] || fail "temporary current link already exists: $next_link"
ln -s "$release_root" "$next_link"
mv -Tf -- "$next_link" /opt/nexus/current
[[ "$(readlink -f -- /opt/nexus/current)" == "$release_root" ]]

systemctl reset-failed nexus.service
systemctl start nexus.service

health_ok=0
for _ in {1..120}; do
    if curl --noproxy '*' --silent --show-error --fail \
            --connect-timeout 2 --max-time 5 \
            "$management_base/actuator/health" >"$backup_dir/health.json" 2>/dev/null &&
            grep -q '"status":"UP"' "$backup_dir/health.json"; then
        health_ok=1
        break
    fi
    if [[ "$(systemctl is-active nexus.service 2>/dev/null || true)" == failed ]]; then
        break
    fi
    sleep 1
done
(( health_ok == 1 )) || fail 'candidate did not reach management health UP within 120 seconds'

[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nexus.service --property NRestarts --value)" == 0 ]]

assert_loopback_listener() {
    local port=$1
    local output address
    output=$(ss -H -lntp "sport = :$port")
    [[ -n "$output" ]] || fail "port $port is not listening"
    while IFS= read -r line; do
        address=$(awk '{print $4}' <<<"$line")
        case "$address" in
            127.0.0.1:"$port"|'[::ffff:127.0.0.1]':"$port") ;;
            *) fail "port $port is not loopback-only: $address" ;;
        esac
    done <<<"$output"
}
assert_loopback_listener 8080
assert_loopback_listener 8081
if ss -H -lntp | awk '$4 ~ /:(80|443)$/ {found=1} END {exit !found}'; then
    fail 'public port 80 or 443 unexpectedly started listening'
fi

curl --noproxy '*' --silent --show-error --fail "$management_base/actuator/metrics" >"$backup_dir/metrics.json"
curl --noproxy '*' --silent --show-error --fail "$management_base/actuator/prometheus" >"$backup_dir/prometheus.txt"
env_status=$(curl --noproxy '*' --silent --show-error --output /dev/null --write-out '%{http_code}' \
    "$management_base/actuator/env")
[[ "$env_status" == 404 ]] || fail "management env endpoint returned $env_status instead of 404"
business_actuator_status=$(curl --noproxy '*' --silent --show-error --output /dev/null --write-out '%{http_code}' \
    "$business_base/actuator/health")
[[ "$business_actuator_status" == 404 ]] || fail "business-port Actuator returned $business_actuator_status instead of 404"

python3 - "$backup_dir/metrics.json" <<'PY'
import json, sys
names = set(json.load(open(sys.argv[1], encoding='utf-8'))['names'])
required = {
    'http.server.requests', 'hikaricp.connections.active',
    'jvm.memory.used', 'jvm.threads.live', 'process.uptime', 'disk.free',
    'nexus.ratelimit.admission', 'nexus.ratelimit.waiters',
    'nexus.ratelimit.states', 'nexus.screenshot.queue.delay',
    'nexus.screenshot.execution', 'nexus.screenshot.jobs.unfinished',
    'nexus.screenshot.slot.busy', 'nexus.usage.record.failures',
}
missing = sorted(required - names)
if missing:
    raise SystemExit(f'missing management metrics: {missing}')
PY

grep -q 'nexus_ratelimit_waiters' "$backup_dir/prometheus.txt"
grep -q 'nexus_screenshot_jobs_unfinished' "$backup_dir/prometheus.txt"
grep -q 'nexus_usage_record_failures_total' "$backup_dir/prometheus.txt"

latest_migration=$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e "SELECT CONCAT(version, ':', success) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
[[ "$latest_migration" == '9:1' ]] || fail "latest Flyway migration is not successful V9: $latest_migration"
failed_migrations=$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0')
[[ "$failed_migrations" == 0 ]] || fail "Flyway has $failed_migrations failed migration rows"
job_table_count=$(mysql --protocol=socket --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nexus' AND table_name='screenshot_jobs'")
[[ "$job_table_count" == 1 ]] || fail 'screenshot_jobs was not created exactly once'
initial_jobs=$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e 'SELECT COUNT(*) FROM screenshot_jobs')
[[ "$initial_jobs" == 0 ]] || fail "screenshot_jobs was not empty immediately after migration: $initial_jobs"

systemctl reset-failed nginx.service
systemctl start nginx.service
[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nginx.service --property NRestarts --value)" == 0 ]]
assert_loopback_listener 18080
assert_loopback_listener 18443
if ss -H -lntp | awk '$4 ~ /:(80|443)$/ {found=1} END {exit !found}'; then
    fail 'public port 80 or 443 unexpectedly started listening after Nginx start'
fi

curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert /etc/nginx/tls-lab/ca.crt \
    "$tls_base/" >"$backup_dir/index.html"
cmp -- "$release_root/console/dist/index.html" "$backup_dir/index.html"

invalid_status=$(curl --noproxy '*' --silent --show-error \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert /etc/nginx/tls-lab/ca.crt \
    -H 'Authorization: ApiKey deployment-invalid' \
    --output "$backup_dir/invalid-key.json" --write-out '%{http_code}' \
    "$tls_base/v1/utils/uuid")
[[ "$invalid_status" == 401 ]] || fail "Nginx-to-Java invalid credential probe returned $invalid_status"
grep -q '"code":"INVALID_API_KEY_CREDENTIAL"' "$backup_dir/invalid-key.json"

actuator_proxy_status=$(curl --noproxy '*' --silent --show-error \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert /etc/nginx/tls-lab/ca.crt \
    --output /dev/null --write-out '%{http_code}' \
    "$tls_base/actuator/health")
[[ "$actuator_proxy_status" == 404 ]] || fail "Nginx Actuator denial returned $actuator_proxy_status"
actuator_log=$(grep 'GET /actuator/health ' /var/log/nginx/nexus-loopback-access.log | tail -n 1 || true)
[[ "$actuator_log" == *'upstream=-'* ]] || fail 'Nginx Actuator request unexpectedly had a Java upstream'
python3 "$script_dir/check_runtime.py"

[[ "$(tr -d '\r\n' </opt/nexus/state/last-successful.txt)" == "$previous_release" ]]
[[ ! -e /opt/nexus/state/rollback-release.txt ]]

completed=1
echo "PASS: $release_name is active but not promoted; V9, loopback 8080/8081, minimal management endpoints, metrics, Nginx TLS, and explicit Actuator denial passed."
echo "BACKUP_DIR=$backup_dir"
echo 'Next: run 03-accept-v9-obs1.py, then 04-promote-release.sh only after acceptance passes.'
