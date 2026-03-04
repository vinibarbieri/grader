# Step 1 — Maven Scaffold + Configuration

## What was done

- Created `backend/pom.xml` with Spring Boot 3.2.3, Java 25, JUnit 5, Mockito
- Created `GraderApplication.java` entry point
- Created `application.yml` with all Phase A config defaults
- Created `GraderConfig.java` with `@ConfigurationProperties(prefix = "grader")`

## Decisions

- Java version updated to 25 (host has OpenJDK Corretto 25)
- Maven found at IntelliJ IDEA bundled install: `/Users/viniciusbarbieri/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`
- `spring-boot-starter-parent` 3.2.3 manages all dependency versions
- Multipart max set to 500MB to accommodate large submission ZIPs

## Config defaults (application.yml)

| Key | Value |
|-----|-------|
| workspace-root | /tmp/grader-jobs |
| tmpfs-path | /tmp/grader-tmpfs |
| worker-pool-size | 2 |
| time-limit-sec | 2 |
| stdout-cap-bytes | 2097152 (2MB) |
| stderr-cap-bytes | 2097152 (2MB) |
| compile-flags | -O1 -Wall -Werror=vla |
| kill-grace-ms | 200 |
| scoring.max-score-per-problem | 20 |

## Status

`mvn compile` passes with only harmless Java 25 sun.misc.Unsafe deprecation warnings from Maven internals.
