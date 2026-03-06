package com.grader.service;

import com.grader.domain.Job;
import com.grader.domain.JobState;
import com.grader.domain.model.StudentResult;
import com.grader.infrastructure.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Stub implementation of {@link JobService} for Step 4 (controller skeletons).
 * Full business logic (ZIP extraction, execution engine, wiring) is added in Step 9.
 */
@Service
public class JobServiceImpl implements JobService {

    private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    private final JobRepository jobRepository;

    public JobServiceImpl(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public Job createJob(MultipartFile file) {
        Job job = Job.create();
        jobRepository.save(job);
        log.info("jobId={} created (file={})", job.getJobId(), file.getOriginalFilename());
        return job;
    }

    @Override
    public Job evaluateJob(String jobId) {
        Job job = getJob(jobId);
        // Stub: transition to RUNNING — full execution engine wired in Step 9
        job.transitionTo(JobState.RUNNING);
        jobRepository.save(job);
        log.info("jobId={} evaluate requested (stub)", jobId);
        return job;
    }

    @Override
    public Job getJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Override
    public List<StudentResult> getResults(String jobId) {
        getJob(jobId); // validates existence
        // Stub: returns empty list — full aggregation wired in Step 9
        return List.of();
    }

    @Override
    public byte[] downloadArtifact(String jobId, String format) {
        getJob(jobId); // validates existence
        // Stub: returns empty bytes — ArtifactService wired in Step 10
        return new byte[0];
    }

    @Override
    public Job cancelJob(String jobId) {
        Job job = getJob(jobId);
        job.requestCancel();
        jobRepository.save(job);
        log.info("jobId={} cancel requested, state={}", jobId, job.getState());
        return job;
    }
}
