package com.grader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grader")
public class GraderConfig {

    private String workspaceRoot = "/tmp/grader-jobs";
    private String tmpfsPath = "/tmp/grader-tmpfs";
    private int workerPoolSize = 2;
    private int timeLimitSec = 2;
    private long stdoutCapBytes = 2097152L;
    private long stderrCapBytes = 2097152L;
    private String compileFlags = "-O1 -Wall -Werror=vla";
    private long killGraceMs = 200L;
    private Scoring scoring = new Scoring();

    public static class Scoring {
        private double maxScorePerProblem = 20.0;

        public double getMaxScorePerProblem() {
            return maxScorePerProblem;
        }

        public void setMaxScorePerProblem(double maxScorePerProblem) {
            this.maxScorePerProblem = maxScorePerProblem;
        }
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getTmpfsPath() {
        return tmpfsPath;
    }

    public void setTmpfsPath(String tmpfsPath) {
        this.tmpfsPath = tmpfsPath;
    }

    public int getWorkerPoolSize() {
        return workerPoolSize;
    }

    public void setWorkerPoolSize(int workerPoolSize) {
        this.workerPoolSize = workerPoolSize;
    }

    public int getTimeLimitSec() {
        return timeLimitSec;
    }

    public void setTimeLimitSec(int timeLimitSec) {
        this.timeLimitSec = timeLimitSec;
    }

    public long getStdoutCapBytes() {
        return stdoutCapBytes;
    }

    public void setStdoutCapBytes(long stdoutCapBytes) {
        this.stdoutCapBytes = stdoutCapBytes;
    }

    public long getStderrCapBytes() {
        return stderrCapBytes;
    }

    public void setStderrCapBytes(long stderrCapBytes) {
        this.stderrCapBytes = stderrCapBytes;
    }

    public String getCompileFlags() {
        return compileFlags;
    }

    public void setCompileFlags(String compileFlags) {
        this.compileFlags = compileFlags;
    }

    public long getKillGraceMs() {
        return killGraceMs;
    }

    public void setKillGraceMs(long killGraceMs) {
        this.killGraceMs = killGraceMs;
    }

    public Scoring getScoring() {
        return scoring;
    }

    public void setScoring(Scoring scoring) {
        this.scoring = scoring;
    }
}
