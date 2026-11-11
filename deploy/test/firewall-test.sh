#!/usr/bin/env bash
# Tests deploy/setup-firewall.sh with a stand-in ufw that only records what it is asked to do. No root needed.
#   bash deploy/test/firewall-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../setup-firewall.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

failures=0
check() {   # check "description" command...
    if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
fails() { ! "$@"; }

# The stand-in ufw: writes each call to $T/calls, one per line; "status" says the firewall is on.
mkdir "$T/bin"
cat > "$T/bin/ufw" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$UFW_CALLS"
[[ $1 == status ]] && echo "Status: active"
exit 0
EOF
chmod +x "$T/bin/ufw"

run() {   # runs the script with the stand-in ufw; extra NAME=value settings as arguments
    : > "$T/calls"
    env PATH="$T/bin:$PATH" UFW_CALLS="$T/calls" FIREWALL_TEST=1 "$@" bash "$SCRIPT" > "$T/log" 2>&1
}
line() { grep -n -F -x -- "$1" "$T/calls" | head -n 1 | cut -d: -f1; }
first() { grep -n -E -- "$1" "$T/calls" | head -n 1 | cut -d: -f1; }
last() { grep -n -E -- "$1" "$T/calls" | tail -n 1 | cut -d: -f1; }

check "runs" run
check "SSH stays allowed" grep -q -x "allow OpenSSH" "$T/calls"
check "...and is never removed" fails grep -q "delete.*OpenSSH" "$T/calls"
check "SSH is handled before anything else" test "$(line "allow OpenSSH")" = 1
for p in 8080 9090 8123; do
    check "$p is allowed from the home network" \
        grep -q -x "allow from 192.168.4.0/22 to any port $p proto tcp comment .* from the home network" "$T/calls"
    check "$p's open-to-anyone rule is removed" grep -q -x "delete allow $p/tcp" "$T/calls"
done
check "every allow comes before the first delete" test "$(last '^allow')" -lt "$(first '^delete')"
check "Tailscale rules are left alone" fails grep -q "tailscale0" "$T/calls"
check "nothing is denied or reset" fails grep -q -E "^(deny|reject|reset|disable|--force)" "$T/calls"
check "says where to check" grep -q "http://192.168.5.251:8080" "$T/log"

check "the home network can be changed" run LAN=10.0.0.0/24
check "...and is used" grep -q "allow from 10.0.0.0/24 to any port 8123 proto tcp" "$T/calls"

if [[ $EUID -ne 0 ]]; then
    check "refuses to run without sudo" fails env PATH="$T/bin:$PATH" UFW_CALLS="$T/calls" bash "$SCRIPT" 2>/dev/null
fi

echo
if ((failures)); then echo "$failures check(s) failed"; exit 1; fi
echo "All firewall checks passed"
