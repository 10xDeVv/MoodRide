package com.moodride.routeworker.service;

import java.util.UUID;

/**
 * Signals that a worker no longer owns the job and must exit without mutation.
 */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException(UUID jobId) {
        super("Worker lease lost for route job " + jobId);
    }
}
