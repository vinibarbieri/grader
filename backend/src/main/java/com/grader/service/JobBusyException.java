package com.grader.service;

public class JobBusyException extends RuntimeException {

    private final String requestedJobId;
    private final String activeJobId;

    public JobBusyException(String requestedJobId, String activeJobId) {
        super("Job " + requestedJobId + " cannot start because job " + activeJobId + " is already running");
        this.requestedJobId = requestedJobId;
        this.activeJobId = activeJobId;
    }

    public String getRequestedJobId() {
        return requestedJobId;
    }

    public String getActiveJobId() {
        return activeJobId;
    }
}
