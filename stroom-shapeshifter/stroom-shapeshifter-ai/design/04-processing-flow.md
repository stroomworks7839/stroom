# Design 04 — The processing flow: how a stream waits for what it needs

*Written 2026-10-04, from the owner's rulings of the same day. Design 01 describes what the stage
decides; design 02 the scenarios that force it; design 03 the order the work is done in. None of them
says how a stream gets to the stage, or what becomes of it when the stage is not ready for it. That is
this document. It is a new mechanism for executing AI pipelines, and the only one of these documents
whose work lands substantially outside `stroom-shapeshifter`.*

---

## 1. What is wrong now

A processing task is pushed at a pipeline and has two ways to end: it completes, or it errors. The stage
needs a third — *not yet* — and in its absence it has been built out of the two that exist.

**The lock is an error.** `Stage.recording` claims a shape with `attempts.opened`, a unique key on
`(doc_uuid, claim_key)` that admits one learner. The claim itself is sound and cheap. What is not sound
is the consequence of losing it: `sentinel(...)` writes an error stream and a ledger row, and the
backlog is released later by `PipelineReprocessing`, which creates a reprocess filter at priority 1 over
`PARENT_ID IN (…) AND PIPELINE IS …`. So a stream that merely arrived early becomes an error, a ledger
row, and a second pass through task creation. In deferred mode that is the fate of *every* stream of an
unlearned shape.

**Learning holds a processing thread.** In inline mode the node that wins the claim carries the whole
conversation — several model calls, each as slow as the endpoint is — inside a processing task, with the
stream's meta locked for the duration. At any width of thread pool this is what exhausts connections and
makes tasks look stuck.

**Scoring is a lagging trigger.** `Stage.serve` already judges every stream it serves. What it does with
the judgement is the problem:

```java
final OptionalDouble rolling = shapes.scored(doc.getUuid(), shape.id(), judged.score(), …);
if (rolling.isPresent() && rolling.getAsDouble() < doc.getRelearnThreshold()) {
    shapes.markForRelearning(…);
}
return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
```

It emits whatever the transform produced, records that the shape is drifting, and waits for a later
stream large enough to relearn from. A record the transform has never met is translated badly and the
output kept.

**None of this is a bug.** It is what you get when the only way to make a stream wait is to fail it.

---

## 2. What a shape is, and the two kinds of failure

**A shape is a feed and a type** (A49). All streams of one feed and type are served by one learned
pipeline. Event types *within* a stream are XSLT match treatment: the transform grows a match per
record type as it learns, and nothing outside the transform identifies them. There is no record-level
signature, no coverage set, no novelty measure — a record the transform cannot translate says so by
scoring low, and that is the whole of the signal.

The learning key stays configurable, so an operator may still split a feed by a receipt header where one
feed genuinely carries two unrelated things; feed and type is what it means by default and what the
mechanism below assumes.

**The two stages of a chain fail differently, and only one of them is an error** (A52).

| Stage | Cannot do it | What happens |
|---|---|---|
| Transform | A record type it cannot translate well | The session learns a match for that type whose output is a marked Unknown (A51, A53). The stream then scores and is served. A translation that has the budget to finish always terminates; one that runs out waits for a person (A65) and errors nothing. |
| Parse | Records cannot be got out of the stream at all | A real error. There is nothing to score, nothing to learn against, and nothing to serve. The stream errors and says why. |

A parse cannot be learned when the session's parser candidates are spent without meeting the
document's own input-coverage and yield gates (A54) — no new setting, the thresholds an operator
already sets decide it.

**Two different things run out, and they must not be confused.** A *step* of the plan has its own
number of candidates, and spending them is the plan working as written: it takes the step's transition
— to the exhaustion step for a transform, to giving the shape up for a parse. The *document's* budgets
— `maxAttempts`, `attemptBudgetMs`, `tokenBudget` — bound the whole session, and when one of those runs
out the session has not reached a conclusion at all: it parks for a person (A65), whichever stage it
was in. So a parse is given up because its step was tried in full and failed, never because the
session ran out of money part-way through trying.

So "given up" survives for parse failure alone, or for a person saying so. Everything that reaches
records ends in output or waits for a person — never in an error — and Unknown events are the honest
residue of a session that tried everything else.

---

## 3. The task decision

Every task does the same cheap thing: resolve the feed and type, consult what is known, and take one of
three outcomes — emit, defer, or error.

| What is true | Outcome |
|---|---|
| A deferral row for this stream names the transform it already failed against, and that is still the transform in force | **Defer without running the transform.** One lookup: no transform, no model, no output. If no attempt holds the shape — the last one lapsed or failed, a parked one still holds it — open one, or the stream would wait on nothing. |
| A rule serves this feed and type, and every record clears the bar | **Emit.** The ordinary case; no model, no state written beyond the rolling score. |
| A rule serves it, and any record falls short | **Defer.** Emit nothing. Write the deferral row against the transform in force — one small row, and nothing about which records failed. Open the session if no attempt holds the shape. |
| No rule, and no attempt holds the shape | **Defer**, and open the session with this stream as its subject. |
| No rule, and an attempt holds the shape | **Defer.** |
| The shape is given up — a session found the parse unlearnable, or a person gave it up | **Error the stream**, with the ledger saying so. |

Learning never happens here. A session is the worker's, and the only thing a task does about it is open
it and stand aside. Nor does a task decide that a parse is hopeless: that is a session's conclusion
(§4), and what a task reads afterwards is a shape that has been given up.

**The bar** is per record and is the gates, not the weighted total: a record clears it when every gate
scorer that applies to a record passes on *that* record. The weighted total stays what it always was —
the promotion gate's measure over a whole candidate. Gates alone are the bar (A55): they are what
"this record did not translate" means, and a per-record weighted threshold would be a new setting
holding streams over output that is merely mediocre.

**An Unknown has to be able to clear the bar, and today it cannot.** `ExtractionQualityScorer` counts a
record whose `EventDetail` is `Unknown` against the candidate — rightly, since that is the degeneracy
trap it exists to catch — and the demo document runs it as a gate with `allowUnknownEventDetail` off.
Left as it is, a session that ends in Unknown matches (A51) produces a stream that still fails, and A50
never releases it. The corpus cannot settle this by itself either: it can say that *this* record's
accepted output is Unknown, but tomorrow's record of the same type has different values and no entry.
So the admission is recognisable **in the output** (A53): the exhaustion step asks for an Unknown event
carrying a defined marker that says it is a learned admission — a named `Data` item inside
`EventDetail/Unknown` (A62), so the event explains itself to anyone reading it and to any processing
downstream — and the quality scorer accepts a marked Unknown where it still refuses a bare one, and
leaves that one `Data` item out of its count of untyped elements. The trap stays closed in both directions — a bare Unknown
is refused as it always was, only the plan's exhaustion step may ask the model for the marked form, and
the corpus stops a record that was once translated from becoming Unknown later.

**The transform in force** is the rule's current snapshot. A session improves a transform **in place**
(A58): one fragment pipeline and one stylesheet per shape, growing matches as it learns, which is what
"event types are match treatment" looks like as documents and what a person who has one open would
want. A fragment is several documents, each with a version of its own, so their versions cannot be the
stamp; and as-processed reprocessing (design 01 §7.3), which until now could name a fragment, must now
name a state of one. One thing answers both: every promotion keeps an immutable **snapshot** of the
fragment's configuration (A61), and the snapshot's id is what a rule points at, what a deferral row is
stamped with, what `Outputs` records beside the pipeline, and what an as-processed reprocess runs.

### Deferral is version-stamped

A deferred stream records the snapshot it failed against. On its next attempt:

- the rule still points at that snapshot → defer again immediately, no transform run;
- the rule has moved on → score once more.

A promotion therefore re-scores every deferred stream of that feed and type exactly **once**. The work
per stream is bounded by the number of promotions it lives through, with no counters, no backoff
tuning, and no gate on the shape. Streams whose records all score keep flowing while a session runs,
which costs nothing in correctness: Stroom does not guarantee the order in which streams are processed,
so nothing downstream can be relying on one.

### Streams of any size

A stream may be tens of gigabytes, and two things the code does today cannot survive that: the
supervisor element reads the whole stream into a `String` ("the whole stream is read", `read(inputSource)`),
and a served run is held as an `EventList` of the whole output before any of it is fired. Both go.

- **Scoring streams.** The chain already runs record by record — the learned boundary is a
  `SplitFilter` and the transform is given one record at a time — so each record's output is scored as
  it is produced. The first record short of the bar ends the run: the task defers, and the rest of the
  stream is never read. A stream that passes is read exactly once.
- **Output is written as it is made, and discarded if the run defers.** An output target is locked
  until the task that opened it ends, and `Target` can logically delete it. So a serving run writes
  straight to its output in one pass, in constant memory, and a deferral deletes the target before
  anything could have seen it. This replaces holding output back until the verdict: at this size there
  is no holding anything back, only not committing it.
- **The acceptance test is one full pass, not one per question.** Candidates are judged on the sample
  — a prefix bounded by `sampleSizeLimit`, as today — and the held stream is run in full only for a
  candidate that has passed the sample. A record the full pass finds short of the bar is small, and it
  becomes the next question's sample; the loop is bounded by the number of record types in the stream,
  not by its length.
- **The corpus takes records, never the stream.** A session's entries are the records it put to the
  model — from the sample and from what the full passes turned up — so a first stream of ten gigabytes
  contributes the handful of records that were actually learned for.
- **The worker reads the held stream from the store the same way**, streaming, through `Inputs`.

---

## 4. The session

A session is opened by the task that met the record it could not translate, and carried by the worker.

**The held stream is the acceptance test** (A50). The session is finished when the stream that triggered
it scores in full — every record at or above the bar, by whatever mixture of real translations and
learned Unknown matches the model arrived at. That is the exit condition; no coverage criterion and no count of candidates decides that the work is
*done*. The document's budgets — `maxAttempts`, time, tokens — still bound what a session may *spend* (§2). A session
whose budgets run out before the stream passes **parks for a person** (A65): it keeps its lease, its
streams go on waiting, and the Supervisor shows what it stopped at. Nothing is baked as Unknown because
money ran out — an admission is what the plan's exhaustion step produces after the trying is done, not
what a budget produces by ending it. A person may answer the question it stopped at (A28), give it more
to spend, or send it to the exhaustion step.

Parking for a person is not the parking the code has. `AWAITING_MODEL` is an attempt any worker may
take, and `opened()` lapses an attempt whose expiry has passed so that a dead node lets the next in. An
attempt parked for a person needs a status of its own that the worker leaves alone, and a lease that
does not lapse — otherwise the next task of that shape takes it and starts spending again, which is
the loop the budget existed to stop.

- **One stream at a time** (A69). A session learns from the one stream that opened it, and every
  waiting stream is tried again when it promotes. Ten streams arriving together on an unlearned feed
  are one session and ten deferral rows; if six still fall short afterwards, one of the six opens the
  next session and the other five wait again. The rounds cost the waiting streams a transform and a
  score each and no question to the model.
- **Evidence is worked out by the session, not stored by the tasks.** Which records of a stream fell
  short is known to a task for as long as it takes to decide to defer, and is then thrown away. The
  worker finds it again for the one stream it is learning from, by running the transform in force over
  that stream as its first act — `Judged.spans` carries a `TextRange` per record and `Inputs.textOf`
  reads a record back by one. Recording the failing records of every waiting stream would be storing
  a great deal about streams nobody is learning from, all of it stale the moment the transform moves.
  The session learns *every* failing record in its stream, so a stream that shows five unseen types is
  handled once rather than five times.
- **Pace.** A node that takes an attempt carries the session to its end in one pass (A64): it goes on
  asking until the held stream clears the bar, or its budgets are spent and it parks for a person. The job's schedule decides only how soon a new session is picked up. Today
  `DeferredWorker.advance` moves each waiting attempt one step per pass, which was right for an attempt
  that might park at any question and is wrong for one whose end is a stream passing.
- **The lease** is the attempt row, unchanged: one open attempt per `(doc, shape)` (A45), with its
  expiry, its turns, and its visibility in the Supervisor.
- **Which node, and what if it dies.** Learning is an ordinary scheduled job — "Shapeshifter AI Deferred
  Learning" — so it runs on every node the job is enabled on, and a session belongs to whichever of
  them claims its attempt first; `Attempts.claimed` is one conditional update, so two cannot. No node is
  *the* learning node, and one that never comes back takes nothing with it: the attempt, its turns and
  the stream are all in the database and the stream store. What the code does today with a session
  whose node has died is weaker than it should be, in two ways this design has to put right. The worker
  asks only for attempts `AWAITING_MODEL`, so one left `IN_PROGRESS` by a dead node is never picked up
  by a worker — although `claimed` already allows a lapsed one to be taken; it is recovered only when a
  task of that shape next calls `opened`, which marks it abandoned and starts a **new** attempt from
  nothing, with the turns already paid for left behind. And the lease is set once, to the attempt's
  time budget plus a grace period, and never renewed. So: the worker also takes `IN_PROGRESS` attempts
  whose lease has lapsed and **resumes** them by the re-walk A45 already built for a parked attempt;
  and a node renews its lease as each turn completes, so a live session is never mistaken for a dead
  one and a dead one is noticed within one turn's allowance rather than a whole budget's.
- **Regression** is held by the corpus: a candidate must not do worse on any record already accepted
  than what was accepted for it. This is what makes a new event type safe to learn, and it is the one
  prerequisite this design adds to work already listed — §12 item 9's durable store, today in memory
  only, with `RegressionSet.Accepted` carrying `(input, score, targets)` and no produced output.
- **Exits.** A promotion of the improved pipeline, or of the pipeline with its new marked Unknown
  matches; both change the transform in force and wake the deferred streams. A session out of budget
  does not exit: it parks, and holds. A parse that cannot be learned is the
  only exit that errors anything: the session gives the shape up, and the streams waiting on it error
  with that reason.

### 4.1 What is recorded

Three things are written, and the first of them already exists.

**The session** is the attempt row and its turns, which exist: `shapeshifter_attempt` holds the document,
the shape, the feed and type, the stream it was raised on, the node, the status, the decision
and the lease's expiry; `shapeshifter_turn` holds every question as it was put, the answer, who answered
and what the answer scored. A28 built them, the Supervisor reads them, and a session needs nothing more
to be readable or resumable.

**What a waiting stream leaves** is its deferral row and nothing else: the stream, the task and filter
that will be woken, the transform it failed against, and since when. No record of which of its records
failed, and none of its text. A session's own stream needs no more either — what it found wanting is
in the turns, as the questions it put.

**The corpus** is the regression record, per rule, and it is the one place where copying is unavoidable:
the source stream may be deleted by retention long before the next session, and a regression set that
loses its inputs tests nothing. So each accepted record is kept as the pair *(input record, the output
it was accepted with)*, with whether that output was a translation or a marked Unknown, the score it
achieved, the attempt that accepted it, and — where the plan
settled targets — the target it was held to. That is A18's regression stream per rule, inheriting the
source feed's classification and a retention capped by the feed's, with a row per entry naming the
stream and segment so the set can be read without scanning it.

Today `RegressionSet.Accepted` is `(input, score, targets)` where `input` is the **whole stream** — every
call site passes `input.data()` — held in memory only, and §12 item 9's durable form is deferred. Per
record is what this design needs, since the bar is per record and a stream of a million records is not
an entry anybody can afford a hundred of. What is kept (A56) is **only the records a session had to learn for**, each with the output it was
finally accepted with — never a whole stream, and never a record that already translated. Those are
exactly what a later session could break; a record that passed without learning either was learned in
an earlier session, and is in the corpus from then, or has nothing a session could change. The
document's existing `regressionCap` bounds a rule's entries (A63). An entry is a record, and a record
is small: the stream it came from may be tens of gigabytes and contributes only what was learned for. Both gaps matter, for different reasons. Without durability a session two months later has nothing to
regress against. The stored **output** is not what the gate compares — under A66 the gate re-runs a
candidate over each entry's input and asks whether the result clears the bar and whether a translation
has become an Unknown, for which it needs to know only which of the two the entry was. The output is
kept because it is the record the owner asked for: what each event that triggered a session became, so
that a person can see what a later session changed and judge whether "no worse" was true. A candidate is held to every entry in the corpus, and held to **no worse** (A66), not to the same:
every entry's new output must clear the bar, and a record once translated may never become Unknown.
Output may otherwise change — a deliberate improvement of a rule (A46) changes it on purpose — and a
marked Unknown may become a real translation without anybody agreeing, since that is the corpus getting
better. Exact equality would have refused both.

A marked Unknown does not ask to be improved, though (A68). It clears the bar, so records of that type
flow, and nothing retries them on its own: turning an admission into a translation is a person's
*improve* or hint in the Supervisor (A46), not tokens spent on a type already tried.

---

## 5. What `stroom-processor` gains

Four changes, all general, none of them about AI.

### 5.1 A task may end as deferred

The signal has to travel out of a pipeline element, and today **two layers swallow it**. In
`AbstractProcessorTaskExecutor`, `handleProcessingException` turns any `IOException` or
`RuntimeException` into `outputFatalError(e)`, and `exec` wraps the whole of processing in
`catch (final Exception e) { outputFatalError(e); }`. That is why the present mechanism had to make
waiting into an error: an exception from the stage *cannot* reach
`DataProcessorTaskHandler`, which is the only place that decides a task's terminal status.

So the deferral is an exception with a path cut for it, in three places:

1. A dedicated unchecked exception, declared in `stroom-processor-api` so that an element may throw it
   without depending on the implementation, carrying the reason. The throw site in this feature is
   `Supervision.supervise`, at the point where a decision with no bindings is today logged at
   `Severity.ERROR` — the one line that makes a waiting stream an error. Throwing is what stops the pipeline mid-stream; nothing
   downstream of the stage should keep working on a stream that will not be emitted.
2. `handleProcessingException` and `exec` let that one type through instead of calling
   `outputFatalError`, as they already let `Error` through. It is not an error and must not reach the
   error receiver, or the stream acquires fatal markers and an error stream is written — which is the
   behaviour being removed.
3. `DataProcessorTaskHandler.exec` catches it where it catches the rest, and its `finally` gains a third
   outcome beside `COMPLETE` and `FAILED`.

**Whatever has been written is discarded when it is thrown.** A serving run writes its output as it
goes (§3, *Streams of any size*), so by the time a record falls short an output target may be open and
part-written. It is locked, so nothing has seen it; the deferred path logically deletes it. With
nothing logged to the error receiver there is no error stream either. A scenario holds the deferred
path to leaving nothing visible (§8) — including the empty stream a destination that writes a header
on `startStream` would otherwise leave — rather than this being taken on trust.

### 5.2 Deferred is a status, not a predicate on every query

The obvious shape is a `next_attempt_ms` column that every query filtering `status = CREATED` must also
honour. Reading those queries, that is the wrong trade. `ProcessorTaskDaoImpl.getTaskAvailability` —
what tells a worker which of its filters have work — is
`SELECT filter_id, MIN(id) … WHERE filter_id IN (…) AND status = CREATED GROUP BY filter_id`, one index
descent per filter by design, and the claim is `… AND status = CREATED ORDER BY id LIMIT n FOR UPDATE
SKIP LOCKED`. Adding a time predicate to both puts a range condition where the index expects equality,
and leaves a filter whose every task is asleep looking available on every fetch.

Instead, a deferred task **leaves the `CREATED` pool**:

- a new `TaskStatus.DEFERRED` with primitive value **30** — no status is compared by order, so the
  number is only an identity, and 30 is above every value this enum has ever carried (0, 1, 2, 3, 10,
  22, 99 across its whole history) rather than in the gaps at 4–9 and 11–21, which were left on purpose
  and which a row written by an older release might still hold — and **no new column**: the time a task was deferred is its `status_time_ms`, and the
  minimum time before retry is a configured delay, as it is for the queue sweep;
- a sweep that is `sweepQueuedTasks` with one status changed —
  `UPDATE processor_task SET status = CREATED, … WHERE status = DEFERRED AND status_time_ms < :before` —
  served by the existing index `processor_task_status_time_ms_status_idx`;
- **queueing and claiming are untouched.** The master queue, `getTaskAvailability`, the claimer,
  `EligibleFilters`, the fetch backoffs: none of them change, because a sleeping task is simply not
  `CREATED`. The only new work in the hot path is none.

The cost is that a retry time is honoured at sweep granularity rather than exactly, and is the same for
every task rather than chosen by the deferrer; with the version stamp making a premature retry one
lookup, and the wake making a promotion prompt, neither matters. One configured delay is the ruling (A59); a per-task time is a column to add later if something needs one.

**What the existing statements do with the new status**, read rather than assumed: the dead-task reap
takes `PROCESSING` tasks with a stale status time, so a deferred task is not reaped;
`ACTIVE_TASKS_STATUS_CONDITION` is `QUEUED, ASSIGNED, PROCESSING`, so it is not counted as running;
the logical deletes take everything not `DELETED`, `COMPLETE` or `FAILED`, so deleting a filter takes
its deferred tasks with it. The one that needs a decision is task creation, which bounds itself on
`countTasksForFilter(filter, CREATED)`: deferred tasks are not counted, so creation does **not** stall
behind a session. That is the right default — a filter over several feeds keeps creating for the feeds
that are fine — and it means the deferred population is bounded by what arrives, not by a budget.

### 5.3 A wake, and visibility

**The wake** is the same statement with a narrower `WHERE`: the AI, on promotion, moves its own
deferred tasks back to `CREATED` for the filter and meta ids it knows are waiting on that shape, rather
than letting them sleep out their retry time. Ordinary FIFO by task id then processes them oldest first.

**Visibility**: deferred counts per filter in the processing screens and in sysinfo, and a maximum
deferral age past which a task is **raised, not failed** (A57): flagged in the Supervisor and counted,
and still waiting. A slow model or a paused session must not turn into data somebody has to reprocess
by hand; giving a shape up is a person's decision. So a feed stuck behind a session that never finishes cannot hold data *silently*, and this is what
A24's error mode becomes. The *reason* a task waits is the deferrer's to hold — here, the deferral
row — since the task row gains no column.

Task *creation* is left exactly as it is. `ProcessorTaskCreatorImpl.createTasksFromCriteria` scanning
`tracker.getMinMetaId()` to `getMaxMetaId(...)` is the right shape, and `getMaxMetaId` already knows how
to wait without failing anything — feed dependencies hold its window back and the progress monitor
reports `WAIT_FOR_READY_STREAMS`. Nothing here needs a second task table, a second creator or a second
notion of a tracker.

**Worker claiming stops being experimental.** This flow is a per-node pull, so `ProcessorTaskClaimer`
and `stroom.processor.claimTasksOnWorker` move from an off-by-default experiment to the mode AI
pipelines require. The master queue's cluster-wide guess — `getMaxConcurrentTasks()` sweeping every
enabled node — is the wrong instrument for a document whose work is mostly deferred.

---

## 6. What the stage gains, and what it loses

**Gains.** Per-record scores out of the scorers at serve time: the scorers that are inherently per
record — schema conformance, extraction quality and business rules, which are the three that exist;
`ERROR_LOAD` and `EVENT_CLASSIFICATION` are scorer types with settings and no scorer — reporting the
record's own outcome beside the mean, so the bar is per record and the evidence is a record rather than
a stream. A deferral table — `(doc_uuid, meta_id, filter_id, task_id, feed, type, failed_against, reason,
first_deferred_ms, attempts)`, the filter and task read from `StreamProcessorHolder` — which is what the
wake addresses and what the Supervisor shows as a backlog. It is the only thing a waiting stream writes.

**What it does not need.** A state machine per shape. The version stamp removes the reason for one: the
lease is the attempt row, which already exists and already means "a session is open", and everything
else a task needs is in the deferral row it reads first. Today's implicit state — `reasonGivenUp`,
`relearnReason`, `draftAwaiting`, the rolling score — is left exactly as it is.

**Loses.** The sentinel as a lock, and with it most of the ledger's traffic. `PipelineReprocessing` as a
release mechanism — it survives for deliberate remediation, which is what A12 always meant. Error mode
counting sentinelled streams, since nothing is sentinelled to make it wait. The rolling score and
`relearnThreshold` as a trigger: they become what A46 wanted them for, the Supervisor's "could this rule
be better?" list.

Execution mode goes from the document and the Settings tab (A60), as `sampleRedaction` did and for the
same reason: once learning never runs on a processing thread, the setting would offer a choice that
changes nothing. Learning in stepping mode is its own discussion and gets its own design.

---

## 7. The plan, in phases

As design 03 has it: a phase ends on a test or a run, not a judgement, and the next does not start until
it has. Phases 1, 2 and 3 do not depend on one another and may be built in any order or together; phase
4 needs all three. Nothing changes for a stream until phase 4.

### Phase 1 — A task may be deferred *(`stroom-processor`, `stroom-core`; nothing about AI)*

- `TaskStatus.DEFERRED`; `ProcessorTaskDao` gains *defer* (to `DEFERRED`, node cleared, status time
  now), *sweep* (the `sweepQueuedTasks` shape) and *wake* (to `CREATED` for a filter and a set of meta
  ids); the sweep scheduled beside the queue sweep; `ProcessorConfig` gains the retry delay.
- The exception in `stroom-processor-api`; `AbstractProcessorTaskExecutor.handleProcessingException`
  and `exec` let it through; `DataProcessorTaskHandler` catches it and its `finally` writes the third
  outcome, deregistering the heartbeat as it does for the other two.
- The status shown wherever task statuses are listed, and counted per filter in sysinfo.
- **Exit:** processor tests in both master-queue and worker-claim mode — a task whose element throws
  the exception ends `DEFERRED`, leaves no output stream, no error stream and no markers, is not
  reaped, returns to `CREATED` after the delay or on a wake, and then completes. A filter deleted with
  deferred tasks takes them with it.

This is the part that needs review outside the feature, and the only part that touches a table every
installation has. It adds a status value and no column, so its migration is nothing.

*Built 2026-10-05.* `TaskStatus.DEFERRED` (30); `DeferTaskException` in `stroom-processor-api` with an
`unwrap` that finds a deferral under the `ProcessException` and `XPathException` the pipeline wraps
things in; `sweepDeferredTasks` and `wakeDeferredTasks` on the DAO and its mock; the sweep in
`ProcessorTaskReaper` in both modes, on `stroom.processor.deferredTaskRetryDelay` (5m by default);
the third outcome in `DataProcessorTaskHandler`. The path out of a pipeline turned out to run through
**five** places that swallow exceptions, not the two §5.1 counted: `AbstractProcessorTaskExecutor` has
three (`exec`, `process`, and the stream loop's `handleProcessingException`), `AbstractParser`'s
catch walks a cause chain and logs a fatal error, and both `MultiWayProcessor`s — one in
`ProcessorFactoryImpl`, one in `SimpleProcessorFactory` — run each branch of a pipeline on another
thread and report its exception as a fatal error without rethrowing anything, so a deferral thrown on
a branch is now kept and thrown by the calling thread once every branch has finished.
`TestDeferredTask` in `stroom-app` is the exit: a parser that defers each stream once, in both modes,
with no child stream of any kind after the deferral and one output stream after the retry, by the sweep
and by a wake. (One thing it taught about the test rather than the flow: `XMLWriter` writes a record
when an element closes at depth one, so a document with only a root writes nothing.) The DAO tests
hold the sweep, the wake, and what the reap and the deletes do with the status; the reaper test holds
the delay in both modes. Sysinfo's claim view counts the deferred population across the cluster, with a note saying what returns it.

*Its own audit found four, 2026-10-05.* The one that mattered: the executor deletes a stream's earlier
output from the same pipeline whenever a task ends, so a **reprocess that deferred would have deleted
the output it was going to supersede and replaced it with nothing** — the deferred path now leaves
that deletion to the run that completes, and a scenario holds it. The others: the handler recognised
only a bare deferral and not one wrapped by an executor other than the pipeline's; a deferral thrown
while a parser was starting, rather than parsing, was still a fatal error; and the mock DAO compared a
boxed id with `==`. One limit recorded rather than fixed, because phase 4 owns it: a deferral
guarantees that the branch which deferred wrote nothing, and a pipeline whose *other* branches write —
a raw writer beside a parser — is not yet held to leaving nothing; the discard of §5.1 is where that
is paid.

### Phase 2 — The score is per record *(the feature; no behaviour change)*

- `SchemaConformanceScorer`, `ExtractionQualityScorer` and `BusinessRulesScorer` report each record's
  outcome beside the mean they report now; `Judged` carries them against the spans it already has.
- The supervisor element stops reading the whole stream into memory: input is streamed and a serving
  run is scored record by record as its output is produced, stopping at the first record short of the
  bar. The whole-output `EventList` goes with it.
- The deferral table, written in **shadow**: `Stage.serve` records which streams
  it *would* defer — against the fragment as it is named today, snapshots not existing until phase 4 —
  and goes on emitting as it does today.
- **Exit:** a scenario in which a stream of known and unknown record types yields exactly the failing
  records, by span, with the gate that failed each; and a shadow run over the six formats of design 03
  §3 whose would-defer rows are read and agreed before phase 4 turns them into behaviour.

### Phase 3 — The corpus is durable and holds output *(the feature; design 01 §12 item 9)*

- `RegressionSet.Accepted` becomes a record's input, the output it was accepted with, whether that was
  a translation or a marked Unknown, its score, the attempt that accepted it and its targets; entries
  per record, not per stream.
- The durable form: A18's stream per rule with a row per entry, classification and retention inherited
  from the source feed and capped by it. Kept per A56: only the records a session learned for, with
  their accepted output, within `regressionCap` (A63).
- The promotion gate holds a candidate to every entry: no worse, and never from a translation to an
  Unknown (A66).
- **Exit:** scenario 10 of §8 in Tier 2, with the corpus surviving a restart between the session that
  filled it and the one it refuses, and the entry named in the feedback.

### Phase 4 — Streams wait instead of failing *(the feature, on phases 1 to 3)*

- A `Deferred` decision. `Stage.serve` defers where a record falls short of the bar instead of marking
  the shape and emitting; the unknown-shape path defers instead of sentinelling; a deferral row whose
  stamp matches the transform in force defers without running anything.
- `Supervision.supervise` throws for a `Deferred` decision where it logs an error today, after
  logically deleting the output target the run had opened.
- A session edits the rule's fragment and stylesheet in place rather than writing new ones (A58), and
  each promotion snapshots the configuration (A61): the rule points at the snapshot, a deferral row is
  stamped with it, `Outputs` records it, and `asProcessed` runs it.
- A dead node's session is resumed, not restarted: the worker takes `IN_PROGRESS` attempts whose lease
  has lapsed and re-walks them, and a running session renews its lease turn by turn (§4).
- Learning leaves the task: the attempt is opened and parked, and `DeferredWorker` carries a taken
  attempt to its end rather than one step (A64) — iterating, under A50, until the held stream clears
  the bar. A promotion calls the wake for the deferral rows of that shape.
- Parking for a person (A65): an attempt status the worker leaves alone, a lease that does not lapse,
  and the Supervisor's actions on it — answer, give more to spend. It is here and not later because it
  is the safe ending for every session that cannot finish: until phase 5 gives the plan an exhaustion
  step, a transform whose steps are spent parks too, rather than being abandoned and erroring its
  streams as it would today.
- **Exit:** steps 1 to 3 of test A (§8.1), and test B over the known types; scenarios 1, 2, 3, 6, 7, 9,
  11, 12, 13, 15 and 16 of §8 in both tiers, and the live run of design 02 §6.3
  repeated over a feed whose later streams carry record types its first did not.

### Phase 5 — A session always ends *(the feature)*

- The exhaustion step: a plan step, reached only when the others are spent, that asks for a match on
  the record type whose output is the admitted form; the built-in text for it; the marker — a named `Data` item inside `Unknown` (A62) —
  defined once, in shared code, and checked to validate against the `EVENTS` schema group, so that the text that asks for it and the scorer that accepts it cannot drift; the
  quality scorer accepting a marked Unknown and no other.
- The Supervisor's third action on a parked session — send it to the exhaustion step — beside the
  two phase 4 gave it.
- The parse that cannot be learned: the session gives the shape up, and the streams deferred on it
  error with the reason. It is unlearnable when the parser step is spent short of the document's
  input-coverage and yield gates (A54).
- **Exit:** the whole of test A and test B with the unseen and untranslatable types arriving
  mid-run (§8.1); scenarios 4, 5 and 14 of §8, and a scripted run in which a model that asks for the admitted
  form before the exhaustion step is refused it.

### Phase 6 — What an operator sees, and what is taken out

- The Supervisor's backlog from the deferral table: what waits, on which transform and since when. The maximum deferral age (scenario 8), past which a stream is
  raised and goes on waiting (A57); A24's error mode restated as that flag.
- Removed: the sentinel as a lock and the ledger rows it wrote; `PipelineReprocessing` as a release;
  `ExecutionMode` from the document, its tab and the attempt row (A60); the rolling score as a trigger. Kept: the ledger and reprocessing for
  deliberate remediation, the rolling score for A46's list.
- Worker claiming required for a filter over a supervised pipeline, and said so where a filter is made.
- **Exit:** a person takes a feed from unseen to served from the UI, watching the backlog form and
  drain, with no error stream written at any point; and the full suite with the removed code gone.

## 8. The scenarios this has to pass

Numbered into design 02 §5 when the work is cut into slices.

1. **A stream with an unseen event type is held, learned and served in full.** One stream, one record
   type the transform has never met: nothing is emitted, a session opens, and after promotion the same
   stream is processed and every record scores. Tier 1 for the decision, Tier 2 as a processor task.
2. **A stream of the same feed and type whose records all score flows while the session runs.** No gate
   on the shape, and no dependency on processing order.
3. **A deferred stream is not re-scored until the version moves.** The transform runs once at failure
   and once per promotion, and the test counts the runs.
4. **An exhausted session bakes an Unknown match and the held stream then passes.** The transform
   carries a match for that record type; the output validates; the stream is served.
5. **A stream whose records cannot be parsed errors, and says so.** The only terminal failure.
6. **A deferred stream leaves nothing behind.** No output stream, no error stream, no fatal markers on
   the input's meta, and the record counts unchanged — the check that the exception travelled as a
   deferral and not as a failure.
7. **Nothing is sentinelled to make it wait.** A regression test over the whole learning path: no error
   stream is written for a stream that is merely early.
8. **A deferral older than the maximum is raised and still waits.** It shows in the Supervisor and in
   the counts, nothing errors, and giving the shape up is what errors it.
9. **A promotion wakes the backlog in order.** The deferred streams of that feed and type are processed
   after it, oldest first.
10. **The corpus holds a candidate to no worse.** A later session that would turn a translated record
   into an Unknown, or leave any entry short of the bar, is refused; one that turns a marked Unknown
   into a real translation is accepted.
11. **A stream is reprocessed as it was processed after its transform has moved on.** The output names a
   snapshot, the fragment has since been improved in place, and the reprocess produces what it produced
   before.
12. **A session out of budget parks and holds.** Its streams stay deferred, no other task takes the
   shape or spends on it, nothing is baked as Unknown, and a person's answer carries it on.
13. **An attempt that lapses or fails does not strand its streams.** The next retry opens another.
16. **A stream larger than memory is scored, deferred and served without being held.** Input streamed,
   output written as made; the run that defers leaves nothing visible and the run that serves reads the
   stream once; memory stays flat throughout.
15. **A session whose node dies is carried on by another.** The attempt is left in progress with its
   lease lapsed; a worker under another node name takes it, re-walks the turns already recorded without
   asking the model for them again, finishes, and the streams are released. No second attempt is opened.
14. **A marked Unknown is not retried.** Later streams carrying that record type are served, and no
   session opens for it until a person asks.

### 8.1 The two tests that say the whole thing works

The scenarios above each hold one decision. These two hold the flow, end to end, in `stroom-app` against
a real database, with processor tasks created by a real filter and the scripted model of design 02 §3
in place of a live one. They are the owner's (2026-10-05), and no phase from 4 onward is finished
without the part of them it makes possible.

**Fixtures.** `windows-security.xml` already carries three event types — 4624, 4634 and 4688, five
records of each — with its stylesheet and golden events. It is cut into streams so that the types
arrive in the order the test needs, and gains one record type that cannot be translated: an event whose
payload gives the model nothing to classify, for the Unknown leg.

**Test A — one feed, from nothing to served, twice over.**

1. Two streams of 4624 and 4634 arrive on a feed nothing has been learned for. Both tasks end
   `DEFERRED`; one session is open, not two; no output stream and no error stream exists for either.
2. The worker carries the session. The scripted model answers; a pipeline is written for the feed and
   type and promoted; the corpus holds the records that held the stream.
3. Both tasks are woken, run again and complete. Each stream's output is its golden events, and carries
   the bindings that say what produced it.
4. A third stream arrives carrying 4688 and the untranslatable type beside the types already known. Its
   task defers — nothing is emitted for the records that would have translated either — and a second
   session opens on that stream. No record of which of its records failed is stored.
5. The model translates 4688 and, at the exhaustion step, gives a marked Unknown for the other. The
   *same* pipeline is improved in place and a new snapshot promoted; the candidate was held to the
   corpus and the 4624 and 4634 entries are no worse.
6. The third task wakes and completes: 4688 as its golden, the untranslatable records as marked
   Unknown events, the rest as before. A fourth stream of all four types is served at once with no
   session opened.

**Test B — many streams, many threads.** The same feed and fixtures, with enough streams arriving at
once that the race is real, processed by many task threads on one node in worker-claim mode. It does
not run more than one node (the owner, 2026-10-05): what has to be safe across nodes is the same set of
statements that has to be safe across threads — the task claim, the attempt lease, the deferral rows,
the wake — and they are contended here through the one database exactly as a second node would contend
for them.

It asserts, after the backlog has drained:

- **one session per learning event** — the number of attempts opened equals the number of times the
  transform actually had to change, however many tasks met the gap at once;
- **every stream processed exactly once** — one completed task and one output stream per input, none
  missing and none twice;
- **no partial output** — every output stream holds a whole stream's events, equal to its golden; none
  exists for a task that ended deferred, at any point the test samples;
- **no error stream that should not exist** — none at all in this test, since nothing in it is
  unparseable; and no fatal or error markers on any input's meta;
- **no task left behind** — nothing `DEFERRED`, `PROCESSING` or `CREATED` at the end, no attempt still
  open, no deferral row unresolved.

The invariants are checked *while* it runs as well as at the end, by sampling: a partial output that is
later completed, or an error stream that is later deleted, is a failure the final state would hide.

---

## 9. Open

- **The routing table.** With one pipeline per feed and type it could collapse to a lookup with
  operator-authored overrides. Not now, by the owner's decision; recorded so the option is not lost.
- **Learning in stepping mode**, which the owner has said is a different discussion.
- **How long a snapshot is kept** (A61). It must outlive every output that names it, so it follows the
  retention of the feed's processed data; the rule for that is not written.
- **That a marked Unknown validates.** A62 puts the marker in a `Data` item inside `Unknown`; the event
  schema is downloaded content and has not been checked for it. Phase 5 checks it first.

---

## 10. Rulings this document records

The owner's, 2026-10-04, proposed for design 01 §13:

**A48.** *Execution of an AI pipeline is a pull with deferral, not a push with errors. A task that cannot
be completed now returns to `CREATED` with a minimum time before it is retried; nothing is errored in
order to make it wait. Learning never runs on a processing thread.*

**A49.** *A shape, for learning, is a feed and a type. Event types within a stream are XSLT match
treatment and need no mechanism outside the transform to identify them.*

**A50.** *A stream with even one record that cannot be translated is held until it scores in full, and
the session learns every event in it. The held stream is the session's acceptance test.*

**A51.** *Unknown is the product of an exhausted session: a learned match for a record type the model
could do nothing better with, baked into the transform. It is not a fallback for anything unmatched, and
the feature does not count them — statistics over converted streams are ordinary downstream processing.*

**A52.** *A translation always terminates: what cannot be translated becomes Unknown and is served. A
parse that cannot be learned is the one real error, because there is nothing to score and nothing to
serve.*

**A53.** *A learned Unknown is marked: the exhaustion step asks for an Unknown event carrying a defined
marker, and the quality scorer accepts a marked Unknown where it refuses a bare one.*

**A54.** *A parse is unlearnable when the session's parser candidates are spent without meeting the
document's input-coverage and yield gates. No new setting decides it.*

**A55.** *The bar a served record must clear is the gates that apply per record, and nothing else.*

**A56.** *The regression corpus is per record and holds only the records a session had to learn for,
each with the output it was accepted with — never a whole stream and never a record that already
translated.* (Restated 2026-10-05: the sample of passing records was dropped.)

**A57.** *A stream deferred past the maximum age is raised and goes on waiting. Nothing errors because a
session is slow; giving a shape up is a person's decision. This is what error mode (A24) becomes.*

**A58.** *A session improves a transform in place: one fragment and one stylesheet per feed and type,
growing matches as it learns.*

**A59.** *The minimum time before a deferred task is retried is one configured delay, and a promotion
wakes its tasks early. `processor_task` gains no column.*

**A60.** *Execution mode is removed from the document: learning never runs in a processing task, and a
setting that offered the choice would change nothing.*

**A61.** *Every promotion keeps an immutable snapshot of the fragment's configuration, keyed by version;
`Outputs` records the version, and an as-processed reprocess runs the snapshot.*

**A62.** *The marker of a learned Unknown is a named `Data` item inside `EventDetail/Unknown`, which the
quality scorer leaves out of its count of untyped elements.*

**A63.** *The document's regression cap bounds a rule's corpus.*

**A64.** *A node that takes an attempt carries the session to its end in one pass — until the held
stream passes, the budgets are spent, or it parks for a person.*

**A65.** *A session whose budgets run out before its held stream passes parks for a person. It keeps its
lease and its streams go on waiting; nothing is baked as Unknown because a budget ended. This qualifies
A52: a translation terminates when its plan is carried through, and waits when its budget is not
enough to carry it.*

**A66.** *The corpus holds a candidate to no worse: every entry's new output clears the bar and a record
once translated never becomes Unknown. Output may otherwise change, and a marked Unknown may become a
real translation without anybody agreeing.*

**A67.** *Nothing new bounds a shape that keeps failing to release its streams: the cluster-wide spend
limit (A44) stops a document asking, and the deferral age raises what has waited too long (A57).*

**A68.** *A marked Unknown is revisited only when a person asks. It clears the bar, and nothing retries
it on its own.*

**A69.** *A session learns from one stream, and every waiting stream is tried again when it promotes.
A waiting stream records that it waits and against which transform, and nothing about which of its
records failed: the session works that out for its own stream, and for the rest it would be stale.*
