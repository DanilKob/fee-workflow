# Validation (AC26, AC27), against the running stack: invalid input is a 400 application/problem+json
# that lists every invalid field once, before anything starts.
# Run: ./scripts/smoke.sh   (or: hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl)
# A fresh transaction_id on every run, so the requests can be repeated.

# Five invalid fields in one request: all five are listed, each once.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 0,
  "asset": "USD",
  "asset_type": "fiat",
  "type": "Nope",
  "state": "SETTLED",
  "created_at": "2023-08-30T15:42:17.610059Z"
}
HTTP 400
[Asserts]
header "Content-Type" contains "application/problem+json"
jsonpath "$.type" == "/problems/invalid-request"
jsonpath "$.status" == 400
jsonpath "$.errors" count == 5
jsonpath "$.errors[*].field" contains "amount"
jsonpath "$.errors[*].field" contains "asset_type"
jsonpath "$.errors[*].field" contains "type"
jsonpath "$.errors[*].field" contains "state"
jsonpath "$.errors[*].field" contains "created_at"
jsonpath "$.errors[*].message" contains "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS"
jsonpath "$.errors[*].message" contains "must be SETTLED - PENDING FEE"

# Malformed JSON: the same problem, with no field listed.
POST {{base_url}}/transaction/fee
Content-Type: application/json
```
{"transaction_id": "txn_{{newUuid}}", "amount": 1000,
```
HTTP 400
[Asserts]
header "Content-Type" contains "application/problem+json"
jsonpath "$.type" == "/problems/invalid-request"
jsonpath "$.errors" count == 0
