Feature: Validation: invalid input is rejected before anything starts
  Every field of POST /transaction/fee is checked before any workflow exists: each one present and
  of its JSON type, the transaction_id pattern, the amount's bounds and its asset's scale, exact
  labels, the one eligible state, and created_at in exactly one format. The fee rules (type vs
  asset type, fee vs amount) run there too. Each invalid request is a 400 application/problem+json
  that lists every invalid field once, and Restate has no invocation for the id afterwards.

  created_at is a UTC local date-time with microseconds: stored as timestamp(6) without time zone
  and read back exactly, whatever the JVM's zone (the ITs run at UTC+14).

  Every scenario goes through the real POST /transaction/fee, the real Restate server and the real
  Postgres, and uses its own transaction_id.

  @AC6
  Scenario Outline: created_at in any other format is rejected: <why>
    Given the brief's sample transaction with transaction_id "<id>"
    And its "created_at" is the JSON string "<created_at>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "created_at" with the message "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS"
    And Restate has no invocation for "<id>"

    Examples:
      | id                      | created_at                  | why                         |
      | txn_val_ac6_iso_zone    | 2023-08-30T15:42:17.610059Z | a T separator and a zone    |
      | txn_val_ac6_no_fraction | 2023-08-30 15:42:17         | no fraction                 |
      | txn_val_ac6_millis      | 2023-08-30 15:42:17.610     | three fraction digits       |
      | txn_val_ac6_hour_25     | 2023-08-30 25:00:00.000000  | an hour that does not exist |

  @AC7
  Scenario: created_at round-trips exactly under a non-UTC JVM zone
    Given the JVM's default time zone is not UTC
    And the brief's sample transaction with transaction_id "txn_val_ac7"
    When it is submitted
    Then the response status is 200
    And exactly one fee_record row exists for "txn_val_ac7"
    And that fee_record row holds:
      | created_at | 2023-08-30 15:42:17.610059 |
    And the fee_record row for "txn_val_ac7", read back through the app, has created_at "2023-08-30 15:42:17.610059"

  @AC26
  Scenario Outline: A body that isn't a JSON object is rejected and starts nothing: <case>
    When this transaction is submitted:
      """
      <body>
      """
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the response field "type" is "/problems/invalid-request"
    And the problem lists no invalid field
    And Restate has no invocation for "txn_val_ac26_malformed"

    Examples:
      | case             | body                                                                |
      | truncated JSON   | {"transaction_id": "txn_val_ac26_malformed", "amount": 1000,        |
      | not JSON at all  | transaction_id=txn_val_ac26_malformed                               |
      | a JSON array     | ["txn_val_ac26_malformed"]                                          |
      | JSON null        | null                                                                |
      | no body          |                                                                     |

  @AC26
  Scenario Outline: A missing required field is rejected and starts nothing: <field>
    Given the brief's sample transaction with transaction_id "<id>"
    And its "<field>" is missing
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "<field>" with the message "is required"
    And Restate has no invocation for "<id>"

    Examples:
      | id                                | field          |
      | txn_val_ac26_missing_id           | transaction_id |
      | txn_val_ac26_missing_amount       | amount         |
      | txn_val_ac26_missing_asset        | asset          |
      | txn_val_ac26_missing_asset_type   | asset_type     |
      | txn_val_ac26_missing_type         | type           |
      | txn_val_ac26_missing_state        | state          |
      | txn_val_ac26_missing_created_at   | created_at     |

  @AC26
  Scenario Outline: A field with a wrong JSON value is rejected and starts nothing: <field> <value>
    Given the brief's sample transaction with transaction_id "<id>"
    And its "<field>" is the JSON value <value>
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "<field>" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | id                             | field          | value                 | message                             |
      | txn_val_ac26_null_id           | transaction_id | null                  | is required                         |
      | txn_val_ac26_null_amount       | amount         | null                  | is required                         |
      | txn_val_ac26_null_asset        | asset          | null                  | is required                         |
      | txn_val_ac26_null_asset_type   | asset_type     | null                  | is required                         |
      | txn_val_ac26_null_type         | type           | null                  | is required                         |
      | txn_val_ac26_null_state        | state          | null                  | is required                         |
      | txn_val_ac26_null_created_at   | created_at     | null                  | is required                         |
      | txn_val_ac26_numeric_id        | transaction_id | 123                   | must be a JSON string               |
      | txn_val_ac26_amount_zero       | amount         | 0                     | must be greater than zero           |
      | txn_val_ac26_amount_negative   | amount         | -1                    | must be greater than zero           |
      | txn_val_ac26_amount_string     | amount         | "1000"                | must be a JSON number               |
      | txn_val_ac26_amount_scale      | amount         | 10.001                | has more decimals than USD allows (2) |
      | txn_val_ac26_amount_21_digits  | amount         | 100000000000000000000 | must have at most 20 integer digits |
      | txn_val_ac26_amount_tiny       | amount         | 1E-30                 | must have at most 18 decimal places |
      | txn_val_ac26_created_at_number | created_at     | 20230830              | must be a JSON string               |

  @AC26
  Scenario Outline: A field with a wrong label is rejected and starts nothing: <field> "<value>"
    Given the brief's sample transaction with transaction_id "<id>"
    And its "<field>" is the JSON string "<value>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "<field>" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | id                            | field      | value                 | message                        |
      | txn_val_ac26_type_unknown     | type       | Card Payment          | unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation |
      | txn_val_ac26_type_case        | type       | mobile top up         | unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation |
      | txn_val_ac26_state_settled    | state      | SETTLED               | must be SETTLED - PENDING FEE  |
      | txn_val_ac26_state_charged    | state      | SETTLED - FEE CHARGED | must be SETTLED - PENDING FEE  |
      | txn_val_ac26_state_pending    | state      | PENDING               | must be SETTLED - PENDING FEE  |
      | txn_val_ac26_state_case       | state      | settled - pending fee | must be SETTLED - PENDING FEE  |
      | txn_val_ac26_asset_unknown    | asset      | XYZ                   | unknown asset                  |
      | txn_val_ac26_asset_type_case  | asset_type | fiat                  | must be one of FIAT, CRYPTO    |
      | txn_val_ac26_created_at_date  | created_at | 2023-08-30            | must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS |
      | txn_val_ac26_created_at_blank | created_at |                       | must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS |

  @AC26
  Scenario Outline: A transaction the fee rules refuse is rejected and starts nothing: <case>
    Given the brief's sample transaction with transaction_id "<id>"
    And its type is "<type>"
    And it is <amount> "<asset>" of asset type "<asset_type>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "<field>" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | case                         | id                           | type          | amount | asset | asset_type | field      | message                                                         |
      | an asset of the other type   | txn_val_ac26_usd_crypto      | Mobile Top Up | 1000   | USD   | CRYPTO     | asset_type | USD is FIAT                                                     |
      | a disallowed asset type      | txn_val_ac26_bank_btc        | Bank Transfer | 1      | BTC   | CRYPTO     | type       | Bank Transfer does not allow CRYPTO; allowed asset types: FIAT |
      | a fee greater than the amount | txn_val_ac26_fee_over_amount | Bank Transfer | 0.10   | USD   | FIAT       | amount     | must be at least the fee of 0.50 USD                            |

  @AC26
  Scenario Outline: A transaction_id that is blank, too long or outside the pattern is rejected and starts nothing: <case>
    Given the brief's sample transaction with transaction_id "<id>"
    When it is submitted
    Then the response status is 400
    And the response content type is "application/problem+json"
    And the problem lists exactly the invalid field "transaction_id" with the message "<message>"
    And Restate has no invocation for "<id>"

    Examples:
      | case               | id                                                                | message        |
      | blank              |                                                                   | is required    |
      | 65 characters      | txn_val_ac26_too_long_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |
      | a space            | txn val ac26                                                      | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |
      | a slash            | txn/val/ac26                                                      | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |
      | a dot              | txn.val.ac26                                                      | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |
      | a non-ASCII letter | txn_val_ac26_é                                                    | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |

  @AC26
  Scenario: A transaction_id of exactly 64 characters is accepted
    Given the brief's sample transaction with transaction_id "txn_val_ac26_64_chars_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    When it is submitted
    Then the response status is 200
    And the response field "transaction_id" is "txn_val_ac26_64_chars_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

  @AC27
  Scenario: Every invalid field is listed, once, in one problem+json
    When this transaction is submitted:
      """
      {
        "transaction_id": "txn val ac27",
        "amount": 0,
        "asset": "XYZ",
        "asset_type": "fiat",
        "type": "Nope",
        "state": "SETTLED",
        "created_at": "2023-08-30"
      }
      """
    Then the response status is 400
    And the response field "type" is "/problems/invalid-request"
    And the response field "title" is "Invalid request"
    And the problem lists exactly these invalid fields:
      | transaction_id | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or - |
      | amount         | must be greater than zero |
      | asset          | unknown asset |
      | asset_type     | must be one of FIAT, CRYPTO |
      | type           | unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation |
      | state          | must be SETTLED - PENDING FEE |
      | created_at     | must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS |
    And Restate has no invocation for "txn val ac27"
