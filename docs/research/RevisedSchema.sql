-- ============================================================
-- Extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS citext;

-- ============================================================
-- Lookup / Reference Tables
-- ============================================================

-- Handler Registry. Needs to be kept in sync when new handlers detected.
CREATE TABLE format_handler (
	id				SMALLINT PRIMARY KEY,
	key				CITEXT NOT NULL UNIQUE,
	handler_class	TEXT NOT NULL
);

-- Types of things in a container
CREATE TABLE entry_type (
	id				SMALLINT PRIMARY KEY,
	name			TEXT NOT NULL UNIQUE
);
INSERT INTO entry_type VALUES
	(0, 'file'),
	(1, 'directory'),
	(2, 'symlink');

-- ============================================================
-- Identity
-- ============================================================

-- UUIDs are global (gateways/SaaS), ids local.
CREATE TABLE organization (
	id				SERIAL PRIMARY KEY,
	global_id		UUID NULL,
	name			CITEXT NOT NULL
);

-- tenants
CREATE TABLE tenant (
	id				SERIAL PRIMARY KEY,
	global_id		UUID NULL,
	org_id			INTEGER NOT NULL REFERENCES organization(id),
	name			CITEXT NOT NULL
);

-- ============================================================
-- Content-Addressed Storage
-- ============================================================

-- global BLOB registry (spans tenants)
-- this allows us to get syndicated BLOB refs (global dedupe)
-- id system-local for better join speed/space
CREATE TABLE blob_ref (
	id				BIGSERIAL PRIMARY KEY,
	content_hash	BYTEA NOT NULL,				-- BLAKE3-128 (16 bytes)
	leaf_size		BIGINT NOT NULL,
	container		BOOLEAN NOT NULL,
	mime_type		TEXT,
	handler			SMALLINT NULL REFERENCES format_handler(id),
	created			TIMESTAMPTZ NOT NULL DEFAULT now(),
	UNIQUE (content_hash, leaf_size, container)
);

-- A BLOB inside a tenant. Same BlobRef in 2 tenants is 2 rows.
CREATE TABLE blob (
	id				BIGSERIAL PRIMARY KEY,
	tenant_id		INTEGER NOT NULL REFERENCES tenant(id),
	blob_ref_id		BIGINT NOT NULL REFERENCES blob_ref(id)
);

-- For BLOBs that have extractable content
CREATE TABLE blob_content (
	blob_id			BIGINT PRIMARY KEY REFERENCES blob(id),
	md				TEXT
);
CREATE INDEX blob_fts ON blob_content USING GIN (to_tsvector('english', md));

-- For BLOBs that are containers
CREATE TABLE container (
	blob_id			BIGINT PRIMARY KEY REFERENCES blob(id),
	entry_count		INTEGER NOT NULL,
	parent_id		BIGINT NULL REFERENCES container(blob_id)
);
CREATE INDEX container_parent_idx ON container(parent_id);

-- All files exist inside a container. This table defines
-- everything as it would appear on filesystem
CREATE TABLE entry (
	id				BIGSERIAL PRIMARY KEY,
	blob_id			BIGINT NOT NULL REFERENCES blob(id),
	container_id	BIGINT NOT NULL REFERENCES container(blob_id),
	entry_type_id	SMALLINT NOT NULL REFERENCES entry_type(id),
	internal_path	TEXT NOT NULL,
	mtime			TIMESTAMPTZ,
	metadata		JSONB,
	UNIQUE (container_id, internal_path)
);

-- ============================================================
-- Cluster
-- ============================================================

-- Self-registered on startup. hostname is the opaque node label
-- (pod name in K8s, hostname in Docker Compose).
CREATE TABLE node (
	id				SERIAL PRIMARY KEY,
	hostname		CITEXT NOT NULL UNIQUE,
	last_seen		TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- Task tables
-- ============================================================

CREATE TABLE task_status (
	id				SMALLINT PRIMARY KEY,
	name			CITEXT NOT NULL UNIQUE
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
	id				SERIAL PRIMARY KEY,
	tenant_id		INTEGER NOT NULL REFERENCES tenant(id),
	parent_id		INTEGER NULL REFERENCES task(id),

	type			CITEXT NOT NULL,			-- extensible: 'ingest.container', etc.
	status			SMALLINT NOT NULL REFERENCES task_status(id),
	priority		SMALLINT DEFAULT 128,

	input			JSONB NULL,
	output			JSONB NULL,					-- holds error or result

	retryable		BOOLEAN,
	retry_count		INTEGER DEFAULT 0,

	executor		INTEGER NULL REFERENCES node(id),

	created_at		TIMESTAMPTZ NOT NULL DEFAULT now(),
	claimed_at		TIMESTAMPTZ,
	completed_at	TIMESTAMPTZ,
	expires_at		TIMESTAMPTZ
);

-- Subtask Deps
CREATE TABLE task_task_dep (
	task_id			INTEGER NOT NULL REFERENCES task(id),
	depends_on		INTEGER NOT NULL REFERENCES task(id),
	PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_dep_depends_on ON task_task_dep(depends_on);

-- Resource / Service Deps (static config, no runtime state)
CREATE TABLE task_resource (
	id				SMALLINT PRIMARY KEY,
	name			CITEXT NOT NULL UNIQUE,
	max_concurrency	INTEGER NULL				-- NULL = unlimited
);

CREATE TABLE task_resource_dep (
	task_id			INTEGER NOT NULL REFERENCES task(id),
	depends_on		SMALLINT NOT NULL REFERENCES task_resource(id),
	PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_resource_dep ON task_resource_dep(depends_on);
