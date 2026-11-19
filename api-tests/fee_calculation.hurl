# The brief's sample transaction gets its fee (AC1–AC5), against the running stack.
# Run: ./scripts/smoke.sh   (or: hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl)
# A fresh transaction_id on every run, so the request can be repeated.

POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
# AC3: numbers in plain form, checked on the raw text, not a parsed value
body contains "\"amount\":1000,"
body contains "\"fee\":1.5,"
body contains "\"rate\":0.0015,"
# AC1, AC5: the brief's expected values
jsonpath "$.asset" == "USD"
jsonpath "$.type" == "Mobile Top Up"
jsonpath "$.description" == "Standard fee rate of 0.15%"
jsonpath "$.transaction_id" startsWith "txn_"
# AC2, AC4: exactly the seven keys; asset_type, state and created_at are not echoed
jsonpath "$.*" count == 7
jsonpath "$.asset_type" not exists
jsonpath "$.state" not exists
jsonpath "$.created_at" not exists
[Captures]
transaction_id: jsonpath "$.transaction_id"

# Resending the identical payload returns the identical result and never charges twice.
# (Slice 5 adds the full duplicate rules in idempotency.hurl.)
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "{{transaction_id}}",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
jsonpath "$.transaction_id" == "{{transaction_id}}"
body contains "\"fee\":1.5,"
