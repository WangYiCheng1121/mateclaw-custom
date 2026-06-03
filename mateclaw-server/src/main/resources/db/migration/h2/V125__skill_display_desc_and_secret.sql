-- V125: Add display description and secret fields to mate_skill.
-- description_zh — Chinese display description from platform sync, can be NULL.
-- secret        — plaintext API key / credential for skills that need it, can be NULL.
--                  Stored as-is from platform; client runtime can reference it directly.

ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS description_zh TEXT DEFAULT NULL;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS secret TEXT DEFAULT NULL;
