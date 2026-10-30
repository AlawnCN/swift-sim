---
name: swift-sim-verifier
description: Connect to the SWIFT trade-finance simulator (swift-sim) AI API to read and verify MT/MX messages and business flows (guarantees/standbys, documentary credits, collections), validate draft messages without sending, poll new traffic with a cursor, and - with an operator key - act as a virtual bank through the review queue. Use when asked to check, reconcile, audit or explain SWIFT messages or trade-finance flows exchanged with the simulator.
---

# swift-sim verifier skill

## When to use
- "Check whether guarantee / LC / collection X is consistent", "why was message #N rejected", "is this MT760/MT700/tsrv.001 correct before we send it",
  "what happened since the last check", "reply as bank B to the pending demand".
- Not for: changing simulator configuration, resetting data, sending traffic on behalf of the system under test (SUT).

## Connection
| item | value |
|---|---|
| Base URL | `http://127.0.0.1:8082/ai/v1` (bound to localhost by default; from another machine use SSH port forwarding `ssh -L 8082:127.0.0.1:8082 <host>` or start with `--sim.ai.address=0.0.0.0` and use `http://<host>:8082/ai/v1`) |
| Auth | header `Authorization: Bearer <key>` (alternative: `X-Api-Key: <key>`) |
| Keys | file `config/secrets/ai-api-keys` on the simulator host, one per line: `<name> <read|operator> <key>`; edits take effect without restart |
| OpenAPI | `GET /ai/v1/openapi.json` (no auth) |
| MCP | `POST /ai/v1/mcp` - JSON-RPC 2.0 (initialize, tools/list, tools/call), same Bearer key |

Scopes: **read** = all GET endpoints + `POST /validate` + `POST /review-queue/{id}/preview` + MCP read tools. **operator** = additionally
`POST /review-queue/{id}/respond` and `POST /scenarios/{id}/trigger`. A read key gets `403 {"code":"AI-SCOPE"}` for anything else; every
denied or operator action is audit-logged.

## Endpoint cheat-sheet
| need | call |
|---|---|
| poll new messages | `GET /messages?sinceId=<cursor>&limit=200` -> `items[]`, `cursor`, `hasMore` (ascending) |
| latest messages / search | `GET /messages?ref=&type=&bank=&direction=IN|OUT|INTERNAL&status=NAK&business=BIZREJECT&dateFrom=YYYY-MM-DD` |
| one message, fully analysed | `GET /messages/{id}` -> `message`, `raw`, `deliveredRaw`, `fields`, `validation.findings[]`, `business`, `replies[]` |
| list business | `GET /business?family=UNDERTAKING|LC|COLL&status=` |
| business state | `GET /business/{ref}` -> amounts, parties, amendments/demands/presentations, movements, `history[]`, `messages[]`, `expectedNext`, `terminal` |
| verify a flow | `GET /business/{ref}/reconcile` -> `summary{ok,counts}`, `findings[]`, `timeline[]` |
| validate a draft | `POST /validate` JSON `{"raw":"...","from":"","to":"","mtType":""}` or text/plain body -> `verdict`, `findings[]` |
| rule codes | `GET /reference/rule-codes` (SIM-* = gateway/network NAK, BIZ-* = business level) |
| state machines | `GET /reference/state-machines` (statuses, expectedNext, invariants) |
| participants / sessions | `GET /reference/participants`, `GET /sessions` (ISN/OSN), `GET /pending-responses` |
| review queue | `GET /review-queue`, `GET /review-queue/{id}`, `POST /review-queue/{id}/preview`, `POST /review-queue/{id}/respond` (operator) |

Message vocabulary: `direction` IN = sent by the SUT to the simulator, OUT = virtual bank -> SUT, INTERNAL = virtual -> virtual, SYS = ACK/NAK/DLV/NOTE.
`status` (gateway): ACK (accepted, delivery pending), NAK (rejected, never delivered), DELIVERED. `businessStatus` (receiver application):
APPLIED, REJECTED, INCONSISTENT, DUPLICATE_IGNORED, INFO, NOT_APPLICABLE, PENDING_REVIEW. `businessReject=true` marks a simulator-generated
business reject message (MT759 / MT499 / tsrv.019 REJT).

## Workflows
### 1. Incremental monitoring
1. Keep a cursor (start with the `cursor` of `GET /messages?limit=1`, or 0 for full history).
2. `GET /messages?sinceId=<cursor>&limit=200` until `hasMore=false`; store the new `cursor`.
3. For each item with `status=NAK`, `businessStatus` in (REJECTED, INCONSISTENT) or `businessReject=true`: `GET /messages/{id}` and explain using
   `validation.findings` and `/reference/rule-codes`.
4. For each distinct `businessRef` touched: `GET /business/{ref}/reconcile`; report ERROR/WARN findings.

### 2. Verify a business end-to-end
1. `GET /business/{ref}` - read status, amounts, outstanding/utilised/paid, expiry, parties, `expectedNext`.
2. `GET /business/{ref}/reconcile` - `summary.ok` false means at least one ERROR.
3. For each finding use `evidence` message ids -> `GET /messages/{id}` to quote the exact fields (e.g. 32B, 21, 31E).
4. Cross-check yourself: amounts in messages vs ledger movements, field 21 = the related reference, dates vs business date
   (`businessDate`), each request has its response (`REC-NORESP`), sequence of amendments (`REC-AMD-SEQ`).
5. Report: verdict, table of findings (severity, code, field, message, evidence), and the next expected message.

### 3. Validate a draft before the SUT sends it
`POST /validate` with the full message (or MT block 4 plus `from`, `to`, `mtType`). Verdicts: `OK`, `WOULD_NAK` (syntax/network - fix and retry),
`WOULD_BUSINESS_REJECT` (receiver bank would reject: see BIZ-* findings), `BUSINESS_INCONSISTENT` (to the SUT; ledger disagrees).
Nothing is sent, no ISN is consumed, the ledger is rolled back.

### 4. Act as a bank operator (operator key)
1. `GET /review-queue` (participants in review mode queue inbound messages instead of auto-replying).
2. `GET /review-queue/{id}` - read `item.ledgerVerdict`, `ledgerErrors`, `actions` and the business context.
3. `POST /review-queue/{id}/preview` with `{"action":"REFUSE","reasons":["..."]}` -> inspect `drafts[].raw`; optionally `POST /validate` a modified draft.
4. `POST /review-queue/{id}/respond` with the same body (or `{"action":"CUSTOM","raw":"<edited draft>"}`).
5. Re-run `GET /business/{ref}/reconcile` to confirm the result.

## Interpreting findings
- `severity`: ERROR = the flow is wrong / a message was rejected; WARN = suspicious or overdue; INFO = state information (awaiting response, scheduled reply).
- Codes: `SIM-*` gateway/syntax (NAK), `BIZ-*` business rules of the receiving bank, `REC-*` reconciliation checks:
  REC-NAK, REC-BIZ-REJECTED / REC-BIZ-INCONSISTENT (`ruleCode` = BIZ code), REC-BIZREJECT-SENT, REC-REF21 (field 21 does not chain), REC-DUP,
  REC-UNDELIVERED, REC-NORESP (no response yet; WARN when your system is late >10 min), REC-BAL-NEG / REC-BAL-OVER / REC-BAL-MOVE (balance),
  REC-DEMAND-AMT, REC-LC-OVERDRAWN, REC-LC-REIMB, REC-LC-PAID, REC-L06-EXAM (>5 banking days to examine documents), REC-COLL-BAL, REC-COLL-ACC,
  REC-EXPIRY / REC-EXPIRY-SOON, REC-AMD-SEQ, REC-AWAITING, REC-EVENT-INCONSISTENT, REC-REVIEW, REC-SCHEDULED.
- `evidence` = message ids; always cite them.

## Safety rules
- Never reset or try to influence ISN/OSN or sessions; never purge or delete data (there is no such AI endpoint - do not look for one).
- Never send messages on behalf of the SUT; operator actions only on review items and scenarios the user asked for.
- Prefer read scope; use preview before respond; respond once per item (a second call returns 409).
- Do not print API keys. Business references like KEGTEE2026000101 are real test fixtures - read them, never "clean them up".
- Report times in the user's time zone; API times are UTC.
