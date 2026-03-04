package com.grader.domain;

import java.util.Set;

/**
 * State machine for a grading job.
 *
 * Valid transitions:
 *   QUEUED     -> RUNNING, CANCELLING, CANCELLED
 *   RUNNING    -> DONE, ERROR, CANCELLING
 *   CANCELLING -> CANCELLED
 *   DONE, ERROR, CANCELLED -> (terminal, no transitions)
 */
public enum JobState {

    QUEUED {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of(RUNNING, CANCELLING, CANCELLED);
        }
    },
    RUNNING {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of(DONE, ERROR, CANCELLING);
        }
    },
    CANCELLING {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of(CANCELLED);
        }
    },
    DONE {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of();
        }
    },
    ERROR {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of();
        }
    },
    CANCELLED {
        @Override
        public Set<JobState> allowedTargets() {
            return Set.of();
        }
    };

    public abstract Set<JobState> allowedTargets();

    /**
     * Attempts the transition to {@code target} and returns {@code target} on success.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public JobState transitionTo(JobState target) {
        if (!allowedTargets().contains(target)) {
            throw new IllegalStateException(
                    "Illegal state transition: " + this + " -> " + target);
        }
        return target;
    }

    public boolean isTerminal() {
        return this == DONE || this == ERROR || this == CANCELLED;
    }

    public boolean isCancellable() {
        return this == QUEUED || this == RUNNING;
    }
}
