# Step 7 — ExecutionEngine

## What was done

Implemented `ExecutionEngine` (in `service` package) and its companion record `CompileOutcome`.
Tests were written first (TDD).

### New files

| File | Role |
|------|------|
| `service/CompileOutcome.java` | Record holding `CompileStatus` + diagnostics string |
| `service/ExecutionEngine.java` | Compile → run → compare cycle with timeout and OLE enforcement |
| `test/service/ExecutionEngineTest.java` | 14 integration tests covering all case statuses |

### Modified files

| File | Change |
|------|--------|
| `config/AppConfig.java` | Added `executionEngine` Spring bean |

---

## Design decisions

### Compile phase
- Uses a plain `ProcessBuilder` (no setsid/ulimit needed — gcc is trusted and short-lived).
- `redirectErrorStream(true)` merges gcc stdout+stderr into one diagnostic string, avoiding deadlocks.
- `readAllBytes()` is safe here because gcc always exits cleanly within 30 s.

### Run phase
- Delegates to `ProcessGroupLauncher.launch()` so the binary runs in a dedicated process group.
- `cpuTimeLimitSec = timeLimitSec + 1` is passed to `ulimit -t`, giving the OS a ceiling one second above the wall-clock limit so the Java `waitFor` timeout fires first for wall-bound processes.
- Stdin is fed in a dedicated daemon thread to prevent the parent from deadlocking while the process's stdout/stderr pipes fill up.

### CappedReader (inner class)
- A daemon thread that reads into a `ByteArrayOutputStream` up to `cap` bytes.
- When `total > cap` it sets `exceeded = true`, invokes the `onCapExceeded` callback (which calls `launcher.kill()`), then drains remaining bytes without storing them so the writer can unblock and exit.
- Both stdout and stderr get their own `CappedReader`; either can trigger OLE independently via a shared `AtomicBoolean`.

### Kill precedence (OLE vs timeout)
After `waitFor()` returns, status is resolved in this order:
1. `OLE` — checked first (covers the case where OLE kill was faster than wall timeout).
2. `TIMEOUT` — `!completed` and no OLE.
3. `RUNTIME_ERROR` — process exited with non-zero code.
4. `OK` / `WA` — comparison of normalized outputs.

### Output normalization
`normalize(s)`: strips trailing whitespace from each line, then strips trailing newlines from the whole output. This tolerates common formatting differences (trailing spaces, missing final newline).

### AtomicBoolean lambda capture
`AtomicBoolean oleFlag` is assigned once before the lambdas, making the reference effectively final — valid Java lambda capture with correct cross-thread visibility.

---

## Test outcomes

```
Tests run: 77, Failures: 0, Errors: 0, Skipped: 22
```

- 14 `ExecutionEngineTest` cases: **all skipped on macOS** (annotated `@EnabledOnOs(OS.LINUX)` — require `gcc` and `setsid`).
- All other existing tests (JobStateTest, FilesystemJobRepositoryTest, SafeZipExtractorTest, JobControllerTest, ProcessGroupLauncherTest): **continue to pass**.
- Linux execution (Raspberry Pi target) will run all 14 engine tests.

### Cases covered

| Test | Expected status |
|------|----------------|
| `compile_validCFile_returnsOkStatus` | `CompileStatus.OK` |
| `compile_syntaxError_returnsCompileError` | `COMPILE_ERROR` |
| `compile_missingReturnStatement_withWallFlag_returnsOk` | `OK` (flags parsed correctly) |
| `runCase_correctOutput_returnsOk` | `CaseStatus.OK` |
| `runCase_wrongOutput_returnsWa` | `WA` |
| `runCase_nonZeroExit_returnsRuntimeError` | `RUNTIME_ERROR` (exit code in details) |
| `runCase_timeout_returnsTimeoutAndProcessIsGone` | `TIMEOUT` (< 8 s wall budget) |
| `runCase_stdoutExceedsCap_returnsOle` | `OUTPUT_LIMIT_EXCEEDED` |
| `runCase_stderrExceedsCap_returnsOle` | `OUTPUT_LIMIT_EXCEEDED` |
| `runCase_withInputFile_passesStdinToProgram` | `OK` (stdin correctly piped) |
| `runCase_trailingWhitespaceTolerance_treatedAsOk` | `OK` (normalization) |
| `runCase_multiLineOutput_normalizesTreatsAsOk` | `OK` |
| `runCase_afterTimeout_processIsNoLongerAlive` | `TIMEOUT` |
| `runCase_emptyExpectedAndEmptyOutput_returnsOk` | `OK` |
