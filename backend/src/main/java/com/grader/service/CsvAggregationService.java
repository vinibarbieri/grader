package com.grader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.model.CaseResult;
import com.grader.domain.model.FileResult;
import com.grader.domain.model.StudentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the grader CSV report and aggregates results into {@link StudentResult} objects.
 *
 * <h2>Expected CSV header</h2>
 * {@code student_dir,c_file,problem,case,status,details,expected_output,program_output}
 *
 * <p>Fields are RFC 4180 double-quote enclosed. Multi-line output content uses literal
 * {@code \n} sequences (as produced by the shell-based grader's {@code file_to_csv_text}
 * function) — these are decoded back to actual newlines during parsing.
 *
 * <h2>Naming convention</h2>
 * {@code {sanitizedName}__{submissionId}} e.g. {@code Alice__submission_12345}
 *
 * <h2>Scoring formula</h2>
 * {@code score = (okCount / totalCases) * maxScorePerProblem} per problem.
 * If a problem has no executed cases (e.g. compile error), its score is 0.
 */
public class CsvAggregationService {

    private static final Logger log = LoggerFactory.getLogger(CsvAggregationService.class);

    // Column indices in the CSV
    private static final int COL_STUDENT_DIR = 0;
    private static final int COL_C_FILE = 1;
    private static final int COL_PROBLEM = 2;
    private static final int COL_CASE = 3;
    private static final int COL_STATUS = 4;
    private static final int COL_DETAILS = 5;
    private static final int COL_EXPECTED = 6;
    private static final int COL_PROGRAM = 7;

    private final double maxScorePerProblem;
    private final ObjectMapper mapper;

    public CsvAggregationService(double maxScorePerProblem) {
        this.maxScorePerProblem = maxScorePerProblem;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses {@code csvContent} and returns one {@link StudentResult} per unique
     * {@code student_dir} value, preserving the row order of first occurrence.
     *
     * @param csvContent full CSV text including the header row
     * @return aggregated results — never {@code null}
     */
    public List<StudentResult> aggregate(String csvContent) {
        List<String[]> rows = parseCsv(csvContent);

        // Group rows by student_dir, preserving insertion order
        Map<String, List<String[]>> byStudent = new LinkedHashMap<>();
        for (String[] row : rows) {
            if (row.length <= COL_STATUS) {
                log.warn("Skipping malformed CSV row with {} columns", row.length);
                continue;
            }
            byStudent.computeIfAbsent(row[COL_STUDENT_DIR], k -> new ArrayList<>()).add(row);
        }

        List<StudentResult> results = new ArrayList<>(byStudent.size());
        for (Map.Entry<String, List<String[]>> entry : byStudent.entrySet()) {
            results.add(buildStudentResult(entry.getKey(), entry.getValue()));
        }
        log.debug("Aggregated {} student results from CSV", results.size());
        return results;
    }

    /**
     * Serializes {@code results} to a pretty-printed JSON file at {@code outputPath}.
     *
     * @param results    aggregated results to persist
     * @param outputPath destination file path; parent directories must exist
     * @throws UncheckedIOException if the file cannot be written
     */
    public void saveResultsJson(List<StudentResult> results, Path outputPath) {
        try {
            mapper.writeValue(outputPath.toFile(), results);
            log.info("Saved results.json with {} students to {}", results.size(), outputPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write results.json to " + outputPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // CSV parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the full CSV text (skipping the header row) into a list of field arrays.
     *
     * <p>Uses a simple RFC 4180 parser that handles quoted fields and {@code ""} escaping.
     * This assumes the format produced by the shell grader where newlines inside values
     * are encoded as literal {@code \n} — so actual newlines only appear between rows.
     */
    List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = content.split("\r?\n", -1);
        boolean firstLine = true;
        for (String line : lines) {
            if (firstLine) {
                firstLine = false;
                continue; // skip header
            }
            if (line.isBlank()) continue;
            rows.add(parseCsvLine(line));
        }
        return rows;
    }

    /**
     * Parses a single RFC 4180 CSV line into an array of field values.
     * Handles quoted fields and {@code ""} as an escaped double-quote character.
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (true) {
            if (i < len && line.charAt(i) == '"') {
                // Quoted field
                StringBuilder sb = new StringBuilder();
                i++; // skip opening quote
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            // Escaped double-quote
                            sb.append('"');
                            i += 2;
                        } else {
                            i++; // skip closing quote
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
            } else {
                // Unquoted field (or empty field after trailing comma)
                int start = i;
                while (i < len && line.charAt(i) != ',') i++;
                fields.add(line.substring(start, i));
            }

            // After each field: either a comma (more fields follow) or end of line
            if (i >= len) break;
            if (line.charAt(i) == ',') {
                i++; // consume comma, loop for next field
            } else {
                break; // unexpected character — stop
            }
        }

        return fields.toArray(new String[0]);
    }

    // -------------------------------------------------------------------------
    // Aggregation
    // -------------------------------------------------------------------------

    private StudentResult buildStudentResult(String submissionDir, List<String[]> rows) {
        String studentName = parseStudentName(submissionDir);
        String studentId = parseStudentId(submissionDir);

        // Group by c_file, preserving order
        Map<String, List<String[]>> byFile = new LinkedHashMap<>();
        for (String[] row : rows) {
            byFile.computeIfAbsent(row[COL_C_FILE], k -> new ArrayList<>()).add(row);
        }

        List<FileResult> files = new ArrayList<>(byFile.size());
        for (Map.Entry<String, List<String[]>> entry : byFile.entrySet()) {
            files.add(buildFileResult(entry.getKey(), entry.getValue()));
        }

        // Student-level counters
        int compileErrorsCount = 0;
        int runtimeErrorsCount = 0;
        int timeoutsCount = 0;
        int waCount = 0;
        int okCount = 0;

        // Per-problem stats for scoring — only executeable cases count toward totals
        int p1Total = 0, p1Ok = 0;
        int p2Total = 0, p2Ok = 0;

        for (FileResult file : files) {
            if (file.compileStatus() == CompileStatus.COMPILE_ERROR) {
                compileErrorsCount++;
            }
            String problem = file.problem();
            for (CaseResult cr : file.cases()) {
                switch (cr.status()) {
                    case OK -> {
                        okCount++;
                        if ("problem1".equals(problem)) { p1Total++; p1Ok++; }
                        else if ("problem2".equals(problem)) { p2Total++; p2Ok++; }
                    }
                    case WA -> {
                        waCount++;
                        if ("problem1".equals(problem)) p1Total++;
                        else if ("problem2".equals(problem)) p2Total++;
                    }
                    case RUNTIME_ERROR -> {
                        runtimeErrorsCount++;
                        if ("problem1".equals(problem)) p1Total++;
                        else if ("problem2".equals(problem)) p2Total++;
                    }
                    case TIMEOUT -> {
                        timeoutsCount++;
                        if ("problem1".equals(problem)) p1Total++;
                        else if ("problem2".equals(problem)) p2Total++;
                    }
                    case OUTPUT_LIMIT_EXCEEDED -> {
                        // Counted toward problem total but tracked separately
                        if ("problem1".equals(problem)) p1Total++;
                        else if ("problem2".equals(problem)) p2Total++;
                    }
                    default -> {
                        // SKIP, INTERNAL_ERROR, COMPILE_ERROR in case position —
                        // not counted toward executable totals
                    }
                }
            }
        }

        double problem1Score = p1Total > 0 ? ((double) p1Ok / p1Total) * maxScorePerProblem : 0.0;
        double problem2Score = p2Total > 0 ? ((double) p2Ok / p2Total) * maxScorePerProblem : 0.0;
        double totalScore = problem1Score + problem2Score;
        boolean failedAnyQuestion = compileErrorsCount > 0 || runtimeErrorsCount > 0
                || timeoutsCount > 0 || waCount > 0;

        return new StudentResult(
                studentId,
                studentName,
                submissionDir,
                compileErrorsCount,
                runtimeErrorsCount,
                timeoutsCount,
                waCount,
                okCount,
                problem1Score,
                problem2Score,
                totalScore,
                failedAnyQuestion,
                files
        );
    }

    /**
     * Builds a {@link FileResult} from the rows belonging to a single {@code c_file}.
     *
     * <p>If any row has {@code status=COMPILE_ERROR} and {@code case="-"}, the file is
     * considered a compile failure and its {@code cases} list is empty. Otherwise, every
     * row becomes one {@link CaseResult}.
     */
    private FileResult buildFileResult(String fileName, List<String[]> rows) {
        String problem = rows.get(0)[COL_PROBLEM];

        boolean hasCompileError = rows.stream()
                .anyMatch(r -> "COMPILE_ERROR".equals(safeGet(r, COL_STATUS))
                        && "-".equals(safeGet(r, COL_CASE)));

        if (hasCompileError) {
            String compileDetails = rows.stream()
                    .filter(r -> "COMPILE_ERROR".equals(safeGet(r, COL_STATUS)))
                    .findFirst()
                    .map(r -> safeGet(r, COL_DETAILS))
                    .orElse("-");
            return new FileResult(fileName, problem, CompileStatus.COMPILE_ERROR, compileDetails, List.of());
        }

        List<CaseResult> cases = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            String caseName = safeGet(row, COL_CASE);
            String statusStr = safeGet(row, COL_STATUS);
            String details = safeGet(row, COL_DETAILS);
            String expectedOutput = unescapeOutput(safeGet(row, COL_EXPECTED));
            String programOutput = unescapeOutput(safeGet(row, COL_PROGRAM));

            CaseStatus status;
            try {
                status = CaseStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown case status '{}' in CSV for file={} case={}", statusStr, fileName, caseName);
                status = CaseStatus.INTERNAL_ERROR;
            }

            cases.add(new CaseResult(caseName, status, details, expectedOutput, programOutput, 0L));
        }

        return new FileResult(fileName, problem, CompileStatus.OK, "-", cases);
    }

    // -------------------------------------------------------------------------
    // Output field helpers
    // -------------------------------------------------------------------------

    /**
     * Converts literal {@code \n} sequences (as written by the shell script's
     * {@code awk '{printf "%s\\n", $0}'}) back to actual newline characters.
     * A bare {@code "-"} value is treated as an empty/absent output.
     */
    private String unescapeOutput(String raw) {
        if ("-".equals(raw)) return "";
        return raw.replace("\\n", "\n");
    }

    private String safeGet(String[] row, int index) {
        return (index < row.length) ? row[index] : "";
    }

    // -------------------------------------------------------------------------
    // Student name / ID parsing
    // -------------------------------------------------------------------------

    /**
     * Extracts the student name from a submission directory name.
     *
     * <p>Expected pattern: {@code {studentName}__{submissionId}} e.g.
     * {@code Alice__submission_12345} → {@code "Alice"}.
     * Falls back to the full directory name if the pattern is not found.
     */
    private String parseStudentName(String submissionDir) {
        int idx = submissionDir.indexOf("__");
        if (idx > 0) {
            return submissionDir.substring(0, idx);
        }
        return submissionDir;
    }

    /**
     * Extracts the numeric student ID from a submission directory name.
     *
     * <p>Expected pattern: {@code {name}__submission_{id}} e.g.
     * {@code Alice__submission_12345} → {@code "12345"}.
     * If the numeric ID cannot be extracted, returns the part after {@code __}
     * or the full directory name.
     */
    private String parseStudentId(String submissionDir) {
        int idx = submissionDir.indexOf("__");
        if (idx > 0) {
            String rest = submissionDir.substring(idx + 2); // e.g. "submission_12345"
            int lastUnderscore = rest.lastIndexOf('_');
            if (lastUnderscore >= 0 && lastUnderscore < rest.length() - 1) {
                String candidate = rest.substring(lastUnderscore + 1);
                if (candidate.matches("\\d+")) {
                    return candidate;
                }
            }
            return rest;
        }
        return submissionDir;
    }
}
