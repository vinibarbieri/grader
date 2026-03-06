# Step 4 — API Controller Skeletons

## What was done

Implemented all 6 REST API endpoint skeletons with tests-first (TDD), covering the full HTTP contract defined in `backend-prd.md`.

## Files created

### Tests (written first)
- `src/test/java/com/grader/controller/JobControllerTest.java` — 15 MockMvc tests covering all 6 endpoints

### DTOs (`controller/dto/`)
- `JobCreateResponse` — `{ jobId, status }`
- `JobEvaluateResponse` — `{ jobId, status }`
- `JobStatusResponse` — full payload with nested `Progress` and `Counters` records; static `from(Job)` factory
- `JobCancelResponse` — `{ jobId, status }`
- `ErrorResponse` — `{ code, message, details, jobId, timestamp }`

### Service layer
- `service/JobService.java` — interface defining all 6 service operations
- `service/JobNotFoundException.java` — `RuntimeException` carrying `jobId`
- `service/JobServiceImpl.java` — `@Service` stub; creates/reads/cancels jobs via `JobRepository`; evaluate and results return stubs (full wiring in Step 9)

### Controller layer
- `controller/JobController.java` — `@RestController` at `/api`, delegates to `JobService`
- `controller/GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping `JobNotFoundException` → 404, `MissingServletRequestPartException` → 400, `IllegalArgumentException` → 400, generic → 500

### Config
- `config/AppConfig.java` — `@Configuration` registering `FilesystemJobRepository` as a `JobRepository` bean (reads `GraderConfig.workspaceRoot`)

## Key decisions

### Test approach: standalone MockMvc
Spring Boot 3.2.x bundles ASM 9.6 (supports up to Java 23 class files). Java 25 (class file v69) triggers `ClassFormatException` during `@WebMvcTest` classpath scanning.
Solution: use `MockMvcBuilders.standaloneSetup()` with `MockitoExtension`, which avoids classpath scanning entirely. Same HTTP contract coverage, no Spring context needed.

### Byte Buddy / Mockito Java 25 support
Mockito's Byte Buddy 1.14.x officially supports up to Java 22. Fix: added `-Dnet.bytebuddy.experimental=true` to Surefire `argLine` in `pom.xml`. This enables Byte Buddy's experimental forward-compatibility path.

### Message converters in standalone setup
`setMessageConverters(...)` replaces all default converters, so both `ByteArrayHttpMessageConverter` (for the download endpoint `byte[]` response) and `MappingJackson2HttpMessageConverter` (with `JavaTimeModule` for `Instant` serialization) must be listed explicitly.

### Download format validation in controller
Invalid `format` parameter throws `IllegalArgumentException`, caught by `GlobalExceptionHandler` → 400. No service call is made before format is validated.

### JobServiceImpl is a stub
`evaluateJob` transitions to `RUNNING` immediately (no async execution yet). `getResults` returns `List.of()`. `downloadArtifact` returns `byte[0]`. Full wiring happens in Step 9 (JobService) and Step 10 (ArtifactService).

## Test outcomes

```
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
  JobControllerTest:          15 tests
  FilesystemJobRepositoryTest: 8 tests
  JobStateTest:               22 tests
BUILD SUCCESS
```

## Endpoints covered

| Method | Path                          | Test scenarios                                 |
|--------|-------------------------------|------------------------------------------------|
| POST   | `/api/jobs`                   | 201 with jobId+status; 400 missing file        |
| POST   | `/api/jobs/{jobId}/evaluate`  | 200 running; 404 unknown job                   |
| GET    | `/api/status/{jobId}`         | 200 full payload; 404 unknown job              |
| GET    | `/api/results/{jobId}`        | 200 array; 404 unknown job                     |
| GET    | `/api/download/{jobId}`       | 200 csv/json with attachment; 400 bad format; 404 unknown job |
| POST   | `/api/jobs/{jobId}/cancel`    | 200 cancelling; 200 idempotent on terminal; 404 unknown job |
