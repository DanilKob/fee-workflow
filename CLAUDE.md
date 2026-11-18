# fee-workflow

A Kotlin / Spring Boot 4 service with one public operation, `POST /transaction/fee`: it takes a
settled transaction, works out its fee, charges it and records it, driven by a Restate workflow
(calculate → charge → record) with Postgres as the system of record. What it must never do is charge
a transaction twice or get a fee wrong by a cent: `BigDecimal` all the way, HALF_UP once at the
asset's scale, idempotent per `transaction_id`, nothing non-deterministic outside a durable step.
The spec is `docs/requirements.md`. `./mvnw -q verify` (Docker running) is the gate, because
`./mvnw test` skips every `*IT`.

<!-- wikingai:knowledge:start -->
## Knowledge

- The docs in `docs/` are the design source of truth; start at `docs/index.md`.
- Open items go only in `docs/todo-backlog.md`, each with the trigger that makes it worth doing.
- Kit config: `.claude/wikingai/config.json`; each skill's project half: `.claude/wikingai/house/<skill>.md`.
- Plan with `/wikingai:plan-feature`, check with `/wikingai:verify-change`, fix the docs with `/wikingai:sync-docs`, tune this setup with `/wikingai:retro`.
- Delegation: launch every builder with `model` and `effort` from `build.tiers` (`wikingai config`): the plan's TIER line; without a plan, tier 1 (the floor) for small, read-only or pattern-copying work and for Explore agents, the last tier (the ceiling) for a risk escalator, a design or a Plan agent, tier 2 otherwise (the ceiling when there are fewer than three); after two VALIDATE failures relaunch a fresh builder one tier up with its description starting `Escalate:`, and at the ceiling hand the failure to the user.
<!-- wikingai:knowledge:end -->
