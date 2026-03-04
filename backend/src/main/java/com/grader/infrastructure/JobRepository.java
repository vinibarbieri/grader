package com.grader.infrastructure;

import com.grader.domain.Job;

import java.nio.file.Path;
import java.util.Optional;

public interface JobRepository {

    void save(Job job);

    Optional<Job> findById(String jobId);

    boolean exists(String jobId);

    Path getJobDirectory(String jobId);
}
