#!/usr/bin/env bash
# Start the Cloudflare Tunnel connector (swift-sim.mrtech.top -> http://127.0.0.1:8080) in background.
# Token is read from config/secrets/tunnel.token (chmod 600) via --token-file; never put it on the command line.
cd "$(dirname "$0")"
CLOUDFLARED=${CLOUDFLARED:-/opt/tools/cloudflared}
TOKEN_FILE=config/secrets/tunnel.token
# The box egress resolves *.argotunnel.com to a fake IP that drops the tunnel TLS, so pin Cloudflare edge IPs
# (region1 198.41.192.0/24, region2 198.41.200.0/24). Override with TUNNEL_EDGE_ARGS="" to use normal discovery.
EDGE_ARGS=${TUNNEL_EDGE_ARGS-"--edge 198.41.192.7:7844 --edge 198.41.192.37:7844 --edge 198.41.200.13:7844 --edge 198.41.200.33:7844"}
mkdir -p data
if [ -f data/cloudflared.pid ] && kill -0 "$(cat data/cloudflared.pid)" 2>/dev/null; then
  echo "tunnel already running pid $(cat data/cloudflared.pid)"; exit 0
fi
[ -r "$TOKEN_FILE" ] || { echo "missing $TOKEN_FILE" >&2; exit 1; }
nohup "$CLOUDFLARED" --no-autoupdate tunnel --loglevel info --protocol "${TUNNEL_PROTOCOL:-http2}" $EDGE_ARGS run --token-file "$TOKEN_FILE" >> data/cloudflared.log 2>&1 &
echo $! > data/cloudflared.pid
echo "tunnel started pid $(cat data/cloudflared.pid) -> https://swift-sim.mrtech.top (log: data/cloudflared.log)"
