#!/usr/bin/env bash
# Start the simulator in background (logs -> data/app.log, pid -> data/app.pid):
#   127.0.0.1:8080 gateway + /api + web UI   |   127.0.0.1:8081 admin console   |   127.0.0.1:8082 AI API (/ai/v1, MCP /ai/v1/mcp)
cd "$(dirname "$0")"
JAVA=${JAVA_HOME:-/opt/tools/jdk}/bin/java
[ -x "$JAVA" ] || JAVA=java
mkdir -p data
# Also start the Cloudflare Tunnel (external HTTPS access to 8080 only) unless SWIFT_SIM_TUNNEL=0
if [ "${SWIFT_SIM_TUNNEL:-1}" != "0" ] && [ -x ./tunnel-start.sh ] && [ -r config/secrets/tunnel.token ]; then ./tunnel-start.sh; fi
if [ -f data/app.pid ] && kill -0 "$(cat data/app.pid)" 2>/dev/null; then echo "already running pid $(cat data/app.pid)"; exit 0; fi
nohup "$JAVA" -Xms128m -Xmx512m -jar target/swift-sim-0.1.0.jar "$@" >> data/app.log 2>&1 &
echo $! > data/app.pid
for i in $(seq 1 60); do curl -s -m 2 -o /dev/null http://127.0.0.1:8080/api/participants && break; sleep 1; done
echo "started pid $(cat data/app.pid)"
echo "  gateway / API / web UI : http://127.0.0.1:8080/"
echo "  admin console (中文管理端): http://127.0.0.1:8081/   login file: $(pwd)/config/secrets/admin-console.properties"
echo "  AI API                  : http://127.0.0.1:8082/ai/v1  (OpenAPI /ai/v1/openapi.json, MCP /ai/v1/mcp)   keys file: $(pwd)/config/secrets/ai-api-keys"
echo "  AI docs (skill / prompts): $(pwd)/docs/ai/"
