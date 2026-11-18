# Fee Workflow: Requirements

Source: [`brief.md`](brief.md). This document is `brief/analysis.md` with every open question **decided** (Section 3). Restate behaviour marked **[R]** was checked against docs.restate.dev on 2026-11-18. Behaviour marked **[V]** was also observed in this project's walking skeleton, against Restate server 1.8.1 and SDK 2.11.0.

---

## 1. The brief in plain words

**What we build.** A REST API in Kotlin with one public operation. A caller sends a transaction that has already settled and is waiting for its fee. The service works out the fee, charges it, records it and returns the result. A Restate workflow drives the three steps, so they survive crashes and retries. The fee calculation and the charge are persisted. Two extra transaction types demonstrate extensibility (D6).

**Input.** A JSON body describing one transaction:

| field | example | meaning |
|---|---|---|
| `transaction_id` | `"txn_001"` | the caller's unique id for the transaction |
| `amount` | `1000` | the transaction amount, a JSON number |
| `asset` | `"USD"` | the currency or token code |
| `asset_type` | `"FIAT"` | `FIAT` or `CRYPTO` |
| `type` | `"Mobile Top Up"` | the transaction type, which selects the fee rule |
| `state` | `"SETTLED - PENDING FEE"` | the lifecycle state; only this one is eligible for a fee |
| `created_at` | `"2023-08-30 15:42:17.610059"` | local date-time, space separator, microseconds, no zone |

**Output.** A JSON body with the fee result:

| field | example | meaning |
|---|---|---|
| `transaction_id` | `"txn_001"` | echoed |
| `amount` | `1000` | echoed, same numeric form |
| `asset` | `"USD"` | echoed |
| `type` | `"Mobile Top Up"` | echoed (canonical label) |
| `fee` | `1.5` | the fee charged, in the transaction's asset |
| `rate` | `0.0015` | the percentage rate applied (`0` for flat or free fees) |
| `description` | `"Standard fee rate of 0.15%"` | human-readable rule description |

`asset_type`, `state` and `created_at` are not in the response.

**Contract of `POST /transaction/fee`.** A Spring controller serves it (D1). Request and response bodies are snake_case JSON. The call is synchronous for up to 10 s. If the workflow hasn't finished by then, the response is `202 Accepted` with a status link (D11). Submitting the same `transaction_id` and payload again returns the same result and never charges twice. The same id with a different payload returns `409` (D7).

**The workflow, one instance per `transaction_id`:**
1. **Calculate:** look up the fee rule for `type` in the code registry (D5), compute the unrounded fee, round it once to the asset's scale with HALF_UP (D9), and build the description.
2. **Charge:** call the `FeeCharge` Virtual Object keyed by `transaction_id`. It mints a charge id and keeps the charge in Restate state. A repeat call returns the same charge (D2).
3. **Record:** write the `fee_record` row in Postgres (D4) with the fee, the rate, the rule applied, `charge_id`, `charged_at` and state `SETTLED` (D3). This step retries until it succeeds. It never fails terminally and never refunds (D2b).

---

## 2. Acceptance criteria

Each criterion can be checked by an automated test. "D*n*" refers to a decision in Section 3.

### Response shape and encoding

- **AC1.** For the brief's sample request, the raw response body equals the brief's expected body, parsed as JSON and compared field by field, with a 200 status and `Content-Type: application/json`.
- **AC2.** All JSON keys, in both request and response, are snake_case. The 200 response contains exactly `transaction_id, amount, asset, type, fee, rate, description`, with no extra keys and no nulls.
- **AC3.** Numbers are rendered in plain form, and the test checks the **raw text**, not a parsed value: `"amount":1000` (never `1000.0`, `1E+3` or `"1000"`), `"fee":1.5` (never `1.50`), `"rate":0.0015` (never `1.5E-3`). Rule: a value is printed as `stripTrailingZeros().toPlainString()` and emitted as a JSON number.
- **AC4.** `asset_type`, `state` and `created_at` are **not** echoed in the response.
- **AC5.** `amount` and `asset` are echoed as sent. `type` is echoed as the canonical label from the fee registry.

### `created_at` (D8)

- **AC6.** `created_at` is **required** and accepted only in the format `yyyy-MM-dd HH:mm:ss.SSSSSS`: a space separator, exactly 6 fractional digits, no zone. `2023-08-30T15:42:17.610059Z`, `2023-08-30 15:42:17`, `2023-08-30 15:42:17.610` and `2023-08-30 25:00:00.000000` each return 400.
- **AC7.** It is interpreted as UTC and stored without loss as `timestamp(6)` without time zone. Reading it back renders exactly `"2023-08-30 15:42:17.610059"`. The test runs with a non-UTC JVM default zone to prove nothing shifts.

### Money (D9, D-assets)

- **AC8.** Every monetary value and rate is a `BigDecimal` from HTTP parse to DB column and back. Neither `Double` nor `Float` appears on the money path. Inside Restate, money crosses the journal as a JSON **string**.
- **AC9.** The fee is rounded **once**, at the end, to the asset's scale with `HALF_UP`. The asset registry, with `asset_type` agreement enforced:

  | asset | asset_type | scale |
  |---|---|---|
  | USD, EUR, GBP | FIAT | 2 |
  | JPY | FIAT | 0 |
  | BTC | CRYPTO | 8 |
  | ETH | CRYPTO | 18 |
  | USDC, USDT | CRYPTO | 6 |

- **AC10.** These cases hold exactly, and each would catch a `Double` bug or the wrong rounding mode:
  - Mobile Top Up 1000 USD gives fee `1.5`, rate `0.0015`.
  - 10 USD gives **0.02**. `Double` gives 0.01.
  - 330 USD gives **0.50**. `Double` gives 0.49.
  - 30 USD gives **0.05** under HALF_UP. HALF_EVEN would give 0.04.
  - 1000 JPY gives `2` (scale 0).
  - 0.12345678 BTC gives `0.00018519` (scale 8).
- **AC11.** An unknown `asset`, or an `asset`/`asset_type` mismatch (`USD` as `CRYPTO`, `BTC` as `FIAT`), returns 400.
- **AC12.** An `amount` with more decimals than the asset's scale returns 400 (`10.001` USD). The fee is never negative and is charged in the transaction's asset.
- **AC13.** A fee larger than the amount returns **400** before any workflow starts (D6b). Example: Bank Transfer of 0.10 USD with a flat fee of 0.50 USD. A fee equal to the amount is allowed.

### Transaction types (D6, D6a)

- **AC14.** The registry holds exactly these types, and each is tested end to end:

  | type | kind | rule | allowed asset types | `rate` | description |
  |---|---|---|---|---|---|
  | Mobile Top Up | percentage | 0.15% | FIAT, CRYPTO | `0.0015` | "Standard fee rate of 0.15%" |
  | Bank Transfer | fixed, per asset | USD/EUR/GBP 0.50, JPY 75 | FIAT | `0` | "Flat fee of 0.50 USD" |
  | Charity Donation | none | no fee | FIAT, CRYPTO | `0` | "No fee for charity donations" |

- **AC15.** A type used with an asset type it doesn't allow returns 400 (Bank Transfer in BTC). So does a fixed-fee type with no amount configured for the asset.

### Idempotency (D7)

- **AC16.** The workflow is keyed by `transaction_id`, one instance per id **[R]**. A second submission of the same workflow id is refused by Restate with 409 "the workflow method was already invoked" **[V]**. In that case the controller reattaches to the running workflow instead of failing.
- **AC17.** Submitting the same payload again, whether in sequence or 10 at once, returns 200 with a body identical to the first. Afterwards exactly **one** charge (one `charge_id`) and **one** `fee_record` row exist.
- **AC18.** The same `transaction_id` with a **different** payload returns **409 Conflict** (problem+json). It neither charges nor changes the record. "Different" is a SHA-256 fingerprint over the normalised payload: `amount` is compared numerically (`1000` equals `1000.00`), plus `asset`, `asset_type`, `type`, `state` and `created_at`. The fingerprint is stored in workflow state at the start of `run` and on `fee_record`.
- **AC19.** Idempotency still holds **after Restate's 24 h workflow retention** expires **[R][V: `workflow_completion_retention: 1d`]**. If a later workflow runs for an existing id:
  - The workflow first checks `fee_record`. An identical fingerprint returns the stored result. A different fingerprint gives a terminal 409.
  - The `FeeCharge` Virtual Object keeps its state indefinitely **[R]** and returns the original `charge_id`, so nothing is charged twice.

### Durability and determinism

- **AC20.** Calculate and record run as named durable steps (`runBlock("calculate")`, `runBlock("record")`). The charge is a Virtual Object call, which Restate records in the workflow's journal **[R]**.
- **AC21.** A crash or failure **after the charge but before the record** ends, once Restate retries, with one charge and one record whose `charge_id` matches the Virtual Object's state.
- **AC22.** Nothing non-deterministic runs outside a durable step: no `Clock.System.now()`, `Instant.now()`, `UUID.randomUUID()`, DB or HTTP calls. Time comes from `Clock.Restate.now()` and ids from `random().nextUUID()` **[R]**. The fee rule is resolved inside `calculate`, so a rule change deployed mid-flight cannot change a fee that's already journaled.
- **AC23.** No step after the charge fails terminally (D2b). `record` retries until it succeeds. The invocation uses `on_max_attempts = Pause`, which is the server default: 70 attempts, then pause **[V]**. It is **never** set to `Kill`. Errors before the charge (validation, unknown rule, 409 conflict) are `TerminalException`s and are not retried.

### Persistence (D4)

- **AC24.** Postgres is the system of record. Flyway migrations create a `fee_record` table with primary key `transaction_id`. It holds the request fields (including `created_at timestamp(6)`), fee, rate, rule id and version, description, `charge_id`, `charged_at`, state, payload fingerprint and `recorded_at`.
- **AC25.** The charge itself lives in the `FeeCharge` Virtual Object's state (`charge_id`, amount, asset, `charged_at`), keyed by `transaction_id`. Calling `charge` again for the same key returns the same `charge_id`.

### Validation and errors (D10, D-state)

- **AC26.** Each of these returns **400**, and none creates a workflow, a charge or a record. Afterwards Restate has no invocation for that id.
  - malformed JSON, or a missing or `null` required field;
  - an unknown `type`;
  - a missing `state`, or any value other than `SETTLED - PENDING FEE`;
  - an `amount` that is zero, negative, a JSON string, or over the asset's scale;
  - an unknown or mismatched asset (AC11), or a disallowed asset type for the type (AC15);
  - a fee greater than the amount (AC13);
  - a bad or missing `created_at` (AC6);
  - a blank `transaction_id`, or one over 64 characters or outside `[A-Za-z0-9_-]`.
- **AC27.** Errors use RFC 9457 `application/problem+json`: `{"type","title","status","detail","errors":[{"field","message"}]}`. A response lists every invalid field. Status codes: 400 for bad input, 409 for a payload conflict, 503 when Restate is unreachable (nothing charged, safe to retry).

### Slow workflows (D11)

- **AC28.** If the workflow hasn't finished within 10 s (a config property), `POST` returns `202 Accepted` with `Location: /transaction/{id}/fee` and `{"transaction_id":"…","status":"PROCESSING"}`. The workflow keeps running.
- **AC29.** `GET /transaction/{id}/fee` returns 200 with exactly the POST's 200 body once the workflow is done, 202 while it's still running, and 404 (problem+json) for an unknown id.

### Testing

- **AC30.** An **end-to-end test** sends the brief's sample payload to the real `POST /transaction/fee` over HTTP. The app runs on a random port against a real Restate server and real Postgres (Testcontainers), and the app's Restate endpoint is registered through the admin API **[V: walking skeleton `HelloWorkflowIT`]**. The test asserts AC1 and AC3 on the raw body, then asserts one `charge_id` and one `fee_record` row with the right values.
- **AC31.** The fee calculator, the number serializer, the date parser and the validator each have plain unit tests (Surefire, `*Test`). Behaviour is also described as Cucumber features and scenarios (the brief's bonus), run through the same HTTP path as AC30 (Failsafe, `*IT`).

---

## 3. Decisions

| # | Question | **Decided** | Rejected alternative | Reason |
|---|---|---|---|---|
| D0 | Restate API and build | **Reflection-based Kotlin API** (SDK 2.11.0), **Maven**, no KSP. Clients are typed proxies: `client.workflow<FeeWorkflow>(id).run(req)`. | KSP code generation (Gradle-only, deprecated since SDK 2.9.0). | Current default API. The spike and skeleton prove it on Maven. |
| D0b | Platform | **Spring Boot 4.1.2**, **Java 21**, Restate server **1.8.1**. Overrides needed by the Restate SDK: Kotlin 2.4.21, kotlinx-coroutines 1.11.0, Netty 4.1.141, plus Jackson 2 `jackson-databind` (Boot 4 ships Jackson 3). | Spring Boot 3.5.x (what Restate builds against; free support ended June 2026). | The current stable line. Each override is documented in `pom.xml`. On JDK 21 the SDK warns that its pure-Java engine is deprecated (JDK 23+ recommended). |
| D1 | Who serves `POST /transaction/fee` | **Spring controller** (port 8081) calling the Restate ingress (8080) through the `Client` bean. The SDK endpoint is on 9080. | Restate ingress directly, as service `transaction`, handler `fee`. Today that path is `/restate/call/transaction/fee`; the bare path was Restate 1.6 and earlier. | Exact path; full control of number format, 400/409/202/503 and validation before any workflow exists. |
| D2 | What "charge" acts on | **`FeeCharge` Virtual Object keyed by `transaction_id`**. Its state holds `charge_id` (from `random().nextUUID()`), amount, asset and `charged_at`. A repeat call returns the same charge. `charge_id` and `charged_at` are copied onto `fee_record`. | Postgres double-entry ledger; fake external payments service; status flag only. | Charged once by Restate itself (one writer per key, journaled call). Charge (Restate) and record (Postgres) sit in different systems, so they really are two steps. |
| D2b | Recording keeps failing after the charge | **Retry, then pause.** `record` never fails terminally. Restate retries, then pauses the invocation for an operator to resume. No refund handler. | Saga: bounded retries, refund, mark FEE FAILED. | A short DB outage must not give up a correctly charged fee. A paused invocation is visible and recoverable. |
| D3 | State after the fee | **`SETTLED`**, stored on `fee_record`, not returned. | `SETTLED - FEE CHARGED`; not tracked. | The input's naming suggests the fee is the last thing pending. |
| D4 | System of record | **Postgres**, schema by **Flyway**. JPA arrives with the first entity (together with the `kotlin-jpa` plugin). | Restate state only. | Fee records must outlive Restate's 24 h retention and be queryable by SQL. |
| D5 | Where fee rules live | **Kotlin code registry** (`FeeSchedule`): typed rules, validated at startup, rule id and version stored on each record. | `application.yml`; a DB table. | Every rule change gets review and runs the tests. A config or DB source can sit behind the same interface later. |
| D6 | Extra transaction types | **A small, fully tested set:** Bank Transfer (fixed, per asset, FIAT only) and Charity Donation (no fee), next to Mobile Top Up. **TODO, if time allows:** Card Payment (% + fixed), International Transfer (tiered), Crypto Withdrawal (% with min/max cap). | Full set of four new kinds; none. | Shows the one-place extension with two new fee kinds and minimal scope. |
| D6a | `rate` for non-percentage fees | **`0`**, with the description explaining the fee ("Flat fee of 0.50 USD"). | Effective rate (fee / amount); omit `rate`. | Same response shape for every type. |
| D6b | Fee larger than the amount | **400 before the workflow starts.** | Cap the fee at the amount; charge it anyway. | Never charge more than the transaction is worth. |
| D-assets | Accepted asset types | **FIAT and CRYPTO** with the per-asset registry (AC9). | FIAT only. | Exercises per-asset scale for real (BTC 8, ETH 18, JPY 0). |
| D7 | Same id, different payload | **409 Conflict** via payload fingerprint (AC18). | Return the original result. | A changed amount under the same id is a caller bug. |
| D8 | `created_at` strictness | **Strict, required, stored:** exactly 6 fractional digits, UTC, `timestamp(6)`. | Lenient 0–6 digits; optional and not stored. | The brief shows one format. Uniform storage. |
| D-state | Input `state` | **Required; must equal `SETTLED - PENDING FEE`.** | Optional, defaulted to PENDING FEE. | Guards against charging unsettled or already-charged transactions. |
| D9 | Rounding | **HALF_UP at each asset's scale**; amounts over the scale return 400. | HALF_EVEN; HALF_UP with 2 decimals for every asset. | Matches what people get by hand, and is correct for JPY and crypto. Pinned by the 30 USD case. |
| D10 | Error shape | **RFC 9457 problem+json**; 400, 409, 503. | `{error, message}` with 400/409/502/504. | A standard Spring supports natively. Lists every bad field. |
| D11 | Slow workflow | **Wait 10 s, then `202 Accepted` + `Location: /transaction/{id}/fee`**; GET returns 200/202/404. | 30 s then 504; wait indefinitely. | 202 is accurate (nothing failed). We answer before client or proxy timeouts. Resending the POST or polling the GET are both safe. |

---

## 4. Extensibility: a new type in one place, with one test

**Model:**
- `FeeRule` is a sealed interface. Each kind implements `compute(amount, asset): BigDecimal` (unrounded), `nominalRate()` and `describe(asset)` itself, so no `when (type)` exists elsewhere. Implemented kinds: `Percentage(rate)`, `Fixed(perAsset)` and `NoFee`.
- `TransactionType(label, rule, allowedAssetTypes, ruleId, version)`.
- `FeeSchedule` is the single list of `TransactionType` entries, in one file. Validation and calculation both look types up there, and an unknown label is a 400, never a default fee.
- Rounding to the asset's scale happens once, in `FeeCalculator`.

**Adding a type:**
1. Add one entry to `FeeSchedule`.
2. Add one row to the Cucumber `Scenario Outline` examples table in `fees.feature` (type, asset, amount → fee, rate, description). That row runs through the real HTTP endpoint and Restate.

**Built-in safety net, with no extra work per type:**
- A registry contract test iterates **every** registered type and checks that:
  - a description exists, with no unfilled placeholders;
  - the fee is ≥ 0 for sample amounts in each allowed asset;
  - `nominalRate` is in [0, 1);
  - each fixed fee has an amount for every allowed asset of its asset type.
- Startup validation fails fast on duplicate labels or malformed rules.

**Adding a new fee kind** (the TODO list in D6) means one new `FeeRule` subclass plus its unit tests. The compiler forces it to implement `compute` and `describe`.

---

## 5. Risks: where a mistake costs money or data, and the test that catches it

| # | Risk | Cost | Test that catches it |
|---|---|---|---|
| R1 | A second charge for the same transaction (retry, concurrent submissions, or a new workflow after retention) | money: double charge | Fire 10 identical requests in parallel: identical bodies, one `charge_id`, one record. Expire the workflow (short retention in the test profile, or purge), resubmit: same body and same `charge_id` (AC17, AC19). |
| R2 | A crash between charge and record loses the record | data: a charge with no record | Fault injection: `record` throws on its first N attempts. After the retries, one record whose `charge_id` matches the Virtual Object's state (AC21). |
| R3 | A terminal error after the charge, or `on_max_attempts = Kill`, drops the workflow | money and data | Assert the deployment's effective retry policy is `Pause` (admin API). A unit test asserts `record` never throws `TerminalException` (AC23). |
| R4 | The same id with a different amount silently returns the old result, or charges the new one | money and data | Submit 1000, then 2000, under the same id: 409, one `charge_id`, record still 1000 (AC18). Repeat after workflow retention expiry. |
| R5 | `Double` creeps into the path (Jackson, kotlinx, JDBC) | money: off by a cent, systematically | The AC10 table end to end, plus a large-amount round trip (`12345678901234567.89`), plus an architecture test banning `Double`/`Float` in money packages. |
| R6 | Wrong scale or rounding mode for an asset | money | A parameterised test over the asset registry, plus the 30 USD HALF_UP case (AC10). |
| R7 | Number rendering breaks the contract (`1E+3`, `1.50`, `1000.0`) | contract: client parsers break | A raw-string golden test on the response body (AC3). |
| R8 | Non-determinism outside durable steps causes a journal mismatch on replay | workflow stuck, or an inconsistent fee | An architecture test bans `Clock.System`, `Instant.now`, `UUID.randomUUID` and repository calls in workflow code outside `runBlock`. The R2 fault test exercises replay (AC22). |
| R9 | An unknown type gets a default or zero fee instead of a 400 | money: lost revenue | Unknown type → 400, no Restate invocation for the id, no charge (AC26). |
| R10 | An ineligible or missing `state` is charged | money | Each non-eligible or missing state → 400 and no charge (AC26). |
| R11 | `created_at` shifts by a time zone, or loses microseconds | data | A round trip renders exactly `2023-08-30 15:42:17.610059`, with the JVM default zone set to a non-UTC zone (AC7). |
| R12 | A flat fee exceeds a tiny amount | money: the customer pays more than they moved | Bank Transfer of 0.10 USD → 400; the registry contract test covers new rules too (AC13). |
| R13 | A fee rule edit changes historical or in-flight fees | data: the audit trail is wrong | The record stores rule id and version. A journaled `calculate` result is reused on replay (fault test with the rule swapped between attempts) (AC22). |
| R14 | Restate is down: a blind client retry, or a charge outside the workflow | money | With Restate stopped: 503 problem+json, no charge. After restart the same request succeeds once (AC27). |
| R15 | A slow workflow leaves the caller unsure whether to retry | money (double submission) or support load | Slow `record` in a test: 202 + `Location`, then GET returns the exact 200 body once done, with one `charge_id` (AC28, AC29). |
| R16 | Spring Boot 4 vs Restate SDK version drift (Kotlin, coroutines, Netty, Jackson 2) | the app fails to start or serve | `HelloWorkflowIT`, and later the end-to-end test, run the real Spring-hosted endpoint against a real Restate server on every `verify` (D0b). |
