# Money (AC9, AC10, AC12), against the running stack: a non-USD fiat asset, a crypto asset, and an
# amount with more decimals than its asset allows. Each fee rounds once, HALF_UP, at its asset's scale.
# Run: ./scripts/smoke.sh   (or: hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl)
# A fresh transaction_id on every run, so the requests can be repeated.

# JPY has scale 0: 1000 x 0.15% = 1.5, rounded to 2.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 1000,
  "asset": "JPY",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
# raw text, not a parsed value
body contains "\"amount\":1000,"
body contains "\"asset\":\"JPY\","
body contains "\"fee\":2,"
body contains "\"rate\":0.0015,"

# BTC has scale 8: 0.12345678 x 0.15% = 0.00018518517, rounded to 0.00018519.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 0.12345678,
  "asset": "BTC",
  "asset_type": "CRYPTO",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
body contains "\"amount\":0.12345678,"
body contains "\"asset\":\"BTC\","
body contains "\"fee\":0.00018519,"
body contains "\"rate\":0.0015,"

# USD has scale 2: 10.001 has a third significant decimal, so it is refused before anything starts.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 10.001,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 400
[Asserts]
header "Content-Type" contains "application/problem+json"
jsonpath "$.status" == 400
jsonpath "$.errors" count == 1
jsonpath "$.errors[0].field" == "amount"
jsonpath "$.errors[0].message" == "has more decimals than USD allows (2)"
