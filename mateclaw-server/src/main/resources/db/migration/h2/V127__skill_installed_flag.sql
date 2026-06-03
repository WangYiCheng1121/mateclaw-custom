-- 技能表增加 installed 字段，区分"安装"和"启用/禁用"两个维度
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS installed BOOLEAN NOT NULL DEFAULT FALSE;
-- 已有数据兼容：内置技能和已启用的技能标记为已安装
UPDATE mate_skill SET installed = TRUE WHERE builtin = TRUE OR enabled = TRUE;
