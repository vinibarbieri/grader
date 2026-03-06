# Backend PRD - Local Grader Platform

## 1) Document Purpose

Define the backend product requirements for the local grader MVP and its evolution path.
This backend will run on a Raspberry Pi 5 (4GB RAM), execute automated grading workflows for C submissions, and expose APIs consumed by a React frontend.

---

## 2) Product Vision

Provide a reliable local grading backend that:

- accepts Gradescope-style submissions and test bundles,
- executes evaluations safely and with predictable performance,
- returns structured JSON for analytics/filtering in the frontend,
- supports future extensibility (new evaluation templates, source-code viewer, flexible grading rules).

---

## 3) Goals and Non-Goals

### Goals (MVP)

- Implement backend in **Java 25 + Spring Boot + Maven**.
- Follow **OOP boundaries** and **TDD with JUnit 5**.
- Support job-based grading workflow with filesystem persistence (no DB in MVP).
- Handle execution lifecycle: upload, evaluate, status, results, download.
- Optimize for Raspberry Pi constraints with controlled concurrency and resource safety.
- Keep code, API contracts, and documentation in English.

### Non-Goals (MVP)

- Multi-tenant cloud platform.
- Full authentication/authorization system.
- Horizontal distributed execution across multiple hosts.
- General-purpose sandbox with complete syscall filtering.

---

## 4) User and Use Cases

### Primary user

- Instructor/TA grading programming assignments locally.

### Core use cases

1. Upload submissions bundle and tests.
2. Trigger evaluation for one job.
3. Monitor progress.
4. Fetch aggregated results as JSON.
5. Download report artifacts (CSV/JSON).

---

## 5) System Constraints

- Target host: Raspberry Pi 5 with 4GB RAM.
- Host also runs other services (e.g., PostgreSQL, Telegram bot).
- Student code is untrusted and may be unstable/malicious.
- Expected workload: multiple students, ~10 test cases per problem per submission.

---

## 6) Functional Requirements

### FR-01 Job lifecycle

- Backend must create a unique `jobId`.
- Backend must maintain states: `queued`, `running`, `cancelling`, `cancelled`, `done`, `error`.
- Backend must persist job metadata and status in filesystem.
- Backend must support idempotent cancellation for `queued` and `running` jobs.

### FR-02 Upload handling

- Backend must receive a **single ZIP file** per job containing:
  - submissions directory tree,
  - tests directory tree.
- Backend must sanitize/normalize incoming paths to prevent traversal.
- Backend must reject uploads when:
  - any ZIP entry contains `..` segments,
  - any ZIP entry is absolute (starts with `/`),
  - any symlink/hardlink entry is present,
  - canonical resolved path escapes the target `jobId` directory.
- Backend must reconstruct expected working layout for evaluation.

### FR-03 Evaluation execution

- Backend must execute grading flow equivalent to `avaliacao_automatica.sh`.
- Backend must compile C submissions with configurable flags (default `-O1 -Wall -Werror=vla`).
- Backend must execute each test case with strict timeout and capture outputs.
- Backend must enforce deterministic output capture limits:
  - `stdout` max: `2MB`,
  - `stderr` max: `2MB`,
  - exceeding either cap aborts execution and classifies case as `OUTPUT_LIMIT_EXCEEDED`.
- Backend must classify outcomes (`OK`, `WA`, `TIMEOUT`, `RUNTIME_ERROR`, `COMPILE_ERROR`, `SKIP`, `OUTPUT_LIMIT_EXCEEDED`, `INTERNAL_ERROR`).

### FR-04 Process control and cleanup

- Backend must run evaluation using Java `ProcessBuilder`.
- Backend must enforce timeout per case.
- Backend must start each execution inside a dedicated **process group (PGID)**.
- Backend kill strategy must target PGID first, then descendants fallback:
  1. graceful stop signal to process group,
  2. short grace window,
  3. forced kill to process group,
  4. descendant sweep/fallback to ensure no survivors.
- Backend must apply OS-level CPU safety in Phase A using `ulimit -t` (or equivalent shell wrapper) as a second defense layer.
- Backend must guarantee deterministic cleanup after case and after job.

### FR-05 Results aggregation

- Backend must produce aggregated JSON model per student, including per-case details.
- Backend must preserve/generated CSV report for audit/download.
- Backend must expose parsed/aggregated results through API.

### FR-06 Retrieval and download

- Backend must provide status endpoint for polling.
- Backend must provide results endpoint with full aggregated payload.
- Backend must provide download endpoint for CSV/JSON artifacts.
- Backend must provide cancellation endpoint:
  - `POST /api/jobs/{jobId}/cancel`
  - idempotent behavior and terminal state consistency.

### FR-07 Operational visibility

- Backend must expose health endpoint.
- Backend must log job lifecycle and failures with enough diagnostic context.
- Backend must collect basic performance metrics (per phase and per case).

---

## 7) Non-Functional Requirements

### NFR-01 Performance (Phase A baseline)

- Fixed worker pool: `2` execution workers.
- One active grading job at a time in MVP.
- Temporary workspace in `tmpfs` with initial cap `64MB`.
- Keep host responsiveness while coexisting with other services.

### NFR-02 Reliability

- No orphan processes after timeout/failure.
- Job failures must not crash API process.
- Partial failures must be reflected clearly in status/error payloads.

### NFR-03 Security

- Strong path sanitization and isolated workspaces by `jobId`.
- Resource limits (timeout/output caps in Phase A; container limits in Phase B).
- Execution under restricted context (Phase B: rootless Podman isolation).
- ZIP extraction must be safe-by-default (no symlink/hardlink extraction).

### NFR-04 Maintainability

- Layered architecture with clear domain/service/infrastructure boundaries.
- Unit and integration tests with deterministic fixtures.
- Config-driven behavior for limits and execution parameters.

---

## 8) Proposed Backend Architecture

## 8.1 Layers

- **Controller layer**: HTTP request/response and validation.
- **Application/service layer**: job orchestration and use cases.
- **Domain layer**: entities/value objects for jobs, case outcomes, aggregates.
- **Infrastructure layer**: filesystem repository, process executor, parser, metrics.

### 8.2 Core components

- `JobController`
- `JobService`
- `ExecutionEngine`
- `ProcessGroupLauncher` (process-group aware start/kill wrapper)
- `CsvAggregationService`
- `JobRepository` (filesystem)
- `MetricsService`
- `ArtifactService`
- `SafeZipExtractor`

### 8.3 Workspace strategy

- Root folder for jobs, one directory per `jobId`.
- Dedicated `tmpfs` working subdirectory for ephemeral artifacts.
- Persistent artifacts (`status.json`, `results.json`, CSV, logs) stored on disk.

---

## 9) API Contract (MVP)

### `POST /api/jobs`

Creates a new job and uploads required inputs.

- Request: multipart payload with one ZIP file (`application/zip`).
- ZIP expected root layout:
  - `submissions/`
  - `tests/problem1/`
  - `tests/problem2/`
- Response: `{ "jobId": "...", "status": "queued" }`

### `POST /api/jobs/{jobId}/evaluate`

Starts evaluation for the target job.

- Response: `{ "jobId": "...", "status": "running" | "queued" }`

### `GET /api/status/{jobId}`

Returns state/progress summary.

- Response includes:
  - `state`,
  - `startedAt`,
  - `endedAt`,
  - `progress.totalStudents`,
  - `progress.processedStudents`,
  - `progress.currentStudent`,
  - counters and error summary.

### `POST /api/jobs/{jobId}/cancel`

Requests cancellation of a queued/running job.

- Must be idempotent.
- If already terminal (`done`, `error`, `cancelled`), return success with unchanged state.
- Response: `{ "jobId": "...", "status": "cancelling" | "cancelled" | "done" | "error" }`

### `GET /api/results/{jobId}`

Returns aggregated JSON model for frontend filters and details.

### `GET /api/download/{jobId}?format=csv|json`

Returns downloadable artifact.

---

## 10) Result Data Model (Contract)

Each student aggregate should include:

- `studentId`
- `studentName`
- `submissionDir`
- `compileErrorsCount`
- `runtimeErrorsCount`
- `timeoutsCount`
- `waCount`
- `okCount`
- `problem1Score`
- `problem2Score`
- `totalScore`
- `failedAnyQuestion`
- `files[]`:
  - `fileName`
  - `problem`
  - `compileStatus` (`OK` | `COMPILE_ERROR`)
  - `compileDetails`
  - `cases[]`:
    - `case`
    - `status`
    - `details`
    - `expectedOutput`
    - `programOutput`
    - `durationMs`

The backend may additionally expose student-level flattened counters to optimize frontend filters.

---

## 11) Execution Engine Behavior

### Compile phase

- Compile each target C file with configured flags.
- On compile failure, register `COMPILE_ERROR` and continue.

### Run phase

- For each valid test input:
  - execute binary with timeout,
  - capture stdout/stderr with deterministic output cap,
  - classify runtime status.

### Compare phase

- Normalize expected/program outputs (trailing spaces/newline tolerance).
- Compare in memory whenever possible to reduce I/O overhead.
- Register `OK` or `WA`.

### Kill and cleanup policy

- On timeout/error:
  1. graceful termination to PGID,
  2. short grace wait (configurable, default 200ms),
  3. forced kill to PGID,
  4. descendant sweep fallback.
- Verify cleanup completed before next case.
- If output cap is exceeded, terminate execution immediately and classify `OUTPUT_LIMIT_EXCEEDED`.
- If cleanup verification fails, classify as `INTERNAL_ERROR` and mark job as `error` when host safety is uncertain.

---

## 12) TDD and Testing Requirements

### Unit tests (JUnit 5)

- Job state transitions and invalid transitions.
- ExecutionEngine timeout/kill/cascade behavior.
- Parser and aggregation rules with edge-case CSV fixtures.
- Path sanitization and workspace safety.
- Cancellation behavior and idempotency.
- Output cap enforcement and OLE classification.

### Integration tests

- Controller endpoints with `MockMvc`.
- End-to-end local fixture run (small synthetic dataset).

### Test quality gates

- Core service path coverage target (to be tracked in CI commands).
- Mandatory tests for every bug fix touching execution flow.

---

## 13) Observability and Metrics

Collect at minimum:

- `job_total_ms`
- `compile_total_ms`
- `run_total_ms`
- `compare_total_ms`
- `cases_executed`
- `timeouts_count`
- `runtime_errors_count`
- `killed_processes_count`
- `tmpfs_peak_bytes`
- `ole_count`
- `cancelled_jobs_count`
- `cleanup_failures_count`

Logs must include `jobId`, student/file identifiers, and phase markers.

---

## 14) Deployment and Runtime Configuration

### Phase A (MVP)

- Spring Boot service running on Raspberry Pi.
- JVM memory cap configured (example: `-Xms256m -Xmx768m`).
- `tmpfs` mounted with 64MB for ephemeral evaluation files.
- worker pool fixed to 2.
- ZIP upload only.
- Progress model by student/submission (`processedStudents/totalStudents`).
- No result cache in Phase A.
- Shell wrapper applies `ulimit -t` for CPU-time ceiling per execution.

### Phase B (hardening)

- Rootless Podman isolation per job.
- Enforce `cpu`, `memory`, `pids`, timeout, and output limits.
- Deterministic cleanup and host-stability safeguards.

---

## 15) Phased Delivery Roadmap

### Phase A - Performance-first MVP

- Implement core APIs and job lifecycle.
- Replace per-case Python timeout wrapper with Java process control.
- Add process-group kill, deterministic cleanup, and in-memory compare path.
- Deliver end-to-end grading with frontend integration support.

### Phase B - Security and scale safety

- Add rootless container isolation and hard resource limits.
- Add SHA-256 cache to avoid redundant compile/run.
- Improve resiliency under hostile/degenerate submissions.

### Phase C - Research track

- Prototype low-level runner (Rust/C).
- Benchmark versus Java engine (throughput, p95, resource use).
- Decide long-term execution core based on empirical data.

---

## 16) Acceptance Criteria

### MVP acceptance

- Upload -> evaluate -> status -> results flow works end-to-end.
- Aggregated JSON matches frontend filtering/sorting needs.
- CSV/JSON downloads are valid and consistent.

### Performance acceptance

- Stable operation with 2 workers on Raspberry while host remains responsive.
- No orphan processes after timeout paths.
- Temporary workspace remains within configured `tmpfs` limits for typical loads.

### Security acceptance

- Path traversal attempts are rejected.
- Untrusted execution cannot destabilize host process.
- Resource ceilings are enforced and visible in logs/metrics.
- Symlink/hardlink ZIP entries are rejected.
- Cancellation transitions are consistent and leave no running evaluator.

---

## 17) Risks and Mitigations

- **Host contention**: keep low concurrency, JVM cap, monitor thermal/load.
- **Malicious code behavior**: timeout + PGID kill + `ulimit -t` in Phase A; container isolation in Phase B.
- **CSV format drift**: fixture-based parser tests and compatibility checks.
- **tmpfs exhaustion**: strict cap + fallback/degraded mode signaling.

---

## 18) Locked MVP Decisions

- Upload format: single ZIP archive (`submissions/` + `tests/`).
- Progress model: student/submission level for MVP.
- Cache policy: no cache in Phase A; introduce SHA-256 cache in Phase B.
- Isolation granularity in Phase B: one rootless Podman container per job.

---

## 19) Implementation Notes (Engineering Detail)

### 19.1 Recommended execution wrapper contract

- Wrapper responsibility:
  - create process group/session,
  - apply `ulimit -t`,
  - launch target binary with redirected stdin/stdout/stderr,
  - return PID/PGID to Java for control.

### 19.2 Suggested status payload example

```json
{
  "jobId": "job_20260303_abc123",
  "state": "running",
  "startedAt": "2026-03-03T22:14:02Z",
  "endedAt": null,
  "progress": {
    "totalStudents": 40,
    "processedStudents": 15,
    "currentStudent": "alice_12345"
  },
  "counters": {
    "casesExecuted": 280,
    "okCount": 210,
    "waCount": 40,
    "timeoutCount": 18,
    "runtimeErrorCount": 8,
    "compileErrorCount": 3,
    "oleCount": 1
  }
}
```

---

## 20) Implementation Readiness Review

### 20.1 What is already implementation-ready

- Core architecture and stack are clear: Java 25, Spring Boot, Maven, JUnit 5.
- Runtime constraints are explicit for Raspberry Pi: worker pool `2`, `tmpfs` `64MB`, no cache in Phase A.
- Security baseline is explicit: safe ZIP extraction, PGID-aware kill policy, output caps, cancellation endpoint.
- API contract for core flows is defined (`create job`, `evaluate`, `status`, `results`, `download`, `cancel`).

### 20.2 Items to lock before coding starts

- API payload schemas (request/response JSON) must be frozen in an OpenAPI file.
- ZIP format rules should include exact naming conventions for expected roots (`submissions/`, `tests/`).
- Scoring formula rules for `problem1Score`, `problem2Score`, and `totalScore` should be formalized.
- Error payload standard should be fixed (`code`, `message`, `details`, `jobId`, `timestamp`).

### 20.3 Engineering risks in Sprint 1

- Process-group handling differs across shells/OS details; wrapper behavior must be tested with malicious fixtures.
- ZIP extraction safety is easy to get wrong; test suite must cover traversal and link attacks.
- Timeout and OLE controls can race with cleanup; integration tests must assert no leaked processes.

### 20.4 Definition of Ready for implementation

- All endpoints have agreed request/response examples.
- Phase A limits are committed in config defaults.
- A fixture package for local tests is available (small set of synthetic submissions and tests).
- Team agrees on state machine semantics (`queued -> running -> done/error/cancelling/cancelled`).

---

## 21) Sprint 1 Technical Backlog (Execution Checklist)

Sprint goal: deliver a runnable Phase A backend vertical slice from upload to results with safety controls and test baseline.

### 21.1 Sprint 1 scope

- Include:
  - project scaffold and module structure,
  - job lifecycle APIs,
  - safe ZIP ingestion,
  - execution engine with timeout/PGID kill/OLE limits,
  - status polling and result retrieval,
  - minimum observability and tests.
- Exclude:
  - Podman isolation (Phase B),
  - SHA-256 cache (Phase B),
  - low-level Rust/C runner (Phase C).

### 21.2 Ordered tasks (must-do sequence)

1. **Bootstrap backend project**
- Create Spring Boot app structure and package layout.
- Add core dependencies and testing stack (`spring-boot-starter-web`, validation, JUnit 5).
- Add baseline configuration files and environment profiles.

2. **Define domain and state machine**
- Implement `JobState` enum with transitions.
- Implement `Job` aggregate and persistence model (`status.json`).
- Add unit tests for valid/invalid transitions including cancellation states.

3. **Implement job APIs (skeleton first)**
- `POST /api/jobs`
- `POST /api/jobs/{jobId}/evaluate`
- `GET /api/status/{jobId}`
- `GET /api/results/{jobId}`
- `GET /api/download/{jobId}?format=csv|json`
- `POST /api/jobs/{jobId}/cancel`
- Return stubbed responses first; then wire services.

4. **Build SafeZipExtractor**
- Accept single ZIP upload.
- Validate/reject traversal, absolute paths, symlink/hardlink entries.
- Extract only under `jobId` workspace.
- Add unit tests with malicious ZIP fixtures.

5. **Implement filesystem repository**
- Persist job metadata and status updates on disk.
- Persist artifacts (`results.json`, CSV, logs).
- Add repository tests for read/write consistency and failure handling.

6. **Implement execution wrapper and PGID control**
- Build `ProcessGroupLauncher` wrapper contract.
- Launch binaries in dedicated process group.
- Implement graceful kill -> grace wait -> force kill -> descendant sweep.
- Add integration tests that spawn child processes and validate full cleanup.

7. **Implement case execution controls**
- Enforce wall timeout per case.
- Enforce output caps (`stdout` 2MB, `stderr` 2MB) and classify OLE.
- Map runtime outcomes to status enum.
- Add tests for timeout, OLE, runtime error, compile error.

8. **Implement CSV parse and result aggregation**
- Parse generated CSV artifact.
- Build student aggregate with `files[]` + `cases[]`.
- Produce `results.json` contract used by frontend.
- Add fixture-driven parser tests.

9. **Implement progress and metrics**
- Expose student-level progress in status endpoint.
- Track minimum metrics (`job_total_ms`, `cases_executed`, `ole_count`, etc.).
- Ensure logs include `jobId` and phase markers.

10. **Finalize API contract and docs**
- Publish OpenAPI spec for Sprint 1 endpoints.
- Add README runbook (local run, config, troubleshooting).
- Document known limitations and out-of-scope Phase B/C features.

### 21.3 Sprint 1 Definition of Done

- End-to-end flow works locally:
  - upload ZIP -> evaluate -> poll status -> fetch results -> download artifacts.
- Cancellation endpoint works for queued/running jobs and is idempotent.
- No leaked processes after timeout/cancel scenarios in integration tests.
- OLE classification is deterministic with 2MB cap enforcement.
- Test suite passes in CI command (`mvn test`) with coverage on core execution paths.
- API docs and local run instructions are complete and in English.

### 21.4 Suggested Sprint 1 deliverables

- Running Spring Boot service with the six MVP endpoints.
- `SafeZipExtractor`, `ExecutionEngine`, and `ProcessGroupLauncher` implementations.
- Fixture pack for local/integration tests.
- OpenAPI document + README runbook.

### 19.3 Suggested student result example

```json
{
  "studentId": "12345",
  "studentName": "Alice",
  "submissionDir": "Alice__submission_12345",
  "compileErrorsCount": 1,
  "runtimeErrorsCount": 0,
  "timeoutsCount": 2,
  "waCount": 4,
  "okCount": 14,
  "problem1Score": 7.0,
  "problem2Score": 5.0,
  "totalScore": 12.0,
  "failedAnyQuestion": true,
  "files": [
    {
      "fileName": "problem1.c",
      "problem": "problem1",
      "compileStatus": "OK",
      "compileDetails": "-",
      "cases": [
        {
          "case": "case01",
          "status": "OK",
          "details": "-",
          "expectedOutput": "42",
          "programOutput": "42",
          "durationMs": 7
        }
      ]
    },
    {
      "fileName": "problem2.c",
      "problem": "problem2",
      "compileStatus": "COMPILE_ERROR",
      "compileDetails": "gcc failed",
      "cases": []
    }
  ]
}
```

