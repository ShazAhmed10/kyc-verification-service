# Build notes

A record of what was built, the decisions taken during the build, and the two things that changed
mid-flight. Distinct from the other two docs in this repo: `README.md` is for someone running or
reviewing the project, `PORTFOLIO.md` is the resume framing, and this is the account of how it came
together.

Final state: clean `mvn verify`, **43 tests**, **7 commits**, working tree clean.

---

## Decisions taken

Three were left open at the design stage. All three were resolved as originally proposed.

| Decision | Choice | Why |
|---|---|---|
| Async worker | RabbitMQ consumer, same artifact under a `worker` Spring profile | Real at-least-once delivery, retry with backoff, a dead-letter queue and a genuine process boundary - all testable without cloud infrastructure |
| AI feature | "Explain this entity's history" | Demos deterministically from one entity, exercises the state-history model the rest of the project builds, and splits cleanly into testable detection plus model narration |
| Module layout | Two Maven modules | Makes the vendor a real external process rather than a mock bean, which is what lets lost callbacks and timeouts actually be experienced rather than only asserted |

## What is in the repo

```
pom.xml                    two-module Maven build
Dockerfile                 multi-stage, MODULE build arg (both services share it)
docker-compose.yml         postgres, rabbitmq, api, worker, vendor-sim
README.md                  architecture, how to run, design decisions and trade-offs
PORTFOLIO.md               5 resume bullets plus interview talking points
BUILD_NOTES.md             this file
verification-service/      the app - 24 classes, 5 test classes
vendor-simulator/          fictional "VerifyPro" as a real process
```

One command to run everything: `docker compose up --build`. `mvn test` needs no Docker.

---

## Two corrections made mid-build

### 1. The Anthropic Java SDK, not raw HTTP

The original plan was to call the model with the JDK's `java.net.http.HttpClient`, to avoid adding a
dependency. Checking the API reference before writing the client showed that Java has an official SDK
(`com.anthropic:anthropic-java`), which is what should be used - and is the better signal in a
portfolio piece anyway, since a reviewer recognises it.

The same check caught a stale model identifier sitting in `application.yaml`. It is now
`claude-opus-5`, with adaptive thinking and `effort=LOW`. Low effort is the correct setting here and is
a direct consequence of the design: the model writes prose from facts that have already been computed,
so it is not doing the analysis and paying for deep reasoning would buy nothing.

### 2. A failing test exposed a real design flaw

Three tests in `StuckSignalCalculatorTest` failed, and the reason was substantive rather than a bad
assertion.

The stuck-detection threshold was `max(3 x cohort median, floor)` - flag a request when it has been in
its state far longer than its peers. The flaw: when a request is two hours old and its peers are
equally old, the median is two hours, the threshold becomes six hours, and nothing is ever flagged.

**Relative-only detection goes blind precisely during a systemic stall** - the case you most need it to
fire.

The fix was to the design, not the assertion:

- The **gate is now absolute** - a configured service expectation for how long a state should take.
- The **peer ratio is still computed and reported**, as `cohortOutlier`, because "is it just this one,
  or is everything equally bad?" is a genuinely different and useful question for whoever is on call.

A regression test named `systemicStallStillDetected` now covers it. This is the most interesting thing
in the project and it appears in three places: the README trade-offs, a commit message, and
`PORTFOLIO.md`.

---

## Test coverage, and where it is weighted

43 tests, deliberately concentrated on the two areas that decide correctness.

| Area | Tests | What it covers |
|---|---|---|
| State machine | 14 | Table validity, duplicate-key rejection at startup, self-loops not resetting the state clock, illegal moves refused, override paths |
| Reconciliation | 12 | Redelivery on a settled request, vendor failure leaving state untouched, INCONCLUSIVE then APPROVED, approval with no preceding callback, attempt counting, the locking read |
| Stuck signals | 11 | Absolute gate, empty and small cohorts, self-exclusion, median rather than mean, systemic-stall regression |
| Read-repair | 6 | Fresh hit, stale rebuild, missing summary, invalidation, unknown id, concurrent stampede collapse |

Two choices worth noting. The reconciliation tests use a **real `TransitionEngine`** rather than a
mock, so they fail if the state machine is wrong and not merely if the service forgets to call it. And
`Clock` is injected everywhere rather than calling `Instant.now()` inline, so time-dependent behaviour
is asserted rather than slept through.

---

## Verifying it by hand

The vendor simulator picks its behaviour from a prefix on the subject reference, so every failure mode
can be driven without an admin API:

```bash
curl -s -XPOST localhost:8080/api/verifications \
  -H 'Content-Type: application/json' -d '{"subjectRef":"silent-bob"}'
```

`silent-` makes the vendor **never call back**. Nothing arrives, and roughly two minutes later
(`EXPIRY_TTL=PT2M` in compose) the sweeper reconciles it and it resolves anyway. That is the
lost-callback recovery path running end to end, and it is the single best thing to demo.

Other prefixes: `decline-`, `flip-` (inconclusive then approved), `flaky-` (503s twice), `slow-`
(sleeps past the read timeout), `unknown-` (vendor has no such reference).

`GET /api/admin/state-machine` dumps the live transition table, so the table printed in the README can
be checked against the running code rather than trusted.

---

## Open items

- **Commit messages carry a `Co-Authored-By: Claude` trailer**, per the standing instruction in this
  environment. For a portfolio repo you may prefer them without it - a `git rebase` or a fresh
  `git init` strips it. Left in rather than silently omitted, so the choice is yours.
- **No remote is configured and nothing has been pushed.** It is a local repo, push-ready when you
  want it.
- The README states what the design **does not** do as plainly as what it does: the queue worker does
  not demonstrate credential isolation, publish-after-commit is not a true outbox (with the remaining
  window described), the stampede guard is per-instance rather than distributed, and the admin
  endpoints are unauthenticated. Those paragraphs are the ones to read before an interview.
