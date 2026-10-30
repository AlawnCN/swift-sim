# AI API curl examples

```bash
cd /path/to/swift-sim
KEY=$(awk '$1=="ai-readonly"{print $3}' config/secrets/ai-api-keys)      # read scope
OPKEY=$(awk '$1=="ai-operator"{print $3}' config/secrets/ai-api-keys)    # operator scope
A=http://127.0.0.1:8082/ai/v1
H="Authorization: Bearer $KEY"

curl -s -H "$H" $A/                                   # index + key scope
curl -s -H "$H" "$A/messages?limit=20"                # newest messages
curl -s -H "$H" "$A/messages?sinceId=0&limit=200"     # cursor polling (ascending); repeat with the returned cursor
curl -s -H "$H" "$A/messages?status=NAK"              # gateway rejects
curl -s -H "$H" "$A/messages?business=BIZREJECT"      # business rejects / inconsistencies
curl -s -H "$H" $A/messages/123                       # raw + parsed fields + findings + linked business
curl -s -H "$H" "$A/business?family=UNDERTAKING"
curl -s -H "$H" $A/business/KEGTEE2026000101
curl -s -H "$H" $A/business/KEGTEE2026000101/reconcile
curl -s -H "$H" $A/reference/rule-codes
curl -s -H "$H" $A/reference/state-machines
curl -s -H "$H" $A/sessions

# validate without sending (file with a full MT or MX message)
curl -s -H "$H" --data-binary @samples/mt760-issue.fin $A/validate
# MT block 4 only
curl -s -H "$H" -H 'Content-Type: application/json' \
  -d '{"raw":":20:TEST1\n:21:NONREF\n:79Z:HELLO","from":"BANK_B","to":"MY_BANK","mtType":"799"}' $A/validate

# review queue (operator for respond)
curl -s -H "$H" $A/review-queue
curl -s -H "$H" -H 'Content-Type: application/json' -d '{"action":"REFUSE","reasons":["DOCUMENTS NOT COMPLIANT"]}' $A/review-queue/5/preview
curl -s -H "Authorization: Bearer $OPKEY" -H 'Content-Type: application/json' -d '{"action":"REFUSE","reasons":["DOCUMENTS NOT COMPLIANT"]}' $A/review-queue/5/respond
```
Participant ids / BICs: `GET $A/reference/participants`. Sample file names depend on `samples/`.
