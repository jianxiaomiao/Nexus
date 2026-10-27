#!/usr/bin/env bash
set -Eeuo pipefail

readonly release_name='V2026.10.27.1__v9-obs1'
readonly previous_release='V2026.10.22.1__ubuntu-initial'
readonly state_file="/opt/nexus/state/deployment-${release_name}.env"
readonly pass_marker="/opt/nexus/state/${release_name}.acceptance-passed"
readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

state_value() {
    local key=$1
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$state_file"
}

atomic_state_write() {
    local target=$1 value=$2 temporary
    temporary=$(mktemp "/opt/nexus/state/.${target##*/}.XXXXXX")
    printf '%s\n' "$value" >"$temporary"
    chown root:root "$temporary"
    chmod 0640 "$temporary"
    mv -Tf -- "$temporary" "$target"
}

if (( EUID != 0 )); then
    fail 'run this script as root'
fi
if (( $# != 0 )); then
    fail "usage: $0"
fi
exec 9>/opt/nexus/state/deployment.lock
flock -n 9 || fail 'another deployment operation is running'
[[ -f "$state_file" ]] || fail "deployment state is missing: $state_file"
[[ -f "$pass_marker" ]] || fail "acceptance marker is missing: $pass_marker"
accepted_pid=$(awk -F= '$1 == "NEXUS_PID" {print $2}' "$pass_marker")
[[ "$accepted_pid" == "$(systemctl show nexus.service -p MainPID --value)" ]] ||
    fail 'Nexus restarted after acceptance; review and rerun acceptance before promotion'
[[ "$(state_value STATUS)" == PENDING ]] || fail 'deployment is not pending'
[[ "$(state_value RELEASE)" == "$release_name" ]] || fail 'deployment state names another release'
[[ "$(state_value PREVIOUS_RELEASE)" == "$previous_release" ]] || fail 'deployment state names another previous release'
[[ "$(readlink -f -- /opt/nexus/current)" == "/opt/nexus/releases/$release_name" ]] || fail 'current is not the candidate release'
last_successful=$(tr -d '\r\n' </opt/nexus/state/last-successful.txt)
[[ "$last_successful" == "$previous_release" || "$last_successful" == "$release_name" ]] ||
    fail 'last-successful contains an unexpected release'
[[ "$(systemctl is-active nexus.service)" == active ]] || fail 'nexus.service is not active'
[[ "$(systemctl is-active nginx.service)" == active ]] || fail 'nginx.service is not active'
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]] || fail 'nexus.service must remain disabled'
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]] || fail 'nginx.service must remain disabled'
python3 "$script_dir/check_runtime.py"

curl --noproxy '*' --silent --show-error --fail \
    http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"'
[[ "$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e "SELECT CONCAT(version, ':', success) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")" == '9:1' ]]
[[ "$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e 'SELECT COUNT(*) FROM screenshot_jobs')" == 0 ]] || fail 'acceptance screenshot rows were not cleaned'
[[ "$(mysql --protocol=socket --batch --skip-column-names nexus \
    -e "SELECT COUNT(*) FROM users WHERE email LIKE 'nexus-v9-obs1-accept-%@example.test'")" == 0 ]] ||
    fail 'acceptance users were not cleaned'
[[ -z "$(find /var/lib/nexus/screenshot-jobs -mindepth 1 -maxdepth 1 -type f -print -quit)" ]] ||
    fail 'the screenshot result directory is not empty after acceptance'

if [[ -f /opt/nexus/state/rollback-release.txt ]]; then
    [[ "$(tr -d '\r\n' </opt/nexus/state/rollback-release.txt)" == "$previous_release" ]] ||
        fail 'rollback-release contains an unexpected value'
else
    atomic_state_write /opt/nexus/state/rollback-release.txt "$previous_release"
fi
atomic_state_write /opt/nexus/state/last-successful.txt "$release_name"

backup_dir=$(state_value BACKUP_DIR)
started_at=$(state_value STARTED_AT_UTC)
promoted_at=$(date -u +%Y%m%dT%H%M%SZ)
temporary=$(mktemp "/opt/nexus/state/.deployment-${release_name}.XXXXXX")
cat >"$temporary" <<STATE
STATUS=PROMOTED
RELEASE=$release_name
PREVIOUS_RELEASE=$previous_release
BACKUP_DIR=$backup_dir
STARTED_AT_UTC=$started_at
PROMOTED_AT_UTC=$promoted_at
STATE
chown root:root "$temporary"
chmod 0600 "$temporary"
mv -Tf -- "$temporary" "$state_file"

[[ "$(systemctl show nexus.service --property MainPID --value)" -gt 0 ]]
[[ "$(systemctl show nginx.service --property MainPID --value)" -gt 0 ]]
echo "PASS: $release_name promoted without restarting services; rollback-release=$previous_release; last-successful=$release_name."
