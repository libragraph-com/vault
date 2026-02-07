-- V1: Initial Vault schema
-- Source: docs/research/Database-Schema.md

-- ============================================================
-- UUIDv7 function
-- ============================================================

-- PostgreSQL 18+: delegates to native uuidv7()
-- PostgreSQL 13-17: PL/pgSQL fallback using gen_random_uuid() + timestamp overlay
CREATE OR REPLACE FUNCTION vault_uuid_v7() RETURNS uuid AS $$
BEGIN
    IF current_setting('server_version_num')::int >= 180000 THEN
        RETURN uuidv7();
    END IF;

    -- Fallback: overlay millisecond timestamp onto random UUID
    RETURN encode(
        set_bit(
            set_bit(
                overlay(uuid_send(gen_random_uuid())
                    placing substring(int8send(floor(extract(epoch from clock_timestamp()) * 1000)::bigint) from 3)
                    from 1 for 6
                ),
                52, 1
            ),
            53, 1
        ),
        'hex')::uuid;
END;
$$ LANGUAGE plpgsql VOLATILE;

-- ============================================================
-- Core tables
-- ============================================================

CREATE TABLE leaves (
    content_hash  BYTEA PRIMARY KEY,     -- BLAKE3-128 (16 bytes)
    size_bytes    BIGINT NOT NULL,
    mime_type     TEXT,
    metadata      JSONB,
    full_text     TEXT,
    tenant_id     UUID NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE containers (
    id              UUID PRIMARY KEY DEFAULT vault_uuid_v7(),
    content_hash    BYTEA NOT NULL,
    original_size   BIGINT NOT NULL,
    container_type  SMALLINT NOT NULL,     -- ZIP=1, PST=2, TAR=3, etc.
    entry_count     INTEGER NOT NULL,
    source_path     TEXT NOT NULL,
    parent_id       UUID,
    tenant_id       UUID NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (parent_id) REFERENCES containers(id)
);

CREATE TABLE entries (
    id              UUID PRIMARY KEY DEFAULT vault_uuid_v7(),
    container_id    UUID NOT NULL,
    internal_path   TEXT NOT NULL,
    entry_type      SMALLINT NOT NULL,     -- 0=file, 1=directory, 2=symlink
    target_hash     BYTEA NOT NULL,
    target_size     BIGINT NOT NULL,
    mtime           TIMESTAMPTZ,
    metadata        JSONB,
    FOREIGN KEY (container_id) REFERENCES containers(id)
);

-- ============================================================
-- Task tables
-- ============================================================

CREATE TABLE tasks (
    id                      UUID PRIMARY KEY,        -- UUIDv7, generated in Java
    type                    VARCHAR(128) NOT NULL,
    status                  VARCHAR(32) NOT NULL,    -- OPEN, IN_PROGRESS, BLOCKED, BACKGROUND, COMPLETE, ERROR, DEAD
    priority                SMALLINT DEFAULT 128,

    input                   JSONB NOT NULL,
    output                  JSONB,

    error_message           TEXT,
    error_type              VARCHAR(256),
    retryable               BOOLEAN,
    retry_count             INTEGER DEFAULT 0,

    claimed_by              VARCHAR(128),
    background_timeout_at   TIMESTAMPTZ,

    parent_id               UUID REFERENCES tasks(id),
    tenant_id               UUID NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    claimed_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ
);

CREATE TABLE task_dependencies (
    blocked_task_id     UUID REFERENCES tasks(id),
    blocking_task_id    UUID REFERENCES tasks(id),
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (blocked_task_id, blocking_task_id)
);

CREATE TABLE resources (
    service_id      VARCHAR(128) PRIMARY KEY,
    status          VARCHAR(32) NOT NULL,
    max_concurrency INTEGER,
    current_load    INTEGER DEFAULT 0,
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    error_message   TEXT,
    CHECK (status IN ('STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'FAILED'))
);

CREATE TABLE task_resource_deps (
    task_id         UUID REFERENCES tasks(id),
    service_id      VARCHAR(128) REFERENCES resources(service_id),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (task_id, service_id)
);

-- ============================================================
-- Indexes
-- ============================================================

-- Full-text search
CREATE INDEX idx_leaves_fts ON leaves USING GIN (to_tsvector('english', full_text));

-- Tenant isolation
CREATE INDEX idx_leaves_tenant ON leaves(tenant_id);
CREATE INDEX idx_containers_tenant ON containers(tenant_id);

-- Queries
CREATE INDEX idx_leaves_mime ON leaves(mime_type);
CREATE INDEX idx_entries_container ON entries(container_id);
CREATE INDEX idx_entries_target ON entries(target_hash);
CREATE INDEX idx_containers_parent ON containers(parent_id);

-- Task indexes
CREATE INDEX idx_tasks_claim ON tasks(priority DESC, created_at ASC)
    WHERE status = 'OPEN' AND claimed_by IS NULL;
CREATE INDEX idx_tasks_tenant ON tasks(tenant_id);
CREATE INDEX idx_tasks_parent ON tasks(parent_id);
CREATE INDEX idx_tasks_stale_bg ON tasks(background_timeout_at)
    WHERE status = 'BACKGROUND';
CREATE INDEX idx_tasks_stale_claimed ON tasks(claimed_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_task_deps_blocking ON task_dependencies(blocking_task_id);
CREATE INDEX idx_task_resource_deps_service ON task_resource_deps(service_id);
CREATE INDEX idx_resources_status ON resources(status);
