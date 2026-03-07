package com.grader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grader.domain.CaseStatus;
import com.grader.domain.CompileStatus;
import com.grader.domain.Job;
import com.grader.domain.JobState;
import com.grader.domain.model.CaseResult;
import com.grader.domain.model.FileResult;
import com.grader.domain.model.StudentResult;
import com.grader.infrastructure.FilesystemJobRepository;
import com.grader.infrastructure.JobRepository;
import com.grader.infrastructure.SafeZipExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link JobServiceImpl}.
 *
 * <p>Unit tests use Mockito mocks for all dependencies. The integration test
 * ({@code evaluate_fullFlow_writesCSVAndResultsJson_andTransitionsToDone})
 * uses a real {@link FilesystemJobRepository} with a temp directory, with only
 * {@link ExecutionEngine} and {@link CsvAggregationService} mocked.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    /** Runs submitted tasks synchronously — makes async code testable without sleeps. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Mock
    JobRepository mockRepo;
    @Mock
    SafeZipExtractor mockZipExtractor;
    @Mock
    ExecutionEngine mockEngine;
    @Mock
    CsvAggregationService mockCsvService;

    private Path tempWorkspace;
    private JobServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        tempWorkspace = Files.createTempDirectory("grader-step9-test-");
        service = new JobServiceImpl(
                mockRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, DIRECT_EXECUTOR);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempWorkspace);
    }

    // -------------------------------------------------------------------------
    // createJob
    // -------------------------------------------------------------------------

    @Test
    void createJob_savesJobAsQueued_andExtractsZip() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.zip", "application/zip", "zip-content".getBytes());

        when(mockRepo.getJobDirectory(anyString())).thenAnswer(inv -> {
            Path dir = tempWorkspace.resolve(inv.<String>getArgument(0));
            Files.createDirectories(dir);
            return dir;
        });

        Job result = service.createJob(file);

        assertThat(result.getState()).isEqualTo(JobState.QUEUED);
        verify(mockRepo).save(any(Job.class));
        verify(mockZipExtractor).extract(any(Path.class), any(Path.class));
    }

    @Test
    void createJob_throwsRuntimeException_whenExtractionFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.zip", "application/zip", "zip-content".getBytes());

        when(mockRepo.getJobDirectory(anyString())).thenAnswer(inv -> {
            Path dir = tempWorkspace.resolve(inv.<String>getArgument(0));
            Files.createDirectories(dir);
            return dir;
        });
        doThrow(new IOException("bad zip")).when(mockZipExtractor).extract(any(), any());

        assertThatThrownBy(() -> service.createJob(file))
                .isInstanceOf(RuntimeException.class);
    }

    // -------------------------------------------------------------------------
    // evaluateJob
    // -------------------------------------------------------------------------

    @Test
    void evaluateJob_transitionsToRunning_whenQueued() {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        // Use a no-op executor so the async task never runs — we test only the transition
        Executor noOp = task -> { /* don't run */ };
        JobServiceImpl svc = new JobServiceImpl(
                mockRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, noOp);

        Job result = svc.evaluateJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.RUNNING);
        verify(mockRepo, atLeastOnce()).save(any(Job.class));
    }

    @Test
    void evaluateJob_isIdempotent_whenAlreadyRunning() {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.evaluateJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.RUNNING);
        // No save — state unchanged
        verify(mockRepo, never()).save(any());
    }

    @Test
    void evaluateJob_throwsJobNotFoundException_whenJobMissing() {
        when(mockRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateJob("ghost"))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void evaluateJob_throwsJobInvalidStateException_whenJobIsDone() {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.DONE);
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.evaluateJob(job.getJobId()))
                .isInstanceOf(JobInvalidStateException.class);
    }

    @Test
    void evaluateJob_throwsJobBusyException_whenAnotherJobIsActive() {
        Job firstJob = Job.create();
        Job secondJob = Job.create();
        Map<String, Job> jobsById = new HashMap<>();
        jobsById.put(firstJob.getJobId(), firstJob);
        jobsById.put(secondJob.getJobId(), secondJob);

        when(mockRepo.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(jobsById.get(invocation.<String>getArgument(0))));

        // Keep first job as RUNNING without executing evaluation thread.
        Executor noOp = task -> { /* don't run */ };
        JobServiceImpl svc = new JobServiceImpl(
                mockRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, noOp);

        svc.evaluateJob(firstJob.getJobId());

        assertThatThrownBy(() -> svc.evaluateJob(secondJob.getJobId()))
                .isInstanceOf(JobBusyException.class);
    }

    // -------------------------------------------------------------------------
    // getJob
    // -------------------------------------------------------------------------

    @Test
    void getJob_throwsJobNotFoundException_whenMissing() {
        when(mockRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJob("ghost"))
                .isInstanceOf(JobNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // getResults
    // -------------------------------------------------------------------------

    @Test
    void getResults_returnsEmptyList_whenResultsJsonDoesNotExist() {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        when(mockRepo.getJobDirectory(anyString())).thenReturn(tempWorkspace);

        List<StudentResult> results = service.getResults(job.getJobId());

        assertThat(results).isEmpty();
    }

    @Test
    void getResults_returnsResults_whenResultsJsonExists() throws IOException {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        when(mockRepo.getJobDirectory(anyString())).thenReturn(tempWorkspace);

        // Write a valid results.json with one student
        List<StudentResult> expected = List.of(
                new StudentResult("001", "Alice", "Alice__submission_001",
                        0, 0, 0, 0, 2, 20.0, 0.0, 20.0, false,
                        List.of(new FileResult("problem1.c", "problem1",
                                CompileStatus.OK, "-",
                                List.of(new CaseResult("case01", CaseStatus.OK, "-", "1\n", "1\n", 10L))))));
        new ObjectMapper().writeValue(tempWorkspace.resolve("results.json").toFile(), expected);

        List<StudentResult> results = service.getResults(job.getJobId());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).studentName()).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // downloadArtifact
    // -------------------------------------------------------------------------

    @Test
    void downloadArtifact_returnsCsvBytes_whenFileExists() throws IOException {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        when(mockRepo.getJobDirectory(anyString())).thenReturn(tempWorkspace);
        byte[] csv = "header\nrow".getBytes();
        Files.write(tempWorkspace.resolve("results.csv"), csv);

        byte[] result = service.downloadArtifact(job.getJobId(), "csv");

        assertThat(result).isEqualTo(csv);
    }

    @Test
    void downloadArtifact_returnsJsonBytes_whenFileExists() throws IOException {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        when(mockRepo.getJobDirectory(anyString())).thenReturn(tempWorkspace);
        byte[] json = "[]".getBytes();
        Files.write(tempWorkspace.resolve("results.json"), json);

        byte[] result = service.downloadArtifact(job.getJobId(), "json");

        assertThat(result).isEqualTo(json);
    }

    @Test
    void downloadArtifact_throwsRuntimeException_whenFileNotFound() {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));
        when(mockRepo.getJobDirectory(anyString())).thenReturn(tempWorkspace);

        assertThatThrownBy(() -> service.downloadArtifact(job.getJobId(), "csv"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    // -------------------------------------------------------------------------
    // cancelJob
    // -------------------------------------------------------------------------

    @Test
    void cancelJob_transitionsToCancelled_whenJobIsQueued() {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.cancelJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.CANCELLED);
        verify(mockRepo).save(any(Job.class));
    }

    @Test
    void cancelJob_transitionsToCancelling_whenJobIsRunning() {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.cancelJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.CANCELLING);
        verify(mockRepo).save(any(Job.class));
    }

    @Test
    void cancelJob_isIdempotent_whenJobIsDone() {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.DONE);
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.cancelJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.DONE);
        verify(mockRepo, never()).save(any());
    }

    @Test
    void cancelJob_isIdempotent_whenJobIsCancelled() {
        Job job = Job.create();
        job.requestCancel(); // QUEUED -> CANCELLED
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.cancelJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.CANCELLED);
        verify(mockRepo, never()).save(any());
    }

    @Test
    void cancelJob_isIdempotent_whenJobIsCancelling() {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.CANCELLING);
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        Job result = service.cancelJob(job.getJobId());

        assertThat(result.getState()).isEqualTo(JobState.CANCELLING);
        verify(mockRepo, never()).save(any());
    }

    @Test
    void cancelJob_throwsJobNotFoundException_whenJobMissing() {
        when(mockRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelJob("ghost"))
                .isInstanceOf(JobNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Full evaluation flow — integration test with real filesystem
    // -------------------------------------------------------------------------

    @Test
    void evaluate_fullFlow_writesCSVAndResultsJson_andTransitionsToDone() throws Exception {
        // Create a real job workspace structure
        Path jobId = Path.of("integration-job-001");
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);

        // submissions/Alice__submission_001/problem1.c
        Path submissionsDir = jobDir.resolve("submissions");
        Path studentDir = submissionsDir.resolve("Alice__submission_001");
        Files.createDirectories(studentDir);
        Files.writeString(studentDir.resolve("problem1.c"), "int main() { return 0; }");

        // tests/problem1/case01.in + case01.out
        Path testsDir = jobDir.resolve("tests");
        Path problem1Dir = testsDir.resolve("problem1");
        Files.createDirectories(problem1Dir);
        Files.writeString(problem1Dir.resolve("case01.in"), "");
        Files.writeString(problem1Dir.resolve("case01.out"), "42\n");

        // Real repository — persists job state to disk
        FilesystemJobRepository realRepo = new FilesystemJobRepository(tempWorkspace.toString());
        Job initialJob = new Job("integration-job-001");
        realRepo.save(initialJob);

        // Mock engine returns a fixed OK result
        CompileOutcome compileOk = new CompileOutcome(CompileStatus.OK, "-");
        CaseResult caseOk = new CaseResult("case01", CaseStatus.OK, "-", "42\n", "42\n", 15L);
        when(mockEngine.compile(any(Path.class), any(Path.class))).thenReturn(compileOk);
        when(mockEngine.runCase(anyString(), any(Path.class), any(), anyString(), any()))
                .thenReturn(caseOk);

        // Mock aggregation returns a stub result list
        List<StudentResult> stubbedResults = List.of(
                new StudentResult("001", "Alice", "Alice__submission_001",
                        0, 0, 0, 0, 1, 20.0, 0.0, 20.0, false, List.of()));
        when(mockCsvService.aggregate(anyString())).thenReturn(stubbedResults);

        // Build service with real repo + direct executor (synchronous)
        JobServiceImpl svc = new JobServiceImpl(
                realRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, DIRECT_EXECUTOR);

        // Trigger evaluation (runs synchronously via DIRECT_EXECUTOR)
        svc.evaluateJob("integration-job-001");

        // After synchronous execution the job on disk must be DONE
        Job finalJob = realRepo.findById("integration-job-001").orElseThrow();
        assertThat(finalJob.getState()).isEqualTo(JobState.DONE);
        assertThat(finalJob.getTotalStudents()).isEqualTo(1);
        assertThat(finalJob.getProcessedStudents()).isEqualTo(1);

        // CSV file must exist
        assertThat(Files.exists(jobDir.resolve("results.csv"))).isTrue();

        // Verify aggregation + persistence were invoked
        verify(mockCsvService).aggregate(anyString());
        verify(mockCsvService).saveResultsJson(any(), any(Path.class));
    }

    @Test
    void evaluate_transitionsToDone_whenSubmissionsDirIsMissing() throws Exception {
        // Job dir exists but no submissions/ subdir
        Path jobDir = tempWorkspace.resolve("no-subs-job");
        Files.createDirectories(jobDir);

        FilesystemJobRepository realRepo = new FilesystemJobRepository(tempWorkspace.toString());
        Job initialJob = new Job("no-subs-job");
        realRepo.save(initialJob);

        when(mockCsvService.aggregate(anyString())).thenReturn(List.of());

        JobServiceImpl svc = new JobServiceImpl(
                realRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, DIRECT_EXECUTOR);

        svc.evaluateJob("no-subs-job");

        // No submissions → evaluation completes with DONE (zero students processed)
        Job finalJob = realRepo.findById("no-subs-job").orElseThrow();
        assertThat(finalJob.getState()).isEqualTo(JobState.DONE);
        assertThat(finalJob.getTotalStudents()).isEqualTo(0);
    }

    @Test
    void evaluate_queued_cancel_preventsEvaluation() {
        Job job = Job.create();
        when(mockRepo.findById(anyString())).thenReturn(Optional.of(job));

        // Cancel the QUEUED job before evaluate
        service.cancelJob(job.getJobId());

        // Trying to evaluate a CANCELLED job must throw
        assertThatThrownBy(() -> service.evaluateJob(job.getJobId()))
                .isInstanceOf(JobInvalidStateException.class);
    }

    @Test
    void evaluate_skipsUnknownProblemFile_writesSkipRow() throws Exception {
        Path jobDir = tempWorkspace.resolve("skip-job");
        Files.createDirectories(jobDir);

        Path studentDir = jobDir.resolve("submissions").resolve("Bob__submission_002");
        Files.createDirectories(studentDir);
        // A file that doesn't match any problem pattern
        Files.writeString(studentDir.resolve("homework.c"), "int main() { return 0; }");

        Path testsDir = jobDir.resolve("tests");
        Files.createDirectories(testsDir);

        FilesystemJobRepository realRepo = new FilesystemJobRepository(tempWorkspace.toString());
        realRepo.save(new Job("skip-job"));

        when(mockCsvService.aggregate(anyString())).thenReturn(List.of());

        JobServiceImpl svc = new JobServiceImpl(
                realRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, DIRECT_EXECUTOR);

        svc.evaluateJob("skip-job");

        Job finalJob = realRepo.findById("skip-job").orElseThrow();
        assertThat(finalJob.getState()).isEqualTo(JobState.DONE);

        // CSV should have a SKIP row for homework.c
        String csvContent = Files.readString(jobDir.resolve("results.csv"));
        assertThat(csvContent).contains("SKIP");
        // Engine should NOT have been called (skipped before compile)
        verify(mockEngine, never()).compile(any(), any());
    }

    @Test
    void evaluate_writesCompileErrorRow_whenCompileFails() throws Exception {
        Path jobDir = tempWorkspace.resolve("ce-job");
        Files.createDirectories(jobDir);

        Path studentDir = jobDir.resolve("submissions").resolve("Carol__submission_003");
        Files.createDirectories(studentDir);
        Files.writeString(studentDir.resolve("problem1.c"), "invalid c code !!!");

        Path testsDir = jobDir.resolve("tests");
        Files.createDirectories(testsDir.resolve("problem1"));

        FilesystemJobRepository realRepo = new FilesystemJobRepository(tempWorkspace.toString());
        realRepo.save(new Job("ce-job"));

        CompileOutcome compileError = new CompileOutcome(CompileStatus.COMPILE_ERROR, "syntax error");
        when(mockEngine.compile(any(Path.class), any(Path.class))).thenReturn(compileError);
        when(mockCsvService.aggregate(anyString())).thenReturn(List.of());

        JobServiceImpl svc = new JobServiceImpl(
                realRepo, mockZipExtractor, mockEngine, mockCsvService,
                tempWorkspace, DIRECT_EXECUTOR);

        svc.evaluateJob("ce-job");

        Job finalJob = realRepo.findById("ce-job").orElseThrow();
        assertThat(finalJob.getState()).isEqualTo(JobState.DONE);
        assertThat(finalJob.getCompileErrorCount()).isEqualTo(1);

        String csvContent = Files.readString(jobDir.resolve("results.csv"));
        assertThat(csvContent).contains("COMPILE_ERROR");
        // Run should never be called for a compile-failed file
        verify(mockEngine, never()).runCase(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void tryTransitionToError_transitionsCancellingJobToCancelled() throws Exception {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.CANCELLING);
        when(mockRepo.findById(job.getJobId())).thenReturn(Optional.of(job));

        Method method = JobServiceImpl.class.getDeclaredMethod("tryTransitionToError", String.class);
        method.setAccessible(true);
        method.invoke(service, job.getJobId());

        assertThat(job.getState()).isEqualTo(JobState.CANCELLED);
        verify(mockRepo).save(job);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {}
                    });
        }
    }
}
