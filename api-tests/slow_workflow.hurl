# The status link (AC29), against the running stack: GET /transaction/{id}/fee answers exactly the
# POST's 200 body once the fee is recorded, and 404 for an id it doesn't know.
# (A slow workflow's 202 needs a delayed record; slow_workflow.feature covers it.)
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
[Captures]
transaction_id: jsonpath "$.transaction_id"
post_body: body

# The same id through GET: the identical body, byte for byte.
GET {{base_url}}/transaction/{{transaction_id}}/fee
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
body == "{{post_body}}"
body contains "\"fee\":1.5,"

# An id nothing is known for: a 404 problem.
GET {{base_url}}/transaction/txn_{{newUuid}}/fee
HTTP 404
[Asserts]
header "Content-Type" contains "application/problem+json"
jsonpath "$.type" == "/problems/fee-not-found"
jsonpath "$.status" == 404
