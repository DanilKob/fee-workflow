# Backlog — fee-workflow

The only list of open items. Each entry says what was found, the trigger that makes it worth
doing, and the plan for when the trigger fires. Delete an entry when it ships.

## CI

- **Finding:** no CI runs the build (practice D1); `docs/plan.md` §9 records the deviation.
- **Trigger:** the repo is pushed to GitHub for submission.
- **Plan:** a GitHub Actions job running `./mvnw -B verify` (Docker is available on hosted runners).

## Workflow tests without Spring

- **Finding:** every workflow branch is tested through the full IT stack (Spring, Postgres, Restate).
- **Trigger:** the IT suite gets too slow to run per task.
- **Plan:** `sdk-testing` `@RestateTest` with a fake `FeeRecords` and a pinned image for the workflow's branch cases; keep the end-to-end scenarios.

## A run cancelled or killed by an operator

- **Finding:** such a run has neither a record nor a stored rejection, so GET answers 202 until retention ends (`docs/plan.md` §1 Q6).
- **Trigger:** one is ever observed, or a cancel or kill is needed in operation.
- **Plan:** a GET branch and a 500 `/problems/fee-workflow-ended`, added to AC27 and AC29, based on how `getOutputSuspend` reports the failure.

## A test that Swagger stays off by default

- **Finding:** `springdoc.*.enabled` defaults to `${SPRINGDOC_ENABLED:false}`, but only the `it`
  profile, which turns it on, is tested. Nothing proves `/v3/api-docs` answers 404 without the
  variable (final review advisory, slice 6).
- **Trigger:** the springdoc configuration or the application profiles change, or the service is
  deployed anywhere other than the compose demo.
- **Plan:** an `ApplicationContextRunner` or slice test without the `it` profile: no variable means
  404 on `/v3/api-docs` and `/swagger-ui.html`; `SPRINGDOC_ENABLED=true` means both are served.
