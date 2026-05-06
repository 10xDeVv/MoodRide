package com.moodride.routeapi.exception;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(UUID jobId) {
        super("Route job not found: " + jobId);
    }
}

