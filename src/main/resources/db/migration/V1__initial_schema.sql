-- Initial schema: authors, messages. Same tables, constraint names and index as the Python
-- service's Alembic revision 0001, so an existing database is taken over with
-- spring.flyway.baseline-on-migrate (baseline version 1) instead of being re-created.

-- postgres_exporter's --collector.stat_statements and the "PostgreSQL Ops & Queries" Grafana
-- dashboard need it (Cloud SQL preloads the library).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE TABLE authors (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    name       VARCHAR(50)  NOT NULL,
    email      VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT authors_pkey PRIMARY KEY (id),
    CONSTRAINT authors_email_key UNIQUE (email)
);

CREATE TABLE messages (
    id         UUID          NOT NULL DEFAULT gen_random_uuid(),
    title      VARCHAR(100)  NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    author_id  UUID          NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Optimistic locking: UPDATE ... WHERE id = :id AND version = :version.
    version    INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT messages_pkey PRIMARY KEY (id),
    CONSTRAINT messages_author_id_fkey FOREIGN KEY (author_id)
        REFERENCES authors (id) ON DELETE RESTRICT
);

-- Matches ListMessages' ORDER BY created_at DESC, id DESC: a stable page order without a
-- full-table sort per request.
CREATE INDEX ix_messages_created_at_id ON messages (created_at, id);
