CREATE TABLE tenant_migration_job (
    job_id          UUID PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    -- the checkpoint (next_page) is only meaningful for the page size the job started with
    page_size       INTEGER      NOT NULL CHECK (page_size > 0),
    next_page       INTEGER      NOT NULL DEFAULT 0 CHECK (next_page >= 0),
    processed_count BIGINT       NOT NULL DEFAULT 0,
    succeeded_count BIGINT       NOT NULL DEFAULT 0,
    skipped_count   BIGINT       NOT NULL DEFAULT 0,
    failed_count    BIGINT       NOT NULL DEFAULT 0,
    -- worker run that currently holds the job; every progress update is conditional on it
    lease_owner     UUID,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- at most one unfinished job per tenant; a FAILED job is resumed instead of starting a new one
CREATE UNIQUE INDEX tenant_migration_job_one_unfinished_per_tenant
    ON tenant_migration_job (tenant_id)
    WHERE status IN ('PENDING', 'RUNNING', 'FAILED');

-- recovery scan: PENDING jobs and RUNNING jobs with an expired lease
CREATE INDEX tenant_migration_job_status_updated_at
    ON tenant_migration_job (status, updated_at);
