-- V129: Add has_skill_binding flag to mate_agent.
-- NULL  = never configured — getBoundSkillIds returns null (all skills).
-- FALSE = user explicitly cleared  — getBoundSkillIds returns null (all skills).
-- TRUE  = had/has explicit bindings — getBoundSkillIds returns current set (possibly empty).
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS has_skill_binding TINYINT(1) DEFAULT NULL;
