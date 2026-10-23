#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
    echo "Run this script as root." >&2
    exit 1
fi

policy_path=/usr/sbin/policy-rc.d
policy_marker='# nexus-temporary-nginx-install-policy'
policy_installed=0

protected_listeners() {
    ss -H -lntp | awk '$4 ~ /:(80|443|18080)$/ { print }'
}

cleanup() {
    local exit_code=$?

    if dpkg-query -W -f='${Status}' nginx 2>/dev/null |
            grep -qx 'install ok installed'; then
        systemctl disable --now nginx.service >/dev/null 2>&1 || true
    fi

    if (( policy_installed == 1 )) &&
            [[ -f "$policy_path" ]] &&
            grep -qxF "$policy_marker" "$policy_path"; then
        rm -f -- "$policy_path"
    fi

    exit "$exit_code"
}
trap cleanup EXIT

if [[ -e "$policy_path" ]]; then
    echo "Refusing to replace the existing $policy_path; inspect its policy first." >&2
    exit 1
fi

if dpkg-query -W -f='${Status}' nginx 2>/dev/null |
        grep -qx 'install ok installed'; then
    echo 'Nginx is already installed; this first-install experiment cannot prove that APT did not start it.' >&2
    exit 1
fi

if [[ -n "$(protected_listeners)" ]]; then
    echo 'A protected port is already listening; no package was installed.' >&2
    protected_listeners >&2
    exit 1
fi

[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]
nexus_pid_before=$(systemctl show nexus.service --property MainPID --value)

cat >"$policy_path" <<'POLICY'
#!/bin/sh
# nexus-temporary-nginx-install-policy
case "${1:-}:${2:-}" in
    nginx:start|nginx:restart|nginx:try-restart|nginx:reload|nginx:force-reload|\
    nginx.service:start|nginx.service:restart|nginx.service:try-restart|\
    nginx.service:reload|nginx.service:force-reload)
        exit 101
        ;;
    *)
        exit 0
        ;;
esac
POLICY
chmod 0755 "$policy_path"
chown root:root "$policy_path"
policy_installed=1

DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nginx acl

# The package may enable its unit even though policy-rc.d denied its start.
# Remove boot activation before removing the temporary installation policy.
systemctl disable --now nginx.service

if [[ -L /etc/nginx/sites-enabled/default ]]; then
    rm -- /etc/nginx/sites-enabled/default
fi

rm -- "$policy_path"
policy_installed=0
trap - EXIT

[[ "$(systemctl is-active nginx.service)" == inactive ]]
[[ "$(systemctl is-enabled nginx.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl is-active nexus.service)" == active ]]
[[ "$(systemctl is-enabled nexus.service 2>/dev/null || true)" == disabled ]]
[[ "$(systemctl show nexus.service --property MainPID --value)" == "$nexus_pid_before" ]]

if [[ -n "$(protected_listeners)" ]]; then
    echo 'A protected listener appeared during package installation.' >&2
    protected_listeners >&2
    exit 1
fi

echo "PASS: nginx=$(nginx -v 2>&1) remained inactive/disabled; Nexus PID=$nexus_pid_before remained active/disabled; ports 80/443/18080 stayed unused."
