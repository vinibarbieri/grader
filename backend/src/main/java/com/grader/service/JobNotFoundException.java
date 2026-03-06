package com.grader.service;

public class JobNotFoundException extends RuntimeException {

    private final String jobId;

    public JobNotFoundException(String jobId) {
        super("Job not found: " + jobId);
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}
