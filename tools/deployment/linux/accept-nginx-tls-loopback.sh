#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
    echo 'Run this script as root.' >&2
    exit 1
fi
if (( $# != 1 )); then
    echo "Usage: $0 /path/to/nginx-tls-loopback.conf" >&2
    exit 1
fi

source_config=$(readlink -f -- "$1")
site_config=/etc/nginx/sites-available/nexus-loopback
backup_config=/etc/nginx/sites-available/nexus-loopback.before-tls-lab
tls_root=/etc/nginx/tls-lab
release_root=$(readlink -f -- /opt/nexus/current)
dist_root="$release_root/console/dist"
tmp_root=$(mktemp -d /var/tmp/nexus-tls-loopback.XXXXXX)
config_installed=0
passed=0

cleanup() {
    local exit_code=$?

    if (( passed == 0 && config_installed == 1 )); then
        echo 'TLS acceptance failed; restoring the previous HTTP-only Nginx configuration.' >&2
        install -o root -g root -m 0644 -- "$backup_config" "$site_config"
        if nginx -t >/dev/null 2>&1; then
            systemctl reload nginx.service || true
        else
            echo 'The restored Nginx configuration did not pass nginx -t.' >&2
        fi
        rm -rf -- "$tls_root"
    fi

    rm -rf -- "$tmp_root"
    exit "$exit_code"
}
trap cleanup EXIT

[[ -f "$source_config" ]]
[[ -f "$site_config" ]]
[[ ! -e "$backup_config" ]]
[[ ! -e "$tls_root" ]]
[[ "$release_root" == /opt/nexus/releases/* ]]
[[ -f "$dist_root/index.html" ]]
command -v openssl >/dev/null
[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]

if ss -H -lntp | awk '$4 ~ /:(80|443|18443)$/ { found=1 } END { exit !found }'; then
    echo 'Port 80, 443, or 18443 is already listening; no configuration was changed.' >&2
    ss -H -lntp | awk '$4 ~ /:(80|443|18443)$/ { print }' >&2
    exit 1
fi
if ! ss -H -lntp 'sport = :18080' | grep -q '127.0.0.1:18080'; then
    echo 'The verified loopback Nginx listener on 127.0.0.1:18080 is missing.' >&2
    exit 1
fi

nexus_pid_before=$(systemctl show nexus.service --property MainPID --value)
nginx_pid_before=$(systemctl show nginx.service --property MainPID --value)

cat >"$tmp_root/ca.cnf" <<'OPENSSL'
[req]
distinguished_name = subject
x509_extensions = ca_extensions
prompt = no

[subject]
CN = Nexus Local Lab CA

[ca_extensions]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
OPENSSL

cat >"$tmp_root/server.cnf" <<'OPENSSL'
[req]
distinguished_name = subject
req_extensions = request_extensions
prompt = no

[subject]
CN = nexus.test

[request_extensions]
subjectAltName = @alternative_names

[alternative_names]
DNS.1 = nexus.test

[server_extensions]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alternative_names
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
OPENSSL

openssl req -x509 -newkey rsa:2048 -sha256 -nodes \
    -days 365 -config "$tmp_root/ca.cnf" \
    -keyout "$tmp_root/ca.key" -out "$tmp_root/ca.crt"
openssl req -new -newkey rsa:2048 -sha256 -nodes \
    -config "$tmp_root/server.cnf" \
    -keyout "$tmp_root/nexus.test.key" -out "$tmp_root/nexus.test.csr"
openssl x509 -req -sha256 -days 30 \
    -in "$tmp_root/nexus.test.csr" \
    -CA "$tmp_root/ca.crt" -CAkey "$tmp_root/ca.key" -CAcreateserial \
    -extfile "$tmp_root/server.cnf" -extensions server_extensions \
    -out "$tmp_root/nexus.test.crt"

openssl verify -CAfile "$tmp_root/ca.crt" "$tmp_root/nexus.test.crt"
openssl x509 -in "$tmp_root/nexus.test.crt" -noout -checkend 1209600
openssl x509 -in "$tmp_root/nexus.test.crt" -noout -ext subjectAltName |
    grep -q 'DNS:nexus.test'

install -o root -g root -m 0600 "$site_config" "$backup_config"
config_installed=1
install -o root -g root -m 0750 -d "$tls_root"
install -o root -g root -m 0600 "$tmp_root/ca.key" "$tls_root/ca.key"
install -o root -g root -m 0644 "$tmp_root/ca.crt" "$tls_root/ca.crt"
install -o root -g root -m 0600 "$tmp_root/nexus.test.key" "$tls_root/nexus.test.key"
install -o root -g root -m 0644 "$tmp_root/nexus.test.crt" "$tls_root/nexus.test.crt"
install -o root -g root -m 0644 "$source_config" "$site_config"

nginx -t
nginx -T >"$tmp_root/effective-nginx.conf" 2>&1
mapfile -t listen_lines < <(
    awk '/^[[:space:]]*listen[[:space:]]/ {
        sub(/^[[:space:]]*/, ""); print
    }' "$tmp_root/effective-nginx.conf"
)
if (( ${#listen_lines[@]} != 2 )) ||
        [[ " ${listen_lines[*]} " != *' listen 127.0.0.1:18080; '* ]] ||
        [[ " ${listen_lines[*]} " != *' listen 127.0.0.1:18443 ssl; '* ]]; then
    printf 'Unexpected effective listen directives:\n%s\n' "${listen_lines[*]:-<none>}" >&2
    exit 1
fi

systemctl reload nginx.service
for _ in {1..50}; do
    if ss -H -lntp 'sport = :18443' | grep -q '127.0.0.1:18443'; then
        break
    fi
    sleep 0.1
done

[[ "$(systemctl is-active nginx.service)" == active ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nginx.service --property MainPID --value)" == "$nginx_pid_before" ]]
[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nexus.service --property MainPID --value)" == "$nexus_pid_before" ]]

if ss -H -lntp | awk '$4 ~ /:(80|443)$/ { found=1 } END { exit !found }'; then
    echo 'Public port 80 or 443 unexpectedly started listening.' >&2
    exit 1
fi
mapfile -t lab_listeners < <(ss -H -lntp | awk '$4 ~ /:(18080|18443)$/ { print }')
if (( ${#lab_listeners[@]} != 2 )) ||
        [[ " ${lab_listeners[*]} " != *'127.0.0.1:18080'* ]] ||
        [[ " ${lab_listeners[*]} " != *'127.0.0.1:18443'* ]]; then
    printf 'Unexpected lab listeners:\n%s\n' "${lab_listeners[*]:-<none>}" >&2
    exit 1
fi

untrusted_code=0
curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    https://nexus.test:18443/ -o "$tmp_root/untrusted.html" \
    2>"$tmp_root/untrusted.error" || untrusted_code=$?
[[ "$untrusted_code" == 60 ]]

wrong_host_code=0
curl --noproxy '*' --silent --show-error --fail \
    --cacert "$tls_root/ca.crt" \
    https://127.0.0.1:18443/ -o "$tmp_root/wrong-host.html" \
    2>"$tmp_root/wrong-host.error" || wrong_host_code=$?
[[ "$wrong_host_code" == 60 ]]

curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert "$tls_root/ca.crt" \
    https://nexus.test:18443/ -o "$tmp_root/index.html"
cmp -- "$dist_root/index.html" "$tmp_root/index.html"

curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert "$tls_root/ca.crt" \
    https://nexus.test:18443/applications -o "$tmp_root/spa.html"
cmp -- "$dist_root/index.html" "$tmp_root/spa.html"

asset_path=$(grep -oE '(src|href)="/assets/[^"]+"' "$dist_root/index.html" |
    sed -n '1{s/^[^=]*="//;s/"$//;p;}')
[[ -n "$asset_path" ]]
curl --noproxy '*' --silent --show-error --fail \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert "$tls_root/ca.crt" \
    "https://nexus.test:18443$asset_path" -o "$tmp_root/asset"
cmp -- "$dist_root$asset_path" "$tmp_root/asset"

curl --noproxy '*' --silent --show-error \
    -H 'Host: nexus.test' \
    -D "$tmp_root/redirect.headers" -o /dev/null \
    'http://127.0.0.1:18080/applications?source=tls-lab'
grep -q '^HTTP/1.1 308 ' "$tmp_root/redirect.headers"
grep -qi '^Location: https://nexus.test:18443/applications?source=tls-lab' \
    "$tmp_root/redirect.headers"

proxy_status=$(curl --noproxy '*' --silent --show-error \
    --resolve nexus.test:18443:127.0.0.1 \
    --cacert "$tls_root/ca.crt" \
    -H 'Authorization: ApiKey tls-loopback-invalid' \
    -o "$tmp_root/proxy.json" -w '%{http_code}' \
    https://nexus.test:18443/v1/utils/uuid)
[[ "$proxy_status" == 401 ]]
grep -q '"code":"INVALID_API_KEY_CREDENTIAL"' "$tmp_root/proxy.json"

if ufw status | grep -Eq '(^|[[:space:]])(80|443|18080|18443)(/tcp)?[[:space:]]+ALLOW'; then
    echo 'UFW unexpectedly allows a lab or public web port.' >&2
    ufw status >&2
    exit 1
fi

passed=1
echo "PASS: HTTP 127.0.0.1:18080 redirects to locally trusted HTTPS nexus.test:18443; unknown CA and wrong-host checks both failed safely; Vue, SPA, asset, and Java proxy passed; Nexus PID=$nexus_pid_before and Nginx PID=$nginx_pid_before remained active/disabled; public 80/443 stayed closed."
