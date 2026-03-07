package com.grader.service;

/**
 * Thrown when a requested download artifact (CSV or JSON) does not exist yet,
 * typically because the job has not completed evaluation.
 */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String message) {
        super(message);
    }
}
