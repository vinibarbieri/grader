package com.grader.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Launches OS processes inside a dedicated process group and provides a
 * structured kill sequence.
 *
 * <h2>Process-group strategy</h2>
 * Every launch is wrapped with {@code setsid /bin/bash -c "ulimit -t N; exec <cmd>"}.
 * {@code setsid} calls the {@code setsid(2)} syscall before exec-ing into bash,
 * making the resulting process the session leader. Therefore
 * {@code PGID == PID == process.pid()}.
 *
 * <h2>Kill sequence</h2>
 * <ol>
 *   <li>SIGTERM to the entire process group ({@code kill -TERM -- -<pgid>})</li>
 *   <li>Grace period wait ({@code killGraceMs})</li>
 *   <li>SIGKILL to the entire process group ({@code kill -KILL -- -<pgid>})</li>
 *   <li>Descendant sweep via {@link ProcessHandle} for any survivors</li>
 * </ol>
 */
public class ProcessGroupLauncher {

    private static final Logger log = LoggerFactory.getLogger(ProcessGroupLauncher.class);

    private final long killGraceMs;

    public ProcessGroupLauncher(long killGraceMs) {
        this.killGraceMs = killGraceMs;
    }

    /**
     * Launches {@code command} in a new process group inside {@code workingDir}.
     *
     * <p>The process is wrapped with {@code setsid} so that its PGID equals its PID.
     * A {@code ulimit -t} CPU ceiling is applied as an OS-level safety net.
     *
     * @param command          command and arguments to execute
     * @param workingDir       working directory for the process
     * @param cpuTimeLimitSec  CPU time ceiling in seconds (passed to {@code ulimit -t})
     * @return a {@link LaunchResult} containing the {@link Process} and its PGID
     * @throws IOException if the process cannot be started
     */
    public LaunchResult launch(List<String> command, Path workingDir, int cpuTimeLimitSec)
            throws IOException {
        List<String> wrappedCommand = wrapWithSetsidAndUlimit(command, cpuTimeLimitSec);

        ProcessBuilder pb = new ProcessBuilder(wrappedCommand);
        pb.directory(workingDir.toFile());

        Process process = pb.start();
        long pgid = process.pid(); // setsid execs into cmd → cmd is the session leader → PGID = PID

        log.debug("Launched process pid={} pgid={} cmd={}", process.pid(), pgid, command);
        return new LaunchResult(process, pgid);
    }

    /**
     * Executes the full kill sequence against the process group identified by
     * {@code result.pgid()}.
     *
     * <p>Safe to call on an already-terminated process (idempotent).
     */
    public void kill(LaunchResult result) {
        long pgid = result.pgid();
        ProcessHandle handle = result.process().toHandle();

        log.info("Killing process group pgid={}", pgid);

        // Step 1: graceful stop
        sendSignalToGroup("TERM", pgid);

        // Step 2: grace period
        try {
            Thread.sleep(killGraceMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 3: force kill
        sendSignalToGroup("KILL", pgid);

        // Step 4: descendant sweep — catches any processes that migrated to a different group
        sweepDescendants(handle);
    }

    // --- private helpers ---

    /**
     * Wraps the command inside a {@code setsid /bin/bash} shell that applies
     * {@code ulimit -t} and then exec's into the real command.
     */
    private List<String> wrapWithSetsidAndUlimit(List<String> command, int cpuTimeLimitSec) {
        String cmdStr = command.stream()
                .map(ProcessGroupLauncher::shellQuote)
                .collect(Collectors.joining(" "));

        return List.of(
                "setsid",
                "/bin/bash", "-c",
                "ulimit -t " + cpuTimeLimitSec + "; exec " + cmdStr
        );
    }

    /**
     * Sends {@code signal} to every process in the process group {@code pgid}.
     * Uses {@code kill -<signal> -- -<pgid>} (the {@code --} separates options
     * from arguments; the leading {@code -} before the pgid targets the group).
     *
     * <p>Errors are logged as warnings and not re-thrown because the process
     * group may already be gone.
     */
    private void sendSignalToGroup(String signal, long pgid) {
        try {
            new ProcessBuilder("kill", "-" + signal, "--", "-" + pgid).start();
            log.debug("Sent SIG{} to process group pgid={}", signal, pgid);
        } catch (IOException e) {
            log.warn("Failed to send SIG{} to pgid={}: {}", signal, pgid, e.getMessage());
        }
    }

    /**
     * Forcibly destroys any surviving descendants of {@code root} using
     * the Java {@link ProcessHandle} API.
     */
    private void sweepDescendants(ProcessHandle root) {
        root.descendants().forEach(ph -> {
            if (ph.isAlive()) {
                log.debug("Sweeping survivor descendant pid={}", ph.pid());
                ph.destroyForcibly();
            }
        });
    }

    /**
     * Wraps an argument in single quotes, escaping any embedded single quotes.
     * Safe for passing arbitrary strings as positional arguments to {@code /bin/sh}.
     */
    private static String shellQuote(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
