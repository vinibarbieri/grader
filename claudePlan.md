# Grader Backend - Phase A Implementation Plan

## Context

Scaffold a Spring Boot 3 / Java 21 / Maven backend for the local grader platform as defined in `backend-prd.md` and `CLAUDE.md`. No existing backend code exists. The project will live at `grader/backend/`.

**Workflow**: TDD project — tests are written first. Plan lives in `grader/claudePlan.md`. Each step is documented in `grader/docs/step{N}.md` after completion. `CLAUDE.md` is updated with this workflow.

Scoring formula: `score = (okCount / totalCases) * maxScore` per problem, configurable, default max per problem = 20 (total max = 40).

---

## Files to create/modify immediately (before Step 1):

1. `grader/claudePlan.md` — copy of this plan for permanent reference
2. `grader/CLAUDE.md` — add step-by-step workflow section
3. `grader/docs/` — create docs directory

---

## Project Structure

```
grader/backend/
├── pom.xml
├── src/
│   ├── main/java/com/grader/
│   │   ├── GraderApplication.java
│   │   ├── controller/JobController.java
│   │   ├── domain/
│   │   │   ├── Job.java
│   │   │   ├── JobState.java
│   │   │   ├── CaseStatus.java
│   │   │   ├── CompileStatus.java
│   │   │   └── model/StudentResult.java, FileResult.java, CaseResult.java
│   │   ├── service/
│   │   │   ├── JobService.java
│   │   │   ├── ExecutionEngine.java
│   │   │   ├── CsvAggregationService.java
│   │   │   ├── ArtifactService.java
│   │   │   └── MetricsService.java
│   │   ├── infrastructure/
│   │   │   ├── JobRepository.java (interface)
│   │   │   ├── FilesystemJobRepository.java
│   │   │   ├── SafeZipExtractor.java
│   │   │   └── ProcessGroupLauncher.java
│   │   └── config/GraderConfig.java
│   ├── main/resources/application.yml
│   └── test/java/com/grader/
│       ├── domain/JobStateTest.java
│       ├── infrastructure/SafeZipExtractorTest.java, ProcessGroupLauncherTest.java
│       ├── service/ExecutionEngineTest.java, CsvAggregationServiceTest.java
│       └── controller/JobControllerTest.java
```

---

## Step-by-Step Implementation (TDD)

### Step 0 — Setup workflow files
- Create `grader/claudePlan.md` (this plan)
- Update `grader/CLAUDE.md` with step-by-step documentation workflow
- Create `grader/docs/` directory
- Document in `grader/docs/step0.md`

### Step 1 — Maven scaffold + configuration
- `pom.xml`: Java 21, Spring Boot 3.x, spring-boot-starter-web, validation, Jackson, JUnit 5, Mockito
- `GraderApplication.java`
- `application.yml` with all Phase A config defaults
- `GraderConfig.java` (config properties binding)
- Document in `grader/docs/step1.md`

### Step 2 — Domain model (tests first)
Write `JobStateTest.java`:
- valid transitions: queued→running, running→done/error/cancelling, cancelling→cancelled
- invalid transitions throw `IllegalStateException`
- cancellation idempotency on terminal states

Implement:
- `JobState.java` (enum with transition validation)
- `CaseStatus.java` (OK, WA, TIMEOUT, RUNTIME_ERROR, COMPILE_ERROR, SKIP, OUTPUT_LIMIT_EXCEEDED, INTERNAL_ERROR)
- `CompileStatus.java` (OK, COMPILE_ERROR)
- `Job.java` (aggregate with jobId, state, timestamps, progress counters)
- Document in `grader/docs/step2.md`

### Step 3 — Filesystem repository (tests first)
Write `FilesystemJobRepositoryTest.java`: read/write/update consistency.

Implement:
- `JobRepository.java` (interface)
- `FilesystemJobRepository.java` — persists `status.json` per job
- Document in `grader/docs/step3.md`

### Step 4 — API controller skeletons (tests first)
Write `JobControllerTest.java` (MockMvc) covering all 6 endpoints.

Implement `JobController.java` returning stubs initially.
- Document in `grader/docs/step4.md`

### Step 5 — SafeZipExtractor (tests first)
Write `SafeZipExtractorTest.java` with malicious ZIP fixtures:
- path traversal (`../../etc/passwd`)
- absolute paths (`/etc/passwd`)
- symlink entries, hardlink entries
- valid extraction

Implement `SafeZipExtractor.java`.
- Document in `grader/docs/step5.md`

### Step 6 — ProcessGroupLauncher (tests first)
Write integration tests spawning real processes and verifying cleanup.

Implement `ProcessGroupLauncher.java`:
- shell wrapper setting PGID + `ulimit -t`
- kill sequence: SIGTERM to PGID → 200ms → SIGKILL to PGID → descendant sweep
- Document in `grader/docs/step6.md`

### Step 7 — ExecutionEngine (tests first)
Write `ExecutionEngineTest.java`:
- compile error, timeout, OLE, runtime error
- stdout/stderr cap (2MB)
- PGID cleanup after case

Implement `ExecutionEngine.java`:
- compile: `gcc` with configurable flags
- run: ProcessGroupLauncher + timeout + output cap
- compare: normalize (trim trailing spaces/newlines) in-memory
- Document in `grader/docs/step7.md`

### Step 8 — CSV aggregation + results model (tests first)
Write `CsvAggregationServiceTest.java` with fixture CSV strings.

Implement:
- `CsvAggregationService.java`: CSV → `List<StudentResult>` → `results.json`
- `StudentResult`, `FileResult`, `CaseResult` records
- Scoring: `(okCount / totalCases) * maxScorePerProblem` (default max=20 per problem)
- Document in `grader/docs/step8.md`

### Step 9 — JobService + full wiring
Implement `JobService.java`:
- createJob, evaluate (worker pool 2), getStatus, getResults, cancel (idempotent)
- Progress tracking
- Document in `grader/docs/step9.md`

### Step 10 — MetricsService + ArtifactService + final API wiring
Implement metrics capture (all required counters) with SLF4J.
Implement ArtifactService for download endpoints.
Wire everything end-to-end.
- Document in `grader/docs/step10.md`

---

## Key Config Defaults (application.yml)
```yaml
grader:
  workspace-root: /tmp/grader-jobs
  tmpfs-path: /tmp/grader-tmpfs
  worker-pool-size: 2
  time-limit-sec: 2
  stdout-cap-bytes: 2097152   # 2MB
  stderr-cap-bytes: 2097152   # 2MB
  compile-flags: "-O1 -Wall -Werror=vla"
  kill-grace-ms: 200
  scoring:
    max-score-per-problem: 20
```

## Error Payload Standard
```json
{"code": "JOB_NOT_FOUND", "message": "...", "details": "...", "jobId": "...", "timestamp": "..."}
```

## Verification per step
- `cd backend && mvn test` after every step
- Final: upload ZIP → POST evaluate → poll status → GET results → GET download
- Cancellation idempotency verified by test
- No leaked processes after timeout (integration test)
