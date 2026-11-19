# Idempotency (AC17, AC18), against the running stack: an identical resend gets the identical body,
# byte for byte, and the same transaction_id with another amount is a 409 problem.
# (Concurrent resends, retention and orphan charges need the test harness; idempotency.feature
# covers them.)
# Run: ./scripts/smoke.sh   (or: hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl)
# A fresh transaction_id on every run, so the requests can be repeated.

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
body contains "\"fee\":1.5,"
[Captures]
transaction_id: jsonpath "$.transaction_id"
first_body: body

# The identical payload again: the identical body, and no second charge.
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
body == "{{first_body}}"

# The same transaction_id with another amount: a 409 problem, and nothing changes.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "{{transaction_id}}",
  "amount": 2000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 409
[Asserts]
header "Content-Type" contains "application/problem+json"
jsonpath "$.type" == "/problems/payload-conflict"
jsonpath "$.status" == 409
jsonpath "$.detail" == "this transaction_id was already used with a different payload"

# The fee is still the first one's.
GET {{base_url}}/transaction/{{transaction_id}}/fee
HTTP 200
[Asserts]
body == "{{first_body}}"
