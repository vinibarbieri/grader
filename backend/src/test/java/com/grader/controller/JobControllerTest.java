package com.grader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grader.domain.Job;
import com.grader.domain.JobState;
import com.grader.service.JobNotFoundException;
import com.grader.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests using standalone MockMvc setup to avoid Spring classpath scanning
 * incompatibility with Java 25 class files in Spring Boot 3.2.x (ASM version constraint).
 */
@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    JobService jobService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs
    // -------------------------------------------------------------------------

    @Test
    void createJob_returns201_withJobIdAndQueuedStatus() throws Exception {
        Job job = Job.create();
        when(jobService.createJob(any())).thenReturn(job);

        MockMultipartFile file = new MockMultipartFile(
                "file", "submissions.zip", "application/zip", "fake-zip-content".getBytes());

        mockMvc.perform(multipart("/api/jobs").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(job.getJobId()))
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void createJob_returns400_whenNoFileProvided() throws Exception {
        mockMvc.perform(multipart("/api/jobs"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs/{jobId}/evaluate
    // -------------------------------------------------------------------------

    @Test
    void evaluate_returns200_withRunningStatus() throws Exception {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        when(jobService.evaluateJob(anyString())).thenReturn(job);

        mockMvc.perform(post("/api/jobs/{jobId}/evaluate", job.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.getJobId()))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void evaluate_returns404_whenJobNotFound() throws Exception {
        when(jobService.evaluateJob("unknown-job"))
                .thenThrow(new JobNotFoundException("unknown-job"));

        mockMvc.perform(post("/api/jobs/{jobId}/evaluate", "unknown-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.jobId").value("unknown-job"));
    }

    // -------------------------------------------------------------------------
    // GET /api/status/{jobId}
    // -------------------------------------------------------------------------

    @Test
    void getStatus_returns200_withFullStatusPayload() throws Exception {
        Job job = Job.create();
        when(jobService.getJob(anyString())).thenReturn(job);

        mockMvc.perform(get("/api/status/{jobId}", job.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.getJobId()))
                .andExpect(jsonPath("$.state").value("queued"))
                .andExpect(jsonPath("$.progress").exists())
                .andExpect(jsonPath("$.counters").exists());
    }

    @Test
    void getStatus_returns404_whenJobNotFound() throws Exception {
        when(jobService.getJob("unknown-job"))
                .thenThrow(new JobNotFoundException("unknown-job"));

        mockMvc.perform(get("/api/status/{jobId}", "unknown-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /api/results/{jobId}
    // -------------------------------------------------------------------------

    @Test
    void getResults_returns200_withResultsList() throws Exception {
        when(jobService.getResults(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/results/{jobId}", "some-job-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getResults_returns404_whenJobNotFound() throws Exception {
        when(jobService.getResults("unknown-job"))
                .thenThrow(new JobNotFoundException("unknown-job"));

        mockMvc.perform(get("/api/results/{jobId}", "unknown-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /api/download/{jobId}?format=csv|json
    // -------------------------------------------------------------------------

    @Test
    void download_csv_returns200_withAttachmentHeader() throws Exception {
        when(jobService.downloadArtifact(anyString(), anyString()))
                .thenReturn("col1,col2\n".getBytes());

        mockMvc.perform(get("/api/download/{jobId}", "some-job-id").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void download_json_returns200_withAttachmentHeader() throws Exception {
        when(jobService.downloadArtifact(anyString(), anyString()))
                .thenReturn("[]".getBytes());

        mockMvc.perform(get("/api/download/{jobId}", "some-job-id").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void download_returns400_whenFormatIsInvalid() throws Exception {
        mockMvc.perform(get("/api/download/{jobId}", "some-job-id").param("format", "xml"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void download_returns404_whenJobNotFound() throws Exception {
        when(jobService.downloadArtifact("unknown-job", "csv"))
                .thenThrow(new JobNotFoundException("unknown-job"));

        mockMvc.perform(get("/api/download/{jobId}", "unknown-job").param("format", "csv"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs/{jobId}/cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_returns200_withCancellingStatus() throws Exception {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.requestCancel();
        when(jobService.cancelJob(anyString())).thenReturn(job);

        mockMvc.perform(post("/api/jobs/{jobId}/cancel", job.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.getJobId()))
                .andExpect(jsonPath("$.status").value("cancelling"));
    }

    @Test
    void cancel_returns404_whenJobNotFound() throws Exception {
        when(jobService.cancelJob("unknown-job"))
                .thenThrow(new JobNotFoundException("unknown-job"));

        mockMvc.perform(post("/api/jobs/{jobId}/cancel", "unknown-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void cancel_isIdempotent_whenJobAlreadyTerminal() throws Exception {
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.DONE);
        when(jobService.cancelJob(anyString())).thenReturn(job);

        mockMvc.perform(post("/api/jobs/{jobId}/cancel", job.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
    }
}
