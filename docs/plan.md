# Fee Workflow: Implementation Plan

Status: **all eight slices as built, 2026-11-19, each approved by the FULL review gate; not yet committed.** Approved at Gate 1 after two `/wikingai:plan-review` rounds. Builds every acceptance criterion of [`requirements.md`](requirements.md)
on the walking skeleton, in six thin vertical slices. Each slice ends with `./mvnw -q verify` green
and is committable on its own. When a slice ships, its heading gets "as built", the tier that ran
and any deltas.

Size: **large.** It adds a public API, money, idempotency, a new schema and four packages.

---

## 1. What and why

A caller (the platform that settles transactions) sends one settled transaction that is waiting
for its fee. It wants back the fee that was charged, and it wants a retry never to charge twice.
The requirements doc decides every behaviour (AC1–AC31, D0–D11). This plan decides **order,
structure and tests**, plus the owner decisions below.

**Owner decisions taken at plan review** (each lands in `requirements.md` on approval, §10):

| # | Decision |
|---|---|
| Q1 | **Amount ceiling:** at most 20 integer digits (the integer room of `numeric(38,18)`). More is a 400 on `amount`. |
| Q2 | **Stored state:** `fee_record.state` holds only D3's `SETTLED` (`CHECK (state = 'SETTLED')`). No `input_state` column: validation accepts only `SETTLED - PENDING FEE`. |
| Q3 | **Zero fee:** a fee that rounds to 0 still mints a FeeCharge, so `charge_id` is always set. No minimum fee. |
| Q4 | **Ambiguous 503:** a transport failure that isn't a refused connection answers "outcome unknown; resending the same request is safe". Only a refused connection says "nothing was started". |
| Q5 | **R14:** its test is the closed-port 503, with no Restate restart. |
| Q6 | **A run that ended with neither a record nor a stored rejection** happens only after an operator cancel or kill, which the runbook forbids. GET answers 202 for it until retention ends. Backlog entry (§10). |
| Q7 | **Mid-flight rule rejection:** when `calculate`'s re-check fails, the caller gets 400 (tag `rule_rejected`), not 500. |
| Q8 | **After the workflow rejects its input,** GET and a duplicate POST answer the same 4xx problem the POST got. The rejection is kept in workflow state. |
| Q9 | **Orphan charge, unchanged payload:** when the same payload is resent and a rule change has moved the fee meanwhile, the stored charge is honoured and the record copies its stored fee. |
| Q10 | **Amount echo:** `1000.00` comes back as `1000`. "Echoed as sent" (AC5) means the same number, rendered by AC3's rule. Raw-text checks write AC10's `0.50` as `0.5`. |
| Q11 | **Matching:** `type`, `asset` and `asset_type` match exactly, case-sensitive. `Transaction` holds the registry spelling. |
| Q12 | **SDK port 9080:** a documented trust boundary, no request signing. compose doesn't publish 9080. |
| Q13 | **Scale counts significant decimals:** `10.000` USD is valid (`stripTrailingZeros().scale() <= 2`), matching the fingerprint's normalisation. |
| Q14 | **Business logic lives in services** (2026-11-19, after slice 6): each is a `…Service` interface with a `…ServiceImpl`, never an `object` or a plain class. Services in `domain` stay Spring-free and are wired as beans in `config/DomainConfig`. Slice 7 applies it. |
| Q15 | **Value helpers stay `object`s** (`Money` superseded by Q18): `CreatedAtFormat` is renamed `DateFormatter` (one fixed timestamp format). |
| Q16 | **Registries are interface + `Default…`:** `AssetRegistry` + `DefaultAssetRegistry`, mirroring `FeeSchedule` + `DefaultFeeSchedule`. No "Service" suffix, because they hold data, not logic. |
| Q17 | **Infrastructure codecs stay `object`s:** `RejectionMetadata` (the Restate error-tag codec) and the kotlinx serializers, on an explicit allow-list. |
| Q18 | **`Money` is a value object** (supersedes Q15 for `Money`): `Money(amount, asset)` holds its invariants (at most 20 integer digits, no more significant decimals than its asset's scale), renders itself (`plain()`) and rounds a raw fee once, HALF_UP at its asset's scale. `Transaction.amount` and `FeeQuote.fee` are `Money`. The read model (`FeeRecord`, `FeeResult`), the journal types and the database keep `BigDecimal` plus an asset code; the mappers convert, so no wire or schema shape changes. The bounds become constants on `Money`'s companion object; the top-level `object Money` leaves the allow-list. |
| Q19 | **The domain builds a valid `Transaction` through a factory**: `TransactionFactory` (interface) + `TransactionFactoryImpl` in `domain` (the DDD term, a service in all but name; ArchUnit's impl rule covers `*FactoryImpl` as well). `create(TransactionInput)` takes the parsed but unchecked fields and returns `Created(transaction)` or `Invalid(violations)`, every violation at once (AC27). It owns every domain check: presence, the id pattern, the eligible state, strict `created_at`, a known asset that agrees with its asset type, and the `Money` bounds. The API keeps only JSON-shape errors (a missing body, a wrong JSON type) and maps the violations to fields. `FeeWorkflow`'s input re-check calls the same factory, so each rule exists once. `Transaction`'s constructor is internal to the domain, so nothing else can build an invalid one. |

**Assumptions** (the owner can overturn these at Gate 1):
- **Success:** all 31 AC scenarios are green under `./mvnw -q verify`, and a clean checkout runs
  from the README in a few commands. There is no metric with a baseline: this is a take-home with
  no production traffic (R-M1).
- **Cut line:** no AC is cut. If time runs short, cut in this order: Swagger UI (6.4), README depth
  beyond setup, architecture and the runbook, then the D6 TODO types (already out of scope).

## 2. Structure: packages and the allowed dependencies

Base package `com.cashi.fee`. Each package is a config area; `platform` owns the root and `config` packages.
Dependencies: `api → service → domain`; `workflow → service, domain`; `persistence → domain`;
`config → domain`.

**Naming rule:** an interface is named after its role (`FeeService`, `FeeRecordRepository`,
`FeeWorkflowGateway`). An implementation is named after its technology (`JpaFeeRecordRepository`,
`RestateFeeWorkflowGateway`), or `…Impl` when there is no technology (`FeeServiceImpl`).

**Shape rule (Q14, from slice 7):** business logic lives in a `…Service` interface and its
`…ServiceImpl`. A top-level `object` holds no public functions, except the allow-list: `DateFormatter`
(a value helper, Q15), `RejectionMetadata` and the serializers (codecs, Q17), and
`FeeApiExamples` (constants). Factories and constants on a `companion object` stay. `ArchitectureTest`
enforces it (task 7.4).

| Package | Holds | May import |
|---|---|---|
| `api` | `FeeController` (thin: validate, call `FeeService`, map the result to HTTP), `TransactionFeeRequest` (raw, every field nullable), `TransactionFeeResponse` (one factory, `from(FeeResult)`), `FeeRequestValidationService` + `FeeRequestValidationServiceImpl`, `InvalidField`, `ApiExceptionHandler`, `JacksonConfig`, `OpenApiConfig` | `service`, `domain` |
| `service` | `FeeService` (interface) and `FeeServiceImpl`: the use case (fingerprint, send, duplicates, waiting, the outcome); `FeeChargeResult` (sealed: `Completed`, `Rejected`, `Failed`); `FeeWorkflowGateway` (port: send, wait for output, status; its sealed results nested inside it, speaking domain and service types only); `GatewayPhase`; `RejectionReason` (`PAYLOAD_CONFLICT("payload_conflict", 409)`, `RULE_REJECTED("rule_rejected", 400)`, `fromWire`); `GatewayFailureMappingService` + `GatewayFailureMappingServiceImpl` (slice 4) | `domain` |
| `workflow` | `FeeWorkflow`, `FeeCharge` (Virtual Object), `RestateFeeWorkflowGateway` (**the only class that touches the Restate `Client`**, `IngressException` or `SendStatus`; it implements the service port and maps to and from the journal types), `workflow/model/*` (the `@Serializable` journal types with explicit `@SerialName`s, their mappers, the `BigDecimalAsString` / `LocalDateTimeAsString` serializers, `RejectionMetadata`: the `reason` metadata on a `TerminalException` and the message-prefix fallback) | `service`, `domain` |
| `domain` | `Asset`, `AssetType`, `AssetRegistry` (interface) and `DefaultAssetRegistry`, `FeeRule` (sealed: `Percentage`, `Fixed`, `NoFee`), `TransactionType`, `FeeSchedule` (interface) and `DefaultFeeSchedule`, `FeeCalculationService` + `FeeCalculationServiceImpl` (**the one owner of the fee rules**: returns a `FeeQuote` or a list of `RuleViolation`s), `Transaction` (always valid: its constructor is internal to `domain`), `TransactionFactory` + `TransactionFactoryImpl` (Q19: `create(TransactionInput)` returns `Created` or `Invalid` with every `RuleViolation` on a `TransactionField`), `FeeResult` (what a 200 says), `PayloadFingerprintService` + `PayloadFingerprintServiceImpl`, `FeeScheduleValidationService` + `FeeScheduleValidationServiceImpl`, `DateFormatter` (strict parse and format), `Money(amount, asset)` (Q18: a value object that enforces its bounds; its companion holds `MAX_INTEGER_DIGITS = 20`, `MAX_SCALE = 18`, `check`, `rounded` and the cheap bound helpers), `FeeRecord` and its `FeeRecordRepository` port | the JDK and the Kotlin stdlib only (`java..`, `kotlin..`, `org.jetbrains.annotations..`): no Spring, Jakarta, Restate or kotlinx |
| `persistence` | `FeeRecordEntity`, `FeeRecordJpaRepository` (Spring Data), `JpaFeeRecordRepository : FeeRecordRepository` | `domain` |
| `config` | `DomainConfig` (`@Bean`s, in order: `AssetRegistry` (`DefaultAssetRegistry`), `FeeScheduleValidationService`, `FeeSchedule` (validated by it while its bean is built), `FeeCalculationService`, `PayloadFingerprintService`). As built: moved here from the root package on owner request | `domain` |
| root `com.cashi.fee` | `FeeWorkflowApplication` only | nothing |

The domain stays Spring-free: its services (`FeeCalculationService`, `PayloadFingerprintService`,
`FeeScheduleValidationService`) and registries (`AssetRegistry`, `FeeSchedule`) are interfaces with
plain Kotlin implementations, unit-tested without Spring and wired in `config/DomainConfig`. `FeeRule`
and the other value types stay plain data. The Restate components keep their shape: their class
names are the Restate service names.

**Journal types** (`workflow/model`, all `@Serializable` with an explicit `@SerialName`, money as
strings, times as ISO strings):
- `FeeWorkflowInput(transactionId, amount, asset, assetType, type, state, createdAt, fingerprint)`
- `QuoteJournal(fee, rate, description, ruleId, ruleVersion)`
- `LookupJournal`, `CalculationJournal` (`Quoted` or `Rejected`)
- `ChargeRequest(fee, asset, fingerprint)`
- `Charge(chargeId, fee, asset, fingerprint, chargedAt)`
- `RecordJournal(...)`, the journaled form of a `FeeRecord`
- `Rejection(status, reason, detail)`
- `WorkflowStatus(fingerprint, rejection)`
- `FeeWorkflowOutput(result fields…, fingerprint)`

Every `runBlock` result is a journal type, never a `domain` type.

The workflow reaches Postgres only through the `FeeRecordRepository` port, which `persistence`
implements. `fee_record` has **one writer**, the `record` step. `ArchitectureTest` (ArchUnit,
Surefire, `ImportOption.DoNotIncludeTests`) enforces this table from slice 1, with each row as a
layer. It grows in slice 2 (no `Double`/`Float` on the money path) and slice 6 (determinism, the
gateway and the single writer).

## 3. Runtime flow: decided here, details in requirements

Both endpoints are `suspend fun`. Spring MVC runs them through Reactor's `mono {}`, so
`kotlinx-coroutines-reactor` is on the classpath (task 1.1), and nothing calls `runBlocking`
(KS-R3).
- Virtual threads stay on for blocking servlet work.
- Coroutines are used only where the Restate client or SDK is suspend.
- **Every JDBC call from suspend code** (`FeeServiceImpl`'s `records.find`, the workflow's `lookup`
  and `record`) runs under `withContext(Dispatchers.IO)` (KS-D5).
- Every `catch` in suspend code rethrows `CancellationException` first (KS-R2).

**`POST /transaction/fee`**: `FeeController` runs step 2 (validation) and calls
`FeeService.chargeFee(transaction)`; `FeeServiceImpl` runs steps 3–6 and returns a sealed result
the controller maps to HTTP.
1. Jackson binds `TransactionFeeRequest`. `amount` is a `JsonNode`, so a JSON string can be rejected, and
   `USE_BIG_DECIMAL_FOR_FLOATS` is on, so nothing passes through `double`.
2. `FeeRequestValidationService` collects **every** field error (AC26, AC27). It keeps only the JSON
   shape (a missing body, a wrong JSON type) and builds a `TransactionInput`; the domain's
   `TransactionFactory` does every domain check below and returns all its violations at once, which the
   service maps to JSON field names (Q19). Pricing (fee > amount) then runs once the four priced fields
   pass.
   - The **amount ceiling runs first**: more than `Money.MAX_INTEGER_DIGITS` (20) digits before
     the decimal point (`amount.precision() - amount.scale() > 20`) is a field error on `amount`.
     It is checked before anything renders the number (`toPlainString`, the fingerprint, the
     quote), so `1E+20000000` is never expanded. The integer-digit count is a `Long`, so
     `1E+2147483647` can't overflow past it. Right after it, more than `Money.MAX_SCALE` (18)
     significant decimals is refused too, so a tiny exponent (`1e-500000000`) never reaches
     rounding either. `Money.hasMoreDecimalsThan` decides from precision and scale, without
     arithmetic.
   - Labels match exactly (Q11). The scale check counts significant decimals (Q13).
   - The rules come from `FeeCalculationService`, and the validator maps its `RuleViolation`s to field
     errors. `checkRules` (type known, asset known, asset vs asset type, and type vs allowed asset
     types from slice 3) runs whenever its own fields are valid, whatever the amount. The amount's
     bounds, greater than zero and decimals vs the asset's scale are `Money`'s checks, run by the
     factory. Pricing (fee > amount, compute, round) runs only when every field is valid
     and no rule is broken, so an invalid amount never hides another field's error, and no field is
     listed twice (AC27).
   - The result is a `Transaction` or a 400 problem. An unknown type's error lists the valid
     labels.
3. `PayloadFingerprintService.of(transaction)`: SHA-256, lowercase hex, over the canonical string
   `amount.stripTrailingZeros().toPlainString()`, `asset`, `asset_type`, `type`, `state` and
   `created_at` (as `DateFormatter` formats it), joined with `\u001F` (AC18).
4. `gateway.send(cmd)`. The adapter uses `client.toWorkflow<FeeWorkflow>(id).request { run(cmd) }
   .send()`: still the typed proxy of D0, sent instead of called, so the controller can wait with
   a deadline. The result is `Accepted`, `Duplicate` or a classified failure.
   - **Duplicate** (`SendStatus.PREVIOUSLY_ACCEPTED`, or the 409 "already invoked" seen in the
     spike):
     1. First `records.find(id)`. Once a record exists, **its fingerprint decides** (D4): equal
        means 200 from the record, with no wait; different means 409.
     2. With no record, read `gateway.status(id)`:
        - a different fingerprint means 409;
        - an equal fingerprint with a stored rejection means that rejection's problem (Q8);
        - an equal fingerprint means wait (step 5);
        - a null fingerprint means wait, then compare the fingerprint in the output.
     3. When a wait after a null fingerprint hits the deadline, re-read the status: different
        means 409, equal means 202, still null means 503 "not started yet; retry with the same
        payload".
     
     A payload that hasn't been checked never gets a 202.
   - **Failures** go through `GatewayFailureMappingService(phase, failure, payloadChecked)`, a pure
     function in `service` (case 6 through its own `recordLookupFailed()`):
     1. `send` fails with a `ConnectException` or an unresolved host: 503 "Restate unreachable;
        nothing was started; resend the same request", with `Retry-After`.
     2. `send` fails with status −1 and any other cause: 503 "outcome unknown; resending the same
        request is safe" (Q4).
     3. `send` gets a non-2xx status other than the duplicate 409: retryable 503.
     4. `status` fails, in POST or GET: 503.
     5. Waiting hits a transport error after an `Accepted` send or a matched fingerprint: 202 +
        `Location`. After a null fingerprint it re-reads the status instead, as in step 4.3.
     6. `FeeServiceImpl`'s `records.find` throws a `DataAccessException` or `TransactionException`: 503 "fee records
        unavailable; resending the same request is safe", with `Retry-After`. It never falls
        through to `status`.
     
     Each 5xx is logged once, with the `transaction_id` and the phase, and never echoes the
     cause to the caller (O1). The gateway logs each ingress failure once at WARN (id, phase,
     status, cause) before classifying it; a duplicate send and a tagged rejection are outcomes, not
     failures, and aren't logged. The catch-all 5xx log reads the `transaction_id` from a request
     attribute the controller sets, not from MDC: the handler runs after the coroutine, on another
     thread.
5. **Wait by polling, never by attaching.** `gateway.awaitOutput(id, deadline)` polls
   `client.workflowHandle<FeeWorkflowOutput>("FeeWorkflow", id).getOutputSuspend()`. A 470 comes back
   as `Output.notReady()`. It polls first after 50 ms, then doubles the interval up to 1 s, until
   the output is ready or `fees.sync-wait` (default 10 s) passes. The deadline stops the polling;
   nothing on the server is cancelled or left open.
   - Ready: 200.
   - Deadline: 202 with `Location: /transaction/{id}/fee`, the body
     `{"transaction_id":"…","status":"PROCESSING"}` and `Retry-After: 1` (AC28).
6. **409 is never chosen by status code alone.** Restate's cancel and kill also use code 409.
   The gateway reads the tag from the failure's body: the `reason` metadata, or the message prefix
   under the 1.4 fallback (the client exposes only status and body).
   - `payload_conflict`, or a fingerprint mismatch found in step 4: 409 problem+json, with the
     fixed detail "this transaction_id was already used with a different payload".
   - `rule_rejected`: 400 problem+json (Q7).
   - No tag: a logged 500 from `ApiExceptionHandler`.

**`FeeWorkflow.run(cmd)`** (one instance per `transaction_id`):
1. `state().set(FINGERPRINT, cmd.fingerprint)`, the first action.
2. `runBlock("lookup") { withContext(Dispatchers.IO) { records.find(id) } }`. A record with the
   same fingerprint returns its stored outcome. A record with a different fingerprint calls
   `rejectBeforeCharge(409, "payload_conflict", …)`. This is AC19, after retention.
3. `runBlock("calculate") { calculator.quote(cmd) }`. The rule is resolved **inside** the step
   (AC22). The same `FeeCalculationService` rules run again, so a rule redeployed mid-flight can't charge
   more than the amount. Violations call `rejectBeforeCharge(400, "rule_rejected", …)` (Q7).
4. `virtualObject<FeeCharge>(id).charge(ChargeRequest(fee, asset, fingerprint))`, wrapped in a
   catch that stores a `Rejection` from a tagged terminal failure and rethrows it.
   - A first call mints `random().nextUUID()` and `Clock.Restate.now()`, and stores them with fee,
     asset and fingerprint (AC25). A zero fee is charged like any other (Q3).
   - A repeat with the **same fingerprint** returns the stored charge, even when a rule change has
     moved the fee since (Q9).
   - A repeat with a **different fingerprint** changes nothing and throws
     `TerminalException(409, …, {reason: payload_conflict})`, even when the fee is equal. This
     protects a charge that has no record (after an operator cancel, or lost database data,
     followed by retention).
   - FeeCharge is **ingress-private**: only `FeeWorkflow` can call it.
5. `runBlock("record") { withContext(Dispatchers.IO) { records.save(...) } }`.
   - It copies fee, asset, `charge_id` and `charged_at` from the returned `Charge`, and takes
     `recorded_at` from `Clock.Restate.now()` read before the step.
   - `save` is safe to replay: an assigned-id `repository.save` on a plain entity merges, so a
     commit whose journal entry was lost replays cleanly.
   - Nothing after the charge throws `TerminalException` (AC23).
6. It returns `FeeWorkflowOutput`: the result fields plus the fingerprint, which the controller strips.

Every pre-charge rejection goes through one function,
`private suspend fun rejectBeforeCharge(status: Int, reason: RejectionReason, detail: String): Nothing`. It
first runs `state().set(REJECTION, Rejection(status, reason, detail))`, outside any `runBlock`,
and then throws `TerminalException(status, detail, mapOf("reason" to reason))`. ArchUnit checks
that nothing else constructs a terminal failure in the workflow (task 6.3).

**`@Shared status()`** returns `WorkflowStatus(fingerprint, rejection)`, with null fields when
none is stored.

**`GET /transaction/{id}/fee`:**
1. Check the AC26 `transaction_id` pattern first: a bad id is a 400 that queries nothing.
2. If `records.find(id)` finds a record, return 200 with the same `TransactionFeeResponse.from(FeeResult)`
   the POST uses (AC29).
3. Otherwise, if `status` has a rejection, return that problem (Q8).
4. Otherwise, if it has a fingerprint, the workflow is running: 202 with the AC28 body, `Location`
   and `Retry-After: 1`.
5. Otherwise 404 problem+json.

Known limits:
- (Q6) A run cancelled or killed by an operator answers 202 until retention ends.
- A run accepted by Restate but not yet at its step 1 answers 404 for that moment, while a
  duplicate POST in the same moment answers 503 "not started yet". Both say retry.

**Trust boundary:**
- Restate's ingress (8080), its admin API (9070) and Postgres are internal. compose binds them to
  `127.0.0.1`.
- The SDK port 9080, which runs `record`, isn't published by compose. On a shared network,
  Restate's request identity (`restate.sdk.identity-key`) would be the fix: documented, not built
  (Q12).
- `run` doesn't trust its input. Its `calculate` step rebuilds the transaction through the same
  `TransactionFactory` the API uses (so the id pattern, the eligible state and the amount bounds are
  checked by one owner), then refuses, before the charge and as `rule_rejected`, an id that isn't the
  workflow key or a fingerprint that isn't the payload's. The fingerprint is compared after the rules
  have checked the amount, so a huge exponent is never expanded. `lookup` reads by the workflow
  key. `status()` takes no input. FeeCharge is ingress-private. Network placement still guards
  the ingress, but a direct `FeeWorkflow/run` can no longer cause a charge the API would refuse.

**Numbers:** `TransactionFeeResponse` holds `BigDecimal.stripTrailingZeros()` values, and the `JsonMapper`
has `WRITE_BIGDECIMAL_AS_PLAIN`. So `1000`, `1000.00` (rendered `1000`, Q10), `1.5` and `0.0015`
come out exactly (AC3). Money crosses the Restate journal as a JSON string (AC8). `fee_record`
money columns are `numeric(38,18)`.

**Configuration** (`application.yml`; the IT profile is `src/test/resources/application-it.yml`):
- `fees.sync-wait`: Duration, default `10s`, in `@Validated FeeProperties` with `@NotNull` and
  positive. The IT profile sets `1s`. It must stay at least 5 s below
  `spring.mvc.async.request-timeout`; `api.SyncWaitCheck` refuses startup otherwise.
- `spring.mvc.async.request-timeout: 30s`: the backstop that answers a mapped 503 if a request
  outlives every wait.
- `spring.datasource.hikari.connection-timeout: 2000` (milliseconds). Hikari's 30 s default outlasts
  `fees.sync-wait`, and a coroutine timeout can't interrupt JDBC.
- `restate.components.FeeCharge.ingress-private: true`.
- IT profile only:
  - `restate.components.FeeWorkflow.retry-policy` with a pinned budget, e.g. `max-attempts: 5`
    (confirm whether that counts the first attempt), `on-max-attempts` left at pause;
  - if usable, `workflow-retention`.
- Every IT uses the one shared context, except `RestateDownIT`, which needs its own.

## 4. Test harness: built in slice 1, used by every slice

- **One Spring context for every IT** (except `RestateDownIT`). As built: Postgres reaches it
  through a `@TestConfiguration` `@Bean @ServiceConnection` (`TestInfrastructure.Connections`,
  `destroyMethod = ""`), and an initializer sets only the Restate properties and registers the
  deployment. The container setup in
  `HelloWorkflowIT.kt:75-98` (the `SDK_PORT` exposed to containers, Postgres with
  `@ServiceConnection`, Restate 1.8.1) and the deployment registration in `HelloWorkflowIT.kt:44-55`
  move to `support/TestInfrastructure.kt`. A `support/RestateAdmin.kt` adds the admin calls:
  - register;
  - SQL query (`sys_invocation`, `sys_journal`);
  - service read (`public`, retry policy);
  - purge;
  - resume.
- **`support/FeeChargeSeeder`**: a test-only `@RestateComponent` service in `src/test` that calls
  `virtualObject<FeeCharge>(id).charge(...)` with a given fee, asset and fingerprint. Only the test
  context registers it.
- **Cucumber:** `cucumber-java`, `cucumber-spring` and `cucumber-junit-platform-engine`, plus
  `junit-platform-suite` for the runner. The runner `bdd/FeaturesIT.kt` is named `*IT`, so Failsafe
  runs it. Feature files live in `src/test/resources/features/`. Every scenario carries `@ACn`
  tags and uses its own `transaction_id`.
- **`TraceabilityTest`** (unit) parses the feature files. It fails when an AC1–AC31 has no tagged
  scenario and isn't on its `NOT_YET` list. The list shrinks every slice and is empty after
  slice 6. This is AC31's proof and R-A5.
- **Non-UTC JVM for every IT:** Failsafe gets `-Duser.timezone=Pacific/Kiritimati` (UTC+14), so
  any zone shift shows (AC7).
- **`support/ControllableFeeRecordRepository`** (from slice 4): a `@Primary` test decorator over
  `JpaFeeRecordRepository`. Faults and delays are **keyed by `transaction_id`** and apply to `save` only:
  - `failNextSave(id, n, exception)` throws before delegating;
  - `failAfterSave(id, n)` commits, then throws;
  - `delaySave(id, d)`;
  - `fired(id)` counts the faults that fired;
  - `reset()` runs in a Cucumber `@Before`.
  
  A leftover run from another scenario can't use up a scenario's faults, and each durability
  scenario asserts that `fired(id)` equals what it armed.
- Surefire and Failsafe set `failIfNoTests`, so a run that finds no tests fails (T3).
  `failIfNoSpecifiedTests` is already the default.

## 5. Slices

Every slice: VALIDATE is `./mvnw -q verify` with Docker running (`verify.commands`; `./mvnw test`
skips every `*IT`). Before each commit, run `/wikingai:verify-change`. Gate 2 level: FULL, since
every slice touches a plan-feature §1 risk escalator. There's no token estimate yet: this project
has no gate runs.

### Slice 1: walking slice (AC1, AC2, AC3, AC4, AC5, AC20, AC24, AC25, AC30, AC31) — as built

**As built (2026-11-19).** Tasks 1.1–1.7 were built at TIER 3 (opus/xhigh) and 1.8 at TIER 1
(opus/medium). `./mvnw -q clean verify` is green with 102 tests: 83 unit, 19 IT, of which 8 are
Cucumber scenarios. After the refactor below it is 114: 95 unit, 19 IT. The three 1.4 confirmations hold:
- the `reason` tag reaches the client as metadata (`{"message", "metadata": {"reason"}}`), also
  across the FeeCharge → FeeWorkflow hop;
- state set before a terminal failure stays readable from `status()`;
- `restate.components.FeeCharge.ingress-private` gives `public: false`, so no admin PATCH
  fallback is needed.

Deltas from the tasks below:
- **`created_at` storage:** `hibernate.type.java_time_use_direct_jdbc: true` replaces
  `hibernate.jdbc.time_zone=UTC`, which still shifted `created_at` by 14 h under the UTC+14 IT JVM.
  The fix is asserted by `FeeEndToEndIT` and the AC24 scenario.
- **Hikari:** `connection-timeout: 2000`, because Hikari binds milliseconds.
- **pom:** excludes `dev.restate:sdk-interceptor-micrometer`, because its coroutine element breaks
  after `withContext(Dispatchers.IO)` inside `runBlock`, and adds
  `tools.jackson.module:jackson-module-kotlin`. Cucumber is pinned at 7.34.9 through its BOM,
  ArchUnit at 1.5.1.
- **Built early as seams, each tested:**
  - the tag mapping of §3 step 6;
  - `lookup`'s different-fingerprint branch (5.2);
  - an interim duplicate check: wait for the output and compare fingerprints, until 5.1 replaces
    it with record-first plus `status()`;
  - the validator's id pattern, amount ceiling, USD significant-decimal scale, strict `created_at`
    and `state` (slices 2 and 4 still own their scenarios).
- **Gateway:** `awaitOutput` has no deadline parameter yet; slice 4 adds the deadline.
- **Not built yet:** `RestateAdmin` purge and resume (slices 5 and 6).
- **Extra journal wrappers:** `LookupJournal` and `CalculationJournal` (Quoted or Rejected),
  because a `runBlock` result is non-null and state can't be set inside a step.
- **Naming and service-layer refactor** (owner request, before the first commit; TIER 2,
  opus/high): §2's architecture and naming rule. `FeeService` / `FeeServiceImpl` hold the flow,
  `FeeController` only validates, calls `chargeFee` and maps the result. The gateway port sits in
  `service` and speaks domain types; `RestateFeeWorkflowGateway` is in `workflow`. The journal types
  have explicit `@SerialName`s. `RejectionReason` lives in `service`, because `service` may not
  depend on `workflow`. `AssetRegistry.defaults()` (`default` is a Java keyword). No behaviour
  change: `clean verify` is green with 114 tests, 95 unit and 19 IT.
- **Hurl smoke tests (1.9, owner request):** `./scripts/smoke.sh` passes against the composed stack, twice in a row. Its first run found a real bug that `verify` couldn't see, because Testcontainers always starts fresh: Restate's default node name is the container hostname, so a recreated container refused the existing `restate-data`. compose now sets `RESTATE_NODE_NAME: restate`.
- **Review fixes (FULL gate, CHANGES_REQUESTED; fixed at TIER 3):**
  - HIGH: a tiny-exponent amount (`1e-500000000`) reached `setScale` and cost minutes of CPU and
    hundreds of MB per request. Now the 18-decimal bound runs in `parseAmount`, an amount with any
    error never reaches pricing, and `FeeCalculator.checkAmount` refuses out-of-bounds amounts
    before any arithmetic, so the workflow's `calculate` step is protected too. The builder also
    found and fixed an `Int` overflow in the integer-digit count (`1E+2147483647`). Each case is
    rejected within 100 ms in the validator, calculator and `Money` tests.
  - MEDIUM (AC27): `checkRules` runs whatever the amount, so an invalid amount no longer hides an
    `asset_type` mismatch.
  - Advisory: a unit test and an HTTP-level test of the 400 problem+json with its `errors[]`.
  - Advisory: a duplicate send that ends in a rejection reads `status()`, and a different
    fingerprint gets 409. A failed status read fails in the STATUS phase (a 500 until slice 4 maps
    it to 503).
  - `clean verify`: 154 tests, 134 unit and 20 IT.
- **Review APPROVE, advisories applied (TIER 2):**
  - `run` checks its own input (§3 Trust boundary), and `lookup` reads by the workflow key;
  - an invalid type no longer hides the asset vs asset_type violation;
  - gateway failures are logged with their cause;
  - the duplicate path's `when` is exhaustive;
  - the shared IT Postgres uses `@ServiceConnection`.
  
  `clean verify`: 163 tests, 140 unit and 23 IT. `smoke.sh` is green.
- **Recheck APPROVE.** Its three advisories moved to the slices that own them: 3.2 and 4.3 below.
- **For later slices:** every IT uses the one `@FeeIntegrationTest` context (`TestInfrastructure.sdkPort`
  is JVM-wide, so `RestateDownIT` needs its own port and initializer); run `clean` after deleting a
  source file, since a stale class is still registered with Restate.

The brief's sample request works end to end: Mobile Top Up, USD only, rate 0.15%, HALF_UP at
scale 2, calculate → charge → record, and the brief's exact response body.

| # | Task | MIRROR | GOTCHA | TIER |
|---|---|---|---|---|
| 1.1 | pom adds: `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `kotlinx-coroutines-reactor` (no version: Boot's BOMs supply it and `reactor-core`, matching coroutines 1.11.0), the `jpa` compiler plugin with `kotlin-maven-noarg`, ArchUnit, Cucumber (3 artifacts) and `junit-platform-suite`, pinned versions for Cucumber and ArchUnit if Boot doesn't manage them, `failIfNoTests`, and Failsafe `argLine -Duser.timezone=Pacific/Kiritimati` | `pom.xml:119-125` (compiler plugins), `pom.xml:142-158` (Failsafe) | keep the documented Kotlin/Netty overrides; without `kotlinx-coroutines-reactor`, Spring MVC fails the first suspend request with `NoClassDefFoundError` | 2 (opus/high) |
| 1.2 | `V1__fee_record.sql`: the **full** AC24 table now, all `NOT NULL` (the domain guarantees every value). Columns: PK `transaction_id varchar(64)` (the AC26 bound); `amount`, `fee`, `rate` as `numeric(38,18)`; `asset`, `asset_type`, `type` (canonical label), `description`, `rule_id` as `text`; `rule_version integer`; `created_at`, `charged_at`, `recorded_at` as `timestamp(6)`; `charge_id uuid`; `state text CHECK (state = 'SETTLED')` (Q2); `fingerprint char(64)`. A comment ties `numeric(38,18)` to `Money.MAX_INTEGER_DIGITS` | none in the repo (`db/migration/` holds only `.gitkeep`) | no column has a bound the schedule or registry can outgrow; `spring.jpa.hibernate.ddl-auto=validate` (KS-P1); `hibernate.type.java_time_use_direct_jdbc: true` (as built: `jdbc.time_zone=UTC` still shifted under UTC+14); the stored `description` is what GET and a replay render, never the live schedule | 3 (opus/xhigh) |
| 1.3 | `domain`: `Asset` (USD only for now), `FeeRule.Percentage`, `TransactionType`, `FeeSchedule` + `DefaultFeeSchedule` (one entry), `FeeCalculator` (rules, then round once, HALF_UP, at the asset's scale; returns a quote or violations), `FeeResult`, `CreatedAtFormat`, `PayloadFingerprint` (§3 step 3), `FeeRecordRepository` port, `Money`; root `DomainConfig`. Unit tests (AC31): calculator, `Money.plain()` (the number serializer), `CreatedAtFormat` (the date parser) and a fingerprint test that pins the brief sample's digest | `HelloWorkflowTest.kt:6-9` (plain JUnit) | `BigDecimal("0.0015")`, never `valueOf(double)`; `domain` imports no Spring | 3 |
| 1.4 | `workflow`: `FeeWorkflow` (§3: steps 1–6, `rejectBeforeCharge`, `status()`; in this slice `lookup` only returns a stored outcome), `FeeCharge` with the fingerprint check, journal types and mappers, a test that decodes a checked-in `Charge` JSON sample (FeeCharge keeps its state indefinitely); `restate.components.FeeCharge.ingress-private: true`; **delete** `HelloWorkflow`, `HelloWorkflowTest` and `HelloWorkflowIT` (their checks move to `FeeEndToEndIT`) | `HelloWorkflow.kt:12-23` (`@RestateComponent`, `@Workflow`, `runBlock`) | constructor injection into `@RestateComponent` (KS-C1); `state()`, `random()` and `Clock.Restate` are top-level in `dev.restate.sdk.kotlin` (SDK 2.11.0 api.kt). Confirm in an IT: (1) the `TerminalException` metadata tag reaches the failure body the client sees, also across the FeeCharge → FeeWorkflow hop (fallback: a `payload_conflict:` / `rule_rejected:` message prefix); (2) state set just before a terminal failure stays readable from `@Shared status()`; (3) the component map key is `FeeCharge` (fallback: admin `PATCH /services/FeeCharge {public: false}`) | 3 |
| 1.5 | `persistence`: entity, repository, `JpaFeeRecordRepository` | none in the repo | a plain `class` entity (KS-L2) with an assigned id and **no `@Version` and no `Persistable`**: either one turns a replay into a duplicate-key loop that ends paused; the `LocalDateTime` fields have no zone | 2 |
| 1.6 | `api`: controller over `FeeWorkflowGateway` (send → poll the output, no timeout yet); `RestateFeeWorkflowGateway`; `TransactionFeeRequest`/`TransactionFeeResponse` (one `from(FeeResult)`); snake_case `JacksonConfig`; `ApiExceptionHandler` skeleton (400 with an `errors[]` list, a logged 500 fallback); a minimal validator (required fields, USD, the one type, exact labels) | none in the repo | Boot 4 is Jackson **3** (`tools.jackson`), while Restate uses Jackson 2: configure the Spring `JsonMapper`, not `ObjectMapper`; endpoints are `suspend fun`, with no `runBlocking` (KS-R3); `records.find` under `Dispatchers.IO`; `ApiExceptionHandler` never echoes an exception message, only fixed details and field errors | 3 |
| 1.7 | Harness (§4), including `FeeChargeSeeder`. `FeeEndToEndIT` (AC30): raw-body asserts, one `charge_id`, one row; actuator health is UP (the check from `HelloWorkflowIT.kt:57-65`); a direct ingress `POST /FeeCharge/txn_x/charge` is non-2xx and FeeCharge reads `public: false`, then a real POST for `txn_x` returns fee 1.5 with a fresh `charge_id`. `fee_calculation.feature` (slice 1 scenarios; AC25's repeat charge goes through `FeeChargeSeeder`); `ArchitectureTest` (layering, §2); `TraceabilityTest` | `HelloWorkflowIT.kt:44-98` | Cucumber and Spring share one context: `@CucumberContextConfiguration` on one class only | 2 |
| 1.8 | compose: bind Restate's 8080 and 9070, and Postgres's 5433, to `127.0.0.1`; a named Postgres volume; `restart: unless-stopped` on `app`, `postgres` and `restate`. README: architecture, ports, run with compose, wait for "deployment registered" in `docker compose logs register`, a curl with a fresh `transaction_id`, test commands, and a warning that `docker compose down -v` deletes `restate-data`, which holds every charge | `compose.yaml` (register service) | local runs still work: `base-uri` defaults to localhost (`application.yml:33`), and registration from the host too | 1 (opus/medium) |
| 1.9 | Hurl smoke tests (owner request): `api-tests/fee_calculation.hurl` (the brief's sample: raw-body AC3 checks, the seven keys, the identical resend) and `scripts/smoke.sh` (`docker compose up -d --build`, wait for a registration logged in this run, run every `api-tests/*.hurl` with a local `hurl` or the pinned image `ghcr.io/orange-opensource/hurl:8.1.0` on the compose network, then `down` without `-v`; `--keep` for a demo); README "API smoke tests (Hurl)"; a `verify.commands` entry for `api-tests/**`, `scripts/smoke.sh`, `compose.yaml`, `Dockerfile` | `compose.yaml` | Hurl is the stack smoke test and live-demo script, never a second source of truth: the AC table maps to Cucumber only; every run uses `{{newUuid}}` ids | 1 (opus/medium) |

### Slice 2: money (AC8, AC9, AC10, AC11, AC12) — as built

**As built (2026-11-19, TIER 3, opus/xhigh).** `clean verify` is green with 232 tests: 177 unit,
55 IT, of which 40 are Cucumber scenarios. `smoke.sh` runs `fee_calculation.hurl` and `money.hurl`.
- `AssetRegistry.defaults()` holds the full AC9 table. `AssetRegistryTest` pins it, every scale
  ≤ `Money.MAX_SCALE`, and exact codes.
- **`ArchitectureTest` money rules:** no `double`/`float` (boxed or primitive, also as a type
  argument) in a field, return or parameter. No `BigDecimal(double)`, `valueOf(double)`,
  `doubleValue`/`floatValue`, `parseDouble`/`parseFloat` or `toDouble*`/`toFloat*` call. A permanent
  offender-class test proves each rule bites. **Limit:** a `Double` used only in local arithmetic
  isn't caught.
- **`money.feature`:** 32 scenarios. Every 400 also asserts that Restate has no invocation for the
  id. AC8 also checks that FeeCharge keeps the fee as a JSON string. The trailing-zero scenario
  also carries `@AC5`.
- **For later slices:** Failsafe's XML `tests=` undercounts Cucumber outlines (shared example
  names), so read the `.txt` report. AC27's several-fields listing needs a table form of the
  problem step.

Hurl companion: `api-tests/money.hurl`: a non-USD asset, a crypto asset, a 400 on an over-scale amount.

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 2.1 | `AssetRegistry`: the full AC9 table, enforcing that asset and asset type agree, with a unit test that every scale is at most 18; scale checks (significant decimals, Q13) and the **amount ceiling** in the validator (AC11, AC12, Q1) | `Money.MAX_INTEGER_DIGITS = 20`; the ceiling runs before any rendering or arithmetic | 3 |
| 2.2 | `ArchitectureTest`: no `double`/`float`/`Double`/`Float` field, parameter or return type in the five packages; no calls to `BigDecimal(double)`, `BigDecimal.valueOf(double)` or `toDouble()` | ArchUnit sees Kotlin `Double` as the primitive `double` | 2 |
| 2.3 | `money.feature`: the AC10 table (raw text in plain form: `0.5`, not `0.50`, Q10); every asset; `1000.00` USD comes back as `"amount":1000`; `10.000` USD is accepted (Q13); the 12345678901234567.89 USD round trip (Mobile Top Up, rate `0.0015`: fee `18518518351851.85`, the exact product 18518518351851.851835 rounded HALF_UP at scale 2); `100000000000000000000` USD → 400 `errors[amount]` with no invocation; `1E+30` USD → 400; `99999999999999999999.99` USD → 200, fee `150000000000000000`, one row with the same digits. A unit test rejects `1e20000000` in under 100 ms (`assertTimeoutPreemptively`) | compare raw response text, not parsed numbers | 2 |

### Slice 3: transaction types (AC13, AC14, AC15) — as built

**As built (2026-11-19, TIER 3, opus/xhigh).** `clean verify` is green with 310 tests: 245 unit,
65 IT, of which 50 are Cucumber scenarios. `smoke.sh` runs three Hurl files.
- `FeeRule.Fixed(perAsset)` and `FeeRule.NoFee(description)`. The description belongs to the
  schedule entry, so `NoFee` is a data class.
- **Startup validation in two places:**
  - each rule's `init`: a percentage in [0, 1), no negative fixed fee, a non-empty map, a
    non-blank description;
  - `FeeScheduleValidation`, run by `DomainConfig`: duplicate labels, a fixed fee with more
    decimals than its asset allows, and a fixed fee for an asset the registry doesn't hold.
- **`checkRules` takes nullable arguments** (null means "already reported"). Field choices:
  - type vs allowed asset types is reported on `type` (AC15);
  - a missing fixed fee is reported on `asset`;
  - fee > amount is reported on `amount` (AC13).
  
  Each field gets at most one error. The validator no longer holds any rule text: the "unknown
  type / unknown asset" messages live only in `FeeCalculator` (slice 1 recheck advisory). Field
  errors now come before rule errors in the list.
- **`TraceabilityTest`** reads the AC ids from `docs/requirements.md` (slice 2 advisory) and
  checks they run from 1 with no gap.
- **`transaction_types.feature`:** the one-row-per-type outline (the §4 extension point) plus a
  second outline for Bank Transfer in EUR, GBP, JPY and Charity Donation in BTC.
- **Review APPROVE.** Its one advisory is applied: a percentage rate is refused past 18 decimals,
  because `fee_record.rate` would round it silently and a replay or GET would then render another
  rate.

Hurl companion: `api-tests/transaction_types.hurl`: one request per registered type.

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 3.1 | `FeeRule.Fixed(perAsset)`, `FeeRule.NoFee`; Bank Transfer and Charity Donation in `DefaultFeeSchedule` with the AC14 descriptions; startup validation (duplicate labels, malformed rules) when `DomainConfig` builds the schedule, with unit tests that build a bad schedule and expect the failure | `when` over `FeeRule` is exhaustive, with no `else` (KS-L4); `FeeSchedule` stays an interface so 6.2(c) can swap it | 3 |
| 3.2 | `FeeCalculator`'s rules: type vs allowed asset types, a fixed fee with no amount for the asset, fee > amount (equal is allowed). Tested once, in `domain`; the validator maps the violations and `calculate` reuses them (§3). The validator passes the raw type label to `checkRules` and maps its `RuleViolation`s, so the "unknown type" / "unknown asset" texts live only in `FeeCalculator` (slice 1 recheck advisory) | the "no amount configured" case can't be reached with the shipped schedule, so a unit test with a test schedule covers it | 2 |
| 3.3 | `FeeScheduleContractTest` (§4 of requirements: every type, every allowed asset); `transaction_types.feature` (Scenario Outline, one row per type, including Charity Donation with fee 0 and a `charge_id`); companion: `requirements.md` §4 names `fees.feature`, so it becomes `transaction_types.feature` | | 2 |

### Slice 4: API edges (AC6, AC7, AC26, AC27, AC28, AC29) — as built

**As built (2026-11-19, TIER 3, opus/xhigh).** `clean verify` is green with 451 tests: 327 unit,
124 IT, of which 107 are Cucumber scenarios. `smoke.sh` runs five Hurl files.
- **`RestateFailureMapping`** (pure, in `service`) maps cases 1–5 and, separately, case 6.
  - Case 6 also catches `TransactionException`, because Hikari's connection failure surfaces as
    `CannotCreateTransactionException`.
  - Wording for case 3: "Restate refused the request; resending the same request is safe".
  - Wording for case 4: "fee workflow status unavailable; resending the same request is safe".
  - Every 503 is `/problems/unavailable` with `Retry-After: 5`; the 202 has `Retry-After: 1`.
- **The wait has a deadline:** `awaitOutput(id, deadline)`, capped at deadline + 500 ms because
  the Restate client sets no request timeout. It still never attaches. `FeeProperties`
  (`fees.sync-wait`) lives in `service` via `@ConfigurationPropertiesScan`;
  `spring.mvc.async.request-timeout: 30s`.
- **Slice 1's carried advisories:** async timeouts are mapped and logged
  (`phase=REQUEST_TIMEOUT`), and a controller test checks the request attribute.
- **Built early from slice 5:**
  - POST's duplicate path checks the record first;
  - a duplicate whose payload hasn't been checked re-reads `status()` at the deadline or after a
    transport error (409, 202, its rejection, or 503 "not started yet").
  
  Left for 5.1: read `status()` before waiting.
- **Every `TransactionFeeRequest` field is a `JsonNode`**, so a wrong JSON type is listed with
  the other errors. A malformed or non-object body gives `/problems/invalid-request` with
  `errors: []`.
- **`RestateDownIT` has its own context.** It wires Postgres by datasource properties, never via
  `TestInfrastructure.Connections`: closing a context that holds the shared container as a
  `@ServiceConnection` bean stops it for every later IT.
- **Known:** a GET calls `status()`, which creates a `FeeWorkflow/status` invocation for that
  key, so "no invocation for id" can't be asserted after a GET.
- **Review APPROVE.** Its plan-text advisory is applied here (§3 now places
  `RestateFailureMapping` and `records.find` in `service`). Its two code advisories go to slice 5:
  a startup check that `fees.sync-wait` stays below the async request timeout, and unit tests for
  the gateway's transport-failure classification.

Hurl companion: `api-tests/validation.hurl` (one 400 listing every invalid field) and `api-tests/slow_workflow.hurl` (GET of a finished fee, 404 for an unknown id).

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 4.1 | Validator complete: every AC26 bullet, `created_at` strict (`uuuu-MM-dd HH:mm:ss.SSSSSS`, `ResolverStyle.STRICT`), `state`, the `transaction_id` pattern; malformed JSON → 400 through `ApiExceptionHandler` | `HttpMessageNotReadableException` goes through the one advice (KS-W2) | 3 |
| 4.2 | `RestateFailureMapping` (§3 step 4, cases 1–6), with a unit test per case and one that a `CancellationException` is rethrown, never mapped; `RestateDownIT` for case 1: its own context, with `restate.client.base-uri` pointing at a closed port, gets 503 and nothing charged | a separate context, so it can't break the shared one | 2 |
| 4.3 | `FeeProperties` (`fees.sync-wait`, `@Validated`) plus a binding test; the polling wait (§3 step 5) → 202 with `Location`, the AC28 body and `Retry-After`; `GET /transaction/{id}/fee` (§3: id pattern first, then record, rejection, fingerprint, 404); controller tests with a `FeeRecordRepository` fake whose `find` throws (case 6), for POST and GET; `ApiExceptionHandler` overrides `handleAsyncRequestTimeoutException` (logged once with the `transaction_id` and phase, in the `/problems` shape) and `spring.mvc.async.request-timeout` is set above `fees.sync-wait`; a controller test asserts the `transaction_id` request attribute is set (slice 1 recheck advisories) | the deadline stops the polling only, and nothing on the server is cancelled; `api` never calls `attach*` | 3 |
| 4.4 | `ControllableFeeRecordRepository` (§4); `validation.feature`, `slow_workflow.feature`; AC26's "no invocation for that id" through an admin SQL query | | 2 |

### Slice 5: idempotency (AC16, AC17, AC18, AC19) — as built

**As built (2026-11-19, TIER 3, opus/xhigh).** `clean verify` is green three times in a row with
498 tests: 363 unit, 135 IT, of which 117 are Cucumber scenarios. `smoke.sh` runs six Hurl files
(17 requests).
- **Duplicate path complete.** Record first, then `status()` **before** waiting: a different
  fingerprint gets 409 at once; a stored rejection returns at once; otherwise wait. Both status
  reads share one `decideByStatus`. A duplicate send arrives as `PREVIOUSLY_ACCEPTED`, seen in
  slice 1; the 409 "already invoked" form is unit-tested too.
- **Purge, confirmed on Restate 1.8.1:** `PATCH /invocations/{id}/purge` on 9070, with the id from
  `sys_invocation` (FeeWorkflow, key, handler `run`). It clears workflow state and journal, frees
  the key, and leaves FeeCharge state unchanged. It works only on completed invocations.
- **(e')** needs a purge between POST B and POST A: B's rejected run holds the key with fp(B), so A
  is a different-fingerprint duplicate until retention ends. That's consistent with §3.
- A Q8 problem body differs from the POST's only in `instance` (the request path).
- **Slice 4's advisories:**
  - `api/SyncWaitCheck` fails startup unless `fees.sync-wait` + 5 s is below
    `spring.mvc.async.request-timeout`;
  - the gateway's `classify` / `isAlreadyInvoked` are pure functions with unit tests (refused
    connection vs outcome unknown vs status).
- README: resending a used `transaction_id` with edits gets 409.
- **For slice 6:**
  - the ArchUnit "only the gateway uses `dev.restate.client`" rule must allow the gateway's nested
    `Companion`;
  - resume is `PATCH /invocations/{id}/resume` (409 while running);
  - with the 1 s IT wait, retried runs answer 202, so assert through GET polling.
- **Review APPROVE.** Its config-reference advisory is applied in §3. Its two others go to slice 6:
  a test of the hung-poll cutoff (6.x), and the README's endpoint list and configuration table (6.5).

Hurl companion: `api-tests/idempotency.hurl`: the identical resend, and a different payload under the same id → 409.

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 5.1 | The controller's duplicate path (§3 step 4) and the tag mapping (§3 step 6). Controller unit tests against a fake gateway: a record decides; status null then fp(B) at the deadline → 409; an equal fingerprint with a stored rejection → that 4xx; `rule_rejected` → 400; an untagged terminal failure (a cancel) → 500, never 409; `records.find` fails → 503 | record first, then `status()`; re-read after a deadline that followed a null; handle both `PREVIOUSLY_ACCEPTED` and the 409 "already invoked" | 3 |
| 5.2 | `lookup` complete: a stored record with the same fingerprint returns the stored outcome, a different one fails with `payload_conflict` | the stored outcome renders byte-identical to the first response, through the one `TransactionFeeResponse.from(FeeResult)` | 3 |
| 5.3 | `idempotency.feature`: (a) sequential and 10 in parallel; (b) a different payload → 409; (c) after retention: purge, then the same payload → same body and same `charge_id`, and a different payload → 409; (d) **different payload first**: A recorded, purge, B → 409, A again → 200 with a byte-identical body, the same `charge_id` and one row, with GET agreeing with POST at every step; (e) orphan charge, different fee: seed `FeeCharge/txn_orphan` with 1.50 USD and fp(A), then POST `txn_orphan` with amount 330 → 409, no row, FeeCharge unchanged, then GET `txn_orphan` → the same 409 body (Q8); (e') orphan charge, same fee: seed `FeeCharge/txn_orphan2` with 1.50 USD and fp(A), POST B (A with another `created_at`) → 409, no row, FeeCharge unchanged; POST A → 200 with the seeded `charge_id`. README: resending a used `transaction_id` with edits gets 409 | confirm the purge endpoint in the Restate 1.8 admin API, or use a short `workflow-retention` in the IT profile. Purge clears workflow state; `FeeCharge` state stays | 3 |

### Slice 6: durability and determinism (AC21, AC22, AC23) — as built

**As built (2026-11-19).** Tasks 6.1–6.4 were built at TIER 3 (opus/xhigh) and 6.5 (README) at
TIER 1 (opus/medium). `clean verify` is green twice in a row with 525 tests: 377 unit, 148 IT, of
which 122 are Cucumber scenarios. **All 31 ACs have tagged scenarios, and
`TraceabilityTest.NOT_YET` is empty.** `smoke.sh` runs six Hurl files (17 requests).
- **6.1:** `restate.components.FeeWorkflow.retry-policy` (IT profile only) took effect: 100 ms,
  ×2, max 1 s, `max-attempts: 5`, `on-max-attempts: pause`. **`max-attempts` counts the first
  attempt**, so n failures need n+1 attempts. `RetryPolicyIT` asserts Pause for FeeWorkflow and
  FeeCharge, and no Kill override. Production keeps the server default (70 attempts, Pause).
- **6.2:** `durability.feature`: (a), (a'), (b), (c) and (d). (b) arms the budget + 1 and asserts
  5 fired at the pause, so clearing the fault matters. Resume is `PATCH /invocations/{id}/resume`.
  (c) switches a `SwitchableFeeSchedule` inside the first failed `record` save, with no race.
- **6.3:** the determinism, terminal-failure, R8, single-writer, Restate-client and no-`attach`
  rules, each with an offender class.
  - **R8 holds on the bytecode:** repository calls sit only in `withContext` lambdas nested in
    step lambdas.
  - The step rule is stricter than written: the outermost lambda must have `runBlock`'s
    `suspend () -> T` shape.
  - `Clock.Restate.now()` journals its own Run entry.
- **6.4:** springdoc 3.2.0 (built for Boot 4.1.2). It needed a snake_case `ModelResolver` and
  `@Schema` on the `JsonNode` request fields so the document matches the wire format. `OpenApiIT`
  pins both endpoints, their statuses and the example.
- **Slice 5's test advisory:** stalled-socket tests prove `awaitOutput` returns `Pending` within
  the deadline + grace, with `@Timeout(10)` so a regression fails instead of hanging.
- **Review APPROVE**, with three LOW advisories applied:
  - Swagger UI and `/v3/api-docs` are off by default (`SPRINGDOC_ENABLED`, on in compose and the
    IT profile);
  - the designed 500 is in the OpenAPI document;
  - `OpenApiIT` pins the request schema's required fields.
  
  The light recheck APPROVEd. Its one LOW advisory, a test of the default-off switch, is in the
  backlog. Final `clean verify`: 526 tests (377 unit, 149 IT). `smoke.sh` green.

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 6.1 | IT profile: `restate.components.FeeWorkflow.retry-policy` with the pinned budget (§3), `on-max-attempts` left at pause; a test reads the service through the admin API and asserts the policy isn't Kill | never set `Kill`; n failures need n+1 attempts, so confirm whether the budget counts the first one; if the component key doesn't take, fall back to the server's default retry policy through env | 3 |
| 6.2 | `durability.feature`, each scenario asserting `fired(id)`: (a) `failNextSave(id, 2)`, then one charge and one matching record (AC21); (a') `failAfterSave(id, 1)`: the invocation completes (not paused) with one row, one `charge_id`, and fields equal to the 200 body; (b) arm at least the budget, so `record` keeps failing and the invocation is `paused` in `sys_invocation`; clear the fault, resume through the admin API, and the record lands (AC23); (c) swap the schedule between attempts and the journaled fee stays (AC22, R13); (d) `failNextSave(id, 1, DataIntegrityViolationException)`: it retries and the record lands (R3) | for (c), a test `FeeSchedule` bean that can switch rules: switch it after `calculate` journaled, then fail `record` once | 3 |
| 6.3 | `ArchitectureTest` (`ImportOption.DoNotIncludeTests`): `workflow` never calls `UUID.randomUUID`, `Instant.now`, `System.currentTimeMillis`, `Clock.System` or `LocalDateTime.now`; no code unit except `FeeWorkflow.rejectBeforeCharge` and FeeCharge's fingerprint check calls a `TerminalException` constructor; no method declared directly on `FeeWorkflow` or `FeeCharge` calls `FeeRecordRepository`, only the step-lambda classes nested in `FeeWorkflow` (R8); no class outside `..workflow..` and `..persistence..` calls `FeeRecordRepository.save` (one writer); only `RestateFeeWorkflowGateway` depends on `dev.restate.client..`; `api` never calls `attach*` | ArchUnit `JavaCodeUnit` predicates; confirm the lambda nesting first, and if it doesn't hold, amend R8 in `requirements.md` | 2 |
| 6.4 | Swagger UI: `springdoc-openapi` on `/swagger-ui.html`. `@Operation`, `@ApiResponses` (200, 202, 400, 409, 503) and an `@ExampleObject` with the brief's sample on each `FeeController` endpoint; `OpenApiConfig` holds only the API info. The example's description tells the reader to change `transaction_id` before resending; a long example JSON may live in a constant. An IT asserts `/v3/api-docs` lists both endpoints with their status codes | use a springdoc release built for Boot 4 / Jackson 3; if it doesn't work within one try, drop it and keep the README's curl | 2 |
| 6.5 | README complete: architecture diagram, decisions summary, the Swagger URL, and the Restate behaviours relied on, including **journal compatibility**: a fix deployed while a FeeWorkflow is paused keeps the step names, their order and the journal JSON shapes (new fields only with defaults); otherwise drain first. **Stuck-charge runbook** (D2b): a `record` that keeps failing pauses the invocation, and GET answers 202. Find unfinished runs with the `sys_invocation` query `RestateAdmin` runs in 6.2(b) (FeeWorkflow and FeeCharge, statuses `paused` and `backing-off`, port 9070). Fix the cause, then resume each one with the admin call 6.2(b) uses. Never cancel or kill a FeeWorkflow invocation that has passed `charge`, and never press Cancel or Kill in the Restate UI on 9070. `TraceabilityTest.NOT_YET` empty | | 1 |

### Slice 7: services refactor (no AC; owner decisions Q14–Q17) — as built

**As built (2026-11-19).** Tasks 7.1–7.4 were built at TIER 3 (opus/xhigh); I did 7.5 (docs).
`clean verify` is green with 536 tests: 387 unit, 149 IT. `smoke.sh` is green. The invariants hold:
the fingerprint digest `a0de743b…9798` is unchanged, `charge-v1.json` is unchanged, the Restate
service names are unchanged, and `domain` has no Spring import.
- `FeeScheduleValidationService` takes the registry in its constructor, so its methods take only
  the schedule.
- The failure mapping's `invoke` became `outcomeOf`, and `Outcome` is nested in the interface.
- **Caller tests:** `FeeServiceImplTest` and `FeeControllerTest` keep the real `Impl`s (real
  behaviour stays covered there) plus stub tests proving the injected service is used.
- **ArchUnit shape rules (a)–(c)** were proven against planted offenders and against temporary
  production offenders.
  - **Known gap:** top-level *functions* in file facades (e.g. `Mappers.kt`) aren't covered by rule
    (a).
- Names in the slice 1–6 notes above are as built at the time; this slice renamed them (table
  below).
- **Review APPROVE.** Its one advisory was stale §1/§2 text (the Q15 wording, `DomainConfig`'s
  beans, the domain shape); fixed.

No behaviour changes. Every AC scenario, the Hurl smoke test and these invariants stay green:
- the pinned fingerprint digest in the fingerprint test (a changed digest turns old records into
  409s);
- `JournalShapeTest` and `charge-v1.json` (no Restate journal type changes);
- the Restate service names `FeeWorkflow` and `FeeCharge`.

**Renames and new shapes:**

| Now | Becomes | Package |
|---|---|---|
| `FeeCalculator` (class) | `FeeCalculationService` + `FeeCalculationServiceImpl` | `domain` |
| `PayloadFingerprint` (object) | `PayloadFingerprintService` + `PayloadFingerprintServiceImpl` | `domain` |
| `FeeScheduleValidation` (object) | `FeeScheduleValidationService` + `FeeScheduleValidationServiceImpl` | `domain` |
| `AssetRegistry` (class) | `AssetRegistry` (interface) + `DefaultAssetRegistry` | `domain` |
| `CreatedAtFormat` (object) | `DateFormatter` (object, Q15) | `domain` |
| `RestateFailureMapping` (object) | `GatewayFailureMappingService` + `GatewayFailureMappingServiceImpl` | `service` |
| `FeeRequestValidator` (class) | `FeeRequestValidationService` + `FeeRequestValidationServiceImpl` | `api` |
| `Money`, `RejectionMetadata`, serializers, `FeeApiExamples` | unchanged (allow-list, Q15, Q17) | — |

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 7.1 | The three `domain` services, `AssetRegistry` + `DefaultAssetRegistry`, and `DateFormatter`. `config/DomainConfig` builds every domain bean (`DefaultAssetRegistry`, the validation service, then the schedule validated by it, then the calculation and fingerprint services). Callers take the interfaces by constructor: the request validation service, `FeeServiceImpl`, `FeeWorkflow`. Existing unit tests move to the `…Impl` classes; callers' tests use simple fakes | no Spring annotation in `domain`; `FeeWorkflow`'s `calculate` step and the input re-check keep their order (the rules before the fingerprint, so a huge exponent is never expanded); the fingerprint digest is unchanged | 3 (opus/xhigh) |
| 7.2 | `GatewayFailureMappingService` + `Impl`, injected into `FeeServiceImpl`; `FeeServiceImplTest` may stub it, and the mapping's own tests move to the `Impl` | pure, no I/O | 2 (opus/high) |
| 7.3 | `FeeRequestValidationService` + `Impl`, injected into `FeeController`; `FeeControllerTest` may stub it | the validator's own tests move to the `Impl` unchanged | 2 |
| 7.4 | `ArchitectureTest`: (a) no top-level `object` with public functions in the five packages and `config`, except the allow-list; (b) every class named `*Service` is an interface; (c) every `*ServiceImpl` implements exactly one `*Service` with the same stem. Each rule is proven to fail on a planted offender | Kotlin `object`s compile to a class with a static `INSTANCE` field; companion objects are excluded | 2 |
| 7.5 | Docs: §2's package table and every class name in this plan, the README package table and architecture text, `requirements.md` §4, and one line in `CLAUDE.md` stating the shape rule | `wikingai lint` clean | 1 (opus/medium) |

VALIDATE: `./mvnw -q clean verify` (clean, so no stale class is registered with Restate), then
`./scripts/smoke.sh`. Gate 2: FULL, because the refactor touches the fee calculation and the
fingerprint.

### Slice 8: DDD, a domain factory and a `Money` value object (no AC; Q18, Q19) — as built

**As built (2026-11-19).** Tasks 8.1–8.3 were built at TIER 3 (opus/xhigh); I did 8.4 (docs).
`clean verify` is green with 620 tests: 471 unit, 149 IT. `smoke.sh` is green. The invariants hold:
- the fingerprint digest is unchanged;
- the journal files are untouched;
- the domain is framework-free.

Deltas:
- **`PricingField` becomes `TransactionField`** (all seven input fields), and `RuleViolation` has its
  own file.
- **`FeeCalculationService`:** `checkAmount` is gone, and the quote takes `Money`.
- **`Transaction`:** `asset` is derived from `amount.asset`; its constructor *and* `copy` are internal,
  and ArchUnit checks both.
- **The API prices even when the factory reports violations, if none is on a priced field**, so AC27
  still lists `[state, amount]`. A wrong JSON type replaces the factory's "is required" for that field in
  the same position.
- **`Money.checkBounds`** (asset-free) and **`Money.check`** (per asset) keep every message and order.
- **Startup validation** also refuses a fixed fee over 20 integer digits.
- **Two workflow `rule_rejected` details** (unknown asset type; `1E-30`) now use the factory's
  messages. No test pinned them.
- **Review APPROVE**, with two advisories applied:
  - MEDIUM: `created_at` accepted a signed or 5+-digit year (the `uuuu` pattern). The fee was then
    charged and the record failed on every retry, or was stored as `-infinity`. `DateFormatter` now
    parses exactly four unsigned digits (0001–9999), pinned in the `DateFormatter`, factory and
    validator tests (AC6 updated).
  - LOW: the house files named the deleted `HelloWorkflow` (owner follow-up, done).
  
  `clean verify`: 630 tests (481 unit, 149 IT). The smoke test and the fingerprint digest are
  unchanged.
- **Light recheck APPROVE**, with a MEDIUM advisory on that fix, now applied. The strict 4-digit
  format made `DateFormatter.format` throw for a year outside 0001–9999, and the workflow formats
  untrusted direct-run input. A `created_at` of `+12345-…` would retry until paused instead of being
  rejected. `DateFormatter.formatOrNull` now feeds the factory an empty string it refuses (`rule_rejected`
  before the charge), and a `RestateBehaviourIT` direct run with years 12345 and -5000 proves it. The
  fingerprint's `format` call takes only factory-built transactions, whose year is always in range.
  `clean verify`: 632 tests (482 unit, 150 IT); smoke green.
- **Final light recheck APPROVE, with no advisories.**

No behaviour changes. The invariants of slice 7 hold:
- every AC scenario and the smoke test;
- the pinned fingerprint digest;
- `JournalShapeTest` and `charge-v1.json`;
- the problem bodies and their field messages;
- the Restate service names.

| # | Task | GOTCHA | TIER |
|---|---|---|---|
| 8.1 | `Money(amount, asset)` value object (Q18). Its companion holds `MAX_INTEGER_DIGITS`, `MAX_SCALE`, a non-throwing `check(amount, asset): RuleViolation?` and the cheap bound checks (no exponent ever expanded). `plain()`, comparison, `rounded(raw, asset)`. `Transaction.amount` and `FeeQuote.fee` become `Money`; the mappers convert at the read-model, journal and persistence edges | `1e-500000000` and `1E+2147483647` are still refused within 100 ms; the fingerprint renders the amount exactly as before | 3 (opus/xhigh) |
| 8.2 | `TransactionFactory` + `TransactionFactoryImpl` (Q19), `TransactionInput`, sealed `TransactionCreation`. The violation fields become a domain enum (`TransactionField`, absorbing `PricingField`). `FeeRequestValidationServiceImpl` keeps the JSON-shape errors, calls the factory, then `FeeCalculationService` for pricing rules (fee > amount, as now), and maps the violations. `FeeWorkflow.calculate` uses the factory for its re-check, then keeps its own key match and fingerprint comparison (rules first). `Transaction`'s constructor becomes `internal` | every invalid field is still listed once, with the same message texts | 3 |
| 8.3 | `ArchitectureTest`: `Money` leaves the top-level object allow-list; rule (c) also covers `*FactoryImpl` → `*Factory`; a rule that only `domain` constructs `Transaction` | each new rule fails on a planted offender | 2 (opus/high) |
| 8.4 | Docs: §2 and §3 names, `requirements.md` §4, the README package table | lint clean | 1 (opus/medium) |

VALIDATE: `./mvnw -q clean verify`, then `./scripts/smoke.sh`. Gate 2: FULL (money path,
fingerprint).

## 6. AC → scenario → slice

Each AC belongs to exactly one slice. A scenario may carry several tags.

| AC | Scenario (feature file) | Slice |
|---|---|---|
| AC1 | The brief's sample transaction gets its fee (`fee_calculation`) | 1 |
| AC2 | Only the seven snake_case keys come back, with no nulls (`fee_calculation`) | 1 |
| AC3 | Numbers are rendered in plain form in the raw body (`fee_calculation`) | 1 |
| AC4 | asset_type, state and created_at are not echoed (`fee_calculation`) | 1 |
| AC5 | Amount and asset are echoed as sent; type is the canonical label (`fee_calculation`) | 1 |
| AC6 | Outline: created_at in any other format is rejected (4 examples) (`validation`) | 4 |
| AC7 | created_at round-trips exactly under a non-UTC JVM zone (`validation`) | 4 |
| AC8 | A huge amount keeps every digit; an amount over 20 integer digits is rejected (`money`) + `ArchitectureTest` | 2 |
| AC9 | Outline: each asset rounds at its own scale (`money`) | 2 |
| AC10 | Outline: amounts where Double or HALF_EVEN would be wrong (6 rows) (`money`) | 2 |
| AC11 | Outline: an unknown or mismatched asset is rejected (`money`) | 2 |
| AC12 | An amount with more significant decimals than the asset allows is rejected; trailing zeros don't count (`money`) | 2 |
| AC13 | A flat fee larger than the amount is rejected; an equal one is charged (`transaction_types`) | 3 |
| AC14 | Outline: every registered type end to end (`transaction_types`) | 3 |
| AC15 | A type used with an asset type it doesn't allow is rejected (`transaction_types`) | 3 |
| AC16 | A second submission while the first runs reattaches to it (`idempotency`) | 5 |
| AC17 | The same payload again, in sequence and 10 at once: one charge, one record (`idempotency`) | 5 |
| AC18 | The same id with a different payload: 409, nothing charged or changed; an orphan charge refuses a different payload (`idempotency`) | 5 |
| AC19 | After workflow retention: the stored result and the original charge; a different payload gets 409, in either order (`idempotency`) | 5 |
| AC20 | The journal shows the calculate, charge and record steps (`fee_calculation`) | 1 |
| AC21 | A crash after the charge ends in one charge and one matching record, also when the commit landed (`durability`) | 6 |
| AC22 | A rule change between attempts doesn't change the journaled fee (`durability`) + `ArchitectureTest` | 6 |
| AC23 | A record that keeps failing pauses the invocation; a resume finishes it (`durability`) | 6 |
| AC24 | The fee_record row holds every field (`fee_calculation`) | 1 |
| AC25 | The charge lives in FeeCharge state; a repeat charge returns the same id; FeeCharge isn't reachable through the ingress (`fee_calculation`) | 1 |
| AC26 | Outline: invalid input is rejected and starts nothing (every bullet) (`validation`) | 4 |
| AC27 | Every invalid field is listed in one problem+json (`validation`) + `RestateDownIT` (503) | 4 |
| AC28 | A slow workflow answers 202 with a Location (`slow_workflow`) | 4 |
| AC29 | GET returns the POST body when done, 202 while running, 404 when unknown (`slow_workflow`) | 4 |
| AC30 | The brief's sample transaction gets its fee (the AC1 scenario) + `FeeEndToEndIT` | 1 |
| AC31 | The brief's sample through the Cucumber HTTP path (the AC1 scenario) + the unit classes of 1.3 + `TraceabilityTest` | 1 |

## 7. Files

**CREATE**
- `src/main/kotlin/com/cashi/fee/{domain,workflow,persistence,api}/…` and
  `src/main/kotlin/com/cashi/fee/config/DomainConfig.kt` (§2)
- `src/main/resources/db/migration/V1__fee_record.sql`
- `src/test/kotlin/com/cashi/fee/{domain,api,workflow}/*Test.kt`, `ArchitectureTest.kt`,
  `TraceabilityTest.kt`, `FeeEndToEndIT.kt`, `RestateDownIT.kt`
- `src/test/kotlin/com/cashi/fee/{support,bdd}/…`
- `src/test/resources/application-it.yml`, `src/test/resources/features/*.feature` (7: `fee_calculation`,
  `money`, `transaction_types`, `validation`, `slow_workflow`, `idempotency`, `durability`),
  and a `Charge` JSON sample
- `README.md`
- `api-tests/*.hurl` (one per behaviour, from 1.9 on), `scripts/smoke.sh`

**UPDATE**
- `pom.xml` (1.1: data-jpa, validation, kotlinx-coroutines-reactor, jpa/noarg, ArchUnit,
  Cucumber, junit-platform-suite; `springdoc-openapi` in 6.4)
- `application.yml`: jpa validate, UTC jdbc zone, Hikari connection timeout, `fees.sync-wait`,
  FeeCharge ingress-private
- `compose.yaml` (1.8): `127.0.0.1` bindings, Postgres volume, restart policies

**DELETE**
- `HelloWorkflow.kt`, `HelloWorkflowTest.kt`, `HelloWorkflowIT.kt` (slice 1)

**Companions in the same change:**
- the `ArchitectureTest` table when a package import changes;
- `docs/index.md` gets this doc's line;
- `requirements.md`:
  - AC30 and R16 name `HelloWorkflowIT`, so slice 1 renames them to `FeeEndToEndIT`;
  - §4 names `fees.feature`, so slice 3 renames it to `transaction_types.feature`;
  - §4 again if `FeeSchedule` takes another shape;
- the README configuration table when a setting is added.

**Harness follow-ups** (the owner applies these; `/.claude/` is git-ignored, so they never reach a
commit). After slice 1, propose:
- `house/plan-feature.md` §3's docs-impact sibling → `src/main/kotlin/com/cashi/fee/workflow/FeeWorkflow.kt`;
- `house/verify-change.md`'s one-class example (`HelloWorkflowTest`) → a unit test class from 1.3;
- `house/plan-feature.md` §4's patterns table, filled from slice 1's files.

**NOT building:**
- the D6 TODO types (Card Payment, International Transfer, Crypto Withdrawal);
- authentication, rate limiting and SDK request signing (Q12);
- a refund or saga path (D2b);
- an external payments service;
- fee rules in YAML or the DB (D5);
- a status or GET branch for a run cancelled or killed by an operator (Q6);
- a reconciliation job (the runbook query covers it here);
- CI (see the backlog);
- metrics and dashboards;
- a UI beyond Swagger.

## 8. Risks and the review gate

| Risk | Likelihood | Mitigation |
|---|---|---|
| The Restate client signals a duplicate workflow `send` differently from the spike's 409 | medium | the gateway handles both `PREVIOUSLY_ACCEPTED` and 409; the AC16/AC17 scenarios in slice 5 |
| `TerminalException` metadata doesn't reach the failure body the client sees, or state set before a terminal failure isn't readable afterwards | medium | confirm both in 1.4; the fallback for the tag is a message prefix; the 5.1 unit tests pin whichever one is used |
| `getOutputSuspend` reports a terminal failure differently from what the gateway expects | medium | the gateway is one class; 1.4's IT exercises a tagged failure through it before slice 5 depends on it |
| The admin API paths (purge, resume, SQL query, service read) differ from what this plan names | medium | `RestateAdmin` is one class; confirm against docs.restate.dev when slice 5 or 6 starts; a red IT shows it at once |
| The per-component Spring keys (`ingress-private`, `retry-policy`, `workflow-retention`) don't take effect as named | low | each has an admin-API assertion (1.7, 6.1) and a fallback (admin PATCH, server env, purge) |
| Jackson 3 coerces a JSON string into `BigDecimal` | medium | `amount` is bound as `JsonNode`; an AC26 example sends `"1000"` |
| Hibernate shifts `LocalDateTime` under the non-UTC JVM | happened in slice 1 | `java_time_use_direct_jdbc: true`, and every IT runs at UTC+14 (AC7) |
| Faults or runs leak between scenarios in the shared IT context | medium | faults keyed by `transaction_id`, `fired(id)` asserted, `reset()` in a Cucumber `@Before`, a unique `transaction_id` per scenario |
| springdoc has no release that works with Boot 4.1 / Jackson 3 | medium | task 6.4 is optional: drop it and keep the README's curl |
| Cucumber versions clash with Boot 4 / JUnit 6 | low | the slice 1 harness task proves it before any feature work |

Review gate dimensions (kit defaults: no `review.dimensions` configured, and `.claude/rules/`
holds only a README): `security` over every file, `practices` (advisory) over code. A CRITICAL or
HIGH finding on a double charge or on money would be refuted by the AC17/AC18/AC19/AC21 and
AC8/AC10 scenarios. Each slice's base is the commit before it.

## 9. Practice notes

- **A1/A2/KS-T1 followed:** `ArchitectureTest` holds the §2 table from slice 1.
- **A3 followed:** `fee_record` has one writer, the `record` step, enforced in 6.3.
- **A5/KS-W2 followed:** one `ApiExceptionHandler`, `ProblemDetail` with an `errors[]` extension; it
  never echoes exception messages.
- **A7 followed:** a retried run is harmless (`lookup`, `FeeCharge` keyed by id with its
  fingerprint check, a `save` that is safe to replay), and the dead-letter path is Restate's pause
  plus the runbook.
- **KS-W4 deviates:** `FeeRequestValidationService` instead of `@Valid`, because AC27 lists every field and
  most rules cross fields (asset × scale, type × asset type, fee × amount, the ceiling before any
  rendering). Suggestion: none. Bean Validation would cover only the trivial half.
- **KS-C2 followed:** `FeeProperties` is `@Validated`, with constraints, and the validation
  starter provides the validator.
- **KS-L2 followed:** the `jpa` compiler plugin arrives with the entity (D4).
- **KS-R2 followed:** every `catch` in suspend code rethrows `CancellationException`; 4.2 tests it.
- **KS-R3 followed:** every JDBC call from suspend code runs under `Dispatchers.IO`; the controller
  endpoints are `suspend fun`, with no `runBlocking`.
- **KS-D5 followed:** virtual threads for servlet work, and coroutines only where Restate is
  suspend; the rule is in §3.
- **KS-P1 followed:** Flyway, `ddl-auto=validate`.
- **T2 followed:** `verify.commands` runs `./mvnw -q verify` for any `src/test/**` change too, so
  an IT-only change runs its ITs.
- **T3 followed:** `failIfNoTests` on Surefire and Failsafe.
- **KS-T2 followed:** Failsafe runs `*IT`; the `test` exclusion is written in `CLAUDE.md`, the
  README and the house verify-change file.
- **O1 followed:** each 5xx is logged once with the `transaction_id` and the phase.
- **S3 followed:** the amount ceiling and the 18-decimal bound stop unbounded input, huge or tiny exponents, before any arithmetic; the
  `transaction_id` allow-list (4.1) and the client's URL-encoding of workflow keys guard the
  Restate paths.
- **K2 followed:** every departure from `requirements.md` is listed in §10 with its reason.
- **D1 followed (after the build):** `.github/workflows/build.yml` runs `./mvnw -B verify` on every
  push to `main` and every pull request; the backlog entry is closed.
- **R-S3 followed:** slice 1 is end to end.
- **R-A5 followed:** `@ACn` tags plus `TraceabilityTest`.

Defaults: no defaults source configured (`defaults.source`).

Reference designs: skipped. This is a backend API with no product surface; the contract is fixed
by the brief.

## 10. Docs, backlog and learning

- `requirements.md` stays the spec. A slice that departs from it corrects that passage in the
  same change.
- **On approval**, `requirements.md` takes the owner decisions:
  - **AC26:** the amount bullet becomes "an `amount` that is zero, negative, a JSON string, over
    the asset's scale, or with more than 20 integer digits" (Q1).
  - **AC12:** the scale counts significant decimals: trailing zeros don't count (Q13).
  - **AC5:** "echoed as sent" means the same number, rendered by AC3's rule (Q10). Labels match
    exactly (Q11).
  - **AC24 and D3:** `state` holds `SETTLED` only (Q2).
  - **AC25 and D2:** FeeCharge stores the payload fingerprint. A repeat with the same fingerprint
    returns the stored charge, even if the fee has moved since (Q9); a different fingerprint gets
    409 and changes nothing.
  - **AC27:**
    - the 503 wording separates "nothing was started" (connection refused) from "outcome unknown;
      resending is safe" (Q4), and adds "fee records unavailable";
    - a rule rejected after the workflow started is a 400 (Q7).
  - **AC29:** after a rejection, GET answers the POST's 4xx (Q8). The known limits in §3 are
    noted.
  - **R14:** its test becomes the closed-port 503 only (Q5).
  - **R3:** its test becomes the 6.3 architecture rule plus 6.2(d).
  - **The trust boundary for SDK port 9080** (Q12) goes beside D1.
- `docs/index.md` gets one line for this doc.
- Backlog, three entries:
  - **CI.** Trigger: the repo is pushed to GitHub for submission. Plan: a GitHub Actions job
    running `./mvnw -B verify`.
  - **Workflow tests without Spring** (`sdk-testing` `@RestateTest` with a fake `FeeRecordRepository`, the
    image pinned). Trigger: the IT suite gets too slow to run per task. Plan: move the workflow's
    branch cases there and keep the end-to-end scenarios.
  - **A run cancelled or killed by an operator** (Q6). Trigger: one is ever observed, or a cancel
    or kill is needed in operation. Plan: a GET branch and a 500 `/problems/fee-workflow-ended`
    added to AC27 and AC29, based on how `getOutputSuspend` reports the failure (already exercised
    by the gateway).
- Learning cards (`/wikingai:teach`, at most two per change; the known-topics list in
  `house/teach.md` isn't filled in yet): *Restate durable steps and determinism* in slice 1, and
  *Virtual Objects as a once-only charge* in slice 5.

## Gate 2

Before each commit: ask the user to run `/wikingai:verify-change`. When the slice is finished,
before merge or push: suggest SKIP, LIGHT (unless `review.allow_light` is false) or FULL from
`.claude/wikingai/house/review-gate.md`; the user picks and types
`/wikingai:review-gate [light] <base>`, naming the base that covers the slice (never start it
yourself). After an APPROVE, apply its advisories and verify. Only if a fix changed production
code, ask the user to type `/wikingai:review-gate recheck`, and pass `since`, that result's
`fileHashes`, and `dimensions`, the fixed findings'. Commit only when the user asks.
