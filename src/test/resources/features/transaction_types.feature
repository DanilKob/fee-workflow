Feature: Transaction types: each registered type prices its fee its own way
  The fee registry holds exactly three types. Mobile Top Up is a percentage (0.15%) in fiat and
  crypto; Bank Transfer is a flat fee per fiat asset (USD, EUR and GBP 0.50, JPY 75), in fiat only;
  Charity Donation has no fee, in fiat and crypto. A flat or free fee shows rate 0 and a description
  that explains it. A zero fee is still charged, so it still gets a charge_id. A fee larger than
  the amount, or a type used with an asset type it doesn't allow, is rejected before anything starts.

  Adding a type is one entry in the fee schedule plus one row in the first outline below.

  Every scenario goes through the real POST /transaction/fee, the real Restate server and the real
  Postgres, and uses its own transaction_id. Numbers are checked as raw response text, in plain
  form: a fee of 0.50 renders as 0.5.

  @AC14
  Scenario Outline: Every registered type is priced, charged and recorded end to end
    Given the brief's sample transaction with transaction_id "<id>"
    And its type is "<type>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 200
    And the response field "type" is "<type>"
    And the raw response body renders "fee" as <fee>
    And the raw response body renders "rate" as <rate>
    And the response field "description" is "<description>"
    And FeeCharge state for "<id>" holds a charge of <fee> "<asset>" with the submitted payload's fingerprint
    And exactly one fee_record row exists for "<id>"
    And that fee_record row holds:
      | type         | <type>        |
      | fee          | <fee>         |
      | rate         | <rate>        |
      | description  | <description> |
      | rule_id      | <rule_id>     |
      | rule_version | 1             |
    And that fee_record row's charge_id and charged_at are the charge in FeeCharge state

    Examples:
      | id                     | type             | amount | asset | asset_type | fee | rate   | description                  | rule_id          |
      | txn_types_ac14_mobile  | Mobile Top Up    | 1000   | USD   | FIAT       | 1.5 | 0.0015 | Standard fee rate of 0.15%   | mobile-top-up    |
      | txn_types_ac14_bank    | Bank Transfer    | 1000   | USD   | FIAT       | 0.5 | 0      | Flat fee of 0.50 USD         | bank-transfer    |
      | txn_types_ac14_charity | Charity Donation | 1000   | USD   | FIAT       | 0   | 0      | No fee for charity donations | charity-donation |

  @AC14
  Scenario Outline: Bank Transfer's flat fee is set per fiat asset, and Charity Donation is free in crypto too
    Given the brief's sample transaction with transaction_id "<id>"
    And its type is "<type>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 200
    And the response field "asset" is "<asset>"
    And the raw response body renders "fee" as <fee>
    And the raw response body renders "rate" as 0
    And the response field "description" is "<description>"
    And FeeCharge state for "<id>" holds a charge of <fee> "<asset>" with the submitted payload's fingerprint

    Examples:
      | id                         | type             | amount     | asset | asset_type | fee | description                  |
      | txn_types_ac14_bank_eur    | Bank Transfer    | 100        | EUR   | FIAT       | 0.5 | Flat fee of 0.50 EUR         |
      | txn_types_ac14_bank_gbp    | Bank Transfer    | 100        | GBP   | FIAT       | 0.5 | Flat fee of 0.50 GBP         |
      | txn_types_ac14_bank_jpy    | Bank Transfer    | 10000      | JPY   | FIAT       | 75  | Flat fee of 75 JPY           |
      | txn_types_ac14_charity_btc | Charity Donation | 0.12345678 | BTC   | CRYPTO     | 0   | No fee for charity donations |

  @AC13
  Scenario: A flat fee larger than the amount is rejected and starts nothing
    Given the brief's sample transaction with transaction_id "txn_types_ac13_larger"
    And its type is "Bank Transfer"
    And it is 0.10 "USD" of asset type "FIAT"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "amount" with the message "must be at least the fee of 0.50 USD"
    And Restate has no invocation for "txn_types_ac13_larger"

  @AC13
  Scenario: A flat fee equal to the amount is charged
    Given the brief's sample transaction with transaction_id "txn_types_ac13_equal"
    And its type is "Bank Transfer"
    And it is 0.50 "USD" of asset type "FIAT"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as 0.5
    And the raw response body renders "fee" as 0.5
    And FeeCharge state for "txn_types_ac13_equal" holds a charge of 0.50 "USD" with the submitted payload's fingerprint
    And exactly one fee_record row exists for "txn_types_ac13_equal"

  @AC15
  Scenario: A type used with an asset type it doesn't allow is rejected and starts nothing
    Given the brief's sample transaction with transaction_id "txn_types_ac15_bank_btc"
    And its type is "Bank Transfer"
    And it is 0.5 "BTC" of asset type "CRYPTO"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "type" with the message "Bank Transfer does not allow CRYPTO; allowed asset types: FIAT"
    And Restate has no invocation for "txn_types_ac15_bank_btc"
