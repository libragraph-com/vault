-- V2: Schema fixes for multi-parent containers and uniqueness constraints

-- Remove container.parent_id (multi-parent via entries table)
ALTER TABLE container DROP COLUMN parent_id;
DROP INDEX IF EXISTS container_parent_idx;

-- Prevent duplicate tenant ownership of same blob_ref
ALTER TABLE blob ADD CONSTRAINT blob_tenant_ref_unique UNIQUE (tenant_id, blob_ref_id);

-- Unique org+name for tenants (prevents test collisions, enables ON CONFLICT upserts)
ALTER TABLE tenant ADD CONSTRAINT tenant_org_name_unique UNIQUE (org_id, name);

-- Unique org name
ALTER TABLE organization ADD CONSTRAINT org_name_unique UNIQUE (name);
