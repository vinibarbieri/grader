package com.grader.infrastructure;

import com.grader.domain.Job;
import com.grader.domain.JobState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FilesystemJobRepositoryTest {

    @TempDir
    Path tempDir;

    FilesystemJobRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FilesystemJobRepository(tempDir.toString());
    }

    @Test
    void save_and_findById_roundtrip() {
        Job job = Job.create();
        repository.save(job);

        Optional<Job> found = repository.findById(job.getJobId());
        assertTrue(found.isPresent());
        assertEquals(job.getJobId(), found.get().getJobId());
        assertEquals(JobState.QUEUED, found.get().getState());
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        Optional<Job> found = repository.findById("nonexistent-job");
        assertTrue(found.isEmpty());
    }

    @Test
    void update_persists_stateChange() {
        Job job = Job.create();
        repository.save(job);

        job.transitionTo(JobState.RUNNING);
        repository.save(job);

        Optional<Job> found = repository.findById(job.getJobId());
        assertTrue(found.isPresent());
        assertEquals(JobState.RUNNING, found.get().getState());
    }

    @Test
    void update_persists_progressCounters() {
        Job job = Job.create();
        repository.save(job);

        job.setTotalStudents(10);
        job.setProcessedStudents(5);
        job.setCurrentStudent("alice_12345");
        repository.save(job);

        Optional<Job> found = repository.findById(job.getJobId());
        assertTrue(found.isPresent());
        assertEquals(10, found.get().getTotalStudents());
        assertEquals(5, found.get().getProcessedStudents());
        assertEquals("alice_12345", found.get().getCurrentStudent());
    }

    @Test
    void multipleJobs_areStoredIndependently() {
        Job job1 = Job.create();
        Job job2 = Job.create();
        repository.save(job1);
        repository.save(job2);

        assertTrue(repository.findById(job1.getJobId()).isPresent());
        assertTrue(repository.findById(job2.getJobId()).isPresent());
        assertNotEquals(job1.getJobId(), job2.getJobId());
    }

    @Test
    void getJobDirectory_createsAndReturnsPath() {
        Job job = Job.create();
        repository.save(job);

        Path dir = repository.getJobDirectory(job.getJobId());
        assertNotNull(dir);
        assertTrue(dir.toFile().exists());
        assertTrue(dir.toFile().isDirectory());
    }

    @Test
    void exists_returnsTrueForSavedJob() {
        Job job = Job.create();
        repository.save(job);
        assertTrue(repository.exists(job.getJobId()));
    }

    @Test
    void exists_returnsFalseForMissingJob() {
        assertFalse(repository.exists("no-such-job"));
    }
}
