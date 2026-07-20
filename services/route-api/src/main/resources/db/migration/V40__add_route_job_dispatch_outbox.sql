-- Persist route-job publication independently from the request thread so a committed
-- QUEUED job cannot be stranded by a Kafka failure. Claims are leased because Kafka
-- acknowledgment necessarily happens outside the database transaction.
CREATE TABLE route_job_dispatches (
    job_id UUID PRIMARY KEY REFERENCES route_jobs(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    sent_at TIMESTAMP,
    lease_token UUID,
    lease_expires_at TIMESTAMP,
    last_error VARCHAR(1000),
    CONSTRAINT ck_route_job_dispatch_lease_pair CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_route_job_dispatch_sent_not_leased CHECK (
        sent_at IS NULL OR (lease_token IS NULL AND lease_expires_at IS NULL)
    )
);

-- Preserve recovery for QUEUED jobs committed before this outbox was deployed.
INSERT INTO route_job_dispatches (job_id, created_at, next_attempt_at)
SELECT id, submitted_at, submitted_at
FROM route_jobs
WHERE status = 'QUEUED'
ON CONFLICT (job_id) DO NOTHING;

CREATE INDEX idx_route_job_dispatch_due
    ON route_job_dispatches (created_at, job_id, next_attempt_at)
    WHERE sent_at IS NULL;
