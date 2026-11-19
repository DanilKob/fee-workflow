# fee-workflow — docs

The design source of truth: decisions, plans, contracts and runbooks live here, and CLAUDE.md
only points here. This index names every doc in this directory, one line each — `wikingai lint`
warns when one is missing (`docs.index_file` in `.claude/wikingai/config.json`).

- `index.md` — this index.
- `todo-backlog.md` — the only backlog: open items, each with the trigger that makes it worth doing.
- `learning/` — the owner's learning cards: one per technique applied or advised (why it mattered here,
  where it lives, pitfalls, read next; `status` applied/suggested/rejected); a slice that applies a new
  technique writes its card in the same change. Its README is the folder's index and reading path.
- `brief.md` — the coding task as given: the API, the sample request and response, the bonus items.
- `requirements.md` — the brief with every open question decided: acceptance criteria AC1–AC31,
  decisions D0–D11, the extensibility model and the money/data risks R1–R16 with their tests.
- `plan.md` — the implementation plan: six slices, the AC → scenario → slice table, the owner
  decisions taken at plan review.

A plan doc matches `docs.plan_glob` (`docs/*plan.md`: the plan itself, or `<topic>-plan.md`); when it ships its status line says "as built" and lists the deltas.
A rejected option goes into its doc's decided-don't-relitigate section, with the reason.
