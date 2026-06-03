-- 技能表增加目录 ID 和目录名称字段（平台同步用）
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS category_id VARCHAR(64);
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS category_name VARCHAR(256);
