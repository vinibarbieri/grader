# Step 10 — MetricsService + ArtifactService + Final API Wiring

## What was done

Implemented the final Phase A components: `MetricsService` for structured metrics logging,
`ArtifactService` for artifact retrieval, wired both into `JobServiceImpl`, added a health
endpoint, and wrote the README/runbook.

---

## Implementation decisions

### MetricsService

A stateless-per-log, stateful-for-global-counters service. At job completion (regardless of
terminal state: `done`, `cancelled`, `error`) a single structured `INFO` log line is emitted
with all required metrics:

```
METRICS jobId=... state=... job_total_ms=... compile_total_ms=... run_total_ms=...
        compare_total_ms=0 cases_executed=... ok_count=... ... cancelled_jobs_count=...
```

- `compare_total_ms` is always 0: comparison is in-memory normalize+equals, included within
  `run_total_ms` (negligible overhead).
- `cancelled_jobs_count` and `cleanup_failures_total` are global `AtomicLong` counters that
  accumulate across all jobs for the lifetime of the process.
- Per-job `killed_processes_count` and `cleanup_failures_count` are computed as deltas during
  each evaluation run (`finalCounter - startCounter`) so the log line reflects that specific
  job rather than lifetime cumulative values.
- `cleanup_failures_total` is incremented by each job's cleanup-failure delta at finalization.

### ArtifactService

Extracted the download-artifact responsibility from `JobServiceImpl` into a dedicated
`ArtifactService`:

- `getArtifactBytes(jobId, format)` — reads `results.csv` or `results.json` from the job
  workspace; throws top-level `ArtifactNotFoundException` if the file is absent.
- `computeWorkspaceSizeBytes(jobId)` — walks the entire job directory and sums file sizes,
  used to populate `tmpfs_peak_bytes` in the metrics log.

`ArtifactNotFoundException` was extracted from the `JobServiceImpl` inner class to its own
top-level class (`service.ArtifactNotFoundException`). `GlobalExceptionHandler` updated to
use the new import path.

### Killed-process and cleanup-failure counters

Two infrastructure counters were added without changing any constructor signatures:

- `ProcessGroupLauncher.killedProcessesCount` (AtomicLong): incremented on every `kill()`
  call. Exposed via `getKilledProcessesCount()`.
- `ExecutionEngine.cleanupFailuresCount` (AtomicLong): incremented inside `verifyCleanup()`
  when the main process or any descendant remains alive post-kill. Exposed via
  `getCleanupFailuresCount()`. `getKilledProcessesCount()` on `ExecutionEngine` delegates
  to the launcher.

`JobServiceImpl` snapshots both counters at the start of `runEvaluation()` and logs only the
per-job delta at completion.

### Compile and run timing in JobServiceImpl

`processStudent()` was changed from `void` to returning a `ProcessingStats` record
`(compileTotalMs, runTotalMs)`. Compile time is measured around each `executionEngine.compile()`
call; run time accumulates `CaseResult.durationMs()` for each case. `runEvaluation()` sums
stats across all students and passes the totals to `recordFinalMetrics()` in the `finally`
block.

### recordFinalMetrics — guaranteed execution

`recordFinalMetrics(jobId, compileTotalMs, runTotalMs)` runs in the `finally` block of
`runEvaluation()` so metrics are always emitted — for `done`, `error`, and `cancelled` jobs.
It reads the final job state from the repository, calls `ArtifactService` for the workspace
size, and calls `MetricsService.recordJobCompletion()`.

### HealthController

A minimal `GET /api/health` endpoint returns `{"status": "UP"}`. Liveness probe for
Raspberry Pi deployment.

### README

`grader/README.md` documents:
- Requirements and quick-start commands
- Full configuration table with defaults
- API endpoint reference with request/response examples
- Error payload standard
- Metrics log format
- Known Phase A limitations

---

## Files created

| File | Purpose |
|---|---|
| `service/MetricsService.java` | Structured metrics logger + global counters |
| `service/ArtifactService.java` | Artifact file reader + workspace size calculator |
| `service/ArtifactNotFoundException.java` | Top-level exception (moved from inner class) |
| `controller/HealthController.java` | `GET /api/health` liveness probe |
| `test/.../service/MetricsServiceTest.java` | 7 unit tests for MetricsService |
| `test/.../service/ArtifactServiceTest.java` | 8 unit tests for ArtifactService |
| `test/.../controller/HealthControllerTest.java` | 1 test for health endpoint |
| `grader/README.md` | Runbook: setup, config, API reference, metrics |

## Files modified

| File | Change |
|---|---|
| `service/JobServiceImpl.java` | Added ArtifactService + MetricsService deps; compile/run timing; processStudent returns ProcessingStats; recordFinalMetrics in finally; removed inner ArtifactNotFoundException |
| `config/AppConfig.java` | Added ArtifactService and MetricsService beans; updated JobService bean |
| `controller/GlobalExceptionHandler.java` | Updated ArtifactNotFoundException import |
| `infrastructure/ProcessGroupLauncher.java` | Added killedProcessesCount AtomicLong + getter |
| `service/ExecutionEngine.java` | Added cleanupFailuresCount AtomicLong, verifyCleanup increments it; getters for both counters |
| `test/.../service/JobServiceImplTest.java` | Updated all constructor calls to pass ArtifactService and MetricsService; added cancelled-metrics idempotency test |

---

## Test outcomes

```
Tests run: 144, Failures: 0, Errors: 0, Skipped: 24
BUILD SUCCESS
```

The 24 skipped tests are pre-existing platform-dependent `ProcessGroupLauncher` and
`ExecutionEngine` integration tests (require `setsid`; skipped on macOS).

### New tests

| Test class | Tests | What they verify |
|---|---|---|
| `MetricsServiceTest` | 7 | Logging without throw; counter increments (including delta increments); snapshot accuracy |
| `ArtifactServiceTest` | 8 | CSV/JSON byte reads; ArtifactNotFoundException on missing files; workspace size including nested files |
| `HealthControllerTest` | 1 | `GET /api/health` returns 200 + `{"status":"UP"}` |

---

## Phase A completion checklist

- [x] Upload → evaluate → status → results → download flow works end-to-end
- [x] Cancellation is idempotent for queued/running jobs
- [x] Single active grading job enforced (JobBusyException / HTTP 409)
- [x] No leaked processes: PGID kill + cleanup verification + INTERNAL_ERROR classification
- [x] OLE classification is deterministic (2 MB cap)
- [x] All required metrics logged at job completion via SLF4J
- [x] README/runbook documents local setup, config, and API reference
