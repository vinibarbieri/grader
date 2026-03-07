package com.grader.service;

import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.model.CaseResult;
import com.grader.infrastructure.LaunchResult;
import com.grader.infrastructure.ProcessGroupLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles the compile → run → compare cycle for a single C source file and its test cases.
 *
 * <h2>Compile phase</h2>
 * Invokes {@code gcc} with configurable flags. Returns a {@link CompileOutcome} describing
 * success or failure with the compiler's diagnostic output.
 *
 * <h2>Run phase</h2>
 * Delegates to {@link ProcessGroupLauncher} so each execution runs in a dedicated process
 * group. Enforces:
 * <ul>
 *   <li>wall-clock timeout ({@code timeLimitSec})</li>
 *   <li>stdout / stderr byte caps — exceeding either cap immediately kills the process and
 *       classifies the case as {@code OUTPUT_LIMIT_EXCEEDED}</li>
 * </ul>
 *
 * <h2>Compare phase</h2>
 * Normalizes both expected and actual output (strips trailing whitespace per line and strips
 * trailing newlines) before comparing.
 */
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final ProcessGroupLauncher launcher;
    private final int timeLimitSec;
    private final long stdoutCapBytes;
    private final long stderrCapBytes;
    private final List<String> compileFlags;

    private final AtomicLong cleanupFailuresCount = new AtomicLong();

    public ExecutionEngine(ProcessGroupLauncher launcher,
                           int timeLimitSec,
                           long stdoutCapBytes,
                           long stderrCapBytes,
                           String compileFlags) {
        this.launcher = launcher;
        this.timeLimitSec = timeLimitSec;
        this.stdoutCapBytes = stdoutCapBytes;
        this.stderrCapBytes = stderrCapBytes;
        this.compileFlags = parseFlags(compileFlags);
    }

    // -------------------------------------------------------------------------
    // Compile
    // -------------------------------------------------------------------------

    /**
     * Compiles {@code sourceFile} with {@code gcc} producing {@code outputBinary}.
     *
     * <p>Merges stdout and stderr from the compiler into a single output string used as
     * {@link CompileOutcome#details()} on failure.
     *
     * @param sourceFile   path to the {@code .c} file
     * @param outputBinary destination path for the compiled binary
     * @return compile outcome — never {@code null}
     * @throws IOException          if the gcc process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public CompileOutcome compile(Path sourceFile, Path outputBinary)
            throws IOException, InterruptedException {

        List<String> cmd = buildCompileCommand(sourceFile, outputBinary);
        log.debug("Compiling {} -> {}", sourceFile.getFileName(), outputBinary.getFileName());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // merge stderr into stdout for a single diagnostic stream
        Process p = pb.start();

        // Close stdin — gcc does not need it
        p.getOutputStream().close();

        // Drain output in a background thread so the timeout below is not blocked by readAllBytes().
        // If gcc hangs without closing stdout, readAllBytes() would block forever; the thread
        // approach lets waitFor() enforce the deadline independently.
        ByteArrayOutputStream outputBuf = new ByteArrayOutputStream();
        InputStream procOut = p.getInputStream();
        Thread drainThread = new Thread(() -> {
            try {
                procOut.transferTo(outputBuf);
            } catch (IOException ignored) {}
        });
        drainThread.setDaemon(true);
        drainThread.start();

        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            drainThread.join(2_000);
            log.warn("gcc did not finish within 30s for {}", sourceFile);
            return new CompileOutcome(CompileStatus.COMPILE_ERROR, "Compilation timed out");
        }
        drainThread.join(5_000);
        String output = outputBuf.toString(StandardCharsets.UTF_8);

        if (p.exitValue() == 0) {
            log.debug("Compiled {} successfully", sourceFile.getFileName());
            return new CompileOutcome(CompileStatus.OK, "-");
        }

        log.info("Compile error for {}: {}", sourceFile.getFileName(), output);
        return new CompileOutcome(CompileStatus.COMPILE_ERROR, output);
    }

    // -------------------------------------------------------------------------
    // Run
    // -------------------------------------------------------------------------

    /**
     * Executes {@code binary} for one test case and returns the classified result.
     *
     * <p>Execution is performed inside a dedicated process group via
     * {@link ProcessGroupLauncher}. Stdin is fed from {@code inputFile} (if non-null and
     * exists) in a background thread to avoid deadlocks. Stdout and stderr are drained
     * concurrently with byte caps; the process is killed immediately when either cap is
     * exceeded.
     *
     * @param caseName       identifier used in the returned {@link CaseResult}
     * @param binary         compiled binary to execute
     * @param inputFile      path to the input file; {@code null} means empty stdin
     * @param expectedOutput expected stdout content (used for comparison)
     * @param workDir        working directory for the process
     * @return classified {@link CaseResult} — never {@code null}
     */
    public CaseResult runCase(String caseName,
                              Path binary,
                              Path inputFile,
                              String expectedOutput,
                              Path workDir) {
        long startMs = System.currentTimeMillis();
        LaunchResult launch = null;
        try {
            launch = launcher.launch(List.of(binary.toString()), workDir, timeLimitSec + 1);

            final LaunchResult finalLaunch = launch;
            final Process proc = launch.process();

            // Feed stdin from a daemon thread to prevent deadlock
            Thread stdinThread = buildStdinFeeder(proc.getOutputStream(), inputFile);
            stdinThread.start();

            // AtomicBoolean is effectively final once assigned — safe to capture in lambdas
            AtomicBoolean oleFlag = new AtomicBoolean(false);

            CappedReader stdoutReader = new CappedReader(
                    proc.getInputStream(), stdoutCapBytes,
                    () -> {
                        oleFlag.set(true);
                        launcher.kill(finalLaunch);
                    });
            CappedReader stderrReader = new CappedReader(
                    proc.getErrorStream(), stderrCapBytes,
                    () -> {
                        oleFlag.set(true);
                        launcher.kill(finalLaunch);
                    });
            stdoutReader.start();
            stderrReader.start();

            // Wait for process with wall-clock timeout
            boolean completed = proc.waitFor(timeLimitSec, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (!completed) {
                launcher.kill(finalLaunch);
            }

            // Wait for I/O threads to drain
            stdoutReader.join(5_000);
            stderrReader.join(5_000);
            stdinThread.join(1_000);

            String stdoutStr = stdoutReader.content();

            if (oleFlag.get() || stdoutReader.exceeded() || stderrReader.exceeded()) {
                log.info("[{}] OLE for case={}", binary.getFileName(), caseName);
                if (!verifyCleanup(launch)) {
                    log.error("[{}] INTERNAL_ERROR: cleanup failed after OLE for case={}",
                            binary.getFileName(), caseName);
                    return new CaseResult(caseName, CaseStatus.INTERNAL_ERROR,
                            "Cleanup failed: survivor processes remain after OLE kill",
                            expectedOutput, stdoutStr, durationMs);
                }
                return new CaseResult(caseName, CaseStatus.OUTPUT_LIMIT_EXCEEDED,
                        "Output limit exceeded", expectedOutput, stdoutStr, durationMs);
            }

            if (!completed) {
                log.info("[{}] TIMEOUT for case={}", binary.getFileName(), caseName);
                if (!verifyCleanup(launch)) {
                    log.error("[{}] INTERNAL_ERROR: cleanup failed after timeout for case={}",
                            binary.getFileName(), caseName);
                    return new CaseResult(caseName, CaseStatus.INTERNAL_ERROR,
                            "Cleanup failed: survivor processes remain after timeout kill",
                            expectedOutput, stdoutStr, durationMs);
                }
                return new CaseResult(caseName, CaseStatus.TIMEOUT,
                        "Time limit exceeded", expectedOutput, stdoutStr, durationMs);
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                log.info("[{}] RUNTIME_ERROR for case={} exitCode={}", binary.getFileName(), caseName, exitCode);
                return new CaseResult(caseName, CaseStatus.RUNTIME_ERROR,
                        "Exit code: " + exitCode, expectedOutput, stdoutStr, durationMs);
            }

            // Compare (normalized)
            String normalizedExpected = normalize(expectedOutput);
            String normalizedActual = normalize(stdoutStr);
            if (normalizedExpected.equals(normalizedActual)) {
                return new CaseResult(caseName, CaseStatus.OK, "-", expectedOutput, stdoutStr, durationMs);
            } else {
                log.debug("[{}] WA for case={}", binary.getFileName(), caseName);
                return new CaseResult(caseName, CaseStatus.WA, "Wrong answer",
                        expectedOutput, stdoutStr, durationMs);
            }

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("[{}] INTERNAL_ERROR for case={}: {}", binary.getFileName(), caseName, e.getMessage(), e);
            if (launch != null) {
                launcher.kill(launch);
            }
            return new CaseResult(caseName, CaseStatus.INTERNAL_ERROR,
                    e.getMessage(), expectedOutput, "", durationMs);
        }
    }

    // -------------------------------------------------------------------------
    // Metrics accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of PGID kill sequences issued by the underlying launcher.
     * This is a global counter across all jobs processed by this engine instance.
     */
    public long getKilledProcessesCount() {
        return launcher.getKilledProcessesCount();
    }

    /**
     * Returns the total number of cases where post-kill cleanup verification failed.
     * A non-zero count indicates potential process leaks that warrant investigation.
     */
    public long getCleanupFailuresCount() {
        return cleanupFailuresCount.get();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> buildCompileCommand(Path sourceFile, Path outputBinary) {
        List<String> cmd = new ArrayList<>();
        cmd.add("gcc");
        cmd.addAll(compileFlags);
        cmd.add(sourceFile.toString());
        cmd.add("-o");
        cmd.add(outputBinary.toString());
        return cmd;
    }

    private static List<String> parseFlags(String flagsStr) {
        if (flagsStr == null || flagsStr.isBlank()) {
            return List.of();
        }
        return Arrays.asList(flagsStr.trim().split("\\s+"));
    }

    /**
     * Verifies that the process and all its known descendants are dead after a kill.
     *
     * <p>Called after every forced-kill path (timeout, OLE) to satisfy the PRD requirement
     * of "verify cleanup completed before next case". If survivors are detected the caller
     * should classify the case as {@link CaseStatus#INTERNAL_ERROR} because host safety
     * cannot be guaranteed.
     *
     * @param launch the launch result whose process group was just killed
     * @return {@code true} if no survivors remain, {@code false} otherwise
     */
    private boolean verifyCleanup(LaunchResult launch) {
        if (launch.process().isAlive()) {
            log.warn("Cleanup verification failed: main process pid={} still alive after kill",
                    launch.process().pid());
            cleanupFailuresCount.incrementAndGet();
            return false;
        }
        long survivors = launch.process().toHandle()
                .descendants()
                .filter(ProcessHandle::isAlive)
                .count();
        if (survivors > 0) {
            log.warn("Cleanup verification failed: {} survivor(s) remain for pgid={}",
                    survivors, launch.pgid());
            cleanupFailuresCount.incrementAndGet();
            return false;
        }
        return true;
    }

    private Thread buildStdinFeeder(OutputStream stdin, Path inputFile) {
        Thread t = new Thread(() -> {
            try (OutputStream os = stdin) {
                if (inputFile != null && Files.exists(inputFile)) {
                    Files.copy(inputFile, os);
                }
            } catch (IOException ignored) {
                // Process may exit before all input is consumed — safe to ignore
            }
        });
        t.setDaemon(true);
        t.setName("stdin-feeder");
        return t;
    }

    /**
     * Normalizes output for comparison: strips trailing whitespace from each line
     * and strips trailing newlines from the entire output.
     */
    static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.stripTrailing()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // -------------------------------------------------------------------------
    // CappedReader
    // -------------------------------------------------------------------------

    /**
     * A daemon thread that drains an {@link InputStream} into an in-memory buffer up to
     * {@code cap} bytes. When the cap is exceeded the {@code onCapExceeded} callback is
     * invoked once, then remaining bytes are drained (without storing) to unblock the
     * writing process until it is killed.
     */
    private static class CappedReader extends Thread {

        private final InputStream in;
        private final long cap;
        private final Runnable onCapExceeded;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean exceeded = false;

        CappedReader(InputStream in, long cap, Runnable onCapExceeded) {
            this.in = in;
            this.cap = cap;
            this.onCapExceeded = onCapExceeded;
            setDaemon(true);
            setName("capped-reader");
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            long total = 0;
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    total += n;
                    if (total > cap) {
                        exceeded = true;
                        onCapExceeded.run();
                        // Drain remaining without buffering so the writer can exit after kill
                        //noinspection StatementWithEmptyBody
                        while (in.read(chunk) != -1) { /* drain */ }
                        return;
                    }
                    buf.write(chunk, 0, n);
                }
            } catch (IOException ignored) {
                // Stream closed when process exits — normal termination path
            }
        }

        /** Returns the buffered output as a UTF-8 string (up to cap bytes). */
        public String content() {
            return buf.toString(StandardCharsets.UTF_8);
        }

        /** Returns {@code true} if the cap was exceeded during reading. */
        public boolean exceeded() {
            return exceeded;
        }
    }
}
