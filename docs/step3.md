# Step 3 — Filesystem Repository

## What was done

Implemented the filesystem-based job persistence layer (TDD — tests were already written).

### Files created/modified

- `src/main/java/com/grader/infrastructure/FilesystemJobRepository.java` — new implementation
- `src/main/java/com/grader/domain/Job.java` — added reconstruction constructor for deserialization

## Design decisions

### Storage layout
Each job gets its own directory under `workspaceRoot`:
```
<workspaceRoot>/
  <jobId>/
    status.json     ← persisted JobSnapshot
```

### Serialization
Used Jackson with `JavaTimeModule` to handle `Instant` fields. An internal `JobSnapshot` DTO is used instead of serializing `Job` directly — this keeps the domain class decoupled from JSON concerns.

### Reconstruction constructor
Added a full-argument constructor to `Job` for rehydrating from a snapshot. The existing `Job(String jobId)` factory constructor is unchanged for normal creation flow.

### No caching
`FilesystemJobRepository` reads from disk on every `findById` call. This is correct for the MVP — the worker pool is small (2) and jobs are persisted after every state change. Caching would add complexity without benefit at this scale.

## Test outcomes

```
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0  BUILD SUCCESS
```

Tests added (8):
- `save_and_findById_roundtrip` — basic persist + read
- `findById_returnsEmpty_whenNotFound`
- `update_persists_stateChange` — transition persists correctly
- `update_persists_progressCounters` — all counters survive round-trip
- `multipleJobs_areStoredIndependently`
- `getJobDirectory_createsAndReturnsPath`
- `exists_returnsTrueForSavedJob`
- `exists_returnsFalseForMissingJob`
