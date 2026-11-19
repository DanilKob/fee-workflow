-- fee_record: the system of record for every charged fee (AC24, D4). One row per transaction_id.
-- Its only writer is the workflow's `record` step. Every column is NOT NULL: the domain
-- guarantees every value before the row is written.
--
-- Money columns are numeric(38,18): 18 decimals hold the largest asset scale, and the other 20
-- digits are the integer room that Money.MAX_INTEGER_DIGITS enforces before any workflow starts
-- (Q1). Change one, change the other.
--
-- No column has a bound the fee schedule or the asset registry can outgrow: labels, codes, rule
-- ids and descriptions are text. The stored description is what GET and a replay render, never
-- the live schedule.
--
-- Timestamps are UTC local date-times without zone, at microsecond precision (D8).

CREATE TABLE fee_record (
    transaction_id varchar(64)    NOT NULL PRIMARY KEY, -- the AC26 bound on transaction_id
    amount         numeric(38,18) NOT NULL,
    asset          text           NOT NULL,
    asset_type     text           NOT NULL,
    type           text           NOT NULL,             -- the canonical label from the schedule
    fee            numeric(38,18) NOT NULL,
    rate           numeric(38,18) NOT NULL,
    description    text           NOT NULL,
    rule_id        text           NOT NULL,
    rule_version   integer        NOT NULL,
    created_at     timestamp(6)   NOT NULL,
    charge_id      uuid           NOT NULL,             -- from the FeeCharge Virtual Object's state
    charged_at     timestamp(6)   NOT NULL,
    state          text           NOT NULL CHECK (state = 'SETTLED'), -- D3, Q2
    fingerprint    char(64)       NOT NULL,             -- SHA-256 of the normalised payload (AC18)
    recorded_at    timestamp(6)   NOT NULL
);
