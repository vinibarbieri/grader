# Step 9 — JobService + Full Wiring

## What was done

Replaced the stub `JobServiceImpl` with a full working implementation that wires all
previously-built components (SafeZipExtractor, ExecutionEngine, CsvAggregationService,
FilesystemJobRepository) into a complete grading lifecycle.

---

## Implementation decisions

### JobServiceImpl is a plain class — no `@Service`

`JobServiceImpl` is no longer annotated with `@Service`. Instead it is returned as a
`@Bean` from `AppConfig`. This gives full control over its constructor parameters (in
particular the `Path workspaceRoot` and the injectable `Executor`) and makes unit-testing
trivial: tests pass `Runnable::run` as a direct executor so that async evaluation runs
synchronously without sleep/wait.

### Cooperative cancellation with AtomicBoolean

A `ConcurrentHashMap<String, AtomicBoolean>` holds one cancel flag per active job. The
evaluation thread checks the flag:
- Between each student directory
- Between each `.c` file inside a student
- Between each test case

`cancelJob()` sets the flag and calls `job.requestCancel()` which transitions the job to
`CANCELLING` (for RUNNING jobs) or `CANCELLED` (for QUEUED jobs). The evaluation thread
then drives the final `CANCELLING → CANCELLED` transition.

### Workspace layout

```
<workspaceRoot>/<jobId>/
  upload.zip          ← saved for audit
  submissions/
    <studentDir>/
      problem1.c
  tests/
    problem1/
      case01.in  case01.out
  results.csv         ← written during evaluation (one row per case)
  results.json        ← written after aggregation
```

### CSV format

Nine RFC 4180 double-quoted columns:
```
student_dir,c_file,problem,case,status,details,expected_output,program_output,duration_ms
```
Multi-line content in `expected_output`/`program_output` uses literal `\n` sequences (same
convention as the shell script's `file_to_csv_text`). `CsvAggregationService.unescapeOutput`
decodes them back. The ninth column `duration_ms` extends the original shell CSV format.

### Problem detection

Replicates the shell script's `detect_problem()` heuristic using four compiled `Pattern`s:
1. Keyword: `problem1`, `coding_1`, `quiz1problem1`, `onlinequiz.c`
2. Keyword: `problem2`, `coding_2`, `quiz1problem2`, `onlinequiz2.c`
3. Fallback: standalone digit `1` via `(^|[^0-9])1([^0-9]|$)`
4. Fallback: standalone digit `2` via `(^|[^0-9])2([^0-9]|$)`

Files that match none → `SKIP` row in CSV; no compilation attempted.

### ArtifactNotFoundException

A new nested exception class `JobServiceImpl.ArtifactNotFoundException` (extends
`RuntimeException`) is thrown when a CSV/JSON artifact doesn't exist yet.
`GlobalExceptionHandler` maps it to HTTP 404 `ARTIFACT_NOT_FOUND`.

### Executor injection

`AppConfig` now declares an `ExecutorService evaluationExecutor` bean (fixed thread pool of
`grader.worker-pool-size`). `JobService` accepts `Executor` (the supertype) so tests can
inject `Runnable::run` for synchronous deterministic execution.

### One active grading job at a time

Although the executor is a pool, `JobServiceImpl` now enforces a single active evaluation
globally with an internal atomic lock (`activeJobId`). If a second queued job tries to start
while another job is active, the service throws `JobBusyException` (mapped to HTTP 409),
keeping behavior aligned with the MVP requirement "one active grading job at a time".

### Error handling while cancelling

If an unexpected exception happens during evaluation while the job is already in
`CANCELLING`, the service now finalizes to `CANCELLED` instead of attempting an invalid
`CANCELLING -> ERROR` transition. This prevents jobs from being stuck in non-terminal state.

---

## Files modified

| File | Change |
|---|---|
| `service/JobServiceImpl.java` | Full rewrite — complete evaluation lifecycle |
| `config/AppConfig.java` | Added `CsvAggregationService`, `ExecutorService`, `JobService` beans |
| `controller/GlobalExceptionHandler.java` | Added `ArtifactNotFoundException` handler (HTTP 404) |

## Files created

| File | Purpose |
|---|---|
| `test/.../service/JobServiceImplTest.java` | 25 tests: unit (mocked) + integration (real filesystem) |
| `service/JobBusyException.java` | Signals that another job is already active (single-job gate) |

---

## Test outcomes

```
Tests run: 125, Failures: 0, Errors: 0, Skipped: 24
BUILD SUCCESS
```

The 24 skipped tests are pre-existing platform-dependent integration tests for
`ProcessGroupLauncher` and `ExecutionEngine` (require a Unix process group environment;
skipped on macOS CI paths).

### New tests in `JobServiceImplTest` (25 tests)

| Test | What it verifies |
|---|---|
| `createJob_savesJobAsQueued_andExtractsZip` | ZIP is written and extractor is called |
| `createJob_throwsRuntimeException_whenExtractionFails` | IOException propagates |
| `evaluateJob_transitionsToRunning_whenQueued` | QUEUED → RUNNING on evaluate |
| `evaluateJob_isIdempotent_whenAlreadyRunning` | Second evaluate call returns RUNNING, no save |
| `evaluateJob_throwsJobNotFoundException_whenJobMissing` | 404 path |
| `evaluateJob_throwsJobInvalidStateException_whenJobIsDone` | Invalid transition rejected |
| `getJob_throwsJobNotFoundException_whenMissing` | 404 path |
| `getResults_returnsEmptyList_whenResultsJsonDoesNotExist` | No file → empty list |
| `getResults_returnsResults_whenResultsJsonExists` | Deserializes results.json correctly |
| `downloadArtifact_returnsCsvBytes_whenFileExists` | Reads results.csv bytes |
| `downloadArtifact_returnsJsonBytes_whenFileExists` | Reads results.json bytes |
| `downloadArtifact_throwsRuntimeException_whenFileNotFound` | ArtifactNotFoundException |
| `cancelJob_transitionsToCancelled_whenJobIsQueued` | QUEUED → CANCELLED directly |
| `cancelJob_transitionsToCancelling_whenJobIsRunning` | RUNNING → CANCELLING |
| `cancelJob_isIdempotent_whenJobIsDone` | No change, no save |
| `cancelJob_isIdempotent_whenJobIsCancelled` | No change, no save |
| `cancelJob_isIdempotent_whenJobIsCancelling` | No change, no save |
| `cancelJob_throwsJobNotFoundException_whenJobMissing` | 404 path |
| `evaluate_fullFlow_writesCSVAndResultsJson_andTransitionsToDone` | **Integration**: real filesystem, mocked engine → DONE, CSV + results.json generated |
| `evaluate_transitionsToDone_whenSubmissionsDirIsMissing` | No submissions/ → DONE, 0 students |
| `evaluate_queued_cancel_preventsEvaluation` | Cancel before evaluate → throws on evaluate |
| `evaluate_skipsUnknownProblemFile_writesSkipRow` | SKIP row for undetected problem |
| `evaluate_writesCompileErrorRow_whenCompileFails` | COMPILE_ERROR row, run never called |
| `evaluateJob_throwsJobBusyException_whenAnotherJobIsActive` | Enforces single active evaluation globally |
| `tryTransitionToError_transitionsCancellingJobToCancelled` | Prevents invalid CANCELLING → ERROR path |
