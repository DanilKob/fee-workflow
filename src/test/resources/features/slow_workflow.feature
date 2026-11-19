Feature: Slow workflows: POST answers 202 with a status link, and GET follows the fee
  POST /transaction/fee waits for the workflow for up to fees.sync-wait (10 s; 1 s in the ITs).
  If the workflow hasn't finished by then, it answers 202 Accepted with Location:
  /transaction/{id}/fee, Retry-After and {"transaction_id":"…","status":"PROCESSING"}, and the
  workflow keeps running. GET /transaction/{id}/fee answers exactly the POST's 200 body once the
  fee is recorded, 202 while the workflow runs, the same problem the POST got after the workflow
  rejected the transaction, and 404 for an id it doesn't know.

  A slow workflow is made by delaying its fee_record save past the IT wait. Every scenario uses
  its own transaction_id.

  @AC28 @AC29
  Scenario: A slow workflow answers 202 with a Location, and GET returns its fee once it is recorded
    Given the brief's sample transaction with transaction_id "txn_slow_ac28"
    And the fee_record save for "txn_slow_ac28" is delayed by 3 seconds
    When it is submitted
    Then the response status is 202
    And the response header "Location" is "/transaction/txn_slow_ac28/fee"
    And the response header "Retry-After" is "1"
    And the response content type is "application/json"
    And the response body equals:
      """
      {"transaction_id": "txn_slow_ac28", "status": "PROCESSING"}
      """
    When the fee of "txn_slow_ac28" is requested
    Then the response status is 202
    And the response header "Location" is "/transaction/txn_slow_ac28/fee"
    And the response header "Retry-After" is "1"
    And the response body equals:
      """
      {"transaction_id": "txn_slow_ac28", "status": "PROCESSING"}
      """
    When the fee of "txn_slow_ac28" is requested until the answer is no longer 202
    Then the response status is 200
    And the response content type is "application/json"
    And the response body equals:
      """
      {
        "transaction_id": "txn_slow_ac28",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And exactly one fee_record row exists for "txn_slow_ac28"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And the faults armed for "txn_slow_ac28" fired 1 time

  @AC29
  Scenario: GET returns exactly the POST's 200 body once the workflow is done
    Given the brief's sample transaction with transaction_id "txn_slow_ac29_done"
    When it is submitted
    Then the response status is 200
    When the fee of "txn_slow_ac29_done" is requested
    Then the response status is 200
    And the response content type is "application/json"
    And the response body is exactly the POST's 200 body

  @AC29
  Scenario: GET of an unknown transaction_id is a 404 problem
    When the fee of "txn_slow_ac29_unknown" is requested
    Then the response status is 404
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/fee-not-found"
    And the response field "title" is "Fee not found"

  @AC29
  Scenario: After the workflow rejected the transaction, GET answers the same problem the POST got
    Given the brief's sample transaction with transaction_id "txn_slow_ac29_rejected"
    And FeeCharge "txn_slow_ac29_rejected" already holds a charge for another payload
    When it is submitted
    Then the response status is 409
    And the response field "type" is "/problems/payload-conflict"
    When the fee of "txn_slow_ac29_rejected" is requested
    Then the response is the same problem the POST got
    And no fee_record row exists for "txn_slow_ac29_rejected"
