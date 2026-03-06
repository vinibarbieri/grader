# Step 6 — ProcessGroupLauncher

## What was done

- Created `LaunchResult.java` (record: `Process process`, `long pgid`)
- Wrote `ProcessGroupLauncherTest.java` (8 tests, Linux-only) — tests first
- Implemented `ProcessGroupLauncher.java`
- Registered `ProcessGroupLauncher` as a Spring bean in `AppConfig.java`

## Process-group strategy

Every launch is wrapped as:

```
setsid /bin/bash -c "ulimit -t <N>; exec <cmd>"
```

`setsid` (util-linux) calls the `setsid(2)` syscall then `execvp()` into `/bin/bash`. Because it **execs in-place**, the resulting bash process has the same OS PID as the Java `Process` object. Since `setsid(2)` makes the caller a session leader, `PGID == PID == process.pid()`. This means:

- No PID-lookup tricks are needed — `pgid = process.pid()` is always correct.
- Killing `-- -<pgid>` sends the signal to the entire process group (bash + all its children).

`ulimit -t <N>` provides an OS-level CPU ceiling as a secondary safety net (in addition to the Java-level wall-clock timeout enforced by `ExecutionEngine` in Step 7).

## Kill sequence

| Step | Action |
|------|--------|
| 1 | `kill -TERM -- -<pgid>` — graceful stop to all processes in the group |
| 2 | Sleep `killGraceMs` (default 200 ms) — grace window for clean shutdown |
| 3 | `kill -KILL -- -<pgid>` — forced kill to all processes in the group |
| 4 | `ProcessHandle.descendants().forEach(destroyForcibly)` — sweep any survivors that may have migrated process groups |

The kill method is idempotent: signals sent to a non-existent PGID are silently ignored by the OS, and `destroyForcibly` on an already-dead handle is a no-op.

## Key decisions

- **`setsid` over `bash -m`**: `set -m` in bash enables job control inside the shell (puts each `&` job in its own group) but does NOT create a new PGID for the shell itself. `setsid` reliably creates a new session/group for the entire subtree.
- **Shell quoting**: Arguments are single-quoted with `'...'` and embedded quotes escaped via `'\''`. This is safe for any argument value.
- **Descendant sweep via `ProcessHandle`**: Catches survivors that may have called `setpgid` to escape the original group (rare but possible with malicious code).
- **No waitFor on kill signals**: The `kill` command is fire-and-forget. The caller (`ExecutionEngine`) is responsible for `waitFor` on the process itself.
- **macOS CI**: All 8 tests are `@EnabledOnOs(OS.LINUX)` because `setsid` is not present on macOS by default. They will execute on the Raspberry Pi target.

## Tests written

| Test | Scenario |
|------|----------|
| `launch_simpleProcess_capturesStdoutAndExitsZero` | echo returns output and exit 0 |
| `launch_setsWorkingDirectory` | pwd outputs the configured working dir |
| `launch_pgidEqualsProcessPid_verifiedViaPs` | ps -o pgid confirms PGID == PID via OS |
| `launch_appliesCpuTimeLimit_processKilledBySigxcpu` | CPU-spinning loop terminated by ulimit in ≤10 s |
| `kill_terminatesLongRunningProcess` | sleep 60 dies after kill() |
| `kill_cleanesUpChildProcesses` | background children (same PGID) die after kill() |
| `kill_isIdempotent_doesNotThrowOnSecondCall` | second kill() call does not throw |
| `kill_onAlreadyExitedProcess_doesNotThrow` | kill() on exited process is safe |

## Test outcome

```
Tests run: 63, Failures: 0, Errors: 0, Skipped: 8 (Linux-only tests on macOS)
BUILD SUCCESS
```
