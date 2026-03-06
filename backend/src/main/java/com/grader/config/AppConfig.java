package com.grader.config;

import com.grader.infrastructure.FilesystemJobRepository;
import com.grader.infrastructure.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public JobRepository jobRepository(GraderConfig config) {
        return new FilesystemJobRepository(config.getWorkspaceRoot());
    }
}
