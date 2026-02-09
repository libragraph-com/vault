-- V3: Add metadata column to blob_content; drop entry.mtime (now in entry.metadata JSONB)
ALTER TABLE blob_content ADD COLUMN IF NOT EXISTS metadata JSONB;
ALTER TABLE entry DROP COLUMN IF EXISTS mtime;
