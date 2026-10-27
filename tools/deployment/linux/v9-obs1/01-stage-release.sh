#!/usr/bin/env bash
set -Eeuo pipefail

readonly release_name='V2026.10.27.1__v9-obs1'
readonly expected_current='V2026.10.22.1__ubuntu-initial'
readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

if (( EUID != 0 )); then
    fail 'run this script as root'
fi
if (( $# < 1 || $# > 2 )); then
    fail "usage: $0 /var/tmp/${release_name}.tar.gz [/path/to/release.sha256]"
fi
exec 9>/opt/nexus/state/deployment.lock
flock -n 9 || fail 'another deployment operation is running'

archive=$(readlink -f -- "$1")
checksum_file=${2:-"$archive.sha256"}
checksum_file=$(readlink -f -- "$checksum_file")
release_root="/opt/nexus/releases/$release_name"
current_root=$(readlink -f -- /opt/nexus/current)
last_successful=$(tr -d '\r\n' </opt/nexus/state/last-successful.txt)

[[ -f "$archive" ]] || fail "release archive is missing: $archive"
[[ -f "$checksum_file" ]] || fail "checksum file is missing: $checksum_file"
[[ "$current_root" == "/opt/nexus/releases/$expected_current" ]] ||
    fail "unexpected current release: $current_root"
[[ "$last_successful" == "$expected_current" ]] ||
    fail "unexpected last-successful release: $last_successful"
[[ "$(systemctl is-active nexus.service)" == active ]] || fail 'nexus.service is not active'
[[ "$(systemctl is-active nginx.service)" == active ]] || fail 'nginx.service is not active'
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]] ||
    fail 'nexus.service must remain disabled'
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]] ||
    fail 'nginx.service must remain disabled'

expected_hash=$(awk 'NF {print toupper($1); exit}' "$checksum_file")
[[ "$expected_hash" =~ ^[0-9A-F]{64}$ ]] || fail 'checksum file does not begin with a SHA-256 value'
actual_hash=$(sha256sum -- "$archive" | awk '{print toupper($1)}')
[[ "$actual_hash" == "$expected_hash" ]] ||
    fail "archive SHA-256 mismatch: expected=$expected_hash actual=$actual_hash"

command -v python3 >/dev/null || fail 'python3 is required'
command -v setfacl >/dev/null || fail 'setfacl is required'
python3 "$script_dir/verify_release.py" --archive "$archive" --release "$release_name" >/dev/null

if [[ -e "$release_root" ]]; then
    python3 "$script_dir/verify_release.py" --directory "$release_root" --release "$release_name"
    echo 'Candidate release already exists and passed integrity validation; it was not overwritten.'
else
    stage_root=$(mktemp -d "/opt/nexus/releases/.${release_name}.stage.XXXXXX")
    case "$stage_root" in
        /opt/nexus/releases/.${release_name}.stage.*) ;;
        *) fail "unsafe staging directory: $stage_root" ;;
    esac
    cleanup_stage() {
        if [[ -d "$stage_root" ]]; then
            rm -rf -- "$stage_root"
        fi
    }
    trap cleanup_stage EXIT

    extract_root="$stage_root/extracted"
    python3 "$script_dir/verify_release.py" \
        --archive "$archive" \
        --release "$release_name" \
        --extract-to "$extract_root"

    candidate="$extract_root/$release_name"
    chown -R root:nexus-readers "$candidate"
    find "$candidate" -type d -exec chmod 0550 {} +
    find "$candidate" -type f -exec chmod 0440 {} +
    mv -- "$candidate" "$release_root"
    cleanup_stage
    trap - EXIT
fi

# Nginx receives traversal only on release parents and read access only to the
# new console subtree. It is not added to nexus-readers.
setfacl -m u:www-data:--x /opt/nexus /opt/nexus/releases "$release_root" "$release_root/console"
find "$release_root/console/dist" -type d -exec setfacl -m u:www-data:r-x {} +
find "$release_root/console/dist" -type f -exec setfacl -m u:www-data:r-- {} +

install -o nexus -g nexus -m 0750 -d /var/lib/nexus/screenshot-jobs

python3 "$script_dir/verify_release.py" --directory "$release_root" --release "$release_name"
sudo -u nexus test -r "$release_root/server/nexus-server.jar"
sudo -u nexus test ! -w "$release_root/server/nexus-server.jar"
sudo -u www-data test -r "$release_root/console/dist/index.html"
sudo -u www-data test ! -r "$release_root/server/nexus-server.jar"
sudo -u nexus test -x \
    /opt/nexus/browsers/playwright-1.63.0/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell

[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(readlink -f -- /opt/nexus/current)" == "/opt/nexus/releases/$expected_current" ]]

echo "PASS: $release_name staged read-only; archive SHA-256=$actual_hash; current remains $expected_current; services were not restarted."
