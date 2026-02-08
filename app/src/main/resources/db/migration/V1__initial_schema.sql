-- V1: Vault schema
-- Source: docs/research/RevisedSchema.sql + LISTEN/NOTIFY triggers

-- ============================================================
-- Extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS citext;

-- ============================================================
-- Lookup / Reference Tables
-- ============================================================

CREATE TABLE format_handler (
    id              SMALLINT PRIMARY KEY,
    key             CITEXT NOT NULL UNIQUE,
    handler_class   TEXT NOT NULL
);

CREATE TABLE entry_type (
    id              SMALLINT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE
);
INSERT INTO entry_type VALUES
    (0, 'file'),
    (1, 'directory'),
    (2, 'symlink');

-- ============================================================
-- Identity (from KeyCloak)
-- ============================================================

CREATE TABLE organization (
    id              SERIAL PRIMARY KEY,
    global_id       UUID NULL,
    name            CITEXT NOT NULL
);

CREATE TABLE tenant (
    id              SERIAL PRIMARY KEY,
    global_id       UUID NULL,
    org_id          INTEGER NOT NULL REFERENCES organization(id),
    name            CITEXT NOT NULL
);

-- ============================================================
-- Content-Addressed Storage
-- ============================================================

CREATE TABLE blob_ref (
    id              BIGSERIAL PRIMARY KEY,
    content_hash    BYTEA NOT NULL,
    leaf_size       BIGINT NOT NULL,
    container       BOOLEAN NOT NULL,
    mime_type       TEXT,
    handler         SMALLINT NULL REFERENCES format_handler(id),
    created         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (content_hash, leaf_size, container)
);

CREATE TABLE blob (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       INTEGER NOT NULL REFERENCES tenant(id),
    blob_ref_id     BIGINT NOT NULL REFERENCES blob_ref(id)
);

CREATE TABLE blob_content (
    blob_id         BIGINT PRIMARY KEY REFERENCES blob(id),
    md              TEXT
);
CREATE INDEX blob_fts ON blob_content USING GIN (to_tsvector('english', md));

CREATE TABLE container (
    blob_id         BIGINT PRIMARY KEY REFERENCES blob(id),
    entry_count     INTEGER NOT NULL,
    parent_id       BIGINT NULL REFERENCES container(blob_id)
);
CREATE INDEX container_parent_idx ON container(parent_id);

CREATE TABLE entry (
    id              BIGSERIAL PRIMARY KEY,
    blob_id         BIGINT NOT NULL REFERENCES blob(id),
    container_id    BIGINT NOT NULL REFERENCES container(blob_id),
    entry_type_id   SMALLINT NOT NULL REFERENCES entry_type(id),
    internal_path   TEXT NOT NULL,
    mtime           TIMESTAMPTZ,
    metadata        JSONB,
    UNIQUE (container_id, internal_path)
);

-- ============================================================
-- Cluster
-- ============================================================

CREATE TABLE node (
    id              SERIAL PRIMARY KEY,
    hostname        CITEXT NOT NULL UNIQUE,
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- Task tables
-- ============================================================

CREATE TABLE task_status (
    id              SMALLINT PRIMARY KEY,
    name            CITEXT NOT NULL UNIQUE
);
INSERT INTO task_status VALUES
    (0, 'OPEN'),
    (1, 'IN_PROGRESS'),
    (2, 'BLOCKED'),
    (3, 'BACKGROUND'),
    (4, 'COMPLETE'),
    (5, 'ERROR'),
    (6, 'CANCELLED'),
    (7, 'DEAD');

CREATE TABLE task (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER NOT NULL REFERENCES tenant(id),
    parent_id       INTEGER NULL REFERENCES task(id),

    type            CITEXT NOT NULL,
    status          SMALLINT NOT NULL REFERENCES task_status(id),
    priority        SMALLINT DEFAULT 128,

    input           JSONB NULL,
    output          JSONB NULL,

    retryable       BOOLEAN,
    retry_count     INTEGER DEFAULT 0,

    executor        INTEGER NULL REFERENCES node(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ
);

CREATE TABLE task_task_dep (
    task_id         INTEGER NOT NULL REFERENCES task(id),
    depends_on      INTEGER NOT NULL REFERENCES task(id),
    PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_dep_depends_on ON task_task_dep(depends_on);

CREATE TABLE task_resource (
    id              SMALLINT PRIMARY KEY,
    name            CITEXT NOT NULL UNIQUE,
    max_concurrency INTEGER NULL
);

CREATE TABLE task_resource_dep (
    task_id         INTEGER NOT NULL REFERENCES task(id),
    depends_on      SMALLINT NOT NULL REFERENCES task_resource(id),
    PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_resource_dep ON task_resource_dep(depends_on);

-- ============================================================
-- Indexes
-- ============================================================

-- Task claim: open tasks without an executor, ordered by priority
CREATE INDEX idx_task_claim ON task (priority DESC, created_at)
    WHERE status = 0 AND executor IS NULL;

-- Task queries
CREATE INDEX idx_task_tenant ON task(tenant_id);
CREATE INDEX idx_task_parent ON task(parent_id);

-- Stale task recovery
CREATE INDEX idx_task_stale_bg ON task(expires_at)
    WHERE status = 3;
CREATE INDEX idx_task_stale_claimed ON task(claimed_at)
    WHERE status = 1;

-- ============================================================
-- LISTEN/NOTIFY triggers
-- ============================================================

CREATE OR REPLACE FUNCTION notify_task_available() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('task_available', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION notify_task_completed() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('task_completed', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_available
    AFTER INSERT OR UPDATE ON task
    FOR EACH ROW
    WHEN (NEW.status = 0)
    EXECUTE FUNCTION notify_task_available();

CREATE TRIGGER trg_task_completed
    AFTER UPDATE ON task
    FOR EACH ROW
    WHEN (NEW.status = 4 AND OLD.status IS DISTINCT FROM 4)
    EXECUTE FUNCTION notify_task_completed();
