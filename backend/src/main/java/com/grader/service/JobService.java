package com.grader.service;

import com.grader.domain.Job;
import com.grader.domain.model.StudentResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface JobService {

    /** Creates a new job and stores the uploaded ZIP file. */
    Job createJob(MultipartFile file);

    /** Transitions the job to running and submits it for evaluation. */
    Job evaluateJob(String jobId);

    /** Returns the current job aggregate. Throws {@link JobNotFoundException} if absent. */
    Job getJob(String jobId);

    /** Returns the aggregated student results for a completed job. */
    List<StudentResult> getResults(String jobId);

    /** Returns the raw bytes of the requested artifact (csv or json). */
    byte[] downloadArtifact(String jobId, String format);

    /** Requests cancellation. Idempotent — returns current state if already terminal. */
    Job cancelJob(String jobId);
}
