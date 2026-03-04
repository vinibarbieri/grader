# Step 2 — Domain Model

## What was done

- Wrote `JobStateTest.java` first (22 tests)
- Implemented `JobState.java` enum with `transitionTo()`, `isTerminal()`, `isCancellable()`
- Implemented `CaseStatus.java` (OK, WA, TIMEOUT, RUNTIME_ERROR, COMPILE_ERROR, SKIP, OUTPUT_LIMIT_EXCEEDED, INTERNAL_ERROR)
- Implemented `CompileStatus.java` (OK, COMPILE_ERROR)
- Implemented `Job.java` aggregate (jobId, state, timestamps, progress counters, `recordCaseResult()`)
- Created `domain/model/` records: `CaseResult`, `FileResult`, `StudentResult`

## Key decisions

- `Job.requestCancel()` is idempotent: no-op on terminal/cancelling states; QUEUED skips directly to CANCELLED; RUNNING goes to CANCELLING first
- `Job.recordCaseResult()` updates counters atomically in one call
- Records used for result model (immutable value objects)

## Test results

22 tests, 0 failures — `BUILD SUCCESS`
