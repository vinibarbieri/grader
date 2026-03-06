package com.grader.service;

public class JobInvalidStateException extends RuntimeException {

    private final String jobId;
    private final String currentState;
    private final String expectedState;

    public JobInvalidStateException(String jobId, String currentState, String expectedState) {
        super("Job " + jobId + " cannot transition from " + currentState + " to " + expectedState);
        this.jobId = jobId;
        this.currentState = currentState;
        this.expectedState = expectedState;
    }

    public String getJobId() {
        return jobId;
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getExpectedState() {
        return expectedState;
    }
}
