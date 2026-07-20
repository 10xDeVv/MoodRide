-- Persist every required terminal route-job publication in the same database as its
-- lifecycle transition. Kafka acknowledgment happens outside the transaction, so
-- delivery claims are leased and fenced independently from worker ownership leases.
CREATE TABLE route_job_terminal_events (
    event_id VARCHAR(100) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES route_jobs(id) ON DELETE CASCADE,
    state_revision BIGINT NOT NULL CHECK (state_revision >= 0),
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('COMPLETION', 'DLQ')),
    terminal_status VARCHAR(20) NOT NULL CHECK (terminal_status IN ('COMPLETED', 'FAILED', 'TIMEOUT')),
    original_payload VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    delivered_at TIMESTAMP,
    lease_token UUID,
    lease_expires_at TIMESTAMP,
    last_error VARCHAR(1000),
    CONSTRAINT uq_route_job_terminal_event_identity
        UNIQUE (job_id, state_revision, event_type),
    CONSTRAINT ck_route_job_terminal_event_lease_pair CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_route_job_terminal_event_delivered_not_leased CHECK (
        delivered_at IS NULL OR (lease_token IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_route_job_terminal_event_dlq_status CHECK (
        event_type <> 'DLQ' OR terminal_status IN ('FAILED', 'TIMEOUT')
    )
);

-- Baseline pre-outbox terminal jobs as already delivered. Release cutover drains active
-- intake before this migration, so replaying the historical terminal population would
-- duplicate old user notifications and DLQ records. Deterministic identities still
-- let a later Kafka redelivery find a bounded completed ledger entry.
INSERT INTO route_job_terminal_events (
    event_id,
    job_id,
    state_revision,
    event_type,
    terminal_status,
    original_payload,
    created_at,
    next_attempt_at,
    delivered_at
)
SELECT
    id::text || ':' || state_revision::text || ':COMPLETION',
    id,
    state_revision,
    'COMPLETION',
    status,
    NULL,
    COALESCE(completed_at, CURRENT_TIMESTAMP),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM route_jobs
WHERE status IN ('COMPLETED', 'FAILED', 'TIMEOUT')
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO route_job_terminal_events (
    event_id,
    job_id,
    state_revision,
    event_type,
    terminal_status,
    original_payload,
    created_at,
    next_attempt_at,
    delivered_at
)
SELECT
    id::text || ':' || state_revision::text || ':DLQ',
    id,
    state_revision,
    'DLQ',
    status,
    id::text,
    COALESCE(completed_at, CURRENT_TIMESTAMP),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM route_jobs
WHERE status IN ('FAILED', 'TIMEOUT')
ON CONFLICT (event_id) DO NOTHING;

CREATE INDEX idx_route_job_terminal_event_due
    ON route_job_terminal_events (next_attempt_at, created_at, event_id)
    WHERE delivered_at IS NULL;
