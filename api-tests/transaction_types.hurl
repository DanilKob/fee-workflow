# Transaction types (AC14), against the running stack: one request per registered type, each with
# its exact fee, rate and description. A flat or free fee shows rate 0 (D6a).
# Run: ./scripts/smoke.sh   (or: hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl)
# A fresh transaction_id on every run, so the requests can be repeated.

# Mobile Top Up: 0.15% of 1000 USD = 1.5.
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
# raw text, not a parsed value
body contains "\"type\":\"Mobile Top Up\","
body contains "\"fee\":1.5,"
body contains "\"rate\":0.0015,"
jsonpath "$.description" == "Standard fee rate of 0.15%"

# Bank Transfer: a flat 0.50 USD, whatever the amount; 0.50 renders as 0.5.
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Bank Transfer",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
body contains "\"type\":\"Bank Transfer\","
body contains "\"fee\":0.5,"
body contains "\"rate\":0,"
jsonpath "$.description" == "Flat fee of 0.50 USD"

# Charity Donation: no fee, still charged and recorded (Q3).
POST {{base_url}}/transaction/fee
Content-Type: application/json
{
  "transaction_id": "txn_{{newUuid}}",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Charity Donation",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
body contains "\"type\":\"Charity Donation\","
body contains "\"fee\":0,"
body contains "\"rate\":0,"
jsonpath "$.description" == "No fee for charity donations"
