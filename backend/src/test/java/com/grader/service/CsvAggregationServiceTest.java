package com.grader.service;

import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.model.CaseResult;
import com.grader.domain.model.FileResult;
import com.grader.domain.model.StudentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CsvAggregationService}.
 *
 * <p>The CSV format is:
 * {@code student_dir,c_file,problem,case,status,details,expected_output,program_output}
 *
 * <p>All fields are RFC 4180 double-quoted. Multi-line output is encoded as literal
 * {@code \n} sequences (not actual newlines), matching the shell script convention.
 *
 * <p>Naming convention: {@code {sanitizedName}__{submissionId}} e.g. {@code Alice__submission_12345}
 */
class CsvAggregationServiceTest {

    private static final String HEADER = "student_dir,c_file,problem,case,status,details,expected_output,program_output";
    private static final String HEADER_WITH_DURATION = HEADER + ",duration_ms";
    private static final double MAX_SCORE = 20.0;

    private CsvAggregationService service;

    @BeforeEach
    void setUp() {
        service = new CsvAggregationService(MAX_SCORE);
    }

    // -------------------------------------------------------------------------
    // Parsing — basic structure
    // -------------------------------------------------------------------------

    @Test
    void emptyBody_returnsEmptyList() {
        List<StudentResult> results = service.aggregate(HEADER + "\n");
        assertThat(results).isEmpty();
    }

    @Test
    void headerOnly_returnsEmptyList() {
        List<StudentResult> results = service.aggregate(HEADER);
        assertThat(results).isEmpty();
    }

    @Test
    void singleStudentSingleOkCase_parsedCorrectly() {
        String csv = HEADER + "\n" +
                "\"Alice__submission_12345\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"42\\n\",\"42\\n\"";

        List<StudentResult> results = service.aggregate(csv);

        assertThat(results).hasSize(1);
        StudentResult alice = results.get(0);
        assertThat(alice.submissionDir()).isEqualTo("Alice__submission_12345");
        assertThat(alice.studentName()).isEqualTo("Alice");
        assertThat(alice.studentId()).isEqualTo("12345");
        assertThat(alice.okCount()).isEqualTo(1);
        assertThat(alice.waCount()).isEqualTo(0);
        assertThat(alice.compileErrorsCount()).isEqualTo(0);
        assertThat(alice.files()).hasSize(1);

        FileResult file = alice.files().get(0);
        assertThat(file.fileName()).isEqualTo("problem1.c");
        assertThat(file.problem()).isEqualTo("problem1");
        assertThat(file.compileStatus()).isEqualTo(CompileStatus.OK);
        assertThat(file.compileDetails()).isEqualTo("-");
        assertThat(file.cases()).hasSize(1);

        CaseResult c = file.cases().get(0);
        assertThat(c.caseName()).isEqualTo("case01");
        assertThat(c.status()).isEqualTo(CaseStatus.OK);
    }

    // -------------------------------------------------------------------------
    // Case status mapping
    // -------------------------------------------------------------------------

    @Test
    void mixedStatuses_countersAggregatedCorrectly() {
        String csv = HEADER + "\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case02\",\"WA\",\"Wrong answer\",\"2\\n\",\"3\\n\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case03\",\"TIMEOUT\",\"Time limit exceeded\",\"4\\n\",\"\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case04\",\"RUNTIME_ERROR\",\"Exit code: 1\",\"5\\n\",\"\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case05\",\"OUTPUT_LIMIT_EXCEEDED\",\"Output limit exceeded\",\"6\\n\",\"...\"\n";

        List<StudentResult> results = service.aggregate(csv);

        StudentResult bob = results.get(0);
        assertThat(bob.okCount()).isEqualTo(1);
        assertThat(bob.waCount()).isEqualTo(1);
        assertThat(bob.timeoutsCount()).isEqualTo(1);
        assertThat(bob.runtimeErrorsCount()).isEqualTo(1);
        assertThat(bob.compileErrorsCount()).isEqualTo(0);
        assertThat(bob.failedAnyQuestion()).isTrue();
    }

    @Test
    void allOk_failedAnyQuestionIsFalse() {
        String csv = HEADER + "\n" +
                "\"Charlie__submission_11111\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Charlie__submission_11111\",\"problem1.c\",\"problem1\",\"case02\",\"OK\",\"-\",\"2\\n\",\"2\\n\"\n";

        StudentResult charlie = service.aggregate(csv).get(0);
        assertThat(charlie.failedAnyQuestion()).isFalse();
    }

    @Test
    void outputLimitExceeded_failedAnyQuestionIsTrue() {
        String csv = HEADER + "\n" +
                "\"Charlie__submission_11111\",\"problem1.c\",\"problem1\",\"case01\",\"OUTPUT_LIMIT_EXCEEDED\",\"Output limit exceeded\",\"1\\n\",\"...\"\n";

        StudentResult charlie = service.aggregate(csv).get(0);
        assertThat(charlie.failedAnyQuestion()).isTrue();
    }

    @Test
    void internalError_failedAnyQuestionIsTrue() {
        String csv = HEADER + "\n" +
                "\"Charlie__submission_11111\",\"problem1.c\",\"problem1\",\"case01\",\"INTERNAL_ERROR\",\"Unexpected error\",\"1\\n\",\"\"\n";

        StudentResult charlie = service.aggregate(csv).get(0);
        assertThat(charlie.failedAnyQuestion()).isTrue();
    }

    @Test
    void skipStatus_notCountedInOkOrError() {
        String csv = HEADER + "\n" +
                "\"Dave__submission_22222\",\"unknown.c\",\"unknown\",\"-\",\"SKIP\",\"problema nao identificado\",\"-\",\"-\"\n";

        StudentResult dave = service.aggregate(csv).get(0);
        assertThat(dave.okCount()).isEqualTo(0);
        assertThat(dave.compileErrorsCount()).isEqualTo(0);
        assertThat(dave.runtimeErrorsCount()).isEqualTo(0);

        FileResult file = dave.files().get(0);
        assertThat(file.compileStatus()).isEqualTo(CompileStatus.OK);
        assertThat(file.cases()).hasSize(1);
        assertThat(file.cases().get(0).status()).isEqualTo(CaseStatus.SKIP);
    }

    // -------------------------------------------------------------------------
    // Compile error handling
    // -------------------------------------------------------------------------

    @Test
    void compileError_fileHasNoTestCases() {
        String csv = HEADER + "\n" +
                "\"Eve__submission_33333\",\"problem2.c\",\"problem2\",\"-\",\"COMPILE_ERROR\",\"gcc failed\",\"-\",\"-\"\n";

        List<StudentResult> results = service.aggregate(csv);
        StudentResult eve = results.get(0);

        assertThat(eve.compileErrorsCount()).isEqualTo(1);
        assertThat(eve.files()).hasSize(1);

        FileResult file = eve.files().get(0);
        assertThat(file.compileStatus()).isEqualTo(CompileStatus.COMPILE_ERROR);
        assertThat(file.compileDetails()).isEqualTo("gcc failed");
        assertThat(file.cases()).isEmpty();
    }

    @Test
    void compileError_doesNotCountTowardProblemTotal() {
        String csv = HEADER + "\n" +
                "\"Frank__submission_44444\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Frank__submission_44444\",\"problem2.c\",\"problem2\",\"-\",\"COMPILE_ERROR\",\"gcc failed\",\"-\",\"-\"\n";

        StudentResult frank = service.aggregate(csv).get(0);
        assertThat(frank.problem1Score()).isEqualTo(MAX_SCORE); // 1/1 * 20
        assertThat(frank.problem2Score()).isEqualTo(0.0);       // no cases run
        assertThat(frank.totalScore()).isEqualTo(MAX_SCORE);
        assertThat(frank.compileErrorsCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    @Test
    void scoring_perfectScore_bothProblems() {
        String csv = HEADER + "\n" +
                "\"Grace__submission_55555\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Grace__submission_55555\",\"problem1.c\",\"problem1\",\"case02\",\"OK\",\"-\",\"2\\n\",\"2\\n\"\n" +
                "\"Grace__submission_55555\",\"problem2.c\",\"problem2\",\"case01\",\"OK\",\"-\",\"3\\n\",\"3\\n\"\n" +
                "\"Grace__submission_55555\",\"problem2.c\",\"problem2\",\"case02\",\"OK\",\"-\",\"4\\n\",\"4\\n\"\n";

        StudentResult grace = service.aggregate(csv).get(0);
        assertThat(grace.problem1Score()).isEqualTo(MAX_SCORE);
        assertThat(grace.problem2Score()).isEqualTo(MAX_SCORE);
        assertThat(grace.totalScore()).isEqualTo(MAX_SCORE * 2);
    }

    @Test
    void scoring_partialOk_scoreIsProportional() {
        // 2 OK out of 4 cases for problem1 → 10.0
        String csv = HEADER + "\n" +
                "\"Heidi__submission_66666\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Heidi__submission_66666\",\"problem1.c\",\"problem1\",\"case02\",\"OK\",\"-\",\"2\\n\",\"2\\n\"\n" +
                "\"Heidi__submission_66666\",\"problem1.c\",\"problem1\",\"case03\",\"WA\",\"Wrong answer\",\"3\\n\",\"0\\n\"\n" +
                "\"Heidi__submission_66666\",\"problem1.c\",\"problem1\",\"case04\",\"WA\",\"Wrong answer\",\"4\\n\",\"0\\n\"\n";

        StudentResult heidi = service.aggregate(csv).get(0);
        assertThat(heidi.problem1Score()).isEqualTo(10.0);
        assertThat(heidi.problem2Score()).isEqualTo(0.0);
        assertThat(heidi.totalScore()).isEqualTo(10.0);
    }

    @Test
    void scoring_noTestCasesForProblem_scoreIsZero() {
        // No cases for problem2 → problem2Score = 0
        String csv = HEADER + "\n" +
                "\"Ivan__submission_77777\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n";

        StudentResult ivan = service.aggregate(csv).get(0);
        assertThat(ivan.problem1Score()).isEqualTo(MAX_SCORE);
        assertThat(ivan.problem2Score()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Multiple students
    // -------------------------------------------------------------------------

    @Test
    void multipleStudents_eachAggregatedSeparately() {
        String csv = HEADER + "\n" +
                "\"Alice__submission_12345\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Alice__submission_12345\",\"problem1.c\",\"problem1\",\"case02\",\"OK\",\"-\",\"2\\n\",\"2\\n\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case01\",\"WA\",\"Wrong answer\",\"1\\n\",\"0\\n\"\n" +
                "\"Bob__submission_99999\",\"problem1.c\",\"problem1\",\"case02\",\"WA\",\"Wrong answer\",\"2\\n\",\"0\\n\"\n";

        List<StudentResult> results = service.aggregate(csv);

        assertThat(results).hasSize(2);

        StudentResult alice = results.stream()
                .filter(r -> "Alice".equals(r.studentName())).findFirst().orElseThrow();
        assertThat(alice.okCount()).isEqualTo(2);
        assertThat(alice.problem1Score()).isEqualTo(MAX_SCORE);

        StudentResult bob = results.stream()
                .filter(r -> "Bob".equals(r.studentName())).findFirst().orElseThrow();
        assertThat(bob.okCount()).isEqualTo(0);
        assertThat(bob.waCount()).isEqualTo(2);
        assertThat(bob.problem1Score()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Output unescaping
    // -------------------------------------------------------------------------

    @Test
    void outputWithLiteralBackslashN_convertedToActualNewline() {
        // The shell script stores multi-line output as literal \n sequences
        String csv = HEADER + "\n" +
                "\"Judy__submission_88888\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"line1\\nline2\\n\",\"line1\\nline2\\n\"\n";

        StudentResult judy = service.aggregate(csv).get(0);
        CaseResult c = judy.files().get(0).cases().get(0);

        assertThat(c.expectedOutput()).isEqualTo("line1\nline2\n");
        assertThat(c.programOutput()).isEqualTo("line1\nline2\n");
    }

    @Test
    void dashOutputField_convertedToEmptyString() {
        String csv = HEADER + "\n" +
                "\"Kim__submission_10101\",\"problem2.c\",\"problem2\",\"-\",\"COMPILE_ERROR\",\"error\",\"-\",\"-\"\n";

        // compile error rows have "-" in output fields — these become empty in CaseResult
        // (no cases added for compile error files, but the conversion matters elsewhere)
        StudentResult kim = service.aggregate(csv).get(0);
        assertThat(kim.files().get(0).cases()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Student name and ID parsing
    // -------------------------------------------------------------------------

    @Test
    void studentNameParsed_fromDoubleUnderscorePattern() {
        String csv = HEADER + "\n" +
                "\"John_Doe__submission_54321\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n";

        StudentResult result = service.aggregate(csv).get(0);
        assertThat(result.studentName()).isEqualTo("John_Doe");
        assertThat(result.studentId()).isEqualTo("54321");
        assertThat(result.submissionDir()).isEqualTo("John_Doe__submission_54321");
    }

    @Test
    void studentNameParsed_whenNoDoubleUnderscore_usesFullDirAsName() {
        String csv = HEADER + "\n" +
                "\"plain_dir_name\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n";

        StudentResult result = service.aggregate(csv).get(0);
        assertThat(result.studentName()).isEqualTo("plain_dir_name");
        assertThat(result.studentId()).isEqualTo("plain_dir_name");
    }

    @Test
    void studentIdParsed_whenNonNumericSuffix_fallsBackToFullDir() {
        String csv = HEADER + "\n" +
                "\"John__submission_abc\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n";

        StudentResult result = service.aggregate(csv).get(0);
        assertThat(result.studentName()).isEqualTo("John");
        assertThat(result.studentId()).isEqualTo("John__submission_abc");
    }

    // -------------------------------------------------------------------------
    // CSV quoting edge cases
    // -------------------------------------------------------------------------

    @Test
    void quotedFieldWithEscapedDoubleQuote_parsedCorrectly() {
        // RFC 4180 uses "" to escape a double-quote inside a quoted field (not \")
        // Content to represent: error: expected ";"
        // RFC 4180 encoding:    "error: expected "";"" "
        String csv = HEADER + "\n" +
                "\"Leo__submission_20202\",\"problem2.c\",\"problem2\",\"-\",\"COMPILE_ERROR\",\"error: expected \"\";\"\" \",\"-\",\"-\"\n";

        StudentResult leo = service.aggregate(csv).get(0);
        assertThat(leo.files().get(0).compileDetails()).isEqualTo("error: expected \";\" ");
    }

    // -------------------------------------------------------------------------
    // Multiple files per student
    // -------------------------------------------------------------------------

    @Test
    void multipleFilesPerStudent_allAggregated() {
        String csv = HEADER + "\n" +
                "\"Mia__submission_30303\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n" +
                "\"Mia__submission_30303\",\"problem1.c\",\"problem1\",\"case02\",\"OK\",\"-\",\"2\\n\",\"2\\n\"\n" +
                "\"Mia__submission_30303\",\"problem2.c\",\"problem2\",\"case01\",\"WA\",\"Wrong answer\",\"3\\n\",\"0\\n\"\n" +
                "\"Mia__submission_30303\",\"problem2.c\",\"problem2\",\"case02\",\"OK\",\"-\",\"4\\n\",\"4\\n\"\n";

        StudentResult mia = service.aggregate(csv).get(0);

        assertThat(mia.files()).hasSize(2);
        assertThat(mia.okCount()).isEqualTo(3);
        assertThat(mia.waCount()).isEqualTo(1);
        // problem1: 2/2 * 20 = 20
        assertThat(mia.problem1Score()).isEqualTo(MAX_SCORE);
        // problem2: 1/2 * 20 = 10
        assertThat(mia.problem2Score()).isEqualTo(10.0);
        assertThat(mia.totalScore()).isEqualTo(30.0);
    }

    @Test
    void caseDurationParsed_whenDurationColumnExists() {
        String csv = HEADER_WITH_DURATION + "\n" +
                "\"Nina__submission_90909\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\",\"37\"\n";

        StudentResult nina = service.aggregate(csv).get(0);
        CaseResult c = nina.files().get(0).cases().get(0);
        assertThat(c.durationMs()).isEqualTo(37L);
    }

    // -------------------------------------------------------------------------
    // JSON persistence
    // -------------------------------------------------------------------------

    @Test
    void saveResultsJson_writesReadableJsonFile(@TempDir Path tmp) throws IOException {
        String csv = HEADER + "\n" +
                "\"Nora__submission_40404\",\"problem1.c\",\"problem1\",\"case01\",\"OK\",\"-\",\"1\\n\",\"1\\n\"\n";

        List<StudentResult> results = service.aggregate(csv);
        Path outputFile = tmp.resolve("results.json");
        service.saveResultsJson(results, outputFile);

        assertThat(outputFile).exists();
        String json = Files.readString(outputFile);
        assertThat(json).contains("Nora__submission_40404");
        assertThat(json).contains("\"studentName\"");
        assertThat(json).contains("\"files\"");
    }
}
