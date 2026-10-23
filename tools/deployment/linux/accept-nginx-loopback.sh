#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
    echo "Run this script as root." >&2
    exit 1
fi
if (( $# != 1 )); then
    echo "Usage: $0 /path/to/nginx-loopback.conf" >&2
    exit 1
fi

source_config=$(readlink -f -- "$1")
site_available=/etc/nginx/sites-available/nexus-loopback
site_enabled=/etc/nginx/sites-enabled/nexus-loopback
release_root=$(readlink -f -- /opt/nexus/current)
dist_root="$release_root/console/dist"
tmp_root=$(mktemp -d /var/tmp/nexus-nginx-accept.XXXXXX)

cleanup() {
    rm -rf -- "$tmp_root"
}
trap cleanup EXIT

[[ -f "$source_config" ]]
[[ "$release_root" == /opt/nexus/releases/* ]]
[[ -f "$dist_root/index.html" ]]
dpkg-query -W -f='${Status}' nginx | grep -qx 'install ok installed'
command -v setfacl >/dev/null
[[ "$(systemctl is-active nginx.service)" == inactive ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]

if ss -H -lntp | awk '$4 ~ /:(80|443|18080)$/ { found=1 } END { exit !found }'; then
    echo 'A protected port is already listening; no Nginx configuration was changed.' >&2
    ss -H -lntp | awk '$4 ~ /:(80|443|18080)$/ { print }' >&2
    exit 1
fi

nexus_pid_before=$(systemctl show nexus.service --property MainPID --value)

# Grant the Nginx worker only traversal on release parents and read access to
# the console subtree. It does not join nexus-readers and cannot read the JAR
# or fixed browser tree through these ACL entries.
setfacl -m u:www-data:--x /opt/nexus /opt/nexus/releases "$release_root" "$release_root/console"
find "$dist_root" -type d -exec setfacl -m u:www-data:r-x {} +
find "$dist_root" -type f -exec setfacl -m u:www-data:r-- {} +

install -o root -g root -m 0644 -- "$source_config" "$site_available"
ln -sfn ../sites-available/nexus-loopback "$site_enabled"
rm -f -- /etc/nginx/sites-enabled/default

# Define the log format once at http scope; the server file only selects it.
log_format_path=/etc/nginx/conf.d/00-nexus-log-format.conf
cat >"$log_format_path" <<'NGINX'
log_format nexus_loopback '$time_iso8601 peer=$remote_addr:$remote_port '
                          'request="$request" status=$status bytes=$body_bytes_sent '
                          'upstream=$upstream_addr upstream_status=$upstream_status '
                          'request_time=$request_time upstream_time=$upstream_response_time';
NGINX
chown root:root "$log_format_path"
chmod 0644 "$log_format_path"

nginx -t
nginx -T >"$tmp_root/effective-nginx.conf" 2>&1
mapfile -t listen_lines < <(
    awk '/^[[:space:]]*listen[[:space:]]/ {
        sub(/^[[:space:]]*/, ""); print
    }' "$tmp_root/effective-nginx.conf"
)
if (( ${#listen_lines[@]} != 1 )) ||
        [[ "${listen_lines[0]}" != 'listen 127.0.0.1:18080;' ]]; then
    printf 'Unexpected effective listen directives:\n%s\n' "${listen_lines[*]:-<none>}" >&2
    exit 1
fi

systemctl start nginx.service
for _ in {1..50}; do
    if ss -H -lntp 'sport = :18080' | grep -q '127.0.0.1:18080'; then
        break
    fi
    sleep 0.1
done

[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nexus.service --property MainPID --value)" == "$nexus_pid_before" ]]

mapfile -t protected_listeners < <(ss -H -lntp | awk '$4 ~ /:(80|443|18080)$/ { print }')
if (( ${#protected_listeners[@]} != 1 )) ||
        [[ "${protected_listeners[0]}" != *'127.0.0.1:18080'* ]]; then
    printf 'Unexpected protected listeners:\n%s\n' "${protected_listeners[*]:-<none>}" >&2
    exit 1
fi

curl -fsS -H 'Host: localhost' http://127.0.0.1:18080/ -o "$tmp_root/index.html"
cmp -- "$dist_root/index.html" "$tmp_root/index.html"
curl -fsS -H 'Host: localhost' http://127.0.0.1:18080/applications -o "$tmp_root/spa.html"
cmp -- "$dist_root/index.html" "$tmp_root/spa.html"

asset_path=$(grep -oE '(src|href)="/assets/[^"]+"' "$dist_root/index.html" |
    sed -n '1{s/^[^=]*="//;s/"$//;p;}')
[[ -n "$asset_path" ]]
[[ -f "$dist_root$asset_path" ]]
curl -fsS -H 'Host: localhost' "http://127.0.0.1:18080$asset_path" -o "$tmp_root/asset"
cmp -- "$dist_root$asset_path" "$tmp_root/asset"

direct_status=$(curl -sS -o "$tmp_root/direct.json" -w '%{http_code}' \
    -H 'Authorization: ApiKey nginx-loopback-invalid' \
    http://127.0.0.1:8080/v1/utils/uuid)
proxy_status=$(curl -sS -o "$tmp_root/proxy.json" -w '%{http_code}' \
    -H 'Host: localhost' \
    -H 'Authorization: ApiKey nginx-loopback-invalid' \
    http://127.0.0.1:18080/v1/utils/uuid)
[[ "$direct_status" == 401 ]]
[[ "$proxy_status" == 401 ]]
grep -q '"code":"INVALID_API_KEY_CREDENTIAL"' "$tmp_root/direct.json"
grep -q '"code":"INVALID_API_KEY_CREDENTIAL"' "$tmp_root/proxy.json"

if ufw status | grep -Eq '(^|[[:space:]])(80|443|18080)(/tcp)?[[:space:]]+ALLOW'; then
    echo 'UFW unexpectedly allows a protected web port.' >&2
    ufw status >&2
    exit 1
fi

echo "PASS: Nexus PID=$nexus_pid_before and Nginx are active/disabled; only 127.0.0.1:18080 is listening; Vue index, SPA fallback, asset bytes, and Java proxy 401 were verified; UFW has no 80/443/18080 allow rule."
