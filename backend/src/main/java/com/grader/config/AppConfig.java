package com.grader.config;

import com.grader.infrastructure.FilesystemJobRepository;
import com.grader.infrastructure.JobRepository;
import com.grader.infrastructure.ProcessGroupLauncher;
import com.grader.service.ExecutionEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public JobRepository jobRepository(GraderConfig config) {
        return new FilesystemJobRepository(config.getWorkspaceRoot());
    }

    @Bean
    public ProcessGroupLauncher processGroupLauncher(GraderConfig config) {
        return new ProcessGroupLauncher(config.getKillGraceMs());
    }

    @Bean
    public ExecutionEngine executionEngine(GraderConfig config, ProcessGroupLauncher launcher) {
        return new ExecutionEngine(
                launcher,
                config.getTimeLimitSec(),
                config.getStdoutCapBytes(),
                config.getStderrCapBytes(),
                config.getCompileFlags());
    }
}
