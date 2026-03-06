package com.grader.infrastructure;

/**
 * Holds the result of a {@link ProcessGroupLauncher#launch} call.
 *
 * <p>{@code pgid} equals the OS PID of the launched process because
 * {@code setsid} execs into the target command, making that process the
 * session/group leader (PGID == PID).
 */
public record LaunchResult(Process process, long pgid) {}
