Feature: Calculating the fee for a settled transaction
  A caller sends one settled transaction that is waiting for its fee. The service prices it,
  charges it once in the FeeCharge object and records it in fee_record, driven by the Restate
  workflow, and answers with the fee. Mobile Top Up is 0.15%, rounded HALF_UP to the asset's scale.

  Every scenario goes through the real POST /transaction/fee, the real Restate server and the real
  Postgres, and uses its own transaction_id.

  @AC1 @AC30 @AC31
  Scenario: The brief's sample transaction gets its fee
    When this transaction is submitted:
      """
      {
        "transaction_id": "txn_fee_calc_ac1",
        "amount": 1000,
        "asset": "USD",
        "asset_type": "FIAT",
        "type": "Mobile Top Up",
        "state": "SETTLED - PENDING FEE",
        "created_at": "2023-08-30 15:42:17.610059"
      }
      """
    Then the response status is 200
    And the response content type is "application/json"
    And the response body equals:
      """
      {
        "transaction_id": "txn_fee_calc_ac1",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """

  @AC2
  Scenario: Only the seven snake_case keys come back, with no nulls
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac2"
    When it is submitted
    Then the response status is 200
    And the response body has exactly the keys "transaction_id, amount, asset, type, fee, rate, description"
    And no value in the response body is null

  @AC3
  Scenario: Numbers are rendered in plain form in the raw body
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac3"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as 1000
    And the raw response body renders "fee" as 1.5
    And the raw response body renders "rate" as 0.0015

  @AC4
  Scenario: asset_type, state and created_at are not echoed
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac4"
    When it is submitted
    Then the response status is 200
    And the response body has no key "asset_type"
    And the response body has no key "state"
    And the response body has no key "created_at"

  @AC5
  Scenario: Amount and asset are echoed as sent; type is the canonical label
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac5"
    And its "amount" is the JSON value 1000.00
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as 1000
    And the response field "asset" is "USD"
    And the response field "type" is "Mobile Top Up"

  @AC20
  Scenario: The journal shows the calculate, charge and record steps
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac20"
    When it is submitted
    Then the response status is 200
    And the workflow journal for "txn_fee_calc_ac20" shows, in this order:
      | Run  | lookup           |
      | Run  | calculate        |
      | Call | FeeCharge/charge |
      | Run  | record           |

  @AC24
  Scenario: The fee_record row holds every field
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac24"
    When it is submitted
    Then the response status is 200
    And exactly one fee_record row exists for "txn_fee_calc_ac24"
    And that fee_record row holds:
      | transaction_id | txn_fee_calc_ac24           |
      | amount         | 1000                       |
      | asset          | USD                        |
      | asset_type     | FIAT                       |
      | type           | Mobile Top Up              |
      | fee            | 1.5                        |
      | rate           | 0.0015                     |
      | description    | Standard fee rate of 0.15% |
      | rule_id        | mobile-top-up              |
      | rule_version   | 1                          |
      | created_at     | 2023-08-30 15:42:17.610059 |
      | state          | SETTLED                    |
    And that fee_record row's fingerprint is the submitted payload's
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And that fee_record row's recorded_at is not before its charged_at

  @AC25
  Scenario: The charge lives in FeeCharge state; a repeat charge returns the same id; FeeCharge isn't reachable through the ingress
    Given the brief's sample transaction with transaction_id "txn_fee_calc_ac25"
    When it is submitted
    Then the response status is 200
    And FeeCharge state for "txn_fee_calc_ac25" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    When FeeCharge "txn_fee_calc_ac25" is charged again with the same fingerprint and a fee of 9.99 "USD"
    Then the repeat charge returns the charge_id of the fee_record row
    When FeeCharge "txn_fee_calc_ac25" is charged again with another fingerprint
    Then the repeat charge fails with status 409 and tag "payload_conflict"
    And FeeCharge state for "txn_fee_calc_ac25" is unchanged
    When FeeCharge "txn_fee_calc_ac25" is called directly through the Restate ingress
    Then the ingress refuses the call
    And FeeCharge state for "txn_fee_calc_ac25" is unchanged
    And FeeCharge is not public in the Restate admin API
