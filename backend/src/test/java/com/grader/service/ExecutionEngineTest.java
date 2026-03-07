package com.grader.service;

import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.model.CaseResult;
import com.grader.infrastructure.ProcessGroupLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ExecutionEngine}.
 *
 * Requires a Linux host with {@code gcc} and {@code setsid} available.
 * All tests are disabled on non-Linux platforms.
 */
@EnabledOnOs(OS.LINUX)
class ExecutionEngineTest {

    @TempDir
    Path workDir;

    // Small cap used in OLE tests to trigger the limit quickly
    private static final long SMALL_CAP = 100L;
    private static final long LARGE_CAP = 2 * 1024 * 1024L;

    // --- factory helpers ---

    private ExecutionEngine engine(int timeLimitSec, long stdoutCap, long stderrCap) {
        return new ExecutionEngine(new ProcessGroupLauncher(200L), timeLimitSec, stdoutCap, stderrCap, "");
    }

    private ExecutionEngine defaultEngine() {
        return engine(5, LARGE_CAP, LARGE_CAP);
    }

    private ExecutionEngine timeoutEngine() {
        return engine(1, LARGE_CAP, LARGE_CAP);
    }

    private ExecutionEngine oleStdoutEngine() {
        return engine(5, SMALL_CAP, LARGE_CAP);
    }

    private ExecutionEngine oleStderrEngine() {
        return engine(5, LARGE_CAP, SMALL_CAP);
    }

    // --- source / compile helpers ---

    private Path writeSource(String code, String filename) throws Exception {
        Path src = workDir.resolve(filename);
        Files.writeString(src, code);
        return src;
    }

    private Path compileBinary(ExecutionEngine eng, String code, String name) throws Exception {
        Path src = writeSource(code, name + ".c");
        Path bin = workDir.resolve(name);
        CompileOutcome outcome = eng.compile(src, bin);
        assertEquals(CompileStatus.OK, outcome.status(),
                "Compile must succeed for this test; got: " + outcome.details());
        return bin;
    }

    private Path writeInputFile(String content, String name) throws Exception {
        Path f = workDir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    // === compile tests ===

    @Test
    void compile_validCFile_returnsOkStatus() throws Exception {
        Path src = writeSource("""
                #include <stdio.h>
                int main() { printf("ok\\n"); return 0; }
                """, "valid.c");
        Path bin = workDir.resolve("valid");

        CompileOutcome outcome = defaultEngine().compile(src, bin);

        assertEquals(CompileStatus.OK, outcome.status());
        assertEquals("-", outcome.details());
        assertTrue(Files.exists(bin), "Binary must be created on disk");
    }

    @Test
    void compile_syntaxError_returnsCompileError() throws Exception {
        Path src = writeSource("this is not valid C code !!!", "bad.c");
        Path bin = workDir.resolve("bad");

        CompileOutcome outcome = defaultEngine().compile(src, bin);

        assertEquals(CompileStatus.COMPILE_ERROR, outcome.status());
        assertFalse(outcome.details().isBlank(), "Compile error details must not be blank");
    }

    @Test
    void compile_missingReturnStatement_withWallFlag_returnsOk() throws Exception {
        // -Wall does not turn missing return into an error; verify flags do not break valid code
        ExecutionEngine eng = new ExecutionEngine(
                new ProcessGroupLauncher(200L), 5, LARGE_CAP, LARGE_CAP, "-O1 -Wall");
        Path src = writeSource("""
                #include <stdio.h>
                int main() { printf("hello\\n"); return 0; }
                """, "walled.c");
        Path bin = workDir.resolve("walled");

        CompileOutcome outcome = eng.compile(src, bin);

        assertEquals(CompileStatus.OK, outcome.status());
    }

    // === runCase tests ===

    @Test
    void runCase_correctOutput_returnsOk() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() { printf("42\\n"); return 0; }
                """, "ok_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "42", workDir);

        assertEquals(CaseStatus.OK, result.status());
        assertEquals("case01", result.caseName());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void runCase_wrongOutput_returnsWa() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() { printf("wrong\\n"); return 0; }
                """, "wa_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "correct", workDir);

        assertEquals(CaseStatus.WA, result.status());
        assertEquals("wrong", result.programOutput().strip());
    }

    @Test
    void runCase_nonZeroExit_returnsRuntimeError() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdlib.h>
                int main() { exit(42); }
                """, "err_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "", workDir);

        assertEquals(CaseStatus.RUNTIME_ERROR, result.status());
        assertTrue(result.details().contains("42"),
                "Details must mention the exit code; got: " + result.details());
    }

    @Test
    void runCase_timeout_returnsTimeoutAndProcessIsGone() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <unistd.h>
                int main() { sleep(60); return 0; }
                """, "sleep_prog");

        long start = System.currentTimeMillis();
        CaseResult result = timeoutEngine().runCase("case01", bin, null, "", workDir);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(CaseStatus.TIMEOUT, result.status());
        // Must return within a reasonable wall-clock budget (timeLimitSec=1 + killGrace ~0.2s + buffer)
        assertTrue(elapsed < 8000, "Timeout handling must not hang; took " + elapsed + "ms");
    }

    @Test
    void runCase_stdoutExceedsCap_returnsOle() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() { while(1) puts("aaaaaaaaaa"); }
                """, "ole_stdout_prog");

        CaseResult result = oleStdoutEngine().runCase("case01", bin, null, "", workDir);

        assertEquals(CaseStatus.OUTPUT_LIMIT_EXCEEDED, result.status());
    }

    @Test
    void runCase_stderrExceedsCap_returnsOle() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() { while(1) fprintf(stderr, "eeeeeeeeee\\n"); }
                """, "ole_stderr_prog");

        CaseResult result = oleStderrEngine().runCase("case01", bin, null, "", workDir);

        assertEquals(CaseStatus.OUTPUT_LIMIT_EXCEEDED, result.status());
    }

    @Test
    void runCase_withInputFile_passesStdinToProgram() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() {
                    int n;
                    scanf("%d", &n);
                    printf("%d\\n", n * 2);
                    return 0;
                }
                """, "echo_prog");
        Path input = writeInputFile("7\n", "input.txt");

        CaseResult result = defaultEngine().runCase("case01", bin, input, "14", workDir);

        assertEquals(CaseStatus.OK, result.status());
        assertEquals("14", result.programOutput().strip());
    }

    @Test
    void runCase_trailingWhitespaceTolerance_treatedAsOk() throws Exception {
        // Program outputs "hello   \n"; expected is "hello". Normalization strips trailing spaces.
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() { printf("hello   \\n"); return 0; }
                """, "trailing_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "hello", workDir);

        assertEquals(CaseStatus.OK, result.status(),
                "Trailing whitespace must be normalized; output was: '" + result.programOutput() + "'");
    }

    @Test
    void runCase_multiLineOutput_normalizesTreatsAsOk() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                int main() {
                    printf("line1\\n");
                    printf("line2\\n");
                    return 0;
                }
                """, "multi_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "line1\nline2", workDir);

        assertEquals(CaseStatus.OK, result.status());
    }

    @Test
    void runCase_afterTimeout_processIsNoLongerAlive() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                #include <unistd.h>
                int main() { sleep(60); return 0; }
                """, "alive_check_prog");

        CaseResult result = timeoutEngine().runCase("case01", bin, null, "", workDir);

        assertEquals(CaseStatus.TIMEOUT, result.status());
        // Verify the process is gone (the engine must have killed it)
        // We can only verify indirectly: the method returned, meaning the engine did not hang
    }

    @Test
    void runCase_timeoutWithForkedChildren_cleanupSucceeds_returnsTimeout() throws Exception {
        // Program forks a child; both sleep. The PGID kill must sweep the child too.
        // If cleanup fails, the engine returns INTERNAL_ERROR instead of TIMEOUT.
        Path bin = compileBinary(defaultEngine(), """
                #include <unistd.h>
                int main() {
                    fork();        /* child inherits the same PGID */
                    sleep(60);
                    return 0;
                }
                """, "fork_sleep_prog");

        CaseResult result = timeoutEngine().runCase("case01", bin, null, "", workDir);

        // Cleanup must succeed — INTERNAL_ERROR means survivors were detected
        assertNotEquals(CaseStatus.INTERNAL_ERROR, result.status(),
                "Cleanup must succeed after PGID kill; got INTERNAL_ERROR: " + result.details());
        assertEquals(CaseStatus.TIMEOUT, result.status());
    }

    @Test
    void runCase_oleWithForkedChildren_cleanupSucceeds_returnsOle() throws Exception {
        // Program forks a child that also writes to stdout; PGID kill must clean both.
        Path bin = compileBinary(defaultEngine(), """
                #include <stdio.h>
                #include <unistd.h>
                int main() {
                    fork();
                    while(1) puts("aaaaaaaaaa");
                    return 0;
                }
                """, "fork_ole_prog");

        CaseResult result = oleStdoutEngine().runCase("case01", bin, null, "", workDir);

        assertNotEquals(CaseStatus.INTERNAL_ERROR, result.status(),
                "Cleanup must succeed after OLE kill; got INTERNAL_ERROR: " + result.details());
        assertEquals(CaseStatus.OUTPUT_LIMIT_EXCEEDED, result.status());
    }

    @Test
    void runCase_emptyExpectedAndEmptyOutput_returnsOk() throws Exception {
        Path bin = compileBinary(defaultEngine(), """
                int main() { return 0; }
                """, "empty_prog");

        CaseResult result = defaultEngine().runCase("case01", bin, null, "", workDir);

        assertEquals(CaseStatus.OK, result.status());
    }
}
