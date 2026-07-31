-- V130: Add preset_id trace column to mate_agent.
-- Records which platform preset template created this assistant instance.
-- NULL = legacy data or assistant created before this column existed.
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS preset_id VARCHAR(64) DEFAULT NULL;
