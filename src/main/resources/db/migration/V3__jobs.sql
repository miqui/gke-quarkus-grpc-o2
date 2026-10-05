-- job-manager-api replaces the message service on the same Cloud SQL database. V1/V2 (authors,
-- messages) stay in the history so Flyway's checksums still validate on an existing database; this
-- migration drops their tables and creates the job schema.
--
-- Payloads (spec, result, error details, labels) are JSONB; everything the API filters, sorts or
-- guards on is a typed column.

DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS authors;

-- Registry of accepted types. Rows are added with SQL (see DB.md); the API only reads them.
CREATE TABLE job_types (
    name                  VARCHAR(100) NOT NULL,
    description           VARCHAR(500) NOT NULL DEFAULT '',
    default_max_attempts  INTEGER      NOT NULL DEFAULT 3,
    default_lease_seconds INTEGER      NOT NULL DEFAULT 60,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT job_types_pkey PRIMARY KEY (name),
    CONSTRAINT job_types_max_attempts_check CHECK (default_max_attempts BETWEEN 1 AND 20),
    CONSTRAINT job_types_lease_seconds_check CHECK (default_lease_seconds BETWEEN 5 AND 3600)
);

INSERT INTO job_types (name, description, default_max_attempts, default_lease_seconds) VALUES
    ('demo.echo',       'Returns its spec as the result. For smoke and load tests.', 3, 30),
    ('demo.sleep',      'Sleeps spec.seconds, then succeeds. For lease and heartbeat tests.', 3, 30),
    ('report.generate', 'Renders a report described by spec (template, parameters, format).', 5, 300),
    ('email.send',      'Sends one email described by spec (to, subject, template, data).', 5, 60);

CREATE TABLE jobs (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(100) NOT NULL,
    state            VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    priority         SMALLINT     NOT NULL DEFAULT 0,
    spec             JSONB        NOT NULL,
    labels           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result           JSONB,
    -- {"message": ..., "details": {...}, "retryable": bool} - the most recent failure.
    error            JSONB,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    max_attempts     INTEGER      NOT NULL,
    run_after        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lease_owner      VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    -- Optimistic locking: +1 on every state change, guarded by CancelJob's optional version.
    version          INTEGER      NOT NULL DEFAULT 0,
    idempotency_key  VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    CONSTRAINT jobs_pkey PRIMARY KEY (id),
    CONSTRAINT jobs_type_fkey FOREIGN KEY (type) REFERENCES job_types (name) ON DELETE RESTRICT,
    CONSTRAINT jobs_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT jobs_state_check
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT jobs_priority_check CHECK (priority BETWEEN 0 AND 9),
    CONSTRAINT jobs_attempts_check CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 20),
    -- A lease exists exactly while the job is RUNNING.
    CONSTRAINT jobs_lease_check
        CHECK ((state = 'RUNNING') = (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);

-- ListJobs: ORDER BY created_at DESC, id DESC, optionally per state or type.
CREATE INDEX ix_jobs_created_at_id ON jobs (created_at DESC, id DESC);
CREATE INDEX ix_jobs_state_created_at ON jobs (state, created_at DESC, id DESC);
CREATE INDEX ix_jobs_type_created_at ON jobs (type, created_at DESC, id DESC);
-- ListJobs label filter: labels @> '{"k":"v"}'.
CREATE INDEX ix_jobs_labels ON jobs USING GIN (labels jsonb_path_ops);
-- ClaimJob: the next QUEUED job by priority, then run_after (FOR UPDATE SKIP LOCKED). Partial, so
-- it only holds the queue, not the history.
CREATE INDEX ix_jobs_claim ON jobs (priority DESC, run_after, id) WHERE state = 'QUEUED';
-- Lease reaper: RUNNING jobs whose lease has passed.
CREATE INDEX ix_jobs_lease_expires_at ON jobs (lease_expires_at) WHERE state = 'RUNNING';

CREATE TABLE job_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    job_id     UUID         NOT NULL,
    -- NULL for the creation event.
    from_state VARCHAR(16),
    to_state   VARCHAR(16)  NOT NULL,
    actor      VARCHAR(120) NOT NULL,
    detail     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT job_events_pkey PRIMARY KEY (id),
    -- DeleteJob removes a job's history with it.
    CONSTRAINT job_events_job_id_fkey FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE
);

CREATE INDEX ix_job_events_job_id_id ON job_events (job_id, id);
