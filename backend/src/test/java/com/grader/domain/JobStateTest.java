package com.grader.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class JobStateTest {

    // --- Valid transitions ---

    @Test
    void queued_transitionsTo_running() {
        assertEquals(JobState.RUNNING, JobState.QUEUED.transitionTo(JobState.RUNNING));
    }

    @Test
    void running_transitionsTo_done() {
        assertEquals(JobState.DONE, JobState.RUNNING.transitionTo(JobState.DONE));
    }

    @Test
    void running_transitionsTo_error() {
        assertEquals(JobState.ERROR, JobState.RUNNING.transitionTo(JobState.ERROR));
    }

    @Test
    void running_transitionsTo_cancelling() {
        assertEquals(JobState.CANCELLING, JobState.RUNNING.transitionTo(JobState.CANCELLING));
    }

    @Test
    void queued_transitionsTo_cancelling() {
        assertEquals(JobState.CANCELLING, JobState.QUEUED.transitionTo(JobState.CANCELLING));
    }

    @Test
    void queued_transitionsTo_cancelled() {
        assertEquals(JobState.CANCELLED, JobState.QUEUED.transitionTo(JobState.CANCELLED));
    }

    @Test
    void cancelling_transitionsTo_cancelled() {
        assertEquals(JobState.CANCELLED, JobState.CANCELLING.transitionTo(JobState.CANCELLED));
    }

    // --- Invalid transitions ---

    @Test
    void queued_cannotTransitionTo_done() {
        assertThrows(IllegalStateException.class, () -> JobState.QUEUED.transitionTo(JobState.DONE));
    }

    @Test
    void queued_cannotTransitionTo_error() {
        assertThrows(IllegalStateException.class, () -> JobState.QUEUED.transitionTo(JobState.ERROR));
    }

    @Test
    void done_cannotTransitionTo_anything() {
        for (JobState target : JobState.values()) {
            if (target == JobState.DONE) continue;
            assertThrows(IllegalStateException.class,
                    () -> JobState.DONE.transitionTo(target),
                    "DONE should not transition to " + target);
        }
    }

    @Test
    void error_cannotTransitionTo_anything() {
        for (JobState target : JobState.values()) {
            if (target == JobState.ERROR) continue;
            assertThrows(IllegalStateException.class,
                    () -> JobState.ERROR.transitionTo(target),
                    "ERROR should not transition to " + target);
        }
    }

    @Test
    void cancelled_cannotTransitionTo_anything() {
        for (JobState target : JobState.values()) {
            if (target == JobState.CANCELLED) continue;
            assertThrows(IllegalStateException.class,
                    () -> JobState.CANCELLED.transitionTo(target),
                    "CANCELLED should not transition to " + target);
        }
    }

    // --- Cancellation idempotency on terminal states ---

    @Test
    void isTerminal_done() {
        assertTrue(JobState.DONE.isTerminal());
    }

    @Test
    void isTerminal_error() {
        assertTrue(JobState.ERROR.isTerminal());
    }

    @Test
    void isTerminal_cancelled() {
        assertTrue(JobState.CANCELLED.isTerminal());
    }

    @Test
    void isNotTerminal_queued() {
        assertFalse(JobState.QUEUED.isTerminal());
    }

    @Test
    void isNotTerminal_running() {
        assertFalse(JobState.RUNNING.isTerminal());
    }

    @Test
    void isNotTerminal_cancelling() {
        assertFalse(JobState.CANCELLING.isTerminal());
    }

    // --- isCancellable ---

    @Test
    void queued_isCancellable() {
        assertTrue(JobState.QUEUED.isCancellable());
    }

    @Test
    void running_isCancellable() {
        assertTrue(JobState.RUNNING.isCancellable());
    }

    @Test
    void cancelling_isNotCancellable() {
        assertFalse(JobState.CANCELLING.isCancellable());
    }

    @Test
    void terminal_statesAreNotCancellable() {
        assertFalse(JobState.DONE.isCancellable());
        assertFalse(JobState.ERROR.isCancellable());
        assertFalse(JobState.CANCELLED.isCancellable());
    }
}
