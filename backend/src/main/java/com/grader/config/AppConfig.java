package com.grader.config;

import com.grader.infrastructure.FilesystemJobRepository;
import com.grader.infrastructure.JobRepository;
import com.grader.infrastructure.ProcessGroupLauncher;
import com.grader.infrastructure.SafeZipExtractor;
import com.grader.service.CsvAggregationService;
import com.grader.service.ExecutionEngine;
import com.grader.service.JobService;
import com.grader.service.JobServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Bean
    public CsvAggregationService csvAggregationService(GraderConfig config) {
        return new CsvAggregationService(config.getScoring().getMaxScorePerProblem());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService evaluationExecutor(GraderConfig config) {
        return Executors.newFixedThreadPool(config.getWorkerPoolSize());
    }

    @Bean
    public JobService jobService(JobRepository jobRepository,
                                 SafeZipExtractor zipExtractor,
                                 ExecutionEngine executionEngine,
                                 CsvAggregationService csvAggregationService,
                                 GraderConfig config,
                                 ExecutorService evaluationExecutor) {
        return new JobServiceImpl(
                jobRepository,
                zipExtractor,
                executionEngine,
                csvAggregationService,
                Path.of(config.getWorkspaceRoot()),
                evaluationExecutor);
    }
}
