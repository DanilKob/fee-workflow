# Learning cards — the techniques fee-workflow applies, explained for its owner

A cheat sheet and a self-education plan in one place. Every card explains one technique the
project applies or has been advised to apply: what it is in one sentence, why it mattered **here**
(the measured fact, never a textbook example), where it lives in the repo, how to see it working,
the pitfalls, and what to read next. Cards are short on purpose: one screen, five minutes.

A card's `status` says whether the technique is applied, suggested (in the backlog with a
trigger) or rejected (with the reason), so the folder also records what was decided against and
why. The cards are the owner's reading: nothing loads them at session start, and Claude opens one
only to write or update it, or when a plan touches its technique.

## The rule

**When a slice applies a technique the owner has not met before, its as-built paragraph names the
technique and its card is written in the same change** — at most two cards per change; a
suggestion the owner has to understand before deciding gets a `suggested` card the same way.
`/wikingai:teach <topic>` writes or updates one card on demand.

## The card

```
---
topic: <kebab-case name, the file name>
status: applied | suggested | rejected
since: <date, and the commit or slice>
level: beginner | intermediate | advanced
read: <minutes>
practices: [A6, KS-P2]      optional: the practice-catalog items it applies
backlog: <entry title>      optional: the backlog entry a suggested card waits on
---
# Title
**In one sentence.**
**Why it mattered here.**   the measured fact and the decision it drove
**How it works.**           3–6 bullets, no more
**See it in this repo.**    file paths (clickable), one command that shows it
**Pitfalls.**               what bit us, or would
**Read next.**              2–3 links, official docs first
```

A `suggested` card names its trigger in one line and links its backlog entry: a `backlog:` key,
or the backlog file and the entry's title (`docs/todo-backlog.md` "Entry title"). A
`rejected` card adds a **Why not.** paragraph: the reason, and what would reopen it. A companion
that is not a card (a glossary) carries `status: reference`: the frontmatter and no lead-ins; the
path below may list it with a dash in its status cell, and the counts keep it apart.
`wikingai lint` checks the folder (area `learning`): the frontmatter, the six lead-ins in order,
the table below listing every card once, a suggested card's trigger, a rejected card's reason.

Obsidian renders the frontmatter as properties, so the folder can be filtered by `status` and
`level`. `[[wiki links]]` between cards are welcome; links out of the folder use the
repo-relative paths the other docs use.

## The learning path (read in this order)

The order says how to read the cards: by the path a request takes through the system, by level,
or by date. Each row says what the reader will be able to explain afterwards.

| # | Card | Status | Level | What you will be able to explain afterwards |
|---|---|---|---|---|
<!-- | 1 | [[example-technique]] | applied | beginner | the one thing a reader can explain after this card | -->

## Where this folder sits

- The plan docs in `docs/` hold the **record** (what was measured, decided and built);
  the cards hold the **explanation**. A card never repeats a record row — it links to it.
- `docs/todo-backlog.md` holds every suggested item with its trigger; a `suggested`
  card links to its backlog entry and says what the trigger is in one line.
- A technique that crosses repos (it applies to two members of the vault, or to the pipeline
  between them) gets its card in the vault's `learning/` folder instead, and this path lists it
  as plain text: `<vault> › <topic>`.
- The wikingai kit owns the convention (this README's shape, the lint check, `/wikingai:teach`);
  this folder is the project's instance of it and stays with the project.
