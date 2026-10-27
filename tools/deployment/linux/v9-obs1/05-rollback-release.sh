#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly release_name='V2026.10.27.1__v9-obs1'
readonly previous_release='V2026.10.22.1__ubuntu-initial'
readonly state_file="/opt/nexus/state/deployment-${release_name}.env"
readonly pass_marker="/opt/nexus/state/${release_name}.acceptance-passed"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

state_value() {
    local key=$1
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$state_file"
}

if (( EUID != 0 )); then
    fail 'run this script as root'
fi
if [[ "${1:-}" != '--restore-v8' || $# != 1 ]]; then
    fail "destructive database restore requires: $0 --restore-v8"
fi
exec 9>/opt/nexus/state/deployment.lock
flock -n 9 || fail 'another deployment operation is running'
[[ -f "$state_file" ]] || fail "deployment state is missing: $state_file"
[[ "$(state_value STATUS)" == PENDING ]] || fail 'rollback is allowed only before promotion while STATUS=PENDING'
[[ "$(state_value RELEASE)" == "$release_name" ]] || fail 'deployment state names another release'
[[ "$(state_value PREVIOUS_RELEASE)" == "$previous_release" ]] || fail 'deployment state names another previous release'

backup_dir=$(state_value BACKUP_DIR)
case "$backup_dir" in
    /opt/nexus/backups/${release_name}-*) ;;
    *) fail "unsafe backup directory in deployment state: $backup_dir" ;;
esac
[[ -d "$backup_dir" ]] || fail "backup directory is missing: $backup_dir"
(cd "$backup_dir" && sha256sum --check SHA256SUMS)
[[ "$(cat "$backup_dir/current-target.txt")" == "/opt/nexus/releases/$previous_release" ]] ||
    fail 'backup points to another previous release'
[[ -d "/opt/nexus/releases/$previous_release" ]] || fail 'previous release directory is missing'
[[ "$(tr -d '\r\n' </opt/nexus/state/last-successful.txt)" == "$previous_release" ]] ||
    fail 'last-successful changed; refusing pre-promotion rollback'
[[ ! -e /opt/nexus/state/rollback-release.txt ]] || fail 'rollback-release exists; refusing pre-promotion rollback'
current_root=$(readlink -f -- /opt/nexus/current)
[[ "$current_root" == "/opt/nexus/releases/$release_name" || "$current_root" == "/opt/nexus/releases/$previous_release" ]] ||
    fail 'current unexpectedly points outside this deployment'

systemctl stop nginx.service || true
systemctl stop nexus.service || true
if ss -H -lntp | awk '$4 ~ /:(8080|8081|18080|18443)$/ {found=1} END {exit !found}'; then
    ss -H -lntp | awk '$4 ~ /:(8080|8081|18080|18443)$/ {print}' >&2
    fail 'a Nexus listener remained after stopping services'
fi

# Preserve the failed V9 database before replacing it with the known V8 dump.
failed_dump="$backup_dir/nexus-v9-failed-$(date -u +%Y%m%dT%H%M%SZ).sql"
mysqldump --protocol=socket --single-transaction --routines --triggers --events \
    --set-gtid-purged=OFF --default-character-set=utf8mb4 --databases nexus \
    >"$failed_dump"
chmod 0600 "$failed_dump"
sha256sum -- "$failed_dump" >"$failed_dump.sha256"
chmod 0600 "$failed_dump.sha256"

cp --preserve=mode,ownership,timestamps /etc/nexus/application-prod.yml \
    "$backup_dir/application-prod.failed-v9.yml"
cp --preserve=mode,ownership,timestamps /etc/nginx/sites-available/nexus-loopback \
    "$backup_dir/nginx-loopback.failed-v9.conf"

mysql --protocol=socket -e 'DROP DATABASE IF EXISTS nexus'
mysql --protocol=socket <"$backup_dir/nexus-v8.sql"

# V8 had no persistent screenshot results. Preserve V9 files as root-only
# evidence outside the runtime tree; do not leave downloadable stale results.
if [[ -d /var/lib/nexus/screenshot-jobs ]]; then
    [[ ! -L /var/lib/nexus/screenshot-jobs ]] || fail 'result directory is unexpectedly a symlink'
    [[ ! -e "$backup_dir/screenshot-jobs.failed-v9" ]] || fail 'result quarantine already exists; inspect interrupted rollback'
    mv -- /var/lib/nexus/screenshot-jobs "$backup_dir/screenshot-jobs.failed-v9"
    chown -R root:root "$backup_dir/screenshot-jobs.failed-v9"
    chmod -R go-rwx "$backup_dir/screenshot-jobs.failed-v9"
    install -o nexus -g nexus -m 0750 -d /var/lib/nexus/screenshot-jobs
fi

latest_migration=$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e "SELECT CONCAT(version, ':', success) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
[[ "$latest_migration" == '8:1' ]] || fail "restored database is not successful V8: $latest_migration"
job_table_count=$(mysql --protocol=socket --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nexus' AND table_name='screenshot_jobs'")
[[ "$job_table_count" == 0 ]] || fail 'restored V8 database still contains screenshot_jobs'

install -o root -g nexus -m 0640 -- "$backup_dir/application-prod.yml" /etc/nexus/application-prod.yml
install -o root -g root -m 0600 -- "$backup_dir/nexus.env" /etc/nexus/nexus.env
install -o root -g root -m 0644 -- "$backup_dir/nginx-loopback.conf" /etc/nginx/sites-available/nexus-loopback
ln -sfn ../sites-available/nexus-loopback /etc/nginx/sites-enabled/nexus-loopback
nginx -t

next_link="/opt/nexus/.current-${previous_release}"
[[ ! -e "$next_link" ]] || fail "temporary current link already exists: $next_link"
ln -s "/opt/nexus/releases/$previous_release" "$next_link"
mv -Tf -- "$next_link" /opt/nexus/current

systemctl reset-failed nexus.service
systemctl start nexus.service
ready=0
for _ in {1..120}; do
    status=$(curl --noproxy '*' --silent --show-error \
        -H 'Authorization: ApiKey rollback-invalid' \
        --output "$backup_dir/rollback-invalid-key.json" --write-out '%{http_code}' \
        http://127.0.0.1:8080/v1/utils/uuid 2>/dev/null || true)
    if [[ "$status" == 401 ]] && grep -q '"code":"INVALID_API_KEY_CREDENTIAL"' "$backup_dir/rollback-invalid-key.json"; then
        ready=1
        break
    fi
    if [[ "$(systemctl is-active nexus.service 2>/dev/null || true)" == failed ]]; then
        break
    fi
    sleep 1
done
(( ready == 1 )) || fail 'restored V8 Nexus did not become ready within 120 seconds'

[[ -z "$(ss -H -lntp 'sport = :8081')" ]] || fail 'management port 8081 remained after V8 rollback'
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]

systemctl reset-failed nginx.service
systemctl start nginx.service
[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert /etc/nginx/tls-lab/ca.crt \
    https://nexus.test:18443/ >"$backup_dir/rollback-index.html"
cmp -- "/opt/nexus/releases/$previous_release/console/dist/index.html" "$backup_dir/rollback-index.html"

if [[ -f "$pass_marker" ]]; then
    mv -- "$pass_marker" "$backup_dir/acceptance-passed.before-rollback"
fi
rolled_back_at=$(date -u +%Y%m%dT%H%M%SZ)
started_at=$(state_value STARTED_AT_UTC)
temporary=$(mktemp "/opt/nexus/state/.deployment-${release_name}.XXXXXX")
cat >"$temporary" <<STATE
STATUS=ROLLED_BACK
RELEASE=$release_name
PREVIOUS_RELEASE=$previous_release
BACKUP_DIR=$backup_dir
STARTED_AT_UTC=$started_at
ROLLED_BACK_AT_UTC=$rolled_back_at
STATE
chown root:root "$temporary"
chmod 0600 "$temporary"
mv -Tf -- "$temporary" "$state_file"

echo "PASS: database, application configuration, Nginx configuration, and current were restored to $previous_release; candidate and failed V9 evidence were retained."
