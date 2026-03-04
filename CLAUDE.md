# Claude Implementation Guide - Grader Backend

## Mission

Build the backend defined in `backend-prd.md` using Java 21, Spring Boot, Maven, OOP design, and TDD with JUnit 5.
This file is a strict execution guide for implementation decisions.

## Source of truth

- Primary specification: `backend-prd.md`
- Existing grading logic reference:
  - `avaliacao_automatica.sh`
  - `renomear_submissoes.sh`

If there is any conflict between this guide and implementation assumptions, follow `backend-prd.md`.

## Mandatory technical stack

- Java 21
- Spring Boot (REST API)
- Maven
- JUnit 5
- Filesystem persistence for MVP (no database)

## Architecture requirements

- Use layered architecture:
  - controller layer
  - service/application layer
  - domain layer
  - infrastructure layer
- Keep classes small and focused.
- Use constructor injection.
- Avoid static mutable state.
- Keep all code, API payloads, comments, and docs in English.

## Phase A scope (must deliver first)

Implement only Phase A first:

1. Job lifecycle and APIs
2. Safe ZIP upload/extraction
3. Evaluation execution engine
4. PGID-based process control and cleanup
5. Deterministic output limits and status mapping
6. CSV parsing and aggregated JSON results
7. Status/progress endpoint
8. Download endpoint
9. Cancellation endpoint
10. Baseline metrics and logs

Do not implement Phase B (Podman/cache) or Phase C (Rust/C runner) before Phase A is complete.

## API endpoints (MVP)

- `POST /api/jobs`
- `POST /api/jobs/{jobId}/evaluate`
- `GET /api/status/{jobId}`
- `GET /api/results/{jobId}`
- `GET /api/download/{jobId}?format=csv|json`
- `POST /api/jobs/{jobId}/cancel`

## Job states

Use this state set:

- `queued`
- `running`
- `cancelling`
- `cancelled`
- `done`
- `error`

Cancellation must be idempotent.

## Upload and extraction rules

- Accept a single ZIP file per job.
- Expected ZIP root layout:
  - `submissions_(submissionId)/`
  - `problem1.c`
  - `problem2.c`
- Reject ZIP entries when:
  - entry path contains `..`
  - entry path is absolute
  - entry is symlink/hardlink
  - canonical destination escapes job directory

## Execution and safety rules

- Use `ProcessBuilder` from Java.
- Start execution in a dedicated process group (PGID-aware wrapper).
- Per-case control sequence:
  1. start process
  2. enforce timeout
  3. on timeout/error: graceful stop to PGID
  4. wait short grace period
  5. force kill to PGID
  6. descendant sweep fallback
- Add OS-level backup CPU protection with `ulimit -t`.

## Deterministic I/O limits

- `stdout` cap: 2MB
- `stderr` cap: 2MB
- If cap is exceeded, abort and classify as `OUTPUT_LIMIT_EXCEEDED`

Case status set:

- `OK`
- `WA`
- `TIMEOUT`
- `RUNTIME_ERROR`
- `COMPILE_ERROR`
- `SKIP`
- `OUTPUT_LIMIT_EXCEEDED`
- `INTERNAL_ERROR`

## Performance constraints (Raspberry Pi 5 4GB)

- Fixed worker pool: 2
- One active grading job at a time
- Temporary workspace in `tmpfs` with 64MB cap
- Keep JVM memory bounded (example: `-Xms256m -Xmx768m`)

## Result contract requirements

Return student aggregates with per-file granularity:

- student-level counters and scores
- `files[]` with:
  - `fileName`
  - `problem`
  - `compileStatus`
  - `compileDetails`
  - `cases[]` with status/details/outputs/duration

## TDD requirements

Write tests first for:

- job state machine transitions
- cancellation and idempotency
- ZIP traversal/link rejection
- timeout and PGID kill behavior
- output cap (OLE) behavior
- CSV parser and aggregation fixtures

Minimum acceptance for each backend change:

- tests added/updated
- all tests passing with `mvn test`

## Logging and metrics requirements

At minimum capture:

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

All major logs must include `jobId`.

## Implementation order checklist

1. Scaffold Spring Boot project and package structure.
2. Implement domain model and job state machine.
3. Implement API skeletons.
4. Implement safe ZIP extractor.
5. Implement filesystem repository.
6. Implement execution engine and PGID wrapper.
7. Implement timeout/OLE mapping.
8. Implement parser and results aggregation.
9. Implement status progress and metrics.
10. Implement downloads and cancellation final behavior.

## Done criteria for Phase A

- End-to-end flow works:
  - upload -> evaluate -> status -> results -> download
- Cancellation works for queued/running jobs.
- No leaked processes in timeout/cancel scenarios.
- OLE classification is deterministic.
- API payloads align with `backend-prd.md`.
- README/runbook explains local setup and execution.

## Guardrails

- Do not introduce database in MVP.
- Do not over-engineer Phase B/C before Phase A acceptance.
- Do not change API contracts without updating `backend-prd.md`.
- Do not skip tests for execution and safety paths.
- Always start writting the text before implementation it is a TDD project

## Step-by-step documentation workflow

After completing each implementation step:
1. Verify tests pass: `cd backend && mvn test`
2. Create `grader/docs/step{N}.md` documenting what was done, decisions made, and test outcomes
3. Update `grader/claudePlan.md` if the plan changed

Steps map:
- Step 0: Setup workflow files
- Step 1: Maven scaffold + configuration
- Step 2: Domain model (tests first)
- Step 3: Filesystem repository (tests first)
- Step 4: API controller skeletons (tests first)
- Step 5: SafeZipExtractor (tests first)
- Step 6: ProcessGroupLauncher (tests first)
- Step 7: ExecutionEngine (tests first)
- Step 8: CSV aggregation + results model (tests first)
- Step 9: JobService + full wiring
- Step 10: MetricsService + ArtifactService + final API wiring
