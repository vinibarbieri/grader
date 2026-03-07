package com.grader.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProcessGroupLauncher.
 *
 * These tests spawn real OS processes and verify process-group-level kill semantics.
 * Annotated {@code @EnabledOnOs(OS.LINUX)} because the implementation relies on
 * {@code setsid} (util-linux) which is not present on macOS by default.
 */
@EnabledOnOs(OS.LINUX)
class ProcessGroupLauncherTest {

    @TempDir
    Path workDir;

    private final ProcessGroupLauncher launcher = new ProcessGroupLauncher(200L);

    // --- launch ---

    @Test
    void launch_simpleProcess_capturesStdoutAndExitsZero() throws Exception {
        LaunchResult result = launcher.launch(List.of("echo", "hello"), workDir, 5);

        boolean done = result.process().waitFor(3, TimeUnit.SECONDS);
        assertTrue(done, "Process should complete within timeout");
        assertEquals(0, result.process().exitValue());

        String output = new String(result.process().getInputStream().readAllBytes());
        assertEquals("hello\n", output);
    }

    @Test
    void launch_setsWorkingDirectory() throws Exception {
        LaunchResult result = launcher.launch(List.of("pwd"), workDir, 5);

        boolean done = result.process().waitFor(2, TimeUnit.SECONDS);
        assertTrue(done);

        String output = new String(result.process().getInputStream().readAllBytes()).trim();
        assertEquals(workDir.toRealPath().toString(), output);
    }

    @Test
    void launch_pgidEqualsProcessPid_verifiedViaPs() throws Exception {
        // With setsid, the launched process becomes the session/group leader,
        // so PGID = PID. We verify this using ps on Linux.
        LaunchResult result = launcher.launch(List.of("sleep", "30"), workDir, 60);
        try {
            assertTrue(result.process().isAlive());

            long pid = result.process().pid();
            assertEquals(pid, result.pgid(), "pgid stored in LaunchResult must equal pid");

            // Verify actual PGID via ps
            Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", String.valueOf(pid))
                    .start();
            ps.waitFor(2, TimeUnit.SECONDS);
            String pgidStr = new String(ps.getInputStream().readAllBytes()).trim();
            assertEquals(pid, Long.parseLong(pgidStr),
                    "Actual OS PGID must equal PID after setsid");
        } finally {
            launcher.kill(result);
            result.process().waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void launch_appliesCpuTimeLimit_processKilledBySigxcpu() throws Exception {
        // ulimit -t 1 terminates a CPU-spinning process within ~1 second of CPU time.
        LaunchResult result = launcher.launch(
                List.of("/bin/bash", "-c", "while true; do :; done"),
                workDir,
                1);

        boolean terminated = result.process().waitFor(10, TimeUnit.SECONDS);
        assertTrue(terminated, "Process must be killed by CPU time limit within 10 s wall time");
        assertNotEquals(0, result.process().exitValue(),
                "Exit code must be non-zero when killed by signal");
    }

    // --- kill ---

    @Test
    void kill_terminatesLongRunningProcess() throws Exception {
        LaunchResult result = launcher.launch(List.of("sleep", "60"), workDir, 120);
        assertTrue(result.process().isAlive());

        launcher.kill(result);

        boolean terminated = result.process().waitFor(3, TimeUnit.SECONDS);
        assertTrue(terminated, "Process should be terminated after kill");
        assertFalse(result.process().isAlive());
    }

    @Test
    void kill_cleanesUpChildProcesses() throws Exception {
        // Non-interactive bash does not enable job control, so background children
        // inherit the parent's PGID. SIGKILL to the group kills all of them.
        LaunchResult result = launcher.launch(
                List.of("/bin/bash", "-c", "sleep 60 & sleep 60 & wait"),
                workDir,
                120);

        // Give children time to spawn
        Thread.sleep(400);

        var descendants = result.process().toHandle()
                .descendants()
                .collect(Collectors.toList());
        assertFalse(descendants.isEmpty(), "Parent should have spawned child processes");

        launcher.kill(result);
        result.process().waitFor(3, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 2_000;
        long aliveCount;
        do {
            aliveCount = descendants.stream().filter(this::isAliveNonZombie).count();
            if (aliveCount == 0) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);

        assertEquals(0, aliveCount, "All child processes must be terminated after kill");
    }

    @Test
    void kill_isIdempotent_doesNotThrowOnSecondCall() throws Exception {
        LaunchResult result = launcher.launch(List.of("sleep", "60"), workDir, 120);

        launcher.kill(result);
        result.process().waitFor(2, TimeUnit.SECONDS);

        // Second kill must not throw
        assertDoesNotThrow(() -> launcher.kill(result));
    }

    @Test
    void kill_onAlreadyExitedProcess_doesNotThrow() throws Exception {
        LaunchResult result = launcher.launch(List.of("echo", "done"), workDir, 5);
        result.process().waitFor(2, TimeUnit.SECONDS);
        assertFalse(result.process().isAlive());

        assertDoesNotThrow(() -> launcher.kill(result));
    }

    private boolean isAliveNonZombie(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return false;
        }
        // In containers without an init/reaper, killed children can remain as zombies.
        // Zombies are terminated from a workload/safety perspective, so we don't count
        // them as survivors in this test.
        return !isZombie(handle.pid());
    }

    private boolean isZombie(long pid) {
        Path stat = Path.of("/proc", String.valueOf(pid), "stat");
        try {
            if (!Files.exists(stat)) {
                return false;
            }
            String raw = Files.readString(stat);
            int closeParen = raw.lastIndexOf(')');
            if (closeParen < 0 || closeParen + 2 >= raw.length()) {
                return false;
            }
            char state = raw.charAt(closeParen + 2);
            return state == 'Z';
        } catch (Exception ignored) {
            return false;
        }
    }
}
