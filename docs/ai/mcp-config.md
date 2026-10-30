# MCP client configuration examples

The simulator exposes MCP over **Streamable HTTP** (JSON responses, stateless) at `http://127.0.0.1:8082/ai/v1/mcp`,
authenticated with the same Bearer key as the REST API (`config/secrets/ai-api-keys`). Replace `<KEY>` - never commit keys.

Tools: `list_messages`, `get_message`, `list_business`, `get_business`, `reconcile_business`, `validate_message`, `list_scenarios`,
`list_review_queue`, `preview_review_response`, `get_reference`, `respond_review` (operator), `trigger_scenario` (operator).

## Cursor (`~/.cursor/mcp.json` or `.cursor/mcp.json`)
```json
{
  "mcpServers": {
    "swift-sim": {
      "url": "http://127.0.0.1:8082/ai/v1/mcp",
      "headers": { "Authorization": "Bearer <KEY>" }
    }
  }
}
```

## Claude Desktop (`claude_desktop_config.json`) - via the mcp-remote bridge
```json
{
  "mcpServers": {
    "swift-sim": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:8082/ai/v1/mcp", "--header", "Authorization: Bearer ${SWIFT_SIM_AI_KEY}"],
      "env": { "SWIFT_SIM_AI_KEY": "<KEY>" }
    }
  }
}
```

## Claude Code
```bash
claude mcp add --transport http swift-sim http://127.0.0.1:8082/ai/v1/mcp --header "Authorization: Bearer <KEY>"
```

## Remote machine
The port listens on 127.0.0.1 only. From another machine: `ssh -N -L 8082:127.0.0.1:8082 <user>@<simulator-host>` and use the same URLs.

## Raw JSON-RPC test
```bash
KEY=$(awk '$1=="ai-readonly"{print $3}' config/secrets/ai-api-keys)
curl -s -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' http://127.0.0.1:8082/ai/v1/mcp
curl -s -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reconcile_business","arguments":{"ref":"KEGTEE2026000101"}}}' \
  http://127.0.0.1:8082/ai/v1/mcp
```
