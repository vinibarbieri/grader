package com.grader.controller;

import com.grader.controller.dto.*;
import com.grader.domain.Job;
import com.grader.domain.model.StudentResult;
import com.grader.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs
    // -------------------------------------------------------------------------

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobCreateResponse createJob(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty ZIP file is required");
        }
        Job job = jobService.createJob(file);
        return new JobCreateResponse(job.getJobId(), job.getState().name().toLowerCase());
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs/{jobId}/evaluate
    // -------------------------------------------------------------------------

    @PostMapping("/jobs/{jobId}/evaluate")
    public JobEvaluateResponse evaluateJob(@PathVariable String jobId) {
        Job job = jobService.evaluateJob(jobId);
        return new JobEvaluateResponse(job.getJobId(), job.getState().name().toLowerCase());
    }

    // -------------------------------------------------------------------------
    // GET /api/status/{jobId}
    // -------------------------------------------------------------------------

    @GetMapping("/status/{jobId}")
    public JobStatusResponse getStatus(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);
        return JobStatusResponse.from(job);
    }

    // -------------------------------------------------------------------------
    // GET /api/results/{jobId}
    // -------------------------------------------------------------------------

    @GetMapping("/results/{jobId}")
    public List<StudentResult> getResults(@PathVariable String jobId) {
        return jobService.getResults(jobId);
    }

    // -------------------------------------------------------------------------
    // GET /api/download/{jobId}?format=csv|json
    // -------------------------------------------------------------------------

    @GetMapping("/download/{jobId}")
    public ResponseEntity<byte[]> download(
            @PathVariable String jobId,
            @RequestParam String format) {

        if (!format.equals("csv") && !format.equals("json")) {
            throw new IllegalArgumentException("Invalid format '" + format + "': must be 'csv' or 'json'");
        }

        byte[] data = jobService.downloadArtifact(jobId, format);

        String contentType = format.equals("csv") ? "text/csv" : "application/json";
        String filename = "results-" + jobId + "." + format;

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }

    // -------------------------------------------------------------------------
    // POST /api/jobs/{jobId}/cancel
    // -------------------------------------------------------------------------

    @PostMapping("/jobs/{jobId}/cancel")
    public JobCancelResponse cancelJob(@PathVariable String jobId) {
        Job job = jobService.cancelJob(jobId);
        return new JobCancelResponse(job.getJobId(), job.getState().name().toLowerCase());
    }
}
