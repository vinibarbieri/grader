package com.grader.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.Job;
import com.grader.domain.JobState;
import com.grader.domain.model.CaseResult;
import com.grader.domain.model.StudentResult;
import com.grader.infrastructure.JobRepository;
import com.grader.infrastructure.SafeZipExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Full implementation of {@link JobService}.
 *
 * <p>Orchestrates the complete grading lifecycle:
 * <ol>
 *   <li>ZIP upload, safe extraction into a per-job workspace.</li>
 *   <li>Async evaluation: discover students, compile, run cases, write CSV.</li>
 *   <li>CSV aggregation into {@code results.json} via {@link CsvAggregationService}.</li>
 *   <li>Cooperative cancellation via per-job {@link AtomicBoolean} cancel flags.</li>
 * </ol>
 *
 * <h2>Workspace layout after ZIP extraction</h2>
 * <pre>
 * &lt;workspaceRoot&gt;/&lt;jobId&gt;/
 *   upload.zip
 *   submissions/
 *     &lt;studentDir&gt;/
 *       problem1.c
 *       problem2.c
 *   tests/
 *     problem1/
 *       case01.in  case01.out  ...
 *     problem2/
 *       case01.in  case01.out  ...
 *   results.csv        (generated during evaluation)
 *   results.json       (generated after aggregation)
 * </pre>
 *
 * <h2>Problem detection</h2>
 * Replicates the shell grader's {@code detect_problem()} heuristic:
 * <ol>
 *   <li>Keyword match: {@code problem1}, {@code coding_1}, {@code quiz1problem1},
 *       {@code onlinequiz.c}.</li>
 *   <li>Keyword match: {@code problem2}, {@code coding_2}, {@code quiz1problem2},
 *       {@code onlinequiz2.c}.</li>
 *   <li>Fallback: first occurrence of standalone digit {@code 1} or {@code 2}.</li>
 * </ol>
 *
 * <h2>CSV format</h2>
 * RFC 4180 double-quoted fields; multi-line content uses literal {@code \n} sequences
 * (same convention as the shell grader's {@code file_to_csv_text} awk function).
 * The 9th column {@code duration_ms} (not in the original shell CSV) is appended.
 */
public class JobServiceImpl implements JobService {

    private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    private static final String CSV_HEADER =
            "student_dir,c_file,problem,case,status,details,expected_output,program_output,duration_ms";

    // Problem detection — mirrors the shell script's detect_problem() heuristic
    private static final Pattern PROBLEM1_PATTERN =
            Pattern.compile("problem1|coding_1|quiz1problem1|onlinequiz\\.c");
    private static final Pattern PROBLEM2_PATTERN =
            Pattern.compile("problem2|coding_2|quiz1problem2|onlinequiz2\\.c");
    private static final Pattern DIGIT1_STANDALONE = Pattern.compile("(^|[^0-9])1([^0-9]|$)");
    private static final Pattern DIGIT2_STANDALONE = Pattern.compile("(^|[^0-9])2([^0-9]|$)");

    private final JobRepository jobRepository;
    private final SafeZipExtractor zipExtractor;
    private final ExecutionEngine executionEngine;
    private final CsvAggregationService csvAggregationService;
    private final Path workspaceRoot;
    private final Executor workerPool;

    /**
     * Per-job cancel flags. Set by {@link #cancelJob} and checked by the evaluation
     * thread between students and test cases. Removed when the evaluation task finishes.
     */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JobServiceImpl(JobRepository jobRepository,
                          SafeZipExtractor zipExtractor,
                          ExecutionEngine executionEngine,
                          CsvAggregationService csvAggregationService,
                          Path workspaceRoot,
                          Executor workerPool) {
        this.jobRepository = jobRepository;
        this.zipExtractor = zipExtractor;
        this.executionEngine = executionEngine;
        this.csvAggregationService = csvAggregationService;
        this.workspaceRoot = workspaceRoot;
        this.workerPool = workerPool;
    }

    // -------------------------------------------------------------------------
    // createJob
    // -------------------------------------------------------------------------

    @Override
    public Job createJob(MultipartFile file) {
        Job job = Job.create();
        Path jobDir = jobRepository.getJobDirectory(job.getJobId());

        try {
            // Persist the raw ZIP for audit / re-extraction
            Path zipPath = jobDir.resolve("upload.zip");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Extract ZIP contents (submissions/ + tests/) into the job directory
            zipExtractor.extract(zipPath, jobDir);

            jobRepository.save(job);
            log.info("jobId={} created, uploadedFile={}", job.getJobId(), file.getOriginalFilename());
            return job;

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create job " + job.getJobId(), e);
        }
    }

    // -------------------------------------------------------------------------
    // evaluateJob
    // -------------------------------------------------------------------------

    @Override
    public Job evaluateJob(String jobId) {
        Job job = getJob(jobId);

        if (job.getState() == JobState.RUNNING) {
            // Idempotent — already running
            log.info("jobId={} evaluate requested while already running (idempotent)", jobId);
            return job;
        }

        if (job.getState() != JobState.QUEUED) {
            throw new JobInvalidStateException(jobId, job.getState().name(), "QUEUED");
        }

        job.transitionTo(JobState.RUNNING);
        jobRepository.save(job);
        log.info("jobId={} evaluation submitted, state=running", jobId);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(jobId, cancelFlag);

        workerPool.execute(() -> runEvaluation(jobId, cancelFlag));

        return job;
    }

    // -------------------------------------------------------------------------
    // getJob / getResults / downloadArtifact
    // -------------------------------------------------------------------------

    @Override
    public Job getJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Override
    public List<StudentResult> getResults(String jobId) {
        getJob(jobId); // validate existence
        Path resultsJson = jobRepository.getJobDirectory(jobId).resolve("results.json");

        if (!Files.exists(resultsJson)) {
            return List.of();
        }

        try {
            String content = Files.readString(resultsJson);
            return objectMapper.readValue(
                    content,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, StudentResult.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read results.json for job " + jobId, e);
        }
    }

    @Override
    public byte[] downloadArtifact(String jobId, String format) {
        getJob(jobId); // validate existence
        String fileName = "csv".equals(format) ? "results.csv" : "results.json";
        Path artifact = jobRepository.getJobDirectory(jobId).resolve(fileName);

        if (!Files.exists(artifact)) {
            throw new ArtifactNotFoundException("Artifact not found for job " + jobId + ": " + fileName);
        }

        try {
            return Files.readAllBytes(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact for job " + jobId, e);
        }
    }

    // -------------------------------------------------------------------------
    // cancelJob
    // -------------------------------------------------------------------------

    @Override
    public Job cancelJob(String jobId) {
        Job job = getJob(jobId);

        if (job.getState().isTerminal() || job.getState() == JobState.CANCELLING) {
            log.info("jobId={} cancel requested but already in state={} (idempotent)", jobId, job.getState());
            return job;
        }

        // Signal the evaluation thread (if running)
        AtomicBoolean flag = cancelFlags.get(jobId);
        if (flag != null) {
            flag.set(true);
        }

        job.requestCancel();
        jobRepository.save(job);
        log.info("jobId={} cancel requested, newState={}", jobId, job.getState());
        return job;
    }

    // -------------------------------------------------------------------------
    // Async evaluation
    // -------------------------------------------------------------------------

    private void runEvaluation(String jobId, AtomicBoolean cancelFlag) {
        try {
            Path jobDir = jobRepository.getJobDirectory(jobId);
            Path submissionsDir = jobDir.resolve("submissions");
            Path testsDir = jobDir.resolve("tests");
            Path csvPath = jobDir.resolve("results.csv");

            List<Path> studentDirs = discoverStudentDirs(submissionsDir);
            log.info("jobId={} evaluation started, totalStudents={}", jobId, studentDirs.size());

            updateJob(jobId, j -> j.setTotalStudents(studentDirs.size()));

            try (BufferedWriter csvWriter = Files.newBufferedWriter(
                    csvPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                csvWriter.write(CSV_HEADER);
                csvWriter.newLine();

                for (Path studentDir : studentDirs) {
                    if (cancelFlag.get()) {
                        log.info("jobId={} cancelled before student={}", jobId, studentDir.getFileName());
                        finishCancelled(jobId);
                        return;
                    }

                    String studentDirName = studentDir.getFileName().toString();
                    updateJob(jobId, j -> j.setCurrentStudent(studentDirName));
                    log.info("jobId={} processing student={}", jobId, studentDirName);

                    processStudent(jobId, studentDir, testsDir, csvWriter, cancelFlag);

                    updateJob(jobId, Job::incrementProcessedStudents);
                }
            }

            if (cancelFlag.get()) {
                finishCancelled(jobId);
                return;
            }

            // Aggregate CSV → results.json
            String csvContent = Files.readString(csvPath);
            List<StudentResult> results = csvAggregationService.aggregate(csvContent);
            csvAggregationService.saveResultsJson(results, jobDir.resolve("results.json"));

            updateJob(jobId, j -> j.transitionTo(JobState.DONE));
            log.info("jobId={} evaluation complete, state=done", jobId);

        } catch (Exception e) {
            log.error("jobId={} evaluation failed: {}", jobId, e.getMessage(), e);
            tryTransitionToError(jobId);
        } finally {
            cancelFlags.remove(jobId);
        }
    }

    /**
     * Processes a single student directory: for each {@code .c} file, detect the problem,
     * compile, then run all matching test cases. Writes one CSV row per case (or one row
     * per compile/skip error when no cases are run).
     */
    private void processStudent(String jobId, Path studentDir, Path testsDir,
                                BufferedWriter csvWriter, AtomicBoolean cancelFlag) throws IOException {

        String studentDirName = studentDir.getFileName().toString();
        List<Path> cFiles = listSortedCFiles(studentDir);

        for (Path cFile : cFiles) {
            if (cancelFlag.get()) return;

            String cFileName = cFile.getFileName().toString();
            String problem = detectProblem(cFileName);

            if ("unknown".equals(problem)) {
                log.info("jobId={} student={} file={} SKIP (problem not detected)",
                        jobId, studentDirName, cFileName);
                writeCsvRow(csvWriter, studentDirName, cFileName, "unknown",
                        "-", "SKIP", "problem not detected", "-", "-", 0L);
                continue;
            }

            // Compile
            String baseName = cFileName.endsWith(".c")
                    ? cFileName.substring(0, cFileName.length() - 2)
                    : cFileName;
            Path binary = studentDir.resolve(baseName);

            CompileOutcome compileOutcome;
            try {
                compileOutcome = executionEngine.compile(cFile, binary);
            } catch (Exception e) {
                log.error("jobId={} student={} file={} compile threw: {}",
                        jobId, studentDirName, cFileName, e.getMessage());
                writeCsvRow(csvWriter, studentDirName, cFileName, problem,
                        "-", "COMPILE_ERROR", "Internal error during compilation", "-", "-", 0L);
                updateJob(jobId, j -> j.recordCaseResult(CaseStatus.COMPILE_ERROR));
                continue;
            }

            if (compileOutcome.status() == CompileStatus.COMPILE_ERROR) {
                log.info("jobId={} student={} file={} COMPILE_ERROR", jobId, studentDirName, cFileName);
                writeCsvRow(csvWriter, studentDirName, cFileName, problem,
                        "-", "COMPILE_ERROR", compileOutcome.details(), "-", "-", 0L);
                updateJob(jobId, j -> j.recordCaseResult(CaseStatus.COMPILE_ERROR));
                continue;
            }

            // Run test cases
            Path problemTestDir = testsDir.resolve(problem);
            List<Path> inputFiles = listSortedInputFiles(problemTestDir);

            for (Path inputFile : inputFiles) {
                if (cancelFlag.get()) return;

                String caseName = stripSuffix(inputFile.getFileName().toString(), ".in");
                Path expectedFile = problemTestDir.resolve(caseName + ".out");

                if (!Files.exists(expectedFile)) {
                    log.warn("jobId={} student={} case={} missing .out file, skipping",
                            jobId, studentDirName, caseName);
                    continue;
                }

                String expectedOutput = Files.readString(expectedFile);

                CaseResult result = executionEngine.runCase(
                        caseName, binary, inputFile, expectedOutput, studentDir);

                writeCsvRow(csvWriter, studentDirName, cFileName, problem,
                        caseName, result.status().name(), result.details(),
                        result.expectedOutput(), result.programOutput(), result.durationMs());

                updateJob(jobId, j -> j.recordCaseResult(result.status()));

                log.debug("jobId={} student={} file={} case={} status={} durationMs={}",
                        jobId, studentDirName, cFileName, caseName, result.status(), result.durationMs());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cancel / error helpers
    // -------------------------------------------------------------------------

    /**
     * Transitions the job through CANCELLING → CANCELLED from within the evaluation thread.
     */
    private void finishCancelled(String jobId) {
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            if (job.getState() == JobState.RUNNING) {
                job.transitionTo(JobState.CANCELLING);
                jobRepository.save(job);
                // Reload for the next transition
                job = jobRepository.findById(jobId).orElse(null);
            }
            if (job != null && job.getState() == JobState.CANCELLING) {
                job.transitionTo(JobState.CANCELLED);
                jobRepository.save(job);
            }
            log.info("jobId={} evaluation cancelled, state=cancelled", jobId);
        } catch (Exception e) {
            log.error("jobId={} failed to transition to CANCELLED: {}", jobId, e.getMessage());
        }
    }

    private void tryTransitionToError(String jobId) {
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job != null && !job.getState().isTerminal()) {
                job.transitionTo(JobState.ERROR);
                jobRepository.save(job);
                log.info("jobId={} transitioned to ERROR", jobId);
            }
        } catch (Exception ex) {
            log.error("jobId={} failed to transition to ERROR: {}", jobId, ex.getMessage());
        }
    }

    /**
     * Loads the job, applies {@code updater}, and saves. Used for progress/counter updates
     * from within the evaluation thread.
     */
    private void updateJob(String jobId, Consumer<Job> updater) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        updater.accept(job);
        jobRepository.save(job);
    }

    // -------------------------------------------------------------------------
    // Filesystem helpers
    // -------------------------------------------------------------------------

    private List<Path> discoverStudentDirs(Path submissionsDir) throws IOException {
        if (!Files.exists(submissionsDir) || !Files.isDirectory(submissionsDir)) {
            log.warn("submissions directory not found or not a directory: {}", submissionsDir);
            return List.of();
        }
        try (Stream<Path> stream = Files.list(submissionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private List<Path> listSortedCFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".c"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private List<Path> listSortedInputFiles(Path problemTestDir) throws IOException {
        if (!Files.exists(problemTestDir) || !Files.isDirectory(problemTestDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(problemTestDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".in"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    // -------------------------------------------------------------------------
    // Problem detection
    // -------------------------------------------------------------------------

    String detectProblem(String fileName) {
        String lower = fileName.toLowerCase();
        if (PROBLEM1_PATTERN.matcher(lower).find()) return "problem1";
        if (PROBLEM2_PATTERN.matcher(lower).find()) return "problem2";
        if (DIGIT1_STANDALONE.matcher(lower).find()) return "problem1";
        if (DIGIT2_STANDALONE.matcher(lower).find()) return "problem2";
        return "unknown";
    }

    // -------------------------------------------------------------------------
    // CSV writing
    // -------------------------------------------------------------------------

    private void writeCsvRow(BufferedWriter writer,
                             String studentDir, String cFile, String problem, String caseName,
                             String status, String details,
                             String expectedOutput, String programOutput,
                             long durationMs) throws IOException {
        writer.write(
                escapeForCsv(studentDir) + "," +
                escapeForCsv(cFile) + "," +
                escapeForCsv(problem) + "," +
                escapeForCsv(caseName) + "," +
                escapeForCsv(status) + "," +
                escapeForCsv(details) + "," +
                escapeForCsv(expectedOutput) + "," +
                escapeForCsv(programOutput) + "," +
                durationMs);
        writer.newLine();
    }

    /**
     * RFC 4180 CSV field escaping. Newlines in output values are encoded as literal
     * {@code \n} sequences to keep each CSV row on a single physical line — matching the
     * convention used by the shell grader's {@code file_to_csv_text} function so that
     * {@link CsvAggregationService#aggregate} can decode them with its
     * {@code unescapeOutput} step.
     */
    private String escapeForCsv(String value) {
        if (value == null) value = "";
        // Encode newlines as literal \n (decoded by CsvAggregationService.unescapeOutput)
        String encoded = value.replace("\n", "\\n");
        // RFC 4180: escape embedded double-quotes
        String escaped = encoded.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String stripSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    /**
     * Thrown when a requested download artifact does not exist yet (job not done).
     * Extends {@link RuntimeException} so it propagates through the controller layer.
     */
    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }
}
