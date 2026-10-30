#!/usr/bin/env python3
"""Generates src/main/resources/ai/openapi.json and docs/ai/openapi.json (OpenAPI 3.0 of the AI API, port 8082)."""
import json, os
def P(name, where='query', typ='string', desc='', req=False): return {'name': name, 'in': where, 'required': req or where == 'path', 'schema': {'type': typ}, 'description': desc}
J = {'application/json': {'schema': {'type': 'object'}}}
def get(summary, params=(), desc=''): return {'get': {'summary': summary, 'description': desc, 'parameters': list(params), 'responses': {'200': {'description': 'OK', 'content': J}, '401': {'$ref': '#/components/responses/Unauthorized'}, '404': {'description': 'not found'}}}}
def post(summary, params=(), body=None, desc='', operator=False):
    op = {'summary': summary + (' (operator scope)' if operator else ''), 'description': desc, 'parameters': list(params),
          'responses': {'200': {'description': 'OK', 'content': J}, '400': {'description': 'bad request'}, '401': {'$ref': '#/components/responses/Unauthorized'}}}
    if operator: op['responses']['403'] = {'description': 'key has read scope (code AI-SCOPE)'}
    if body: op['requestBody'] = {'required': True, 'content': body}
    return {'post': op}
msgq = [P('sinceId', typ='integer', desc='cursor: only id > sinceId, ascending (incremental polling)'), P('beforeId', typ='integer', desc='paging backwards (descending mode)'),
        P('limit', typ='integer', desc='max 500, default 100'), P('ref', desc='substring of field 20 / 21 / business reference'), P('type', desc='message type, e.g. MT760, 767, tsrv.001'),
        P('bank', desc='BIC or participant id; matches sender or receiver'), P('from', desc='sender BIC/id'), P('to', desc='receiver BIC/id'),
        P('direction', desc='IN (from your system), OUT (to your system), INTERNAL, SYS; comma list'), P('format', desc='MT|MX|SYS'),
        P('status', desc='gateway status ACK|NAK|DELIVERED (comma list)'), P('business', desc='APPLIED|REJECTED|INCONSISTENT|PENDING_REVIEW|DUPLICATE_IGNORED or BIZREJECT'),
        P('dateFrom', desc='YYYY-MM-DD or ISO date-time'), P('dateTo', desc='YYYY-MM-DD (inclusive) or ISO date-time'), P('includeSys', typ='boolean', desc='include ACK/NAK/DLV/NOTE service messages'),
        P('includeRaw', typ='boolean', desc='include raw text')]
review_body = {'application/json': {'schema': {'type': 'object', 'required': ['action'], 'properties': {
    'action': {'type': 'string', 'enum': ['ACK', 'ACCEPT', 'REFUSE', 'PAY', 'EXTEND', 'CONFIRM', 'BUSINESS_REJECT', 'SCENARIO', 'CUSTOM', 'DISMISS']},
    'reasons': {'type': 'array', 'items': {'type': 'string'}}, 'amount': {'type': 'string'}, 'newExpiry': {'type': 'string', 'format': 'date'},
    'scenario': {'type': 'string'}, 'raw': {'type': 'string'}, 'mtType': {'type': 'string'}, 'note': {'type': 'string'}}}}}
paths = {
 '/ai/v1': get('Index: endpoints, key name and scope'),
 '/ai/v1/whoami': get('Key name and scope'),
 '/ai/v1/messages': get('Search / poll messages', msgq, 'Without sinceId: newest first. With sinceId: oldest first after the cursor; response has cursor (last id) and hasMore.'),
 '/ai/v1/messages/{id}': get('One message: raw, parsed fields, validation findings, linked business, replies', [P('id', 'path', 'integer'), P('includeRaw', typ='boolean', desc='default true')]),
 '/ai/v1/business': get('List business records', [P('family', desc='UNDERTAKING|LC|COLL'), P('status'), P('party', desc='BIC or participant id')]),
 '/ai/v1/business/{ref}': get('Business record: state, amounts, parties, items, movements, history, messages, expectedNext', [P('ref', 'path'), P('includeRaw', typ='boolean')]),
 '/ai/v1/business/{ref}/reconcile': get('Reconcile a flow: summary + findings (severity, code, message, evidence)', [P('ref', 'path')]),
 '/ai/v1/validate': post('Validate a message without sending (syntax + network + business dry run)', [P('from'), P('to'), P('mtType')],
     {'application/json': {'schema': {'type': 'object', 'required': ['raw'], 'properties': {'raw': {'type': 'string'}, 'from': {'type': 'string'}, 'to': {'type': 'string'}, 'mtType': {'type': 'string'}}}},
      'text/plain': {'schema': {'type': 'string'}}}, 'verdict: OK | WOULD_NAK | WOULD_BUSINESS_REJECT | BUSINESS_INCONSISTENT. Allowed for read scope.'),
 '/ai/v1/scenarios': get('Scenarios and active scenario per group'),
 '/ai/v1/scenarios/{id}/trigger': post('Kick off a scenario', [P('id', 'path'), P('format', desc='MT|MX'), P('ref'), P('from'), P('to')], {'application/json': {'schema': {'type': 'object', 'additionalProperties': {'type': 'string'}}}}, operator=True),
 '/ai/v1/review-queue': get('Review queue items', [P('status', desc='PENDING_REVIEW (default) | RESPONDED | DISMISSED | ALL'), P('participant')]),
 '/ai/v1/review-queue/{id}': get('Review item with message, ledger context and allowed actions', [P('id', 'path', 'integer')]),
 '/ai/v1/review-queue/{id}/preview': post('Draft of the message(s) a decision would send (nothing sent; allowed for read scope)', [P('id', 'path', 'integer')], review_body),
 '/ai/v1/review-queue/{id}/respond': post('Decide a review item (virtual bank sends the response)', [P('id', 'path', 'integer')], review_body, operator=True),
 '/ai/v1/reference/rule-codes': get('Catalogue of SIM-* (network/syntax) and BIZ-* (business) rule codes'),
 '/ai/v1/reference/state-machines': get('Statuses, transitions, expected next messages and invariants per product'),
 '/ai/v1/reference/message-types': get('Supported MT / MX message types per product'),
 '/ai/v1/reference/participants': get('Participants (id, BIC, role, SUT flag, review mode)'),
 '/ai/v1/sessions': get('FIN-like LT sessions: session number, last ISN / OSN per BIC'),
 '/ai/v1/pending-responses': get('Simulator responses scheduled but not yet sent'),
 '/ai/v1/mcp': post('MCP (Model Context Protocol) JSON-RPC 2.0 endpoint: initialize, tools/list, tools/call, ping', body={'application/json': {'schema': {'type': 'object'}}},
     desc='Streamable HTTP transport, JSON responses, stateless. Operator-only tools (respond_review, trigger_scenario) return isError for read keys.'),
}
doc = {'openapi': '3.0.3', 'info': {'title': 'swift-sim AI API', 'version': '1.0',
       'description': 'AI / automation access to the SWIFT trade-finance simulator (guarantees, documentary credits, collections). Bind: 127.0.0.1:8082. '
                      'Auth: Authorization: Bearer <key> (config/secrets/ai-api-keys). read scope = GET + validate + preview + MCP read tools; operator scope = also trigger / respond.'},
       'servers': [{'url': 'http://127.0.0.1:8082'}], 'security': [{'bearer': []}],
       'components': {'securitySchemes': {'bearer': {'type': 'http', 'scheme': 'bearer'}}, 'responses': {'Unauthorized': {'description': 'missing or invalid key'}}},
       'paths': paths}
root = os.path.join(os.path.dirname(__file__), '..')
for p in ['src/main/resources/ai/openapi.json', 'docs/ai/openapi.json']:
    with open(os.path.join(root, p), 'w') as f: json.dump(doc, f, indent=1, ensure_ascii=False); f.write('\n')
print('ok', len(paths), 'paths')
