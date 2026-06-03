-- V125: Add display description and secret fields to mate_skill.
-- MySQL version — guards each ALTER with INFORMATION_SCHEMA checks.

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'description_zh'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN description_zh TEXT DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'secret'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN secret TEXT DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
