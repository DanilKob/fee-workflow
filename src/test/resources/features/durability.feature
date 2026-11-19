Feature: Durability: a failure after the charge never loses the record or charges again
  Once FeeCharge has charged a transaction, nothing fails terminally. The record step retries
  until it lands, replaying the journal on every attempt, so the charge and the computed fee are
  taken from the journal, never redone. When the record keeps failing, Restate pauses the
  invocation once its retry budget is spent (never kills it), GET answers 202, and an operator's
  resume finishes it once the cause is fixed.

  The IT profile pins FeeWorkflow's retry budget at 5 attempts, the first one included, with
  on-max-attempts pause. Faults are armed on the fee_record saves of one transaction_id, and each
  scenario checks that exactly the armed faults fired. The POST waits 1 s, so a run that is still
  retrying answers 202, and each scenario follows the fee through GET. Every scenario uses its own
  transaction_id.

  The journal lists each step once however many attempts ran: a retry replays the journaled steps.
  "Clock.System.now()" is the step Restate's Clock.Restate journals for the record's recorded_at.

  @AC21
  Scenario: A record that fails twice after the charge lands on a retry, with one charge and a matching record
    Given the brief's sample transaction with transaction_id "txn_dur_retry"
    And the next 2 fee_record saves for "txn_dur_retry" fail before reaching the database
    When it is submitted
    And the fee of "txn_dur_retry" is requested until the answer is no longer 202
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_dur_retry",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the POST got this same body, or the status link if the retries outlasted its wait
    And the faults armed for "txn_dur_retry" fired 2 times
    And exactly one fee_record row exists for "txn_dur_retry"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And FeeCharge was charged exactly once for "txn_dur_retry"
    And the workflow journal for "txn_dur_retry" shows only:
      | Run  | lookup             |
      | Run  | calculate          |
      | Call | FeeCharge/charge   |
      | Run  | Clock.System.now() |
      | Run  | record             |
    And the FeeWorkflow run for "txn_dur_retry" completes without pausing

  @AC21
  Scenario: A record whose commit lands and then fails is retried to one row, and the run completes
    Given the brief's sample transaction with transaction_id "txn_dur_commit_lost"
    And the next fee_record save for "txn_dur_commit_lost" commits, then fails
    When it is submitted
    And the fee of "txn_dur_commit_lost" is requested until the answer is no longer 202
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_dur_commit_lost",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the POST got this same body, or the status link if the retries outlasted its wait
    And the faults armed for "txn_dur_commit_lost" fired 1 time
    And the FeeWorkflow run for "txn_dur_commit_lost" completes without pausing
    And exactly one fee_record row exists for "txn_dur_commit_lost"
    And that fee_record row holds the fields of the 200 body
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And FeeCharge was charged exactly once for "txn_dur_commit_lost"

  @AC23
  Scenario: A record that keeps failing pauses the invocation, GET answers 202, and a resume after the fix lands the record
    Given the brief's sample transaction with transaction_id "txn_dur_paused"
    And the next 6 fee_record saves for "txn_dur_paused" fail before reaching the database
    When it is submitted
    Then the response status is 202
    And the response header "Location" is "/transaction/txn_dur_paused/fee"
    When the FeeWorkflow run for "txn_dur_paused" is paused
    Then the faults armed for "txn_dur_paused" fired 5 times
    And no fee_record row exists for "txn_dur_paused"
    And FeeCharge state for "txn_dur_paused" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    When the fee of "txn_dur_paused" is requested
    Then the response status is 202
    And the response header "Location" is "/transaction/txn_dur_paused/fee"
    And the response header "Retry-After" is "1"
    And the response body equals:
      """
      {"transaction_id": "txn_dur_paused", "status": "PROCESSING"}
      """
    When the fault on the fee_record saves for "txn_dur_paused" is cleared
    And the FeeWorkflow run for "txn_dur_paused" is resumed through the Restate admin API
    And the fee of "txn_dur_paused" is requested until the answer is no longer 202
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_dur_paused",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the faults armed for "txn_dur_paused" fired 5 times
    And exactly one fee_record row exists for "txn_dur_paused"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And FeeCharge state for "txn_dur_paused" is unchanged
    And FeeCharge was charged exactly once for "txn_dur_paused"
    And the FeeWorkflow run for "txn_dur_paused" completes

  @AC22
  Scenario: A fee rule changed between attempts doesn't change the journaled fee
    Given the brief's sample transaction with transaction_id "txn_dur_rule_change"
    And the fee schedule reprices Mobile Top Up at 0.003 as rule version 2 when the first fee_record save for "txn_dur_rule_change" runs, which then fails
    When it is submitted
    And the fee of "txn_dur_rule_change" is requested until the answer is no longer 202
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_dur_rule_change",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the POST got this same body, or the status link if the retries outlasted its wait
    And the faults armed for "txn_dur_rule_change" fired 1 time
    And the live fee schedule now prices the brief's sample at 3 under rule version 2
    And exactly one fee_record row exists for "txn_dur_rule_change"
    And that fee_record row holds:
      | fee          | 1.5                       |
      | rate         | 0.0015                    |
      | description  | Standard fee rate of 0.15% |
      | rule_id      | mobile-top-up             |
      | rule_version | 1                         |
    And FeeCharge state for "txn_dur_rule_change" holds a charge of 1.50 "USD" with the submitted payload's fingerprint
    And FeeCharge was charged exactly once for "txn_dur_rule_change"

  @AC23
  Scenario: A record that fails with an integrity violation is retried like any other failure, and lands
    Given the brief's sample transaction with transaction_id "txn_dur_integrity"
    And the next fee_record save for "txn_dur_integrity" fails with a DataIntegrityViolationException
    When it is submitted
    And the fee of "txn_dur_integrity" is requested until the answer is no longer 202
    Then the response status is 200
    And the response body equals:
      """
      {
        "transaction_id": "txn_dur_integrity",
        "amount": 1000,
        "asset": "USD",
        "type": "Mobile Top Up",
        "fee": 1.5,
        "rate": 0.0015,
        "description": "Standard fee rate of 0.15%"
      }
      """
    And the POST got this same body, or the status link if the retries outlasted its wait
    And the faults armed for "txn_dur_integrity" fired 1 time
    And exactly one fee_record row exists for "txn_dur_integrity"
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state
    And FeeCharge was charged exactly once for "txn_dur_integrity"
    And the FeeWorkflow run for "txn_dur_integrity" completes without pausing
