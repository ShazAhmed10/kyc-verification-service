# Portfolio notes

**verification-flow-prototype** — a personal practice project, designed and built by me. Not
production software and not associated with any employer; the domain and the vendor ("VerifyPro") are
invented, and the vendor is simulated by a container in this repo.

I built it to have something concrete to point at when talking about integrating asynchronous
third-party vendors — the class of problem where the vendor tells you *that* something happened but
you still have to work out *what*, and where every message can arrive twice, late, or not at all.

## Resume bullets

- Designed and built a Java 17 / Spring Boot service demonstrating asynchronous third-party vendor
  integration, where the vendor's webhook is treated as a **trigger rather than a source of truth** and
  the verdict is always re-read from the vendor's result API — making reconciliation idempotent by
  construction and reducing a forged or replayed webhook to a wasted call rather than a wrong decision.

- Implemented a **declarative state machine** with no framework and no branching on state: every legal
  move is one row in a table keyed on `(event, fromState)` with the target derived, which lets one event
  resolve differently depending on the aggregate's current state. The table is validated at startup so
  an ambiguous duplicate row fails the boot rather than silently resolving to whichever row was indexed
  first.

- Built the async path for at-least-once delivery: queue messages carry **only an entity id** so the
  consumer rebuilds its input from current state and a redelivery cannot act on a stale snapshot;
  publishing is deferred to `@TransactionalEventListener(AFTER_COMMIT)` so a rolled-back transaction
  cannot emit an event; and a sweeper recovers requests whose callback or queue publish was lost.

- Implemented **read-repair caching** — a denormalised projection with a freshness stamp, rebuilt in
  place on read when stale, with a per-key guard collapsing concurrent rebuilds — so nothing is served
  stale and no work is spent refreshing rows nobody reads.

- Added an LLM-backed operational endpoint that explains one entity's history in plain English, with
  **anomaly detection kept deterministic in code and the model used only for narration** — a split that
  makes the detection unit-testable (11 tests) and keeps the model away from arithmetic it cannot be
  trusted with. Caught and fixed a real flaw in the process: purely relative "3× the cohort median"
  detection goes blind during a systemic stall, so the alert gate is absolute and the peer comparison is
  reported as context.

## What I would say about it in an interview

The things worth discussing, all documented in the README's trade-offs section:

- Why `(event, fromState)` rather than `(event)` is the right key, and the concrete case that forces it
  (approval arriving with no preceding callback).
- Why publish-after-commit is *better* than publishing inline but still **not** a transactional outbox,
  and exactly what window remains.
- Why a weak webhook secret is acceptable here specifically, and what would make it unacceptable.
- Why the relative anomaly threshold was wrong, and how a test surfaced it.
- What the queue-based worker does not demonstrate that a real managed function would — chiefly
  credential isolation.
