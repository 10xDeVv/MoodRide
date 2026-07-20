package com.moodride.routeworker.service;

import com.moodride.datamodels.RouteJob;

import java.time.Instant;
import java.util.UUID;

final class WorkerLeaseGuard {
    private WorkerLeaseGuard() {
    }

    static void requireActive(RouteJob job, UUID expectedLeaseToken, Instant now) {
        if (!job.leaseMatches(expectedLeaseToken)
            || job.getLeaseExpiresAt() == null
            || !job.getLeaseExpiresAt().isAfter(now)) {
            throw new LeaseLostException(job.getId());
        }
    }
}
