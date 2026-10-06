# Shapeshifter AI: open issues

What is known to be unfinished or undecided, with where the detail lives. Rulings are cited by number
(design 01 §13, design 04 §10, design 05 §9). Close an entry by striking it through with the date and
the commit or ruling that closed it, rather than deleting it.

## Needs a ruling

1. **Whether deferred tasks count against a filter's task budget.** Task creation bounds itself on
   `countTasksForFilter(filter, CREATED)`, so `DEFERRED` tasks are outside the budget: the deferred
   population is bounded by what arrives, and a wake or a sweep can return a backlog to `CREATED` far
   past the limit, after which creation pauses until it drains. Options: count `CREATED` and `DEFERRED`
   together, which brings back the stall design 04 §5.2 avoided; or a separate per-filter cap on deferred
   tasks past which creation pauses. Recommended: leave it until the real-scale trial shows whether the
   deferred population matters. *Design 04 §5.2 and §9.*

2. **A format change under a rule goes unnoticed.** What a parser drops while a stream is served — lines
   its splitter does not match — is reported on the error stream but judged by nothing, since the record
   bar judges records and these never became records. A feed that changes its format under a rule is
   not sent back to be learned until a person marks the shape (A100). *Design 04 §10, A99's note.*

3. **Improving a serving rule is still synchronous.** The Supervisor's *improve* is the one place the
   model is asked outside the worker (A102): it learns against the rule's accepted corpus, which the
   worker cannot read back as a stream. Moving it to the worker needs the attempt to carry its own
   sample — a change to the attempt row. *Design 04 §10, A102.*

## Small fixes

4. ~~**The lease can lapse during a long model call.**~~ *Closed 2026-10-06: `HeartbeatingAdvisor` pushes
   the claim out every third of the lease while a question is with the model, design 04 §4.* A session's claim is pushed out before every
   question but not during one, so a single call longer than the lease (ten minutes by default) lets
   another node take the attempt and ask the same question. A heartbeat on a timer during the call fixes
   it. Worth doing before anything runs unattended. *Design 04 §4.*

5. **Grant asks for tokens only.** The resource takes tokens and milliseconds; the Supervisor's button
   prompts for tokens. *Design 04, phase 6 first slice (A77).*

6. **A session sent to the exhaustion step shows no mark** on its row in the Supervisor beyond its state,
   though the attempt carries the flag. *Design 04, phase 6 third slice (A65).*

7. **An unreadable step edit is lost on the Flow tab.** An edit the form cannot read as a step — an id
   that is not a word, a limit below one — is reported beside the form, and lost if another step is
   clicked before it is put right. *Design 05 §7, slice 3's audit.*

8. **A kind's own checks are not named.** Where no check is ticked, the step's panel says it is judged by
   its kind's own checks but cannot say which: that table lives in the engine. Moving it into the shared
   model would let the engine and the panel read one table. *Design 05 §10.*

9. **A multi-part step's pass is highlighted early.** On the Supervisor's path over the flow, a step of
   several units — a Configure over the whole chain, a Target over several kinds — records a turn per
   unit, and the first unit's pass lights the step's pass edge. *Design 05 §7, slice 2's audit.*

10. **A marked shape relearns on its first stream whose prefix is big enough.** A task holds only its
    stream's prefix, so the mark path judges that. Noted rather than a fault. *Design 04 §10, A100.*

## For the real-scale trial

11. **The backlog pages in memory,** reading every readable document's rows, and its "raised" count is
    the page total rather than the raised rows. *Design 04, phase 6 first slice's audit.*

## Before a pull request

12. **The full build and the Docker images** — `./gradlew clean build shadowJar buildDistribution` and
    `./buildDockerImages.sh` — have not been run since phase 6 began.

13. **One live-model run** against the real model, since the flow, its exits and the Chain step's
    elements have all changed under the harness. *Design 02 §6.3.*

14. **A changelog entry,** through `./log_change.sh`, which needs a GitHub issue number.

## Walkthroughs

15. **Phase 6's exit:** a person takes a feed from unseen to served from the UI, watching the backlog form
    and drain, with no error stream written at any point. *Design 03, design 04 phase 6.*

16. **Phase H's exit:** a person composes the escalating example on the Flow tab from an empty flow
    without typing the grammar, and reads a parked session's path in the Supervisor. *Design 03, design 05.*
