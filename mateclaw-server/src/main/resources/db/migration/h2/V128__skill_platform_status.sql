-- 技能表增加 platform_status 字段，客户端本地跟踪平台端可见性
-- NULL = 正常  /  "REMOVED" = 平台已移除（禁用/删除/取消分配）
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS platform_status VARCHAR(32) DEFAULT NULL;
