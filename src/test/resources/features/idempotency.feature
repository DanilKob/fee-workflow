Feature: Idempotency: one charge per transaction_id, whatever is resent
  A transaction_id is charged once. Resending the same payload, in sequence, all at once, while the
  first run is still going, or after Restate's workflow retention has ended, answers the first 200
  body byte for byte and never charges again. The same transaction_id with a different payload is a
  409 problem that charges nothing and changes nothing. "Different" is the payload fingerprint over
  amount (compared numerically), asset, asset_type, type, state and created_at. GET agrees with
  POST throughout.

  Workflow retention is ended on demand by purging the workflow's completed run through the Restate
  admin API. That deletes the run's journal and workflow state, as the end of retention does, and
  leaves the FeeCharge object's state alone. Every scenario uses its own transaction_id.

  @AC17
  Scenario: The same payload again, in sequence, answers the first body byte for byte and charges once
    Given the brief's sample transaction with transaction_id "txn_idem_sequence"
    When it is submitted
    Then the response status is 200
    When it is submitted again
    Then the response status is 200
    And the response body is exactly the first POST's 200 body
    When the same transaction_id is submitted with its "amount" as the JSON value 1000.00
    Then the response status is 200
    And the response body is exactly the first POST's 200 body
    And exactly one fee_record row exists for "txn_idem_sequence"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And Restate ran exactly one FeeWorkflow run for "txn_idem_sequence"
    When the fee of "txn_idem_sequence" is requested
    Then the response body is exactly the first POST's 200 body

  @AC16 @AC17
  Scenario: The same payload 10 times at once: identical 200 bodies, one charge, one record
    Given the brief's sample transaction with transaction_id "txn_idem_at_once"
    When it is submitted 10 times at once
    Then every one of those responses is 200 with the same body
    And the response body equals:
      """
      {
        "transaction_id": "txn_idem_at_once",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And exactly one fee_record row exists for "txn_idem_at_once"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And Restate ran exactly one FeeWorkflow run for "txn_idem_at_once"
    When the fee of "txn_idem_at_once" is requested
    Then the response body is exactly the first POST's 200 body

  @AC16
  Scenario: A second submission while the first run is still going reattaches to it
    Given the brief's sample transaction with transaction_id "txn_idem_reattach"
    And the fee_record save for "txn_idem_reattach" is delayed by 400 milliseconds
    When it is submitted in the background
    And the run for "txn_idem_reattach" is held in its fee_record save
    And it is submitted again
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_idem_reattach",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the background submission got the same 200 body, or the status link if its wait ran out
    And exactly one fee_record row exists for "txn_idem_reattach"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And Restate ran exactly one FeeWorkflow run for "txn_idem_reattach"
    And the faults armed for "txn_idem_reattach" fired 1 time

  @AC18
  Scenario Outline: The same transaction_id with a different <field> is a 409 problem, and nothing is charged or changed
    Given the brief's sample transaction with transaction_id "<transaction_id>"
    When it is submitted
    Then the response status is 200
    And FeeCharge state for "<transaction_id>" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    And the fee_record row for "<transaction_id>" is noted
    When the same transaction_id is submitted with its "<field>" as the JSON value <value>
    Then the response status is 409
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/payload-conflict"
    And the response field "title" is "Payload conflict"
    And the response field "detail" is "this transaction_id was already used with a different payload"
    And exactly one fee_record row exists for "<transaction_id>"
    And the fee_record row for "<transaction_id>" is unchanged
    And FeeCharge state for "<transaction_id>" is unchanged
    And Restate ran exactly one FeeWorkflow run for "<transaction_id>"
    When the fee of "<transaction_id>" is requested
    Then the response body is exactly the first POST's 200 body

    Examples:
      | field      | value                        | transaction_id        |
      | amount     | 2000                         | txn_idem_diff_amount  |
      | created_at | "2023-08-30 15:42:17.610060" | txn_idem_diff_created |
      | type       | "Bank Transfer"              | txn_idem_diff_type    |

  @AC19
  Scenario: After workflow retention, the same payload gets the stored body and the original charge, and another payload gets 409
    Given the brief's sample transaction with transaction_id "txn_idem_retained"
    When it is submitted
    Then the response status is 200
    And FeeCharge state for "txn_idem_retained" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    And the fee_record row for "txn_idem_retained" is noted
    When the workflow run for "txn_idem_retained" is purged, as at the end of workflow retention
    And it is submitted again
    Then the response status is 200
    And the response body is exactly the first POST's 200 body
    And the workflow journal for "txn_idem_retained" shows only:
      | Run | lookup |
    And exactly one fee_record row exists for "txn_idem_retained"
    And the fee_record row for "txn_idem_retained" is unchanged
    And FeeCharge state for "txn_idem_retained" is unchanged
    When the same transaction_id is submitted with its "amount" as the JSON value 2000
    Then the response status is 409
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/payload-conflict"
    And the fee_record row for "txn_idem_retained" is unchanged
    And FeeCharge state for "txn_idem_retained" is unchanged
    When the fee of "txn_idem_retained" is requested
    Then the response body is exactly the first POST's 200 body

  @AC18 @AC19
  Scenario: After workflow retention, a different payload first gets 409, and the recorded payload still gets its stored body
    Given the brief's sample transaction with transaction_id "txn_idem_retained_other_first"
    When it is submitted
    Then the response status is 200
    And FeeCharge state for "txn_idem_retained_other_first" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    And the fee_record row for "txn_idem_retained_other_first" is noted
    When the fee of "txn_idem_retained_other_first" is requested
    Then the response body is exactly the first POST's 200 body
    When the workflow run for "txn_idem_retained_other_first" is purged, as at the end of workflow retention
    And the fee of "txn_idem_retained_other_first" is requested
    Then the response body is exactly the first POST's 200 body
    When the same transaction_id is submitted with its "amount" as the JSON value 2000
    Then the response status is 409
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/payload-conflict"
    And the workflow journal for "txn_idem_retained_other_first" shows only:
      | Run | lookup |
    And the fee_record row for "txn_idem_retained_other_first" is unchanged
    And FeeCharge state for "txn_idem_retained_other_first" is unchanged
    When the fee of "txn_idem_retained_other_first" is requested
    Then the response body is exactly the first POST's 200 body
    When the first payload is submitted again
    Then the response status is 200
    And the response body is exactly the first POST's 200 body
    And exactly one fee_record row exists for "txn_idem_retained_other_first"
    And the fee_record row for "txn_idem_retained_other_first" is unchanged
    And FeeCharge state for "txn_idem_retained_other_first" is unchanged
    When the fee of "txn_idem_retained_other_first" is requested
    Then the response body is exactly the first POST's 200 body

  @AC18
  Scenario: An orphan charge refuses a payload with a different fee, and GET answers the same 409 problem
    Given the brief's sample transaction with transaction_id "txn_idem_orphan"
    And FeeCharge "txn_idem_orphan" already holds a charge of 1.50 "USD" for this payload
    And its "amount" is the JSON value 330
    When it is submitted
    Then the response status is 409
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/payload-conflict"
    And the response field "detail" is "this transaction_id was already used with a different payload"
    And no fee_record row exists for "txn_idem_orphan"
    And FeeCharge state for "txn_idem_orphan" is unchanged
    When the fee of "txn_idem_orphan" is requested
    Then the response is the same problem the POST got
    And no fee_record row exists for "txn_idem_orphan"
    And FeeCharge state for "txn_idem_orphan" is unchanged

  @AC18 @AC19
  Scenario: An orphan charge with the same fee still refuses another payload, and the charged payload gets the planted charge
    Given the brief's sample transaction with transaction_id "txn_idem_orphan_same_fee"
    And FeeCharge "txn_idem_orphan_same_fee" already holds a charge of 1.50 "USD" for this payload
    And its "created_at" is the JSON string "2023-08-30 15:42:17.610060"
    When it is submitted
    Then the response status is 409
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/payload-conflict"
    And no fee_record row exists for "txn_idem_orphan_same_fee"
    And FeeCharge state for "txn_idem_orphan_same_fee" is unchanged
    When the workflow run for "txn_idem_orphan_same_fee" is purged, as at the end of workflow retention
    And the same transaction_id is submitted with its "created_at" as the JSON value "2023-08-30 15:42:17.610059"
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_idem_orphan_same_fee",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And exactly one fee_record row exists for "txn_idem_orphan_same_fee"
    And that fee_record row's charge is the seeded charge
    And FeeCharge state for "txn_idem_orphan_same_fee" is unchanged
