Feature: Money is exact: every amount and fee is a decimal, rounded once at its asset's scale
  Amounts, fees and rates are BigDecimal from the HTTP body to the fee_record columns and back,
  and cross the Restate journal as JSON strings. The fee is rounded once, HALF_UP, at the scale of
  the transaction's asset: USD, EUR and GBP 2, JPY 0, BTC 8, ETH 18, USDC and USDT 6. An amount
  has at most 20 integer digits, and no more significant decimals than its asset allows.

  Numbers are checked as raw response text, in plain form: a fee of 0.50 renders as 0.5.

  Every scenario is a Mobile Top Up (0.15%) through the real POST /transaction/fee, the real
  Restate server and the real Postgres, and uses its own transaction_id.

  @AC8
  Scenario: A huge amount keeps every digit, in the response, the charge and the record
    Given the brief's sample transaction with transaction_id "txn_money_ac8_huge"
    And it is 12345678901234567.89 "USD" of asset type "FIAT"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as 12345678901234567.89
    And the raw response body renders "fee" as 18518518351851.85
    And FeeCharge state for "txn_money_ac8_huge" holds the fee as the JSON string "18518518351851.85"
    And exactly one fee_record row exists for "txn_money_ac8_huge"
    And that fee_record row holds:
      | amount | 12345678901234567.89 |
      | fee    | 18518518351851.85    |

  @AC8
  Scenario: The largest amount, 20 integer digits, is priced and recorded digit for digit
    Given the brief's sample transaction with transaction_id "txn_money_ac8_largest"
    And it is 99999999999999999999.99 "USD" of asset type "FIAT"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as 99999999999999999999.99
    And the raw response body renders "fee" as 150000000000000000
    And exactly one fee_record row exists for "txn_money_ac8_largest"
    And that fee_record row holds:
      | amount | 99999999999999999999.99 |
      | fee    | 150000000000000000      |

  @AC8
  Scenario Outline: An amount over 20 integer digits is rejected and starts nothing
    Given the brief's sample transaction with transaction_id "<id>"
    And it is <amount> "USD" of asset type "FIAT"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "amount" with the message "must have at most 20 integer digits"
    And Restate has no invocation for "<id>"

    Examples:
      | id                      | amount                |
      | txn_money_ac8_21_digits | 100000000000000000000 |
      | txn_money_ac8_exponent  | 1E+30                 |

  @AC9
  Scenario Outline: Each asset rounds at its own scale, and is charged in that asset
    Given the brief's sample transaction with transaction_id "<id>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 200
    And the response field "asset" is "<asset>"
    And the raw response body renders "amount" as <amount>
    And the raw response body renders "fee" as <fee>
    And FeeCharge state for "<id>" holds a charge of <fee> "<asset>" with the submitted payload's fingerprint

    Examples:
      | id                 | asset | asset_type | amount               | fee                  | unrounded fee           |
      | txn_money_ac9_usd  | USD   | FIAT       | 12345.67             | 18.52                | 18.518505               |
      | txn_money_ac9_eur  | EUR   | FIAT       | 30                   | 0.05                 | 0.045 (a tie)           |
      | txn_money_ac9_gbp  | GBP   | FIAT       | 330                  | 0.5                  | 0.495                   |
      | txn_money_ac9_jpy  | JPY   | FIAT       | 3000                 | 5                    | 4.5 (a tie)             |
      | txn_money_ac9_btc  | BTC   | CRYPTO     | 0.00003              | 0.00000005           | 0.000000045 (a tie)     |
      | txn_money_ac9_eth  | ETH   | CRYPTO     | 1.234567890123456789 | 0.001851851835185185 | 0.0018518518351851851835 |
      | txn_money_ac9_usdc | USDC  | CRYPTO     | 0.003                | 0.000005             | 0.0000045 (a tie)       |
      | txn_money_ac9_usdt | USDT  | CRYPTO     | 1234.567891          | 1.851852             | 1.8518518365            |

  @AC10
  Scenario Outline: Amounts where Double or HALF_EVEN would be wrong
    Given the brief's sample transaction with transaction_id "<id>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "fee" as <fee>
    And the raw response body renders "rate" as 0.0015

    Examples:
      | id                      | amount     | asset | asset_type | fee        | the wrong answer      |
      | txn_money_ac10_brief    | 1000       | USD   | FIAT       | 1.5        |                       |
      | txn_money_ac10_10_usd   | 10         | USD   | FIAT       | 0.02       | Double gives 0.01     |
      | txn_money_ac10_330_usd  | 330        | USD   | FIAT       | 0.5        | Double gives 0.49     |
      | txn_money_ac10_30_usd   | 30         | USD   | FIAT       | 0.05       | HALF_EVEN gives 0.04  |
      | txn_money_ac10_1000_jpy | 1000       | JPY   | FIAT       | 2          | scale 0               |
      | txn_money_ac10_btc      | 0.12345678 | BTC   | CRYPTO     | 0.00018519 | scale 8               |

  @AC11
  Scenario Outline: An unknown or mismatched asset is rejected and starts nothing
    Given the brief's sample transaction with transaction_id "<id>"
    And it is 1000 "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "<field>" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | id                        | asset | asset_type | field      | message        |
      | txn_money_ac11_unknown    | XYZ   | FIAT       | asset      | unknown asset  |
      | txn_money_ac11_lowercase  | usd   | FIAT       | asset      | unknown asset  |
      | txn_money_ac11_usd_crypto | USD   | CRYPTO     | asset_type | USD is FIAT    |
      | txn_money_ac11_btc_fiat   | BTC   | FIAT       | asset_type | BTC is CRYPTO  |
      | txn_money_ac11_usdt_fiat  | USDT  | FIAT       | asset_type | USDT is CRYPTO |
      | txn_money_ac11_jpy_crypto | JPY   | CRYPTO     | asset_type | JPY is FIAT    |

  @AC12
  Scenario Outline: An amount with more significant decimals than its asset allows is rejected
    Given the brief's sample transaction with transaction_id "<id>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "amount" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | id                  | asset | asset_type | amount                | message                                |
      | txn_money_ac12_usd  | USD   | FIAT       | 10.001                | has more decimals than USD allows (2)  |
      | txn_money_ac12_jpy  | JPY   | FIAT       | 1000.5                | has more decimals than JPY allows (0)  |
      | txn_money_ac12_btc  | BTC   | CRYPTO     | 0.123456789           | has more decimals than BTC allows (8)  |
      | txn_money_ac12_usdc | USDC  | CRYPTO     | 1.0000001             | has more decimals than USDC allows (6) |
      | txn_money_ac12_eth  | ETH   | CRYPTO     | 1.0000000000000000001 | must have at most 18 decimal places    |

  @AC12 @AC5
  Scenario Outline: Trailing zeros are not decimals, and the amount comes back in plain form
    Given the brief's sample transaction with transaction_id "<id>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 200
    And the raw response body renders "amount" as <rendered>
    And the raw response body renders "fee" as <fee>

    Examples:
      | id                       | asset | asset_type | amount  | rendered | fee |
      | txn_money_ac12_usd_10000 | USD   | FIAT       | 10.000  | 10       | 0.02 |
      | txn_money_ac12_usd_1000  | USD   | FIAT       | 1000.00 | 1000     | 1.5  |
      | txn_money_ac12_jpy_1000  | JPY   | FIAT       | 1000.0  | 1000     | 2    |
