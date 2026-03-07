# Grader Backend

A local grading backend for C programming assignments, built with Java 25 + Spring Boot + Maven.

## Requirements

- Java 25 (Corretto or OpenJDK)
- Maven 3.9+
- `gcc` on PATH (for compiling student submissions)
- `setsid` on PATH (available on Linux; macOS skips PGID-based tests)

## Quick Start

```bash
cd backend
mvn spring-boot:run
```

The API listens on `http://localhost:8080`.

## Configuration

All settings are in `src/main/resources/application.yml`. Key defaults:

| Property | Default | Description |
|---|---|---|
| `grader.workspace-root` | `/tmp/grader-jobs` | Root directory for job workspaces |
| `grader.tmpfs-path` | `/tmp/grader-tmpfs` | tmpfs mount point (mount manually or leave as regular dir) |
| `grader.worker-pool-size` | `2` | Thread pool size for evaluation workers |
| `grader.time-limit-sec` | `2` | Per-case wall-clock timeout in seconds |
| `grader.stdout-cap-bytes` | `2097152` | 2 MB stdout cap per case |
| `grader.stderr-cap-bytes` | `2097152` | 2 MB stderr cap per case |
| `grader.compile-flags` | `-O1 -Wall -Werror=vla` | `gcc` compilation flags |
| `grader.kill-grace-ms` | `200` | Grace period between SIGTERM and SIGKILL |
| `grader.scoring.max-score-per-problem` | `20` | Maximum score per problem |

### Raspberry Pi 5 deployment

Mount tmpfs before starting:

```bash
sudo mount -t tmpfs -o size=64m tmpfs /tmp/grader-tmpfs
```

Set JVM memory bounds in the startup script:

```bash
java -Xms256m -Xmx768m -jar grader-backend.jar
```

## API Reference

### `POST /api/jobs`

Upload a ZIP containing submissions and tests.

**ZIP layout:**
```
submissions/
  Alice__submission_001/
    problem1.c
    problem2.c
tests/
  problem1/
    case01.in  case01.out
  problem2/
    case01.in  case01.out
```

**Response:**
```json
{"jobId": "job_1234_abcd", "status": "queued"}
```

### `POST /api/jobs/{jobId}/evaluate`

Start evaluation for a queued job. Only one job may be active at a time.

**Response:**
```json
{"jobId": "job_1234_abcd", "status": "running"}
```

### `GET /api/status/{jobId}`

Poll job state and progress counters.

**Response:**
```json
{
  "jobId": "job_1234_abcd",
  "state": "running",
  "startedAt": "2026-03-06T20:00:00Z",
  "endedAt": null,
  "progress": {
    "totalStudents": 40,
    "processedStudents": 10,
    "currentStudent": "alice_001"
  },
  "counters": {
    "casesExecuted": 120,
    "okCount": 95,
    "waCount": 15,
    "timeoutCount": 5,
    "runtimeErrorCount": 3,
    "compileErrorCount": 2,
    "oleCount": 0
  }
}
```

### `GET /api/results/{jobId}`

Retrieve the aggregated JSON report. If aggregation has not been produced yet, returns an empty list.

### `GET /api/download/{jobId}?format=csv|json`

Download the raw CSV or JSON artifact file.

### `POST /api/jobs/{jobId}/cancel`

Request cancellation. Idempotent — safe to call on any state.

**Response:**
```json
{"jobId": "job_1234_abcd", "status": "cancelling"}
```

### `GET /api/health`

Liveness probe.

**Response:**
```json
{"status": "UP"}
```

## Running Tests

```bash
cd backend
mvn test
```

Tests run: ~142 total, 24 skipped (Unix-only PGID integration tests).

## Error Payloads

All error responses follow:

```json
{
  "code": "JOB_NOT_FOUND",
  "message": "Job not found: job_1234_abcd",
  "details": null,
  "jobId": "job_1234_abcd",
  "timestamp": "2026-03-06T20:00:00Z"
}
```

| HTTP | Code | When |
|---|---|---|
| 404 | `JOB_NOT_FOUND` | jobId does not exist |
| 404 | `ARTIFACT_NOT_FOUND` | results not ready yet |
| 409 | `JOB_INVALID_STATE` | invalid state transition |
| 409 | `JOB_BUSY` | another job is already evaluating |
| 400 | `INVALID_REQUEST` | bad request parameters |
| 500 | `INTERNAL_ERROR` | unexpected server error |

## Metrics Logging

At job completion, a structured log line is emitted:

```
METRICS jobId=... state=done job_total_ms=5000 compile_total_ms=800 run_total_ms=4200
        compare_total_ms=0 cases_executed=200 ok_count=185 wa_count=10 timeouts_count=3
        runtime_errors_count=2 compile_error_count=0 ole_count=0
        killed_processes_count=3 cleanup_failures_count=0 tmpfs_peak_bytes=4194304
        cancelled_jobs_count=0 cancelled_jobs_total=0 cleanup_failures_total=0
```

## Known Limitations (Phase A)

- One active grading job at a time (enforced by application).
- No result caching — each `evaluate` re-runs everything.
- No container isolation — student code runs directly on the host (mitigated by timeout + PGID kill + ulimit).
- Phase B will add rootless Podman isolation and SHA-256 caching.
