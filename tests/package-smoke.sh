#!/usr/bin/env bash
# Exercise a built release tar.gz in a fresh directory (the path contains a space).
# Does not use Maven. Expects the standalone archive (bundled Java), not the slim one.
#   tests/package-smoke.sh dist/swift-sim-*-linux-x86_64.tar.gz
set -euo pipefail

TAR=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
[[ -f "$TAR" ]] || { echo "missing $TAR" >&2; exit 1; }

fail() { echo "FAIL $*" >&2; exit 1; }
pass() { echo "PASS $*"; }

WORKDIR=$(mktemp -d /tmp/swift-sim-pkg.XXXXXX)
DEST="$WORKDIR/dir with spaces"
mkdir -p "$DEST"
tar -xzf "$TAR" -C "$DEST"
PKG="$DEST/swift-sim"
[[ -d "$PKG" ]] || fail "archive layout"
[[ -x "$PKG/run.sh" && -x "$PKG/stop.sh" ]] || fail "scripts not executable"
[[ -x "$PKG/runtime/bin/java" ]] || fail "bundled java missing"
[[ -f "$PKG/lib/swift-sim.jar" ]] || fail "jar missing"
[[ -f "$PKG/config/scenarios.yml" && -f "$PKG/config/participants.yml" ]] || fail "config missing"
[[ -d "$PKG/samples" && -d "$PKG/config/templates" ]] || fail "samples or templates missing"
[[ -f "$PKG/使用说明.txt" && -f "$PKG/docs/file-transport-zh.md" ]] || fail "chinese docs missing"
[[ ! -e "$PKG/config/secrets" ]] || fail "archive shipped secrets"
[[ ! -e "$PKG/data" ]] || fail "archive shipped data"
if tar -tzf "$TAR" | grep -E '(^|/)(config/secrets|data/|target/|\.m2/)'; then
  fail "archive contains secrets, data, target, or a maven cache"
fi

FAKE="$WORKDIR/fake-bin"
mkdir -p "$FAKE"
cat > "$FAKE/java" <<'EOF'
#!/bin/sh
echo "system java must not be used" >&2
exit 97
EOF
chmod +x "$FAKE/java"
export PATH="$FAKE:/usr/bin:/bin"
unset JAVA_HOME || true

mkdir -p "$PKG/data"
echo 99999999 > "$PKG/data/app.pid"

# Start from a different working directory, with a broken java first on PATH.
( cd /tmp && "$PKG/run.sh" )
pid=$(tr -d '[:space:]' < "$PKG/data/app.pid")
kill -0 "$pid" || fail "pid not alive"
exe=$(readlink -f "/proc/$pid/exe")
case "$exe" in
  "$PKG/runtime"/*) pass "process uses bundled runtime ($exe)" ;;
  *) fail "process java is $exe, not the bundled runtime" ;;
esac

listen_addrs() {
  local port=$1 hex
  hex=$(printf '%04X' "$port")
  awk -v suf=":${hex}" 'NR>1 && $4=="0A" && substr($2, length($2)-length(suf)+1)==suf { print $2 }' \
    /proc/net/tcp /proc/net/tcp6 2>/dev/null || true
}
listen_ok() {
  local port=$1 hex lines
  hex=$(printf '%04X' "$port")
  lines=$(listen_addrs "$port")
  [[ -n "$lines" ]] || fail "nothing listens on $port"
  printf '%s\n' "$lines" | grep -Eq "^(00000000|00000000000000000000000000000000):${hex}$" \
    || fail "port $port is not a wildcard bind ($lines)"
  pass "wildcard:${port} ($lines)"
}
listen_ok 8080
listen_ok 8081
listen_ok 8082

code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/participants)
[[ "$code" == "200" ]] || fail "gateway HTTP $code"
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/admin/api/me)
[[ "$code" == "401" ]] || fail "admin without login HTTP $code"
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/ai/v1/messages)
[[ "$code" == "401" ]] || fail "AI API without key HTTP $code"
pass "gateway 200, admin 401, AI 401"

pid2=$pid
( cd / && "$PKG/run.sh" )
pid_again=$(tr -d '[:space:]' < "$PKG/data/app.pid")
[[ "$pid_again" == "$pid2" ]] || fail "second start changed pid $pid2 -> $pid_again"
pass "second start did not spawn another process"

# Persist a folder-transport setting and a gateway record, then restart.
props="$PKG/config/secrets/admin-console.properties"
[[ -f "$props" ]] || fail "credentials were not generated"
password=$(awk -F= '/^password=/{print substr($0, index($0,"=")+1)}' "$props")
login=$(mktemp)
curl -s -c "$login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${password}\"}" \
  http://127.0.0.1:8081/admin/api/login | grep -q '"user"' || fail "admin login"
kenex="$WORKDIR/pay-root"
curl -s -b "$login" -H 'X-Sim-Admin: 1' -H 'Content-Type: application/json' \
  -d "{\"mode\":\"BOTH\",\"root\":\"${kenex}\",\"stableMs\":0}" \
  http://127.0.0.1:8081/admin/api/transport | grep -q '"mode":"BOTH"' || fail "save transport"
nak=$(curl -s -o /tmp/swift-sim-nak.json -w '%{http_code}' -X POST --data 'not-a-swift-message' http://127.0.0.1:8080/api/messages)
[[ "$nak" == "422" ]] || fail "expected NAK 422, got $nak"
msg_id=$(python3 -c 'import json; print(json.load(open("/tmp/swift-sim-nak.json"))["id"])')
db_before=$(stat -c '%s' "$PKG/data/db/swiftsim.mv.db")

( cd /tmp && "$PKG/stop.sh" )
[[ ! -f "$PKG/data/app.pid" ]] || fail "pid file left behind"
( cd /tmp && "$PKG/stop.sh" )
pass "repeat stop is harmless"
[[ -f "$PKG/data/file-transport.properties" ]] || fail "file-transport settings missing after stop"
( cd / && "$PKG/run.sh" )
newpid=$(tr -d '[:space:]' < "$PKG/data/app.pid")
[[ "$newpid" != "$pid2" ]] || fail "restart did not get a new pid"
password2=$(awk -F= '/^password=/{print substr($0, index($0,"=")+1)}' "$props")
[[ "$password2" == "$password" ]] || fail "admin password changed across restart"
curl -s -c "$login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${password}\"}" \
  http://127.0.0.1:8081/admin/api/login >/dev/null
curl -s -b "$login" http://127.0.0.1:8081/admin/api/transport | grep -q '"mode":"BOTH"' || fail "transport mode not restored"
curl -s -b "$login" http://127.0.0.1:8081/admin/api/transport | grep -q "$kenex" || fail "transport root not restored"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8080/api/messages/${msg_id}")
[[ "$code" == "200" ]] || fail "message $msg_id missing after restart ($code)"
[[ -f "$PKG/data/db/swiftsim.mv.db" ]] || fail "database missing"
pass "restart kept database, credentials, and file-transport settings (db bytes before stop $db_before)"

( cd /tmp && "$PKG/stop.sh" )

# Stale pid is replaced by a real start. Wrong live pid is not killed.
echo 99999999 > "$PKG/data/app.pid"
( cd /tmp && "$PKG/run.sh" --server.port=18180 --sim.admin.port=18181 --sim.ai.port=18182 )
stale_pid=$(tr -d '[:space:]' < "$PKG/data/app.pid")
[[ "$stale_pid" != "99999999" ]] || fail "stale pid was kept"
kill -0 "$stale_pid" || fail "stale-pid start did not run"
pass "stale pid replaced"
( cd /tmp && "$PKG/stop.sh" )

sleep 300 &
foreign=$!
echo "$foreign" > "$PKG/data/app.pid"
set +e
( cd /tmp && "$PKG/stop.sh" )
set -e
kill -0 "$foreign" || fail "stop.sh killed an unrelated process"
[[ $(tr -d '[:space:]' < "$PKG/data/app.pid") == "$foreign" ]] || fail "foreign pid file was removed"
pass "wrong pid left untouched"
kill "$foreign" 2>/dev/null || true
wait "$foreign" 2>/dev/null || true
rm -f "$PKG/data/app.pid"

python3 -m http.server 19080 --bind 127.0.0.1 >/tmp/swift-sim-port-holder.log 2>&1 &
holder=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do
  listen_addrs 19080 | grep -q . && break
  sleep 0.2
done
set +e
( cd /tmp && "$PKG/run.sh" --server.port=19080 --sim.admin.port=19081 --sim.ai.port=19082 )
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "start succeeded while 19080 was taken"
kill -0 "$holder" || fail "port holder died"
if [[ -f "$PKG/data/app.pid" ]]; then
  maybe=$(tr -d '[:space:]' < "$PKG/data/app.pid")
  if [[ -n "$maybe" ]] && kill -0 "$maybe" 2>/dev/null; then
    fail "simulator stayed up after a port conflict"
  fi
fi
pass "port conflict refused without leaving a simulator running"
kill "$holder" 2>/dev/null || true
wait "$holder" 2>/dev/null || true

( cd / && "$PKG/run.sh" --server.port=18110 --sim.admin.port=18111 --sim.ai.port=18112 \
  --server.address=127.0.0.1 --sim.admin.address=127.0.0.1 --sim.ai.address=127.0.0.1 )
for p in 18110 18111 18112; do
  hex=$(printf '%04X' "$p")
  lines=$(listen_addrs "$p")
  printf '%s\n' "$lines" | grep -q "0100007F:${hex}" || fail "override did not bind 127.0.0.1:$p ($lines)"
  printf '%s\n' "$lines" | grep -q "^00000000:${hex}$" && fail "override still bound 0.0.0.0:$p"
done
pass "address and port overrides applied"
( cd /tmp && "$PKG/stop.sh" )
( cd /tmp && "$PKG/stop.sh" )
pass "package smoke finished"
echo "workdir left at $WORKDIR (stopped)"
